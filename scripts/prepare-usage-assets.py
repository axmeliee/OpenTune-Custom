"""Give dashboard assets content-derived URLs so updates bypass stale caches."""
from hashlib import sha256
import json
import shutil
from urllib.parse import urlparse
from pathlib import Path

source = Path(__file__).resolve().parents[1] / 'usage-api' / 'dashboard'
root = source.parent / 'public' / 'usage'
root.mkdir(parents=True, exist_ok=True)
assets = {}
def fingerprint(name, content):
    source = Path(name)
    digest = sha256(content).hexdigest()
    filename = f'{source.stem}.{digest[:12]}{source.suffix}'
    (root / filename).write_bytes(content)
    assets[filename] = digest
    return filename

session = fingerprint('session.mjs', (source / 'session.mjs').read_bytes())
model = fingerprint('model.mjs', (source / 'model.mjs').read_bytes())
script = (source / 'dashboard.js').read_text()
assert "'./model.mjs'" in script, 'Update the module rewrite when the entrypoint changes'
script = script.replace("'./model.mjs'", f"'./{model}'").replace("'./session.mjs'", f"'./{session}'")
js = fingerprint('dashboard.js', script.encode())
css = fingerprint('dashboard.css', (source / 'dashboard.css').read_bytes())
html = (source / 'index.html').read_text()
assert 'href="dashboard.css"' in html and 'src="dashboard.js"' in html, 'Expected unprocessed dashboard HTML'
html = html.replace('href="dashboard.css"', f'href="{css}"').replace('src="dashboard.js"', f'src="{js}"')
(root / 'index.html').write_text(html)
(root / 'assets.json').write_text(json.dumps({'assets': assets, 'entrypoints': [css, js]}))
print('Prepared dashboard assets:', css, js, model)

# GitHub Pages serves the interface; only the authenticated Worker returns private data.
site = source.parents[1] / 'site'
pages = site / 'usage'
pages.mkdir(parents=True, exist_ok=True)
api = json.loads((site / 'usage-config.json').read_text())['apiBase'].rstrip('/')
parsed = urlparse(api)
assert parsed.scheme == 'https' and not parsed.username and not parsed.password and not parsed.query and not parsed.fragment
assert parsed.path in ('', '/')
policy = f"default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src {api} https://api.github.com; img-src 'self'; base-uri 'none'; form-action 'none'"
page = html.replace('<html lang="en">', '<html lang="en" data-host="pages">')
page = page.replace('<meta charset="utf-8">', f'<meta charset="utf-8"><meta http-equiv="Content-Security-Policy" content="{policy}"><meta name="usage-api" content="{api}">')
page = page.replace('<main id="owner-dashboard">', (source / 'login.html').read_text() + '<main id="owner-dashboard" hidden>')
page = page.replace('</body>', '<noscript>Enable JavaScript to sign in to your usage dashboard.</noscript></body>')
(pages / 'index.html').write_text(page)
for name in [*assets, 'assets.json']:
    shutil.copyfile(root / name, pages / name)
print('Prepared GitHub Pages login and dashboard.')
