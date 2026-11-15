# 小米 17 Pro OS4：OTA、手机采集与真机反馈操作指南

核对日期：2026-11-15（D02 软件修订实机采集、上传与 GitHub 回执均已验收）。适用 pandora；[当前状态](pandora-os4-guide.md)以机器可读指针和真实记录为准。当前稳定快照为 OS4.0.5.0.XBLCNXM / R62 Gen 4。

## 一、先判断是否需要重新采集

| 你的情况 | 操作 |
| --- | --- |
| 系统 build 没变，只想更新组件或自选功能 | 日常编译无需重复采集；如更新器明确显示 D02 等新软件修订，使用 0.13.3 的人工确认入口记录 Dxx，提交后只进入 REVIEW_REQUIRED；未声明修订的同 build / boot 漂移门禁保持不变 |
| 升级到另一个 HyperOS OTA build | 重新采集；仅完整内核 OTA 经 ABI/KMI 后才建立新 GKI 基线；同主版本 `.D02` 类软件修订只记录证据并待核验，不自动替换固定输入 |
| 只是重新安装 App 或换 PAT | 不用重采；固定签名覆盖安装保留原数据 |

## 二、新 OTA 之后先保留恢复点

1. 升级并正常启动，核对设备、系统版本和 slot。
2. 从**同版本**官方 OTA 离线保存未经修补的原始 `init_boot.img` 及来源、SHA-256，再用对应镜像修补 Root。不能用旧版修补镜像恢复新 OTA。
3. 按本机 Root 方案恢复 Root；为 `com.wekingchen.pandoraota` 单独授予超级用户权限。ADB shell 有 Root 不等于 App 有授权。
4. 另外保留同版本、同 slot 的已知可启动分区备份。Root 状态备份可以用于当时的恢复，但不能冒充完全未修改的 OEM 原版。

App 新 build / fingerprint 提醒的「已了解」只表示读过说明，不表示它代你备份了原始镜像。已 Root 时仍可从同版本官方 OTA 保存原件，不必为了采集撤销 Root。

## 三、手机首选：配置、预检、采集和上传

安装最新固定签名 APK，在工作台/设置完成以下操作：

| 配置 | 要求 |
| --- | --- |
| 仓库 | wekingchen/GKI_KernelSU_SUSFS |
| 采集和上传 PAT | 仅目标仓库，Contents: Read and write |
| 手机提交真机反馈 | 上述权限再加 Actions: Read and write |
| 令牌保存 | Android Keystore 加密；不要清除应用数据以免丢失本地回执和令牌 |

1. 工作台检查 Root，再点击「开始采集」。预检通过后如更新页面显示 D02、但 Android Build 未变，则在「软件修订号 · 可选」中填 `D02`；原系统属性、fingerprint、slot 原样保留，仅附加一份用户声明，绝不将 D02 虚构成 Android Build。无软件修订时选「无软件修订」。实际 captures 目录可用空间 **<1 GiB 阻止，1 GiB 至不足 2 GiB 警告，≥2 GiB 正常继续**；读取分区前再次核对。
2. 每次采集/上传前在线核验 GitHub：真实读取仓库、创建/核对/删除专属空草稿、发送不会触发接收的权限探针。仅显示已配置不代表实时写权限通过；清理失败会给出空草稿 ID。
3. 只读采集活动 slot 的 boot/init_boot/vendor_boot/dtbo 与 OEM 模块，生成最终 ZIP；完成后清理临时镜像/TAR/staging，失败清理残缺 ZIP 并保留明确错误。
4. 上传到专用 Draft Release，不要 Publish。显示真实字节进度；服务器确认附件后保存回执，再通知手机接收工作流。保持 App 前台及网络稳定，不支持断点续传。
5. 前台自动查询接收结果，后台暂停、返回恢复。PENDING 保留 ZIP；ACCEPTED 只说明输入接收，后续 GKI/ABI 仍需继续；ALREADY_CURRENT 不必重复；REVIEW_REQUIRED 表示软件 OTA 来源证据已保存、待核对内核/OEM 差异且不替换 stock-input；REJECTED 说明门禁未通过，远端原始附件仍在。
6. 通知失败使用「重新通知」，不重复上传。记录页可切换历史 ZIP；通知成功且接收到终态后，用户可主动核验并清理本地 ZIP。远端附件名称/ID/大小/SHA 和本地 SHA 必须一致，网络失败不能删除。

