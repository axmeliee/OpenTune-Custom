"""Read-only check that Pages serves this build's HTML and exact assets."""
from hashlib import sha256
import json
from pathlib import Path
from time import sleep
from urllib.request import Request, urlopen

root = Path(__file__).resolve().parents[1] / 'site' / 'usage'
manifest = json.loads((root / 'assets.json').read_text())
base = 'https://justshivv.github.io/Opentune/usage/'
version = sha256((root / 'assets.json').read_bytes()).hexdigest()[:12]
def get(path):
    request = Request(base + path, headers={'Cache-Control': 'no-cache', 'User-Agent': 'OpenTune-deployment-check'})
    with urlopen(request, timeout=20) as response:
        return response.read()

for attempt in range(6):
    try:
        html = get('?v=' + version).decode()
        for name in manifest['entrypoints']:
            assert f'"{name}"' in html, f'Published HTML still points to a different build: {name}'
        for name, expected in manifest['assets'].items():
            assert sha256(get(name)).hexdigest() == expected, f'Published asset differs: {name}'
        print('Verified live dashboard HTML, stylesheet, script and module:', base + '?v=' + version)
        break
    except Exception as error:
        if attempt == 5:
            raise
        print(f'Waiting for Pages propagation ({attempt + 1}/6): {error}')
        sleep(10)
