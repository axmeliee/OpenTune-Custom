"""Give dashboard assets content-derived URLs so updates bypass stale caches."""
from hashlib import sha256
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1] / 'site' / 'usage'
assets = {}
def fingerprint(name, content):
    source = Path(name)
    digest = sha256(content).hexdigest()
    filename = f'{source.stem}.{digest[:12]}{source.suffix}'
    (root / filename).write_bytes(content)
    assets[filename] = digest
    return filename

model = fingerprint('model.mjs', (root / 'model.mjs').read_bytes())
script = (root / 'dashboard.js').read_text()
assert "'./model.mjs'" in script, 'Update the module rewrite when the entrypoint changes'
script = script.replace("'./model.mjs'", f"'./{model}'")
js = fingerprint('dashboard.js', script.encode())
css = fingerprint('dashboard.css', (root / 'dashboard.css').read_bytes())
html = (root / 'index.html').read_text()
assert 'href="dashboard.css"' in html and 'src="dashboard.js"' in html, 'Expected unprocessed dashboard HTML'
html = html.replace('href="dashboard.css"', f'href="{css}"').replace('src="dashboard.js"', f'src="{js}"')
(root / 'index.html').write_text(html)
(root / 'assets.json').write_text(json.dumps({'assets': assets, 'entrypoints': [css, js]}))
print('Prepared dashboard assets:', css, js, model)
