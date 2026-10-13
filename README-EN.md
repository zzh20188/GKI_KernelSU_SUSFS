<div align="center">

# GKI KernelSU SUSFS
### 🏮 2026 🐎 Happy New Year! 🏮

**Automated GKI Kernel Builds | KernelSU + SUSFS Integrated**

[![Release](https://img.shields.io/github/v/release/zzh20188/GKI_KernelSU_SUSFS?label=Release&style=flat-square&logo=github&logoColor=white&color=2ea44f)](https://github.com/zzh20188/GKI_KernelSU_SUSFS/releases)
[![Coolapk](https://img.shields.io/badge/Follow-Coolapk-3DDC84?style=flat-square&logo=android&logoColor=white)](http://www.coolapk.com/u/11253396)
[![KernelSU](https://img.shields.io/badge/KernelSU-Supported-5AA300?style=flat-square)](https://kernelsu.org/)
[![SUSFS](https://img.shields.io/badge/SUSFS-Integrated-E67E22?style=flat-square)](https://gitlab.com/simonpunk/susfs4ksu)

English | [**简体中文**](README.md)

---

</div>

## 🚀 Quick Navigation

- 📖 [Documentation](https://github.com/zzh20188/GKI_KernelSU_SUSFS/wiki)
- 📥 [Downloads](https://github.com/zzh20188/GKI_KernelSU_SUSFS/releases)
- 🔰 [Tutorial](https://zzh20188.github.io/GKI_KernelSU_SUSFS/guide.html)

---

## Recent Updates

1. Disk cleanup now runs in parallel across workflows, speeding up overall builds
2. Fixed intermittent failures when fetching kernel sources, repositories, and tools
3. Synced upstream SUSFS updates and added compatibility with unsupported GKI versions
4. Refreshed the web UI style
5. Releases now also ship the SUSFS patches, corresponding to **upstream `50_add_susfs_in_gki-<kernel version>.patch` and the other files** (except `10_enable_susfs_for_ksu.patch`, which is already built into SukiSU). They can be used as a drop-in replacement without SUSFS-side errors. This is not needed if you do not build kernels: typically you clone SukiSU into the source tree and apply this patch, then run the build script or bash commands directly, skipping many steps

---

## ⚠️ Compatibility Notice

> **Note:** OnePlus ColorOS 14/15 is currently not supported. A data wipe may be required after flashing.

> **rekernel feature (beta): rekernel feature is now supported (currently in beta)**


---

## 📚 Documentation & Guides

For detailed instructions, please refer to the [**GitHub Wiki (bilingual CN/EN)**](https://github.com/zzh20188/GKI_KernelSU_SUSFS/wiki)

Wiki covers:
- [**🔰 Tutorial**](https://zzh20188.github.io/GKI_KernelSU_SUSFS/guide.html)
- 📥 Download / Flash kernel
- 💡 Tips & Tricks
- 🆘 Brick Recovery Guide
- 📊 Kernel Version Compatibility
- 🧩 [**Advanced Features**](docs/advanced-features-en.md): GhostLock Security Fix, Droidspaces Container Support, Custom Commit Pinning, Spoofing `/proc/config.gz`

---

## 🛠️ Post-Install Recommendations

### 📦 Recommended Modules

<table>
<tr>
<th>Module</th>
<th>Repository</th>
<th>Channel</th>
</tr>
<tr>
<td><b>LSPosed-Irena</b></td>
<td><a href="https://github.com/re-zero001/LSPosed-Irena">GitHub</a></td>
<td><a href="https://t.me/lsposed_irena">Telegram</a></td>
</tr>
<tr>
<td><b>Zygisk Next</b></td>
<td><a href="https://github.com/Dr-TSNG/ZygiskNext">GitHub</a></td>
<td rowspan="2"><a href="https://t.me/real5ec1cff">Telegram</a></td>
</tr>
<tr>
<td><b>TrickyStore</b></td>
<td><a href="https://github.com/5ec1cff/TrickyStore">GitHub</a></td>
</tr>
</table>

### 🔧 Xposed Modules

| Module | Description |
|:---:|:---|
| **FuseFixer** | [Unicode zero-width fix module](https://t.me/real5ec1cff/268) |

### App

| Name | Description |
|:---:|:---|
| **Scene** | [Official Site](https://omarea.com/#/) |
---

<div align="center">

**More content coming soon...**

⭐ If this project helps you, please give it a Star!

</div>
