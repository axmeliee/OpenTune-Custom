import { create } from 'zustand';
import { endpoint, flacInfo, manifestOf, matches, publicHttps, qualityParams, type Addon, type CatalogTrack, type Recording } from '@opentune/shared/addons';
import { getBridge } from '@/extension/bridge';
const KEY = 'opentune:audio-addons';
function read(): Addon[] { try { const v = JSON.parse(localStorage.getItem(KEY) ?? '[]'); return Array.isArray(v) ? v.filter(x => typeof x?.url === 'string' && typeof x?.name === 'string').slice(0, 20) : []; } catch { return []; } }
export const useAddons = create<{ sources: Addon[]; update: (sources: Addon[]) => void; status: string }>(set => ({
  sources: read(), status: '', update: sources => { localStorage.setItem(KEY, JSON.stringify(sources)); set({ sources }); },
}));
const cache = new Map<string, { until: number; value: unknown }>();
const pending = new Map<string, Promise<unknown>>();
const cooldown = new Map<string, number>();
async function fetchJson(url: string): Promise<unknown> {
  const origin = publicHttps(url).origin;
  if ((cooldown.get(origin) ?? 0) > Date.now()) throw new Error('Source rate limited; try later');
  const old = cache.get(url); if (old && old.until > Date.now()) return old.value;
  const running = pending.get(url); if (running) return running;
  const job = (async () => {
    let status: number, text: string;
    try {
      const r = await fetch(url, { credentials: 'omit', redirect: 'error', referrerPolicy: 'no-referrer', signal: AbortSignal.timeout(6000) });
      status = r.status;
      const reader = r.body?.getReader(); if (!reader) throw new Error('Empty response');
      const decoder = new TextDecoder(); text = ''; let size = 0;
      try { while (true) { const item = await reader.read(); if (item.done) break; size += item.value.length; if (size > 2097152) throw new Error('Response too large'); text += decoder.decode(item.value, { stream: true }); } text += decoder.decode(); }
      finally { await reader.cancel().catch(() => {}); }
    } catch {
      const bridge = getBridge(); if (!bridge) throw new Error('Source needs CORS support or extension host permission');
      const r = await bridge.call('addon.fetch', { url }); status = r.status; text = r.body;
    }
    if (status === 429) cooldown.set(origin, Date.now() + 60000);
    if (status < 200 || status >= 300) throw new Error(`Source returned HTTP ${status}`);
    const value: unknown = JSON.parse(text);
    if (cache.size > 100) cache.clear();
    cache.set(url, { until: Date.now() + (new URL(url).pathname.includes('/stream/') ? 300000 : 600000), value });
    return value;
  })().finally(() => pending.delete(url));
  pending.set(url, job); return job;
}
export async function addSource(raw: string): Promise<void> {
  const url = endpoint(raw, 'manifest.json');
  const manifest = manifestOf(await fetchJson(url));
  const { sources, update } = useAddons.getState();
  if (sources.length >= 20) throw new Error('Up to 20 sources can be added');
  if (sources.some(x => x.url === url)) throw new Error('This source is already added');
  update([...sources, { id: crypto.randomUUID(), name: manifest.name, url, enabled: true }]);
}
export async function allowSource(raw: string): Promise<void> {
  const bridge = getBridge(); if (!bridge) throw new Error('Install the OpenTune extension first');
  await bridge.call('addon.requestPermission', { url: publicHttps(raw).href });
}
export type LosslessResult = { url: string; provider: string; bits: number; rate: number; duration: number; sourceId: string };
/** Search in source order, compare measured direct FLAC quality; ties keep the listener's order. */
export async function findLossless(recording: Recording): Promise<LosslessResult | null> {
  let best: LosslessResult | null = null;
  const started = Date.now();
  for (const source of useAddons.getState().sources) {
    if (!source.enabled || Date.now() - started > 15000) continue;
    try {
      useAddons.setState({ status: `Checking ${source.name}…` });
      const manifest = manifestOf(await fetchJson(source.url));
      // Web cannot verify the physical output route. Respect this gate conservatively.
      if (manifest.checkValidLossless) continue;
      const params = qualityParams(manifest);
      const response = await fetchJson(endpoint(source.url, 'search', { ...params, q: `${recording.title} ${recording.artist}` })) as { tracks?: CatalogTrack[] };
      const match = response.tracks?.find(x => matches(recording, x)); if (!match) continue;
      const stream = await fetchJson(endpoint(source.url, `stream/${encodeURIComponent(match.id)}`, params)) as { url?: string; encrypted?: boolean; manifest?: string };
      if (!stream.url || stream.encrypted || (stream.manifest && stream.manifest !== 'none')) continue;
      const url = publicHttps(stream.url).href;
      const r = await fetch(url, { headers: { Range: 'bytes=0-4095' }, credentials: 'omit', redirect: 'error', referrerPolicy: 'no-referrer', signal: AbortSignal.timeout(6000) });
      if (!r.ok || !r.body) continue;
      const reader = r.body.getReader(); let bytes = new Uint8Array(0);
      try { while (bytes.length < 42) { const part = await reader.read(); if (part.done) break; const merged = new Uint8Array(bytes.length + Math.min(part.value.length, 4096)); merged.set(bytes); merged.set(part.value.slice(0, 4096), bytes.length); bytes = merged; } }
      finally { await reader.cancel().catch(() => {}); }
      const info = flacInfo(bytes);
      if (Math.abs(info.duration - recording.duration) > 3) continue;
      const candidate = { url, provider: manifest.name, ...info, sourceId: source.id };
      if (!best || candidate.bits * candidate.rate > best.bits * best.rate) best = candidate;
    } catch { /* A failing source does not stop the next one or normal playback. */ }
  }
  useAddons.setState({ status: best ? `${best.provider} · verified FLAC` : 'No matching browser-compatible FLAC found' });
  return best;
}
