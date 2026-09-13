# 最终证据归档审计

[audit_final_archives.py](../audit_final_archives.py) 是独立的 Python 只读审计入口，不连接数据库、不启动 Java、不运行 Maven，也不把 tar 文件解压到文件系统。它逐个流式读取归档成员，仅保存文件摘要、完整记录键和每条原生字段的摘要。

完整模式从已冻结的三个场景清单确定报告集合：`reports/final` 的 90 个主场景、`reports/schema-stress` 的 2 个默认值补充场景、`reports/geography-final` 的 1 个地理试跑场景。额外的 oracle、Meta 比较等 JSON 不会误计入 93 份 case 报告。

在仓库根目录执行，输出文件必须尚不存在：

```bash
python3 task/acceptance/audit_final_archives.py \
  --out task/acceptance/reports/final-archive-audit.json
```

只有全部 93 份报告及其归档通过，才能得到 `passed=true`、`status=PASSED`、`partial=false`、`checkedArchives=93`，且 `failures`、`missingReports` 都为空；退出码为 0。审计文件还记录脚本自身 SHA-256、输入场景清单 SHA-256、每份外部报告和归档的 SHA-256，以及每份归档的详细证明。

运行中可用部分模式检查已经完成的报告：

```bash
python3 task/acceptance/audit_final_archives.py --partial \
  --out task/acceptance/probes/archive-audit-partial-new.json
```

部分模式成功的状态始终是 `PARTIAL`，即使已经发现全部归档，也不能作为最终 PASSED 门禁。缺失报告明确列出；现有报告或归档损坏不会因为启用部分模式而被跳过。零份归档通过、任意审计失败或完整模式缺少报告都会返回非零。

每份归档的检查包含：

- 压缩包原始字节数和 SHA-256；拒绝重复成员、跨目录路径和非普通文件/目录成员。
- 内部 `case-report.json` 与外部报告逐字段一致，只排除外部新增的 `archive` 字段。JSON 重复键被拒绝。
- `steps.log` 逐一映射到该 case 的原始归档文件，逐字节核对 SHA-256，并要求成功步骤退出码为 0。
- bundle 每个 CSV 的摘要、真实数据行数、表头、列数、标识唯一性和每表预期数量；全部 VID 和完整边键与 plan 精确对应。不会把多 Tag 行数误当作点数。
- plan 两个 ID CSV 的真实摘要和各 1000 行计数；重算 plan digest，并核对其 `sourceExportManifestSha256` 与 bundle 清单绑定。
- 两侧原始 `records.jsonl` 摘要，每侧真实 1000 点/1000 边、无重复键、全部 OK；清单的 COMPLETE、计数和请求键摘要必须与文件内容一致；两侧计划 ID、计划 digest 和完整键集合必须与 plan 一致。
- 源、目标每条原生字段字符串精确对应，包含负零、浮点位、二进制字符串和地理坐标编码；不会将值规范化。相关 Schema 快照和内部/外部比较报告也须一致。

每条原生字段摘要使用字段名称排序后的紧凑 JSON，键和值均保留为原有字符串；`ensure_ascii=true`、无额外空白，再对 ASCII 字节求 SHA-256。请求键摘要按 Java 协议重算：`nebula-requested-keys-v1\n` 加按字典序排列的每个键及换行。审计不证明采集期间不存在并发写入，也不替代源预制样本 oracle、真实服务端验收或外部不可篡改签名。

[test_archive_audit.py](test_archive_audit.py) 在临时目录中复制一个已通过的小归档，构造 18 个离线探针；源证据和数据库均保持不变：

```bash
python3 task/acceptance/probes/test_archive_audit.py \
  --out task/acceptance/probes/archive-audit-probes-new.json
```

探针包含原样通过、大小/SHA/日志/CSV/清单损坏、少记录、伪造 COMPLETE、计划混用、目标属性变化但仍宣称 MATCH、重复 tar 成员/JSON 键，以及真实 CLI 完整/部分模式的退出码检查。交付运行结果见 [18 个探针结果](archive-audit-probes-final.json)；人工篡改副本的预期拒绝不计作业务迁移成功样本。
