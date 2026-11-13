"""Real deployed UI and backend only; requires stdin credential and explicit --execute."""
import argparse
import json
import os
from pathlib import Path
import re
import socket
import subprocess
import threading
import sys
import time
import uuid
from urllib.parse import urlsplit

from playwright.sync_api import sync_playwright, expect

parser = argparse.ArgumentParser()
parser.add_argument('--execute', action='store_true')
parser.add_argument('--port', required=True, type=int)
parser.add_argument('--base', default='http://192.168.5.19:18088')
args = parser.parse_args()
if args.base.rstrip('/') != 'http://192.168.5.19:18088':
    raise SystemExit('Refusing unexpected frontend target')
if not 1024 <= args.port <= 65535:
    raise SystemExit('Invalid port')
if not args.execute:
    print('PLAN ONLY: real deployed UI, 4 uniquely named children; no requests made')
    raise SystemExit(0)

credential = json.loads(sys.stdin.readline())
prefix = 'codex_ui_' + time.strftime('%Y%m%d_%H%M%S') + '_' + uuid.uuid4().hex[:8]
parent = prefix + '_parent'
gp, cp = prefix + '_gp', prefix + '_cp'
children = [prefix + '_c' + str(n) for n in range(1, 5)]
folder = Path(os.environ['TEMP']) / prefix
folder.mkdir()
manifest_file = folder / 'manifest.json'
requests_file = folder / 'requests.jsonl'
manifest = {'prefix': prefix, 'port': args.port, 'frontend': args.base, 'network': None,
            'networkName': prefix + '_network', 'gateway': None, 'gatewayName': prefix + '_access',
            'parent': parent, 'parentProduct': gp, 'childProduct': cp, 'children': children,
            'created': [], 'completed': [], 'failed': False}

def save_manifest():
    manifest_file.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')

save_manifest()
print('MANIFEST=' + str(manifest_file), flush=True)
events = []
tcp_socket = None
tcp_stop = threading.Event()
tcp_thread = None

def crc16(data):
    crc = 0xffff
    for value in data:
        crc ^= value
        for _ in range(8):
            crc = (crc >> 1) ^ (0xa001 if crc & 1 else 0)
    return crc

def serve_test_modbus(sock):
    buffer = b''
    sock.settimeout(0.5)
    while not tcp_stop.is_set():
        try:
            chunk = sock.recv(4096)
            if not chunk:
                return
            buffer += chunk
            while len(buffer) >= 8:
                request, buffer = buffer[:8], buffer[8:]
                expected = int.from_bytes(request[6:8], 'little')
                if crc16(request[:6]) != expected or request[1] != 3:
                    with (folder / 'tcp-frames.jsonl').open('a', encoding='utf-8') as stream:
                        stream.write(json.dumps({'request': request.hex(), 'unsupported_or_bad_crc': True}) + '\n')
                    continue
                quantity = int.from_bytes(request[4:6], 'big')
                if quantity < 1 or quantity > 125:
                    continue
                payload = bytes([request[0], 3, quantity * 2]) + b''.join((100 + request[0]).to_bytes(2, 'big') for _ in range(quantity))
                reply = payload + crc16(payload).to_bytes(2, 'little')
                sock.sendall(reply)
                with (folder / 'tcp-frames.jsonl').open('a', encoding='utf-8') as stream:
                    stream.write(json.dumps({'request': request.hex(), 'response': reply.hex(), 'slave': request[0]}) + '\n')
        except socket.timeout:
            continue
        except OSError:
            return

def verify_online():
    helper = r'C:\Users\wkc\.codex\skills\test-gplink2-test-api\scripts\invoke-gplink2-test-api.ps1'
    evidence = []
    for child in children:
        proc = subprocess.run(['powershell.exe', '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', helper,
                               '-Method', 'GET', '-Path', '/device-instance/' + child + '/state'],
                              capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=60)
        if proc.returncode:
            raise RuntimeError('State API failed for ' + child)
        lines = proc.stdout.splitlines()
        index = lines.index('RESPONSE:')
        body = json.loads(lines[index + 1])
        state = body.get('result')
        state = state.get('value') if isinstance(state, dict) else state
        if body.get('status') != 200 or state != 'online':
            raise RuntimeError('Unexpected runtime state for ' + child + ': ' + str(state))
        evidence.append({'device': child, 'http': 200, 'state': state})
    (folder / 'tcp-runtime-evidence.json').write_text(json.dumps(evidence, indent=2), encoding='utf-8')
    print('FOUR_CHILDREN_ONLINE', flush=True)

