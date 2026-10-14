// Read-only checks: never send synthetic activity reports or print credentials.
import assert from 'node:assert/strict';
const base='https://opentune-usage.create-a-new-site-with-sites.workers.dev';
const read=(path,options={})=>fetch(base+path,{redirect:'manual',signal:AbortSignal.timeout(20000),...options});
// Allow brief edge propagation after publishing, but never accept an open API.
for(let attempt=0;;attempt++) {
  try {
    for(const path of ['/usage/','/usage/index.html','/usage/assets.json','/v1/summary','/usage/v1/summary','/v1/analytics?from=2026-01-01&to=2026-01-01']) {
      const response=await read(path);
      if(['/usage/','/usage/index.html'].includes(path)) {
        assert.equal(response.status,303);assert.equal(response.headers.get('location'),'/login');
      } else assert.equal(response.status,401,`Unauthenticated data route must be locked: ${path}`);
      assert.match(response.headers.get('cache-control'),/no-store/);
    }
    const page=await read('/login');assert.equal(page.status,200);
    assert.ok((await page.text()).includes('name="password"'));
    break;
  } catch(error) { if(attempt===5) throw error;await new Promise(resolve=>setTimeout(resolve,5000)); }
}
const preflight=await read('/v1/activity',{method:'OPTIONS',headers:{Origin:'https://justshivv.github.io'}});
assert.equal(preflight.status,204);
if(process.env.USAGE_ADMIN_USERNAME && process.env.USAGE_ADMIN_PASSWORD) {
  const login=await read('/auth/login',{method:'POST',headers:{Origin:base,'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({username:process.env.USAGE_ADMIN_USERNAME,password:process.env.USAGE_ADMIN_PASSWORD})});
  assert.equal(login.status,303);
  const cookie=login.headers.get('set-cookie');assert.ok(cookie?.includes('HttpOnly'));
  const headers={Cookie:cookie.split(';')[0]};
  for(const path of ['/usage/','/usage/v1/summary']) assert.equal((await read(path,{headers})).status,200);
  const logout=await read('/auth/logout',{method:'POST',headers:{...headers,Origin:base}});
  assert.match(logout.headers.get('set-cookie'),/Max-Age=0/);
  console.log('Verified login, private dashboard/API, logout and anonymous rejection.');
} else console.log('Verified login form and anonymous rejection. Add owner credentials to enable sign-in.');
