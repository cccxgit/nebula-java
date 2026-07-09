# 2026-07-08 需求答复与方案收敛记录

## 1. 本轮已确认内容

### 1.1 一致性验收对象

需求测试阶段：

1. 验证“目标集群与当前源集群一致”。
2. 导出完成后，源集群仍保持数据禁止状态。

实际生产使用阶段：

1. 验证“目标集群与导出快照一致”。
2. 数据禁止功能由人工通过 gflags 参数控制。

方案影响：

1. 测试阶段需要冻结覆盖导出、导入、校验全过程。
2. 生产阶段可以将导出完成时刻作为快照边界，后续校验目标集群与导出文件一致。
3. 设计文档中需要明确区分“源集群当前状态一致”和“导出快照一致”两个验收语义。

### 1.2 数据静止范围

根据官方文档，涉及后台数据变更的操作需要禁止。

迁移期间建议禁止或确认无运行中的操作：

1. DML：`INSERT`、`UPDATE`、`UPSERT`、`DELETE`。
2. DDL：`CREATE/ALTER/DROP SPACE`、`CREATE/ALTER/DROP TAG`、`CREATE/ALTER/DROP EDGE`、`CREATE/DROP INDEX`。
3. 后台任务：`BALANCE`、`BALANCE LEADER`、`COMPACT`、`DOWNLOAD/INGEST`、`REBUILD INDEX`。
4. TTL 相关风险：如果测试验证“目标与当前源一致”，需要避免 TTL 数据在导出、导入、校验期间过期。

参考文档：

1. 作业管理：https://docs.nebula-graph.com.cn/3.6.0/3.ngql-guide/4.job-statements/
2. Storage 负载均衡：https://docs.nebula-graph.com.cn/3.6.0/8.service-tuning/load-balance/
3. Compaction：https://docs.nebula-graph.com.cn/3.6.0/8.service-tuning/compaction/
4. REBUILD INDEX：https://docs.nebula-graph.com.cn/3.6.0/3.ngql-guide/14.native-index-statements/4.rebuild-native-index/
5. TTL：https://docs.nebula-graph.com.cn/3.6.0/3.ngql-guide/8.clauses-and-options/ttl-options/

### 1.3 in-flight 写入处理

已确认：

1. 已经进入 graphd 的写请求等待完成。
2. 已经进入 storaged 的写请求等待完成。
3. 冻结命令返回成功时，保证所有 graphd 和 storaged 都已经进入只读状态。

方案影响：

1. 冻结命令需要具备集群级 barrier 语义。
2. 冻结命令返回成功前，需要等待已受理写请求完成。
3. 冻结成功后，新写请求应被拒绝或返回明确错误。

### 1.4 冻结状态控制方式

已确认：

1. 方案上通过 graph 侧 gflags 参数控制冻结。
2. 不考虑水平扩容场景。

方案影响：

1. 冻结控制不需要设计复杂的动态服务发现和新增实例同步。
2. 设计中仍需说明 graph 侧 gflags 如何覆盖所有 graphd 实例。
3. 需要定义迁移前检查项：确认所有 graphd 已开启冻结，且无后台变更任务运行中。

### 1.5 目标集群初始状态

已确认：

1. 目标集群若存在图空间，则停止导入并报错。
2. 目标集群若存在用户，则停止导入并报错。

方案影响：

1. 目标集群默认要求为空逻辑集群。
2. 导入前需要执行 precheck：
   - `SHOW SPACES` 结果为空。
   - `SHOW USERS` 不存在除内置默认用户外的业务用户，或按约定判断为空。
3. 如果 precheck 失败，迁移任务直接进入 failed 状态，不执行 schema 和数据导入。

### 1.6 密码搬迁

已确认：

1. 密码无需导出。
2. 目标用户使用默认密码 `nebula`。

方案影响：

1. 不需要导出源集群密码 hash。
2. 导出文件无需因为密码 hash 强制加密。
3. 用户导入时统一创建默认密码。
4. 迁移报告中需要提示：用户密码已重置为默认值，需要上线后人工修改。

### 1.7 索引导入顺序

已确认：

1. 采用先建 index 再导入数据。

方案影响：

