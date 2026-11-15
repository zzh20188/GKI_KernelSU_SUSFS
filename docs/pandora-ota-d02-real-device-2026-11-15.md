# Xiaomi 17 Pro OS4 · D02 软件修订真实 OTA 验收（2026-11-15）

> 设备 pandora，Android 16 / HyperOS OS4；本记录源于用户同日实机 ADB 输出、系统更新页面照片、独立分区 SHA-256 对比以及 GitHub Actions / Draft Release 审计。**这是软件修订接收闭环的实机证据，不是跨内核版本 OTA 的 GKI/ABI/临时启动验收。**

## 1. 更新前的真实环境与恢复点

- 原始运行系统：`OS4.0.5.0.XBLCNXM`；设备 `pandora`，活动 Slot A，内核 `6.12.69-android16-6-os4r62-custom-4k`；KernelSU Root 有效。
- 用户离线保存了该系统对应的、未经 Root 和内核修补的原厂 `boot.img` 与 `init_boot.img`，且保留当前 A 槽 `boot/init_boot/vendor_boot/dtbo` 四分区快照。
- 当前运行 A 槽启动分区的 SHA-256，经用户确认本地镜像与真机分区一致：

| 分区 | 升级前 SHA-256 | D02 更新准备阶段二次核验 |
| --- | --- | --- |
| `boot_a` | `8e12104a1d2924be8728f28711e353ead6ea7d95bab2915b4673a024a9923df7` | 相同 |
| `init_boot_a` | `16aa388f088eb0066198828fa1ff59abcf870808ef6c0627fd9abf6f7d727711` | 相同 |
| `vendor_boot_a` | `b30b55dbe20b2b39fce8d2bf53e27352227c337d05af7fd681fb7968da0b1a4a` | 相同 |
| `dtbo_a` | `0f210837bdcb385d13bb5096331d600fd9878f918992e5d6d17bcd8e811608c8` | 相同 |

原厂镜像与上述 Root/自定义快照不同，不可混淆。原厂 `boot.img` SHA `4f873abec68220c0028c11c8833e7ec8b9652f263e850f1ddb76d19913374653`，原厂 `init_boot.img` SHA `73436c1032869c85c1153a37b16def09cf7e01cfb434b1bdeddeb1946f816cb8`。

## 2. OTA 准备、槽位与正常重启

用户直接使用小米系统更新安装 D02，未恢复原厂内核，保持自定义内核、Root 和 Slot A；更新器出现「立即重启」。重启前检查得到：

- `ro.build.ab_update=true`、`ro.virtual_ab.enabled=true`；当前 Slot `_a`。
- 当时 B 槽 `boot_b`（96 MiB）、`init_boot_b`（8 MiB）、`vendor_boot_b`（96 MiB）、`dtbo_b`（32 MiB）逐分区 SHA 与对应大小的全零数据匹配，读取的分区头也为零。这些都是**重启前**的观测，不可假设 OTA 本身清空了 B 槽，因升级前未记录 B 槽基线。
- 旧版 `bootctl` 返回 `Error getting bootctrl v1.0 module.`，但 update_engine 日志可加载 AIDL `android.hardware.boot.IBootControl/default`。经 AIDL 服务只读调用，下次启动 Slot 0 (A)、当前 Slot 0 (A)，B 槽不可启动、快照合并状态 NONE。
- `update_engine.20261115-131757` 日志显示 `OTAResult::NOT_ATTEMPTED`、旧状态清理完成，没有捕获 D02 payload 安装过程；不能用该条日志认定 OTA 失败或成功。

**真正首次重启后**，用户提交：

```text
ro.boot.slot_suffix=_a
ro.mi.os.version.incremental=OS4.0.5.0.XBLCNXM
uname -r=6.12.69-android16-6-os4r62-custom-4k
su -c id → uid=0(root), context=u:r:ksu:s0
```

