# SyncSrv

Event-driven file backup, version archiving and point-in-time recovery with lightweight device agents.

SyncSrv 是文件备份、版本归档与按时间点合成恢复服务。中心管理设备、任务、执行记录和恢复节点；设备 Agent 优先直接传输文件，同设备的复制与校验优先在设备内执行。

本仓库发布经过清理的源码。历史本地安装包、运行数据库、日志、账户口令、设备身份和发布私钥不在公开范围。源码可构建不等于所有硬件、NAS 固件或正式安装包已经完成实机验收；部署边界见 [安全模型](docs/SECURITY-MODEL.md)。

## 功能

- 源端事件先持久化、确认后通知，支持失败重试与首次/异常补扫。
- 一台设备管理多个备份任务，按任务设置路径、并发和带宽。
- 支持本地、SMB、SFTP、FTP 和 WebDAV 存储连接；使用前核对协议加密和服务器能力。
- 文件替换和删除前归档旧版本，分块续传、SHA-256 校验及完整恢复清单。
- 按完成节点合成到独立恢复库；执行进度、文件列表、传输速度与审计记录。
- Java 17 中心、React/Ant Design 网页、内嵌 H2 元数据和 Go 设备 Agent。

路径名称：**源目录 · 备份库 · 归档库 · 恢复库**。

## 源码构建与启动

需要 Java 17、Maven 3.9、Python 3.9+、Node 22.12+、npm 11.6.2，以及 `packaging/go-toolchain.lock.json` 固定的 Go 版本。开发构建无需发布签名私钥。

```sh
python3 scripts/build-native-io.py --host-only
cd Frontend
npx --yes npm@11.6.2 ci
npm run check:licenses
npm test
npm run build
cd ..
python3 scripts/release_gate.py stamp
cd Backend
mvn verify
```

本地存储的原生组件使用 Go 标准库和根目录句柄，缺失时会拒绝本地操作。`--host-only` 用于本机开发；省略该参数可构建 Windows、Linux、macOS 的 x64/ARM64 组件。生成的二进制文件不提交到 Git。

复制 `.env.example` 为自己的 `.env`，填写自选的强初始管理员密码和本地数据目录，然后执行 `sh scripts/start.sh`。新建管理员使用 `SYNCSRV_INITIAL_PASSWORD`；已有数据库使用保存的密码哈希，不会被源码示例覆盖。默认访问 `http://127.0.0.1:18010`。前端开发在 `Frontend` 执行 `npm run dev`，端口 18011。

IDEA 可运行 `SyncSrvApplication.main`：先生成原生组件及前端资源，工作目录设为仓库根目录，环境变量在运行配置中填写。`.env` 是启动脚本使用的本地文件，IDEA 不会自动读取它。H2 数据与配套密钥必须成套保存，禁止多个实例写同一数据目录。

Agent 压缩包与群晖套件的安装、设备绑定、服务身份和升级流程见 [Agent 安装与升级](docs/Agent安装与升级-0.16.md)及 [设备执行与群晖安装](docs/设备执行与群晖安装-0.11.md)。官方发行包需要另行完成签名和平台验收；本仓库不提供旧版未验证附件。

## 备份与恢复边界

首次备份使用独立空备份库；各任务的源目录、备份库、归档库与恢复库不能重叠。备份库和归档库是服务管理空间，不供外部程序同时修改。删除或替换前先归档并验证，恢复不会改写备份库和归档库。

恢复节点表示完成校验的文件状态，不是数据库或虚拟机的瞬时一致性快照。快速连续变化可能合并中间版本。文件内容不由 SyncSrv 加密，不恢复任意 ACL、所有者或扩展属性。需要应用一致性、加密或权限恢复时，应由存储和源系统提供对应能力，并进行独立恢复验证。

远程管理使用经过验证的 HTTPS；私网 HTTP 是显式开发例外。FTP/HTTP WebDAV 不提供机密传输，SMB 签名不等于内容加密。Agent 以安装时选择的身份执行，中心管理员属于受信任管理边界。详见 [安全模型](docs/SECURITY-MODEL.md)。

## 授权与贡献

自有代码采用 [Apache-2.0](LICENSE)。第三方代码保留自己的许可；React Bits 的应用特效采用 MIT + Commons Clause，不能改标为 Apache-2.0 或提取为独立组件产品。当前 UI 免费使用条件、原许可和已审查版本见 [UI 授权说明](docs/UI授权与免费条件.md)、[第三方声明](THIRD_PARTY_NOTICES.md)与 `licenses/`。品牌名称和 LOGO 不代表授予商标权。

贡献前阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。源码公开范围与验证结果见 [SOURCE-REVIEW.md](docs/SOURCE-REVIEW.md)，漏洞报告见 [SECURITY.md](SECURITY.md)。请勿在 Issue 中公开凭据、业务文件名、日志原件或漏洞利用细节。
