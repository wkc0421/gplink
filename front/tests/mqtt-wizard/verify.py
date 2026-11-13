"""Isolated browser acceptance: renders the real wizard and real API wrappers with mocked HTTP."""
import json
from pathlib import Path
from urllib.parse import urlparse, parse_qs
from playwright.sync_api import sync_playwright, expect

BASE = 'http://127.0.0.1:5197'
ARTIFACTS = Path(__file__).resolve().parents[2] / 'test-results' / 'mqtt-wizard'
ARTIFACTS.mkdir(parents=True, exist_ok=True)


class Backend:
    def __init__(self, **flags):
        self.flags = flags
        self.calls = []
        self.product = {'id': 'product1', 'name': 'Existing product', 'deviceType': 'device'}
        if self.flags.get('existing_metadata'):
            self.product['metadata'] = json.dumps({'properties': [{'id': 'original', 'name': '原属性', 'expands': {'registerAddr': '99'}, 'sortsIndex': 3, 'valueType': {'scale': 2}}], 'events': [{'id': 'event1'}]})
        self.protocols = [{'id': 'proto1', 'name': '已有协议'}]
        if self.flags.get('second_protocol'):
            self.protocols.append({'id': 'proto2', 'name': '另一个协议'})
        self.network = {'id': 'network1', 'name': 'Existing connection', 'type': 'MQTT_CLIENT'}
        self.gateway = {'id': 'gateway1', 'name': 'Existing gateway', 'channelId': 'network1', 'protocol': 'proto1', 'provider': 'mqtt-client-gateway', 'state': {'value': 'enabled'}}
        if self.flags.get('protocol_missing'):
            self.gateway['protocol'] = ''
        self.device = {'id': 'device1', 'name': 'Existing device', 'productId': 'product1'}

    def route(self, route):
        request = route.request
        path = urlparse(request.url).path.removeprefix('/api')
        try:
            body = request.post_data_json if request.post_data else None
        except Exception:
            body = None
        self.calls.append((request.method, path, body))

        def ok(result=None):
            route.fulfill(json={'status': 200, 'result': result})

        if path == '/file/upload':
            raw = request.post_data_buffer or b''
            jar = b'.jar' in raw
            metadata = b'metadata.xlsx' in raw
            name = 'protocol.jar' if jar else 'metadata.xlsx' if metadata else 'devices.csv'
            ok({'id': 'metadata-file' if metadata else 'file1', 'name': name, 'accessUrl': f'/file/{name}?accessKey=a&b=1'})
        elif path == '/protocol' and request.method == 'POST':
            if self.flags.get('bad_jar'):
                route.fulfill(status=500, json={'status': 500, 'message': '协议包加载失败'})
            else:
                if not self.flags.get('unsupported'):
                    self.protocols.append({'id': 'uploaded-protocol', 'name': body['name']})
                ok({'id': 'uploaded-protocol'})
        elif path == '/protocol/supports/MQTT':
            ok(self.protocols)
        elif path.startswith('/protocol/') and '/transport/' in path:
            ok({'routes': [] if self.flags.get('no_routes') else [{'topic': '/device/+/report', 'upstream': True, 'qos': 0}]})
        elif path == '/network/config/_query':
            networks = [] if self.flags.get('network_missing') else [self.network]
            if self.flags.get('second_network'):
                networks.append({'id': 'network2', 'name': 'Other connection', 'type': 'MQTT_CLIENT'})
            ok(networks)
        elif path == '/network/config' and request.method == 'POST':
            self.network.update(body); ok(self.network)
        elif path.endswith('/_start'):
            if self.flags.pop('start_fail_once', False):
                route.fulfill(status=500, json={'status': 500, 'message': 'Broker 连接失败'})
            else:
                ok()
        elif path.startswith('/network/config/MQTT_CLIENT/_alive'):
            ok([self.network])
        elif path == '/gateway/device/_query/no-paging':
            ok([self.gateway])
        elif path == '/gateway/device' and request.method == 'POST':
            self.gateway.update(body); ok(self.gateway)
        elif path.endswith('/_startup'):
            ok()
        elif path == '/device-product/_query/no-paging':
            ok([self.product])
        elif path == '/device-product' and request.method in ('POST', 'PATCH'):
            self.product.update(body); ok(self.product)
        elif path == '/device-product/product1':
            ok(self.product)
        elif path == '/device-product/product1/deploy':
            if self.flags.pop('product_fail_once', False):
                route.fulfill(status=500, json={'status': 500, 'message': '产品启用失败'})
            else:
                ok(True)
        elif path == '/v1/product/product1/property-metadata/import':
            assert parse_qs(urlparse(request.url).query)['fileUrl'] == ['/file/metadata.xlsx?accessKey=a&b=1']
            if self.flags.pop('metadata_fail_once', False):
                route.fulfill(status=500, json={'status': 500, 'message': '物模型文件格式错误'})
            else:
                self.product['metadata'] = json.dumps({'properties': [
                    {'id': 'water', 'name': '水量', 'expands': {'registerAddr': '0010'}, 'sortsIndex': 7, 'valueType': {'type': 'float', 'scale': 0}},
                    {'id': 'status', 'name': '状态', 'valueType': {'type': 'string'}},
                ], 'events': [{'id': 'event1'}]})
                ok(self.product['metadata'])
        elif path.startswith('/v1/product/property-metadata/properties_template.'):
            route.fulfill(content_type='application/octet-stream', body=b'fixture')
        elif '/template.' in path:
            route.fulfill(content_type='application/octet-stream', body='设备ID,设备名称\ndev01,设备1\n'.encode())
        elif path.endswith('/import/_withlog'):
            params = parse_qs(urlparse(request.url).query)
            assert params['createOnly'] == ['true']
            assert params['fileUrl'] == ['/file/devices.csv?accessKey=a&b=1']
            assert 'token' not in params
            assert request.headers.get('x-access-token') == 'test-token'
            mode = self.flags.get('import_mode', 'success')
            if mode == 'forbidden':
                route.fulfill(status=403, body='forbidden'); return
            if mode == 'legacy':
                rows = [{'success': False, 'detailFile': '/file/result.csv'}, {'success': True, 'result': {'added': 0, 'updated': 1, 'total': 1}}]
                route.fulfill(content_type='text/event-stream', body=''.join('data: ' + json.dumps(row) + '\n\n' for row in rows))
                return
            rows = [{'type': 'row', 'success': True, 'deviceId': 'dev01', 'row': 2}]
            if mode == 'partial':
                rows.append({'type': 'row', 'success': False, 'deviceId': 'existing', 'row': 3, 'message': '设备ID已存在'})
            if mode == 'empty':
                rows = []
            if mode != 'interrupted':
                rows += [{'type': 'detailFile', 'success': True, 'detailFile': '/file/result.csv'}, {'type': 'complete', 'successCount': 0 if mode == 'empty' else 1, 'failureCount': 1 if mode == 'partial' else 0}]
            if mode == 'wrapped':
                rows = [{'status': 200, 'result': row} for row in rows]
            route.fulfill(content_type='text/event-stream', body=''.join('data: ' + json.dumps(row) + '\n\n' for row in rows))
        elif path == '/device-instance/_query/no-paging':
            ok([self.device])
        elif path.endswith('/exists'):
            ok(self.flags.get('existing_device', False))
        elif path == '/device-instance' and request.method == 'POST':
            self.device.update(body); ok(self.device)
        elif path.endswith('/detail'):
            ok(self.device)
        elif path.endswith('/deploy'):
            ok({'success': False, 'message': '设备启用结果失败'} if self.flags.get('device_deploy_failure') else {'success': True})
        elif path == '/network/resources/alive/_current':
            ok([{'id': 'node1'}])
        elif path == '/network/certificate/_query/no-paging':
            ok([])
        else:
            raise AssertionError(f'Unhandled request: {request.method} {path}')

    def count(self, method, path):
        return sum(1 for verb, url, _ in self.calls if verb == method and url == path)