手机包内的 Root 后 init_boot 是诊断快照，不能替代离线原件；boot 与 OEM .ko 是内核/ABI 主输入，但并非因为 init_boot 已修补就自动证明 boot 原厂。当前转换仍维持八文件 xm.zip 契约。

## 四、等待候选，电脑只负责临时启动

服务端接收器先暂存、下载回检新输入，再安全替换固定 `pandora-os4-stock-input` 的唯一 xm.zip。无需自己先删除旧附件。[OTA 主工作流](../.github/workflows/xiaomi-sm8850-os4-ota-auto-candidate.yml)自动识别、构建 GKI、核对 OEM 模块 CRC/vermagic；失败可能只有报告，没有可启动 IMG。

从同一来源 Run 的 `pandora-OTA-OEM-ABI-TESTBOOT-<build>-<run>` 下载待测 IMG、TESTBOOT-SHA256SUMS 和说明。电脑先核对**IMG 本身**，不要使用 ZIP/Artifact 的 SHA：

```cmd
certutil -hashfile "你的待测文件.img" SHA256
adb reboot bootloader
fastboot devices
fastboot boot "你的待测文件.img"
```

电脑执行临时启动；App 不执行 fastboot、flash、重启、切 slot 或写分区。静态通过不表示已启动。纯 GKI 阶段尚未集成完整自定义功能，且可能阻止旧 KernelSU LKM，因此 Root 未必可用，不能以必须有 Root 作为基础启动通过条件。

## 五、手机上测试并直接提交

进入 **工作台 → 真机测试反馈**，不需要测试前登记，也不用网页填表。

1. 选择电脑实际测试的候选，或输入来源 Run ID 后「读取候选信息」。新候选通过独立小型附件预填 SHA-256；旧候选需手填。用户确认实际 IMG 一致。
2. 页面自动读取以下五项，也可重新读取；模块普通权限受限时尝试只读 Root，失败显示未取得。模块完整文本可展开，保存在手机草稿；不能把未取得当作正常。
3. 手动测试关键模块、Wi-Fi、移动数据/通话、蓝牙、相机等功能，再选通过/失败，填写确认项和备注，核对摘要提交原工作流。

| 原电脑检查 | App 读数 |
| --- | --- |
| getprop ro.product.device | 设备为 pandora |
| getprop ro.mi.os.version.incremental | 实际完整 build |
| getprop sys.boot_completed | 启动完成应为 1 |
| uname -r | 实际运行内核 |
| cat /proc/modules | 已加载模块；失败不伪装成功 |

电脑备用检查（手机 App 已可直接完成，不必两边重复）：

```cmd
adb shell getprop ro.product.device
adb shell getprop ro.mi.os.version.incremental
adb shell getprop sys.boot_completed
adb shell uname -r
adb shell cat /proc/modules
```

自动读数不会替你勾选人工确认。`build + uname + boot_completed=1` 不是指定 IMG 的硬件证明；不能读取磁盘 boot 分区的 SHA 来冒充 fastboot boot 的临时镜像 SHA。

**无法开机、恢复后才打开 App**：选择「待测启动失败，未能进入系统；现已恢复」。只允许失败反馈，待测 build/uname 留空，启动/模块确认强制 false，当前信息标为恢复后环境。

提交前本地保存唯一编号。响应未知时先查原请求，不自动再发；两分钟仍未建立运行记录，可手动重发同一编号和内容，服务端阻止重复处理。前台显示等待、运行、完成或失败，后台暂停、返回继续，历史最多 20 条。

