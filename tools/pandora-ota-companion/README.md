# Pandora OTA 助手：当前使用说明

当前版本 **0.13.3-dev**（versionCode 27）。仓库默认分支 dev，设备 Xiaomi 17 Pro / pandora。完整项目入口见 [OS4 文档索引](../../docs/pandora-os4-guide.md)。

App 的范围：只读采集、GitHub 上传与接收跟踪、在手机填写原真机反馈工作流。电脑负责 fastboot boot；App 不执行 fastboot/flash、重启、切 slot 或分区写入，也不会自动晋升 LKG。

## 当前能力

以下说明当前版本的操作方式；版本演进单独列在文末。

### 页面与最短操作路径

| 页面 | 功能 |
| --- | --- |
| 工作台 | 当前系统、Root 检查、采集前预检、采集与上传、最近采集、真机测试反馈入口、OTA 全链路可信阶段快照 |
| 记录 | 切换本地 ZIP、查回执、重新通知、前台接收跟踪、核验后清理、查看 GitHub 草稿 |
| 设置 | 仓库、Keystore 加密 PAT、实时连接验证、采集/上传说明、使用指南、安全与关于 |
| 真机测试反馈（二级页） | 候选/Run 与 IMG SHA、五项本机信息、人工检查和备注、原工作流提交与跟踪、最近 20 条反馈 |

下载安装固定签名 APK → 配置仓库/PAT → 新 OTA 保留原始 init_boot 并取得 Root → App 预检/采集/上传 → 等后端候选 → 电脑临时启动 → 手机上测试并提交 → 功能版稳定后另行人工晋升 LKG。

### OTA 全链路状态

工作台点击「刷新可信状态」，核验当前输入及服务器快照，分别显示没有新 OTA、GKI 已构建、ABI 阻断、待测包就绪/等待人工、开发基线已验证、自定义内核待验证、稳定 LKG 已归档。稳定版从已核验 Release 证据动态显示 Gen 代次；同时显示 GitHub 核验时间。技术性 Run/Asset/SHA 放在「查看核验详情」，正常页面不直接堆叠编号。没有待测候选时，反馈入口和候选选择区会明确提示无需提交；不限制查询旧候选。输入变化、附件过期或最新状态生成失败时停止展示旧结论，可在 GitHub 检查来源。该卡为按需刷新的阶段快照，不是逐秒编译进度；服务器只读聚合，不自动刷机或晋升。恢复流程及精确证据绑定见 [审计前三阶段整改](../../docs/pandora-os4-audit-stages-2026-11-02.md)。

### 权限与更新

- fine-grained PAT 只授权 `wekingchen/GKI_KernelSU_SUSFS`。采集/上传需要 **Contents: Read and write**；App 提交真机反馈另需 **Actions: Read and write**。缺少 Actions 权限不会自动禁止原采集能力。
- PAT 用 AES-GCM / Android Keystore 加密保存，可在设置主动清除。ADB shell Root 不代表 App 获权，需在 Root 管理器给 `com.wekingchen.pandoraota` 单独授权。
- 同包名、同固定签名覆盖安装可保留 PAT、采集回执和反馈草稿；不要为排障直接清除应用数据。
- 正式 APK 在 [APK 与固定签名工作流](../../.github/workflows/pandora-os4-ota-companion-apk.yml)的 Artifacts 中以 `pandora-ota-companion-STABLE-SIGNED.apk` 原样发布，无需解压。Debug UNVERIFIED ZIP 仅作开发测试，不能保证覆盖正式签名版。

### 新 OTA、预检与只读采集

1. 先从同版本官方 OTA 离线保存原始、未修补的 init_boot.img 及来源和 SHA，再修补同版本镜像恢复 Root。新 build/fingerprint 或首次使用会提醒；「已了解」不表示 App 已备份镜像。
2. 采集目录实际可用空间小于 1 GiB 阻止；1 GiB 至不足 2 GiB 警告；至少 2 GiB 正常继续。真正读取分区前再次核验。
3. 采集和上传前在线验证仓库与实际写权限：创建、读回、删除独有空 Draft，再发送不会触发接收的权限探针。不是仅看 token 已填写或 permissions.push。空草稿清理失败会显示 ID 并停止继续。
4. 预检完成后出现「软件修订号 · 可选」：如果小米系统更新页面明确显示 D02，而 `ro.mi.os.version.incremental` 仍是 `OS4.0.5.0.XBLCNXM`，手动填入 `D02` 并确认；没有这种修订时选「无软件修订」。这是**用户确认的更新页面证据**，不是 Android 设备可信上报，不能伪造成真实系统 build。随后只读采集活动 slot 的 boot/init_boot/vendor_boot/dtbo 和 OEM 模块；仍强制检查 pandora、build、slot、文件完整性。单个 ZIP 必须小于 950 MiB。
5. 成功只保留最终 ZIP，删除镜像/TAR/staging；失败删除残缺 ZIP 并尝试清理 staging。上次中断的 staging 在 30 分钟宽限后回收，不误删最终 ZIP。

