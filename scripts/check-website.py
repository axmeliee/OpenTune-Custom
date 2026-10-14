"""Browser regressions using local files and fixtures; never sends usage reports."""
import json
import re
from datetime import date, timedelta
from pathlib import Path
from urllib.parse import urlparse, parse_qs
from playwright.sync_api import sync_playwright, expect

ROOT = Path(__file__).resolve().parents[1] / 'site'
OUT = Path('website-reports')
OUT.mkdir(exist_ok=True)
today = date.today()
iso = today.isoformat()
summary = {'asOf': iso + 'T12:00:00Z', 'totals': {'installs': 1234, 'plays': 54321, 'since': '2026-01-01'}, 'today': {'active': 12, 'plays': 42}, 'week': 100, 'month': 300,
           'daily': [{'day': (today - timedelta(days=i)).isoformat(), 'active': 12, 'plays': 42} for i in range(30)],
           'versions': [], 'downloads': [{'release': '0.4.' + str(i), 'browsers': i + 1} for i in range(12)], 'downloadBrowsers': 99}
mode = 'normal'
def route(request):
    url = urlparse(request.request.url)
    if url.netloc == 'usage.test' and url.path.startswith('/usage/v1/'):
        if url.path == '/usage/v1/summary':
            request.fulfill(json=summary)
        elif mode == 'legacy':
            request.fulfill(status=404, json={})
        elif mode == 'failed':
            request.fulfill(status=503, json={})
        else:
            args = parse_qs(url.query)
            start, end = args['from'][0], args['to'][0]
            request.fulfill(json={'asOf': summary['asOf'], 'range': {'from': start, 'to': end}, 'coverageSince': '2026-01-01', 'totals': {'active': 12, 'plays': 84},
                                 'daily': [{'day': end, 'active': 12, 'plays': 84}], 'versions': [{'version': '0.4.13-' + 'verylonglabel' * 9, 'installations': 12, 'plays': 84}]})
    elif url.netloc == 'api.github.com':
        request.fulfill(json=[{'tag_name': 'v0.4.' + str(i), 'assets': [{'name': 'app.apk', 'download_count': 10}]} for i in range(12)] if '/releases' in url.path else {})
    elif url.netloc in ('opentune.test', 'usage.test'):
        if url.path == '/usage-config.json':
            request.fulfill(json={'apiBase': 'https://usage.test'})
        else:
            path = (ROOT.parent / 'usage-api' / 'public' if url.netloc == 'usage.test' else ROOT) / url.path.lstrip('/')
            if path.is_dir(): path = path / 'index.html'
            if path.is_file():
                mime = {'.html': 'text/html', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css', '.json': 'application/json', '.png': 'image/png', '.jpg': 'image/jpeg'}.get(path.suffix, 'text/plain')
                request.fulfill(body=path.read_bytes(), content_type=mime)
            else: request.fulfill(status=404, body='')
    else:
        request.abort()

def no_overflow(page):
    sizes = page.evaluate('({width:innerWidth, client:document.documentElement.clientWidth, scroll:document.documentElement.scrollWidth, body:document.body.scrollWidth, scale:visualViewport.scale})')
    assert sizes['scroll'] <= sizes['client'] + 1, sizes
    assert abs(sizes['scale'] - 1) < .02, sizes

with sync_playwright() as p:
    browser = p.chromium.launch()
    for width in (320, 360, 390, 768, 1024, 1440):
        context = browser.new_context(viewport={'width': width, 'height': 844}, is_mobile=width < 768, has_touch=width < 768, reduced_motion='reduce')
        context.route('**/*', route)
        page = context.new_page()
        errors = []
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.goto('http://opentune.test/')
        page.wait_for_timeout(300)
        no_overflow(page)
        page.reload()
        page.wait_for_timeout(300)
        no_overflow(page)
        page.evaluate('window.scrollTo(0, 1200)')
        page.wait_for_timeout(100)
        header = page.locator('.nav').bounding_box()
        assert header['y'] >= -1 and header['x'] >= -1, header
        for element in page.locator('.nav a:visible').all():
            box = element.bounding_box()
            assert box['x'] >= -1 and box['x'] + box['width'] <= width + 1, box
            assert box['y'] >= 0 and box['y'] + box['height'] <= header['height'] + 1, box
        assert page.locator('meta[property="og:image"]').get_attribute('content').endswith('/opentune-logo-current.png')
        page.screenshot(path=str(OUT / f'home-{width}.png'))
        page.goto('http://opentune.test/usage/')
        expect(page.locator('h1')).to_have_text('Private analytics')
        expect(page.locator('#chart')).to_have_count(0)
        page.goto('https://usage.test/usage/')
        assert re.fullmatch(r'dashboard\.[a-f0-9]{12}\.css', page.locator('link[rel=stylesheet]').get_attribute('href'))
        assert re.fullmatch(r'dashboard\.[a-f0-9]{12}\.js', page.locator('script[type=module]').get_attribute('src'))
        expect(page.locator('#period-plays')).to_have_text('84')
        expect(page.locator('#period-average')).to_have_text('7')
        active_bar = page.locator('#chart .selected').bounding_box()
        chart_box = page.locator('#chart').bounding_box()
        assert active_bar['x'] >= chart_box['x'] - 1 and active_bar['x'] + active_bar['width'] <= chart_box['x'] + chart_box['width'] + 1
        no_overflow(page)
        assert page.locator('#daily-table tbody tr').count() == 7
        page.locator('#daily-table .view-more').click()
        assert page.locator('#daily-table tbody tr').count() == 30
        page.locator('#daily-table .view-more').click()
        page.locator('.long-label summary').click()
        assert page.locator('.long-label').get_attribute('open') is not None
        no_overflow(page)
        page.locator('[data-days="1"]').click()
        expect(page.locator('#daily-table tbody tr')).to_have_count(1)
        expect(page.locator('#day-detail')).to_contain_text('84 plays')
        single_bar = page.locator('#chart .bar')
        expect(single_bar).to_have_count(1)
        assert single_bar.bounding_box()['width'] <= 128, 'A single date must not stretch across the chart'
        expect(single_bar.locator('.bar-value')).to_have_text('84')
        assert single_bar.locator('.bar-label').evaluate('(el) => getComputedStyle(el).writingMode') == 'horizontal-tb'
        # The selected label must remain light against the dark selection background.
        label_color = single_bar.locator('.bar-label').evaluate('(el) => getComputedStyle(el).color')
        assert all(int(channel) >= 176 for channel in label_color.removeprefix('rgb(').removesuffix(')').split(',')), label_color
        page.locator('#chart-metric').select_option('active')
        expect(single_bar.locator('.bar-value')).to_have_text('12')
        page.locator('#chart-metric').select_option('plays')
        page.locator('.trend-panel').screenshot(path=str(OUT / f'chart-single-{width}.png'))
        page.locator('[data-days="7"]').click()
        expect(page.locator('#chart .bar')).to_have_count(7)
        page.locator('#chart .bar').first.click()
        expect(page.locator('#chart .selected .bar-value')).to_have_text('0')
        expect(page.locator('#day-detail')).to_contain_text('0 plays')
        no_overflow(page)

        page.locator('[data-days="30"]').click()
        expect(page.locator('#daily-table tbody tr')).to_have_count(7)
        page.screenshot(path=str(OUT / f'usage-{width}.png'), full_page=True)
        assert not errors, errors
        context.close()
    context = browser.new_context()
    context.route('**/*', route)
    page = context.new_page()
    mode = 'legacy'
    page.goto('https://usage.test/usage/')
    expect(page.locator('#period-status')).to_contain_text('Limited server')
    expect(page.locator('#period-active')).to_have_text('—')
    expect(page.locator('#period-plays')).to_have_text('1,260')
    page.locator('[data-days="90"]').click()
    expect(page.locator('#period-status')).to_contain_text('needs the analytics API update')
    mode = 'failed'
    page.locator('[data-days="7"]').click()
    expect(page.locator('#period-status')).to_contain_text('Activity unavailable')
    expect(page.locator('#period-plays')).to_have_text('—')
    context.close()
    browser.close()
print('Passed mobile refresh/header, dashboard dates, expansion, wrapping and API failure checks.')
