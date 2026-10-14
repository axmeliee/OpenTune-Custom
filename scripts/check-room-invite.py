"""Room invite mobile layout and safe launch targets; no external app is launched."""
from pathlib import Path
from playwright.sync_api import sync_playwright

root = Path(__file__).resolve().parents[1]
reports = root / 'website-reports'
reports.mkdir(exist_ok=True)
url = (root / 'site/together/index.html').as_uri()
with sync_playwright() as p:
    browser = p.chromium.launch()
    page = browser.new_page(user_agent='Android Chrome')
    for width in (320, 360, 480, 768):
        page.set_viewport_size({'width': width, 'height': 800})
        page.goto(url + '#code=K7QXM2PA9DTE')
        assert page.locator('#open').is_visible()
        assert page.locator('#open').get_attribute('href') == 'intent://together/K7QXM2PA9DTE#Intent;scheme=opentune;package=com.opentune;end'
        assert page.evaluate('document.documentElement.scrollWidth <= innerWidth')
        page.screenshot(path=str(reports / f'room-invite-{width}.png'))
    for fragment in ('', '#code=invalid', '#code=K7QXM2PA9DTEAAAA', '#code=%3Cscript%3E'):
        page.goto(url + fragment)
        page.reload()
        assert page.locator('#open').is_hidden()
        assert 'invalid' in page.locator('#message').inner_text()
    browser.close()
print('Room invite checks passed at 320–768px; invalid codes cannot launch the app.')
