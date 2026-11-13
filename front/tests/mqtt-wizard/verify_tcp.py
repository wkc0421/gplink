"""TCP acceptance with actual Vue pages/API wrappers and isolated HTTP fixtures."""
from pathlib import Path
from urllib.parse import urlparse
from playwright.sync_api import sync_playwright, expect
from verify import Backend, BASE, ARTIFACTS, select, upload_devices, product_step


class TcpBackend(Backend):
    def __init__(self, **flags):
        super().__init__(**flags)
        self.network_created = False
        self.gateway_created = False
        self.protocols = [{'id': 'tcp-proto', 'name': 'TCP测试协议'}]
        self.network.update(id='tcp_server_abc123', name='TCP服务端_abc123', type='TCP_SERVER')
        self.gateway.update(id='tcp_gateway_abc123', name='TCP网关_abc123', provider='tcp-server-gateway', protocol='tcp-proto', channelId=self.network['id'])

    def route(self, route):
        request = route.request
        path = urlparse(request.url).path.removeprefix('/api')
        body = request.post_data_json if request.post_data and 'application/json' in request.headers.get('content-type', '') else None
        result = None
        handled = True
        if path == '/protocol/supports/TCP':
            result = self.protocols
        elif path.endswith('/transport/TCP'):
            # TCP has no MQTT routes: it must still be accepted.
            result = {}
        elif path == '/network/config/_query':
            result = [self.network] if self.network_created or self.flags.get('network_conflict') or self.flags.get('reuse') else []
        elif path == '/gateway/device/_query/no-paging':
            result = [self.gateway] if self.gateway_created or self.flags.get('gateway_conflict') or self.flags.get('reuse') else []
        elif path.startswith('/network/config/TCP_SERVER/_alive'):
            result = [self.network]
        elif path == '/network/config' and request.method == 'POST':
            self.network_created = True; handled = False
        elif path == '/gateway/device' and request.method == 'POST':
            if self.flags.pop('gateway_fail_once', False):
                self.calls.append((request.method, path, body))
                route.fulfill(status=500, json={'status': 500, 'message': '网关创建失败'})
                return
            self.gateway_created = True; handled = False
        else:
            handled = False
        if handled:
            self.calls.append((request.method, path, body))
            route.fulfill(json={'status': 200, 'result': result})
        else:
            super().route(route)


def fill_endpoint(page, upload=False):
    page.get_by_role('textbox', name='辨识 ID', exact=True).fill('abc123')
    page.get_by_role('textbox', name='公网 IP', exact=True).fill('203.0.113.10')
    page.get_by_role('spinbutton', name='端口', exact=True).fill('4001')
    if upload:
        page.get_by_text('上传 JAR', exact=True).click()
        page.locator('input[type=file][accept=".jar"]').set_input_files({'name': 'protocol.jar', 'mimeType': 'application/java-archive', 'buffer': b'test-jar'})
        expect(page.get_by_text('protocol.jar', exact=True)).to_be_visible()
    else:
        select(page, '选择 TCP 协议', 'TCP测试协议（tcp-proto）')