`.D02` 是更新页面标识，不保证 `ro.mi.os.version.incremental` 一定带该后缀。2026-11-15 Xiaomi 17 Pro D02 真机已证实：更新器显示 D02，但真实 Build、活动 slot A、6.12.69 自定义内核和 Root 均保留。0.13.3 使用单独的 `software_revision=D02`、`software_revision_source=USER_CONFIRMED_UPDATE_SCREEN`、`software_revision_base_build=...` 保存人工确认；原始 `build=` 始终只取 Android 属性，并在采集、上传、服务端规范化时交叉校验。服务端只回执 `REVIEW_REQUIRED`，不替换固定内核输入、不生成 GKI、不晋升 LKG。**没有手动确认 D02 的同 build 快照仍受原有 boot 漂移拒收门禁，不会自动推断软件修订。**

Root 后 init_boot 明确是诊断快照，不是 OEM 原件；无需把离线原始 init_boot 塞回 App ZIP。boot/OEM .ko 是 GKI/ABI 主输入，但当前快照也不能被无条件视为原厂 pristine 镜像。转换后的八文件 xm.zip 明确保留来源标签。

### 上传、状态与本地清理

上传到专用 Draft，不要 Publish。显示真实字节进度、速率与耗时；只有 GitHub HTTP 201 且接收大小一致才记为已上传，随后另行发送接收通知。上传期间保持前台和网络，不支持后台常驻/断点续传。

| 接收状态 | 含义 |
| --- | --- |
| PENDING | 等待本附件 ID / SHA 对应的服务端回执，保留本地 ZIP |
| ACCEPTED | 新输入已接收，不代表 ABI/KMI、可启动或 LKG 通过 |
| ALREADY_CURRENT | 与当前固定输入一致，无需重复分析 |
| REVIEW_REQUIRED | 同一系统主版本下的 `.D02` 类软件修订证据已保存至原 Draft；尚未证明底层内核/OEM 模块相同，不替换 stock-input、不触发 GKI、不改变 LKG |
| REJECTED | 身份或内容被安全拒收，远端采集附件保留 |

前台轮询从 5 秒退避至最多 60 秒；退出前台暂停、返回恢复；网络失败不伪造终态。通知失败点「重新通知」，只核验已上传附件并重发通知，不重新传整个 ZIP。

本地删除必须同时满足：上传确认、通知成功、接收终态。删除前再次在线核对远端 Release/附件 ID、名称、大小、上传状态及 SHA-256，再核对本地 SHA；任何网络错误或不一致都停止。REVIEW_REQUIRED 与 REJECTED 都不等于上传失败，满足这些条件后用户仍可主动删本地副本；建议先离线保存需要的审计证据。不会删除远端 Draft、固定 xm.zip 或 LKG。

查看草稿时重新核验远端附件，再打开私有草稿编辑 URL `/releases/edit/...`；`/releases/tag/untagged-*` 的 404 不代表附件丢失。浏览器需登录有仓库权限的账号，PAT 不放入浏览器 URL。

### 电脑临时启动后，在手机提交原表单

不需要测试前登记或开机监听。电脑校验并临时启动同一候选 IMG，在手机实际测试功能后进入「真机测试反馈」。

1. 选择候选或输入来源 Run ID。新候选独立小型元数据核对 repository/Run/commit/build/文件后预填 IMG SHA；手机不下载完整 boot。旧候选无元数据需手填实际 IMG SHA，并确认电脑用的镜像一致。
2. 页面自动读取设备代号、完整 build、boot_completed、uname -r、/proc/modules，也可重新读取。前四项不依赖 Root；模块列表普通读取失败仅做 `su -c cat /proc/modules` 回退，读不到明确显示未取得。
3. 用户选择通过/失败，人工确认已进入待测桌面及关键模块/基础功能正常，填写备注并核对摘要。人工项默认不勾选，自动读数不代替手动测试；模块全文保存在本机草稿供查看，原工作流仍提交既有确认字段，不宣称上传了完整模块附件。
4. 待测无法开机、恢复后才打开 App，选「待测启动失败，未能进入系统；现已恢复」：只许失败，待测 build/uname 留空、启动/模块确认 false，恢复环境不冒充待测证据。
5. 直接 dispatch 原 `xiaomi-sm8850-os4-ota-test-feedback.yml`，固定 dev。新增可选 client_request_id / 运行标题用于关联；网页入口保留。服务端仍核对来源、镜像/ABI、同 OTA fixture 字节复现及人工确认。
6. 本地先记录编号再发请求。响应未知时查原运行、不自动重发；两分钟仍无运行记录可手动重发同一编号及内容，服务端阻止重复建 Issue 或固化基线。前台跟踪原运行，后台暂停，重新打开继续；最近历史最多 20 条。

