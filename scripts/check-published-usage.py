"""Read-only check: Pages no longer serves the dashboard."""
from time import sleep, time
from pathlib import Path
from hashlib import sha256
from urllib.request import Request, urlopen
base = 'https://justshivv.github.io/Opentune/usage/'
for attempt in range(6):
    try:
        with urlopen(Request(base + '?private=' + str(int(time())), headers={'Cache-Control':'no-cache'}), timeout=20) as response:
            html = response.read().decode()
        assert 'Private analytics' in html and 'Owner sign-in' in html
        assert 'dashboard.' not in html and 'id="chart"' not in html
        home = 'https://justshivv.github.io/Opentune/'
        with urlopen(Request(home + '?preview=' + str(int(time())), headers={'Cache-Control':'no-cache'}), timeout=20) as response:
            metadata = response.read().decode()
        image = 'img/share-waveform-2026.png'
        assert f'content="{home}{image}"' in metadata and 'summary_large_image' in metadata
        with urlopen(home + image, timeout=20) as response:
            published = response.read()
        local = Path(__file__).resolve().parents[1] / 'site' / image
        assert sha256(published).digest() == sha256(local.read_bytes()).digest()
        print('Verified private Pages entry and exact social preview image published.')
        break
    except Exception:
        if attempt == 5: raise
        sleep(10)
