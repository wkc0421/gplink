"""Real wizard browser regression using intercepted HTTP APIs only.

Run with modbus-wizard-server.mjs on port 9384. Requires Python Playwright.
"""
import copy
import json
import os
import re
from pathlib import Path
from urllib.parse import urlparse
from playwright.sync_api import sync_playwright, expect


class Backend:
    def __init__(self, empty=False):
        self.products = {}
        self.devices = {}
        self.accesses = {}
        self.networks = {}
        self.writes = []
        self.reads = []
        self.fail_name = None
        self.fail_once = False
        if not empty:
            for id, name, kind, prop in [('pg', '采集器产品', 'gateway', None), ('pa', '电表', 'childrenDevice', 'voltage'), ('pb', '水表', 'childrenDevice', 'flow')]:
                self.products[id] = dict(id=id, name=name, deviceType=kind, state=1, accessId='access', metadata='{"properties":[]}', configuration={
                    'customProduct': id, **({'registerMap': json.dumps([dict(propertyId=prop, functionCode=3, address=0, quantity=1, dataType='UINT16', byteOrder='ABCD', scale=1, offset=0, writable=False)])} if prop else {})})
            self.devices = {
                'collector': dict(id='collector', name='现场采集器', productId='pg', state='online', configuration={}),
                'meter': dict(id='meter', name='电表一', productId='pa', parentId='collector', state='online', configuration={'slaveId': 1, 'pollOverrideEnabled': True, 'pollIntervalMs': 9999, 'customDevice': 'keep'}),
                'water': dict(id='water', name='水表一', productId='pb', parentId='collector', state='notActive', configuration={'slaveId': 14}),
            }
            self.accesses['access'] = dict(id='access', name='Modbus 接入', provider='tcp-server-gateway', state='enabled', protocol='modbus-rtu.v1', transport='TCP', channelId='network', configuration={'deviceId': 'collector', 'childDeviceIds': ['meter', 'water', 'outsider'], 'customAccess': 'keep'})

    def route(self, route):
        request = route.request
        path = urlparse(request.url).path.removeprefix('/mock')
        method = request.method
        body = request.post_data_json if request.post_data else None
        if method != 'GET' and not path.endswith(('_query', 'no-paging', '_read')):
            self.writes.append((method, path, copy.deepcopy(body)))
        result = None
        status = 200
        if path in ('/gateway/device/detail/_query', '/gateway/device/_query/no-paging'):
            result = list(self.accesses.values())
        elif path == '/device-product/_query/no-paging':
            result = list(self.products.values())
        elif path == '/device-instance/_query/no-paging':
            result = list(self.devices.values())
            for term in (body or {}).get('terms', []):
                result = [value for value in result if value.get(term['column']) == term['value']]
        elif path == '/network/config/supports':
            result = [{'id': 'TCP_SERVER'}, {'id': 'TCP_CLIENT'}]
        elif path == '/gateway/device/providers':
            result = [{'id': 'tcp-server-gateway'}, {'id': 'tcp-client-gateway'}]
        elif path == '/network/certificate/_query/no-paging':
            result = []
        elif path == '/device-product' and method == 'POST':
            id = body.get('id') or f'created-product-{len(self.products)}'
            assert id not in self.products, f'duplicate product creation: {id}'
            self.products[id] = dict(body, id=id, state=0, configuration={}, metadata='{"properties":[]}')
            result = self.products[id]
        elif path == '/device-product' and method == 'PATCH':
            self.products[body['id']].update(body)
            result = True
        elif re.fullmatch('/device-product/[^/]+', path):
            product = self.products[path.split('/')[-1]]
            if method == 'PUT':
                if product['name'] == self.fail_name and not self.fail_once:
                    self.fail_once = True
                    status = 500
                else:
                    product.update(body)
            result = product
        elif re.fullmatch('/device-product/[^/]+/deploy', path):
            self.products[path.split('/')[-2]]['state'] = 1
            result = 1
        elif re.fullmatch('/device-instance/[^/]+/exists', path):
            result = path.split('/')[-2] in self.devices
        elif re.fullmatch('/device-instance/[^/]+/detail', path):
            result = self.devices[path.split('/')[-2]]
        elif path == '/device-instance' and method in ('POST', 'PATCH'):
            id = body['id']
            if method == 'POST':
                assert id not in self.devices, f'duplicate device creation: {id}'
                self.devices[id] = dict(body, state='notActive')
            else:
                self.devices[id].update(body)
            result = self.devices[id]
        elif re.fullmatch('/device-instance/[^/]+/deploy', path):
            self.devices[path.split('/')[-2]]['state'] = 'online'
            result = 1
        elif re.fullmatch('/device/gateway/[^/]+/(bind|unbind)', path):
            parent = path.split('/')[-2]
            for id in body:
                self.devices[id]['parentId'] = parent if path.endswith('/bind') else None
            result = True
        elif path.endswith('/properties/_read'):
            id = path.split('/')[-3]
            self.reads.append((id, body))
            result = {prop: 42 for prop in body}
        elif path == '/network/config' and method == 'POST':
            id = f'network-{len(self.networks)}'
            self.networks[id] = dict(body, id=id)
            result = self.networks[id]
        elif re.fullmatch('/network/config/[^/]+/_start', path):
            self.networks[path.split('/')[-2]]['state'] = 'enabled'
            result = True
        elif re.fullmatch('/network/config/[^/]+', path):
            result = self.networks[path.split('/')[-1]]
        elif path == '/gateway/device' and method == 'POST':
            id = f'access-{len(self.accesses)}'
            self.accesses[id] = dict(body, id=id)
            result = self.accesses[id]
        elif path == '/gateway/device' and method == 'PATCH':
            self.accesses[body['id']].update(body)
            self.accesses[body['id']]['configuration'] = dict(reversed(list(self.accesses[body['id']]['configuration'].items())))
            result = True
        elif re.fullmatch('/gateway/device/[^/]+/_startup', path):
            self.accesses[path.split('/')[-2]]['state'] = 'enabled'
            result = True
        elif re.fullmatch('/gateway/device/[^/]+', path):
            result = self.accesses[path.split('/')[-1]]
        else:
            raise AssertionError(f'unexpected mock API: {method} {path}')
        route.fulfill(json=dict(success=status == 200, status=status, result=result, message='模拟保存失败' if status != 200 else ''))


