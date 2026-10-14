import { test } from 'node:test';
import assert from 'node:assert/strict';
import { presetRange, rangeError, dailyRows, legacyAnalytics } from './model.mjs';
test('UTC presets cross year and leap-day boundaries inclusively', () => {
  assert.deepEqual(presetRange(7,new Date('2027-01-02T23:59:00Z')),{from:'2026-12-27',to:'2027-01-02'});
  assert.deepEqual(presetRange(2,new Date('2024-03-01T00:01:00Z')),{from:'2024-02-29',to:'2024-03-01'});
  assert.ok(rangeError({from:'2026-02-30',to:'2026-03-01'}));
  assert.ok(rangeError({from:'2026-03-01',to:'2026-02-28'}));
});
test('missing reports during collection are zero; prehistory is unavailable', () => {
  const rows = dailyRows({range:{from:'2026-10-01',to:'2026-10-04'},coverageSince:'2026-10-02',daily:[{day:'2026-10-02',active:2,plays:3}]});
  assert.deepEqual(rows.map(row=>row.active),[null,2,0,0]);
  assert.deepEqual(rows.map(row=>row.plays),[null,3,0,0]);
});
test('legacy fallback never fabricates period unique users or older history', () => {
  const summary = {asOf:'2026-10-09T12:00:00Z',totals:{since:'2026-09-01'},daily:[{day:'2026-10-08',active:1,plays:3},{day:'2026-10-09',active:1,plays:4}]};
  const data=legacyAnalytics(summary,{from:'2026-10-08',to:'2026-10-09'});
  assert.equal(data.totals.active,null); assert.equal(data.totals.plays,7); assert.equal(data.versions,null);
  assert.equal(legacyAnalytics(summary,{from:'2026-10-09',to:'2026-10-09'}).totals.active,1);
  assert.throws(()=>legacyAnalytics(summary,{from:'2026-08-01',to:'2026-10-09'}));
});
