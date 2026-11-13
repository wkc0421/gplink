# 导出设备最新数据

脚本分页获取当前账号有权访问的设备，对每台设备查询各属性的最新可用记录，生成 `.xlsx` 文件。工作簿包含“设备清单”和“最新属性”两个工作表；没有可用属性的设备也会列在设备清单中。

属性值是接口返回的最近记录，不会主动触发设备采集，也不是统一时刻的快照。不同属性的采集时间可能不同或较早；请查看采集时间及原始毫秒时间戳。接口可能从缓存或数据库返回最新值。

需要 Python 3.8 及以上、`openpyxl`，以及具有设备查询和设备数据查询权限的有效访问令牌。首次使用可执行：

```powershell
python -m pip install openpyxl
```

在 PowerShell 中设置服务根地址和令牌，然后运行：

```powershell
$env:GPLINK_BASE_URL = 'http://服务地址:8848'
$env:GPLINK_TOKEN = '填写有效访问令牌'
python scripts/export_device_latest.py --output device_latest.xlsx
```

也可通过 `--base-url` 指定服务根地址；地址不要包含 `/api` 路径。令牌仅从环境变量 `GPLINK_TOKEN` 读取，不写入 Excel。用完可执行 `Remove-Item Env:GPLINK_TOKEN` 清除当前 PowerShell 会话中的令牌。

默认输出至当前目录，文件名带运行时间。脚本不会覆盖已有文件；可用 `--page-size 100` 和 `--timeout 30` 调整分页大小及单次请求超时秒数。任何设备查询失败、分页不完整或设备总数发生变化时，脚本报错退出，不导出不完整结果。

接口：`POST /api/v1/device/_query` 和 `GET /api/v1/device-data/{deviceId}/properties/redis`。需要注意，设备列表由账号的数据权限决定；导出过程中设备或属性仍可能变化，不能保证跨设备的事务一致性。采集时间按运行脚本的电脑时区显示，并保留原始时间戳。值以文本保存，保留长数字和复杂值；对象和数组以 JSON 形式保存。