def panel(page):
    return page.locator('section.step-panel:visible')


def paste(page, kind, text):
    panel(page).get_by_role('button', name='粘贴导入', exact=True).nth(0 if kind == 'register' else 1).click()
    modal = page.locator('.ant-modal:visible')
    modal.locator('textarea').fill(text)
    modal.get_by_role('button', name=re.compile(r'导\s*入')).click()
    expect(modal).not_to_be_visible()


def product_csv(prop, address=0):
    return f'propertyId,propertyName,functionCode,address,quantity,dataType\n{prop},{prop},3,{address},1,UINT16'


def enter_products(page):
    page.get_by_role('button', name='下一步', exact=True).click()
    page.get_by_role('button', name='下一步', exact=True).click()


def save(page):
    page.get_by_role('button', name='保存并测试', exact=True).last.click()


def saved(page, previous=0):
    page.wait_for_function('(n) => (window.__messages || []).filter(x => x.includes("各产品配置已保存")).length > n', arg=previous)
    expect(page.locator('.ant-spin-spinning')).to_have_count(0)


def existing_flow(browser):
    backend = Backend()
    backend.products['pb']['state'] = 0
    page = browser.new_page(viewport={'width': 1440, 'height': 1100})
    errors = []
    page.on('pageerror', lambda error: errors.append(str(error)))
    page.route('**/mock/**', backend.route)
    page.goto('http://127.0.0.1:9384/')
    page.wait_for_load_state('networkidle')
    page.screenshot(path='test-results/modbus-initial.png', full_page=True)
    assert not errors, errors
    enter_products(page)
    expect(page.get_by_role('tab', name='电表', exact=True)).to_be_visible()
    expect(page.get_by_role('tab', name='水表', exact=True)).to_be_visible()
    page.get_by_role('tab', name='水表', exact=True).click()
    expect(panel(page).get_by_placeholder('属性ID')).to_have_value('flow')
    page.get_by_role('tab', name='电表', exact=True).click()
    paste(page, 'register', product_csv('voltage', 2))
    paste(page, 'slave', 'slaveId,deviceId,deviceName\n1,meter,电表一\n2,collector_2,电表二')
    page.get_by_role('tab', name='水表', exact=True).click()
    expect(panel(page).get_by_placeholder('属性ID')).to_have_value('flow')
    page.get_by_role('tab', name='电表', exact=True).click()
    expect(panel(page).get_by_placeholder('设备ID')).to_have_count(2)
    page.get_by_role('button', name='下一步', exact=True).click()
    save(page)
    saved(page)
    assert backend.devices['water']['state'] == 'notActive'
    assert backend.products['pb']['state'] == 0
    assert backend.devices['meter']['configuration']['pollIntervalMs'] == 9999
    assert backend.devices['meter']['configuration']['pollOverrideEnabled'] is True
    assert backend.devices['meter']['configuration']['customDevice'] == 'keep'
    assert backend.products['pa']['configuration']['customProduct'] == 'pa'
    assert backend.accesses['access']['configuration']['customAccess'] == 'keep'
    assert set(backend.accesses['access']['configuration']['childDeviceIds']) == {'meter', 'water', 'collector_2', 'outsider'}
    assert ('meter', ['voltage']) in backend.reads and ('collector_2', ['voltage']) in backend.reads
    assert not any(id == 'water' for id, _ in backend.reads)
    assert not any(path.endswith('/water/deploy') for _, path, _ in backend.writes)
    print('PASS: existing groups hydrate; independent imports; shared configuration and disabled state preserved')

    page.get_by_role('button', name='上一步', exact=True).click()
    page.get_by_role('button', name='添加产品', exact=True).click()
    panel(page).get_by_placeholder('例如：Modbus 从机产品').fill('温度表')
    paste(page, 'register', product_csv('temperature'))
    paste(page, 'slave', 'slaveId,deviceId,deviceName\n20,collector_20,温度一')
    backend.fail_name = '温度表'
    page.get_by_role('button', name='下一步', exact=True).click()
    save(page)
    page.wait_for_function('() => (window.__messages || []).some(x => x.includes("温度表") && x.includes("失败"))')
    assert sum(product['name'] == '温度表' for product in backend.products.values()) == 1
    save(page)
    saved(page, 1)
    assert sum(product['name'] == '温度表' for product in backend.products.values()) == 1
    assert ('collector_20', ['temperature']) in backend.reads
    assert backend.devices['collector_20']['parentId'] == 'collector'
    print('PASS: second product save failure retries without duplicate products; each product tests its own properties')

    page.get_by_role('button', name='上一步', exact=True).click()
    page.get_by_role('tab', name='水表', exact=True).click()
    page.get_by_role('button', name='移除当前产品组', exact=True).click()
    page.get_by_role('button', name='下一步', exact=True).click()
    expect(page.get_by_text('保存时将解绑 1 台已有从机（保留设备和历史数据）', exact=True)).to_be_visible()
    save(page)
    page.locator('.ant-modal-confirm').get_by_role('button', name=re.compile('OK|确.*定')).click()
    saved(page, 2)
    assert backend.devices['water']['parentId'] is None
    assert 'pb' in backend.products and 'water' in backend.devices
    assert 'water' not in backend.accesses['access']['configuration']['childDeviceIds']
    assert 'outsider' in backend.accesses['access']['configuration']['childDeviceIds']
    page.reload()
    page.wait_for_load_state('networkidle')
    enter_products(page)
    expect(page.get_by_role('tab', name='电表', exact=True)).to_be_visible()
    expect(page.get_by_role('tab', name='温度表', exact=True)).to_be_visible()
    expect(page.get_by_role('tab', name='水表', exact=True)).to_have_count(0)
    assert not errors, errors
    page.screenshot(path='test-results/modbus-multi-product.png', full_page=True)
    page.close()
    print('PASS: removal only unbinds; reopening hydrates the remaining product groups')


