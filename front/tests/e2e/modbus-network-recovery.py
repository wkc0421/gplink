"""TCP quick-create recovery against the real wizard and intercepted HTTP only.

Run with modbus-wizard-server.mjs on 127.0.0.1:9384. No remote API is used.
"""
import copy
import importlib.util
import os
import re
from pathlib import Path
from urllib.parse import urlparse

from playwright.sync_api import expect, sync_playwright


spec = importlib.util.spec_from_file_location("wizard_regression", Path(__file__).with_name("modbus-multi-product.py"))
wizard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(wizard)


class Backend(wizard.Backend):
    def __init__(self, failure):
        super().__init__(empty=True)
        self.failure = failure
        self.calls = []
        self.bad_readback = False

    def route(self, route):
        request = route.request
        path = urlparse(request.url).path.removeprefix("/mock")
        method = request.method
        body = request.post_data_json if request.post_data else None
        self.calls.append((method, path))
        stages = {
            "/network/config/network-0/_start": "network-start",
            "/gateway/device/access-0/_startup": "access-start",
            "/gateway/device/access-0/_shutdown": "access-stop",
            "/network/config/network-0/_shutdown": "network-stop",
        }
        stage = stages.get(path)
        if path == "/network/config" and method == "PATCH":
            stage = "patch"
        if self.failure == stage and stage:
            self.failure = None
            self.writes.append((method, path, copy.deepcopy(body)))
            if stage == "access-start":
                # The state write may succeed before runtime startup reports an error.
                self.accesses["access-0"]["state"] = "enabled"
            route.fulfill(json=dict(success=False, status=500, message=f"injected {stage}"))
            return
        if path == "/network/certificate/_query/no-paging":
            route.fulfill(json=dict(success=True, status=200, result=[dict(id="certificate", name="测试证书")]))
            return
        if path == "/network/config" and method == "PATCH":
            self.writes.append((method, path, copy.deepcopy(body)))
            assert body["name"] and body["type"] == self.networks[body["id"]]["type"]
            self.networks[body["id"]].update(body)
            if self.failure == "readback":
                self.failure = None
                self.bad_readback = True
            if self.failure == "patch-response":
                self.failure = None
                route.fulfill(json=dict(success=False, status=500, message="patch response lost"))
            else:
                route.fulfill(json=dict(success=True, status=200, result=True))
            return
        if path.endswith("/_shutdown"):
            self.writes.append((method, path, copy.deepcopy(body)))
            collection = self.networks if path.startswith("/network/") else self.accesses
            if self.failure == f"{stage}-readback":
                self.failure = None
            else:
                collection[path.split("/")[-2]]["state"] = "disabled"
            route.fulfill(json=dict(success=True, status=200, result=True))
            return
        if path == "/network/config/network-0" and method == "GET" and self.bad_readback:
            self.bad_readback = False
            result = copy.deepcopy(self.networks["network-0"])
            result["configuration"]["host"] = "not-the-saved-host"
            route.fulfill(json=dict(success=True, status=200, result=result))
            return
        super().route(route)


def wait_failure(page, text):
    page.wait_for_function("text => (window.__messages || []).some(x => x.includes(text))", arg=text)
    expect(page.locator(".ant-spin-spinning")).to_have_count(0)


