"""Small, fail-closed stable release pipeline. Python standard library only."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

UPSTREAM = "justshivv/Opentune"
CUSTOM = "axmeliee/OpenTune-Custom"
ORIGINAL_CERT_SHA256 = "e8ce6f3b61626581556e2b245f98b9e4aa4c8e0d59dd457d6f5fd3a75c9f9483"
SECRETS = ("OPENTUNE_KEYSTORE_BASE64", "OPENTUNE_KEYSTORE_PASSWORD",
           "OPENTUNE_KEY_ALIAS", "OPENTUNE_KEY_PASSWORD")


def run(*args, cwd=None):
    return subprocess.check_output(args, cwd=cwd, text=True).strip()


def api(path):
    return json.loads(run("gh", "api", path))


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def stable_version(release):
    tag = release.get("tag_name", "")
    if release.get("draft") or release.get("prerelease") or not re.fullmatch(r"v\d+\.\d+\.\d+", tag):
        raise ValueError("Expected a published stable upstream vMAJOR.MINOR.PATCH release")
    return tag[1:]


def release_complete(release, tag, upstream_sha, patch_sha, directory):
    """Verify actual assets, not merely an existing release title/tag."""
    if release.get("draft") or release.get("prerelease"):
        return False
    apk = f"OpenTune-{tag}-universal.apk"
    required = {apk, "SHA256SUMS.txt", "provenance.json"}
    assets = release.get("assets", [])
    if not required.issubset({a["name"] for a in assets if a.get("size", 0) > 0}):
        return False
    run("gh", "release", "download", tag, "--repo", CUSTOM,
        "--dir", str(directory), "--pattern", apk, "--pattern", "SHA256SUMS.txt",
        "--pattern", "provenance.json")
    provenance = json.loads((directory / "provenance.json").read_text())
    sums = (directory / "SHA256SUMS.txt").read_text().splitlines()
    expected = f"{digest(directory / apk)}  {apk}"
    return (expected in sums and provenance.get("upstream_commit") == upstream_sha
            and provenance.get("patch_sha256") == patch_sha
            and provenance.get("custom_tag") == tag
            and bool(re.fullmatch(r"[0-9a-f]{40}", provenance.get("source_commit", ""))))


def plan(patch):
    upstream = api(f"repos/{UPSTREAM}/releases/latest")
    version = stable_version(upstream)
    tag = f"v{version}-custom.1"
    # Resolve annotated as well as lightweight tags to the actual build commit.
    sha = api(f"repos/{UPSTREAM}/commits/{upstream['tag_name']}")["sha"]
    if not re.fullmatch(r"[0-9a-f]{40}", sha):
        raise ValueError("Invalid resolved upstream commit")
    patch_sha = digest(patch)
    # REST list returns drafts to authenticated callers; HTTP failures are not 'no release'.
    releases = api(f"repos/{CUSTOM}/releases?per_page=100")
    existing = next((r for r in releases if r["tag_name"] == tag), None)
    skip = False
    if existing and not existing.get("draft"):
        with tempfile.TemporaryDirectory() as temp:
            skip = release_complete(existing, tag, sha, patch_sha, Path(temp))
            if skip:
                source = api(f"repos/{CUSTOM}/commits/{tag}")["sha"]
                provenance = json.loads((Path(temp) / "provenance.json").read_text())
                if provenance["source_commit"] != source:
                    raise ValueError("Published source tag does not match provenance")
        if not skip:
            raise ValueError("Existing published release is incomplete or differs; inspect it before retrying")
    result = dict(version=version, upstream_tag=upstream["tag_name"], upstream_sha=sha,
                  tag=tag, patch_sha=patch_sha, skip=str(skip).lower())
    Path("plan.json").write_text(json.dumps(result, indent=2) + "\n")
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a") as output:
            for key, value in result.items():
                output.write(f"{key}={value}\n")
    print(json.dumps(result, indent=2))


def replace_one(text, pattern, replacement):
    updated, count = re.subn(pattern, replacement, text)
    if count != 1:
        raise ValueError(f"Expected exactly one metadata field: {pattern}; got {count}")
    return updated


def transform_gradle(text, version):
    name = re.search(r'versionName\s*=\s*"([^\"]+)"', text)
    code = re.search(r'versionCode\s*=\s*(\d+)', text)
    if not name or name[1] != version or not code:
        raise ValueError("Upstream Gradle version does not match the stable release")
    upstream_code = int(code[1])
    custom_code = upstream_code * 100 + 1
    if not 0 < custom_code <= 2100000000:
        raise ValueError("Custom versionCode outside Android range")
    text = replace_one(text, r'applicationId\s*=\s*"com\.opentune"', 'applicationId = "com.opentune.custom"')
    text = replace_one(text, r'versionCode\s*=\s*\d+', f"versionCode = {custom_code}")
    text = replace_one(text, r'versionName\s*=\s*"[^\"]+"', f'versionName = "{version}-custom.1"')
    # android-37.0 is the SDK package installed on CI and this workstation.
    text = replace_one(text, r'compileSdk\s*=\s*37\b', 'compileSdkVersion("android-37.0")')
    # Keep What's new reading the upstream changelog despite the custom versionCode.
    text = replace_one(text, r'changelogs/\$versionCode\.txt', f"changelogs/{upstream_code}.txt")
    # Gradle readText() preserves CRLF on Windows; escape CR as well as LF.
    needle = '.replace("\\n", "\\\\n")'
    if text.count(needle) != 1:
        raise ValueError("Cannot locate changelog escaping")
    text = text.replace(needle, '.replace("\\r\\n", "\\n").replace("\\r", "\\n")' + needle)
    # Even manual release builds from the source tag must refuse debug signing.
    needle = 'val releaseKeystore = System.getenv("OPENTUNE_KEYSTORE")?.let(::file)?.takeIf { it.exists() }'
    if text.count(needle) != 1:
        raise ValueError("Cannot locate upstream signing configuration")
    guard = '''
    gradle.taskGraph.whenReady {
        if (allTasks.any { it.path.startsWith(":app:") && it.name.contains("Release", ignoreCase = true) }) {
            require(releaseKeystore != null) { "Custom release requires the original OPENTUNE_KEYSTORE" }
            listOf("OPENTUNE_KEYSTORE_PASSWORD", "OPENTUNE_KEY_ALIAS", "OPENTUNE_KEY_PASSWORD").forEach {
                require(!System.getenv(it).isNullOrBlank()) { "Missing custom signing secret: $it" }
            }
        }
    }
'''
    # Aggregate assemble/build tasks also include release tasks. Never retain the
    # upstream debug-key or unsignedRelease escape hatch in a custom source tag.
    inherited_signing = 'signingConfig = if (unsignedRelease) null else (signingConfigs.findByName("release") ?: signingConfigs.getByName("debug"))'
    text = replace_one(text, re.escape(inherited_signing),
                       'signingConfig = signingConfigs.findByName("release")')
    return text.replace(needle, needle + guard), custom_code


def prepare(source, patch, metadata):
    plan_data = json.loads(metadata.read_text())
    actual = run("git", "rev-parse", "HEAD", cwd=source)
    if actual != plan_data["upstream_sha"]:
        raise ValueError("Source checkout is not the exact upstream release commit")
    if run("git", "status", "--porcelain", cwd=source):
        raise ValueError("Upstream checkout must be clean")
    if digest(patch) != plan_data["patch_sha"]:
        raise ValueError("Patch changed after release planning")
    run("git", "apply", "--check", str(patch.resolve()), cwd=source)
    run("git", "apply", str(patch.resolve()), cwd=source)
    gradle = source / "app/build.gradle.kts"
    text, code = transform_gradle(gradle.read_text(encoding="utf-8"), plan_data["version"])
    gradle.write_text(text, encoding="utf-8", newline="\n")
    # No upstream publication workflow may execute on the generated custom source tag.
    shutil.rmtree(source / ".github/workflows", ignore_errors=True)
    provenance = dict(upstream_repository=f"https://github.com/{UPSTREAM}",
                      upstream_tag=plan_data["upstream_tag"], upstream_commit=actual,
                      patch_sha256=plan_data["patch_sha"], custom_tag=plan_data["tag"],
                      application_id="com.opentune.custom", version_code=code,
                      automation_repository=f"https://github.com/{CUSTOM}",
                      automation_commit=os.environ.get("GITHUB_SHA", "local-unpublished"))
    custom = source / "custom"
    custom.mkdir(exist_ok=True)
    shutil.copy2(__file__, custom / "pipeline.py")
    shutil.copytree(Path(__file__).parent / "tests", custom / "tests",
                    ignore=shutil.ignore_patterns("__pycache__"), dirs_exist_ok=True)
    build_notes = Path(__file__).parent.parent / "CUSTOM-BUILD.md"
    if build_notes.is_file():
        shutil.copy2(build_notes, source / "CUSTOM-BUILD.md")
    (custom / "provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
    (custom / "app.patch").write_bytes(patch.read_bytes())
    (custom / "README.md").write_text(
        "# OpenTune Custom source\n\nBased on OpenTune by its upstream contributors. "
        "Original copyright notices and GPL LICENSE are retained. This exact source tree "
        "produced the APK for this tag. Custom changes add persistent New songs first "
        "playlist insertion, a separate updater channel and custom package/version metadata.\n\n"
        "See provenance.json and app.patch here. Rebuild with Java 21, Android SDK "
        "platforms;android-37.0 and build-tools;36.0.0: "
        "`./gradlew -PuniversalOnly=true assembleRelease`. Supply the original signing "
        "keystore and OPENTUNE signing environment variables; the private key is never "
        "included in source. Debug builds use `./gradlew assembleDebug`.\n")


def signing(destination):
    missing = [name for name in SECRETS if not os.environ.get(name, "").strip()]
    if missing:
        raise ValueError("Missing required signing secrets: " + ", ".join(missing))
    data = base64.b64decode(os.environ[SECRETS[0]], validate=True)
    if not data:
        raise ValueError("Empty signing keystore")
    descriptor = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "wb") as stream:
        stream.write(data)
    if key_fingerprint(destination) != ORIGINAL_CERT_SHA256:
        destination.unlink()
        raise ValueError("Signing certificate differs from the original custom APK")


def key_fingerprint(keystore):
    certificate = subprocess.check_output([
        "keytool", "-exportcert", "-keystore", str(keystore),
        "-storepass:env", "OPENTUNE_KEYSTORE_PASSWORD",
        "-alias", os.environ["OPENTUNE_KEY_ALIAS"]])
    return hashlib.sha256(certificate).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("plan")
    p.add_argument("patch", type=Path)
    p = sub.add_parser("prepare")
    p.add_argument("source", type=Path)
    p.add_argument("patch", type=Path)
    p.add_argument("metadata", type=Path)
    p = sub.add_parser("signing")
    p.add_argument("destination", type=Path)
    args = parser.parse_args()
    if args.command == "plan":
        plan(args.patch)
    elif args.command == "prepare":
        prepare(args.source, args.patch, args.metadata)
    else:
        signing(args.destination)


if __name__ == "__main__":
    main()
