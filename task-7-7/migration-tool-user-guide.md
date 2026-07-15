# Nebula Graph 跨集群全量搬迁工具使用指南

## 1. 文档范围

本文说明当前迁移工具的数据导出、文件交付、目标集群导入、失败恢复和结果校验方式，面向在其他环境进行功能验证的开发和运维人员。

代码根目录为 `/home/sch/nebula/nebula-tools/nebula-migration`。工具版本为 `0.1.0`，使用 Java 8、nebula-java `3.8.4` 和 Shell，服务端兼容性基线是源、目标集群均为 Nebula Graph `3.6`。

本文不涉及当前版本的源端一致性控制设计。该代码仍与一套待重构的旧实现耦合，因此不要将其配置、接口或操作过程作为外部环境的使用契约。完整端到端搬迁验收应在该重构合入后，按本文的数据搬迁流程执行。

## 2. 能力边界

| 范围 | 当前行为 |
| --- | --- |
| 搬迁模式 | 一次性全量搬迁，不支持增量、CDC、双写或跨版本搬迁 |
| 数据 | 按 space、tag、edge 导出点边；支持 `INT64` 和 `FIXED_STRING` VID |
| 元数据 | space、tag、edge、原生 tag/edge index、非 root 用户、非 root 的 space 级角色 |
| 用户处理 | 非 root 用户以默认密码 `nebula` 重建；root 只记录审计信息，不修改目标 root |
| 文件格式 | `csv` 和 `json`；JSON 实际文件扩展名为 `.jsonl` |
| 文件布局 | `label` 和 `partition`；默认 CSV + `label` |
| 完整性 | 每个完成文件生成 `.sha256` 和 `.sig` 侧车文件 |
| 导入 | nGQL `INSERT VERTEX` / `INSERT EDGE` 批量写入，支持重试、失败记录和断点续导 |
| 校验 | 默认数量一致加确定性抽样一致；可手动开启全量 canonical checksum |

以下对象不搬迁：job 运行信息、全文索引、配置参数、快照、日志、物理 storage/raft 状态、root 密码和 root 权限变更。商业版扩展权限无法完整表达时会写入报告警告。

## 3. 外部环境前提

1. 源集群和目标集群均为 Nebula Graph 3.6，且每个集群至少有 3 个 ONLINE Storage 实例。
2. 执行主机安装 JDK 8 和 Maven；可访问源、目标的 graph 服务和 meta 地址。
3. 目标集群仅允许存在 `root` 用户，且不能存在任何业务 space。工具不会自动删除目标对象。
4. 执行账号至少能读取源端元数据、扫描源端 storage、在目标端创建元数据并执行 `INSERT`。
5. 工作目录所在文件系统应具备足够空间保存导出文件、`.sha256`、`.sig`、manifest、checkpoint、报告和日志。导出文件需要作为长期审计交付物保留。
6. 生产前应独立确认全部服务端版本。`precheck` 会验证 graph/meta 的 3.6 版本信息，并检查 Storage host 是否 ONLINE；若开发构建没有嵌入版本字段，`compatibility.allow.unreported.server.version=true` 只能用于已完成独立版本核验的测试环境。

建议先用 2 至 3 个 space 验证以下数据组合：整数和字符串 VID、多 tag、多 edge、rank、NULL、空字符串、转义字符、浮点、date/time/datetime、duration、geography、TTL、原生索引、非 root 用户和 space 级角色。

## 4. 构建与启动

在工具代码目录执行：

```bash
mvn clean package
```

生成的可执行包为：

```text
target/nebula-migration-0.1.0.jar
```

可通过启动脚本或直接调用 JAR：

```bash
bin/nebula-migration <command> -c /path/to/migration.properties

java -jar target/nebula-migration-0.1.0.jar <command> -c /path/to/migration.properties
```

支持的命令如下：

| 命令 | 作用 | 成功后的主要产物 |
| --- | --- | --- |
| `precheck` | 检查版本、连通性、源端后台任务和目标空集群条件 | `reports/precheck-report.json` |
| `export` | 导出元数据与点边文件，并生成完整性侧车文件 | `data/`、`meta/`、`manifest.properties`、`reports/export-report.json` |
| `import` | 先创建元数据，再批量导入数据文件 | `checkpoint.properties`、`reports/import-report.json` |
| `retry-failed` | 重放导入失败队列中的单行 nGQL | `reports/retry-report.json`、`failures/import-retry-history.jsonl` |
| `verify` | 按 artifact 和 partition 校验目标数据 | `reports/verify-report.json`、`reports/verify-details.jsonl` |

## 5. 配置文件

从 `conf/migration.properties.example` 复制一份运行配置。密码以明文保存在 Java `.properties` 文件中，应限制文件权限并避免提交到版本库。

下面的模板只描述数据迁移参数，不包含待重构的源端一致性控制参数。

