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
const accessEnv = {ACCESS_TEAM_DOMAIN:'opentune-test.cloudflareaccess.com',ACCESS_AUD:'test-audience',ACCESS_OWNER_EMAIL:'owner@example.com'};
const keys = await crypto.subtle.generateKey({name:'RSASSA-PKCS1-v1_5',modulusLength:2048,publicExponent:new Uint8Array([1,0,1]),hash:'SHA-256'},true,['sign','verify']);
const jwk = {...await crypto.subtle.exportKey('jwk',keys.publicKey),kid:'test-key',alg:'RS256',use:'sig'};
globalThis.fetch = async url => {
  assert.equal(String(url),'https://opentune-test.cloudflareaccess.com/cdn-cgi/access/certs');
  return Response.json({keys:[jwk]});
};
async function jwt(changes={},headerChanges={}) {
  const encode = value => Buffer.from(JSON.stringify(value)).toString('base64url');
  const now=Math.floor(Date.now()/1000);
  const unsigned=encode({alg:'RS256',kid:'test-key',...headerChanges})+'.'+encode({iss:'https://'+accessEnv.ACCESS_TEAM_DOMAIN,aud:[accessEnv.ACCESS_AUD],type:'app',email:accessEnv.ACCESS_OWNER_EMAIL,iat:now,exp:now+3600,...changes});
  const signature=await crypto.subtle.sign('RSASSA-PKCS1-v1_5',keys.privateKey,new TextEncoder().encode(unsigned));
  return unsigned+'.'+Buffer.from(signature).toString('base64url');
}
const auth = {'Cf-Access-Jwt-Assertion':await jwt()};
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
  assert.equal(rejected.status,401);
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
  assert.equal((await worker.fetch(new Request('https://example.test/v1/analytics?from=bad',{headers:auth}),accessEnv)).status,400);
  const result = await worker.fetch(new Request('https://example.test/v1/analytics?from=2020-01-01&to=2020-01-02',{headers:auth}),{DB:database(),...accessEnv});
  assert.equal(result.status,200); assert.equal((await result.json()).totals.active,0);
});

test('private routes fail closed, including direct asset URLs and spoofed identity headers', async () => {
  for (const path of ['/','/usage/','/usage/index.html','/usage/dashboard.js','/usage/assets.json','/v1/summary','/v1/analytics']) {
    for (const headers of [{}, {Authorization:'Basic !!!!'}, {Authorization:'Basic '+btoa('owner:wrong')}, {'Cf-Access-Authenticated-User-Email':'owner@example.com'}]) {
      const result = await worker.fetch(new Request('https://example.test'+path,{headers}),accessEnv);
      assert.equal(result.status,401); assert.match(result.headers.get('cache-control'),/no-store/);
      assert.match(await result.text(),/Owner sign-in required/);
    }
    assert.equal((await worker.fetch(new Request('https://example.test'+path,{headers:auth}),{})).status,401);
    assert.equal((await worker.fetch(new Request('https://example.test'+path,{headers:auth}),{...accessEnv,ACCESS_AUD:''})).status,401);
  }
});
test('owner can read aggregates and assets, with no-cache and anti-framing headers', async () => {
  const env={...accessEnv,DB:database(),ASSETS:{fetch:async()=>new Response('private dashboard',{headers:{'Cache-Control':'public, max-age=3600'}})}};
  for (const path of ['/v1/summary','/usage/']) {
    const result=await worker.fetch(new Request('https://example.test'+path,{headers:auth}),env);
    assert.equal(result.status,200); assert.match(result.headers.get('cache-control'),/no-store/);
    assert.match(result.headers.get('content-security-policy'),/frame-ancestors 'none'/);
  }
  const cross=await worker.fetch(new Request('https://example.test/v1/summary',{headers:{...auth,Origin:'https://justshivv.github.io'}}),env);
  assert.equal(cross.status,403);
  const wrongUser=await worker.fetch(new Request('https://example.test/v1/summary',{headers:{'Cf-Access-Jwt-Assertion':await jwt({email:'other@example.com'})}}),env);
  assert.equal(wrongUser.status,401);
});
test('anonymous reporting stays compatible and cannot reveal stored data', async () => {
  const env={DB:database()};
  const day=new Date().toISOString().slice(0,10);
  const payload={installationId:id,days:[{day,version:'0.4.13',plays:3}]};
  const response=await worker.fetch(new Request('https://example.test/v1/activity',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)}),env);
  assert.equal(response.status,200); assert.deepEqual(await response.json(),{accepted:1});
  const download=await worker.fetch(new Request('https://example.test/v1/download',{method:'POST',headers:{'Content-Type':'application/json',Origin:'https://justshivv.github.io'},body:JSON.stringify({browserId:id,release:'0.4.13'})}),env);
  assert.equal(download.status,200);
  assert.equal((await worker.fetch(new Request('https://example.test/v1/summary'),env)).status,401);
});

test('Access rejects expired, tampered, wrong issuer/audience, non-owner and non-RS256 tokens',async()=>{
  for (const token of [await jwt({exp:0}),await jwt({email:'intruder@example.com'}),await jwt({aud:['another-app']}),await jwt({iss:'https://evil.example'}),await jwt({type:'service'}),await jwt({nbf:9999999999}),await jwt({}, {alg:'none'}),await jwt({}, {alg:'HS256'}),await jwt()+'.extra',auth['Cf-Access-Jwt-Assertion'].slice(0,-10)+'AAAAAAAAAA']) {
    const result=await worker.fetch(new Request('https://example.test/v1/summary',{headers:{'Cf-Access-Jwt-Assertion':token}}),accessEnv);
    assert.equal(result.status,401);
  }
});
test('verified Access cookie authenticates same-origin API requests outside the edge login path',async()=>{
  const response=await worker.fetch(new Request('https://example.test/v1/summary',{headers:{Cookie:'unrelated=value; CF_Authorization='+auth['Cf-Access-Jwt-Assertion']}}),{...accessEnv,DB:database()});
  assert.equal(response.status,200);
});
