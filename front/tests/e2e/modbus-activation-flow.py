"""Exercise the real wizard with intercepted APIs; no project server or Redis is used."""
import importlib.util
import os
from pathlib import Path
from urllib.parse import urlparse, parse_qs
from playwright.sync_api import sync_playwright

spec = importlib.util.spec_from_file_location('multi_product', Path(__file__).with_name('modbus-multi-product.py'))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


class RecordingBackend(fixture.Backend):
    def __init__(self, empty=False, stuck=False):
        super().__init__(empty)
        self.requests = []
        self.stuck = stuck

    def route(self, route):
        parsed = urlparse(route.request.url)
        path = parsed.path.removeprefix('/mock')
        self.requests.append((route.request.method, path, parse_qs(parsed.query)))
        if self.stuck and path == '/device-instance/water/deploy':
            route.fulfill(json={'success': True, 'status': 200, 'result': {'success': True}})
            return
        return super().route(route)


def existing_child(browser, stuck):
    backend = RecordingBackend(stuck=stuck)
    page = browser.new_page(viewport={'width': 1440, 'height': 1100})
    errors = []
    page.on('pageerror', lambda error: errors.append(str(error)))
    page.route('**/mock/**', backend.route)
    page.goto('http://127.0.0.1:9384/')
    page.wait_for_load_state('networkidle')
    fixture.enter_products(page)
    page.get_by_role('button', name='下一步', exact=True).click()
    fixture.save(page)
    if stuck:
        page.wait_for_function('() => (window.__messages || []).some(x => x.includes("启用后状态未确认"))')
        assert not any(path.endswith('/bind') or path.endswith('/_startup') for _, path, _ in backend.requests)
        assert not page.evaluate('(window.__messages || []).some(x => x.includes("各产品配置已保存"))')
        print('PASS: HTTP activation success without active read-back stops before binding/startup')
    else:
        fixture.saved(page)
        deploy = next(i for i, (_, path, _) in enumerate(backend.requests) if path == '/device-instance/water/deploy')
        bind = next(i for i, (_, path, _) in enumerate(backend.requests) if path == '/device/gateway/collector/bind')
        assert deploy < bind
        assert any(path == '/device-instance/water/detail' for _, path, _ in backend.requests[deploy + 1:bind])
        assert backend.requests[bind][2] == {'validateRuntime': ['true']}
        print('PASS: existing inactive child activation and read-back precede strict binding')
    assert not errors, errors
    page.close()


def quick_server(browser):
    backend = RecordingBackend(empty=True)
    original = fixture.Backend
    fixture.Backend = lambda **_: backend
    try:
        fixture.new_flow(browser, True)
    finally:
        fixture.Backend = original
    bind = next(i for i, (_, path, _) in enumerate(backend.requests) if path == '/device/gateway/new-collector/bind')
    start = next(i for i, (_, path, _) in enumerate(backend.requests) if path.endswith('/_startup'))
    for child in ['new-meter', 'new-water']:
        deploy = next(i for i, (_, path, _) in enumerate(backend.requests) if path == f'/device-instance/{child}/deploy')
        assert deploy < bind < start
    assert backend.requests[bind][2] == {'validateRuntime': ['true']}
    print('PASS: TCP server wizard activates children, strictly binds, then starts gateway')


if __name__ == '__main__':
    with sync_playwright() as playwright:
        executable = os.environ.get('MODBUS_TEST_CHROMIUM')
        browser = playwright.chromium.launch(headless=True, **({'executable_path': executable} if executable else {}))
        try:
            existing_child(browser, False)
            existing_child(browser, True)
            quick_server(browser)
        finally:
            browser.close()