1. 目标集群 schema 阶段创建 tag/edge 后创建原生 index。
2. 后续数据导入时由 Nebula 正常维护索引。
3. 导入性能会低于“先导数据后 rebuild index”的方案，但导入完成后索引可直接使用。
4. 设计中需要把这一点作为性能 tradeoff 说明。

### 1.8 默认验收标准

已确认：

1. 默认成功条件是数量一致和抽样一致通过。
2. 默认不开启全量 checksum。
3. 未开启全量 checksum 时，允许报告中明确标记“未证明全量完全一致”。

方案影响：

1. 默认迁移成功不等价于数学意义上的全量一致证明。
2. 报告需要区分：
   - 默认校验通过。
   - 全量 checksum 未开启。
   - 未证明全量完全一致。
3. 全量 checksum 作为手动开启的增强校验。

### 1.9 参数热生效

已确认：

1. 方案变更，无需支持参数热生效。

方案影响：

1. 导出并发、导入并发、scan batch size、insert batch size、重试参数等可以在任务启动时读取。
2. 运行中参数变更不生效。
3. 如果需要调整参数，需要停止任务后恢复执行。

### 1.10 中间文件压缩、加密、签名

已确认：

1. CSV/JSON 无需压缩。
2. CSV/JSON 无需加密。
3. 单文件导出完成后需要单独生成 checksum 和签名。
4. 含签名的文件代表该文件已经导出完成。
5. 中断后重新导出时，含签名的文件无需重新导出。

方案影响：

1. 文件写入需要使用临时文件，完成后再生成 checksum 和签名。
2. 恢复执行时，以签名文件和 manifest 状态作为跳过依据。
3. 没有签名的文件不能作为完整文件使用。

### 1.11 测试数据集

已确认：

1. 使用开源数据集。
2. 优选覆盖多 space、多 tag、多 edge、索引、TTL、权限、字符串 VID、整数 VID、数据类型种类多的数据集。
3. 如果开源数据集不具备这些能力，则构造数据集和开发测试用例验证。

方案影响：

1. 测试数据准备需要分成两类：
   - 大体量开源数据集，用于性能和稳定性验证。
   - 构造数据集，用于功能覆盖和边界类型验证。
2. 构造数据集必须覆盖 VID 类型、TTL、权限、索引、多 schema、多数据类型。

## 2. 第 9 条问题解释

原问题：

> 对浮点、时间、NULL、空字符串、复杂转义字符等类型是否要求字节级一致？

该问题不是问“导出的 CSV/JSON 文件内容是否逐字节相同”，而是问校验时采用哪种一致性标准。

### 2.1 两种一致性口径

#### 口径一：文本字节级一致

要求导出文件中的文本完全一致。

例如：

1. `1.0` 和 `1.000000` 认为不一致。
2. `"a\nb"` 和实际换行表示可能认为不一致。
3. `NULL`、空字符串、缺省字段如果文本不同，可能认为不一致。

该口径不建议作为默认验收标准。

#### 口径二：Nebula typed value 语义一致

要求源和目标中的 Nebula 类型值一致，而不是要求 CSV/JSON 文本表现形式一致。

例如：

1. 同一个 `double` 值使用不同文本格式导出，只要写回后 Nebula typed value 一致，即认为一致。
2. 字符串转义只要还原后的字符串一致，即认为一致。
3. `NULL` 和空字符串必须区分，因为它们是不同语义。
4. `date/time/datetime/timestamp` 需要按 Nebula 的时间语义和时区配置处理。

推荐采用该口径。

### 2.2 建议结论

建议定义为：

1. 不要求 CSV/JSON 文本字节级一致。
2. 要求 Nebula typed value 语义一致。
3. 全量 checksum 使用工具内部 canonical 编码计算。
4. canonical 编码应稳定区分：
   - `NULL`
   - 空字符串
   - 普通字符串
   - 转义字符
   - 整数
   - 浮点
   - 布尔
   - 日期时间
   - VID 类型

参考文档：

