"""Local UI regression: real catalog page, isolated in-memory API fixture."""
from pathlib import Path
import re
import sys
from playwright.sync_api import sync_playwright, expect

sys.stdout.reconfigure(encoding='utf-8')
sys.stderr.reconfigure(encoding='utf-8')
OUTPUT = Path(__file__).resolve().parents[2] / '.property-library-test-results' / 'management'
OUTPUT.mkdir(parents=True, exist_ok=True)
BASE = 'http://127.0.0.1:5201/'

def button(scope, text):
    return scope.get_by_role('button', name=re.compile('^' + r'\s*'.join(re.escape(c) for c in text) + '$'))

def fresh(browser, suffix=''):
    page = browser.new_page(viewport={'width': 1440, 'height': 1100})
    errors = []
    page.on('pageerror', lambda e: errors.append(str(e)))
    page.goto(BASE + suffix)
    page.wait_for_load_state('networkidle')
    expect(page.get_by_text('共 57 项', exact=True)).to_be_visible()
    return page, errors

def confirm(page):
    button(page.get_by_role('dialog'), '确定').click()

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    try:
        page, errors = fresh(browser)
        button(page, '0918数据类型配置（5-600号段）').click()
        expect(page.get_by_text('共 353 项', exact=True)).to_be_visible()
        expect(button(page, '未设置分类')).to_be_visible()
        expect(page.locator('.ant-tree-title').get_by_text('未分类', exact=True)).to_be_visible()
        page.get_by_label('搜索采集项', exact=True).fill('Ua')
        page.get_by_label('搜索采集项', exact=True).press('Enter')
        expect(page.get_by_text('A相电压', exact=True)).to_be_visible()
        assert page.locator('tbody tr.ant-table-row').count() >= 1
        page.locator('tbody tr.ant-table-row').filter(has=page.get_by_text('A相电压', exact=True)).get_by_role('checkbox').check()
        button(page, '采纳到标准库').click()
        expect(page.get_by_role('dialog').get_by_text('标准库中的目标分类')).to_be_visible()
        confirm(page)
        expect(page.get_by_text('新增 0 项，跳过 1 项', exact=True)).to_be_visible()
        button(page.get_by_role('dialog'), '取消').click()
        page.screenshot(path=str(OUTPUT / 'project-search.png'), full_page=True)
        assert not errors, errors
        print('PASS: 353-row project, search, multi-select, standard adoption without overwrite')
        page.close()

        page, errors = fresh(browser)
        button(page, '新建库').click()
        dialog = page.get_by_role('dialog')
        dialog.get_by_label('项目库名称').fill('验收项目')
        dialog.get_by_label('项目库编码').fill('review-project')
        confirm(page)
        expect(page.get_by_role('heading', name='验收项目', exact=True)).to_be_visible()
        button(page, '新增').click()
        page.get_by_role('dialog').get_by_label('分类名称').fill('电压分类')
        confirm(page)
        page.locator('.ant-tree-title').filter(has_text='电压分类').click()
        button(page, '新增采集项').click()
        dialog = page.get_by_role('dialog')
        dialog.get_by_label('采集项名称').fill('A相电压')
        dialog.get_by_label('采集项 ID').fill('nOnE')
        confirm(page)
        expect(dialog.get_by_text('仅支持字母、数字和下划线，且不能为 None', exact=True)).to_be_visible()
        dialog.get_by_label('采集项 ID').fill('Ua')
        confirm(page)
        expect(page.get_by_text('共 1 项', exact=True)).to_be_visible()
        row = page.locator('tbody tr.ant-table-row').filter(has=page.get_by_text('A相电压', exact=True))
        button(row, '停用').click()
        expect(row.get_by_text('停用', exact=True)).to_be_visible()
        button(row, '编辑').click()
        page.get_by_role('dialog').get_by_label('采集项名称').fill('输入电压')
        page.evaluate("window.__propertyLibraryFixture.failures['/device/property-library-item/' + window.__propertyLibraryFixture.items().find(i => i.libraryId !== 'standard' && i.name === 'A相电压' && i.code === 'Ua' && !i.id.startsWith('excel')).id] = 1")
        confirm(page)
        expect(page.get_by_role('dialog').get_by_text('模拟请求失败，请重试')).to_be_visible()
        expect(page.get_by_role('dialog').get_by_label('采集项名称')).to_have_value('输入电压')
        confirm(page)
        expect(page.get_by_text('输入电压', exact=True)).to_be_visible()
        row = page.locator('tbody tr.ant-table-row').filter(has_text='输入电压')
        row.get_by_role('checkbox').check()
        button(page, '批量分类').click()
        confirm(page)
        button(page, '未设置分类').click()
        expect(page.get_by_text('输入电压', exact=True)).to_be_visible()
        assert not errors, errors
        print('PASS: create library/category/item, status, failed save retains draft, retry, batch category')
        page.close()

        page, errors = fresh(browser)
        button(page, '导入 Excel').click()
        dialog = page.get_by_role('dialog')
        with page.expect_download() as download:
            button(dialog, '下载模板').click()
        template = download.value
        template.save_as(str(OUTPUT / 'template.csv'))
        assert (OUTPUT / 'template.csv').read_text(encoding='utf-8-sig').startswith('采集项ID,采集项名称,分类路径,备注')
        dialog.locator('input[type=file]').set_input_files({'name': 'test.csv', 'mimeType': 'text/csv', 'buffer': b'code,name\nUa,voltage'})
        expect(dialog.get_by_text('共 3 行 · 有效 1 行 · 已选 1 行', exact=True)).to_be_visible()
        dialog.get_by_label('预览采集项 ID', exact=True).nth(2).fill('Eqc_phase_c')
        expect(dialog.get_by_text('共 3 行 · 有效 3 行 · 已选 1 行', exact=True)).to_be_visible()
        button(dialog, '选择全部有效行').click()
        page.evaluate("window.__propertyLibraryFixture.failures['/device/property-library/standard/import/_confirm'] = 1")
        dialog.get_by_role('button', name=re.compile('确认导入')).click()
        expect(dialog.get_by_text('模拟请求失败，请重试')).to_be_visible()
        expect(dialog.get_by_label('预览采集项 ID', exact=True).nth(2)).to_have_value('Eqc_phase_c')
        dialog.get_by_role('button', name=re.compile('确认导入')).click()
        expect(dialog.get_by_text(re.compile('已新增 .* 项，跳过 .* 项'))).to_be_visible()
        expect(dialog.get_by_text('以下 ID 已存在，已保留库中的内容')).to_be_visible()
        assert page.evaluate("window.__propertyLibraryFixture.items().find(i => i.libraryId === 'standard' && i.code === 'Ua').name") == 'A相电压'
        page.screenshot(path=str(OUTPUT / 'import-conflicts.png'), full_page=True)
        assert not errors, errors
        print('PASS: CSV template, preview duplicate repair updates both rows, retry preserves draft, conflict does not overwrite')
        page.close()

        page, errors = fresh(browser, '?readonly=1')
        expect(button(page, '新建库')).to_have_count(0)
        expect(button(page, '新增采集项')).to_have_count(0)
        expect(page.get_by_text('A相电压', exact=True)).to_be_visible()
        assert not errors, errors
        print('PASS: query-only permissions hide maintenance actions')
        page.close()
    except Exception:
        if 'page' in locals():
            page.screenshot(path=str(OUTPUT / 'failure.png'), full_page=True)
            (OUTPUT / 'failure.html').write_text(page.content(), encoding='utf-8')
        raise
    finally:
        browser.close()
