import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from datetime import date
from unittest.mock import patch

MODULE = Path(__file__).resolve().parents[1] / "pipeline.py"
spec = importlib.util.spec_from_file_location("pipeline", MODULE)
pipeline = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pipeline)
SHA = "a" * 40
GRADLE = r'''
android {
    compileSdk = 37
    defaultConfig {
        applicationId = "com.opentune"
        versionCode = 42
        versionName = "0.4.18"
        val changelog = rootProject.file("fastlane/metadata/android/en-US/changelogs/$versionCode.txt")
        buildConfigField("String", "CHANGELOG", changelog.replace("\n", "\\n"))
    }
    val releaseKeystore = System.getenv("OPENTUNE_KEYSTORE")?.let(::file)?.takeIf { it.exists() }
    signingConfig = if (unsignedRelease) null else (signingConfigs.findByName("release") ?: signingConfigs.getByName("debug"))
}
'''


class MetadataTest(unittest.TestCase):
    def test_lint_pin_preserves_properties_and_does_not_duplicate(self):
        original = "# upstream settings\nandroid.useAndroidX=true\n"
        pinned = pipeline.pin_lint(original)
        self.assertTrue(pinned.startswith(original))
        self.assertEqual(1, pinned.count("android.experimental.lint.version=9.3.3"))
        self.assertEqual(pinned, pipeline.pin_lint(pinned))
        self.assertEqual("android.experimental.lint.version=9.3.3\n",
                         pipeline.pin_lint("android.experimental.lint.version : 8.10.0\n"))
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            pipeline.pin_lint("android.experimental.lint.version=8.1\n"
                              "android.experimental.lint.version=8.2\n")

    def test_rejects_draft_prerelease_and_non_semver(self):
        for release in [dict(tag_name="v1.2.3", draft=True),
                        dict(tag_name="v1.2.3", prerelease=True),
                        dict(tag_name="v1.2.3-rc1"), dict(tag_name="v1.2")]:
            with self.subTest(release=release), self.assertRaises(ValueError):
                pipeline.stable_version(release)
        self.assertEqual("0.4.18", pipeline.stable_version(dict(tag_name="v0.4.18")))

    def test_transforms_identity_preserves_changelog_and_requires_key(self):
        text, code = pipeline.transform_gradle(GRADLE, "0.4.18")
        self.assertEqual(4201, code)
        self.assertIn('applicationId = "com.opentune.custom"', text)
        self.assertIn('versionName = "0.4.18-custom.1"', text)
        self.assertIn('changelogs/42.txt', text)
        self.assertIn('compileSdkVersion("android-37.0")', text)
        self.assertIn(r'.replace("\r\n", "\n").replace("\r", "\n")', text)
        self.assertIn('require(releaseKeystore != null)', text)
        self.assertIn('OPENTUNE_KEY_PASSWORD', text)
        self.assertIn('gradle.taskGraph.whenReady', text)
        self.assertIn('allTasks.any { it.path.startsWith(":app:") && it.name.contains("Release", ignoreCase = true) }', text)
        self.assertNotIn('startParameter.taskNames', text)
        self.assertIn('signingConfig = signingConfigs.findByName("release")', text)
        self.assertNotIn('getByName("debug")', text)
        self.assertNotIn('if (unsignedRelease)', text)

    def test_rejects_tag_gradle_version_mismatch_or_unknown_metadata(self):
        for text in (GRADLE.replace('"0.4.18"', '"0.4.19"'),
                     GRADLE.replace('compileSdk = 37', 'compileSdk = 38'),
                     GRADLE + '\nversionCode = 99\n'):
            with self.subTest(text=text), self.assertRaises(ValueError):
                pipeline.transform_gradle(text, "0.4.18")


