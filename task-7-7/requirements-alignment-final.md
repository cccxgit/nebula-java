# Nebula Graph 跨集群搬迁需求对齐完成记录

## 1. 对齐状态

当前需求讨论已无明确阻塞 GAP，可以进入正式设计方案定稿阶段。

## 2. 核心边界

1. 本需求仅考虑一次全量跨集群搬迁。
2. 源集群和目标集群均为 Nebula Graph 3.8。
3. 不支持跨版本迁移。
4. 技术选型为 Java 和 Shell。
5. 第三方件限定为 nebula-java。
6. 不使用 Spark、Flink、Nebula Importer 等其他组件。
7. 导出通过 nebula-java `StorageClient.scanVertex/scanEdge` 直连 storage。
8. 导入通过 graph service 执行拼接后的 `INSERT VERTEX/EDGE`。

## 3. 一致性与冻结

1. 需求测试阶段验证“目标集群与当前源集群一致”。
2. 测试阶段导出后源集群继续保持数据禁止状态，覆盖导出、导入、校验全过程。
3. 实际生产阶段验证“目标集群与导出快照一致”。
4. 数据禁止功能由人工通过 graph 侧 gflags 控制。
5. 冻结命令返回成功时，保证所有 graphd 和 storaged 都已经进入只读状态。
6. 已经进入 graphd/storaged 的写请求等待完成。
7. 涉及后台数据变更的任务需要禁止或确认无运行中任务。

## 4. 搬迁范围

需要搬迁：

1. Space 定义。
2. Tag schema。
3. Edge schema。
4. 原生 Tag Index / Edge Index。
5. Vertex 数据。
6. Edge 数据。
7. 非 root 用户。
8. 非 root 用户 space 级角色授权。
9. root 用户信息进入报告，但导入时不修改目标 root。

不搬迁：

1. job 运行信息。
2. 全文索引。
3. 配置参数。
4. 快照文件。
5. 日志文件。
6. storage host 地址、leader、raft membership 等物理运行状态。
7. root 密码、root 权限变更。
8. 商业版扩展权限无法完整导出时不强保证迁移，告警并写入报告。

## 5. 目标集群要求

1. 目标集群初始状态要求仅存在 `root` 用户。
2. 如果目标集群存在业务 space，则 precheck 失败。
3. 如果目标集群存在除 `root` 外的用户，则 precheck 失败。
4. schema/index 创建失败后，导入脚本退出。
5. 目标集群清理由用户手动执行，工具不自动 `DROP SPACE` 或 `DROP USER`。

## 6. 用户与权限

1. 密码无需导出。
2. 非 root 用户导入后默认密码为 `nebula`。
3. root 导出到报告。
4. 导入时不创建 root、不修改 root 密码、不修改 root 权限。
5. 仅强保证非 root 用户 space 级角色授权。
6. 其他商业版扩展权限告警并写入报告。

## 7. 文件格式

1. 默认导出格式为 CSV。
2. 同时支持 JSON。
3. CSV 使用 RFC4180 风格转义。
4. CSV 中 `NULL` 使用 `\N` 表示。
5. CSV 中空字符串使用 `""` 表示。
6. JSON 使用 typed JSON，字段显式保存 `type/value`。
7. 校验按 Nebula typed value 语义一致，不要求 CSV/JSON 文本字节级一致。
8. 全量 checksum 使用工具内部 canonical 编码计算。

## 8. 文件布局

本期开发支持：

1. `label` 布局。
2. `partition` 布局。

本期只输出设计文档，不开发实现：

1. `segment` 布局。

默认值：

```text
export.file.layout = label
```

`label` 布局：

1. 一个 tag/edge 一个大文件。
2. 多 partition 并发 scan。
3. 单 writer 串行写入同一个 tag/edge 文件。
4. 文件行顺序不作为一致性标准。
5. checkpoint 粒度为 tag/edge 文件级。
6. 无签名时整体重导。

`partition` 布局：

1. 每个 `space + tag/edge + partition` 一个文件。
2. 每个 partition 文件独立生成 `.sha256` 和 `.sig`。
3. 恢复时已签名 partition 文件跳过。
4. 未签名 partition 文件整体重导。

## 9. checksum 与签名

1. checksum 使用 `SHA-256`。
2. 签名使用 `HMAC-SHA256`。
3. HMAC 使用固定内置默认密钥。
4. 用户无需提供签名密钥。
5. 默认 HMAC 不作为安全防篡改能力。
6. 默认 HMAC 主要用于标识文件导出完成，防止误用未完成文件。
7. 文件完整性校验以 `SHA-256` checksum 为主。
8. 只有带签名的正式文件可在恢复时跳过。

## 10. TTL 处理

1. `StorageClient.scanVertex/scanEdge` 对应的 storage scan 路径会过滤 TTL 已过期数据。
2. TTL 不是仅在 auto compaction 阶段生效。
3. 读路径会判断 TTL，过期数据对查询和 scan 不可见。
4. compaction filter 负责在 compaction 阶段物理清理过期数据。
5. 导出阶段如果识别到 schema 存在 TTL，则使用 log4j 打印告警日志。
6. 存在 TTL 不阻断导出。
7. TTL 标记写入 `manifest.json` 和导出报告。
8. 测试和生产阶段的校验策略不因 TTL 存在而变化。

## 11. 导入与重试

1. 导入通过 graph service 执行 `INSERT VERTEX/EDGE`。
2. 导入批量失败后降级到单行重试。
3. 单行仍失败后写入失败记录文件。
4. 重复导入同一份导出文件时，允许同 key 同值覆盖。
5. 依赖目标空集群规避同 key 不同值冲突。
6. 导入中断恢复时，已成功写入的数据再次执行 `INSERT` 可以接受。

## 12. 默认参数

```text
import.batch.vertices = 500
import.batch.edges = 500
import.retry.max-attempts = 3
import.retry.backoff.ms = 1000
export.scan.limit = 1000
export.concurrent.partitions = 8
export.concurrent.labels = 1
```

## 13. 配置与日志

1. 主任务配置使用 Java `.properties`。
2. 日志采用 log4j。
3. log4j 使用 properties 风格配置。

## 14. 校验策略

1. 默认成功条件为数量一致和抽样一致通过。
2. 默认不开启全量 checksum。
3. 全量 checksum 提供手动开启能力。
4. 未开启全量 checksum 时，报告中明确标记“未证明全量完全一致”。
5. 校验报告需要按 space、tag、edge、partition 输出。

## 15. 输出文件

建议固定输出：

```text
manifest.json
export-report.json
import-report.json
verify-report.json
```

## 16. 下一步

下一步进入正式设计方案定稿，设计文档应覆盖：

1. 需求边界与非目标。
2. 总体架构。
3. 数据静止和迁移流程。
4. meta/schema/user/role/permission 搬迁。
5. vertex/edge 导出。
6. CSV/typed JSON 编码。
7. label/partition 文件布局。
8. checksum/signature/manifest/report。
9. 导入和失败重试。
10. 校验策略。
11. TTL 风险处理。
12. 断点续导/续导入。
13. 参数设计。
14. 测试方案。
