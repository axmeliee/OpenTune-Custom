import { test } from 'node:test';
import assert from 'node:assert/strict';
import { DatabaseSync } from 'node:sqlite';
import { readFileSync } from 'node:fs';
import worker, { ingest, summary, downloadSql, validateActivity } from './worker.mjs';

function database() {
  const sql = new DatabaseSync(':memory:'); sql.exec(readFileSync(new URL('./schema.sql',import.meta.url),'utf8'));
  return { prepare(query) { return { bind(...args) { return { query,args,run:async()=>sql.prepare(query).run(...args) }; }, query,args:[] }; },
    async batch(statements) {
      sql.exec('BEGIN');
      try { const results=statements.map(s=>({results:sql.prepare(s.query).all(...s.args)})); sql.exec('COMMIT'); return results; }
      catch(e) { sql.exec('ROLLBACK'); throw e; }
    }
  };
}
const loginEnv={USAGE_ADMIN_USERNAME:'owner',USAGE_ADMIN_PASSWORD:'test-password-only-12345',USAGE_SESSION_SECRET:'a'.repeat(64),LOGIN_LIMITER:{limit:async()=>({success:true})}};
function loginRequest(user='owner',password=loginEnv.USAGE_ADMIN_PASSWORD,origin='https://example.test') {
  return new Request('https://example.test/auth/login',{method:'POST',headers:{Origin:origin,'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({username:user,password})});
}
const signedIn=await worker.fetch(loginRequest(),loginEnv);
const auth={Cookie:signedIn.headers.get('set-cookie').split(';')[0]};
const id='00000000-0000-4000-8000-000000000001';
const now=new Date('2026-10-09T12:00:00Z');
const body=(plays=0,version='0.4.2')=>({installationId:id,days:[{day:'2026-10-09',version,plays}]});
test('retries, reordering and upgrades preserve one active installation and exact play aggregates',async()=>{
  const db=database();
  await ingest(db,body(4),now); await ingest(db,body(4),now); await ingest(db,body(2),now);
  await ingest(db,body(1,'0.4.3'),now);
  const result=await summary(db,now);
  assert.equal(result.totals.installs,1); assert.equal(result.today.active,1); assert.equal(result.totals.plays,5);
  assert.equal(result.versions.length,2);
});
test('different installations count separately; week/month use distinct IDs across days',async()=>{
  const db=database(); await ingest(db,body(1),now);
  await ingest(db,{installationId:id,days:[{day:'2026-10-08',version:'0.4.2',plays:2}]},now);
  await ingest(db,{...body(3),installationId:id.replace(/1$/,'2')},now);
  const result=await summary(db,now); assert.equal(result.week,2); assert.equal(result.month,2); assert.equal(result.totals.plays,6);
});
test('four downloads from one browser count once per release',async()=>{
  const db=database(); for(let i=0;i<4;i++) await db.prepare(downloadSql).bind(id,'0.4.2',now.toISOString()).run();
  await db.prepare(downloadSql).bind(id,'0.4.3',now.toISOString()).run();
  const result=await summary(db,now); assert.equal(result.downloadBrowsers,1); assert.equal(result.downloads.length,2);
  assert.ok(result.downloads.every(d=>d.browsers===1));
});
test('reject invalid IDs, negative counts, invalid dates and future/backdated batches',()=>{
  for(const change of [{installationId:'phone'}, {days:[{day:'2026-10-10',version:'0.4.2',plays:1}]},
    {days:[{day:'2026-02-30',version:'0.4.2',plays:1}]}, {days:[{day:'2026-10-09',version:'0.4.2',plays:-1}]}])
    assert.throws(()=>validateActivity({...body(),...change},now));
});
test('API rejects foreign origins and oversized bodies without touching database',async()=>{
  const rejected=await worker.fetch(new Request('https://example.test/v1/summary',{headers:{Origin:'https://foreign.test'}}),{});
  assert.equal(rejected.status,403);
  const large=await worker.fetch(new Request('https://example.test/v1/activity',{method:'POST',headers:{'Content-Type':'application/json'},body:' '.repeat(17000)}),{});
  assert.equal(large.status,413);
});

test('UTC year boundary counts this ISO week once without carrying last month into this month',async()=>{
  const db=database(), jan=new Date('2027-01-01T00:01:00Z');
  await ingest(db,{installationId:id,days:[{day:'2026-12-31',version:'0.4.2',plays:2},{day:'2027-01-01',version:'0.4.2',plays:1}]},jan);
  const result=await summary(db,jan);
  assert.equal(result.week,1); assert.equal(result.month,1); assert.equal(result.today.plays,1);
});

test('invalid batch does not partially write valid rows',async()=>{
  const db=database();
  await assert.rejects(ingest(db,{...body(),days:[...body().days,{day:'invalid',version:'0.4.2',plays:1}]},now));
  assert.equal((await summary(db,now)).totals.installs,0);
});


const { analytics, validateRange } = await import('./worker.mjs');
test('date range uses inclusive UTC endpoints and distinct installations across days and versions', async () => {
  const db = database();
  await ingest(db, { installationId:id, days:[
    {day:'2026-10-07',version:'0.4.2',plays:90},
    {day:'2026-10-08',version:'0.4.2',plays:2},
    {day:'2026-10-09',version:'0.4.2',plays:3},
    {day:'2026-10-09',version:'0.4.3',plays:1},
  ] }, now);
  await ingest(db, {...body(4),installationId:id.replace(/1$/,'2')}, now);
  const result = await analytics(db, {from:'2026-10-08',to:'2026-10-09'}, now);
  assert.deepEqual({...result.totals}, {active:2,plays:10});
  assert.equal(result.coverageSince,'2026-10-07');
  assert.deepEqual(result.daily.map(row => [row.day,row.active,row.plays]), [['2026-10-08',1,2],['2026-10-09',2,8]]);
  assert.deepEqual(result.versions.map(row => [row.version,row.installations,row.plays]), [['0.4.2',2,9],['0.4.3',1,1]]);
  const day = await analytics(db,{from:'2026-10-08',to:'2026-10-08'},now);
  assert.deepEqual({...day.totals},{active:1,plays:2});
});
test('historical activity remains queryable beyond the summary 30 day window', async () => {
  const db=database();
  await ingest(db,{installationId:id,days:[{day:'2026-01-01',version:'0.4.2',plays:7}]},new Date('2026-01-01T12:00:00Z'));
  assert.equal((await summary(db,now)).daily.length,0);
  assert.equal((await analytics(db,{from:'2026-01-01',to:'2026-01-01'},now)).totals.plays,7);
  const empty = await analytics(database(),{from:'2026-10-01',to:'2026-10-09'},now);
  assert.equal(empty.coverageSince,null); assert.deepEqual(empty.daily,[]);
});
test('range validation rejects impossible, future, reversed, missing and oversized dates', async () => {
  for (const query of ['from=2026-02-30&to=2026-03-01','from=2026-10-09&to=2026-10-10','from=2026-10-09&to=2026-10-08','from=2025-01-01&to=2026-10-09','from=bad','']) {
    assert.throws(()=>validateRange(new URLSearchParams(query),now));
  }
  assert.deepEqual(validateRange(new URLSearchParams('from=2026-10-09&to=2026-10-09'),now),{from:'2026-10-09',to:'2026-10-09'});
  assert.equal((await worker.fetch(new Request('https://example.test/v1/analytics?from=bad',{headers:auth}),loginEnv)).status,400);
  const result = await worker.fetch(new Request('https://example.test/v1/analytics?from=2020-01-01&to=2020-01-02',{headers:auth}),{DB:database(),...loginEnv});
  assert.equal(result.status,200); assert.equal((await result.json()).totals.active,0);
});


test('anonymous dashboard redirects to login while APIs and assets stay inaccessible',async()=>{
  for(const path of ['/v1/summary','/usage/v1/summary','/v1/analytics','/usage/dashboard.js']) {
    for(const headers of [{},{'Cf-Access-Authenticated-User-Email':'owner@example.com'},{Authorization:'Basic '+btoa('owner:password')},{Cookie:'__Host-opentune_session=forged'}]) {
      assert.equal((await worker.fetch(new Request('https://example.test'+path,{headers}),loginEnv)).status,401);
    }
    assert.equal((await worker.fetch(new Request('https://example.test'+path,{headers:auth}),{})).status,401);
  }
  const result=await worker.fetch(new Request('https://example.test/usage/'),loginEnv);
  assert.equal(result.status,303);assert.equal(result.headers.get('location'),'/login');
});
test('login form sets a secure session and permits private data and assets',async()=>{
  assert.equal(signedIn.status,303);
  const cookie=signedIn.headers.get('set-cookie');
  for(const flag of ['HttpOnly','Secure','SameSite=Strict','Path=/','Max-Age=28800']) assert.ok(cookie.includes(flag));
  const env={...loginEnv,DB:database(),ASSETS:{fetch:async()=>new Response('private dashboard')}};
  for(const path of ['/v1/summary','/usage/v1/summary','/usage/']) {
    const result=await worker.fetch(new Request('https://example.test'+path,{headers:auth}),env);
    assert.equal(result.status,200);assert.match(result.headers.get('cache-control'),/no-store/);
  }
  const cross=await worker.fetch(new Request('https://example.test/v1/summary',{headers:{...auth,Origin:'https://evil.test'}}),env);
  assert.equal(cross.status,403);
});
test('bad credentials, cross-site login/logout, malformed and oversized submissions are rejected',async()=>{
  for(const req of [loginRequest('other'),loginRequest('owner','wrong')]) {
    const res=await worker.fetch(req,loginEnv);assert.equal(res.status,401);assert.equal(res.headers.get('set-cookie'),null);
  }
  assert.equal((await worker.fetch(loginRequest('owner',loginEnv.USAGE_ADMIN_PASSWORD,'https://evil.test'),loginEnv)).status,403);
  assert.equal((await worker.fetch(new Request('https://example.test/auth/logout',{method:'POST'}),loginEnv)).status,403);
  const big=new Request('https://example.test/auth/login',{method:'POST',headers:{Origin:'https://example.test','Content-Type':'application/x-www-form-urlencoded'},body:'x'.repeat(5000)});
  assert.equal((await worker.fetch(big,loginEnv)).status,413);
});
test('login limits guesses and fails closed without credentials or limiter',async()=>{
  assert.equal((await worker.fetch(loginRequest(),{...loginEnv,LOGIN_LIMITER:{limit:async()=>({success:false})}})).status,429);
  assert.equal((await worker.fetch(loginRequest(),{...loginEnv,LOGIN_LIMITER:undefined})).status,503);
  assert.equal((await worker.fetch(loginRequest(),{})).status,503);
  assert.equal((await worker.fetch(new Request('https://example.test/v1/summary',{headers:auth}),{...loginEnv,USAGE_ADMIN_PASSWORD:'short'})).status,401);
});
test('tampering, expired sessions and credential rotation cannot authorize reads',async()=>{
  const req=cookie=>new Request('https://example.test/v1/summary',{headers:{Cookie:cookie}});
  assert.equal((await worker.fetch(req(auth.Cookie+'tampered'),loginEnv)).status,401);
  assert.equal((await worker.fetch(req(auth.Cookie),{...loginEnv,USAGE_ADMIN_PASSWORD:'different-password-12345'})).status,401);
  const payload=Buffer.from(JSON.stringify({user:'owner',exp:1})).toString('base64url');
  const key=await crypto.subtle.importKey('raw',new TextEncoder().encode(JSON.stringify([loginEnv.USAGE_SESSION_SECRET,loginEnv.USAGE_ADMIN_USERNAME,loginEnv.USAGE_ADMIN_PASSWORD])),{name:'HMAC',hash:'SHA-256'},false,['sign']);
  const sig=Buffer.from(await crypto.subtle.sign('HMAC',key,new TextEncoder().encode(payload))).toString('base64url');
  assert.equal((await worker.fetch(req('__Host-opentune_session='+payload+'.'+sig),loginEnv)).status,401);
});
test('logout clears the browser session and login uses no third-party identity scripts',async()=>{
  const result=await worker.fetch(new Request('https://example.test/auth/logout',{method:'POST',headers:{...auth,Origin:'https://example.test'}}),loginEnv);
  assert.equal(result.status,303);assert.match(result.headers.get('set-cookie'),/Max-Age=0/);
  const page=await worker.fetch(new Request('https://example.test/login'),loginEnv);
  const html=await page.text();assert.ok(html.includes('autocomplete="current-password"'));assert.ok(!html.includes('Google'));
});
test('anonymous reporting remains write-only and compatible',async()=>{
  const env={DB:database()},day=new Date().toISOString().slice(0,10);
  const result=await worker.fetch(new Request('https://example.test/v1/activity',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({installationId:id,days:[{day,version:'0.4.13',plays:3}]})}),env);
  assert.equal(result.status,200);assert.deepEqual(await result.json(),{accepted:1});
  assert.equal((await worker.fetch(new Request('https://example.test/v1/summary'),env)).status,401);
});

test('GitHub Pages login returns a bearer session without third-party cookies',async()=>{
  const origin='https://justshivv.github.io';
  const request=new Request('https://example.test/auth/login',{method:'POST',headers:{Origin:origin,Accept:'application/json','Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({username:'owner',password:loginEnv.USAGE_ADMIN_PASSWORD})});
  const login=await worker.fetch(request,loginEnv);
  assert.equal(login.status,200);assert.equal(login.headers.get('set-cookie'),null);assert.equal(login.headers.get('Access-Control-Allow-Origin'),origin);
  const {token}=await login.json();
  const result=await worker.fetch(new Request('https://example.test/usage/v1/summary',{headers:{Origin:origin,Authorization:'Bearer '+token}}),{...loginEnv,DB:database()});
  assert.equal(result.status,200);assert.equal(result.headers.get('Access-Control-Allow-Origin'),origin);
  const unauthenticated=await worker.fetch(new Request('https://example.test/usage/v1/summary',{headers:{Origin:origin}}),loginEnv);
  assert.equal(unauthenticated.status,401);assert.equal(unauthenticated.headers.get('Access-Control-Allow-Origin'),origin);
  const preflight=await worker.fetch(new Request('https://example.test/usage/v1/summary',{method:'OPTIONS',headers:{Origin:origin,'Access-Control-Request-Headers':'authorization'}}),loginEnv);
  assert.equal(preflight.status,204);assert.match(preflight.headers.get('Access-Control-Allow-Headers'),/Authorization/);
});
test('foreign origins cannot obtain or use a bearer session',async()=>{
  const login=new Request('https://example.test/auth/login',{method:'POST',headers:{Origin:'https://evil.test',Accept:'application/json','Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({username:'owner',password:loginEnv.USAGE_ADMIN_PASSWORD})});
  assert.equal((await worker.fetch(login,loginEnv)).status,403);
  const result=await worker.fetch(new Request('https://example.test/v1/summary',{headers:{Origin:'https://evil.test',Authorization:'Bearer '+auth.Cookie.split('=')[1]}}),loginEnv);
  assert.equal(result.status,403);assert.equal(result.headers.get('Access-Control-Allow-Origin'),null);
});
