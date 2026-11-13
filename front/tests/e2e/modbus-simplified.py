"""Simplified wizard regression. Run against modbus-wizard-server.mjs; no live APIs."""
import importlib.util
import re
from pathlib import Path
from playwright.sync_api import sync_playwright, expect

spec = importlib.util.spec_from_file_location('fixture', Path(__file__).with_name('modbus-multi-product.py'))
fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixture)


def open_page(browser, backend):
    page = browser.new_page(viewport={'width': 1440, 'height': 1100})
    page.errors = []
    page.on('pageerror', lambda error: page.errors.append(str(error)))
    page.route('**/mock/**', backend.route)
    page.goto('http://127.0.0.1:9384/')
    page.wait_for_load_state('networkidle')
    return page


def field(page, label):
    return page.locator('.ant-form-item:visible').filter(
        has=page.locator('.ant-form-item-label label').filter(has_text=re.compile('^' + re.escape(label) + '$'))
    ).locator('input').first


def configure(page, server=True):
    if not server:
        page.get_by_text('新建 TCP 客户端', exact=True).click()
    field(page, '公网 IP').fill('203.0.113.10' if server else '192.168.110.170')
    field(page, '监听端口').fill('8004' if server else '10001')
    field(page, '监听端口').press('Tab')


def products(page):
    page.get_by_role('button', name='下一步', exact=True).click()
    fixture.panel(page).get_by_placeholder('例如：Modbus 从机产品').fill('测试电表')
    fixture.paste(page, 'register', fixture.product_csv('voltage'))
    fixture.paste(page, 'slave', 'slaveId,deviceName\n1,电表一')
    page.get_by_role('button', name='下一步', exact=True).click()


def defaults_and_save(browser, server):
    backend = fixture.Backend()
    page = open_page(browser, backend)
    expect(page.locator('.ant-radio-button-wrapper-checked').first).to_contain_text('新建 TCP 服务端')
    expect(page.locator('.ant-steps-item')).to_have_count(3)
    expect(page.locator('input:visible')).to_have_count(2)
    assert len(backend.writes) == 0
    configure(page, server)
    suffix = '8004' if server else '170_10001'
    summary = page.locator('.resource-summary')
    for value in ['Modbus TCP 网络组件 ' + suffix, 'Modbus TCP 接入网关 ' + suffix,
                  'mb_' + suffix, 'Modbus 网关产品 ' + suffix, 'Modbus 网关 ' + suffix]:
        expect(summary).to_contain_text(value)
    page.screenshot(path=f'test-results/modbus-simplified-{"server" if server else "client"}.png', full_page=True)
    products(page)
    fixture.save(page)
    fixture.saved(page)
    network = backend.networks['network-0']
    access = backend.accesses['access-1']
    assert network['name'] == 'Modbus TCP 网络组件 ' + suffix
    assert access['name'] == 'Modbus TCP 接入网关 ' + suffix
    assert access['configuration']['deviceId'] == 'mb_' + suffix
    assert network['configuration']['port'] == (8004 if server else 10001)
    assert network['configuration']['host'] == ('0.0.0.0' if server else '192.168.110.170')
    if server:
        assert network['configuration']['publicHost'] == '203.0.113.10'
        assert network['configuration']['publicPort'] == 8004
    assert backend.products['mb_' + suffix]['name'] == 'Modbus 网关产品 ' + suffix
    assert backend.devices['mb_' + suffix]['name'] == 'Modbus 网关 ' + suffix
    assert backend.devices['mb_' + suffix + '_1']['parentId'] == 'mb_' + suffix
    expect(page.locator('.ant-steps-item')).to_have_count(3)
    assert not page.errors, page.errors
    page.close()
    print('PASS: example names, IDs, three steps and saved payloads:', suffix)


