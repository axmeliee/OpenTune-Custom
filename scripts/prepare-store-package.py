"""Export current metadata and a pinned F-Droid submission draft. Never publishes."""
import argparse
import json
import re
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def prepare(commit, output):
    if not re.fullmatch(r'[0-9a-f]{40}', commit):
        raise ValueError('Use the full 40-character source commit SHA')
    gradle = (ROOT / 'app/build.gradle.kts').read_text()
    version = re.search(r'versionName = "([^"]+)"', gradle)[1]
    code = int(re.search(r'versionCode = (\d+)', gradle)[1])
    metadata = ROOT / 'fastlane/metadata/android'
    locale = metadata / 'en-US'
    for name, maximum in [('title.txt', 30), ('short_description.txt', 80), ('full_description.txt', 4000)]:
        value = (locale / name).read_text().strip()
        if not value or len(value) > maximum:
            raise ValueError(f'{name} must contain 1–{maximum} characters')
    output.mkdir(parents=True, exist_ok=True)
    shutil.copytree(metadata, output / 'fastlane/metadata/android', dirs_exist_ok=True)
    template = (ROOT / 'distribution/fdroid/com.opentune.yml.in').read_text()
    for key, value in {'VERSION': version, 'VERSION_CODE': str(code), 'COMMIT': commit}.items():
        template = template.replace('@' + key + '@', value)
    (output / 'fdroid').mkdir(exist_ok=True)
    (output / 'fdroid/com.opentune.yml').write_text(template)
    (output / 'release.json').write_text(json.dumps({
        'name': 'OpenTune', 'packageName': 'com.opentune', 'versionName': version,
        'versionCode': code, 'sourceCommit': commit,
        'website': 'https://justshivv.github.io/Opentune/',
        'privacyPolicy': 'https://justshivv.github.io/Opentune/privacy/',
        'sourceCode': 'https://github.com/justshivv/Opentune',
        'releasePage': 'https://github.com/justshivv/Opentune/releases/latest',
        'fdroidStatus': 'Submission draft; source build and scanner review required',
    }, indent=2) + '\n')
    shutil.copy(ROOT / 'docs/publishing.md', output / 'PUBLISHING.md')
    print(f'Store metadata prepared for {version} ({code}) at {output}')

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--commit', required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/store-package')
    args = parser.parse_args()
    prepare(args.commit, args.output)
