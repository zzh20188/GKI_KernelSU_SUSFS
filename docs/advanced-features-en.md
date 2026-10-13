<div align="center">

# 🧩 Advanced Features

**GhostLock Security Fix · Droidspaces Container Support · Custom Commit Pinning · Spoofing `/proc/config.gz`**

English | [**简体中文**](advanced-features.md)

[⬅ Back to README](../README-EN.md)

---

</div>

## Contents

- [🛡️ GhostLock Security Fix](#️-ghostlock-security-fix)
- [🧪 Droidspaces Container Support (Experimental)](#-droidspaces-container-support-experimental)
- [🔧 Custom Commit Pinning](#-custom-commit-pinning)
- [🧪 Spoof `/proc/config.gz` (Stock Config)](#-spoof-procconfiggz-stock-config)

---

## 🛡️ GhostLock Security Fix

GhostLock is a pair of high-risk Linux kernel vulnerabilities tracked as `CVE-2026-43499` and `CVE-2026-53163`. An attacker does not need Root access or an additional kernel module. The vulnerability may be exploited by any application or local process that can run code on the device.

### Potential impact

- **System crash or forced reboot:** An ordinary application can crash the kernel, making the device unavailable and potentially causing the loss of unsaved data.
- **Local privilege escalation:** A more advanced exploit can cross Android security boundaries and give an ordinary application kernel-level control of the device.
- **Public exploits are available:** Both a denial-of-service proof of concept and a complete privilege-escalation chain targeting Android ARM64 have been published. This is no longer a theoretical risk.
- **No reliable temporary workaround exists:** Permission restrictions, application isolation, and hardening options may make exploitation harder, but they cannot fully prevent crashes or alternative exploit methods.

The vulnerability cannot be triggered directly over the network. However, a malicious application, untrusted code in a shared environment, or an attacker who already gained code execution through another vulnerability can use GhostLock as the next step. Extra care should therefore be taken with applications, modules, and scripts from unknown sources.

This project can check and apply the complete fix when building kernels 5.10, 5.15, 6.1, 6.6, and 6.12. The option is disabled by default. To include GhostLock protection, manually enable `CVE-2026-43499 rtmutex fix chain` when starting a build. Both vulnerability fixes must be present together, and the workflow handles this automatically. Kernels that already contain the complete fix are not patched again.

The fix has passed a [full build validation covering 84 kernel versions](https://github.com/zzh20188/GKI_KernelSU_SUSFS/actions/runs/29509099128). For vulnerability details, affected systems, public exploits, and mitigation guidance, read CIQ's article: [GhostLock Mitigation](https://kb.ciq.com/article/rocky-linux/rl-ghostlock-mitigation).

---

## 🧪 Droidspaces Container Support (Experimental)

> **Experimental feature:** Successful build and boot is not guaranteed across all GKI versions. Always back up your boot image before flashing.
>
> **TIPS:** The workflow uses the [official Droidspaces patches](https://github.com/ravindu644/Droidspaces-OSS/tree/main/Documentation/resources/kernel-patches/GKI) from [Droidspaces](https://github.com/ravindu644/Droidspaces-OSS). If you have better patches, feel free to open an issue. Since there are three patch variants, you may need to test them repeatedly to find one that fits your device. Choose based on other users' feedback or your own experience.

[Droidspaces](https://github.com/ravindu644/Droidspaces-OSS) is a lightweight Linux containerization tool that lets you run full Linux environments (with systemd, OpenRC, etc.) on Android — useful for development, running servers, and more.

**Supported versions:** 5.10 / 5.15 / 6.1 / 6.6 / 6.12

**Usage:** When triggering a build manually, select the `Droidspaces` option:

| Option | Description |
|:---:|:---|
| `off` | Disabled (default) |
| `678` | Use 6_7_8 slot patch (recommended) |
| `123` | Use 1_2_3 slot patch (fallback) |
| `345` | Use 3_4_5 slot patch (fallback) |

> **Note:** Kernel 6.12 has only one patch — any non-off option will use it.

**If the build fails or bootloops after flashing:** Try switching to a different slot patch (e.g. 678 → 123 or 345). Different kernel sub-levels may require different patches.

---

## 🔧 Custom Commit Pinning

Use the [`config/config`](../config/config) file to pin SUSFS and SukiSU to specific commits.

**What is a commit?**

A commit is a hash string representing the state of a repository at a specific point in time. For example, setting sukisu to `4b8644515fe6d87a109129e590ccd9d33a855dca` means using the January 30th version of SukiSU to build the kernel.

**Why pin a commit?**

- When upstream updates introduce bugs or compatibility issues, you can roll back to a stable version
- When SUSFS and SukiSU versions are out of sync causing build failures, you can manually specify compatible versions

**How to get a commit hash?**

- SUSFS: [susfs4ksu](https://gitlab.com/simonpunk/susfs4ksu)
- SukiSU: [SukiSU-Ultra commits/builtin](https://github.com/SukiSU-Ultra/SukiSU-Ultra/commits/builtin/)

Taking SUSFS as an example, first select the branch, then copy the commit hash:

![Select branch](../assets/susfs_branch.png)
![Copy commit](../assets/susfs_commit.png)

```ini
# Enable custom commits
custom=true

# SUSFS commit hash per branch
gki-android12-5.10=
gki-android13-5.15=
gki-android14-6.1=
gki-android15-6.6=

# SukiSU commit hash
sukisu=
```

> Empty value = use the latest commit of that branch.

---

## 🧪 Spoof `/proc/config.gz` (Stock Config)

This is an advanced trick and requires no workflow toggle.  
The build process auto-detects whether `config/stock_defconfig` exists: if present, it is applied; if absent, it is skipped.

How to use:
1. Make sure your device is running stock ROM + stock kernel.
2. Obtain `/proc/config.gz` from your device (phone-side or PC-side workflow both work).
3. Decompress it, rename it to `stock_defconfig`, upload it to the [`config/`](../config/) directory in your repo, and commit (can be done directly on phone).

During the build, the workflow will automatically:
- Copy it to `$KERNEL_ROOT/common/arch/arm64/configs/stock_defconfig`
- In `$KERNEL_ROOT/common/kernel/Makefile`, switch the `$(obj)/config_data` rule from `$(KCONFIG_CONFIG)` to `arch/arm64/configs/stock_defconfig`
- Make `/proc/config.gz` in the built kernel closer to your stock kernel config

---

<div align="center">

[⬅ Back to README](../README-EN.md) · [简体中文](advanced-features.md)

</div>
