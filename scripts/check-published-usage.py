"""Read-only check: Pages no longer serves the dashboard."""
from time import sleep, time
from urllib.request import Request, urlopen
base = 'https://justshivv.github.io/Opentune/usage/'
for attempt in range(6):
    try:
        with urlopen(Request(base + '?private=' + str(int(time())), headers={'Cache-Control':'no-cache'}), timeout=20) as response:
            html = response.read().decode()
        assert 'Private analytics' in html and 'Owner sign-in' in html
        assert 'dashboard.' not in html and 'id="chart"' not in html
        print('Verified public Pages contains only the owner sign-in link.')
        break
    except Exception:
        if attempt == 5: raise
        sleep(10)
