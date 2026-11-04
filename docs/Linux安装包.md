# Linux x64 自带 Java 安装包

SyncSrv 0.4.2 安装包包含 Java 17 后端、React 前端、固定版本的 Temurin JRE 和 Agent 发布仓库。启动脚本只使用包内 `runtime/bin/java`；目标系统不需要另外安装 Java、Maven 或 Node.js。数据库使用数据目录内的 H2 2.4.240，无需外部数据库。中心在线升级工具使用 Python 3.9 及以上版本。

中心发行目标为 Linux x86_64 和 glibc；Windows、macOS、Linux ARM64 及群晖包是 Agent。当前不提供其他架构的中心安装包。

## 首次部署

通过可信渠道核对包的发布签名与 SHA-256，再解压 `syncsrv-0.4.3-linux-x64.tar.gz`，运行 `sudo sh install.sh`。安装器校验包和自带 JVM，建立无登录权限的 syncsrv 服务账号，不会自动启动服务，也不会覆盖已有安装。

| 位置 | 内容 |
| --- | --- |
| /opt/syncsrv/releases/0.4.2 | 只读应用、运行时和 Agent 包 |
| /opt/syncsrv/current | 当前发行版本的原子链接 |
| /etc/syncsrv/application-private.yml | 审计锚点及告警配置 |
| /etc/syncsrv/syncsrv.env | 首次管理员口令、端口及进程选项 |
| /var/lib/syncsrv | 身份密钥、凭据密钥和运行数据 |

H2 数据库保存在 /var/lib/syncsrv/metadata.mv.db，签名和凭据密钥与数据库成套保留。不要将数据库放在 NAS 或网络共享，不开启 H2 TCP 或 Web Console。生产模式要求独立 HTTPS 审计锚点；配置位于受限私有 YAML，不能把开发配置或秘密打入发行包。

首次管理员口令保存在受限的 syncsrv.env 文件中，至少 10 个字符。密码中的反斜杠按实际单个字符保存；shell 配置使用单引号。管理员初始化后，数据库中保存的密码哈希生效。

## 初始化审计身份

在服务尚未运行时执行：

```sh
sudo -u syncsrv /opt/syncsrv/current/bin/initialize.sh
```

命令建立签名身份和首次管理员，输出可公开的审计公钥，不启动 Web 服务。将该公钥注册到另一信任域的审计锚点服务器，在私有 YAML 中配置锚点 HTTPS 地址和收据公钥，再启动中心：

```sh
sudo systemctl enable --now syncsrv
systemctl status syncsrv
journalctl -u syncsrv -f
```

锚点服务器参考实现位于源码的 `scripts/audit-anchor-server.py`，另随交付目录 `Operations` 提供，需要 Python cryptography、独立 Ed25519 收据密钥和 HTTPS 证书。收据绑定请求随机数，服务端只接受单调前进的签名链头。生产中心启动时核对外部历史；检测到回退或分叉后停止写入。锚点应位于独立主机或独立管理域；本机文件副本不等价于外部防回退保护。

主服务默认绑定 127.0.0.1:18010，通过 HTTPS 反向代理访问。也可在私有 Spring 配置中启用服务自身 TLS。源端必须信任中心证书。开发前端使用 18011，隔离验收使用 18012；18013 至 18019 预留。

## 在线升级与回滚

0.3 及更早中心没有安全的完整更新器，需要先离线备份并迁移到 0.4 的版本目录布局。安装器发现旧目录会退出，避免覆盖历史配置和密钥。

0.4.2 安装时用 `--update-manifest` 一次配置 RelsHub 渠道地址，保存在 root 管理的 `/etc/syncsrv/update.json`；旧版 `update.url` 仍兼容。随后直接执行：

```sh
sudo sh /opt/syncsrv/current/update.sh check
sudo sh /opt/syncsrv/current/update.sh update
sudo sh /opt/syncsrv/current/update.sh recover
sudo sh /opt/syncsrv/current/update.sh rollback
```

也可使用 `--manifest HTTPS地址` 指定本次来源。更新器通过已安装版本中的固定发布公钥验证清单，再下载和校验完整包。它保留旧发行目录，持久化升级阶段，停止中心后切换 current，随后检查新版本和数据库健康；失败时回到上一版本。断电后执行 recover 处理尚未确认的切换。配置、数据和 B/C 不属于版本替换范围。

本次数据结构仍为版本 1。更新器只接受声明兼容当前结构且允许回滚到已安装版本的发行包。结构变化或破坏兼容性的版本需要离线迁移，不会绕过检查强行更新。