def new_flow(browser, server_mode):
    backend = Backend(empty=True)
    page = browser.new_page(viewport={'width': 1440, 'height': 1100})
    errors = []
    page.on('pageerror', lambda error: errors.append(str(error)))
    page.route('**/mock/**', backend.route)
    page.goto('http://127.0.0.1:9384/')
    page.wait_for_load_state('networkidle')
    page.get_by_text('新建 TCP 服务端' if server_mode else '新建 TCP 客户端', exact=True).click()
    if server_mode:
        panel(page).get_by_placeholder('设备实际连接的平台地址').fill('127.0.0.1')
    else:
        panel(page).get_by_placeholder('设备网关 IP 或域名').fill('127.0.0.1')
    page.get_by_role('button', name='下一步', exact=True).click()
    panel(page).get_by_text('新建', exact=True).nth(0).click()
    panel(page).get_by_text('新建', exact=True).nth(1).click()
    panel(page).get_by_placeholder('留空由系统生成').nth(0).fill('new-gateway-product')
    panel(page).get_by_placeholder('留空由系统生成').nth(1).fill('new-collector')
    page.get_by_role('button', name='下一步', exact=True).click()
    panel(page).get_by_placeholder('例如：Modbus 从机产品').fill('新电表')
    paste(page, 'register', product_csv('voltage'))
    paste(page, 'slave', 'slaveId,deviceId,deviceName\n1,new-meter,电表一')
    page.get_by_role('button', name='添加产品', exact=True).click()
    panel(page).get_by_placeholder('例如：Modbus 从机产品').fill('新水表')
    paste(page, 'register', product_csv('flow'))
    paste(page, 'slave', 'slaveId,deviceId,deviceName\n14,new-water,水表一')
    page.get_by_role('button', name='下一步', exact=True).click()
    save(page)
    saved(page)
    assert len(backend.networks) == 1 and len(backend.accesses) == 1
    assert len(backend.products) == 3 and len(backend.devices) == 3
    assert all(product['state'] == 1 for product in backend.products.values())
    assert backend.devices['new-meter']['parentId'] == backend.devices['new-water']['parentId'] == 'new-collector'
    assert ('new-meter', ['voltage']) in backend.reads and ('new-water', ['flow']) in backend.reads
    access = next(iter(backend.accesses.values()))
    assert access['state'] == 'enabled'
    if server_mode:
        assert set(access['configuration']['childDeviceIds']) == {'new-meter', 'new-water'}
    else:
        assert 'childDeviceIds' not in access['configuration']
    assert not errors, errors
    page.close()
    print(f'PASS: new TCP {"server" if server_mode else "client"} provisions one collector and two products end to end')


