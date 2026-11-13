"""Hold HTTP responses to verify resource selection cannot change mid-operation."""
from playwright.sync_api import sync_playwright, expect
from verify import Backend, BASE, ARTIFACTS, select
from verify_tcp import TcpBackend


def main():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        try:
            for tcp in (False, True):
                page = browser.new_page(viewport={'width': 1280, 'height': 900})
                page.add_init_script('window.__heldRequestCount = 0')
                backend = TcpBackend(reuse=True) if tcp else Backend()
                pending = []

                def handle(route):
                    if route.request.url.endswith('/api/file/upload') or route.request.url.endswith('/_start'):
                        pending.append(route)
                        page.evaluate('window.__heldRequestCount += 1')
                    else:
                        backend.route(route)

                page.route(BASE + '/api/**', handle)
                page.goto(BASE + ('/?tcp=1' if tcp else '/'))
                page.wait_for_load_state('networkidle')
                page.get_by_role('textbox', name='辨识 ID' if tcp else '接入 ID', exact=True).fill('abc123')
                page.get_by_text('上传 JAR', exact=True).click()
                page.locator('input[type=file][accept=".jar"]').set_input_files({'name': 'protocol.jar', 'mimeType': 'application/java-archive', 'buffer': b'test'})
                reuse = page.get_by_role('radio', name='选择已有接入网关', exact=True)
                expect(reuse).to_be_disabled()
                expect(page.get_by_role('button', name='保存接入并继续')).to_be_disabled()
                page.wait_for_function('window.__heldRequestCount === 1')
                assert pending
                backend.route(pending.pop())
                expect(page.get_by_text('protocol.jar', exact=True)).to_be_visible()
                reuse.check()
                select(page, '已有接入网关', 'TCP网关_abc123（tcp_gateway_abc123）' if tcp else 'Existing gateway（gateway1）')
                page.get_by_role('heading').first.click()
                page.screenshot(path=str(ARTIFACTS / ('tcp-reuse-gateway.png' if tcp else 'mqtt-reuse-gateway.png')), full_page=True, animations='disabled')
                page.get_by_role('button', name='使用已有网关并继续').click()
                expect(reuse).to_be_disabled()
                expect(page.get_by_role('radio', name='新建接入网关', exact=True)).to_be_disabled()
                page.wait_for_function('window.__heldRequestCount === 2')
                assert pending
                backend.route(pending.pop())
                expect(page.get_by_role('textbox', name='产品名称')).to_have_value('TCP产品_tcp_gateway_abc123' if tcp else 'MQTT产品_gateway1')
                assert all(backend.count('POST', path) == 0 for path in ('/protocol', '/network/config', '/gateway/device'))
                page.close()
        finally:
            browser.close()
    print('PASS MQTT/TCP uploading/saving lock gateway selection; reuse after upload never registers the draft JAR')


if __name__ == '__main__':
    main()
