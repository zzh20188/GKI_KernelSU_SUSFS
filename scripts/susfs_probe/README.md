# susfs-probe 工具分支

用未经修补的 susfs4ksu 原始补丁对每个月份分支做全量编译，得到「原始补丁能否直接应用并编译成功」的逐条结论，用来校准网页（dev 分支 `web/`）上的 SUSFS 兼容线。

与 dev 的分工：dev 负责给被淘汰的 GKI 打 SUSFS 兼容修补；本分支不做修补，只回答「不修补行不行」。两者边界分明，本分支不定期合并 dev。

## 与 dev 的差异

| 文件 | 差异 |
|------|------|
| `scripts/susfs_fixes/apply.sh` | 原始模式：只拷文件 + `patch -p1`，不做上下文调整与版本修复；写出 `apply.json`（rej 数、被忽略的 hunk 数、偏移/模糊 hunk 数、KSU 侧补丁成败） |
| `.github/workflows/build.yml` | 新增 `susfs_raw_probe` / `susfs_side_fixes` / `susfs_pin_time` 输入：跳过 Unicode 绕过修复，缓存只恢复不保存，产物名追加月份避免同子版本撞名，`always()` 时写出并上传 `SUSFS-Probe-*` 结论；新增产物模式「不上传」（不打包内核、不构建 boot 镜像） |
| `.github/workflows/susfs-probe.yml` | 探测入口：从 dev 现取 `data/*.json` 生成矩阵 → 逐个全量编译 → 与 dev 上已有结论合并后汇总 |
| `scripts/susfs_probe/` | `build_matrix.py`、`write_result.py`、`aggregate.py`、`split_summary.py` |

保留 / 可选的适配（都与内核子版本无关，是否触发都记入结论）：

- **KSU 侧（总是启用）**：SukiSU/ReSukiSU 尚未提供 su 会话 FD 接口时恢复旧版 exec hook。对 SukiSU 这是链接必需，`ksu_compat_applied` 对 SukiSU 会一直为 true；ReSukiSU 一般不触发。
- **SUSFS 侧（`susfs_side_fixes` 打开才启用，默认关）**：上游 5.10 补丁自身的两处编译缺陷（statfs.c 声明晚于使用、susfs.c 缺 `linux/security.h`）。不修的话 5.10 整条线都会是「failed 且 rej=0」，探测不出子版本边界；修了则结论字段 `susfs_side_fixes_applied` 会列出。

## 怎么跑

GitHub 只列出默认分支上存在的 `workflow_dispatch` 工作流，所以 dev 上有一个同名同输入的空壳 `.github/workflows/susfs-probe.yml`。运行步骤：

1. Actions → 「SUSFS 原始补丁探测」→ Run workflow
2. 「Use workflow from」选择分支 **susfs-probe**（选 dev 只会打印提示，不会构建）
3. 填输入：

| 输入 | 说明 |
|------|------|
| `kernel_version` | all 或某一个内核版本 |
| `kernelsu_variant` | 默认 SukiSU（用 ShirkNeko fork 的补丁，缺分支时回退 simonpunk）；ReSukiSU / Official 用 simonpunk |
| `sub_level_min` / `sub_level_max` / `os_patch_levels` | 只重跑某个区间或某几个月份（不作用于 LTS，局部重跑请同时关掉 `include_lts`） |
| `include_lts` | 是否连 `-lts` 分支一起探测 |
| `susfs_side_fixes` | 是否保留上面说的 SUSFS 侧修复 |
| `susfs_pin_time` | susfs4ksu 固定到该时刻之前的最后一次提交；留空 = 本次运行开始时刻，保证几小时内所有任务用同一提交 |
| `upload_kernels` | 默认不上传内核包，只留结论 |
| `max_parallel` | 并行数，Free 计划上限 20 |
| `data_source` | 读取版本数据与已有探测结论的分支，默认 dev |

也可以用命令行：`gh workflow run susfs-probe.yml --ref susfs-probe -f kernel_version=all -f kernelsu_variant=SukiSU`。

规模：全量约 130 个任务（125 个月份 + 5 个 LTS）。单个任务编译约 20 分钟、准备约 5 分钟，20 并行约 3 小时，保守按 5 到 6 小时算。任务失败可用「Re-run failed jobs」重跑，产物已设 `overwrite`，以重跑后的 Summary 为准。

## 结论

每个任务上传 `SUSFS-Probe-<android>-<kernel>-<sub>-<月份>-<变体>` 产物（`result.json` + `apply.json` + `patch.log`），最后一个 job 汇总成 `SUSFS-Probe-Summary`：

- `summary.md`：按分支的表格，含「网页会画的兼容线起点」（与前端同一规则）与需要复核的行
- `summary.json`：按 `<android>-<kernel>` 分块，已与 `data_source` 分支上已有的 `data/susfs_probe/<key>.json` 合并（本次没跑到的月份沿用旧结论），每条目自带 `susfs_commit`
- `summary.run.json`：只含本次的原始条目（含 error），排查用

`verdict` 取值：

| 值 | 含义 |
|----|------|
| `clean` | 0 个 .rej、0 个被忽略的 hunk，且编译成功，网页据此画兼容线 |
| `built_with_rej` | 有 .rej（或有被忽略的 hunk）但编译成功，功能可能缺失，网页用空心方块标出 |
| `failed` | 编译失败。若 rej=0 仍失败，summary.md 会标「需复核」，多半是 SUSFS/KSU 侧或环境问题而非子版本不兼容 |
| `error` | 探测流程本身没跑完（同步失败、KSU 侧补丁冲突、任务没产出结论等），不是内核结论，落盘前应重跑 |

汇总会与矩阵对账：矩阵里有、结论里没有的任务记为 `error / missing_result`，summary.md 顶部会提示数量。

## 落盘到 dev

1. 下载 `SUSFS-Probe-Summary`，确认 summary.md 顶部没有 error 提示（有则用 `os_patch_levels` 只重跑那些月份）
2. 在 dev 工作树里执行：

```
python3 scripts/susfs_probe/split_summary.py summary.json data/susfs_probe
```

（`split_summary.py` 在本分支，可用 `git show susfs-probe:scripts/susfs_probe/split_summary.py > /tmp/split_summary.py` 取出来。）它会拒绝仍含 error 的分支块，`--force` 可强制。

3. 提交 `data/susfs_probe/*.json` 到 dev。网页每个分支只有一个文件，默认以 SukiSU 的结论为准；跑了别的变体只作参考，不要覆盖。

网页只把 `clean` 当作「可直接应用」，且只在 clean 的版本从某一行起连续到最新、且更早月份没有 clean 时才画兼容线，否则只逐行标记；探测文件里没有的月份视为「未探测」，不再按旧阈值猜测。

## 需要手工从 dev 同步的内容

- `scripts/gki_fetch.py` 里的 `TARGETS`（新增内核大版本时）
- `susfs-probe.yml` 的 `kernel_version` 选项（同上，dev 上的空壳也要同步）
- `build.yml` 里与源码同步、工具链、runner 环境相关的修复（这些不是 SUSFS 修补，工具分支也需要）
