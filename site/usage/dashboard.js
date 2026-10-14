import { isoDay, presetRange, rangeError, dailyRows, legacyAnalytics } from './model.mjs';
const $ = id => document.getElementById(id);
const fmt = value => Number.isFinite(value) ? value.toLocaleString('en-US', { maximumFractionDigits: 1 }) : '—';
const dateLabel = day => new Date(day + 'T00:00:00Z').toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' });
let apiPromise, summaryPromise, allController, periodController, periodData, selectedDay;
const node = (tag, text, className) => { const el = document.createElement(tag); if (text != null) el.textContent = text; if (className) el.className = className; return el; };
async function response(url, signal) {
  const result = await fetch(url, { cache: 'no-store', signal: AbortSignal.any([signal, AbortSignal.timeout(12000)]) });
  if (!result.ok) { const error = new Error('HTTP ' + result.status); error.status = result.status; throw error; }
  return result.json();
}
function empty(id, message) { $(id).replaceChildren(node('p', message, 'empty')); }
function longLabel(value) {
  const text = String(value);
  if (text.length <= 44) return node('span', text);
  const details = node('details', null, 'long-label');
  const summary = node('summary', text.slice(0, 38) + '… ');
  const action = node('span', 'View more'); summary.append(action);
  details.append(summary, node('p', text));
  details.addEventListener('toggle', () => { action.textContent = details.open ? 'View less' : 'View more'; });
  return details;
}
function table(id, headings, rows, { daily = false } = {}) {
  const host = $(id); host.replaceChildren();
  if (!rows.length) { empty(id, 'No reports received for this period.'); return; }
  const tab = node('table'), caption = node('caption');
  caption.textContent = headings.join(', '); caption.hidden = true;
  const head = node('thead'), tr = node('tr'), body = node('tbody');
  headings.forEach((text, index) => { const th = node('th', text, index ? 'num' : ''); th.scope = 'col'; tr.append(th); });
  head.append(tr); tab.append(caption, head, body); host.append(tab);
  let expanded = false;
  const render = () => {
    body.replaceChildren();
    for (const row of (expanded ? rows : rows.slice(0, 7))) {
      const tr = node('tr');
      row.forEach((value, index) => {
        const td = node('td', null, index ? 'num' : '');
        if (index) td.textContent = fmt(value);
        else if (daily) { const button = node('button', dateLabel(value), 'date-link'); button.type = 'button'; button.addEventListener('click', () => selectDay(value)); td.append(button); }
        else td.append(longLabel(value));
        tr.append(td);
      });
      body.append(tr);
    }
  };
  render();
  if (rows.length > 7) {
    const button = node('button', `View more (${fmt(rows.length - 7)} more)`, 'view-more');
    button.type = 'button'; button.setAttribute('aria-expanded', 'false');
    button.addEventListener('click', () => { expanded = !expanded; render(); button.textContent = expanded ? 'View less' : `View more (${fmt(rows.length - 7)} more)`; button.setAttribute('aria-expanded', String(expanded)); });
    host.append(button);
  }
}
function selectDay(day) {
  selectedDay = day;
  const row = dailyRows(periodData).find(row => row.day === day);
  const detail = $('day-detail'); detail.replaceChildren(node('strong', dateLabel(day) + ' · UTC'));
  detail.append(node('span', row?.active == null ? ' — No activity history available for this date.' : ` — ${fmt(row.active)} active installations · ${fmt(row.plays)} plays · ${fmt(row.active ? row.plays / row.active : null)} plays per active installation`));
  for (const bar of $('chart').children) { const selected = bar.dataset.day === day; bar.classList.toggle('selected', selected); bar.setAttribute('aria-pressed', String(selected)); }
}
function chart() {
  if (!periodData) return;
  const metric = $('chart-metric').value, rows = dailyRows(periodData), max = Math.max(1, ...rows.map(row => row[metric] ?? 0));
  const host = $('chart'); host.replaceChildren();
  for (const row of rows) {
    const value = row[metric], button = node('button', null, 'bar' + (value == null ? ' unknown' : ''));
    button.type = 'button'; button.dataset.day = row.day;
    const label = `${dateLabel(row.day)}: ${value == null ? 'unavailable' : fmt(value)} ${metric === 'plays' ? 'plays' : 'active installations'}`;
    button.title = label; button.setAttribute('aria-label', label);
    const fill = node('span', null, 'bar-fill'); fill.style.height = `${Math.max(3, (value ?? 0) / max * 118)}px`; fill.setAttribute('aria-hidden', 'true');
    const tick = node('span', row.day.slice(5), 'bar-label'); tick.setAttribute('aria-hidden', 'true');
    button.append(fill, tick); button.addEventListener('click', () => selectDay(row.day)); host.append(button);
  }
  selectDay(selectedDay && rows.some(row => row.day === selectedDay) ? selectedDay : periodData.range.to);
}
async function loadPeriod() {
  const range = { from: $('from').value, to: $('to').value }, error = rangeError(range);
  $('filter-error').textContent = error; if (error) return;
  periodController?.abort(); const controller = new AbortController(); periodController = controller;
  const signal = controller.signal;
  periodData = null; selectedDay = null;
  $('period').setAttribute('aria-busy', 'true');
  $('period-title').textContent = range.from === range.to ? dateLabel(range.from) : `${dateLabel(range.from)} – ${dateLabel(range.to)}`;
  $('period-status').textContent = 'Loading selected dates…';
  for (const id of ['period-active', 'period-plays', 'period-average']) $(id).textContent = '—';
  for (const id of ['daily-table', 'versions-table', 'chart']) empty(id, 'Loading…');
  $('day-detail').textContent = 'Loading daily counts…';
  for (const button of document.querySelectorAll('[data-days]')) { const preset = presetRange(Number(button.dataset.days)); button.setAttribute('aria-pressed', String(preset.from === range.from && preset.to === range.to)); }
  try {
    const api = await apiPromise; let data;
    try { data = await response(`${api}/v1/analytics?${new URLSearchParams(range)}`, signal); }
    catch (e) { if (e.status !== 404) throw e; data = legacyAnalytics(await summaryPromise, range); }
    if (signal.aborted) return;
    periodData = data;
    const hasHistory = data.coverageSince && range.to >= data.coverageSince;
    $('period-active').textContent = fmt(hasHistory ? data.totals.active : null);
    $('period-plays').textContent = fmt(hasHistory ? data.totals.plays : null);
    $('period-average').textContent = fmt(hasHistory && data.totals.active > 0 ? data.totals.plays / data.totals.active : null);
    $('period-status').textContent = data.limited ? 'Limited server: daily counts available; period-wide unique counts and version filters need the analytics API update.' : !hasHistory ? 'No activity history available for these dates.' : `Reports available since ${dateLabel(data.coverageSince)}. Today and recent days may still change.`;
    table('daily-table', ['Date (UTC)', 'Active', 'Plays'], dailyRows(data).reverse().map(row => [row.day, row.active, row.plays]), { daily: true });
    if (data.versions) table('versions-table', ['Version', 'Active', 'Plays'], data.versions.map(row => [row.version, row.installations, row.plays]));
    else empty('versions-table', 'Date-filtered versions need the analytics API update.');
    chart();
  } catch (e) {
    if (signal.aborted) return;
    $('period-status').textContent = `Activity unavailable — not zero. ${e.message}`;
    for (const id of ['daily-table', 'versions-table', 'chart']) empty(id, 'Unavailable. Try refreshing data.');
    $('day-detail').textContent = 'Daily counts are unavailable.';
  } finally { if (!signal.aborted) $('period').setAttribute('aria-busy', 'false'); }
}
async function loadAll() {
  allController?.abort(); const controller = new AbortController(); allController = controller; const signal = controller.signal;
  $('refresh').disabled = true; $('updated').textContent = '';
  $('lifetime-status').textContent = 'Loading totals…'; $('github-status').textContent = 'Loading GitHub totals…';
  for (const id of ['all-installs', 'all-plays', 'all-browsers', 'all-downloads']) $(id).textContent = '—';
  for (const id of ['downloads-table', 'browser-table']) empty(id, 'Loading…');
  apiPromise = response('../usage-config.json', signal).then(config => {
    if (!config.apiBase) throw new Error('Usage reporting is not connected.');
    const api = new URL(config.apiBase);
    if (api.protocol !== 'https:' || api.username || api.password || api.search || api.hash) throw new Error('Invalid usage API address.');
    return api.href.replace(/\/$/, '');
  });
  summaryPromise = apiPromise.then(api => response(api + '/v1/summary', signal));
  await Promise.allSettled([
    loadPeriod(),
    summaryPromise.then(data => {
      if (signal.aborted) return;
      $('all-installs').textContent = fmt(data.totals.installs); $('all-plays').textContent = fmt(data.totals.plays); $('all-browsers').textContent = fmt(data.downloadBrowsers);
      $('lifetime-status').textContent = data.totals.since ? `Activity recorded since ${dateLabel(data.totals.since)}` : 'No activity reports received yet.';
      table('browser-table', ['Release', 'Browsers'], data.downloads.map(row => [row.release, row.browsers]));
      $('updated').textContent = `Activity updated ${new Date(data.asOf).toLocaleString('en-GB', { timeZone: 'UTC' })} UTC`;
    }).catch(() => { if (!signal.aborted) { $('lifetime-status').textContent = 'Reporting totals unavailable — not zero.'; empty('browser-table', 'Unavailable. Try refreshing data.'); } }),
    (async () => {
      try {
        const releases = [];
        for (let page = 1; ; page++) { const batch = await response(`https://api.github.com/repos/justshivv/Opentune/releases?per_page=100&page=${page}`, signal); releases.push(...batch.filter(release => !release.draft)); if (batch.length < 100) break; }
        if (signal.aborted) return;
        const rows = releases.map(release => [release.tag_name, release.assets.filter(asset => asset.name.toLowerCase().endsWith('.apk')).reduce((total, asset) => total + asset.download_count, 0)]);
        $('all-downloads').textContent = fmt(rows.reduce((total, row) => total + row[1], 0));
        $('github-status').textContent = `${fmt(rows.length)} published releases · cumulative APK downloads`;
        table('downloads-table', ['Release', 'Downloads'], rows);
      } catch { if (!signal.aborted) { $('github-status').textContent = 'GitHub unavailable — not zero.'; empty('downloads-table', 'Download counts could not be loaded. Try again later.'); } }
    })(),
  ]);
  if (!signal.aborted) $('refresh').disabled = false;
}
$('filters').addEventListener('submit', event => { event.preventDefault(); loadPeriod(); });
for (const button of document.querySelectorAll('[data-days]')) button.addEventListener('click', () => { const range = presetRange(Number(button.dataset.days)); $('from').value = range.from; $('to').value = range.to; loadPeriod(); });
$('chart-metric').addEventListener('change', chart);
$('refresh').addEventListener('click', loadAll);
$('from').max = $('to').max = isoDay(new Date());
const initial = presetRange(30); $('from').value = initial.from; $('to').value = initial.to;
document.querySelector('.about').addEventListener('toggle', event => { event.currentTarget.querySelector('summary span').textContent = event.currentTarget.open ? 'View less' : 'View more'; });
loadAll();