def response_event(response):
    path = urlsplit(response.url).path
    if not any(part in path for part in ('/device-instance', '/device/instance/', '/device-product', '/device/gateway/', '/network/config', '/gateway/device')):
        return
    if 'json' not in response.headers.get('content-type', '').lower():
        return
    try:
        body = response.json()
        result = body.get('result') if isinstance(body, dict) else None
        event = {'method': response.request.method, 'path': path, 'http': response.status,
                 'status': body.get('status') if isinstance(body, dict) else None,
                 'success': body.get('success') if isinstance(body, dict) else None}
        if isinstance(result, dict):
            event['result_id'] = result.get('id')
            event['result_success'] = result.get('success')
        elif isinstance(result, (str, int)) and not isinstance(result, bool):
            event['result_id'] = result
        if response.request.method == 'POST' and path.endswith('/network/config') and event.get('result_id'):
            manifest['network'] = str(event['result_id'])
        if response.request.method == 'POST' and path.endswith('/gateway/device') and event.get('result_id'):
            manifest['gateway'] = str(event['result_id'])
        if (response.request.method == 'POST' and response.status == 200 and event.get('status') == 200
                and (path.endswith('/device-product') or path.endswith('/device-instance'))):
            payload = response.request.post_data_json or {}
            if isinstance(payload, dict) and str(payload.get('id', '')).startswith(prefix):
                entry = {'kind': 'product' if path.endswith('/device-product') else 'device',
                         'id': payload['id'], 'name': payload.get('name')}
                if entry not in manifest['created']:
                    manifest['created'].append(entry)
        event['query_validateRuntime'] = 'validateRuntime=true' in urlsplit(response.url).query
        events.append(event)
        with requests_file.open('a', encoding='utf-8') as stream:
            stream.write(json.dumps(event, ensure_ascii=False) + '\n')
        save_manifest()
    except Exception as error:
        print('RESPONSE_CAPTURE_ERROR=' + type(error).__name__, flush=True)

def panel(page):
    return page.locator('section.step-panel:visible')

def field(page, label):
    return page.locator('.ant-form-item:visible').filter(
        has=page.locator('.ant-form-item-label label').filter(has_text=re.compile('^' + re.escape(label) + '$'))
    ).locator('input').first

def paste(page, index, text):
    panel(page).locator('button').filter(has_text='粘贴导入').nth(index).click()
    modal = page.locator('.ant-modal:visible')
    modal.locator('textarea').fill(text)
    modal.get_by_role('button', name=re.compile(r'导\s*入')).click()
    expect(modal).not_to_be_visible()