发布方用受限的 Ed25519 私钥生成清单，私钥不可提交版本控制，也不能进入 JAR 或安装包。`scripts/publish-relshub.py` 将已验证包签名并发布至 RelsHub；渠道地址和发行回执见 `OPERATIONS.md`。发行目录不可覆盖，同一版本不得更换字节。本次签名明确绑定真实 RelsHub 仓库与文件哈希；内网 HTTP 需要显式允许确切 IP 和端口，跨网使用 HTTPS。旧的独立仓库发布方式仍保留，但不能把占位清单用作在线源。轮换发布密钥时先发布包含新旧公钥的过渡版本，确认设备完成升级后再移除旧公钥；旧私钥丢失时需要可信完整包重新建立升级信任。

## 灾备恢复

停止中心后执行 `current/bin/state-backup.sh export 绝对路径`，交互输入至少 16 字符的恢复口令。文件用 AES-GCM 加密，包含一致的数据库状态及签名、凭据密钥。口令不进入进程参数。

在空数据目录上执行 `state-backup.sh import 绝对路径`。导入前完整验证密文，导入事务中检查对象签名、审计链及链头；非空库和已有密钥目录会被拒绝。

完整灾备需要保留这份状态备份、私有部署配置，以及对应时点的 B/C 文件卷。应停止中心和存储端执行任务后取得一致的文件卷副本；只恢复数据库不够。恢复时保持原存储地址和相对路径映射。

导入会暂停备份任务及自动保留策略，使设备旧缓存回执失效，并要求源端重新完整校准。停止中心，以 syncsrv 用户执行 `current/bin/verify-state-files.sh`，完整读取当前 B 和所有保留恢复点引用的 B/C 文件及清单，核对 SHA-256。校验失败时任务保持关闭；成功后才允许重新启用，文件与性能报告保留在中心。

旧备份落后于最新外部锚点时，不能静默重置审计历史。先运行 `state-backup.sh inspect 备份文件`，将输出的公开 evidence JSON 交给独立锚点管理员。该管理员在锚点主机执行 `audit-anchor-server.py` 的 `--authorize-recovery evidence.json --reason 原因 --operator 具名操作人`，并提供该实例原有的目录、客户端公钥与收据私钥参数。此权限不通过中心 HTTP API 提供。

恢复授权不会删除旧锚点。全局锚定序号继续前进，同时保存原可信链头、所选备份链头和恢复原因；中心首次连接后把独立授权写入新的审计链。恢复点之后未进入备份的业务及详细审计数据仍受备份 RPO 限制，不能将此流程宣称为零数据丢失。

通过 B/C 校验和锚点核对后，再开启任务，检查 A 重新校准、执行报告与 D 合成结果。保留策略需要重新确认后再启用。

## 容量与运行保障

服务日志按 10 MiB 轮转，保留 14 天并限制总量 200 MiB。中心监控本地可用空间、设备离线、最近失败和锚点状态，可通过 HTTPS Webhook 告警。H2 数据库容量计入中心数据目录告警；数据库与密钥须成套备份。

归档 C 支持按月份预览清理、显式确认和月度保留策略，默认只生成预览。自动删除需单独确认；当前版本、最新完整恢复点和未完成恢复引用仍受保护。审计、故障处置和运行报告不会随 C 文件整理静默删除，须依据审计保留期规划数据库容量。

归档规划使用只读一致性事务，控制请求限定 1 MiB，历史记录分批读取。规划超出堆内存预算时会明确中止，不执行删除；大量历史恢复清单仍需按容量验收调整堆内存。

## 192.168.50.118 内网验证实例

2026-10-23 已完成真实 systemd 的 0.4.1 → 0.4.2 → 0.4.1 → 0.4.2 安装、升级、回滚及再次升级，启用开机启动；使用包内 Java，H2 健康。此实例明确使用非生产模式，仅绑定 192.168.50.118:18010，未迁移正式数据。首次升级后的密钥与 syncsrv.env 已逐一比对一致。独立 HTTPS 审计锚点仍属于正式上线前置项。

此实例的健康检查地址是明确内网 IP，升级、回滚、恢复均须传入对应参数：

```sh
sudo sh /opt/syncsrv/current/update.sh check --health-url http://192.168.50.118:18010/api/health
sudo sh /opt/syncsrv/current/update.sh update --health-url http://192.168.50.118:18010/api/health
sudo sh /opt/syncsrv/current/update.sh rollback --health-url http://192.168.50.118:18010/api/health
```

0.4.3 修复入口缓存策略。浏览器若已经缓存 0.4.1/0.4.2 的入口，首次访问请使用 `http://192.168.50.118:18010/?v=0.4.3` 或强制刷新；新入口使用 no-store，带内容哈希的脚本及样式继续允许缓存。
