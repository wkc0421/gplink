# GPLink2 Windows 离线目录式一键部署

本目录用于在 Windows 11 x64 及 Windows Server 2019/2022/2025 x64 上离线部署 GPLink2 后端、PostgreSQL 16.15、TimescaleDB 2.29.1、Temurin JDK 17、VC++、OpenSSL 和 NSSM。目标服务器不联网、不编译源码，也不要求预装 Java 17；Redis 必须由现有环境提供。

安装时会优先检测 `D:\Program Files\Java\jdk-17`，并实际运行检查其 Java/Javac 均为 x64 Java 17。检测通过后，GPLink2 服务直接使用该 JDK，不会复制或覆盖包内运行时；缺少或检测不通过时，会将离线包内已校验的 Temurin JDK 17 安装到 `{AppRoot}\runtime\java` 并使用它。

## 目录和介质

构建完成后会生成三个需要一起交付的文件：

```text
GpLink2-Offline-gplink2-windows-offline-2026.10.13-network-only-modbus-import-jdk-priority-fulljdk-nginx-config.zip
GpLink2-Offline-gplink2-windows-offline-2026.10.13-network-only-modbus-import-jdk-priority-fulljdk-nginx-config.zip.sha256
Verify-GpLink2-Package.cmd
```

用户只需双击 `Verify-GpLink2-Package.cmd`，程序会自动核对固定ZIP文件名、发布时SHA-256、旁路校验文件及ZIP实际SHA-256，不需要打开PowerShell或输入命令。只有显示 `[PASS]` 才能解压安装；显示 `[FAIL]` 时必须重新复制上述三个文件。

目标服务器默认介质目录为 `D:\tool\GpLink2`，三个交付文件放在同一目录，ZIP解压到 `D:\tool\GpLink2\GpLink2-Offline`。该介质目录与应用安装目录 `D:\GpLink2` 相互独立。

ZIP 解压后包含：

```text
GpLink2-Offline\
├─ Check-GpLink.cmd
├─ Install-GpLink.cmd
├─ Verify-GpLink.cmd
├─ config\deploy-config.psd1
├─ config\nginx.conf（随包参考配置，不自动应用）
├─ manifest\packages.json
├─ packages\
│  ├─ application.jar
│  ├─ protocol-modbus.jar（可选 API 导入）
│  ├─ gplink-baseline.sql
│  └─ PostgreSQL、TimescaleDB、JDK、VC++、OpenSSL、NSSM 介质
└─ scripts\
```

`packages.json` 固定全部 9 个介质的版本、大小和 SHA-256；EXE 还按清单要求校验 Authenticode。`bundle.json` 固定 `packages.json` 自身的 SHA-256。不得只替换文件而不重建并复核清单。

基础安装不会自动导入协议。安装验收通过后，如需 Modbus RTU，双击 `install_protocol.cmd`。脚本会登录本机 API，调用 `/file/upload` 上传 `protocol-modbus.jar`，然后创建或更新固定协议 ID `modbus-rtu.v1`；数据库中的 `fileId` 和 `location` 均来自本次上传。结果写入 `logs\install-protocol-report.*` 和 `logs\install-protocol-*.log`，不会导入旧的 KHMQTT 或 mbrtu 协议。

Nginx 配置随包放在 config\nginx.conf 供参考；安装程序不会安装、启动或自动应用 Nginx。该文件包含目标机器的路径和上游地址，部署前按实际环境调整。

## 目标服务器使用

1. 在目标服务器创建本地NTFS目录 `D:\tool\GpLink2`。
2. 把 ZIP、`.zip.sha256` 和 `Verify-GpLink2-Package.cmd` 一起复制到 `D:\tool\GpLink2`。
3. 双击 `D:\tool\GpLink2\Verify-GpLink2-Package.cmd`；看到绿色 `[PASS]` 后，把ZIP解压到 `D:\tool\GpLink2\GpLink2-Offline`，不需要输入命令。
4. 只修改 `D:\tool\GpLink2\GpLink2-Offline\config\deploy-config.psd1`，填写路径、端口、Redis、外部地址和全部密码。
5. 双击 `Check-GpLink.cmd`。它只读检查配置、介质、系统、端口、权限和 Redis；只有 `logs\preflight-report.txt/json` 全部 PASS 才继续。
6. 双击 `Install-GpLink.cmd` 并同意管理员权限提升。安装入口会重新执行同一套预检，不能绕过。
7. 查看 `logs\verify-report.txt/json`，确认在线验收全部 PASS。
8. 在维护窗口重启 Windows，再双击 `Verify-GpLink.cmd` 验证开机自启动和服务依赖顺序。

