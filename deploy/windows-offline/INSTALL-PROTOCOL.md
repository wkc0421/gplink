# Optional Modbus protocol import

基础安装只导入基线 SQL 中的两个网络组件，不会自动导入协议。安装和验收完成后，双击
`install_protocol.cmd` 即可导入 Modbus RTU。

脚本会执行以下操作：

1. 重新校验清单中 `protocol-modbus.jar` 的 SHA-256、JAR 清单、SPI 服务文件和
   `ModbusProtocolSupportProvider` 类。
2. 使用 `deploy-config.psd1` 中的 GPLink2 管理员账号登录本机 API。
3. 调用 `/file/upload` 上传 JAR，读取服务返回的实际 `fileId` 和 `accessUrl`。
4. 以固定协议 ID `modbus-rtu.v1` 调用 `/protocol` 创建或更新记录，配置中同时写入
   `fileId`、`location`、`provider` 和文件名。
5. 轮询 `/protocol/modbus-rtu.v1/transports`，确认协议已经被应用加载。

导入结果写入 `logs\install-protocol-report.txt`、`logs\install-protocol-report.json`，
详细过程写入 `logs\install-protocol-*.log`。密码、访问令牌和上传响应正文不会写入日志。
如果已经存在同 ID 协议记录，脚本会更新它；不会清理产品、设备、网关或网络组件。
