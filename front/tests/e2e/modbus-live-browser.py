import os
import re
from urllib.parse import urlparse
from playwright.sync_api import sync_playwright


BASE_URL = "http://192.168.5.19:18088"
WIZARD_URL = BASE_URL + "/#/iot/device/ModbusAccess"
LOGIN_URL = BASE_URL + "/#/login"


with sync_playwright() as playwright:
    browser = playwright.chromium.launch(
        headless=True,
        executable_path=os.environ["MODBUS_LIVE_CHROMIUM"],
    )
    context = browser.new_context(viewport={"width": 1600, "height": 1200})
    page = context.new_page()
    auth_requests = []
    auth_responses = []
    auth_failures = []
    console_errors = []
    page.on("request", lambda request: auth_requests.append(
        (request.method, urlparse(request.url).netloc, urlparse(request.url).path)
    ) if "login" in request.url.lower() or ":8848" in request.url else None)
    page.on("response", lambda response: auth_responses.append(
        (response.status, urlparse(response.url).netloc, urlparse(response.url).path)
    ) if "login" in response.url.lower() or ":8848" in response.url else None)
    page.on("requestfailed", lambda request: auth_failures.append(
        (urlparse(request.url).netloc, urlparse(request.url).path, request.failure)
    ) if "login" in request.url.lower() or ":8848" in request.url else None)
    page.on("console", lambda message: console_errors.append(message.text[:300])
            if message.type == "error" else None)
    page.goto(LOGIN_URL, wait_until="domcontentloaded", timeout=60000)

    username = page.get_by_placeholder("请输入账号")
    try:
        username.wait_for(state="visible", timeout=20000)
    except Exception:
        pass
    if username.is_visible():
        username.fill(os.environ["MODBUS_LIVE_USERNAME"])
        password = page.get_by_placeholder("请输入密码")
        password.fill(os.environ["MODBUS_LIVE_PASSWORD"])
        print("LOGIN INPUTS:", page.locator("input").evaluate_all(
            "(nodes) => nodes.map((n) => ({placeholder:n.placeholder, type:n.type, length:n.value.length, usernameValue:n.placeholder.includes('账号') ? '[masked]' : undefined}))"
        ))
        login_button = page.get_by_role("button", name=re.compile(r"登\s*录"))
        if not login_button.is_enabled():
            raise RuntimeError("网页登录按钮当前不可用")
        login_button.click()
        page.wait_for_timeout(500)
        if not auth_requests:
            password.press("Enter")
        page.wait_for_timeout(3000)
        try:
            page.wait_for_load_state("networkidle", timeout=20000)
        except Exception:
            pass

    if page.get_by_placeholder("请输入密码").is_visible():
        body = page.locator("body").inner_text()
        body = body.replace(os.environ["MODBUS_LIVE_USERNAME"], "[username]")
        raise RuntimeError("网页登录未完成，页面仍显示登录框: " + body[:1200])

    page.goto(WIZARD_URL, wait_until="domcontentloaded", timeout=60000)
    try:
        page.wait_for_load_state("networkidle", timeout=20000)
    except Exception:
        pass

    if "Modbus 接入向导" not in page.locator("body").inner_text():
        page.screenshot(path="front/test-results/modbus-live-page-inspection.png", full_page=True)
        body = page.locator("body").inner_text()
        body = body.replace(os.environ["MODBUS_LIVE_USERNAME"], "[username]")
        print("VISIBLE TEXT:", body[:1800])
        print("AUTH REQUESTS:", auth_requests)
        print("AUTH RESPONSES:", auth_responses)
        print("AUTH FAILURES:", auth_failures)
        print("CONSOLE ERRORS:", console_errors[:10])
        raise RuntimeError(f"Modbus 向导未加载，当前 URL: {page.url}")

    print("URL:", page.url)
    print("TITLE:", page.title())
    print("BUTTONS:", page.get_by_role("button").all_text_contents())
    print("INPUTS:", page.locator("input").evaluate_all(
        "(nodes) => nodes.map((n) => ({placeholder:n.placeholder, value:n.value, type:n.type}))"
    ))
    print("TEXT:", page.locator(".modbus-access-page").inner_text()[:6000])
    page.get_by_text("新建 TCP 服务端", exact=True).click()
    page.get_by_placeholder("例如：Modbus TCP 网络组件").fill("Modbus Live TCP 20261021")
    page.get_by_placeholder("例如：Modbus TCP 接入网关").fill("Modbus Live Access 20261021")
    page.get_by_placeholder("设备实际连接的平台地址").fill("192.168.5.19")
    ports = page.locator("section.step-panel:visible .ant-input-number-input")
    ports.nth(0).fill("4000")
    ports.nth(1).fill("4000")
    page.get_by_role("button", name="下一步", exact=True).click()
    page.wait_for_timeout(1000)
    print("STEP 1 TEXT:", page.locator("body").inner_text()[-2500:])
    print("STEP 1 INPUTS:", page.locator("input:visible").evaluate_all(
        "(nodes) => nodes.map((n) => ({placeholder:n.placeholder, type:n.type, value:n.type === 'password' ? '[masked]' : n.value}))"
    ))
    page.screenshot(path="front/test-results/modbus-live-step1.png", full_page=True)
    browser.close()
