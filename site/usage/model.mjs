export const DAY = 86400000;
export const isoDay = date => date.toISOString().slice(0, 10);
export function presetRange(days, now = new Date()) {
  const to = isoDay(now);
  return { from: isoDay(new Date(Date.parse(to) - (days - 1) * DAY)), to };
}
export function rangeError({ from, to }, now = new Date()) {
  const valid = value => /^\d{4}-\d{2}-\d{2}$/.test(value || '') && Number.isFinite(Date.parse(value)) && isoDay(new Date(value)) === value;
  if (!valid(from) || !valid(to)) return 'Choose a valid start and end date.';
  if (from > to) return 'The start date must be before or equal to the end date.';
  if (to > isoDay(now)) return 'Choose today or an earlier date (UTC).';
  if ((Date.parse(to) - Date.parse(from)) / DAY >= 366) return 'Choose up to 366 days at a time.';
  return '';
}
// Zero means no reports received during collection, never a failed request.
export function dailyRows(data) {
  const byDay = new Map(data.daily.map(row => [row.day, row]));
  const rows = [];
  for (let time = Date.parse(data.range.from); time <= Date.parse(data.range.to); time += DAY) {
    const day = isoDay(new Date(time));
    const covered = Boolean(data.coverageSince && day >= data.coverageSince);
    rows.push({ day, active: covered ? 0 : null, plays: covered ? 0 : null, ...byDay.get(day) });
  }
  return rows;
}
// Older workers only expose 30 days. Never sum daily users into unique users.
export function legacyAnalytics(summary, range) {
  const available = presetRange(30, new Date(summary.asOf));
  if (range.from < available.from || range.to > available.to) throw new Error('This date range needs the analytics API update. The current server exposes only the last 30 days.');
  const daily = summary.daily.filter(row => row.day >= range.from && row.day <= range.to);
  return { range, daily, coverageSince: summary.totals.since, versions: null,
    totals: { active: range.from === range.to ? (daily[0]?.active ?? 0) : null, plays: daily.reduce((sum, row) => sum + row.plays, 0) },
    limited: true, asOf: summary.asOf };
}