```properties
# Source cluster
source.graph.addresses=10.10.1.11:9669,10.10.1.12:9669,10.10.1.13:9669
source.meta.addresses=10.10.2.11:9559,10.10.2.12:9559,10.10.2.13:9559
source.user=root
source.password=nebula

# Target cluster
target.graph.addresses=10.20.1.11:9669,10.20.1.12:9669,10.20.1.13:9669
target.meta.addresses=10.20.2.11:9559,10.20.2.12:9559,10.20.2.13:9559
target.user=root
target.password=nebula

# Artifact workspace. A relative path is resolved relative to this properties file.
workspace=/data/nebula-migration/job-20260715-01

# Export
export.format=csv
export.file.layout=label
export.scan.limit=1000
export.partition.concurrency=8
export.label.concurrency=1

# Import
import.batch.size=500
import.artifact.concurrency=8
import.retry.times=3
import.retry.backoff.ms=1000
checkpoint.flush.batch.interval=20
metadata.schema.visibility.timeout.ms=60000

# Verification
verify.sample.modulo=1000
verify.sample.max.records=10000
verify.full.checksum=false

# Development-only exception for a server binary without embedded build version.
compatibility.allow.unreported.server.version=false
```

### 5.1 参数说明

| 参数 | 默认值 | 说明 |
| --- | --- | --- |
| `export.format` | `csv` | `csv` 或 `json`；JSON 使用一行一条记录的 `.jsonl` 文件 |
| `export.file.layout` | `label` | `label`：一个 tag/edge 一个文件；`partition`：一个 tag/edge/partition 一个文件 |
| `export.scan.limit` | `1000` | 单次 Storage scan 请求的分页大小，必须大于 0 |
| `export.partition.concurrency` | `8` | 同一 label 下并发扫描 partition 数，必须大于 0 |
| `export.label.concurrency` | `1` | 并发导出 label 数，必须大于 0 |
| `import.batch.size` | `500` | 一条多值 `INSERT` 包含的记录数，必须大于 0 |
| `import.artifact.concurrency` | `8` | 并发导入 artifact 数，范围 `1-16`，每个 worker 占用一个 graph session |
| `import.retry.times` | `3` | 可重试错误的额外重试次数，可为 0 |
| `import.retry.backoff.ms` | `1000` | 线性退避的基础毫秒数，可为 0 |
| `checkpoint.flush.batch.interval` | `20` | 成功多少个 batch 后刷盘 checkpoint；异常中断后每个活跃 artifact 最多重放 `batch.size * interval` 行 |
| `metadata.schema.visibility.timeout.ms` | `60000` | 等待所有目标 graphd 看见 tag/edge schema 的最大时间 |
| `verify.sample.modulo` | `1000` | 确定性抽样分母，值越小，样本越多 |
| `verify.sample.max.records` | `10000` | 每个 artifact 保留的抽样上限 |
| `verify.full.checksum` | `false` | `true` 时扫描目标全量数据并比较 canonical checksum |

参数只在任务启动时读取，不支持运行中热更新。调优应从减少并发、减小 batch 和 scan limit 开始，再逐步增加。

## 6. 文件布局与交付物

`workspace` 是任务级状态目录，同一时刻只允许一个工具进程持有该目录。目录内的 `.workspace.lock` 会阻止并发命令互相覆盖状态。

```text
<workspace>/
  manifest.properties
  manifest.properties.bak
  checkpoint.properties
  data/
    <space>/vertex/<tag>.csv
    <space>/edge/<edge>.csv
    <space>/vertex/<tag>.part-<partition>.jsonl
    <artifact>.sha256
    <artifact>.sig
  meta/
    metadata.statements
    metadata.statements.sha256
    metadata.statements.sig
    metadata.nql
  reports/
    precheck-report.json
    export-report.json
    import-report.json
    retry-report.json
    verify-report.json
    verify-details.jsonl
  failures/
    import-failures.jsonl
    import-retry-history.jsonl
  logs/
    <command>.log
```

`metadata.statements` 使用每行 Base64 编码的 nGQL，供工具可靠重放；`metadata.nql` 是同一批元数据的可读审计文件。`.sha256` 保存文件摘要；`.sig` 保存文件名、字节数、摘要、恢复统计信息及 HMAC-SHA256 签名。固定内置 HMAC key 仅用于“文件完成标记”和防止意外改写，不应被视为保密加密机制。

完成导出后，建议使用能够保留文件内容和目录层级的方式把整个 workspace 复制到导入主机，例如：

```bash
rsync -a --checksum /data/nebula-migration/job-20260715-01/ \
  migration-host:/data/nebula-migration/job-20260715-01/
```

复制完成后，在目标侧先检查 artifact、`.sha256` 和 `.sig` 是否成对存在。不要编辑已签名的数据文件或元数据文件。

## 7. 标准执行流程

待源端一致性控制重构完成后，外部验证按照以下顺序执行：

```text
precheck -> export -> 审计/传输 workspace -> import -> retry-failed（仅失败时） -> verify
```

示例：

```bash
bin/nebula-migration precheck -c /etc/nebula-migration/job.properties
bin/nebula-migration export -c /etc/nebula-migration/job.properties

# 在目标侧使用同一份 workspace 和目标地址配置
bin/nebula-migration import -c /etc/nebula-migration/job.properties
bin/nebula-migration verify -c /etc/nebula-migration/job.properties
```

