// Only the owner receives this deployment secret. Never embed it in dashboard assets.
// A 32+ character random password makes online guessing impractical without storing IPs.
export async function authorized(request, env) {
  const password = env.USAGE_ADMIN_PASSWORD;
  if (typeof password !== 'string' || !/^[\x21-\x7e]{32,256}$/.test(password)) return false;
  const header = request.headers.get('Authorization') || '';
  if (header.length > 1024 || !/^Basic /i.test(header)) return false;
  let supplied;
  try { supplied = atob(header.slice(6)); } catch { return false; }
  const encode = value => new TextEncoder().encode(value);
  const [expected, actual] = await Promise.all([`owner:${password}`, supplied].map(value =>
    crypto.subtle.digest('SHA-256', encode(value)).then(buffer => new Uint8Array(buffer))));
  let difference = 0;
  for (let i = 0; i < expected.length; i++) difference |= expected[i] ^ actual[i];
  return difference === 0;
}

export const privateHeaders = {
  'Cache-Control': 'private, no-store, max-age=0',
  'Vary': 'Authorization, Origin',
  'X-Robots-Tag': 'noindex, nofollow, noarchive',
  'X-Content-Type-Options': 'nosniff',
  'Referrer-Policy': 'no-referrer',
  'Content-Security-Policy': "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self' https://api.github.com; img-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'",
};

export function locked() {
  return new Response('Owner sign-in required.', { status: 401, headers: {
    ...privateHeaders, 'Content-Type': 'text/plain; charset=utf-8',
    'WWW-Authenticate': 'Basic realm="OpenTune owner", charset="UTF-8"',
  }});
}
