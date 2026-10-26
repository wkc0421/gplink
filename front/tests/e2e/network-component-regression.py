"""Local browser regression against the real network component Vue pages.

Run from front with the fixture server managed by webapp-testing/with_server.py.
All API requests are intercepted; this never changes a deployed network component.
"""
import copy
import json
import re
from pathlib import Path
import sys

from playwright.sync_api import sync_playwright, expect

BASE = 'http://127.0.0.1:9385'
OUTPUT = Path(__file__).resolve().parents[2] / 'test-results/network-component'
OUTPUT.mkdir(parents=True, exist_ok=True)


def record(kind='TCP_CLIENT', shared=True):
    configuration = {
        'host': '192.168.110.170', 'port': 10001, 'ssl': False,
        'parserType': 'DIRECT', 'parserConfiguration': {'customParserSetting': 'keep'},
        'connectTimeout': 7654,
    }
    if kind == 'TCP_SERVER':
        configuration.update(host='0.0.0.0', port=8001, publicHost='218.200.69.180', publicPort=8001, secure=False)
    if kind == 'MQTT_CLIENT':
        configuration = {'remoteHost': 'mqtt.example.com', 'remotePort': 1883,
                         'clientId': 'mqtt-test', 'username': 'tester', 'password': 'password', 'secure': False}
    return {
        'id': 'network-170', 'name': 'Test network', 'type': kind,
        'typeObject': {'value': kind, 'name': 'unknown' if kind == 'TCP_CLIENT' else kind},
        'shareCluster': shared, 'state': {'value': 'enabled', 'text': '正常'},
        'configuration': configuration if shared else None,
        'cluster': [] if shared else [{'id': 'node-a', 'serverId': 'node-a',
            'tagsFilter': [{'column': 'zone', 'termType': 'eq', 'value': 'a'}],
            'configuration': configuration}],
    }