def open_failed_wizard(browser, server=False, failure="access-start", tls=False):
    backend = Backend(failure)
    page = browser.new_page(viewport={"width": 1440, "height": 1100})
    errors = []
    page.on("pageerror", lambda error: errors.append(str(error)))
    page.route("**/mock/**", backend.route)
    page.goto("http://127.0.0.1:9384/")
    page.wait_for_load_state("networkidle")
    expect(page.get_by_text("新建 TCP 客户端", exact=True)).to_be_visible()
    page.get_by_text("新建 TCP 服务端" if server else "新建 TCP 客户端", exact=True).click()
    host = wizard.panel(page).get_by_placeholder("0.0.0.0" if server else "设备网关 IP 或域名", exact=True)
    expect(host).to_be_visible()
    host.fill("127.0.0.2")
    if server:
        wizard.panel(page).get_by_placeholder("设备实际连接的平台地址").fill("127.0.0.2")
    if tls:
        wizard.panel(page).get_by_text("TLS（可选）", exact=True).click()
        wizard.panel(page).get_by_role("switch").click()
        wizard.panel(page).get_by_role("combobox").click()
        page.locator(".ant-select-dropdown:visible").get_by_text("测试证书", exact=True).click()
    page.get_by_role("button", name="下一步", exact=True).click()
    wizard.panel(page).get_by_text("新建", exact=True).nth(0).click()
    wizard.panel(page).get_by_text("新建", exact=True).nth(1).click()
    wizard.panel(page).get_by_placeholder("留空由系统生成").nth(0).fill("new-gateway-product")
    wizard.panel(page).get_by_placeholder("留空由系统生成").nth(1).fill("new-collector")
    page.get_by_role("button", name="下一步", exact=True).click()
    wizard.panel(page).get_by_placeholder("例如：Modbus 从机产品").fill("新电表")
    wizard.paste(page, "register", wizard.product_csv("voltage"))
    wizard.paste(page, "slave", "slaveId,deviceId,deviceName\n1,new-meter,电表一")
    page.get_by_role("button", name="下一步", exact=True).click()
    wizard.save(page)
    wait_failure(page, f"injected {failure}")
    assert len(backend.networks) == len(backend.accesses) == 1
    backend.networks["network-0"]["configuration"]["customNetwork"] = {"preserve": True}
    backend.accesses["access-0"]["configuration"]["customAccess"] = "preserve"
    return page, backend, errors


def edit_connection(page, server=False, tls=False):
    for _ in range(3):
        page.get_by_role("button", name="上一步", exact=True).click()
    host = wizard.panel(page).get_by_placeholder("0.0.0.0" if server else "设备网关 IP 或域名", exact=True)
    expect(host).to_be_enabled()
    host.fill("127.0.0.1")
    wizard.panel(page).locator(".ant-input-number-input").first.fill("1502")
    wizard.panel(page).locator(".ant-input-number-input").first.press("Tab")
    if server:
        wizard.panel(page).get_by_placeholder("设备实际连接的平台地址").fill("localhost")
        wizard.panel(page).locator(".ant-input-number-input").nth(1).fill("2502")
        wizard.panel(page).locator(".ant-input-number-input").nth(1).press("Tab")
    if tls:
        # Collapse expansion survives navigating between steps.
        expect(wizard.panel(page).get_by_role("switch")).to_be_enabled()
        wizard.panel(page).get_by_role("switch").click()


def assert_no_start(writes):
    assert not any(path.endswith(("/_start", "/_startup")) for _, path, _ in writes), writes


def assert_reconfigured(backend, server=False):
    assert sum(method == "POST" and path == "/network/config" for method, path, _ in backend.writes) == 1
    assert sum(method == "POST" and path == "/gateway/device" for method, path, _ in backend.writes) == 1
    assert set(backend.networks) == {"network-0"} and set(backend.accesses) == {"access-0"}
    assert set(backend.devices) == {"new-collector", "new-meter"}
    assert len(backend.products) == 2
    configuration = backend.networks["network-0"]["configuration"]
    assert configuration["host"] == "127.0.0.1" and configuration["port"] == 1502
    assert configuration["customNetwork"] == {"preserve": True}
    assert configuration["parserType"] == "DIRECT"
    assert configuration["secure" if server else "ssl"] is False
    assert "certId" not in configuration
    assert backend.accesses["access-0"]["configuration"]["customAccess"] == "preserve"
    assert backend.networks["network-0"]["state"] == backend.accesses["access-0"]["state"] == "enabled"
    if server:
        assert configuration["publicHost"] == "localhost" and configuration["publicPort"] == 2502