配置预检会验证 PSD1 语法、字段集合和类型、数值范围、绝对路径、服务名、PostgreSQL 标识符、密码占位符、JVM 档位及字段间冲突。配置不合法时不会继续检查或安装介质。

## 首次数据库基线

安装 PostgreSQL 和 TimescaleDB 后，安装程序会先启动 GPLink2，让当前 `application.jar` 完成数据库建表；随后停止 GPLink2，在一个 PostgreSQL 事务内自动导入 `gplink-baseline.sql`：

- 清空全部产品、设备和接入网关，并清理相关运行数据及按产品生成的动态时序表。
- 不导入 `modbus-rtu.v1`、`jetlinks.v3.0`（mbrtu/KHMQTT）协议，也不部署对应协议 JAR；基线 SQL 会清空协议注册及旧基线协议文件记录。
- 网络组件仅保留 `2068932624811515904`（Gp_mqtt）和 `2068926208973430784`（KH_mqtt）。
- 事务完成后写入 `gplink_deployment_baseline` 标记，再启动 GPLink2 并执行完整验收。

该 SQL 只能自动执行一次。相同离线包进入修复模式时，安装程序会核对 bundle ID 和 SQL 哈希并跳过导入，避免清空安装后新增的正式数据；标记不一致时停止，禁止自动覆盖。SQL 本身也有重复执行保护。不要手工绕过标记运行该清理 SQL。

网络组件基线内容来自测试服务器的只读快照：数据库查询使用 `BEGIN TRANSACTION READ ONLY`；测试服务器数据库数据没有被修改。

## 安全说明

`gplink-baseline.sql` 为了恢复两条 MQTT 网络组件，包含其认证配置明文，属于敏感介质。离线目录和 ZIP 必须按密码文件管理，禁止提交 Git、公开分发或发送到不受控位置。正式安装开始后，源配置和基线 SQL ACL 会限制为 Administrators 与 SYSTEM；运行时 Spring 配置仅额外允许 LocalService 读取。日志不会输出 SQL 内容、网络认证信息、数据库密码、Redis 密码、管理员密码或令牌。

PostgreSQL 只监听 `127.0.0.1`。GPLink2 以 LocalService 运行，依赖 PostgreSQL，使用 NSSM 延迟自动启动、失败重启和日志轮转。防火墙只按配置开放 API 端口的 Domain/Private 入站规则，不开放 MQTT、TCP、SIP、前端或 Nginx 端口。

普通安装失败时只自动删除本次新建的 GPLink2 服务和防火墙规则；PostgreSQL 及数据目录保留以便修复。v1 只支持全新安装和同版本修复，不处理版本升级、数据库迁移或高可用。需要清空旧环境时必须显式使用下面的破坏性重装入口。

## 不想手工清理时的一键重装

如果服务器上残留了上一次 GPLink2/PostgreSQL 安装，且确认旧数据可以丢弃，可直接双击 `Reinstall-GpLink.cmd`。脚本会先检查服务的可执行文件、PostgreSQL 数据目录、安装目录标记、状态文件和防火墙规则归属；发现未知程序或路径时会停止，不会按名称强制删除别人的服务。

预检查通过后，脚本会显示将要删除的精确目录和服务。只有在窗口中输入 `RESET GPLink2` 才会继续。它会停止并删除配置的 `GpLink2`、`GpLinkPostgreSQL16` 服务，删除 `AppRoot`、`PgInstallRoot`、`PgData`、匹配的安装状态和 GPLink2 防火墙规则，然后自动调用普通安装流程（普通安装仍会再次执行完整预检）。离线包目录、Redis、VC++ 和 OpenSSL 运行库不会删除。

该操作不可恢复旧的应用文件和 PostgreSQL 数据；清理完成后新安装失败也不会自动恢复旧数据。过程记录在 `logs\\reinstall-*.log`，失败时按日志修正配置后可重新运行。若输入其他内容或直接关闭窗口，则不会修改任何文件、服务或防火墙规则。

## 构建和校验

在构建机本目录运行：

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Prepare-Bundle.ps1 `
  -ApplicationJarPath .\packages\application.jar

powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Test-Bundle.ps1
```

准备脚本会重新核验所有介质、更新清单和 `bundle.json`，并生成 ZIP、`.zip.sha256` 和可双击运行的 `Verify-GpLink2-Package.cmd`。生成的 ZIP 始终使用示例配置，不会打入构建机的真实 `deploy-config.psd1`。

第三方参数参考：[EDB PostgreSQL 命令行参数](https://www.enterprisedb.com/docs/supported-open-source/postgresql/installing/command_line_parameters/)、[TimescaleDB Windows 安装说明](https://docs.timescale.com/self-hosted/latest/install/installation-windows/)、[FireDaemon OpenSSL 无人值守安装](https://kb.firedaemon.com/support/solutions/articles/4000121705)。