def select(page, label, text):
    page.locator(f'.ant-select[aria-label="{label}"]').click()
    page.locator('.ant-select-dropdown:visible').get_by_text(text, exact=True).click()


def first_step(page, upload=False):
    page.get_by_role('textbox', name='接入 ID', exact=True).fill('abc123')
    if upload:
        page.get_by_text('上传 JAR', exact=True).click()
        page.locator('input[type=file][accept=".jar"]').set_input_files({'name': 'protocol.jar', 'mimeType': 'application/java-archive', 'buffer': b'fake-jar'})
        expect(page.get_by_text('protocol.jar', exact=True)).to_be_visible()
    else:
        select(page, '选择 MQTT 协议', '已有协议（proto1）')
    page.get_by_role('button', name='保存接入并继续', exact=True).click()


def product_step(page):
    expect(page.get_by_role('textbox', name='产品名称', exact=True)).to_be_visible()
    page.get_by_role('button', name='创建产品并继续', exact=True).click()
    expect(page.get_by_role('button', name='下载 Excel 模板')).to_be_visible()


def upload_devices(page):
    page.locator('input[type=file][accept=".xlsx,.csv"]').set_input_files({'name': 'devices.csv', 'mimeType': 'text/csv', 'buffer': '设备ID,设备名称\ndev01,设备1\n'.encode()})
    expect(page.get_by_text('devices.csv', exact=True)).to_be_visible()


