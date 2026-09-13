# 独立 FETCH 校验工具验收记录

2026-09-13 在用户提供的本地 NebulaGraph 3.6 实例（127.0.0.1:9669）完成。工具使用本仓库 client 3.8.4，独立 JAR 只连接 graphd。构建使用 JDK 8；最终 JAR 命令行另在本机默认 Java 17 上运行通过。

本次新增 78 项校验工具单元测试全部通过，另运行既有 Client 扫描回归测试 18 项，全部通过。两组真实验收分别执行完整 FETCH 采集及离线比较，并逐项制造、恢复目标异常。

| VID 类型 | 源空间（只读） | 独立测试目标 | 清单点数 | 清单边数 | 初始及恢复后结果 |
|---|---|---|---:|---:|---|
| FIXED_STRING(128) | mig_mtzi85s0_s_a | verify_20260913_s_b | 127 | 131 | MATCH |
| INT64 | mig_mtzi85s0_i_a | verify_20260913_i_b | 127 | 131 | MATCH |

两组共 516 个清单对象。每个点包含全部实际 Tag，跨 Tag 不重复计点；这不是对全库数量的验证。两个目标使用已有搬迁导出样本导入生成，验收结束已恢复目标数据并再次匹配源基准；未修改旧验收 A/B 空间。

本次源 FETCH 基准在目标导入完成后采集，期间源保持不变。尚未严格按“源 FETCH 基准 → 目标导入 → 目标 FETCH”顺序完整重跑，也未在两台网络隔离机器上验收；已有结果证明本机真实搬迁后的独立 FETCH 内容校验。

原样本包括 14 种属性类型、全 NULL/逐列 NULL、整数边界、FLOAT/DOUBLE 有限极值与非正规数及正负零、日期时间微秒、DURATION 整数字段边界；字符串涵盖引号、反斜杠、CR/LF、NUL、emoji、组合字符、全部 256 种字节、无效 UTF-8 和约 1 MiB 字符串。两类 VID 均包含相应边界，另有多 Tag、无属性 Tag/Edge、特殊 Schema 名、自环、多 rank。样本来源与详细清单见 [原搬迁验收说明](../../../migration/acceptance/2026-09-13/README.md)。

| 每组真实异常测试 | 实测结果 |
|---|---|
| DOUBLE 0.1 改为 Math.nextUp(0.1) | DIFFERENT，恰好 1 个对象/属性不同 |
| 删除某点无属性 bare_tag | DIFFERENT，实际 Tag 缺失被发现 |
| 给某点增加新的无属性 Tag | DIFFERENT，多出 Tag 与相关 Schema 被发现 |
| 删除指定无属性 bare_edge | ERROR，1 个清单对象 MISSING，不能宣称一致 |
| 两次采集不存在的空间 | 两次均拒绝，未产生有效快照 |
| 所有修改恢复后再次采集 | MATCH |

额外通过单元测试验证：两侧相同 ERROR/MISSING 仍不能 MATCH；损坏文件、缺物理记录、重复 ID、未写完的快照、错误计数、不同计划被拒绝；中文 Tag 名 Base64 中含 `/` 时仍正确识别无属性 Tag；差异明细截断不改变结论。

第一次试运行发生在测试目标导入尚未结束时，采集发现尚未写入的边并返回 INCOMPLETE，测试正确失败。待导入正常退出且数据稳定后重新执行，以上两组正式验收全部通过。此过程也说明必须遵守数据稳定前提，不能把搬迁中的目标作为完整校验对象。

## 可执行 JAR 验收

使用最终 `nebula-data-verifier-3.8.4.jar`，分别启动独立进程执行 `prepare`、源端 `capture`、目标端 `capture`、`compare`，正常结果 MATCH、退出 0。另对真实异常采集文件执行比较，浮点位差异退出 2，无属性边缺失退出 1，报告结果分别为 DIFFERENT/ERROR。`prepare` 与全部 `compare` 进程均移除 `NEBULA_PASSWORD`，没有传递数据库连接参数。

当时验收所用 JAR 的 SHA-256 记录在 [jar-sha256.txt](jar-sha256.txt)，重新构建的文件可能因构建元数据不同而具有不同摘要。可执行文件位于仓库的 `verification/target/nebula-data-verifier-3.8.4.jar`；仅复制此一个 JAR 即可运行，无需 migration JAR。

## 保存的证据

- [字符串 VID 真实测试报告](string-acceptance-report.txt)
- [整数 VID 真实测试报告](int64-acceptance-report.txt)
- [JAR 正常比较报告](cli-match-report.json)
- [JAR 单个浮点位差异报告](cli-different-report.json)
- [JAR 缺失边报告](cli-error-report.json)
- [单元测试汇总](unit-test-summary.json)

本目录同时保存两组源、初始目标、恢复后目标的 manifest（含相关 Schema、完成状态、记录摘要）。完整原始采集数据保留在：

```text
verification/target/acceptance/verifier-mtzn18ey/   # 字符串 VID 正式验收
verification/target/acceptance/verifier-mtzn2cl5/   # 整数 VID 正式验收
verification/target/cli-acceptance/               # 最终 JAR 命令行验收
```

这些 target 目录不进入 Git；正式保存、跨机传输时需复制完整 plan 和 snapshot 目录。快照不包含连接密码。输出的 MATCH 仅对清单对象在各自采集时刻的可读内容成立，不检查清单外数据，也不代替数据库一致性快照。