1. 数值：https://docs.nebula-graph.com.cn/3.6.0/3.ngql-guide/3.data-types/1.numeric/
2. 字符串：https://docs.nebula-graph.com.cn/3.6.0/3.ngql-guide/3.data-types/3.string/
3. 日期时间：https://docs.nebula-graph.com.cn/3.6.0/3.ngql-guide/3.data-types/4.date-and-time/
4. NULL：https://docs.nebula-graph.com.cn/3.6.0/3.ngql-guide/3.data-types/5.null/

## 3. 导出文件切分策略讨论

用户提出的问题：

> 数据导出策略是一个 tag/edge 一个文件，还是一个 tag/edge 按分片划分文件？

### 3.1 候选方案一：一个 tag/edge 一个文件

示例：

```text
spaces/<space>/vertices/<tag>.csv
spaces/<space>/edges/<edge>.csv
```

优点：

1. 文件数量少。
2. 人工查看简单。
3. 下游导入顺序简单。

缺点：

1. 20 亿点边规模下，单文件可能非常大。
2. 中断后恢复粒度过粗。
3. 单文件签名粒度过粗，文件未完成时无法复用已写入部分。
4. 不利于按 partition 并行导出和导入。
5. 不利于按 `space/tag/edge/partition` 输出校验报告。
6. 与 `StorageClient scanVertex/scanEdge` 的 partition cursor 模型不匹配。

结论：不建议作为生产默认方案。

### 3.2 候选方案二：一个 tag/edge 按 partition 切分文件

示例：

```text
spaces/<space>/vertices/<tag>/part-000001.csv
spaces/<space>/edges/<edge>/part-000001.csv
```

优点：

1. 与 Nebula partition scan 模型匹配。
2. 断点续导粒度合理。
3. 支持按 partition 并发导出。
4. 支持按 partition 输出计数、checksum 和抽样报告。
5. 单个 partition 文件失败时，只需重导该 partition。

缺点：

1. 文件数量比 tag/edge 单文件多。
2. 如果某个 partition 数据极大，仍可能产生大文件。

结论：比 tag/edge 单文件更适合生产迁移。

### 3.3 推荐方案：tag/edge + partition + segment

建议采用：

```text
一个 space + 一个 tag/edge + 一个 partition + 可选 segment 一个文件
```

示例目录：

```text
export-root/
  manifest.json
  spaces/<space>/vertices/<tag>/part-000001/segment-000000.csv
  spaces/<space>/vertices/<tag>/part-000001/segment-000000.sha256
  spaces/<space>/vertices/<tag>/part-000001/segment-000000.sig
  spaces/<space>/edges/<edge>/part-000001/segment-000000.csv
  spaces/<space>/edges/<edge>/part-000001/segment-000000.sha256
  spaces/<space>/edges/<edge>/part-000001/segment-000000.sig
```

推荐原因：

1. `StorageClient scanVertex/scanEdge` 本身围绕 partition cursor 推进。
2. partition 是天然并行调度单元。
3. checkpoint 可以记录到 `space + tag/edge + partition + cursor + segment`。
4. 单文件签名粒度更细，中断后可跳过已完成文件。
5. 单个大 partition 可以继续按 segment 滚动，避免超大文件。
6. 导入阶段可以按 segment 作为任务单元，失败重试粒度更小。
7. 校验阶段可以自然汇总：
   - segment 级计数/checksum。
   - partition 级计数/checksum。
   - tag/edge 级计数/checksum。
   - space 级计数/checksum。

### 3.4 文件完成语义

建议定义如下：

1. 写入时使用临时文件：`segment-000000.csv.tmp`。
2. 文件写入完成后 close/fsync。
3. 生成 `segment-000000.sha256`。
4. 生成 `segment-000000.sig`。
5. 原子 rename 为正式文件：`segment-000000.csv`。
6. manifest 记录该 segment 状态：
   - `space`
   - `type`：vertex 或 edge
   - `label`
   - `partition`
   - `segment`
   - `format`
   - `row_count`
   - `byte_size`
   - `sha256`
   - `signature`
   - `scan_cursor_start`
   - `scan_cursor_end`
   - `status`
7. 恢复执行时：
   - 有正式数据文件、checksum 文件、签名文件，且 manifest 状态为完成：跳过。
   - 只有 `.tmp` 文件：删除并从 checkpoint 继续。
   - 有数据文件但无签名：视为未完成，删除或隔离后重导。

### 3.5 文件命名建议

