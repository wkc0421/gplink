#!/usr/bin/env python3
"""Export the latest available device property values to an Excel workbook."""

import argparse
import datetime as dt
import json
import os
import sys
import tempfile
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlsplit
from urllib.request import Request, urlopen

try:
    from openpyxl import Workbook
    from openpyxl.styles import Font, PatternFill
except ImportError:
    sys.exit("缺少 openpyxl，请先运行：python -m pip install openpyxl")


def request_json(base, token, path, body=None, timeout=30):
    data = None if body is None else json.dumps(body).encode("utf-8")
    headers = {"X-Access-Token": token, "Accept": "application/json"}
    if data is not None:
        headers["Content-Type"] = "application/json"
    request = Request(base + path, data=data, headers=headers)
    try:
        with urlopen(request, timeout=timeout) as response:
            payload = json.load(response)
    except HTTPError as exc:
        raise RuntimeError(f"接口 {path} 返回 HTTP {exc.code}") from exc
    except (URLError, TimeoutError, OSError, ValueError) as exc:
        raise RuntimeError(f"接口 {path} 请求失败：{exc}") from exc
    if not isinstance(payload, dict):
        raise RuntimeError(f"接口 {path} 返回格式不符合预期")
    if payload.get("success") is False or payload.get("status", 200) not in (200, "200"):
        raise RuntimeError(f"接口 {path} 返回错误：{payload.get('message', '未知错误')}")
    return payload.get("result")


def get_devices(base, token, page_size, timeout):
    devices, seen, expected = [], set(), None
    page = 0
    while expected is None or len(devices) < expected:
        result = request_json(base, token, "/api/v1/device/_query", {
            "pageIndex": page, "pageSize": page_size,
            "sorts": [{"name": "id", "order": "asc"}],
        }, timeout)
        if not isinstance(result, dict) or not isinstance(result.get("data"), list):
            raise RuntimeError("设备列表响应缺少分页数据")
        total = result.get("total")
        if not isinstance(total, int) or isinstance(total, bool) or total < 0:
            raise RuntimeError("设备列表响应缺少有效的总数")
        if expected is None:
            expected = total
        elif total != expected:
            raise RuntimeError("导出期间设备总数发生变化，请重新运行")
        items = result["data"]
        if not items and len(devices) < expected:
            raise RuntimeError("设备列表提前结束，请重新运行")
        for device in items:
            if not isinstance(device, dict) or not device.get("id"):
                raise RuntimeError("设备列表包含缺少 ID 的记录")
            device_id = str(device["id"])
            if device_id in seen:
                raise RuntimeError("设备分页中出现重复 ID，请重新运行")
            seen.add(device_id)
            devices.append(device)
        if len(devices) > expected:
            raise RuntimeError("设备列表记录数超过总数，请重新运行")
        page += 1
    return devices


def text(value):
    if value is None:
        return ""
    if isinstance(value, (dict, list, bool)):
        return json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    return str(value)


def time_text(value):
    if value is None or value == "":
        return ""
    try:
        return dt.datetime.fromtimestamp(float(value) / 1000, tz=dt.timezone.utc).astimezone().isoformat(timespec="seconds")
    except (ValueError, TypeError, OverflowError, OSError):
        return ""


def append_text(sheet, values):
    # Explicit string cells keep IDs intact and prevent formula interpretation.
    values = [text(value) for value in values]
    if any(len(value) > 32767 for value in values):
        raise RuntimeError("数据超过 Excel 单元格长度限制，未生成文件")
    sheet.append(values)
    for cell in sheet[sheet.max_row]:
        cell.data_type = "s"


def header(sheet, labels, widths):
    append_text(sheet, labels)
    sheet.freeze_panes = "A2"
    for cell in sheet[1]:
        cell.font = Font(bold=True, color="FFFFFF")
        cell.fill = PatternFill("solid", fgColor="315A7D")
    for column, width in widths.items():
        sheet.column_dimensions[column].width = width


