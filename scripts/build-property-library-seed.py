"""Build the reviewed property catalogue snapshot without modifying its workbook."""

import argparse
import hashlib
import json
import re
from collections import Counter
from pathlib import Path

from openpyxl import load_workbook


ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "back/jetlinks-community-2.10/jetlinks-community-2.10/jetlinks-manager/saas-manager/src/main/resources/property-catalog/seed-v1.json"
COMMON_CODES = {
    "WaterSum", "FlowSum", "HeatSum", "CoolSum", "HeatPower", "FlowCur",
    "TempSup", "TempBack", "Temp", "Hum", "TempEn",
}


def category(name, code, fid):
    if "谐波" in name or "畸变" in name or "THD" in code:
        return "谐波/电压谐波" if "电压" in name or "THDU" in code or "THD_U" in code else "谐波/电流谐波"
    if 101 <= fid <= 139:
        return "预付费"
    if 201 <= fid <= 219:
        return "水热/水量" if fid <= 203 else "水热/流量与冷热量"
    if 302 <= fid <= 316 or "温度" in name or "湿度" in name:
        return "环境/湿度" if "湿度" in name else "环境/温度"
    if code.startswith("YX"):
        return "状态控制/遥信"
    if code.startswith("YK") or code.startswith("Cmd_"):
        return "状态控制/遥控"
    if "Fault" in code or "Alarm" in code or "Event" in code or "状态" in name or code == "PowerOff_State":
        return "状态控制/状态与告警"
    if "设置" in name or code.endswith("_Comp") or code == "Mod_Dev_Addr":
        return "设备配置"
    if "倍率" in name or "变比" in name:
        return "电参量/倍率"
    if "需量" in name:
        return "电参量/需量"
    if "电能" in name or "电度" in name:
        return "电参量/电能"
    if "功率因数" in name or "COS" in name.upper():
        return "电参量/功率因数"
    if "功率" in name or "损耗" in name:
        return "电参量/功率"
    if "电压" in name:
        return "电参量/电压"
    if "电流" in name:
        return "电参量/电流"
    if "频率" in name:
        return "电参量/频率"
    return "未分类"


def build(workbook_path):
    workbook = load_workbook(workbook_path, read_only=True, data_only=True)
    try:
        sheet = workbook["电能质量数据ID"]
        candidates = []
        for row_number, row in enumerate(sheet.iter_rows(values_only=True), 1):
            if row_number <= 2:
                continue
            name, code = (str(value or "").strip() for value in row[3:5])
            if not code or code.lower() == "none" or name.lower() == "none":
                continue
            if str(row[1]).strip() != "1":
                raise ValueError(f"Review required: unexpected status on row {row_number}")
            if not re.fullmatch(r"[a-zA-Z0-9_]{1,64}", code) or not 1 <= len(name) <= 64:
                raise ValueError(f"Review required: invalid name/code on row {row_number}")
            fid_text = str(row[2] or "").strip()
            fid = int(fid_text) if fid_text.isdigit() else 0
            source = {"file": workbook_path.name, "sheet": sheet.title, "row": row_number, "fid": fid_text}
            item = {"code": code, "name": name, "categoryPath": category(name, code, fid),
                    "sortIndex": row_number - 3, "source": source}
            notes = list(dict.fromkeys(str(value).strip() for value in row[5:] if value is not None and str(value).strip()))
            if notes:
                item["remark"] = "；".join(notes)
            candidates.append(item)
        counts = Counter(item["code"] for item in candidates)
        conflicts = [{**item, "reason": "同一项目库编码重复且名称不同，需人工确认编码"}
                     for item in candidates if counts[item["code"]] > 1]
        items = [item for item in candidates if counts[item["code"]] == 1]
        standard_codes = [item["code"] for item in items
                          if (item["source"]["fid"].isdigit() and 1 <= int(item["source"]["fid"]) <= 48)
                          or item["code"] in COMMON_CODES]
        assert len(items) == 353, f"Review required: expected 353 items, got {len(items)}"
        assert len(standard_codes) == 57, f"Review required: expected 57 standard items, got {len(standard_codes)}"
        assert [(item["code"], item["source"]["row"]) for item in conflicts] == [("Eqc", 29), ("Eqc", 51)]
        pairs = {(item["name"], item["code"]) for item in candidates}
        for harmonic_sheet in workbook.worksheets[1:]:
            harmonic_pairs = [(str(row[-2]).strip(), str(row[-1]).strip())
                              for row in harmonic_sheet.iter_rows(min_row=3, values_only=True)
                              if row[-2] and row[-1]]
            assert len(harmonic_pairs) == 93 and all(pair in pairs for pair in harmonic_pairs)
        return {"version": 1,
                "source": {"file": workbook_path.name, "sha256": hashlib.sha256(workbook_path.read_bytes()).hexdigest(), "sheet": sheet.title},
                "library": {"id": "excel-0918", "code": "excel-0918", "name": "0918数据类型配置（5-600号段）"},
                "items": items, "standardCodes": standard_codes, "conflicts": conflicts}
    finally:
        workbook.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("workbook", type=Path)
    parser.add_argument("--check", action="store_true", help="Verify the committed snapshot without rewriting it")
    args = parser.parse_args()
    data = build(args.workbook)
    if args.check:
        assert json.loads(OUTPUT.read_text(encoding="utf-8")) == data, "The committed seed differs from the workbook"
    else:
        OUTPUT.parent.mkdir(parents=True, exist_ok=True)
        OUTPUT.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"items": len(data["items"]), "standard": len(data["standardCodes"]),
                      "conflicts": len(data["conflicts"]), "categories": dict(Counter(item["categoryPath"] for item in data["items"])),
                      "sourceSha256": data["source"]["sha256"]}, ensure_ascii=False))


if __name__ == "__main__":
    main()
