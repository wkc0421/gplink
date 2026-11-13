"""Upload real UTF-8/GBK bytes to both Modbus CSV inputs against mocked APIs."""
import importlib.util
from pathlib import Path
from playwright.sync_api import sync_playwright, expect

spec = importlib.util.spec_from_file_location('wizard', Path(__file__).with_name('modbus-simplified.py'))
wizard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(wizard)

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    try:
        backend = wizard.fixture.Backend(empty=True)
        page = wizard.open_page(browser, backend)
        wizard.configure(page)
        page.get_by_role('button', name='下一步', exact=True).click()
        panel = wizard.fixture.panel(page)
        uploads = panel.locator('input[type=file]')
        expect(uploads).to_have_count(2)
        for encoding in ['utf-8', 'utf-8-sig', 'gbk']:
            register = 'propertyId,propertyName,functionCode,address,quantity,dataType,unit\r\ntemp,温度,3,0,1,INT16,℃\r\n'
            uploads.nth(0).set_input_files(dict(name='register.csv', mimeType='text/csv', buffer=register.encode(encoding)))
            expect(panel.get_by_placeholder('名称', exact=True)).to_have_value('温度')
            slave = 'slaveId,deviceName,description\r\n1,一号设备,车间采集\r\n'
            uploads.nth(1).set_input_files(dict(name='slaves.csv', mimeType='text/csv', buffer=slave.encode(encoding)))
            expect(panel.get_by_placeholder('设备名称', exact=True)).to_have_value('一号设备')
            expect(panel.get_by_placeholder('说明', exact=True)).to_have_value('车间采集')
            print('PASS: register and slave upload:', encoding, flush=True)
        for index in [0, 1]:
            page.evaluate('window.__messages = []')
            uploads.nth(index).set_input_files(dict(name='broken.csv', mimeType='text/csv', buffer=b'\x81'))
            page.wait_for_function("() => (window.__messages || []).some(x => x.includes('无法按 UTF-8 或 GBK 解码'))")
            expect(panel.get_by_placeholder('名称', exact=True)).to_have_value('温度')
            expect(panel.get_by_placeholder('设备名称', exact=True)).to_have_value('一号设备')
        assert not backend.writes
        assert not page.errors, page.errors
        print('PASS: undecodable files preserve both current tables; no backend writes', flush=True)
    finally:
        browser.close()

