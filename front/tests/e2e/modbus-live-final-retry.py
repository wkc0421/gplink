import os
import re
import time
from playwright.sync_api import sync_playwright


BASE_URL = "http://192.168.5.19:18088"
USER_PH = "\u8bf7\u8f93\u5165\u8d26\u53f7"
PASS_PH = "\u8bf7\u8f93\u5165\u5bc6\u7801"
LOGIN_RE = r"\u767b\s*\u5f55"
NEXT = "\u4e0b\u4e00\u6b65"
WIZARD_TITLE = "Modbus \u63a5\u5165\u5411\u5bfc"
TEMP_NAME = "\u6e29\u6e7f\u5ea6\u91c7\u96c6\u4ea7\u54c1"
PRESS_NAME = "\u538b\u529b\u6d41\u91cf\u91c7\u96c6\u4ea7\u54c1"


with sync_playwright() as playwright:
    browser = playwright.chromium.launch(headless=True, executable_path=os.environ["MODBUS_LIVE_CHROMIUM"])
    context = browser.new_context(viewport={"width": 1600, "height": 1400})
    page = context.new_page()
    page.on("pageerror", lambda error: print("PAGE ERROR:", str(error)))
    page.on("requestfailed", lambda request: print("REQUEST FAILED:", request.method, request.url, request.failure))
    def log_response(response):
        if any(x in response.url for x in ["/device/gateway/", "/gateway/device", "/device-instance/", "/device-product/"]):
            print("API RESPONSE:", response.status, response.request.method, response.url.split("/api/")[-1])
            if response.status >= 400:
                print("API ERROR BODY:", response.text()[:1000])
    page.on("response", log_response)
    page.on("request", lambda request: print("API REQUEST:", request.method, request.url.split("/api/")[-1])
            if any(x in request.url for x in ["/device/gateway/", "/gateway/device"]) else None)
    page.on("console", lambda message: print("BROWSER CONSOLE:", message.type, message.text)
            if message.type == "error" else None)

    page.goto(BASE_URL + "/#/login", wait_until="domcontentloaded", timeout=60000)
    page.get_by_placeholder(USER_PH).wait_for(state="visible", timeout=30000)
    page.get_by_placeholder(USER_PH).fill(os.environ["MODBUS_LIVE_USERNAME"])
    page.get_by_placeholder(PASS_PH).fill(os.environ["MODBUS_LIVE_PASSWORD"])
    page.get_by_role("button", name=re.compile(LOGIN_RE)).click()
    page.get_by_text("Administrator", exact=True).wait_for(state="visible", timeout=30000)

    page.goto(BASE_URL + "/#/iot/device/ModbusAccess", wait_until="domcontentloaded", timeout=60000)
    page.get_by_text(WIZARD_TITLE, exact=True).wait_for(state="visible", timeout=30000)
    page.wait_for_timeout(2000)
    print("STEP0:", page.locator("section.step-panel:visible").inner_text())
    page.get_by_role("button", name=NEXT, exact=True).click()
    page.wait_for_timeout(1000)
    print("STEP1:", page.locator("section.step-panel:visible").inner_text())
    page.get_by_role("button", name=NEXT, exact=True).click()
    page.wait_for_timeout(1200)

    panel = page.locator("section.step-panel:visible")
    tab_locator = panel.locator(".ant-tabs-tab")
    tabs = tab_locator.all_inner_texts()
    print("LOADED PRODUCT TABS:", tabs)
    assert any(TEMP_NAME in tab for tab in tabs), tabs
    if not any(PRESS_NAME in tab for tab in tabs):
        panel.get_by_role("button", name="\u6dfb\u52a0\u4ea7\u54c1", exact=True).click()
        panel = page.locator("section.step-panel:visible")
        radios = panel.locator(".ant-radio-group").nth(0)
        if radios.locator("input:checked").get_attribute("value") != "select":
            radios.locator(".ant-radio-button-wrapper").filter(has_text="\u9009\u62e9\u5df2\u6709").click()
        select = panel.locator(".ant-form-item").filter(has_text="\u9009\u62e9\u4ece\u673a\u4ea7\u54c1").locator(".ant-select-selector")
        select.click()
        option = page.locator(".ant-select-dropdown:visible .ant-select-item-option").filter(has_text="mb-live-20261021-pressure")
        option.wait_for(state="visible", timeout=15000)
        option.click()
        page.wait_for_timeout(1000)
        panel = page.locator("section.step-panel:visible")
        print("Missing pressure group; it must be imported before retrying.")
        raise RuntimeError("Pressure group not loaded from backend")

    tab_locator.filter(has_text=PRESS_NAME).click()
    page.wait_for_timeout(500)
    panel = page.locator("section.step-panel:visible")
    values = panel.locator("input:visible").evaluate_all("nodes => nodes.map(node => node.value)")
    print("PRESSURE INPUT VALUES:", values)
    assert "mb-live-20261021-pressure-02" in values and "mb-live-20261021-pressure-07" in values, values

    page.get_by_role("button", name=NEXT, exact=True).click()
    page.wait_for_timeout(500)
    panel = page.locator("section.step-panel:visible")
    print("CONFIRMATION:\n", panel.inner_text())
    page.screenshot(path="front/test-results/modbus-live-final-confirm.png", full_page=True)
    page.get_by_role("button", name="\u4fdd\u5b58\u5e76\u6d4b\u8bd5", exact=True).click()

    deadline = time.time() + 120
    while time.time() < deadline:
        page.wait_for_timeout(1000)
        panel = page.locator("section.step-panel:visible")
        text = panel.inner_text()
        results = panel.locator(".test-table .ant-table-tbody tr")
        if results.count() >= 4 or "\u4fdd\u5b58\u5931\u8d25" in text:
            break
    panel = page.locator("section.step-panel:visible")
    print("FINAL STATUS:\n", panel.inner_text())
    print("RESULT ROWS:", panel.locator(".test-table .ant-table-tbody tr").all_inner_texts())
    page.screenshot(path="front/test-results/modbus-live-final-retry.png", full_page=True)
    browser.close()