def conflict_and_unavailable(browser):
    backend = Backend()
    page = browser.new_page(viewport={'width': 1440, 'height': 1100})
    page.route('**/mock/**', backend.route)
    page.goto('http://127.0.0.1:9384/')
    page.wait_for_load_state('networkidle')
    enter_products(page)
    page.get_by_role('tab', name='水表', exact=True).click()
    slaves = panel(page).locator('.ant-table-wrapper').last
    slaves.locator('.ant-input-number-input').first.fill('1')
    slaves.locator('.ant-input-number-input').first.press('Tab')
    save(page)
    expect(page.locator('.ant-modal:visible')).to_contain_text('slaveId 1')
    assert not backend.writes, backend.writes
    page.close()
    for unavailable in ('offline', 'disabled'):
        backend = Backend()
        if unavailable == 'offline':
            backend.devices['collector']['state'] = 'offline'
        else:
            backend.accesses['access']['state'] = 'disabled'
        page = browser.new_page(viewport={'width': 1440, 'height': 1100})
        page.route('**/mock/**', backend.route)
        page.goto('http://127.0.0.1:9384/')
        page.wait_for_load_state('networkidle')
        enter_products(page)
        page.get_by_role('button', name='下一步', exact=True).click()
        save(page)
        saved(page)
        expect(page.get_by_text('等待连接' if unavailable == 'offline' else '已跳过', exact=True).first).to_be_visible()
        assert not backend.reads
        assert not any(path.endswith('/_startup') for _, path, _ in backend.writes)
        page.close()
    print('PASS: cross-product duplicate slaveId blocks all writes; offline/disabled resources report waiting/skipped')


def cancel_context_switch(browser):
    backend = Backend()
    backend.devices['other-collector'] = dict(id='other-collector', name='另一个采集器', productId='pg', state='online', configuration={})
    page = browser.new_page(viewport={'width': 1440, 'height': 1100})
    page.route('**/mock/**', backend.route)
    page.goto('http://127.0.0.1:9384/')
    page.wait_for_load_state('networkidle')
    enter_products(page)
    panel(page).get_by_placeholder('属性ID').fill('draftVoltage')
    page.get_by_role('button', name='上一步', exact=True).click()
    panel(page).locator('.ant-select-selector').nth(1).click()
    page.locator('.ant-select-dropdown:visible').get_by_text('另一个采集器（other-collector）', exact=True).click()
    page.locator('.ant-modal-confirm').get_by_role('button', name=re.compile(r'Cancel|取\s*消')).click()
    page.get_by_role('button', name='下一步', exact=True).click()
    expect(panel(page).get_by_placeholder('属性ID')).to_have_value('draftVoltage')
    expect(page.get_by_role('tab', name='水表', exact=True)).to_be_visible()
    assert not backend.writes
    page.close()
    print('PASS: cancelling a collector switch keeps the current selection and unsaved product draft')


if __name__ == '__main__':
    Path('test-results').mkdir(exist_ok=True)
    with sync_playwright() as playwright:
        executable = os.environ.get('MODBUS_TEST_CHROMIUM')
        browser = playwright.chromium.launch(headless=True, **({'executable_path': executable} if executable else {}))
        try:
            existing_flow(browser)
            new_flow(browser, True)
            new_flow(browser, False)
            conflict_and_unavailable(browser)
            cancel_context_switch(browser)
        finally:
            browser.close()
