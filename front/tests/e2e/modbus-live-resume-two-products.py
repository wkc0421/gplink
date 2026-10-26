import os
import re
import time
from playwright.sync_api import sync_playwright


BASE_URL = "http://192.168.5.19:18088"
USERNAME_PLACEHOLDER = "\u8bf7\u8f93\u5165\u8d26\u53f7"
PASSWORD_PLACEHOLDER = "\u8bf7\u8f93\u5165\u5bc6\u7801"
LOGIN_NAME = r"\u767b\s*\u5f55"
WIZARD_TITLE = "Modbus \u63a5\u5165\u5411\u5bfc"
NEXT = "\u4e0b\u4e00\u6b65"
PASTE = "\u7c98\u8d34\u5bfc\u5165"
IMPORT = "\u5bfc\u5165"
PRESSURE_PRODUCT = "\u538b\u529b\u6d41\u91cf\u91c7\u96c6\u4ea7\u54c1"

slaves_b = (
    "slaveId,deviceId,deviceName,description\n"
    "2,mb-live-20261021-pressure-02,\u538b\u529b\u6d41\u91cf\u8bbe\u5907-2,\n"
    "7,mb-live-20261021-pressure-07,\u538b\u529b\u6d41\u91cf\u8bbe\u5907-7,"
)


def checked_radio_value(group):
    return group.locator("input:checked").get_attribute("value")


def select_radio(group, value):
    if checked_radio_value(group) != value:
        label = {"create": "\u65b0\u5efa", "select": "\u9009\u62e9\u5df2\u6709"}[value]
        group.locator(".ant-radio-button-wrapper").filter(has_text=label).click()
    assert checked_radio_value(group) == value


with sync_playwright() as playwright:
    browser = playwright.chromium.launch(
        headless=True,
        executable_path=os.environ["MODBUS_LIVE_CHROMIUM"],
    )
    context = browser.new_context(viewport={"width": 1600, "height": 1400})
    page = context.new_page()
    page.on("pageerror", lambda error: print("PAGE ERROR:", str(error)))
    page.on("requestfailed", lambda request: print("REQUEST FAILED:", request.method, request.url, request.failure))

    page.goto(BASE_URL + "/#/login", wait_until="domcontentloaded", timeout=60000)
    page.get_by_placeholder(USERNAME_PLACEHOLDER).wait_for(state="visible", timeout=30000)
    page.get_by_placeholder(USERNAME_PLACEHOLDER).fill(os.environ["MODBUS_LIVE_USERNAME"])
    page.get_by_placeholder(PASSWORD_PLACEHOLDER).fill(os.environ["MODBUS_LIVE_PASSWORD"])
    page.get_by_role("button", name=re.compile(LOGIN_NAME)).click()
    page.get_by_text("Administrator", exact=True).wait_for(state="visible", timeout=30000)

    page.goto(BASE_URL + "/#/iot/device/ModbusAccess", wait_until="domcontentloaded", timeout=60000)
    page.get_by_text(WIZARD_TITLE, exact=True).wait_for(state="visible", timeout=30000)
    page.wait_for_timeout(3000)
    print("STEP 0:", page.locator("section.step-panel:visible").inner_text())
    page.get_by_role("button", name=NEXT, exact=True).click()
    page.wait_for_timeout(1500)
    print("STEP 1:", page.locator("section.step-panel:visible").inner_text())
    page.get_by_role("button", name=NEXT, exact=True).click()
    page.wait_for_timeout(1500)

    panel = page.locator("section.step-panel:visible")
    existing_values = panel.locator("input:visible").evaluate_all("nodes => nodes.map(node => node.value)")
    assert "mb-live-20261021-temp-01" in existing_values, existing_values
    print("LOADED EXISTING GROUP:", panel.inner_text()[-1500:])
    panel.get_by_role("button", name="\u6dfb\u52a0\u4ea7\u54c1", exact=True).click()
    panel = page.locator("section.step-panel:visible")
    select_radio(panel.locator(".ant-radio-group").nth(0), "select")
    product_select = panel.locator(".ant-form-item").filter(has_text="\u9009\u62e9\u4ece\u673a\u4ea7\u54c1").locator(".ant-select-selector")
    product_select.click()
    option = page.locator(".ant-select-dropdown:visible .ant-select-item-option").filter(has_text="mb-live-20261021-pressure")
    option.wait_for(state="visible", timeout=15000)
    print("PRODUCT OPTION:", option.inner_text())
    option.click()
    page.wait_for_timeout(1000)
    panel = page.locator("section.step-panel:visible")

    panel.get_by_text(PASTE, exact=True).nth(1).click()
    dialog = page.get_by_role("dialog")
    dialog.locator("textarea").fill(slaves_b)
    dialog.locator(".ant-modal-footer .ant-btn-primary").click()
    dialog.wait_for(state="hidden", timeout=15000)
    page.wait_for_timeout(500)
    print("STEP 2 READY:", panel.inner_text()[-1800:])
    page.screenshot(path="front/test-results/modbus-live-resume-step2.png", full_page=True)

    page.get_by_role("button", name=NEXT, exact=True).click()
    page.wait_for_timeout(700)
    panel = page.locator("section.step-panel:visible")
    confirmation = panel.inner_text()
    print("CONFIRMATION:\n", confirmation)
    assert confirmation.count("2 \u4e2a\u70b9\u4f4d") >= 2, confirmation
    assert confirmation.count("2 \u53f0\u4ece\u673a") >= 2, confirmation
    page.screenshot(path="front/test-results/modbus-live-resume-confirm.png", full_page=True)

    page.get_by_role("button", name="\u4fdd\u5b58\u5e76\u6d4b\u8bd5", exact=True).click()
    deadline = time.time() + 300
    while time.time() < deadline:
        page.wait_for_timeout(1000)
        panel = page.locator("section.step-panel:visible")
        body = panel.inner_text()
        rows = panel.locator(".test-table .ant-table-tbody tr")
        if rows.count() >= 4 or "\u4fdd\u5b58\u5931\u8d25" in body:
            break
    panel = page.locator("section.step-panel:visible")
    print("FINAL GROUP STATUS TEXT:\n", panel.inner_text())
    print("TEST ROWS:", panel.locator(".test-table .ant-table-tbody tr").all_inner_texts())
    page.screenshot(path="front/test-results/modbus-live-resume-result.png", full_page=True)
    browser.close()