def export(base, token, output, page_size, timeout):
    devices = get_devices(base, token, page_size, timeout)
    if len(devices) > 1048575:
        raise RuntimeError("设备数量超过 Excel 单表行数限制，未生成文件")
    workbook = Workbook()
    summary = workbook.active
    summary.title = "设备清单"
    detail = workbook.create_sheet("最新属性")
    header(summary, ["设备ID", "设备名称", "产品ID", "产品名称", "属性数量", "查询结果"],
           {"A": 26, "B": 25, "C": 26, "D": 25, "E": 12, "F": 25})
    header(detail, ["设备ID", "设备名称", "产品ID", "产品名称", "属性ID", "属性名称", "原始值", "格式化值", "采集时间（本机时区）", "时间戳（毫秒）"],
           {"A": 26, "B": 25, "C": 26, "D": 25, "E": 24, "F": 24, "G": 30, "H": 30, "I": 30, "J": 22})
    count = 0
    for index, device in enumerate(devices, 1):
        device_id = str(device["id"])
        path = "/api/v1/device-data/" + quote(device_id, safe="") + "/properties/redis"
        properties = request_json(base, token, path, timeout=timeout)
        if properties is None:
            properties = []
        if not isinstance(properties, list) or any(not isinstance(p, dict) for p in properties):
            raise RuntimeError(f"设备 {device_id} 的属性响应格式不符合预期")
        if detail.max_row + len(properties) > 1048576:
            raise RuntimeError("属性记录超过 Excel 单表行数限制，未生成文件")
        common = [device_id, device.get("name"), device.get("productId"), device.get("productName")]
        append_text(summary, common + [len(properties), "有数据" if properties else "无可用最新属性"])
        for prop in properties:
            append_text(detail, common + [prop.get("property"), prop.get("propertyName"),
                         prop.get("value"), prop.get("formatValue"), time_text(prop.get("timestamp")), prop.get("timestamp")])
        count += len(properties)
        print(f"\r已查询 {index}/{len(devices)} 台设备", end="", file=sys.stderr, flush=True)
    if devices:
        print(file=sys.stderr)
    for sheet in (summary, detail):
        sheet.auto_filter.ref = sheet.dimensions
    output = output.resolve()
    if output.exists():
        raise RuntimeError(f"输出文件已存在：{output}；请指定新文件名")
    output.parent.mkdir(parents=True, exist_ok=True)
    temp_path = None
    created = False
    try:
        with tempfile.NamedTemporaryFile(suffix=".xlsx", dir=output.parent, delete=False) as temp:
            temp_path = Path(temp.name)
        workbook.save(temp_path)
        # Exclusive creation prevents overwriting an existing workbook.
        with output.open("xb") as target, temp_path.open("rb") as source:
            created = True
            import shutil
            shutil.copyfileobj(source, target)
    except Exception:
        if created:
            output.unlink(missing_ok=True)
        raise
    finally:
        if temp_path is not None:
            temp_path.unlink(missing_ok=True)
    print(f"导出完成：{output}；设备 {len(devices)} 台，属性 {count} 条")


def main():
    parser = argparse.ArgumentParser(description="导出所有可访问设备的最新属性到 Excel")
    parser.add_argument("--base-url", default=os.getenv("GPLINK_BASE_URL"), help="服务根地址，例如 http://127.0.0.1:8848；也可设置 GPLINK_BASE_URL")
    parser.add_argument("--output", type=Path, default=Path("device_latest_" + dt.datetime.now().strftime("%Y%m%d_%H%M%S") + ".xlsx"))
    parser.add_argument("--page-size", type=int, default=100)
    parser.add_argument("--timeout", type=float, default=30)
    args = parser.parse_args()
    token = os.getenv("GPLINK_TOKEN", "").strip()
    if not args.base_url or not token:
        parser.error("请提供 --base-url 或 GPLINK_BASE_URL，并设置 GPLINK_TOKEN")
    base = args.base_url.rstrip("/")
    parsed = urlsplit(base)
    if parsed.scheme not in ("http", "https") or not parsed.netloc or parsed.path or parsed.query or parsed.fragment or parsed.username or parsed.password:
        parser.error("服务地址须为 http(s)://主机:端口，不含路径、凭证或查询参数")
    if args.page_size < 1 or args.timeout <= 0:
        parser.error("--page-size 和 --timeout 必须大于 0")
    try:
        export(base, token, args.output, args.page_size, args.timeout)
    except (RuntimeError, OSError) as exc:
        print(f"导出失败：{exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
