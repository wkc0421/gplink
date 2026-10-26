import os
import re
import sys
import time
from playwright.sync_api import sync_playwright


BASE_URL = "http://192.168.5.19:18088"
USERNAME_PLACEHOLDER = "\u8bf7\u8f93\u5165\u8d26\u53f7"
PASSWORD_PLACEHOLDER = "\u8bf7\u8f93\u5165\u5bc6\u7801"
LOGIN_NAME = r"\u767b\s*\u5f55"
WIZARD_TITLE = "Modbus \u63a5\u5165\u5411\u5bfc"
NEXT = "\u4e0b\u4e00\u6b65"
IMPORT = "\u5bfc\u5165"
PASTE = "\u7c98\u8d34\u5bfc\u5165"
CSV_TEXT = "\u4ece Excel \u590d\u5236\u540e\u76f4\u63a5\u7c98\u8d34\uff0c\u7b2c\u4e00\u884c\u9700\u8981\u662f\u8868\u5934\u3002"

register_a = (
    "propertyId,propertyName,functionCode,address,quantity,dataType,byteOrder,scale,offset,writable,unit\n"
    "temperature,\u6e29\u5ea6,3,0,1,INT16,ABCD,0.1,0,false,\u2103\n"
    "humidity,\u6e7f\u5ea6,3,1,1,INT16,ABCD,0.1,0,false,%RH"
)
slaves_a = (
    "slaveId,deviceId,deviceName,description\n"
    "1,mb-live-20261021-temp-01,\u6e29\u6e7f\u5ea6\u8bbe\u5907-1,\n"
    "6,mb-live-20261021-temp-06,\u6e29\u6e7f\u5ea6\u8bbe\u5907-6,"
)
register_b = (
    "propertyId,propertyName,functionCode,address,quantity,dataType,byteOrder,scale,offset,writable,unit\n"
    "pressure,\u538b\u529b,3,0,2,FLOAT32,ABCD,1,0,false,kPa\n"
    "flow_rate,\u6d41\u91cf,3,2,2,FLOAT32,ABCD,1,0,false,m\u00b3/h"
)
slaves_b = (
    "slaveId,deviceId,deviceName,description\n"
    "2,mb-live-20261021-pressure-02,\u538b\u529b\u6d41\u91cf\u8bbe\u5907-2,\n"
    "7,mb-live-20261021-pressure-07,\u538b\u529b\u6d41\u91cf\u8bbe\u5907-7,"
)


def checked_radio_value(group):
    return group.locator("input:checked").get_attribute("value")


def select_radio(group, value):
    if checked_radio_value(group) != value:
        group.locator(".ant-radio-button-wrapper").filter(
            has_text={"create": "\u65b0\u5efa", "select": "\u9009\u62e9\u5df2\u6709"}[value]
        ).click()
    assert checked_radio_value(group) == value, (value, checked_radio_value(group))


def import_csv(page, panel, index, content):
    buttons = panel.get_by_text(PASTE, exact=True)
    if buttons.count() <= index:
        print("PASTE BUTTONS:", panel.locator("button").all_inner_texts())
        print("VISIBLE PANEL:", panel.inner_text())
    buttons.nth(index).click()
    dialog = page.get_by_role("dialog")
    dialog.locator("textarea").fill(content)
    dialog.locator(".ant-modal-footer .ant-btn-primary").click()
    dialog.wait_for(state="hidden", timeout=15000)
    page.wait_for_timeout(300)


def configure_product_group(page, product_id, product_name, registers, slaves):
    panel = page.locator("section.step-panel:visible")
    radio_groups = panel.locator(".ant-radio-group")
    select_radio(radio_groups.nth(0), "create")
    panel.get_by_placeholder("\u7559\u7a7a\u7531\u7cfb\u7edf\u751f\u6210").fill(product_id)
    panel.get_by_placeholder("\u4f8b\u5982\uff1aModbus \u4ece\u673a\u4ea7\u54c1").fill(product_name)
    import_csv(page, panel, 0, registers)
    import_csv(page, panel, 1, slaves)
    inputs = panel.locator("input:visible")
    values = inputs.evaluate_all("nodes => nodes.map(node => node.value)")
    print("GROUP", product_id, "VISIBLE INPUT VALUES", values)


