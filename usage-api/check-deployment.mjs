// Read-only smoke test: no synthetic reports or credentials in output.
import assert from 'node:assert/strict';
const base = 'https://opentune-usage.create-a-new-site-with-sites.workers.dev';
for (const path of ['/usage/', '/usage/index.html', '/usage/assets.json', '/v1/summary', '/v1/analytics?from=2026-01-01&to=2026-01-01']) {
  const response = await fetch(base + path, {signal:AbortSignal.timeout(20000)});
  assert.equal(response.status,401,`Unauthenticated route must be locked: ${path}`);
  assert.match(response.headers.get('cache-control'),/no-store/);
}
const preflight = await fetch(base + '/v1/activity', {method:'OPTIONS', headers:{Origin:'https://justshivv.github.io'},signal:AbortSignal.timeout(20000)});
assert.equal(preflight.status,204);
if (process.env.USAGE_ADMIN_PASSWORD) {
  const headers = {Authorization:'Basic ' + Buffer.from('owner:' + process.env.USAGE_ADMIN_PASSWORD).toString('base64')};
  const page = await fetch(base + '/usage/', {headers,signal:AbortSignal.timeout(20000)});
  assert.equal(page.status,200);
  const html = await page.text();
  assert.ok(html.includes('id="chart"'));
  for (const match of html.matchAll(/(?:href|src)="(dashboard\.[a-f0-9]{12}\.(?:css|js))"/g)) {
    assert.equal((await fetch(base + '/usage/' + match[1], {headers,signal:AbortSignal.timeout(20000)})).status,200);
  }
  assert.equal((await fetch(base + '/v1/summary',{headers,signal:AbortSignal.timeout(20000)})).status,200);
  console.log('Verified owner access and rejection of anonymous dashboard/API requests.');
} else console.log('Verified anonymous requests are blocked. Owner sign-in awaits password configuration.');