def run():
    passed = []
    with sync_playwright() as p:
        browser = p.chromium.launch(headless=True)
        context = browser.new_context(locale='zh-CN', viewport={'width': 1440, 'height': 1100})
        page = context.new_page()
        errors = []
        page.on('pageerror', lambda error: errors.append(str(error)))
        if '--inspect' in sys.argv:
            page.on('console', lambda message: print(message.type, message.text, flush=True) if message.type == 'error' else None)
            page.on('pageerror', lambda error: print('PAGE ERROR', str(error), flush=True))
            page.on('requestfailed', lambda request: print('REQUEST FAILED', request.url, request.failure, flush=True))
        state = {'record': record(), 'writes': [], 'supports': True}

        def mock(route):
            request = route.request
            url = request.url.split('/mock', 1)[1]
            body = request.post_data_json if request.post_data else None
            result = []
            if url == '/network/config/supports':
                result = [{'id': kind, 'name': 'unknown' if kind == 'TCP_CLIENT' else kind}
                          for kind in (['TCP_CLIENT', 'TCP_SERVER', 'MQTT_CLIENT'] if state['supports'] else ['TCP_SERVER'])]
            elif url == '/network/config/_query':
                result = {'data': [state['record']], 'total': 1}
            elif url == '/network/config/network-170':
                result = state['record']
            elif url == '/network/config' and request.method in ('PATCH', 'POST'):
                state['writes'].append(copy.deepcopy(body))
                state['record'].update(copy.deepcopy(body))
                result = state['record']
            elif url.startswith('/network/resources/alive'):
                result = [{'clusterNodeId': 'node-a', 'host': '127.0.0.1', 'ports': {'TCP': [8001, 8002]}}]
            elif url == '/network/resources/clusters':
                result = [{'id': 'node-a', 'name': 'Node A', 'tags': {'zone': 'a'}}]
            elif url.startswith('/network/certificate/'):
                result = [{'id': 'cert-1', 'name': 'Test certificate'}]
            elif url.startswith('/system/resources/'):
                result = ''
            else:
                raise AssertionError(f'Unexpected API: {request.method} {url}')
            route.fulfill(json={'status': 200, 'success': True, 'result': result})

        page.route('**/mock/**', mock)

        def button(name):
            return page.get_by_role('button', name=re.compile(r'^' + r'\s*'.join(name) + r'$'))

        def open_edit():
            page.goto(BASE + '/network/network-170?view=false')
            page.wait_for_load_state('networkidle')
            expect(page.get_by_placeholder('请输入名称')).to_have_value('Test network')

        def save():
            previous = len(state['writes'])
            try:
                with page.expect_response(lambda response: response.url.endswith('/mock/network/config')
                                          and response.request.method in ('PATCH', 'POST'), timeout=5000):
                    button('保存').click()
                page.wait_for_load_state('networkidle')
            except Exception:
                page.screenshot(path=str(OUTPUT / 'save-failed.png'), full_page=True)
                print('SAVE FAILED', page.locator('body').inner_text(), errors, flush=True)
                raise
            assert len(state['writes']) == previous + 1, page.locator('.ant-form-item-explain-error').all_text_contents()
            return state['writes'][-1]

        page.goto(BASE)
        page.wait_for_load_state('networkidle')
        if '--inspect' in sys.argv:
            print(page.locator('body').inner_text())
            button('编辑').click()
            page.wait_for_load_state('networkidle')
            print(page.locator('body').inner_text())
            print(page.locator('input').evaluate_all('(inputs) => inputs.map(i => ({placeholder:i.placeholder,value:i.value,id:i.id}))'))
            page.locator('.ant-select').filter(has=page.get_by_role('combobox', name=re.compile('粘拆包规则'))).locator('.ant-select-selector').click()
            print('OPTIONS', page.locator('[role=option]').evaluate_all('(items) => items.map(i => i.outerHTML)'), flush=True)
            print('BODY', page.locator('body').inner_text(), flush=True)
            page.screenshot(path=str(OUTPUT / 'inspection.png'), full_page=True)
            assert not errors, errors
            browser.close()
            return

        expect(page.locator('.network-card')).to_contain_text('TCP客户端')
        expect(page.locator('.network-card')).to_contain_text('tcp://192.168.110.170:10001')
        expect(page.get_by_test_id('table-type')).to_have_text('TCP客户端')
        expect(page.get_by_test_id('table-details')).to_contain_text('tcp://192.168.110.170:10001')
        assert 'unknown' not in page.locator('body').inner_text().lower()
        button('编辑').click()
        page.wait_for_load_state('networkidle')
        expect(page.get_by_placeholder('请输入远程地址')).to_have_value('192.168.110.170')
        expect(page.get_by_placeholder('请输入远程端口')).to_have_value('10001')
        expect(page.get_by_role('combobox', name=re.compile('粘拆包规则'))).to_be_visible()
        page.get_by_placeholder('请输入远程端口').fill('10002')
        payload = save()
        assert payload['configuration']['host'] == '192.168.110.170'
        assert payload['configuration']['port'] == 10002
        assert payload['configuration']['ssl'] is False
        assert payload['configuration']['connectTimeout'] == 7654
        assert payload['configuration']['parserConfiguration']['customParserSetting'] == 'keep'
        open_edit()
        expect(page.get_by_placeholder('请输入远程端口')).to_have_value('10002')
        page.screenshot(path=str(OUTPUT / 'tcp-client-edit.png'), full_page=True)
        passed.append('unknown label and endpoint corrected; card edit/save/reopen preserves remote address and hidden settings')

        page.get_by_placeholder('请输入远程地址').fill('')
        button('保存').click()
        expect(page.locator('.ant-form-item-explain-error').first).to_be_visible()
        assert len(state['writes']) == 1
        page.get_by_placeholder('请输入远程地址').fill('invalid address')
        button('保存').click()
        expect(page.locator('.ant-form-item-explain-error').first).to_be_visible()
        assert len(state['writes']) == 1
        passed.append('empty and invalid remote hosts blocked without sending configuration')

        open_edit()
        page.locator('.ant-select').filter(has=page.get_by_role('combobox', name=re.compile('粘拆包规则'))).locator('.ant-select-selector').click()
        page.locator('.ant-select-item-option-content').filter(has_text=re.compile('^固定长度$')).click()
        page.get_by_placeholder('请输入长度值').fill('8')
        payload = save()
        assert payload['configuration']['parserType'] == 'FIXED_LENGTH'
        assert payload['configuration']['parserConfiguration']['size'] == 8
        assert payload['configuration']['connectTimeout'] == 7654
        passed.append('TCP client parser and parser-specific fields can be edited and saved')

        state['record'] = record()
        state['record']['configuration'].update(ssl=True, certId='cert-1')
        open_edit()
        expect(page.get_by_role('radio', name='是', exact=True)).to_be_checked()
        expect(page.get_by_role('combobox', name=re.compile('证书'))).to_be_visible()
        payload = save()
        assert payload['configuration']['ssl'] is True and payload['configuration']['certId'] == 'cert-1'
        passed.append('existing TLS client uses ssl and saves certificate without requiring server private-key alias')

        open_edit()
        page.get_by_role('radio', name='否', exact=True).check()
        expect(page.get_by_role('combobox', name=re.compile('证书'))).to_have_count(0)
        payload = save()
        assert payload['configuration']['ssl'] is False and not payload['configuration'].get('certId')
        passed.append('turning TLS off saves ssl=false and clears the certificate')

        state['record'] = record(shared=False)
        open_edit()
        expect(page.get_by_placeholder('请输入远程地址')).to_have_value('192.168.110.170')
        page.get_by_placeholder('请输入远程端口').fill('10003')
        payload = save()
        assert payload['cluster'][0]['configuration']['host'] == '192.168.110.170'
        assert payload['cluster'][0]['configuration']['port'] == 10003
        passed.append('node-specific client configuration edits and saves canonical host/port')

        state['record'] = record()
        state['supports'] = False
        open_edit()
        expect(page.locator('.ant-select-selection-item').filter(has_text='TCP客户端')).to_be_visible()
        expect(page.get_by_placeholder('请输入远程地址')).to_have_value('192.168.110.170')
        state['supports'] = True
        passed.append('existing TCP client remains editable when omitted from supports response')

        for kind in ['TCP_SERVER', 'MQTT_CLIENT']:
            state['record'] = record(kind)
            open_edit()
            if kind == 'TCP_SERVER':
                expect(page.get_by_placeholder('请输入公网地址')).to_have_value('218.200.69.180')
                expect(page.get_by_placeholder('请输入远程地址')).to_have_count(0)
            else:
                expect(page.get_by_placeholder('请输入远程地址')).to_have_value('mqtt.example.com')
                expect(page.get_by_placeholder('请输入远程端口')).to_have_value('1883')
            payload = save()
            config = payload['configuration']
            assert config['secure'] is False
            if kind == 'MQTT_CLIENT':
                assert config['remoteHost'] == 'mqtt.example.com' and config['remotePort'] == 1883
            else:
                assert config['host'] == '0.0.0.0' and config['port'] == 8001
            passed.append(f'{kind} existing field names and endpoint settings preserved')

        state['record'] = record()
        page.goto(BASE + '/network/:id?view=false&type=TCP_CLIENT')
        page.wait_for_load_state('networkidle')
        expect(page.get_by_placeholder('请输入远程地址')).to_have_value('')
        page.get_by_placeholder('请输入名称').fill('New TCP client')
        page.get_by_placeholder('请输入远程地址').fill('192.168.110.170')
        page.get_by_placeholder('请输入远程端口').fill('10001')
        payload = save()
        assert payload['type'] == 'TCP_CLIENT'
        assert payload['configuration']['parserType'] == 'DIRECT'
        assert payload['configuration']['ssl'] is False
        passed.append('new TCP client uses empty remote host and DIRECT parser by default')
        assert not errors, errors
        browser.close()
    (OUTPUT / 'regression.json').write_text(json.dumps({'passed': passed, 'pageErrors': errors}, ensure_ascii=False, indent=2), encoding='utf-8')
    for item in passed:
        print('PASS:', item)
    print(f'{len(passed)} browser regression cases passed')


if __name__ == '__main__':
    run()
