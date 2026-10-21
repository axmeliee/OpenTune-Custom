/** OpenTune HTTP add-on starter. Metadata only; media is served directly by your CDN. */
const json = (value, status = 200) => new Response(JSON.stringify(value), { status, headers: {
  'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Methods': 'GET, OPTIONS', 'Cache-Control': 'no-store',
}});
const manifest = { id: 'org.example.opentune.catalog', name: 'My OpenTune catalog', version: '1.0.0',
  resources: ['search', 'stream'], allowDownloads: true, checkValidLossless: false,
  settings: [{ key: 'quality', default: 'lossless', options: [{ label: 'Best available lossless', value: 'lossless' }] }],
};
const validUrl = value => { try { const u = new URL(value); return u.protocol === 'https:' && !u.username && !u.password; } catch { return false; } };
export function createAddon(catalog, info = manifest) {
  if (!Array.isArray(catalog) || catalog.some(t => typeof t.id !== 'string' || !t.id || typeof t.title !== 'string' ||
      typeof t.artist !== 'string' || !Number.isFinite(t.duration) || t.duration <= 0 || !validUrl(t.url) ||
      t.codec !== 'flac' || !Number.isInteger(t.sampleRate) || t.sampleRate < 44100 || ![16, 24, 32].includes(t.bitDepth))) {
    throw new Error('Invalid catalog configuration');
  }
  return async request => {
    if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: {
      'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Methods': 'GET, OPTIONS',
      'Access-Control-Allow-Headers': 'Accept', 'Access-Control-Max-Age': '86400',
    }});
    if (request.method !== 'GET') return json({ error: 'Method not allowed' }, 405);
    const u = new URL(request.url);
    if (u.pathname === '/manifest.json') return json(info);
    const quality = u.searchParams.get('quality') ?? 'lossless';
    if (quality.toLowerCase() !== 'lossless') return json({ error: 'Unsupported quality' }, 400);
    if (u.pathname === '/search') {
      const words = (u.searchParams.get('q') ?? '').normalize('NFKC').toLowerCase().trim().split(/\s+/).filter(Boolean);
      if (!words.length) return json({ tracks: [] });
      return json({ tracks: catalog.filter(t => words.every(word => `${t.title} ${t.artist} ${t.album ?? ''}`.normalize('NFKC').toLowerCase().includes(word)))
        .slice(0, 50).map(({ url, codec, sampleRate, bitDepth, ...track }) => ({ ...track, format: codec, audioQuality: 'LOSSLESS' })) });
    }
    if (u.pathname.startsWith('/stream/')) {
      let id; try { id = decodeURIComponent(u.pathname.slice('/stream/'.length)); } catch { return json({ error: 'Invalid track ID' }, 400); }
      const track = catalog.find(t => t.id === id);
      if (!track) return json({ error: 'Recording not found' }, 404);
      return json({ url: track.url, codec: track.codec, container: 'flac', format: 'flac', manifest: 'none',
        encrypted: false, bitDepth: track.bitDepth, sampleRate: track.sampleRate });
    }
    return json({ error: 'Not found' }, 404);
  };
}
export default {
  async fetch(request, env) {
    try { return await createAddon(JSON.parse(env.CATALOG ?? '[]'))(request); }
    catch { return json({ error: 'Add-on catalog is not configured correctly' }, 500); }
  },
};
