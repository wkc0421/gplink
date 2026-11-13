# 采集项库与物模型录入

采集项库按“项目库 → 多级分类 → 采集项”维护名称和 ID。标准库与项目库独立，采纳到标准库、加入物模型均为复制；库内后续的编辑、停用、删除不会改写已有物模型或历史数据。

## 使用

- 在设备管理的“采集项库”中维护项目库、分类与采集项。父分类筛选包含子分类；同一库内 ID 唯一，不同库可以存在同一 ID。
- 项目库中可勾选条目并“采纳到标准库”，选择标准库的目标分类。相同名称和 ID 跳过；同 ID 不同名称报告冲突，不覆盖。
- 产品或设备的物模型属性页可“从采集项库添加”，默认标准库。分类、分页切换保留本批勾选，切换项目库清空本批勾选；物模型已有 ID 不可重复加入。
- 名称、ID 支持库内搜索与联动选择，也保留自定义输入。加入条目后仍需填写数据类型、单位及实际协议参数，并点击物模型原有保存按钮。
- 精简字段和完整字段切换只影响显示，完整校验仍然生效。继承自产品的设备属性沿用原有编辑限制。

采集项库不是产品的业务项目，也不是物模型与标准项的实时关联。不会自动将已有 `PF`、`F`、`Uca` 等编码改成 Excel 中的 `Pf`、`Freq`、`Uac`。

## 初始化数据与冲突清单

来源：`0918数据类型配置 (5-600号段).xlsx` 的“电能质量数据ID”工作表。

SHA-256：`b7494b8647d3e9a36fd00ae7dae32698ef2c7ff13781989ecaaf2a9f23312280`。

- `FName` → 采集项名称，`FCode` → 物模型 ID。`Id` / `Fid` 不是物模型 ID；来源行号与 `Fid` 保留用于核对。
- 356 行记录中去掉一个 `None` 占位，两条编码冲突保留在下面的待处理清单，首次项目库纳入 353 项。
- 两张谐波曲线表右侧各 93 条记录均与主表重复，不再次导入。
- 第 331 行编码 ` CT_Ratio_Set` 去除前导空格；保留其他合法编码的大小写和数字前缀。
- 标准库首批 57 项：`Fid 1–48` 的 46 个无冲突条目，以及 `WaterSum`、`FlowSum`、`HeatSum`、`CoolSum`、`HeatPower`、`FlowCur`、`TempSup`、`TempBack`、`Temp`、`Hum`、`TempEn`。
- 初始化没有填充 Excel 未提供的单位、数值类型、寄存器、解析方式、操作类型或倍率。

| Excel 行号 | Fid | 名称 | 冲突 ID | 处理 |
| --- | --- | --- | --- | --- |
| 29 | 26 | C相无功电能 | Eqc | 待确认编码，未纳入可选库 |
| 51 | 48 | 反向无功电度 | Eqc | 待确认编码，未纳入可选库 |

整理结果随后端资源 `property-catalog/seed-v1.json` 交付，部署机器无需提供原始 Excel。首次初始化在事务内建立两个库、分类及条目，并记录完成版本；之后启动不覆盖维护结果，也不补回已删除条目。

可使用仓库脚本核对已交付快照，原始 Excel 始终只读：

```powershell
python -X utf8 scripts/build-property-library-seed.py 'C:\Users\wkc\Desktop\0918数据类型配置 (5-600号段).xlsx' --check
```

## 后续 Excel 导入

导入到当前选中的项目库，支持本次 `FName/FCode` 工作表及统一模板：

| 分类路径 | 采集项名称 | 采集项ID | 备注 |
| --- | --- | --- | --- |
| 电参量/电压 | A相电压 | Ua | |

分类路径以 `/` 分隔，最多 8 级。导入窗口可下载 UTF-8 CSV 模板，也可使用 `.xls` 或 `.xlsx`；每个文件最多 10 MB、每批最多 10000 项。先预览并修改错误，勾选需要导入的条目，再确认。确认接口重新校验内容和现有数据；不会因为客户端清空了错误文字而跳过校验。重复 ID 不覆盖已有数据，创建分类与写入条目在同一事务中完成。

## 接口与发布

新增接口前缀为 `/device/property-library`、`/device/property-library-category`、`/device/property-library-item`。浏览器继续使用既有 `/api` 前缀与统一响应包装。库和分类维护、分页查询、批量分类、导入及采纳均由后端检查归属和编码唯一性。

后端使用三张独立表：`dev_property_library`、`dev_property_library_category`、`dev_property_library_item`，按现有 EasyORM 启动流程建立结构。非空分类、非空项目库不能直接删除；标准库入口保留。

发布时先更新后端，再发布前端。部署后的菜单来自服务端，需在系统菜单管理中同步新增“采集项库”菜单，再为角色配置权限：

- 只使用选择器的物模型编辑角色：`property-library:query`。
- 库维护角色：按职责额外授予 `property-library:save`、`property-library:delete`。
- 产品和设备原有保存权限继续独立生效，不因库权限扩大。

同步后验证普通编辑角色能查库和加入草稿，但不能维护库；维护角色能管理项目、分类、导入与采纳。部署前后原有物模型数量、ID 和协议参数应保持一致。

本次代码交付不执行远程部署或修改已部署角色。现有产品 Excel 直接导入和设备 Excel 预览后保存两条链路保持原样。

## 本地验证记录（2026-11-03）

- 后端 14 项测试通过，其中 11 项使用隔离 H2 数据库和真实 EasyORM，验证初始化数量与幂等、三表事务回滚、库内唯一与跨库同码、多级分类、并发防环和防孤儿、清空分类、分页搜索、导入与采纳冲突；另 3 项验证 Excel/CSV 解析。
- 前端 14 项针对性测试通过：物模型相关 10 项、管理页工具函数 4 项。另完成管理页 4 组浏览器流程，覆盖维护、导入纠错、采纳、权限及失败重试。
- 物模型测试挂载真实共享编辑组件和保存转换逻辑，覆盖产品/设备差异、继承只读、跨页多选、名称/ID 联动、自定义、半填行、隐藏字段校验及失败保留草稿。浏览器接口及登录状态使用隔离测试数据，未连接远程环境。
- 菜单配置检查确认产品、设备入口仅增加库查询权限，维护菜单单独配置保存及删除权限。实际部署后的菜单同步与角色授权仍按上一节执行。
- Excel 快照核对、相关文件差异检查及前端生产构建通过；构建输出使用独立目录，未覆盖既有发布包。

后端测试在 `back/jetlinks-community-2.10/jetlinks-community-2.10` 执行：

```powershell
mvn -o -pl jetlinks-manager/saas-manager -am '-Dtest=PropertyCatalog*Test' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dcheckstyle.skip=true' '-Djacoco.skip=true' test
```

前端测试位于 `front/tests/e2e/property-library-*.spec.ts`，物模型页面测试需要先启动 `front/tests/property-library-metadata/vite.config.ts` 对应的本地测试服务。管理页浏览器流程为 `front/tests/property-library/verify.py`，使用 `front/tests/property-library/vite.config.ts` 对应的服务。两组服务均使用本地模拟接口。

生产构建在 `front` 执行：

```powershell
pnpm.cmd build --outDir .property-library-build-codex-20261103
```
