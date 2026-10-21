import { test } from 'node:test';
import assert from 'node:assert/strict';
import worker, { createAddon } from './worker.mjs';
const track = { id: 'album/track 1', title: 'An Original', artist: 'Example Artist', album: 'First', duration: 183.5,
  url: 'https://cdn.example/audio.flac', codec: 'flac', sampleRate: 96000, bitDepth: 24 };
const fetch = createAddon([track]);
const get = path => fetch(new Request(`https://addon.example${path}`));
test('manifest advertises the implemented contract and CORS', async () => {
  const response = await get('/manifest.json'); assert.equal(response.headers.get('Access-Control-Allow-Origin'), '*');
  const body = await response.json(); assert.deepEqual(body.resources, ['search','stream']); assert.equal(body.allowDownloads, true);
});
test('search preserves seconds and does not leak media URLs', async () => {
  const body = await (await get('/search?q=original%20artist&quality=lossless')).json();
  assert.equal(body.tracks.length, 1); assert.equal(body.tracks[0].duration, 183.5); assert.equal(body.tracks[0].url, undefined);
  assert.deepEqual(await (await get('/search?q=missing')).json(), { tracks: [] });
});
test('opaque IDs resolve exact native FLAC metadata', async () => {
  const body = await (await get('/stream/' + encodeURIComponent(track.id))).json();
  assert.equal(body.url, track.url); assert.equal(body.bitDepth, 24); assert.equal(body.sampleRate, 96000); assert.equal(body.encrypted, false);
  assert.equal((await get('/stream/missing')).status, 404); assert.equal((await get('/stream/x?quality=low')).status, 400);
});
test('malformed configuration and unsupported methods fail clearly', async () => {
  assert.throws(() => createAddon([{ ...track, url: 'http://insecure.example/a' }]));
  assert.equal((await worker.fetch(new Request('https://addon.example/search'), { CATALOG: '{}' })).status, 500);
  assert.equal((await fetch(new Request('https://addon.example/search', { method: 'POST' }))).status, 405);
  assert.equal((await fetch(new Request('https://addon.example/search', { method: 'OPTIONS' }))).status, 204);
});