try:
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True, executable_path=r'C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe')
        try:
            context = browser.new_context(viewport={'width': 1600, 'height': 1300})
            page = context.new_page()
            page.on('response', response_event)
            page.on('pageerror', lambda error: print('PAGE_ERROR=' + str(error), flush=True))
            page.goto(args.base + '/#/login', wait_until='domcontentloaded', timeout=60000)
            page.get_by_placeholder('请输入账号').wait_for(state='visible', timeout=30000)
            page.get_by_placeholder('请输入账号').fill(credential['username'])
            page.get_by_placeholder('请输入密码').fill(credential['password'])
            credential.clear()
            page.get_by_role('button', name=re.compile(r'登\s*录')).click()
            page.get_by_text('Administrator', exact=True).wait_for(state='visible', timeout=30000)
            page.goto(args.base + '/#/iot/device/ModbusAccess', wait_until='domcontentloaded', timeout=60000)
            page.get_by_text('Modbus 接入向导', exact=True).wait_for(state='visible', timeout=30000)
            try:
                page.wait_for_load_state('networkidle', timeout=15000)
            except Exception:
                pass
            page.get_by_text('新建 TCP 服务端', exact=True).click()
            field(page, '公网 IP').fill('192.168.5.19')
            field(page, '监听端口').fill(str(args.port))
            field(page, '监听端口').press('Tab')
            page.get_by_text('高级配置', exact=True).click()
            field(page, '网络组件名称').fill(manifest['networkName'])
            field(page, '接入网关名称').fill(manifest['gatewayName'])
            field(page, '产品 ID').fill(gp)
            field(page, '设备 ID').fill(parent)
            page.get_by_role('button', name='下一步', exact=True).click()
            group = panel(page)
            group.get_by_placeholder('例如：Modbus 从机产品').fill(cp)
            group.get_by_placeholder('留空由系统生成').first.fill(cp)
            page.screenshot(path=str(folder / 'before-import.png'), full_page=True)
            print('BEFORE_IMPORT_PANEL=' + ascii(group.inner_text()[:2500]), flush=True)
            paste(page, 0, 'propertyId,propertyName,functionCode,address,quantity,dataType\nvalue,value,3,0,1,UINT16')
            rows = ['slaveId,deviceId,deviceName'] + [f'{i},{child},{child}' for i, child in enumerate(children, 1)]
            paste(page, 1, '\n'.join(rows))
            page.get_by_role('button', name='下一步', exact=True).click()
            expect(panel(page)).to_contain_text('4 台从机')
            page.screenshot(path=str(folder / 'confirmation.png'), full_page=True)
            print('CONFIRMED_REVIEW; starting actual wizard save', flush=True)
            page.evaluate("""() => {
                window.__wizardSuccessText = '';
                new MutationObserver(() => {
                    for (const el of document.querySelectorAll('.ant-message-notice-content')) {
                        const text = el.innerText || '';
                        if (text.includes('各产品配置已保存，请查看通讯测试结果')) window.__wizardSuccessText = text;
                    }
                }).observe(document.body, {childList: true, subtree: true, characterData: true});
            }""")
            page.locator('button').filter(has_text='保存并测试').last.click()
            deadline = time.monotonic() + 240
            connected_at = None
            verified_online = False
            toast_seen = False
            while time.monotonic() < deadline:
                page.wait_for_timeout(250)
                starts = [e for e in events if e['method'] == 'POST' and e['path'].endswith('/_startup') and e['http'] == 200 and e.get('status') == 200]
                binds = [e for e in events if e['method'] == 'POST' and e['path'].endswith('/bind') and e['query_validateRuntime'] and e['http'] == 200 and e.get('status') == 200]
                if starts and binds and manifest['network'] and manifest['gateway'] and tcp_socket is None:
                    tcp_socket = socket.create_connection(('192.168.5.19', args.port), timeout=5)
                    connected_at = time.monotonic()
                    tcp_thread = threading.Thread(target=serve_test_modbus, args=(tcp_socket,), daemon=True)
                    tcp_thread.start()
                    print('TCP_CONNECTED port=' + str(args.port), flush=True)
                if tcp_socket and not verified_online and time.monotonic() - connected_at >= 1:
                    verify_online()
                    verified_online = True
                if page.evaluate('Boolean(window.__wizardSuccessText)'):
                    toast_seen = True
                result_rows = panel(page).locator('.test-table .ant-table-tbody tr')
                if toast_seen and result_rows.count() >= 4 and verified_online and time.monotonic() - connected_at >= 5:
                    break
                errors = page.locator('.ant-message-error:visible, .ant-notification-notice-error:visible')
                if errors.count():
                    page.screenshot(path=str(folder / 'error.png'), full_page=True)
                    raise RuntimeError('Wizard reports error: ' + ascii(errors.first.inner_text()))
            else:
                page.screenshot(path=str(folder / 'timeout.png'), full_page=True)
                raise RuntimeError('Timed out waiting for final wizard result and child online verification')
            deploys = [e for e in events if e['method'] == 'POST' and e['path'].endswith('/deploy') and e['http'] == 200 and e.get('status') == 200 and e.get('result_success') is not False]
            for child in children:
                if not any(e['path'].endswith('/' + child + '/deploy') for e in deploys):
                    raise RuntimeError('Missing successful child deployment: ' + child)
            manifest['completed'].append('strict-bind-and-gateway-started')
            manifest['completed'].append('final-wizard-success-and-four-online')
            manifest['tcpHeldSeconds'] = round(time.monotonic() - connected_at, 2)
            manifest['resultRows'] = result_rows.all_inner_texts()
            manifest['successMessage'] = page.evaluate('window.__wizardSuccessText')
            save_manifest()
            page.screenshot(path=str(folder / 'result.png'), full_page=True)
            print('WIZARD_SUCCESS manifest=' + str(manifest_file), flush=True)
        finally:
            tcp_stop.set()
            if tcp_socket:
                tcp_socket.close()
            if tcp_thread:
                tcp_thread.join(timeout=2)
            browser.close()
except Exception as error:
    manifest['failed'] = True
    manifest['failure_type'] = type(error).__name__
    save_manifest()
    print('WIZARD_FAILED type=' + type(error).__name__ + ' message=' + str(error) + ' manifest=' + str(manifest_file), flush=True)
    raise