with sync_playwright() as playwright:
    browser = playwright.chromium.launch(
        headless=True,
        executable_path=os.environ["MODBUS_LIVE_CHROMIUM"],
    )
    context = browser.new_context(viewport={"width": 1600, "height": 1400})
    page = context.new_page()
    page.on("pageerror", lambda error: print("PAGE ERROR:", str(error)))

    page.goto(BASE_URL + "/#/login", wait_until="domcontentloaded", timeout=60000)
    page.get_by_placeholder(USERNAME_PLACEHOLDER).wait_for(state="visible", timeout=30000)
    page.get_by_placeholder(USERNAME_PLACEHOLDER).fill(os.environ["MODBUS_LIVE_USERNAME"])
    page.get_by_placeholder(PASSWORD_PLACEHOLDER).fill(os.environ["MODBUS_LIVE_PASSWORD"])
    page.get_by_role("button", name=re.compile(LOGIN_NAME)).click()
    page.get_by_text("Administrator", exact=True).wait_for(state="visible", timeout=30000)

    page.goto(BASE_URL + "/#/iot/device/ModbusAccess", wait_until="domcontentloaded", timeout=60000)
    page.get_by_text(WIZARD_TITLE, exact=True).wait_for(state="visible", timeout=30000)
    page.get_by_text("\u65b0\u5efa TCP \u670d\u52a1\u7aef", exact=True).click()
    page.get_by_placeholder("\u4f8b\u5982\uff1aModbus TCP \u7f51\u7edc\u7ec4\u4ef6").fill("Modbus Live TCP 20261021")
    page.get_by_placeholder("\u4f8b\u5982\uff1aModbus TCP \u63a5\u5165\u7f51\u5173").fill("Modbus Live Access 20261021")
    page.get_by_placeholder("\u8bbe\u5907\u5b9e\u9645\u8fde\u63a5\u7684\u5e73\u53f0\u5730\u5740").fill("192.168.5.19")
    ports = page.locator("section.step-panel:visible .ant-input-number-input")
    ports.nth(0).fill("4000")
    ports.nth(1).fill("4000")
    page.get_by_role("button", name=NEXT, exact=True).click()
    page.wait_for_timeout(1000)

    panel = page.locator("section.step-panel:visible")
    modes = panel.locator(".ant-radio-group")
    select_radio(modes.nth(0), "create")
    panel.get_by_placeholder("\u7559\u7a7a\u7531\u7cfb\u7edf\u751f\u6210").nth(0).fill("mb-live-20261021-gw-product")
    panel.get_by_placeholder("\u4f8b\u5982\uff1aModbus \u7f51\u5173\u4ea7\u54c1").fill("Modbus Live Gateway Product 20261021")
    select_radio(modes.nth(1), "create")
    panel.get_by_placeholder("\u7559\u7a7a\u7531\u7cfb\u7edf\u751f\u6210").nth(1).fill("mb-live-20261021-collector")
    panel.get_by_placeholder("\u4f8b\u5982\uff1aModbus \u7f51\u5173 1").fill("Modbus Live Collector 20261021")
    assert checked_radio_value(modes.nth(0)) == "create"
    assert checked_radio_value(modes.nth(1)) == "create"
    page.get_by_role("button", name=NEXT, exact=True).click()
    page.wait_for_timeout(800)

    panel = page.locator("section.step-panel:visible")
    assert "mb_test_product" not in panel.inner_text(), "Stale existing collector product remained in the new collector flow"
    assert panel.get_by_text("\u6e29\u5ea6", exact=True).count() == 0
    configure_product_group(page, "mb-live-20261021-temperature", "\u6e29\u6e7f\u5ea6\u91c7\u96c6\u4ea7\u54c1", register_a, slaves_a)
    page.screenshot(path="front/test-results/modbus-live-product-a.png", full_page=True)

    panel.get_by_role("button", name="\u6dfb\u52a0\u4ea7\u54c1", exact=True).click()
    configure_product_group(page, "mb-live-20261021-pressure", "\u538b\u529b\u6d41\u91cf\u91c7\u96c6\u4ea7\u54c1", register_b, slaves_b)
    page.screenshot(path="front/test-results/modbus-live-product-b.png", full_page=True)

    page.get_by_role("button", name=NEXT, exact=True).click()
    page.wait_for_timeout(700)
    panel = page.locator("section.step-panel:visible")
    summary = panel.inner_text()
    print("CONFIRMATION SUMMARY:\n", summary)
    assert "2 \u4e2a\u70b9\u4f4d" in summary and "2 \u53f0\u4ece\u673a" in summary, summary
    assert "\u6e29\u6e7f\u5ea6\u91c7\u96c6\u4ea7\u54c1" in summary
    assert "\u538b\u529b\u6d41\u91cf\u91c7\u96c6\u4ea7\u54c1" in summary
    page.screenshot(path="front/test-results/modbus-live-two-products-confirm.png", full_page=True)
    print("READY: two product groups and four slaves are prepared in the web wizard; press Enter to save and test.", flush=True)
    sys.stdin.readline()

    page.get_by_role("button", name="\u4fdd\u5b58\u5e76\u6d4b\u8bd5", exact=True).click()
    deadline = time.time() + 240
    while time.time() < deadline:
        page.wait_for_timeout(1000)
        visible = page.locator("section.step-panel:visible")
        result_rows = visible.locator(".test-table .ant-table-tbody tr")
        body = visible.inner_text()
        if result_rows.count() >= 4 or "\u914d\u7f6e\u5df2\u4fdd\u5b58" in body or "\u4fdd\u5b58\u5931\u8d25" in body:
            break
    print("POST-SAVE TEXT:\n", page.locator("section.step-panel:visible").inner_text())
    page.screenshot(path="front/test-results/modbus-live-two-products-result.png", full_page=True)
    browser.close()