def overrides_and_validation(browser):
    page = open_page(browser, fixture.Backend(empty=True))
    page.get_by_role('button', name='下一步', exact=True).click()
    expect(page.locator('.ant-modal:visible')).to_contain_text('请输入公网地址')
    page.locator('.ant-modal:visible .ant-modal-close').click()
    configure(page)
    field(page, '监听端口').fill('')
    page.get_by_role('button', name='下一步', exact=True).click()
    expect(page.locator('.ant-modal:visible')).to_contain_text('端口范围')
    page.locator('.ant-modal:visible .ant-modal-close').click()
    field(page, '监听端口').fill('8004')
    field(page, '监听端口').press('Tab')
    page.get_by_text('高级配置', exact=True).click()
    field(page, '网络组件名称').fill('手工名称')
    field(page, '产品 ID').fill('custom_product')
    field(page, '设备 ID').fill('custom_device')
    field(page, '公网端口').fill('18004')
    field(page, '公网端口').press('Tab')
    field(page, '监听端口').fill('8005')
    field(page, '监听端口').press('Tab')
    expect(field(page, '网络组件名称')).to_have_value('手工名称')
    expect(field(page, '产品 ID')).to_have_value('custom_product')
    expect(field(page, '设备 ID')).to_have_value('custom_device')
    expect(field(page, '产品名称')).to_have_value('Modbus 网关产品 8005')
    expect(field(page, '公网端口')).to_have_value('18004')
    page.get_by_role('button', name='恢复自动生成').click()
    expect(field(page, '产品 ID')).to_have_value('mb_8005')
    expect(field(page, '公网端口')).to_have_value('8005')
    page.get_by_text('新建 TCP 客户端', exact=True).click()
    field(page, '公网 IP').fill('192.168.110.170')
    expect(page.locator('.resource-summary')).to_contain_text('mb_170_8005')
    page.get_by_text('新建 TCP 服务端', exact=True).click()
    expect(field(page, '公网 IP')).to_have_value('203.0.113.10')
    page.get_by_text('高级配置', exact=True).click()
    expect(field(page, '监听地址')).to_have_value('0.0.0.0')
    page.get_by_text('新建 TCP 客户端', exact=True).click()
    expect(field(page, '公网 IP')).to_have_value('192.168.110.170')
    assert not page.errors, page.errors
    page.close()
    print('PASS: required fields, overrides, reset and mode switching')


def conflicts_and_retry(browser):
    for kind in ['product', 'device', 'retry']:
        backend = fixture.Backend(empty=True)
        if kind == 'product':
            backend.products['mb_8004'] = dict(id='mb_8004', name='占用产品', deviceType='gateway')
        elif kind == 'device':
            backend.devices['mb_8004'] = dict(id='mb_8004', name='占用设备', productId='other')
        else:
            backend.fail_name = 'Modbus 网关产品 8004'
        page = open_page(browser, backend)
        configure(page)
        products(page)
        fixture.save(page)
        page.wait_for_function("() => (window.__messages || []).some(x => x.includes('失败'))")
        if kind != 'retry':
            assert not backend.writes, backend.writes
            assert page.evaluate("window.__messages").pop().find('已被占用') >= 0
        else:
            page.get_by_role('button', name='上一步', exact=True).click()
            page.get_by_role('button', name='上一步', exact=True).click()
            page.get_by_text('高级配置', exact=True).click()
            expect(field(page, '产品 ID')).to_be_disabled()
            expect(field(page, '设备 ID')).to_be_disabled()
            expect(field(page, '网络组件名称')).to_be_disabled()
            page.get_by_role('button', name='下一步', exact=True).click()
            page.get_by_role('button', name='下一步', exact=True).click()
            fixture.save(page)
            fixture.saved(page)
            assert len(backend.networks) == len(backend.accesses) == 1
            assert len([w for w in backend.writes if w[0] == 'POST' and w[1] == '/device-product' and w[2].get('id') == 'mb_8004']) == 1
            assert len(backend.products) == len(backend.devices) == 2
        assert not page.errors, page.errors
        page.close()
    print('PASS: conflicts stop all writes, partial creation retries reuse locked resources')


if __name__ == '__main__':
    Path('test-results').mkdir(exist_ok=True)
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        try:
            defaults_and_save(browser, True)
            defaults_and_save(browser, False)
            overrides_and_validation(browser)
            conflicts_and_retry(browser)
        finally:
            browser.close()

