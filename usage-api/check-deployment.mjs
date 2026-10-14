// Read-only smoke test: no synthetic reports or credentials in output.
import assert from 'node:assert/strict';
const base = 'https://opentune-usage.create-a-new-site-with-sites.workers.dev';
for (const path of ['/usage/', '/usage/index.html', '/usage/assets.json', '/v1/summary', '/v1/analytics?from=2026-01-01&to=2026-01-01']) {
  const response = await fetch(base + path, {redirect:'manual',signal:AbortSignal.timeout(20000)});
  if (response.status === 302 && path.startsWith('/usage/')) {
    assert.ok(new URL(response.headers.get('location')).hostname.endsWith('.cloudflareaccess.com'));
  } else {
    assert.equal(response.status,401,`Unauthenticated route must be locked: ${path}`);
    assert.match(response.headers.get('cache-control'),/no-store/);
  }
}
const preflight = await fetch(base + '/v1/activity', {method:'OPTIONS', headers:{Origin:'https://justshivv.github.io'},signal:AbortSignal.timeout(20000)});
assert.equal(preflight.status,204);
console.log('Verified anonymous data requests are blocked and reporting preflight remains available. Google login must be checked by the owner.');