通过只固化开发基线，不修改 LKG。build/uname/启动完成不是指定 IMG 已启动的硬件证明，仍需用户确认实际电脑镜像；不能用磁盘 boot 分区 SHA 冒充临时镜像 SHA。纯 GKI 临时会话未必有 Root，不能把 Root 缺失直接当作基础启动失败。

## 验证状态与能力边界

- 工作台已提供 GKI / ABI / 待测 / 基线 / 自定义 / LKG 的可信阶段快照，可按需刷新；接收回执及所提交反馈运行支持前台自动跟踪。阶段快照不提供逐秒编译进度。
- 不自动清理远端采集 Draft，不自动刷机/晋升 LKG，不承诺后台上传或断点续传。
- OS4.0.5.0 真机已确认既有 Root、采集、上传、同版本自定义 boot 漂移安全拒收、草稿查看与本地删除。关键接收 Run 41280701707 是有效拒收证据，结论 failure 不应被误删成无用日志。
- **2026-11-02 真机确认（0.13.0-dev）：**用户亲自点击「验证 GitHub 连接」，等待创建/读取/删除空 Draft 和权限探针完成，显示已验证；同机成功读取 Gen 4 稳定归档阶段、活动 slot A、系统与 `uname`、启动完成状态和 659 条已加载模块记录。详见 [真机验收记录](../../docs/pandora-ota-real-device-verification-2026-11-02.md)。0.13.1-dev 的 UI 精修仍需覆盖安装后确认。当前 CI 结果以对应提交的 APK、固定签名、模拟器和契约工作流为准。

## 编译和固定签名

JDK 17 / SDK 35 / Gradle 8.11.1：

```bash
python3 -m unittest discover -s . -p 'test_*.py' -v
bash -n mobile-intake.sh
gradle --no-daemon :app:assembleDebug
```

[模拟器工作流](../../.github/workflows/pandora-os4-ota-companion-emulator.yml)执行设备端仪器测试并保存工作台、记录、设置、反馈、全链路状态及帮助六张截图；[接收契约](../../.github/workflows/pandora-os4-ota-mobile-intake-contract-tests.yml)与上游隔离门禁分别校验协议与源码边界。

稳定签名要求四项 Secrets：PANDORA_OTA_SIGNING_KEYSTORE_B64、PANDORA_OTA_STORE_PASSWORD、PANDORA_OTA_KEY_ALIAS、PANDORA_OTA_KEY_PASSWORD。缺失即失败，不把只有 Debug 的结果当成正式通过。正式产物验证 APK v2 签名及固定证书 SHA-256：`6a465b38eaadaca90f9186634f416a16c03eeef6056813e5ae906c0aecab732f`。私钥和密码不提交源码、日志或公开附件；现有私密离线备份继续保留。


## 历史版本记录

此处记录功能演进；当前操作流程和限制以本文前面的「当前能力」「验证状态与能力边界」为准。

| 版本 | 主要变化 |
| --- | --- |
| 0.11 | 加入采集目录空间预检、GitHub 实时连接与真实写权限验证、系统 build 变化提醒、上传后的前台接收回执跟踪 |
| 0.12 | 加入手机真机测试反馈表单、本机五项证据读取、原工作流提交与反馈历史 |
| 0.12.1 | 修订帮助说明及模拟器截图工具 |
| 0.12.2 | 修复成功响应被静默截断至 16 KiB 的问题；完整读取 GitHub JSON，超过 2 MiB 明确失败，错误提示最多保留 16 KiB |
| 0.13 | 加入来源绑定的全链路阶段快照卡及相应帮助；服务器配套增加候选/固定输入恢复入口。后续维护补齐模拟器 Launcher 弹窗处理、页面就绪确认与黑屏拒收 |
| 0.13.2 | 支持严格的 `.Dxx` 软件修订格式；服务端分类为待核验而非自动声明无内核变化；增加 `REVIEW_REQUIRED` 回执、前台停止轮询及本地安全删除适配 |
| 0.13.3 | 修复 D02 更新页面版本与 Android Build 不一致：用户单次确认 Dxx，采集与 Draft 回执保留独立来源声明，同 Build 只审阅不触碰内核基线 |

HTTP User-Agent 的版本字段直接取构建生成的 `BuildConfig.VERSION_NAME`，随 App 版本自动更新。
