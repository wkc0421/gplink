import os
import re
from urllib.parse import urlparse
from playwright.sync_api import sync_playwright

BASE_URL = "http://192.168.5.19:18088"
LOGIN_URL = BASE_URL + "/#/login"
WIZARD_URL = BASE_URL + "/#/iot/device/ModbusAccess"
USERNAME_PLACEHOLDER = "\u8bf7\u8f93\u5165\u8d26\u53f7"
PASSWORD_PLACEHOLDER = "\u8bf7\u8f93\u5165\u5bc6\u7801"
LOGIN_NAME = r"\u767b\s*\u5f55"
WIZARD_TITLE = "Modbus \u63a5\u5165\u5411\u5bfc"
SERVER_MODE = "\u65b0\u5efa TCP \u670d\u52a1\u7aef"
NETWORK_NAME_PLACEHOLDER = "\u4f8b\u5982\uff1aModbus TCP \u7f51\u7edc\u7ec4\u4ef6"
ACCESS_NAME_PLACEHOLDER = "\u4f8b\u5982\uff1aModbus TCP \u63a5\u5165\u7f51\u5173"
PUBLIC_HOST_PLACEHOLDER = "\u8bbe\u5907\u5b9e\u9645\u8fde\u63a5\u7684\u5e73\u53f0\u5730\u5740"
NEXT_STEP = "\u4e0b\u4e00\u6b65"

with sync_playwright() as playwright:
    browser = playwright.chromium.launch(
        headless=True,
        executable_path=os.environ["MODBUS_LIVE_CHROMIUM"],
    )
    context = browser.new_context(viewport={"width": 1600, "height": 1200})
    page = context.new_page()
    page.goto(LOGIN_URL, wait_until="domcontentloaded", timeout=60000)
    username = page.get_by_placeholder(USERNAME_PLACEHOLDER)
    username.wait_for(state="visible", timeout=30000)
    username.fill(os.environ["MODBUS_LIVE_USERNAME"])
    password = page.get_by_placeholder(PASSWORD_PLACEHOLDER)
    password.fill(os.environ["MODBUS_LIVE_PASSWORD"])
    page.get_by_role("button", name=re.compile(LOGIN_NAME)).click()
    page.get_by_text("Administrator", exact=True).wait_for(state="visible", timeout=30000)

    page.goto(WIZARD_URL, wait_until="domcontentloaded", timeout=60000)
    page.get_by_text(WIZARD_TITLE, exact=True).wait_for(state="visible", timeout=30000)
    page.get_by_text(SERVER_MODE, exact=True).click()
    page.get_by_placeholder(NETWORK_NAME_PLACEHOLDER).fill("Modbus Live TCP 20261021")
    page.get_by_placeholder(ACCESS_NAME_PLACEHOLDER).fill("Modbus Live Access 20261021")
    page.get_by_placeholder(PUBLIC_HOST_PLACEHOLDER).fill("192.168.5.19")
    ports = page.locator("section.step-panel:visible .ant-input-number-input")
    ports.nth(0).fill("4000")
    ports.nth(1).fill("4000")
    page.get_by_role("button", name=NEXT_STEP, exact=True).click()
    page.wait_for_timeout(1000)
    panel = page.locator("section.step-panel:visible")
    create_options = panel.get_by_text("\u65b0\u5efa", exact=True)
    print("NEW OPTIONS:", create_options.count())
    create_options.nth(0).click()
    create_options.nth(1).click()
    page.wait_for_timeout(500)
    ids = panel.get_by_placeholder("\u7559\u7a7a\u7531\u7cfb\u7edf\u751f\u6210")
    ids.nth(0).fill("mb-live-20261021-gw-product")
    panel.get_by_placeholder("\u4f8b\u5982\uff1aModbus \u7f51\u5173\u4ea7\u54c1").fill(
        "Modbus Live Gateway Product 20261021"
    )
    ids.nth(1).fill("mb-live-20261021-collector")
    panel.get_by_placeholder("\u4f8b\u5982\uff1aModbus \u7f51\u5173 1").fill(
        "Modbus Live Collector 20261021"
    )
    page.get_by_role("button", name=NEXT_STEP, exact=True).click()
    page.wait_for_timeout(1000)
    print("URL:", page.url)
    print("VISIBLE TEXT:", page.locator("body").inner_text()[-4500:])
    print("VISIBLE INPUTS:", page.locator("input:visible").evaluate_all(
        "(nodes) => nodes.map((n) => ({placeholder:n.placeholder, type:n.type, value:n.type === 'password' ? '[masked]' : n.value}))"
    ))
    page.screenshot(path="front/test-results/modbus-live-step1.png", full_page=True)
    browser.close()