def changed_connection_recovery(browser, server):
    page, backend, errors = open_failed_wizard(browser, server, tls=True)
    try:
        edit_connection(page, server, tls=True)
        start = len(backend.calls)
        wizard.save(page)
        wizard.saved(page)
        calls = backend.calls[start:]
        access_stop = calls.index(("POST", "/gateway/device/access-0/_shutdown"))
        access_read = calls.index(("GET", "/gateway/device/access-0"), access_stop + 1)
        network_stop = calls.index(("POST", "/network/config/network-0/_shutdown"))
        network_read = calls.index(("GET", "/network/config/network-0"), network_stop + 1)
        patch = calls.index(("PATCH", "/network/config"))
        readback = calls.index(("GET", "/network/config/network-0"), patch + 1)
        network_start = calls.index(("POST", "/network/config/network-0/_start"))
        access_start = calls.index(("POST", "/gateway/device/access-0/_startup"))
        assert access_stop < access_read < network_stop < network_read < patch < readback < network_start < access_start, calls
        assert_reconfigured(backend, server)
        assert not errors, errors
        print(f"PASS: TCP {'server' if server else 'client'} correction stops/verifies resources, preserves IDs/settings, disables TLS, then restarts")
    finally:
        page.close()


def failure_chain(browser):
    page, backend, errors = open_failed_wizard(browser)
    try:
        edit_connection(page)
        for stage, message in [("access-stop", "停止接入网关失败"), ("access-stop-readback", "接入网关尚未停止"),
                               ("network-stop", "停止 TCP 网络组件失败"), ("network-stop-readback", "网络组件或接入网关尚未停止"),
                               ("patch", "更新 TCP 连接配置失败"), ("readback", "TCP 连接配置保存结果不一致")]:
            backend.failure = stage
            before = len(backend.writes)
            wizard.save(page)
            wait_failure(page, message)
            writes = backend.writes[before:]
            assert_no_start(writes)
            if stage.startswith(("access-stop", "network-stop")):
                assert not any(method == "PATCH" and path == "/network/config" for method, path, _ in writes)
            assert set(backend.networks) == {"network-0"} and set(backend.accesses) == {"access-0"}
        # The prior PATCH succeeded but its readback was bad. Re-read actual state,
        # retain the user's input, and resume without stopping/patching again.
        before = len(backend.writes)
        wizard.save(page)
        wizard.saved(page)
        assert not any(path.endswith("/_shutdown") or (method == "PATCH" and path == "/network/config")
                       for method, path, _ in backend.writes[before:])
        assert_reconfigured(backend)
        assert not errors, errors
        print("PASS: stop/PATCH/readback failures block later starts; IDs and corrected input survive every retry")
    finally:
        page.close()


def unchanged_retry(browser):
    page, backend, errors = open_failed_wizard(browser, failure="network-start")
    try:
        before = len(backend.writes)
        wizard.save(page)
        wizard.saved(page)
        assert not any(path.endswith("/_shutdown") or (method == "PATCH" and path == "/network/config")
                       for method, path, _ in backend.writes[before:])
        assert len(backend.networks) == len(backend.accesses) == 1
        assert not errors, errors
        print("PASS: retry without connection edits reuses resources without extra shutdown/PATCH")
    finally:
        page.close()


def changed_ownership(browser, shared):
    page, backend, errors = open_failed_wizard(browser, failure="network-start")
    try:
        edit_connection(page)
        if shared:
            backend.accesses["other-access"] = dict(backend.accesses["access-0"], id="other-access")
            message = "网络组件已被其他接入网关引用"
        else:
            backend.accesses["access-0"]["channelId"] = "another-network"
            message = "接入网关归属已变化"
        before = copy.deepcopy((backend.networks, backend.accesses, backend.products, backend.devices))
        write_count = len(backend.writes)
        wizard.save(page)
        wait_failure(page, message)
        assert len(backend.writes) == write_count, backend.writes[write_count:]
        assert before == (backend.networks, backend.accesses, backend.products, backend.devices)
        assert not errors, errors
        print(f"PASS: {'shared network' if shared else 'changed access ownership'} blocks all retry mutations")
    finally:
        page.close()


if __name__ == "__main__":
    with sync_playwright() as playwright:
        executable = os.environ.get("MODBUS_TEST_CHROMIUM")
        browser = playwright.chromium.launch(headless=True, **({"executable_path": executable} if executable else {}))
        try:
            changed_connection_recovery(browser, False)
            changed_connection_recovery(browser, True)
            failure_chain(browser)
            unchanged_retry(browser)
            changed_ownership(browser, False)
            changed_ownership(browser, True)
        finally:
            browser.close()