执行要点：

1. `precheck` 同时检查源和目标，目标不为空或存在非 root 用户时会失败。
2. `export` 会再次执行源端前置检查，然后导出 metadata、vertex 和 edge。所有 label 均完成后状态进入 `EXPORTED`。
3. `import` 先验证所有 artifact 完整性，再创建 space、schema、index、非 root 用户和角色，然后导入数据。
4. 元数据创建失败时工具退出，不会自动清理目标。清理目标后重新执行 `import`。
5. `verify` 以导出 artifact 为基线扫描目标，不需要再次读取源数据。

## 8. 中断与失败恢复

### 8.1 导出恢复

- `label` 布局中，一个 tag/edge 对应一个已签名的大文件。该文件或签名无效时，重新执行 `export` 会重导整个 label。
- `partition` 布局中，每个已签名的 partition 文件可独立复用；未完成或校验失败的 partition 会重新导出。
- manifest 保存 artifact 行数、canonical digest 以及 label 布局下的 partition 统计。进程在签名之后、manifest 落盘之前中断时，可由签名中的恢复统计重建清单，而不必扫描已经完成的文件。

### 8.2 导入恢复

- 每个 artifact 的 checkpoint 表示连续成功前缀，而不是“最大成功行号”。这可防止失败行被后续成功行掩盖。
- 批量 `INSERT` 失败时，工具会将该 batch 降级为逐行 `INSERT`；最终失败的记录写入 `failures/import-failures.jsonl`。
- 存在未解决失败记录时，`import` 会退出，防止跳过失败行继续前进。
- 修复目标环境后执行：

```bash
bin/nebula-migration retry-failed -c /etc/nebula-migration/job.properties
```

- `retry-failed` 成功后会移除已解决项、保留未解决项并写入重试历史。若全部 artifact 都达到 manifest 中的行数，状态恢复为 `IMPORTED`。

### 8.3 常见退出码

| 退出码 | 含义 | 首要检查位置 |
| --- | --- | --- |
| `2` | 参数或命令使用错误 | properties 文件、命令行 |
| `3` | 前置检查失败 | `reports/precheck-report.json`、命令日志 |
| `4` | 导出失败 | `logs/export.log`、artifact 侧车文件 |
| `5` | 数据导入失败 | `logs/import.log`、`failures/import-failures.jsonl`、checkpoint |
| `6` | 元数据导入失败 | `logs/import.log`、目标集群 DDL 状态 |
| `7` | 校验发现差异 | `reports/verify-report.json`、`reports/verify-details.jsonl` |
| `10` | 内部错误 | 对应命令日志和异常栈 |

## 9. 验收与报告解读

默认校验按每个 artifact 和 partition 输出结果：

1. 导出 manifest 中的行数必须等于目标 Storage scan 行数。
2. 工具从导出文件和目标扫描结果中计算相同规则的确定性样本，并比较 key 到 canonical hash 的映射。
3. `verify.full.checksum=true` 时，额外对每个 partition 和 artifact 比较全量 canonical checksum。

`reports/verify-report.json` 的关键字段：

| 字段 | 含义 |
| --- | --- |
| `status` | `PASS` 或 `FAIL` |
| `artifactsChecked` | 已校验的数据 artifact 数 |
| `ttlDetected` | 源 schema 是否发现启用的 TTL |
| `fullChecksumEnabled` | 本次是否启用全量 checksum |
| `fullConsistencyProven` | 仅在全量 checksum 已启用且全部通过时为 `true` |
| `fullChecksum` | 未启用时明确写为“未证明全量完全一致” |

`reports/verify-details.jsonl` 每行是一项 partition 或 artifact 结果，包含 `space`、`kind`、`label`、`partition`、期望数量、实际数量、`countMatch` 和 `valueMatch`。验收归档至少保留 workspace、运行配置副本、命令日志和全部 report。

TTL 被发现时，导出继续执行，并在 manifest 与报告中标记。TTL 可能使数据在导出和校验之间自然老化，因此报告中的该字段必须与业务验收记录一并审阅。

## 10. 外部验证建议

建议将验证分为三层：

| 层级 | 数据规模 | 重点 |
| --- | --- | --- |
| 单元构建 | 无集群 | `mvn clean package`，验证 codec、完整性、manifest、nGQL 渲染等单元测试 |
| 功能冒烟 | 2-3 space、少量数据 | CSV/label、JSON/partition、两类 VID、元数据、失败恢复、默认与全量校验 |
| 压力验证 | 约 5,000 万点边 | 3 个 space、99 partitions、3 Storage 集群拓扑、并发、资源和总耗时 |

仓库中已有 `tests/e2e/run-local-migration-e2e.sh` 和 `tests/perf/run-local-50m-benchmark.sh`。前者覆盖 CSV/label、JSON/partition、数据类型、完整性损坏、断点续导、失败重试和全量 checksum；后者默认构造 49,999,998 条点边记录。两者目前含有待替换的源端一致性控制测试夹具，重构完成前可用于了解数据集、预期报告和验收断言，但不应原样作为其他环境的正式执行脚本。