通过后服务端独立核对 Run、固定输入、IMG SHA、ABI 和同 OTA 字节复现，自动固化版本 fixture、verification 与开发指针；失败只保存报告。**这不是 LKG 晋升。** 原[网页反馈入口](../.github/workflows/xiaomi-sm8850-os4-ota-test-feedback.yml)和上述 ADB 检查仍可作为备用。

## 六、后续功能编译与稳定晋升

新 OTA 基础验证通过后，仍使用 [OS4 自定义内核](../.github/workflows/xiaomi-sm8850-os4-custom.yml)选择功能。每次解析最新稳定渠道，成功后同次提供使用同一 Image 的待测 IMG 与 `OTA_CUSTOM_UNVERIFIED` AnyKernel3；**不需要第二次反馈才能取得 ZIP**。先临时测试，再由用户决定是否持久安装。

真正持久安装、稳定使用且选用功能均正常后，在[唯一晋升入口](../.github/workflows/xiaomi-sm8850-os4-promote-lkg.yml)填写原自定义 Run，确认启动/持久安装/运行时三项。后台自动识别 R62 或新 OTA、严格复现并归档，不用用户选择重复入口。详见 [LKG 说明](xiaomi-17-pro-os4-cross-ota-lkg.md)。

## 七、Windows 和人工管理只作备用

Windows 10/11 可运行仓库最新 [`collect-pandora-stock-input.cmd`](../scripts/xiaomi-os4/collect-pandora-stock-input.cmd)，需要 adb、PowerShell、tar 和手机 Root；完整导出四分区、OEM 模块/数量清单、README、SHA256SUMS 后生成 xm.zip。模块数量或字节流出错即停止，不上传残包。

人工更新固定 Release 前，必须完整备份旧附件并核验新输入；不能先删除唯一恢复输入，再验证新 ZIP。只更换附件未必触发 edited，可等北京时间 04:41 每日补查，或手动运行 OTA 主流程。**D02 软件修订**的手机采集与审核已经实机验证；真正更换 HyperOS Build / 内核的 OTA 自动候选、ABI、临时启动与固化仍待实物验证。

## 八、恢复与验证边界

2026-11-15 真机观察：系统更新页面显示 `4.0.5.0.XBLCNXM.D02`（相册、计算器优化），而 `ro.mi.os.version.incremental` 仍为 `OS4.0.5.0.XBLCNXM`；首次重启成功，活动 slot A、自定义内核 `6.12.69-android16-6-os4r62-custom-4k` 与 KernelSU Root 均保留。旧 A 槽四分区备份 SHA 前后相同；B 槽启动分区为全零、不可启动，因此不能误将其当成 D02 boot。该实机结果证明此次软件更新未使运行内核及 Root 丢失，但不能推导其他 OTA 类型都不改内核。

从 0.13.3 起，当 Android Build 未携带 Dxx 时，用户可在 App 采集前依据更新器页面手动确认软件修订。采集包仅记录独立的 `software_revision`、`software_revision_source=USER_CONFIRMED_UPDATE_SCREEN` 以及绑定的真实 Build。服务器标记为**人工声称、未由 Android 属性验证**，只保存 Draft 和 `REVIEW_REQUIRED` 回执；不自动替换 stock-input、不运行 GKI、不晋升 LKG。0.13.3 的 D02 采集、上传与回执已完成真实验收：[D02 完整证据与 GitHub Run](pandora-ota-d02-real-device-2026-11-15.md)；0.13.4 仅更新可见修订号的 UI，仍须等覆盖安装后进行真实 UI 确认。

软件更新页面的 `.D02` 标记不保证 `ro.mi.os.version.incremental` 含相同后缀。验收需要真实的 getprop、fingerprint 与 slot；`REVIEW_REQUIRED` 不能解读为内核不变、ABI 通过或新 LKG。

当前 Gen 4 和旧 Gen 3 Release 只适用于各自已记录的系统，不是跨版本恢复包。下载并核验方式见 [长期恢复指南](xiaomi-17-pro-os4-lkg-release-archive.md)。App 既有采集/上传/本地删除已真机验证，新增预检、状态轮询和反馈表单已通过模拟器/契约；不能因此宣称另一版真实 OTA 已端到端成功。
