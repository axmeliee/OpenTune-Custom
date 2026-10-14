// Verify Access JWTs at the origin, including direct API calls outside the Access route.
// Trust only this account's issuer, this application's audience and the owner's email.
const keyCache = new Map();
function decode(value) {
  if (!/^[A-Za-z0-9_-]+$/.test(value)) throw new Error('Invalid JWT encoding');
  return Uint8Array.from(atob(value.replace(/-/g,'+').replace(/_/g,'/')), c => c.charCodeAt(0));
}
export async function authorized(request, env) {
  const domain = env.ACCESS_TEAM_DOMAIN;
  const audience = env.ACCESS_AUD;
  const owner = env.ACCESS_OWNER_EMAIL;
  if (!/^[a-z0-9-]+\.cloudflareaccess\.com$/.test(domain || '') ||
      typeof audience !== 'string' || !audience || typeof owner !== 'string' || !owner.includes('@')) return false;
  const token = request.headers.get('Cf-Access-Jwt-Assertion') ||
    request.headers.get('Cookie')?.split(';').map(value => value.trim()).find(value => value.startsWith('CF_Authorization='))?.slice(17);
  if (!token || token.length > 16384) return false;
  try {
    const parts = token.split('.');
    if (parts.length !== 3) return false;
    const header = JSON.parse(new TextDecoder().decode(decode(parts[0])));
    const payload = JSON.parse(new TextDecoder().decode(decode(parts[1])));
    const now = Math.floor(Date.now()/1000), issuer = `https://${domain}`;
    if (header.alg !== 'RS256' || typeof header.kid !== 'string' || header.crit !== undefined ||
        payload.iss !== issuer || !Array.isArray(payload.aud) || !payload.aud.includes(audience) ||
        payload.type !== 'app' || typeof payload.email !== 'string' || payload.email.toLowerCase() !== owner.toLowerCase() ||
        !Number.isFinite(payload.exp) || payload.exp <= now ||
        !Number.isFinite(payload.iat) || payload.iat > now + 30 ||
        (payload.nbf !== undefined && (!Number.isFinite(payload.nbf) || payload.nbf > now))) return false;
    let cached = keyCache.get(domain);
    if (!cached || cached.until <= now) {
      const response = await fetch(`${issuer}/cdn-cgi/access/certs`, {signal:AbortSignal.timeout(5000),redirect:'error'});
      if (!response.ok) return false;
      const {keys} = await response.json();
      if (!Array.isArray(keys) || keys.length > 20) return false;
      cached = {keys,until:now+300};
      if (keyCache.size >= 4) keyCache.clear();
      keyCache.set(domain,cached);
    }
    const jwk = cached.keys.find(key => key.kid === header.kid && key.kty === 'RSA' &&
      (!key.alg || key.alg === 'RS256') && (!key.use || key.use === 'sig'));
    if (!jwk) return false;
    const key = await crypto.subtle.importKey('jwk',jwk,{name:'RSASSA-PKCS1-v1_5',hash:'SHA-256'},false,['verify']);
    return await crypto.subtle.verify('RSASSA-PKCS1-v1_5',key,decode(parts[2]),new TextEncoder().encode(parts[0]+'.'+parts[1]));
  } catch { return false; }
}
export const privateHeaders = {
  'Cache-Control':'private, no-store, max-age=0', 'Vary':'Cookie, Cf-Access-Jwt-Assertion, Origin',
  'X-Robots-Tag':'noindex, nofollow, noarchive', 'X-Content-Type-Options':'nosniff', 'Referrer-Policy':'no-referrer',
  'Content-Security-Policy':"default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self' https://api.github.com; img-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'",
};
export function locked() {
  return new Response('Owner sign-in required. Open /usage/ to sign in through Cloudflare Access.',
    {status:401,headers:{...privateHeaders,'Content-Type':'text/plain; charset=utf-8'}});
}
