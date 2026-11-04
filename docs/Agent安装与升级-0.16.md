# SyncSrv Agent 0.16 安装与升级

Agent 0.16 与中心 0.4 配套使用。文件源 A 由源端监测并直接推送到实时备份 B；归档 C 可使用 NAS 上的执行设备或存储端复制。新版本只提示，由用户手动运行更新脚本。

## 选择安装包

| 系统 | 架构 | 包名 |
| --- | --- | --- |
| Windows | Intel AMD x64 | syncsrv-agent-windows-amd64.zip |
| Windows | ARM64 | syncsrv-agent-windows-arm64.zip |
| Linux | x64 | syncsrv-agent-linux-amd64.tar.gz |
| Linux | ARM64 | syncsrv-agent-linux-arm64.tar.gz |
| macOS 12 及以上 | Intel | syncsrv-agent-darwin-amd64.tar.gz |
| macOS 12 及以上 | Apple Silicon | syncsrv-agent-darwin-arm64.tar.gz |
| 群晖 DSM 7.2.2 及以上 | x64 | syncsrv-agent-synology-amd64.spk |
| 群晖 DSM 7.2.2 及以上 | ARM64 | syncsrv-agent-synology-arm64.spk |

其他 NAS 需匹配 x64 或 ARM64 的 Linux 包，并验证系统权限、文件系统和服务管理方式。当前没有 QPKG、ARMv7 或 MIPS 发行包。网络挂载目录应在存储主机上安装 Agent；源端监听只支持已确认的本机文件系统。

## 首次安装

Windows 解压完整 ZIP，以管理员运行 install.bat。Linux 和 macOS 解压完整包后运行 `sudo sh install.sh`；macOS 也可使用 install.command。配对使用中心生成的一次性令牌，随后在中心授权设备。群晖通过套件中心手动安装对应 SPK，按套件说明完成配对。

正式网络连接使用 HTTPS，并让系统信任中心的证书。`--allow-http` 仅用于明确指定的内网测试。安装包内程序和发布清单使用独立发布者签名；外部分发所需的 Windows Authenticode、Apple Developer ID 和公证依赖部署方证书，不能以交叉编译代替验证。

## 从旧版本建立升级信任

0.15 及更早版本没有独立的发布签名校验。先升级中心，再从可信来源取得一次完整的 0.16 包，解压到新目录，并运行其中的 update.bat 或 update.sh。程序通过现有服务找到安装位置，不要重新注册设备，不要删除原 data、identity.json 或 events.db。

这一轮完成后，运行已安装目录的 update.bat 或 `sudo sh update.sh` 即可在线取得新版，无需手工复制后续安装包。新程序在下载前验证发布者签名及中心兼容性，在运行前验证大小和 SHA-256，并拒绝降级或过期清单。

## 日常管理

| 操作 | Windows | Linux 和 macOS |
| --- | --- | --- |
| 启动 | start.bat | sudo sh start.sh |
| 停止 | stop.bat | sudo sh stop.sh |
| 重启 | restart.bat | sudo sh restart.sh |
| 更新 | update.bat | sudo sh update.sh |
| 卸载服务 | uninstall.bat | sudo sh uninstall.sh |

Linux systemd 服务名为 `SyncSrvAgent`：`systemctl status SyncSrvAgent`。macOS 使用 launchd。无 systemd 的 Linux 使用受控的便携进程管理；群晖优先使用套件中心启停。

升级前会保存二进制、管理脚本、适用的服务注册文件及队列快照；启动后必须收到中心确认，配置版本也须一致。失败或中断后可以恢复上一版本。回滚保留最新事件队列，避免丢失升级期间产生的变化。最近三组升级备份保留在安装目录；服务日志运行中轮转，升级日志保留当前和上一份。

卸载默认保留身份、待处理事件和业务文件。不要把删除 data 当作升级或故障修复步骤。

## 验收要求

在独立的 A、B、C、D 测试目录验证安装、授权、启停、系统重启、升级失败恢复和卸载。测试新增、修改、删除、连续保存、小文件批量、大文件中断、限速，以及从 B/C 合成 D。全过程核对执行报告、文件哈希、审计记录和源端待处理事件。

Windows SCM、DSM 套件生命周期和真实 NAS 吞吐由对应设备实测确认。Linux 容器、macOS 本机结果与交叉编译结果分别记录。