推荐：

```text
spaces/<space>/vertices/<tag>/part-<part-id>/segment-<segment-id>.<format>
spaces/<space>/edges/<edge>/part-<part-id>/segment-<segment-id>.<format>
```

示例：

```text
spaces/basketballplayer/vertices/player/part-000001/segment-000000.csv
spaces/basketballplayer/edges/follow/part-000001/segment-000000.csv
```

如果需要更强可读性，也可以在文件名中追加计数：

```text
segment-000000.rows-1000000.csv
```

但不建议把 checksum 放进文件名，避免重命名和 manifest 更新复杂化。

## 4. TTL 风险说明

TTL 是当前方案中的一个特别风险点。

原因：

1. 即使没有业务写入，TTL 语义也可能使数据在查询时过期。
2. 官方文档说明，属性过期后，对应数据仍存储在硬盘上，但查询时会过滤过期数据。
3. Compaction 过程中会回收 TTL 过期数据。

方案建议：

1. 测试阶段如果验证“目标与当前源集群一致”，TTL 测试数据的过期时间必须覆盖完整的导出、导入、校验周期。
2. 迁移期间禁止手动 `SUBMIT JOB COMPACT`。
3. 如果源集群存在接近过期的 TTL 数据，迁移报告需要标记风险。
4. 全量 checksum 如果通过 storage scan 读取到底层已过期但未 compact 的数据，需要确认 scan 是否遵守 TTL 过滤语义。该点需要后续技术验证。

## 5. 当前仍需确认的问题

### 5.1 第 9 条确认

请确认是否接受：

1. 不要求 CSV/JSON 文本字节级一致。
2. 要求 Nebula typed value 语义一致。
3. 全量 checksum 使用工具内部 canonical 编码计算。

### 5.2 导出文件粒度确认

请确认是否接受推荐方案：

```text
space + tag/edge + partition + segment
```

即：

1. 不采用一个 tag/edge 一个大文件作为默认方案。
2. 每个 tag/edge 按 partition 独立导出。
3. 每个 partition 支持按最大行数或最大文件大小滚动 segment。
4. 每个 segment 独立生成 checksum 和签名。

### 5.3 segment 滚动阈值

需要后续确定默认阈值。

候选：

1. 按行数滚动：例如 100 万行一个 segment。
2. 按文件大小滚动：例如 512MB 或 1GB 一个 segment。
3. 同时启用，先达到任一阈值就滚动。

建议默认同时启用。

### 5.4 签名算法

需要后续确定签名算法。

候选：

1. HMAC-SHA256：实现简单，需要共享密钥。
2. RSA/ECDSA：可验证签名来源，需要私钥和公钥管理。

如果只是声明“该文件已经导出完成且未损坏”，`sha256 + HMAC-SHA256` 已足够。

如果需要证明“由可信迁移工具签发”，建议使用非对称签名。

### 5.5 Storage scan 与 TTL 语义

需要通过代码或测试确认：

1. `StorageClient.scanVertex/scanEdge` 是否返回 TTL 已过期但尚未 compaction 清理的数据。
2. 如果 scan 返回已过期数据，而 graph 查询过滤已过期数据，迁移会产生源/目标语义差异。
3. 如果存在差异，需要在导出阶段按 TTL schema 主动过滤过期数据，或在需求中声明不支持迁移期间接近过期的 TTL 数据。

## 6. 暂定设计倾向

在进入正式方案设计前，当前建议采用以下方向：

1. 冻结通过 graph 侧 gflags 手动控制。
2. 测试阶段冻结覆盖导出、导入、校验全过程。
3. 生产阶段以导出完成时刻作为快照边界。
4. 目标集群要求为空逻辑集群，否则 precheck 失败。
5. 密码不搬迁，目标用户密码统一为 `nebula`。
6. 先创建 schema 和 index，再导入数据。
7. 默认校验为数量一致和抽样一致。
8. 全量 checksum 手动开启。
9. 文件格式默认 CSV，同时支持 JSON。
10. 文件粒度采用 `space + tag/edge + partition + segment`。
11. 每个 segment 独立 checksum 和签名。
12. 未开启全量 checksum 时，报告明确标记“未证明全量完全一致”。
