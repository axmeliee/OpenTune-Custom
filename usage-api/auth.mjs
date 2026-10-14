const COOKIE = '__Host-opentune_session';
const HOURS = 8 * 60 * 60;
const encoder = new TextEncoder();
export const privateHeaders = {
  'Cache-Control':'private, no-store, max-age=0', 'Vary':'Cookie, Authorization, Origin',
  'X-Robots-Tag':'noindex, nofollow, noarchive', 'X-Content-Type-Options':'nosniff', 'Referrer-Policy':'no-referrer',
  'Content-Security-Policy':"default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self' https://api.github.com; img-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'",
};
function configured(env) {
  return /^[A-Za-z0-9_.-]{3,64}$/.test(env.USAGE_ADMIN_USERNAME || '') &&
    typeof env.USAGE_ADMIN_PASSWORD === 'string' && env.USAGE_ADMIN_PASSWORD.length >= 16 && env.USAGE_ADMIN_PASSWORD.length <= 256 &&
    /^[a-f0-9]{64}$/.test(env.USAGE_SESSION_SECRET || '');
}
const base64 = bytes => btoa(String.fromCharCode(...new Uint8Array(bytes))).replace(/=/g,'').replace(/\+/g,'-').replace(/\//g,'_');
function decode(value) {
  if (!/^[A-Za-z0-9_-]+$/.test(value)) throw new Error('Invalid encoding');
  return Uint8Array.from(atob(value.replace(/-/g,'+').replace(/_/g,'/')), c=>c.charCodeAt(0));
}
async function key(env) {
  // Rotating either credential also invalidates all previously issued sessions.
  return crypto.subtle.importKey('raw',encoder.encode(JSON.stringify([env.USAGE_SESSION_SECRET,env.USAGE_ADMIN_USERNAME,env.USAGE_ADMIN_PASSWORD])),{name:'HMAC',hash:'SHA-256'},false,['sign','verify']);
}
async function equal(a,b) {
  const [x,y]=await Promise.all([a,b].map(value=>crypto.subtle.digest('SHA-256',encoder.encode(value)).then(buffer=>new Uint8Array(buffer))));
  let different=0; for(let i=0;i<x.length;i++) different |= x[i]^y[i];
  return different===0;
}
export async function authorized(request,env) {
  if (!configured(env)) return false;
  const bearer=request.headers.get('Authorization');
  const token=bearer?.startsWith('Bearer ') ? bearer.slice(7) : request.headers.get('Cookie')?.split(';').map(s=>s.trim()).find(s=>s.startsWith(COOKIE+'='))?.slice(COOKIE.length+1);
  if (!token || token.length>2048) return false;
  try {
    const parts=token.split('.'); if(parts.length!==2) return false;
    if (!await crypto.subtle.verify('HMAC',await key(env),decode(parts[1]),encoder.encode(parts[0]))) return false;
    const session=JSON.parse(new TextDecoder().decode(decode(parts[0]))), now=Math.floor(Date.now()/1000);
    return session.user===env.USAGE_ADMIN_USERNAME && Number.isInteger(session.exp) && session.exp>now && session.exp<=now+HOURS;
  } catch { return false; }
}
function redirect(location, cookie) {
  return new Response(null,{status:303,headers:{...privateHeaders,Location:location,...(cookie?{'Set-Cookie':cookie}:{})}});
}
const cookie = (value,age) => `${COOKIE}=${value}; Path=/; Max-Age=${age}; Secure; HttpOnly; SameSite=Strict`;
function page(message='',status=200) {
  return new Response(`<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Owner login · OpenTune</title><style>*{box-sizing:border-box}body{margin:0;min-height:100svh;background:#101113;color:#f4f5f1;font:16px/1.6 system-ui;display:grid;place-items:center;padding:24px}main{width:100%;max-width:420px;background:#191b20;border:1px solid #33373c;border-radius:24px;padding:32px}h1{font-size:30px;line-height:1.2;margin:8px 0 14px}p{color:#b8bdc5}label{display:block;margin-top:18px;font-weight:600}input,button{width:100%;font:inherit;border-radius:12px;padding:13px 16px}input{margin-top:6px;background:#101113;border:1px solid #454b55;color:#fff}input:focus-visible,a:focus-visible,button:focus-visible{outline:3px solid #c8ee98;outline-offset:3px}button{margin-top:24px;border:0;background:#c8ee98;color:#182015;font-weight:700;cursor:pointer}a{color:#c8ee98}.error{color:#ffb4ab;overflow-wrap:anywhere}.brand{font-size:14px;letter-spacing:.1em;color:#c8ee98}</style></head><body><main><div class="brand">OPENTUNE</div><h1>Welcome back.</h1><p>Sign in to your private usage dashboard.</p>${message?`<p class="error" role="alert">${message}</p>`:''}<form method="post" action="/auth/login"><label for="username">Username</label><input id="username" name="username" autocomplete="username" maxlength="64" required><label for="password">Password</label><input id="password" name="password" type="password" autocomplete="current-password" maxlength="256" required><button type="submit">Sign in</button></form><p><a href="https://justshivv.github.io/Opentune/">← Back to OpenTune</a></p></main></body></html>`,{status,headers:{...privateHeaders,'Content-Type':'text/html; charset=utf-8'}});
}
export function locked(request) {
  if (['/','/usage','/usage/','/usage/index.html'].includes(new URL(request.url).pathname) && ['GET','HEAD'].includes(request.method)) return redirect('/login');
  return Response.json({error:'Sign in required'},{status:401,headers:privateHeaders});
}
export async function authRoute(request,env) {
  const url=new URL(request.url),path=url.pathname;
  if (path==='/login' && request.method==='GET') return await authorized(request,env) ? redirect('/usage/') : page(configured(env)?'':'Owner login is not configured yet.');
  if (!['/auth/login','/auth/logout'].includes(path)) return null;
  if (request.method!=='POST') return new Response('Method not allowed',{status:405,headers:{...privateHeaders,Allow:'POST'}});
  const json=request.headers.get('Accept')==='application/json';
  const origin=request.headers.get('Origin');
  const trustedPage=json && (env.ALLOWED_ORIGINS || 'https://justshivv.github.io').split(',').includes(origin);
  if (url.protocol!=='https:' || (origin!==url.origin && !trustedPage)) return new Response('Request rejected',{status:403,headers:privateHeaders});
  if (path==='/auth/logout') return redirect('/login',cookie('',0));
  if (!configured(env) || !env.LOGIN_LIMITER) return page('Owner login is not configured yet.',503);
  // Cloudflare's per-IP limiter stores no IPs in the usage database. Fail closed if unavailable.
  try {
    const ip=request.headers.get('CF-Connecting-IP') || 'unknown';
    if (!(await env.LOGIN_LIMITER.limit({key:ip})).success) return page('Too many attempts. Please wait a minute and try again.',429);
  } catch { return page('Sign-in is temporarily unavailable. Please try again.',503); }
  if (!request.headers.get('Content-Type')?.startsWith('application/x-www-form-urlencoded')) return page('Invalid sign-in request.',415);
  const reader=request.body?.getReader(); if(!reader) return page('Enter your username and password.',400);
  let size=0,chunks=[];
  while(true) { const {done,value}=await reader.read(); if(done) break; size+=value.length;
    if(size>4096) {await reader.cancel();return page('Invalid sign-in request.',413);} chunks.push(value); }
  const bytes=new Uint8Array(size);let offset=0;for(const chunk of chunks){bytes.set(chunk,offset);offset+=chunk.length;}
  const form=new URLSearchParams(new TextDecoder().decode(bytes));
  const user=form.get('username') || '',password=form.get('password') || '';
  if(form.getAll('username').length!==1 || form.getAll('password').length!==1 ||
      !(await equal(JSON.stringify([user,password]),JSON.stringify([env.USAGE_ADMIN_USERNAME,env.USAGE_ADMIN_PASSWORD]))))
    return page('Incorrect username or password.',401);
  const payload=base64(encoder.encode(JSON.stringify({user,exp:Math.floor(Date.now()/1000)+HOURS})));
  const signature=base64(await crypto.subtle.sign('HMAC',await key(env),encoder.encode(payload)));
  const token=payload+'.'+signature;
  if(json) return Response.json({token,expiresIn:HOURS},{headers:privateHeaders});
  return redirect('/usage/',cookie(token,HOURS));
}
