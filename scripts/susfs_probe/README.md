# susfs-probe 工具分支

用未经修补的 susfs4ksu 原始补丁对每个月份分支做全量编译，得到「原始补丁能否直接应用并编译成功」的逐条结论，用来校准网页（dev 分支 `web/`）上的 SUSFS 兼容线。

与 dev 的分工：dev 负责给被淘汰的 GKI 打 SUSFS 兼容修补；本分支不做任何修补，只回答「不修补行不行」。两者边界分明，本分支不需要定期合并 dev。

## 与 dev 的差异

| 文件 | 差异 |
|------|------|
| `scripts/susfs_fixes/apply.sh` | 原始模式：只拷文件 + `patch -p1`，不做上下文调整与版本修复；写出 `apply.json`（rej 数、rej 文件、按偏移/模糊打入的 hunk 数） |
| `.github/workflows/build.yml` | 新增 `susfs_raw_probe` 开关：跳过 Unicode 绕过修复、在 `always()` 时写出并上传 `SUSFS-Probe-*` 结论产物；新增产物模式「不上传」 |
| `.github/workflows/susfs-probe.yml` | 探测入口：从 dev 现取 `data/*.json` 生成矩阵 → 逐个全量编译 → 汇总 |
| `scripts/susfs_probe/` | `build_matrix.py`、`write_result.py`、`aggregate.py` |

唯一保留的 KSU 侧适配是「SukiSU/ReSukiSU 尚未提供 su 会话 FD 接口时恢复旧版 exec hook」，它只取决于 KernelSU 变体、与内核子版本无关；是否触发会记录在结论的 `ksu_compat_applied` 字段里。若要完全严格，删掉 `apply.sh` 里对应的段落即可。

## 怎么跑

Actions → 「SUSFS 原始补丁探测」→ Run workflow：

- `kernel_version`：all 或某一个内核版本
- `kernelsu_variant`：默认 SukiSU（用 ShirkNeko fork 的补丁，缺分支时回退 simonpunk）；ReSukiSU / Official 用 simonpunk
- `sub_level_min` / `sub_level_max` / `os_patch_levels`：只重跑某个区间或某几个月份
- `include_lts`：是否连 `-lts` 分支一起探测
- `upload_kernels`：默认不上传内核包，只留结论
- `max_parallel`：并行数，全量 125 个任务按 20 并行约 5 到 6 小时

## 结论

每个任务上传 `SUSFS-Probe-<android>-<kernel>-<sub>-<月份>-<变体>` 产物（`result.json` + `apply.json` + `patch.log`），最后一个 job 汇总成 `SUSFS-Probe-Summary`：

- `summary.md`：按分支的表格，含「连续兼容起点」
- `summary.json`：按 `<android>-<kernel>` 分块，每块可直接落成 dev 上的 `data/susfs_probe/<android>-<kernel>.json`

`verdict` 取值：

| 值 | 含义 |
|----|------|
| `clean` | 0 个 .rej 且编译成功，网页据此画兼容线 |
| `built_with_rej` | 有 .rej 但编译成功，功能可能缺失，网页用空心方块标出 |
| `failed` | 编译失败 |
| `error` | 探测流程本身没跑完（源码同步失败等），不是内核结论 |

网页只把 `clean` 当作「可直接应用」，且只在 clean 的版本从某一行起连续到最新时才画兼容线，否则只逐行标记。
