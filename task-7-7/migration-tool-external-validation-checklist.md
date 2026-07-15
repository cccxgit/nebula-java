# Nebula Graph 搬迁工具外部环境验证清单

## 1. 使用方式

本清单用于其他环境的功能验收和证据归档。它配合 [使用指南](migration-tool-user-guide.md) 和 [代码详细解读](migration-tool-code-walkthrough.md) 使用。

本文不覆盖当前待重构的源端一致性控制模块。该模块完成前，可以执行构建和代码级检查，但不应将完整端到端搬迁结果作为正式验收结论。

## 2. 环境确认

| 检查项 | 通过标准 | 证据 |
| --- | --- | --- |
| 服务端版本 | 源、目标均为 Nebula Graph 3.6 | `SHOW HOSTS GRAPH`、`SHOW HOSTS META` 输出 |
| Storage 拓扑 | 源、目标各至少 3 个 `ONLINE` Storage | `SHOW HOSTS STORAGE` 输出 |
| 目标初始状态 | 无业务 space，仅有 root 用户 | `SHOW SPACES`、`SHOW USERS` 输出 |
| 工具运行环境 | JDK 8、Maven、网络路由和 DNS/IP 可达 | `java -version`、`mvn -version`、连通性记录 |
| 磁盘空间 | workspace 有足够空间存放导出物与日志 | `df -h` 和容量估算 |
| 账号权限 | 能读取源元数据与 storage，能在目标创建对象和写入 | 预检查输出或最小权限冒烟 |

## 3. 构建验证

在 `/home/sch/nebula/nebula-tools/nebula-migration` 执行：

```bash
mvn clean package
```

验收项：

- 命令退出码为 `0`。
- `target/nebula-migration-0.1.0.jar` 存在。
- 单元测试全部通过。
- 保存 Maven 控制台输出和构建产物的 SHA-256。

## 4. 最小功能数据集

建议创建至少两个 space：一个 `FIXED_STRING` VID，一个 `INT64` VID。每个数据集应至少覆盖下列项目。

| 类别 | 需要覆盖的值或对象 |
| --- | --- |
| Schema | 多 tag、多 edge、空属性 tag/edge、tag index、edge index |
| Vertex/edge identity | 字符串和整数 VID、edge src/dst/rank、同 src/dst 不同 rank |
| 标量 | NULL、空字符串、布尔、正负整数、浮点、date、time、datetime、duration、geography |
| 文本 | 逗号、双引号、反斜杠、换行和非 ASCII 字符 |
| 元数据 | 非 root 用户、space 级角色授权、root 审计信息 |
| TTL | 至少一个启用 TTL 的 schema，并记录报告的 `ttlDetected` 字段 |

目标集群必须保持空白，不能预建同名 schema 或用户。

## 5. 运行流程验收

源端一致性控制重构完成后，按下表依次执行。每一步成功后归档 workspace，不要只保留控制台输出。

| 步骤 | 命令 | 通过标准 | 归档文件 |
| --- | --- | --- | --- |
| 前置检查 | `precheck -c <properties>` | 退出码 0，源/目标检查通过 | `reports/precheck-report.json`、日志 |
| 导出 | `export -c <properties>` | manifest 状态为 `EXPORTED`，所有 artifact 有 `.sha256` 与 `.sig` | `data/`、`meta/`、`manifest.properties`、`reports/export-report.json` |
| 传输 | 复制完整 workspace | 文件数量、大小、SHA-256 与源端一致 | 传输日志、目标侧目录清单 |
| 导入 | `import -c <properties>` | manifest 状态为 `IMPORTED`，无未解决失败队列 | `checkpoint.properties`、`reports/import-report.json`、日志 |
| 校验 | `verify -c <properties>` | `status=PASS` | `reports/verify-report.json`、`reports/verify-details.jsonl` |
| 增强校验 | `verify.full.checksum=true` 后再次 `verify` | `fullConsistencyProven=true` | 增强校验报告 |

## 6. 格式和布局矩阵

至少执行下面两组，覆盖四项核心可选能力中的两条有效组合。

| 组 | `export.format` | `export.file.layout` | 校验模式 |
| --- | --- | --- | --- |
| A | `csv` | `label` | 默认数量 + 抽样，随后全量 checksum |
| B | `json` | `partition` | 全量 checksum |

对每一组检查：

- CSV 的首行严格为 `kind,key1,key2,rank,properties`。
- JSON 文件为一行一条记录的 `.jsonl`。
- `label` 布局下每个 tag/edge 只有一个完成数据文件。
- `partition` 布局下每个 tag/edge/partition 独立完成。
- 每个完成文件、metadata artifact 都同时存在 `.sha256` 和 `.sig`。

## 7. 恢复与负向测试

| 场景 | 操作 | 预期结果 |
| --- | --- | --- |
| 缺失签名 | 删除一个 artifact 的 `.sig` 后执行 `import` | 导入失败，提示 artifact 未完成；重新 `export` 后恢复 |
| 文件损坏 | 修改完成 artifact 内容后执行 `import` | 导入失败，提示 SHA-256 或签名不匹配 |
| 目标不为空 | 预建一个业务 space 后执行 `import` | 导入失败，工具不自动删除目标对象 |
| 导入中断 | checkpoint 已落盘后终止进程，再次执行 `import` | 从连续 checkpoint 恢复，允许重放已提交窗口 |
| 行级失败 | 制造无法写入的记录 | `import-failures.jsonl` 有记录，`import` 退出；修复后 `retry-failed` 可清空队列 |
| label 恢复 | 破坏 label artifact 后重新 export | 该 label 整体重导 |
| partition 恢复 | 破坏一个 partition artifact 后重新 export | 仅该 partition 重导，其他签名文件复用 |

## 8. 性能验证

性能验证采用 3 space、99 partitions 的数据集。现有本机基准的规模是 49,999,998 条点边，使用 CSV + partition 布局、导出 scan limit 10,000、导入 8 worker、batch 500。该数值是已有环境的结果，不应直接当作其他硬件上的性能承诺。

外部环境应至少记录：

| 指标 | 记录内容 |
| --- | --- |
| 数据规模 | space、tag/edge、partition、vertex、edge、总记录数 |
| 拓扑 | Meta/Storage/Graph 数量、replica factor、主机规格 |
| 配置 | scan limit、label/partition 并发、batch、导入 artifact 并发、checkpoint 间隔 |
| 时延 | precheck、export、传输、import、verify 的开始/结束时间 |
| 资源 | CPU、内存、磁盘空间、网络流量、Storage/Graph 日志错误 |
| 结果 | 每个 report 的 status、TTL 标记、全量 checksum 是否启用 |

初次压测应采用保守参数：`export.partition.concurrency=2`、`export.label.concurrency=1`、`import.artifact.concurrency=2`、`import.batch.size=100`。确认没有 OOM、超时和 Storage 错误后，再逐步提高单个维度。

## 9. 最终交付证据

每次外部环境验收应交付一个不可修改的任务归档，至少包含：

```text
<job-id>/
  migration.properties.redacted
  manifest.properties
  data/ and meta/ artifacts with .sha256 and .sig
  reports/
  logs/
  source-cluster-inventory.txt
  target-cluster-inventory.txt
  command-timeline.txt
  resource-observations.txt
  issue-list.md
```

配置归档必须脱敏密码。若默认校验通过但未启用全量 checksum，验收结论必须明确写为“数量和抽样一致通过，未证明全量完全一致”。