def main():
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        errors = []
        def new_page(**flags):
            page = browser.new_page(viewport={'width': 1280, 'height': 900})
            backend = Backend(**flags)
            page.on('pageerror', lambda error: errors.append(str(error)))
            page.route(BASE + '/api/**', backend.route)
            page.goto(BASE); page.wait_for_load_state('networkidle')
            return page, backend

        page, backend = new_page(start_fail_once=True, product_fail_once=True)
        # Inspect the rendered DOM before interactions; retain screenshot for visual review.
        assert page.get_by_role('textbox', name='接入 ID', exact=True).count() == 1
        page.screenshot(path=str(ARTIFACTS / 'step-1.png'), full_page=True)
        page.get_by_role('textbox', name='接入 ID').fill('abc123')
        page.get_by_text('自定义配置', exact=True).filter(visible=True).click()
        page.get_by_role('textbox', name='组件名称', exact=True).fill('自定义组件')
        page.get_by_role('textbox', name='接入 ID').fill('abc456')
        expect(page.get_by_role('textbox', name='组件名称', exact=True)).to_have_value('自定义组件')
        expect(page.get_by_role('textbox', name='客户端 ID', exact=True)).to_have_value('gplink_abc456')
        page.get_by_role('button', name='恢复默认').click()
        expect(page.get_by_role('spinbutton', name='Broker 端口')).to_have_value('2883')
        first_step(page, upload=True)
        expect(page.get_by_text('Broker 连接失败', exact=True)).to_be_visible()
        page.get_by_role('button', name='保存接入并继续').click()
        expect(page.get_by_role('textbox', name='产品名称')).to_have_value('MQTT产品_abc123')
        assert backend.count('POST', '/network/config') == 1
        assert backend.count('POST', '/protocol') == 1
        assert backend.network['configuration']['remoteHost'] == '127.0.0.1'
        assert backend.network['configuration']['password'] == 'mqtt_pwd_abc123'
        page.get_by_role('button', name='创建产品并继续').click()
        expect(page.get_by_text('产品启用失败', exact=True)).to_be_visible()
        page.get_by_role('button', name='创建产品并继续').click()
        expect(page.get_by_role('button', name='下载 Excel 模板')).to_be_visible()
        assert backend.count('POST', '/device-product') == 1
        for format in ('Excel', 'CSV'):
            with page.expect_download() as download:
                page.get_by_role('button', name=f'下载 {format} 模板').click()
            assert download.value.suggested_filename.endswith('.xlsx' if format == 'Excel' else '.csv')
        upload_devices(page)
        assert backend.count('GET', '/device-instance/product1/import/_withlog') == 0
        page.get_by_role('button', name='导入并启用').click()
        expect(page.get_by_text('全部成功', exact=True)).to_be_visible()
        expect(page.get_by_role('button', name='导入并启用')).to_be_disabled()
        page.screenshot(path=str(ARTIFACTS / 'step-3.png'), full_page=True)
        assert backend.count('GET', '/device-instance/product1/import/_withlog') == 1
        page.get_by_role('button', name='上一步', exact=True).filter(visible=True).click()
        expect(page.get_by_text('产品 product1 已保存并启用')).to_be_visible()
        page.close()
        print('PASS defaults, JAR registration, retry checkpoints, template download, upload separation and success')

        for flags, message in [({'no_routes': True}, '协议未声明 MQTT 上行 routes'), ({'unsupported': True}, '不支持 MQTT'), ({'bad_jar': True}, '协议包加载失败')]:
            page, backend = new_page(**flags)
            first_step(page, upload=True)
            expect(page.locator('.ant-alert-error')).to_contain_text(message)
            assert backend.count('POST', '/network/config') == 0
            page.close()
        print('PASS invalid/unsupported/no-route protocols block resource creation')

        page, backend = new_page(metadata_fail_once=True)
        first_step(page)
        expect(page.get_by_role('textbox', name='产品名称')).to_be_visible()
        for format in ('Excel', 'CSV'):
            with page.expect_download() as download:
                page.get_by_role('button', name=f'下载物模型 {format} 模板').click()
            assert download.value.suggested_filename.endswith('.xlsx' if format == 'Excel' else '.csv')
        page.locator('input[type=file][accept=".xls,.xlsx,.csv"]').set_input_files({'name': 'metadata.xlsx', 'mimeType': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', 'buffer': b'test workbook'})
        expect(page.get_by_text('metadata.xlsx', exact=True)).to_be_visible()
        page.get_by_role('button', name='创建产品并继续').click()
        expect(page.locator('.ant-alert-error')).to_contain_text('请先点击“导入物模型”')
        assert backend.count('POST', '/device-product') == 0
        page.get_by_role('button', name='导入物模型', exact=True).click()
        expect(page.locator('.ant-alert-error')).to_contain_text('物模型文件格式错误')
        assert backend.count('POST', '/device-product') == 1 and backend.count('POST', '/device-product/product1/deploy') == 0
        page.get_by_role('button', name='导入物模型', exact=True).click()
        table = page.locator('.metadata-section .ant-table')
        expect(table).to_contain_text('water')
        row = table.locator('tbody tr').filter(has_text='water')
        assert row.locator('td').all_text_contents() == ['water', '水量', '0010', '7', '0']
        assert backend.count('POST', '/device-product') == 1
        assert backend.count('POST', '/v1/product/product1/property-metadata/import') == 2
        page.get_by_role('button', name='刷新物模型').click()
        expect(table).to_contain_text('water')
        assert backend.count('POST', '/v1/product/product1/property-metadata/import') == 2
        page.get_by_role('button', name='创建产品并继续').click()
        expect(page.get_by_role('button', name='下载 Excel 模板')).to_be_visible()
        assert backend.count('POST', '/device-product/product1/deploy') == 1
        page.get_by_role('button', name='上一步', exact=True).filter(visible=True).click()
        expect(table).to_contain_text('water')
        assert json.loads(backend.product['metadata'])['events'] == [{'id': 'event1'}]
        page.close()

        page, backend = new_page(existing_metadata=True)
        first_step(page)
        expect(page.get_by_role('textbox', name='产品名称')).to_be_visible()
        page.get_by_text('选择已有产品', exact=True).click()
        select(page, '已有普通设备产品', 'Existing product（product1）')
        expect(page.get_by_text('导入会替换所选产品的全部属性', exact=False)).to_be_visible()
        expect(page.locator('.metadata-section .ant-table')).to_contain_text('原属性')
        assert backend.count('POST', '/v1/product/product1/property-metadata/import') == 0
        page.locator('input[type=file][accept=".xls,.xlsx,.csv"]').set_input_files({'name': 'metadata.xlsx', 'mimeType': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet', 'buffer': b'test workbook'})
        page.get_by_role('button', name='导入物模型', exact=True).click()
        expect(page.locator('.metadata-section .ant-table')).to_contain_text('水量')
        assert backend.count('POST', '/device-product') == 0
        page.get_by_role('button', name='绑定产品并继续').click()
        expect(page.get_by_role('button', name='下载 Excel 模板')).to_be_visible()
        page.close()
        print('PASS product metadata templates, preview, failure retry, saved readback and existing-product warning')

        for mode, message in [('partial', '部分或全部设备导入失败'), ('interrupted', '导入中断'), ('forbidden', '没有设备导入权限'), ('empty', '未导入设备：文件没有有效数据行')]:
            page, backend = new_page(import_mode=mode)
            first_step(page); product_step(page); upload_devices(page)
            page.get_by_role('button', name='导入并启用').click()
            expect(page.get_by_text(message, exact=False).first).to_be_visible()
            expect(page.get_by_role('button', name='导入并启用')).to_be_disabled()
            assert backend.count('GET', '/device-instance/product1/import/_withlog') == 1
            page.close()
        print('PASS partial failure, interruption, 403 and empty files never report full success')

        for mode in ('legacy', 'wrapped'):
            page, backend = new_page(import_mode=mode)
            first_step(page); product_step(page); upload_devices(page)
            page.get_by_role('button', name='导入并启用').click()
            if mode == 'legacy':
                expect(page.get_by_text('完成状态待核对', exact=False)).to_be_visible()
                expect(page.get_by_text('已处理 1 条，失败 0 条。', exact=False)).to_be_visible()
                expect(page.locator('.ant-alert-error')).to_have_count(0)
                expect(page.get_by_text('全部成功', exact=True)).not_to_be_visible()
            else:
                expect(page.get_by_text('全部成功', exact=True)).to_be_visible()
                expect(page.get_by_text('成功 1 条，失败 0 条。', exact=False)).to_be_visible()
            expect(page.get_by_role('link', name='下载导入结果与错误详情')).to_have_attribute('href', '/file/result.csv')
            expect(page.get_by_role('button', name='导入并启用')).to_be_disabled()
            assert backend.count('GET', '/device-instance/product1/import/_withlog') == 1
            page.screenshot(path=str(ARTIFACTS / f'mqtt-import-{mode}.png'), full_page=True, animations='disabled')
            page.close()
        print('PASS legacy saved totals and wrapped SSE render correctly without replay or false completion')

        page, backend = new_page(second_protocol=True)
        page.get_by_text('选择已有接入网关', exact=True).click()
        page.get_by_role('button', name='使用已有网关并继续').click()
        expect(page.locator('.ant-alert-error')).to_contain_text('请选择已有接入网关')
        select(page, '已有接入网关', 'Existing gateway（gateway1）')
        expect(page.locator('.reuse-summary')).to_contain_text('Existing connection')
        expect(page.locator('.reuse-summary')).to_contain_text('已有协议')
        expect(page.get_by_role('textbox', name='接入 ID')).to_have_count(0)
        expect(page.locator('.ant-select[aria-label="选择 MQTT 协议"]')).to_have_count(0)
        page.screenshot(path=str(ARTIFACTS / 'mqtt-reuse-gateway.png'), full_page=True, animations='disabled')
        page.get_by_role('button', name='使用已有网关并继续').click()
        expect(page.get_by_role('textbox', name='产品名称')).to_have_value('MQTT产品_gateway1')
        assert backend.count('POST', '/network/config') == backend.count('POST', '/gateway/device') == 0
        assert backend.count('POST', '/protocol') == 0
        assert backend.count('POST', '/network/config/network1/_start') == 1
        assert backend.count('POST', '/gateway/device/gateway1/_startup') == 1
        product_step(page)
        assert backend.product['accessId'] == 'gateway1' and backend.product['messageProtocol'] == 'proto1'
        page.close()

        page, backend = new_page(network_missing=True)
        page.get_by_text('选择已有接入网关', exact=True).click()
        select(page, '已有接入网关', 'Existing gateway（gateway1）')
        page.get_by_role('button', name='使用已有网关并继续').click()
        expect(page.locator('.ant-alert-error')).to_contain_text('网关绑定的网络组件不存在或当前账号无权查看')
        assert backend.count('POST', '/network/config') == backend.count('POST', '/gateway/device') == 0
        page.close()

        page, backend = new_page(protocol_missing=True)
        page.get_by_text('选择已有接入网关', exact=True).click()
        select(page, '已有接入网关', 'Existing gateway（gateway1）')
        page.get_by_role('button', name='使用已有网关并继续').click()
        expect(page.locator('.ant-alert-error')).to_contain_text('网关未绑定协议')
        assert backend.count('POST', '/network/config/network1/_start') == 0
        page.close()

        page, backend = new_page(second_network=True, second_protocol=True)
        page.get_by_role('textbox', name='接入 ID').fill('change123')
        page.get_by_text('选择已有网络组件', exact=True).click()
        select(page, '已有网络组件', 'Other connection（network2）')
        select(page, '选择 MQTT 协议', '另一个协议（proto2）')
        page.get_by_text('选择已有接入网关', exact=True).click()
        select(page, '已有接入网关', 'Existing gateway（gateway1）')
        expect(page.locator('.reuse-summary')).to_contain_text('Existing connection')
        page.get_by_text('新建接入网关', exact=True).click()
        expect(page.get_by_role('textbox', name='接入 ID')).to_have_value('change123')
        expect(page.locator('.ant-select[aria-label="已有网络组件"]')).to_contain_text('Other connection')
        expect(page.locator('.ant-select[aria-label="选择 MQTT 协议"]')).to_contain_text('另一个协议')
        page.get_by_text('选择已有接入网关', exact=True).click()
        page.get_by_role('button', name='使用已有网关并继续').click()
        expect(page.get_by_role('textbox', name='产品名称')).to_be_visible()
        assert backend.count('POST', '/network/config') == backend.count('POST', '/gateway/device') == 0
        assert backend.count('POST', '/network/config/network1/_start') == 1
        assert backend.count('POST', '/network/config/network2/_start') == 0
        page.close()
        print('PASS gateway-only reuse without ID, missing bindings, and independent new-resource selections')

        page, backend = new_page()
        page.get_by_role('textbox', name='接入 ID').fill('invalid')
        page.get_by_text('自定义配置', exact=True).filter(visible=True).click()
        page.get_by_role('textbox', name='Broker 地址').fill('')
        page.get_by_role('textbox', name='接入网关名称').fill('')
        page.get_by_text('上传 JAR', exact=True).click()
        page.get_by_text('选择已有接入网关', exact=True).click()
        select(page, '已有接入网关', 'Existing gateway（gateway1）')
        page.get_by_role('button', name='使用已有网关并继续').click()
        expect(page.get_by_role('textbox', name='产品名称')).to_have_value('MQTT产品_gateway1')
        assert all(backend.count('POST', path) == 0 for path in ('/protocol', '/network/config', '/gateway/device'))
        page.close()
        print('PASS hidden invalid new-resource fields and unuploaded JAR do not block gateway reuse')

        page, backend = new_page()
        page.get_by_text('自定义配置', exact=True).filter(visible=True).click()
        page.get_by_role('textbox', name='Broker 地址').fill('')
        page.get_by_role('textbox', name='客户端 ID').fill('')
        page.get_by_text('选择已有网络组件', exact=True).click()
        select(page, '已有网络组件', 'Existing connection（network1）')
        select(page, '选择 MQTT 协议', '已有协议（proto1）')
        page.get_by_role('button', name='保存接入并继续').click()
        expect(page.locator('.ant-alert-error')).to_contain_text('接入 ID 必须为')
        page.get_by_role('textbox', name='接入 ID').fill('mixed123')
        page.get_by_role('button', name='保存接入并继续').click()
        expect(page.get_by_role('textbox', name='产品名称')).to_be_visible()
        assert backend.count('POST', '/network/config') == 0 and backend.count('POST', '/gateway/device') == 1
        assert backend.gateway['name'] == 'MQTT网关_mixed123' and backend.gateway['channelId'] == 'network1'
        page.close()
        print('PASS existing network plus new gateway validates only new-resource fields')

        page, backend = new_page()
        page.get_by_text('选择已有接入网关', exact=True).click()
        select(page, '已有接入网关', 'Existing gateway（gateway1）')
        page.get_by_role('button', name='使用已有网关并继续').click()
        expect(page.get_by_role('textbox', name='产品名称')).to_be_visible()
        assert backend.count('POST', '/network/config') == 0 and backend.count('POST', '/gateway/device') == 0
        page.get_by_text('选择已有产品', exact=True).click()
        select(page, '已有普通设备产品', 'Existing product（product1）')
        page.get_by_role('button', name='绑定产品并继续').click()
        expect(page.get_by_role('button', name='下载 Excel 模板')).to_be_visible()
        assert backend.count('POST', '/device-product') == 0
        page.get_by_text('自定义配置', exact=True).filter(visible=True).click()
        page.get_by_text('手动添加单台设备', exact=True).click()
        page.get_by_role('textbox', name='设备 ID', exact=True).fill('manual123')
        page.get_by_role('textbox', name='设备名称', exact=True).fill('Manual device')
        page.get_by_role('button', name='保存设备').click()
        expect(page.get_by_text('全部成功', exact=True)).to_be_visible()
        assert backend.count('POST', '/device-instance') == 1
        page.close()
        print('PASS reuse preserves connections, product binding and manual device path')

        page, backend = new_page(device_deploy_failure=True)
        first_step(page); product_step(page)
        page.get_by_text('自定义配置', exact=True).filter(visible=True).click()
        page.get_by_text('选择已有设备', exact=True).click()
        select(page, '产品下已有设备', 'Existing device（device1）')
        page.get_by_role('button', name='保存设备').click()
        expect(page.get_by_text('设备启用结果失败', exact=True)).to_be_visible()
        expect(page.get_by_text('全部成功', exact=True)).not_to_be_visible()
        assert backend.count('POST', '/device-instance') == 0
        page.close()

        page, backend = new_page()
        page.get_by_role('textbox', name='接入 ID').fill('abc123')
        page.get_by_text('上传 JAR', exact=True).click()
        page.locator('input[type=file][accept=".jar"]').set_input_files({'name': 'wrong.txt', 'mimeType': 'text/plain', 'buffer': b'wrong'})
        expect(page.get_by_text('请选择 .jar 协议包', exact=True)).to_be_visible()
        assert backend.count('POST', '/file/upload') == 0
        page.close()
        print('PASS existing device deployment result failures and non-JAR rejection')
        browser.close()
        assert not errors, errors
        print('ALL MQTT WIZARD BROWSER CHECKS PASSED')


if __name__ == '__main__':
    main()
