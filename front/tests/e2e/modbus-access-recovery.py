"""Regression for conflicting legacy products; HTTP is intercepted, never sent to a server.

Run with modbus-wizard-server.mjs listening on 127.0.0.1:9384.
"""
import copy
import importlib.util
import os
import tempfile
from pathlib import Path

from playwright.sync_api import expect, sync_playwright


spec = importlib.util.spec_from_file_location("wizard_regression", Path(__file__).with_name("modbus-multi-product.py"))
wizard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(wizard)


def open_wizard(browser, backend):
    page = browser.new_page(viewport={"width": 1440, "height": 1100})
    errors = []
    page.on("pageerror", lambda error: errors.append(str(error)))
    page.route("**/mock/**", backend.route)
    page.goto("http://127.0.0.1:9384/")
    page.wait_for_load_state("networkidle")
    expect(page.get_by_text("新建 TCP 客户端", exact=True)).to_be_visible()
    return page, errors


def changed_product_blocks_all_writes(browser, product_id):
    backend = wizard.Backend()
    page, errors = open_wizard(browser, backend)
    try:
        wizard.enter_products(page)
        # Simulate a product changed after the wizard's initial read.
        backend.products[product_id]["accessId"] = "original-access"
        before = copy.deepcopy((backend.products, backend.devices, backend.accesses, backend.networks))
        page.get_by_role("button", name="下一步", exact=True).click()
        wizard.save(page)
        page.wait_for_function('() => (window.__messages || []).some(x => x.includes("已使用其他接入网关"))')
        assert not backend.writes, backend.writes
        assert before == (backend.products, backend.devices, backend.accesses, backend.networks)
        assert not errors, errors
        print(f"PASS: changed {product_id} access is detected before any resource write")
    finally:
        page.close()


def quick_access_with_legacy_gateway_blocks_creation(browser):
    backend = wizard.Backend()
    page, errors = open_wizard(browser, backend)
    try:
        page.get_by_text("新建 TCP 客户端", exact=True).click()
        wizard.panel(page).get_by_placeholder("设备网关 IP 或域名").fill("192.168.110.170")
        page.get_by_role("button", name="下一步", exact=True).click()
        expect(wizard.panel(page).get_by_text("采集器产品（pg）", exact=True)).to_be_visible()
        # Keep the existing gateway product, but create a new collector/access.
        wizard.panel(page).get_by_text("新建", exact=True).nth(1).click()
        wizard.panel(page).get_by_placeholder("留空由系统生成").fill("late-collector")
        page.get_by_role("button", name="下一步", exact=True).click()
        wizard.panel(page).get_by_placeholder("例如：Modbus 从机产品").fill("新电表")
        wizard.paste(page, "register", wizard.product_csv("voltage"))
        wizard.paste(page, "slave", "slaveId,deviceId,deviceName\n1,late-meter,电表一")
        page.get_by_role("button", name="下一步", exact=True).click()
        wizard.save(page)
        page.wait_for_function('() => (window.__messages || []).some(x => x.includes("采集器产品") && x.includes("已使用其他接入网关"))')
        assert not backend.writes, backend.writes
        assert not backend.networks
        assert len(backend.accesses) == 1
        assert "late-collector" not in backend.devices
        assert not errors, errors
        print("PASS: legacy gateway product blocks quick access before creating disabled resources")
    finally:
        page.close()


def legacy_slave_selection_is_cleared(browser):
    backend = wizard.Backend()
    page, errors = open_wizard(browser, backend)
    try:
        page.get_by_text("新建 TCP 客户端", exact=True).click()
        wizard.panel(page).get_by_placeholder("设备网关 IP 或域名").fill("192.168.110.170")
        page.get_by_role("button", name="下一步", exact=True).click()
        wizard.panel(page).get_by_text("新建", exact=True).nth(1).click()
        page.get_by_role("button", name="下一步", exact=True).click()
        wizard.panel(page).get_by_text("选择已有", exact=True).click()
        wizard.panel(page).locator(".ant-select-selector").first.click()
        page.locator(".ant-select-dropdown:visible").get_by_text("电表（pa）", exact=True).click()
        page.wait_for_function('() => (window.__messages || []).some(x => x.includes("电表") && x.includes("已使用其他接入网关"))')
        expect(wizard.panel(page).get_by_text("请选择 deviceType=childrenDevice 的产品", exact=True)).to_be_visible()
        assert not backend.writes, backend.writes
        assert not errors, errors
        print("PASS: incompatible legacy slave selection is cleared instead of retaining stale product data")
    finally:
        page.close()


if __name__ == "__main__":
    with sync_playwright() as playwright:
        executable = os.environ.get("MODBUS_TEST_CHROMIUM")
        browser = playwright.chromium.launch(headless=True, **({"executable_path": executable} if executable else {}))
        try:
            changed_product_blocks_all_writes(browser, "pa")
            changed_product_blocks_all_writes(browser, "pg")
            legacy_slave_selection_is_cleared(browser)
            quick_access_with_legacy_gateway_blocks_creation(browser)
            wizard.new_flow(browser, False)
            wizard.new_flow(browser, True)
            # Existing tests capture screenshots; keep this run's artifacts isolated.
            original_directory = Path.cwd()
            with tempfile.TemporaryDirectory(prefix="modbus-recovery-") as output:
                try:
                    os.chdir(output)
                    Path("test-results").mkdir()
                    wizard.existing_flow(browser)
                finally:
                    os.chdir(original_directory)
        finally:
            browser.close()