def main():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        errors = []
        def new_page(**flags):
            page = browser.new_page(viewport={'width': 1280, 'height': 1000})
            backend = TcpBackend(**flags)
            page.on('pageerror', lambda error: errors.append(str(error)))
            page.route(BASE + '/api/**', backend.route)
            page.goto(BASE + '/?tcp=1'); page.wait_for_load_state('networkidle')
            return page, backend

        page, backend = new_page(gateway_fail_once=True)
        expect(page.get_by_role('heading', name='TCP 服务端接入向导')).to_be_visible()
        assert page.get_by_role('textbox', name='用户名', exact=True).count() == 0
        assert page.get_by_role('textbox', name='客户端 ID', exact=True).count() == 0
        fill_endpoint(page)
        expect(page.get_by_text('tcp_server_abc123 / TCP服务端_abc123')).to_be_visible()
        page.screenshot(path=str(ARTIFACTS / 'tcp-step-1.png'), full_page=True)
        page.get_by_role('button', name='保存接入并继续').click()
        expect(page.get_by_text('网关创建失败', exact=True)).to_be_visible()
        expect(page.get_by_role('textbox', name='辨识 ID')).to_be_disabled()
        page.get_by_role('button', name='保存接入并继续').click()
        expect(page.get_by_role('textbox', name='产品名称')).to_have_value('TCP产品_abc123')
        assert backend.count('POST', '/network/config') == 1
        assert backend.network['id'] == 'tcp_server_abc123'
        assert backend.network['configuration'] == {'host': '0.0.0.0', 'port': 4001, 'publicHost': '203.0.113.10', 'publicPort': 4001, 'secure': False, 'parserType': 'DIRECT', 'parserConfiguration': {}, 'tcpKeepAlive': True}
        assert backend.gateway['id'] == 'tcp_gateway_abc123'
        assert backend.gateway['transport'] == 'TCP' and backend.gateway['provider'] == 'tcp-server-gateway'
        product_step(page)
        assert backend.product['transportProtocol'] == 'TCP'
        assert backend.product['accessProvider'] == 'tcp-server-gateway'
        for format in ('Excel', 'CSV'):
            with page.expect_download() as download:
                page.get_by_role('button', name=f'下载 {format} 模板').click()
            assert download.value.suggested_filename.startswith('TCP')
        upload_devices(page)
        page.get_by_role('button', name='导入并启用').click()
        expect(page.get_by_text('全部成功', exact=True)).to_be_visible()
        page.screenshot(path=str(ARTIFACTS / 'tcp-step-3.png'), full_page=True)
        assert not any('/MQTT' in path for _, path, _ in backend.calls)
        page.close()
        print('PASS TCP defaults, deterministic IDs, gateway retry, no MQTT routes, product binding and imports')

        for conflict in ('network_conflict', 'gateway_conflict'):
            page, backend = new_page(**{conflict: True})
            fill_endpoint(page)
            page.get_by_role('button', name='保存接入并继续').click()
            expect(page.locator('.ant-alert-error')).to_contain_text('已存在')
            assert backend.count('POST', '/network/config') == 0
            assert backend.count('POST', '/gateway/device') == 0
            page.close()
        print('PASS existing component and gateway IDs block creation without overwriting')

        page, backend = new_page()
        fill_endpoint(page, upload=True)
        page.get_by_role('textbox', name='公网 IP').fill('http://203.0.113.10')
        page.get_by_role('button', name='保存接入并继续').click()
        expect(page.locator('.ant-alert-error')).to_contain_text('有效的公网 IP')
        assert backend.count('POST', '/protocol') == 0
        page.get_by_role('textbox', name='公网 IP').fill('203.0.113.10')
        page.get_by_role('button', name='保存接入并继续').click()
        expect(page.get_by_role('textbox', name='产品名称')).to_be_visible()
        created = next(body for method, path, body in backend.calls if method == 'POST' and path == '/protocol')
        assert created['name'] == 'TCP协议_abc123'
        page.close()
        print('PASS invalid endpoint blocks writes and uploaded TCP protocol is selected automatically')

        page, backend = new_page(unsupported=True)
        fill_endpoint(page, upload=True)
        page.get_by_role('button', name='保存接入并继续').click()
        expect(page.locator('.ant-alert-error')).to_contain_text('不支持 TCP')
        assert backend.count('POST', '/network/config') == 0
        page.close()

        page, backend = new_page(reuse=True, start_fail_once=True)
        page.get_by_text('选择已有接入网关', exact=True).click()
        select(page, '已有接入网关', 'TCP网关_abc123（tcp_gateway_abc123）')
        expect(page.locator('.reuse-summary')).to_contain_text('TCP服务端_abc123')
        expect(page.locator('.reuse-summary')).to_contain_text('TCP测试协议')
        expect(page.get_by_role('textbox', name='辨识 ID')).to_have_count(0)
        expect(page.get_by_role('textbox', name='公网 IP')).to_have_count(0)
        expect(page.get_by_role('spinbutton', name='端口')).to_have_count(0)
        page.screenshot(path=str(ARTIFACTS / 'tcp-reuse-gateway.png'), full_page=True, animations='disabled')
        page.get_by_role('button', name='使用已有网关并继续').click()
        expect(page.locator('.ant-alert-error')).to_contain_text('Broker 连接失败')
        page.get_by_role('button', name='使用已有网关并继续').click()
        expect(page.get_by_role('textbox', name='产品名称')).to_have_value('TCP产品_tcp_gateway_abc123')
        assert backend.count('POST', '/network/config') == backend.count('POST', '/gateway/device') == 0
        assert backend.count('POST', '/protocol') == 0
        assert backend.count('POST', '/network/config/tcp_server_abc123/_start') == 2
        product_step(page)
        assert backend.product['accessId'] == 'tcp_gateway_abc123' and backend.product['messageProtocol'] == 'tcp-proto'
        page.close()
        print('PASS TCP gateway reuse and retry without ID, IP, port or protocol entry')

        page, backend = new_page(reuse=True)
        page.get_by_text('选择已有网络组件', exact=True).click()
        select(page, '已有网络组件', 'TCP服务端_abc123（tcp_server_abc123）')
        expect(page.get_by_role('textbox', name='公网 IP')).to_have_count(0)
        select(page, '选择 TCP 协议', 'TCP测试协议（tcp-proto）')
        page.get_by_role('button', name='保存接入并继续').click()
        expect(page.locator('.ant-alert-error')).to_contain_text('辨识 ID 必须为')
        page.get_by_role('textbox', name='辨识 ID').fill('mixed123')
        page.get_by_role('button', name='保存接入并继续').click()
        expect(page.get_by_role('textbox', name='产品名称')).to_be_visible()
        assert backend.count('POST', '/network/config') == 0 and backend.count('POST', '/gateway/device') == 1
        assert backend.gateway['id'] == 'tcp_gateway_mixed123'
        assert backend.gateway['channelId'] == 'tcp_server_abc123'
        page.close()
        print('PASS TCP existing component plus new gateway without endpoint entry')

        page, backend = new_page()
        fill_endpoint(page)
        page.get_by_role('button', name='保存接入并继续').click()
        expect(page.get_by_role('textbox', name='产品名称')).to_be_visible()
        page.locator('input[type=file][accept=".xls,.xlsx,.csv"]').set_input_files({'name': 'metadata.xlsx', 'mimeType': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', 'buffer': b'test workbook'})
        page.get_by_role('button', name='导入物模型', exact=True).click()
        row = page.locator('.metadata-section .ant-table tbody tr').filter(has_text='water')
        expect(row).to_be_visible()
        assert row.locator('td').all_text_contents() == ['water', '水量', '0010', '7', '0']
        page.get_by_role('button', name='创建产品并继续').click()
        expect(page.get_by_role('button', name='下载 Excel 模板')).to_be_visible()
        assert backend.count('POST', '/v1/product/product1/property-metadata/import') == 1
        page.close()
        print('PASS TCP product metadata import and five-column display')

        page, backend = new_page(import_mode='legacy')
        fill_endpoint(page)
        page.get_by_role('button', name='保存接入并继续').click()
        product_step(page); upload_devices(page)
        page.get_by_role('button', name='导入并启用').click()
        expect(page.get_by_text('完成状态待核对', exact=False)).to_be_visible()
        expect(page.get_by_text('已处理 1 条，失败 0 条。', exact=False)).to_be_visible()
        expect(page.get_by_role('link', name='下载导入结果与错误详情')).to_be_visible()
        expect(page.get_by_text('全部成功', exact=True)).not_to_be_visible()
        assert backend.count('GET', '/device-instance/product1/import/_withlog') == 1
        page.close()
        print('PASS TCP legacy result statistics without replay or false completion')
        browser.close()
        assert not errors, errors
        print('ALL TCP SERVER WIZARD BROWSER CHECKS PASSED')


if __name__ == '__main__':
    main()