class PreparationTest(unittest.TestCase):
    def test_fresh_patch_prepare_metadata_provenance_and_no_upstream_workflows(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            source = directory / "source"
            (source / "app").mkdir(parents=True)
            (source / "app/build.gradle.kts").write_text(GRADLE)
            (source / "gradle.properties").write_text("android.useAndroidX=true\n")
            (source / "example.txt").write_text("original\n")
            (source / ".github/workflows").mkdir(parents=True)
            (source / ".github/workflows/release.yml").write_text("upstream")
            (source / "LICENSE").write_text("GPL retained")
            patch_file = directory / "custom.patch"
            patch_file.write_text("diff --git a/example.txt b/example.txt\n"
                                  "--- a/example.txt\n+++ b/example.txt\n"
                                  "@@ -1 +1 @@\n-original\n+custom\n")
            metadata = directory / "plan.json"
            metadata.write_text(json.dumps(dict(upstream_sha=SHA,
                patch_sha=pipeline.digest(patch_file), version="0.4.18",
                upstream_tag="v0.4.18", tag="v0.4.18-custom.1")))
            original_run = pipeline.run
            def run(*args, **kwargs):
                if args[:3] == ("git", "rev-parse", "HEAD"):
                    return SHA
                if args[:3] == ("git", "status", "--porcelain"):
                    return ""
                return original_run(*args, **kwargs)
            with patch.object(pipeline, "run", side_effect=run), patch.dict(os.environ, {
                    "GITHUB_ACTIONS": "true", "GITHUB_SHA": "c" * 40,
                    "OPENTUNE_AUTOMATION_SHA": "b" * 40}):
                pipeline.prepare(source, patch_file, metadata)
            self.assertEqual("custom\n", (source / "example.txt").read_text())
            self.assertFalse((source / ".github/workflows").exists())
            self.assertEqual("GPL retained", (source / "LICENSE").read_text())
            self.assertTrue((source / "custom/pipeline.py").exists())
            self.assertEqual(4201, json.loads((source / "custom/provenance.json").read_text())["version_code"])
            self.assertIn('versionCode = 4201', (source / "app/build.gradle.kts").read_text())
            self.assertIn('android.experimental.lint.version=9.3.3',
                          (source / "gradle.properties").read_text())
            self.assertIn('android.useAndroidX=true', (source / "gradle.properties").read_text())
            self.assertEqual("9.3.3", json.loads((source / "custom/provenance.json").read_text())["lint_version"])
            self.assertEqual("b" * 40, json.loads((source / "custom/provenance.json").read_text())["automation_commit"])

    def test_actions_provenance_rejects_missing_or_invalid_actual_checkout_sha(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            metadata = directory / "plan.json"
            metadata.write_text("{}")
            for sha in ("", "not-a-sha", "a" * 39):
                with self.subTest(sha=sha), patch.dict(os.environ, {
                        "GITHUB_ACTIONS": "true", "GITHUB_SHA": "c" * 40,
                        "OPENTUNE_AUTOMATION_SHA": sha}), patch.object(pipeline, "run") as runner:
                    with self.assertRaisesRegex(ValueError, "exact automation checkout"):
                        pipeline.prepare(directory, directory / "app.patch", metadata)
                    runner.assert_not_called()

    def test_wrong_source_and_patch_hash_fail_before_mutation(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            patch_file = directory / "patch"
            patch_file.write_text("patch")
            metadata = directory / "plan.json"
            metadata.write_text(json.dumps(dict(upstream_sha=SHA, patch_sha="wrong")))
            with patch.object(pipeline, "run", return_value="b" * 40) as runner:
                with self.assertRaisesRegex(ValueError, "exact upstream"):
                    pipeline.prepare(directory, patch_file, metadata)
                self.assertEqual(1, runner.call_count)
            with patch.object(pipeline, "run", side_effect=[SHA, ""]) as runner:
                with self.assertRaisesRegex(ValueError, "Patch changed"):
                    pipeline.prepare(directory, patch_file, metadata)
                self.assertEqual(2, runner.call_count)

    def test_real_git_conflict_refuses_patch_and_keeps_source(self):
        # git apply works without a Git index; no checkout/index is changed by this test.
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            target = directory / "example.txt"
            target.write_text("changed upstream\n")
            patch_file = directory / "custom.patch"
            patch_file.write_text("diff --git a/example.txt b/example.txt\n"
                                  "--- a/example.txt\n+++ b/example.txt\n"
                                  "@@ -1 +1 @@\n-original\n+custom\n")
            result = subprocess.run(["git", "apply", "--check", str(patch_file)],
                                    cwd=directory, capture_output=True)
            self.assertNotEqual(0, result.returncode)
            self.assertEqual("changed upstream\n", target.read_text())


class SigningTest(unittest.TestCase):
    def test_missing_any_secret_never_writes_keystore(self):
        with tempfile.TemporaryDirectory() as temp:
            destination = Path(temp) / "release.keystore"
            for missing in pipeline.SECRETS:
                env = {name: "a2V5" for name in pipeline.SECRETS if name != missing}
                with self.subTest(missing=missing), patch.dict(os.environ, env, clear=True):
                    with self.assertRaisesRegex(ValueError, "Missing required signing"):
                        pipeline.signing(destination)
                    self.assertFalse(destination.exists())

    def test_valid_key_written_once_invalid_base64_refused(self):
        with tempfile.TemporaryDirectory() as temp:
            destination = Path(temp) / "release.keystore"
            env = {name: "a2V5" for name in pipeline.SECRETS}
            with patch.dict(os.environ, env, clear=True), \
                    patch.object(pipeline, "key_fingerprint", return_value=pipeline.ORIGINAL_CERT_SHA256):
                pipeline.signing(destination)
                self.assertEqual(b"key", destination.read_bytes())
                with self.assertRaises(FileExistsError):
                    pipeline.signing(destination)
            destination.unlink()
            env[pipeline.SECRETS[0]] = "invalid!"
            with patch.dict(os.environ, env, clear=True), self.assertRaises(ValueError):
                pipeline.signing(destination)
            self.assertFalse(destination.exists())

    def test_wrong_certificate_is_refused_and_deleted(self):
        with tempfile.TemporaryDirectory() as temp:
            destination = Path(temp) / "release.keystore"
            env = {name: "a2V5" for name in pipeline.SECRETS}
            with patch.dict(os.environ, env, clear=True), \
                    patch.object(pipeline, "key_fingerprint", return_value="0" * 64):
                with self.assertRaisesRegex(ValueError, "differs from the original"):
                    pipeline.signing(destination)
            self.assertFalse(destination.exists())


class ReleaseTest(unittest.TestCase):
    def assets(self, tag):
        return dict(draft=False, prerelease=False, assets=[dict(name=name, size=1) for name in
                    (f"OpenTune-{tag}-universal.apk", "SHA256SUMS.txt", "provenance.json")])

    def test_valid_release_noop_and_damaged_asset_rebuild_refusal(self):
        tag = "v0.4.18-custom.1"
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            apk = directory / f"OpenTune-{tag}-universal.apk"
            apk.write_bytes(b"signed apk")
            (directory / "SHA256SUMS.txt").write_text(f"{pipeline.digest(apk)}  {apk.name}\n")
            (directory / "provenance.json").write_text(json.dumps(dict(
                upstream_commit=SHA, patch_sha256="patch", custom_tag=tag, source_commit="b" * 40)))
            with patch.object(pipeline, "run"):
                self.assertTrue(pipeline.release_complete(self.assets(tag), tag, SHA, "patch", directory))
                apk.write_bytes(b"damaged apk")
                self.assertFalse(pipeline.release_complete(self.assets(tag), tag, SHA, "patch", directory))
                self.assertFalse(pipeline.release_complete(self.assets(tag), tag, SHA, "other patch", directory))
                release = self.assets(tag)
                release["assets"].pop()
                self.assertFalse(pipeline.release_complete(release, tag, SHA, "patch", directory))

    def test_plan_verified_existing_release_skips_and_incomplete_release_fails(self):
        tag = "v0.4.18-custom.1"
        release = dict(tag_name=tag, draft=False)
        responses = [dict(tag_name="v0.4.18"), dict(sha=SHA), [release], dict(sha="b" * 40)]
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            patch_file = directory / "app.patch"
            patch_file.write_text("patch")
            previous = Path.cwd()
            os.chdir(directory)
            try:
                def valid_release(*args):
                    (args[-1] / "provenance.json").write_text(json.dumps(dict(source_commit="b" * 40)))
                    return True
                with patch.object(pipeline, "api", side_effect=responses), \
                        patch.object(pipeline, "release_complete", side_effect=valid_release):
                    pipeline.plan(patch_file)
                    self.assertEqual("true", json.loads(Path("plan.json").read_text())["skip"])
                with patch.object(pipeline, "api", side_effect=responses[:3]), \
                        patch.object(pipeline, "release_complete", return_value=False):
                    with self.assertRaisesRegex(ValueError, "incomplete"):
                        pipeline.plan(patch_file)
            finally:
                os.chdir(previous)


class KeepaliveTest(unittest.TestCase):
    def test_due_dates_and_future_state(self):
        self.assertTrue(pipeline.keepalive_due(None, date(2026, 10, 22)))
        state = dict(last_keepalive="2026-10-22")
        self.assertFalse(pipeline.keepalive_due(state, date(2026, 11, 18)))
        self.assertTrue(pipeline.keepalive_due(state, date(2026, 11, 19)))
        with self.assertRaisesRegex(ValueError, "future"):
            pipeline.keepalive_due(state, date(2026, 10, 21))

    def test_monthly_commit_only_state_no_force_and_same_day_noop(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            def response(*args, **kwargs):
                if args[:3] == ("git", "branch", "--show-current"):
                    return "custom"
                if args[:4] == ("git", "diff", "--cached", "--name-only"):
                    return ".github/monitor-state.json"
                return ""
            with patch.dict(os.environ, dict(GITHUB_ACTIONS="true")), \
                    patch.object(pipeline, "run", side_effect=response) as runner:
                self.assertTrue(pipeline.keepalive("custom", directory, date(2026, 10, 22)))
                calls = [call.args for call in runner.call_args_list]
                self.assertIn(("git", "add", "--", ".github/monitor-state.json"), calls)
                self.assertIn(("git", "push", "origin", "HEAD:refs/heads/custom"), calls)
                self.assertFalse(any("--force" in arg or arg.startswith("+") for call in calls for arg in call))
                runner.reset_mock()
                self.assertFalse(pipeline.keepalive("custom", directory, date(2026, 10, 22)))
                runner.assert_not_called()

    def test_local_use_and_dirty_checkout_refused_before_writing(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            with patch.dict(os.environ, dict(GITHUB_ACTIONS="false")), self.assertRaises(ValueError):
                pipeline.keepalive("custom", directory)
            with patch.dict(os.environ, dict(GITHUB_ACTIONS="true")), \
                    patch.object(pipeline, "run", side_effect=["custom", " M unrelated.txt"]):
                with self.assertRaisesRegex(ValueError, "clean checkout"):
                    pipeline.keepalive("custom", directory)
            self.assertFalse((directory / ".github/monitor-state.json").exists())


if __name__ == "__main__":
    unittest.main()