更新器页面实际显示 **`4.0.5.0.XBLCNXM.D02`**，更新说明主要为相册/计算器优化。因而确认此次 D02 软件修订安装后正常开机、运行内核/Slot/Root 保留；但**不能推论任何未来 Dxx 或完整系统 OTA 都不会修改内核，也不能将更新器显示的 D02 伪装成 Android 系统属性**。

## 3. OTA 助手 0.13.3 实机采集及 GitHub 接收

0.13.3-dev（versionCode 27）经 [PR #81](https://github.com/wekingchen/GKI_KernelSU_SUSFS/pull/81) Squash 合并至 dev：`14ff5745ee27fcd10189c77985fdc6e4e0f60051`。主分支固定签名 APK、Android 模拟器及手机 OTA 接收契约测试均成功，且用户完成覆盖安装后现场实测：

| 核验项 | GitHub 实际记录 |
| --- | --- |
| 采集包 | `pandora-os4-capture-20261115-064627-116.zip` |
| 设备/Build/Slot | `pandora` / `OS4.0.5.0.XBLCNXM` / `_a` |
| 软件修订号 | `D02` |
| 软件修订来源 | `USER_CONFIRMED_UPDATE_SCREEN`（用户核对更新器照片，**非 Android 属性自动证明**） |
| 核验源断言 | `software_revision_verified_by_device=false` |
| 内核/Boot | `6.12.69-android16-6-os4r62-custom-4k`；boot SHA 与 OTA 前 `boot_a` 一致 |
| GitHub Draft Release ID | `445865878`（只保留 Draft，不 Publish） |
| GitHub Asset ID | `750110892` |
| GitHub Asset 长度 | `89,575,444` 字节（约 85.43 MiB） |
| GitHub Asset SHA-256 | `c14c07df4a69af3ec9bea4430c3ec0af6df3722036aef199cf252daa54866398` |
| OTA 接收工作流 | [Run #43687115408](https://github.com/wekingchen/GKI_KernelSU_SUSFS/actions/runs/43687115408)，结论 success |
| 接收状态 | `SOFTWARE_OTA_REVIEW` → Draft 回执 `REVIEW_REQUIRED` |

工作流日志明确报告 `may_build_gki_candidate=false`、`safe_to_flash=false`、`fixture_promoted=false`、`lkg_unchanged=true`、`next_gate=SOFTWARE_OTA_EVIDENCE_REVIEW`。**本次只保存软件 OTA 证据**：不替换 `pandora-os4-stock-input`、不编译新 GKI、不宣称 OEM ABI 或 KMI 已重新验收、不改变已确认的 Gen 4 LKG。

软件修订单独记录为 `software_revision=D02`；真实 `build=OS4.0.5.0.XBLCNXM` 不增加 `.D02`。如果无人工修订证据，同 Build/Boot 漂移仍受原有拒收门禁约束。采集来自 Root 后的活动槽，`init_boot` 仍是诊断快照，**绝不是原厂原始镜像**。

## 4. 结果状态与后续测试边界

- **真机已完成**：D02 正常重启；Slot A、自定义内核、KernelSU Root 保留；0.13.3 采集、上传、GitHub Draft 字节及 SHA 核对、服务端分类与 `REVIEW_REQUIRED` 回执。
- **CI 已完成**：PR #81 四项检查通过，合并后 APK/模拟器/手机接收契约全部成功。
- **待未来新内核 OTA 实测**：新 Build 与 Slot 切换、完整 `boot/init_boot` 来源验证、GKI 候选编译、OEM CRC/ABI/KMI、`fastboot boot` 真机与动态 LKG 开发基线。
- **禁止误操作**：不把本次全零的 B 槽快照当成可用镜像；不自动刷写；不将 `REVIEW_REQUIRED` 改写成 `ACCEPTED` 或新 LKG；不发布包含 Root 后分区的私有接收 Draft。

最后核对：2026-11-15，证据在远端 Draft 和接收运行中。Actions 附件可能按留存策略过期，长期关键证据以本记录、离线镜像和用户保留的采集 ZIP 为准。
