# 2026-07-08 第二轮需求确认与 TTL 源码分析

## 1. 本轮新增确认

### 1.1 特殊数据类型一致性

第 9 条按照前述建议执行：

1. 不要求 CSV/JSON 文本字节级一致。
2. 要求 Nebula typed value 语义一致。
3. 全量 checksum 使用工具内部 canonical 编码计算。
4. canonical 编码需要稳定区分：
   - `NULL`
   - 空字符串
   - 普通字符串
   - 转义字符
   - 整数
   - 浮点
   - 布尔
   - 日期时间
   - VID 类型

### 1.2 导出文件布局

导出任务启动前通过参数控制文件布局，支持三种方案：

1. 一个 tag/edge 一个文件。
2. 一个 tag/edge 按 partition 切分文件。
3. 一个 tag/edge 按 partition + segment 切分文件。

建议参数：

```text
export.file.layout = label | partition | segment
```

含义：

| 参数值 | 文件布局 | 适用场景 |
| --- | --- | --- |
| `label` | 一个 tag/edge 一个文件 | 小规模数据、人工审计优先 |
| `partition` | 一个 tag/edge 按 partition 一个文件 | 中等规模、需要并行和断点续导 |
| `segment` | 一个 tag/edge 按 partition + segment 多文件 | 大规模生产迁移，推荐默认 |

建议默认值：

```text
export.file.layout = segment
```

## 2. 三种文件布局的恢复语义

### 2.1 label 布局

示例：

```text
spaces/<space>/vertices/<tag>.csv
spaces/<space>/edges/<edge>.csv
```

恢复语义：

1. 如果正式文件、checksum、签名均存在，则跳过整个 tag/edge。
2. 如果没有签名，则认为该 tag/edge 文件未完成，需要整体重新导出。
3. 该模式下断点粒度最粗，不适合超大 tag/edge。

### 2.2 partition 布局

示例：

```text
spaces/<space>/vertices/<tag>/part-000001.csv
spaces/<space>/edges/<edge>/part-000001.csv
```

恢复语义：

1. 如果某个 partition 文件、checksum、签名均存在，则跳过该 partition。
2. 如果某个 partition 没有签名，则重新导出该 partition。
3. 该模式和 `StorageClient.scanVertex/scanEdge` 的 partition cursor 模型匹配。

### 2.3 segment 布局

示例：

```text
spaces/<space>/vertices/<tag>/part-000001/segment-000000.csv
spaces/<space>/edges/<edge>/part-000001/segment-000000.csv
```

恢复语义：

1. 如果某个 segment 文件、checksum、签名均存在，则跳过该 segment。
2. 如果某个 segment 没有签名，则重新导出该 segment 或从最近 checkpoint 继续。
3. 该模式适合 5 亿到 20 亿点边规模的生产迁移。

## 3. 文件完成语义

不论选择哪种布局，文件完成状态统一按以下规则定义：

1. 数据写入时使用 `.tmp` 临时文件。
2. 数据文件写完后 close/fsync。
3. 生成 `.sha256`。
4. 生成 `.sig`。
5. 原子 rename 为正式数据文件。
6. manifest 记录文件状态、行数、字节数、checksum、签名、起止 cursor。
7. 恢复时只有带签名的正式文件可以跳过。
8. 无签名文件视为未完成，删除或隔离后重导。

## 4. TTL 源码分析结论

用户问题：

> TTL 老化是在 Nebula Graph 仅在执行 auto compaction 阶段触发吗？

结论：

**不是。TTL 在 Nebula Graph 3.6 源码中分成两个层面：**

1. **读路径逻辑过期判断**：查询、scan、索引扫描等读取路径会判断 TTL，过期数据对查询结果不可见。
2. **Compaction 物理清理**：RocksDB compaction filter 在 compaction 阶段把过期数据从底层存储中清理掉。

因此，TTL 不是仅在 auto compaction 阶段才生效。auto/manual/full/periodic compaction 主要影响的是过期数据何时从 RocksDB 中被物理删除。

## 5. TTL 判断逻辑

源码位置：

```text
/home/sch/nebula/nebula-3.6-io/src/storage/CommonUtils.cpp
```

关键逻辑：

1. `CommonUtils::ttlProps()` 从 schema 中读取 `ttl_duration` 和 `ttl_col`。
2. 当 `ttl_duration <= 0` 或 `ttl_col` 为空时，不启用 TTL。
3. `CommonUtils::checkDataExpiredForTTL()` 读取 TTL 字段值。
4. TTL 字段类型只支持 `TIMESTAMP` 和 `INT64`。
5. 当前时间来自 `std::time(nullptr)`，如果 `FLAGS_ttl_use_ms=true`，则使用毫秒时间戳。
6. 如果 TTL 字段值不是整数类型，例如 `NULL`，则永不过期。
7. 判断条件是：

```text
now > ttl_value + ttl_duration
```

源码证据：

```text
/home/sch/nebula/nebula-3.6-io/src/storage/CommonUtils.cpp:17
/home/sch/nebula/nebula-3.6-io/src/storage/CommonUtils.cpp:29
/home/sch/nebula/nebula-3.6-io/src/storage/CommonUtils.cpp:50
/home/sch/nebula/nebula-3.6-io/src/storage/CommonUtils.cpp:57
```

## 6. 读路径 TTL 过滤

### 6.1 Vertex 读取

`TagNode::resetReader()` 会在读取 tag value 后执行 TTL 判断。

如果 TTL 已过期：

1. `reader_.reset()`。
2. 不设置 `valid_ = true`。
3. 上层 scan/query 收集结果时该 tag 不可见。

源码证据：

```text
/home/sch/nebula/nebula-3.6-io/src/storage/exec/TagNode.h:142
/home/sch/nebula/nebula-3.6-io/src/storage/exec/TagNode.h:146
```

### 6.2 Edge 读取

`FetchEdgeNode::resetReader()` 对单条边执行 TTL 判断。

源码证据：

```text
/home/sch/nebula/nebula-3.6-io/src/storage/exec/EdgeNode.h:161
/home/sch/nebula/nebula-3.6-io/src/storage/exec/EdgeNode.h:165
```

`SingleEdgeIterator::check()` 在边遍历时也会跳过 TTL 过期边。

源码证据：

```text
/home/sch/nebula/nebula-3.6-io/src/storage/exec/StorageIterator.h:131
/home/sch/nebula/nebula-3.6-io/src/storage/exec/StorageIterator.h:138
```

## 7. scanVertex / scanEdge 与 TTL

### 7.1 scanVertex

`ScanVertexProcessor` 在构建执行计划时会：

1. 调用 `buildTagTTLInfo()`。
2. 创建 `TagNode`。
3. 创建 `ScanVertexPropNode`。

`ScanVertexPropNode` 遍历 kv 后调用 `TagNode::doExecute()`，而 `TagNode` 内部会执行 TTL 判断。

源码证据：

```text
/home/sch/nebula/nebula-3.6-io/src/storage/query/ScanVertexProcessor.cpp:81
/home/sch/nebula/nebula-3.6-io/src/storage/query/ScanVertexProcessor.cpp:107
/home/sch/nebula/nebula-3.6-io/src/storage/query/ScanVertexProcessor.cpp:109
/home/sch/nebula/nebula-3.6-io/src/storage/exec/ScanNode.h:102
/home/sch/nebula/nebula-3.6-io/src/storage/exec/ScanNode.h:132
```

结论：

`StorageClient.scanVertex` 应该会过滤 TTL 已过期的 tag 数据。

### 7.2 scanEdge

`ScanEdgeProcessor` 在构建执行计划时会：

1. 调用 `buildEdgeTTLInfo()`。
2. 创建 `FetchEdgeNode`。
3. 创建 `ScanEdgePropNode`。

`ScanEdgePropNode` 遍历 kv 后调用 `FetchEdgeNode::doExecute()`，而 `FetchEdgeNode` 内部会执行 TTL 判断。

源码证据：

```text
/home/sch/nebula/nebula-3.6-io/src/storage/query/ScanEdgeProcessor.cpp:78
/home/sch/nebula/nebula-3.6-io/src/storage/query/ScanEdgeProcessor.cpp:103
/home/sch/nebula/nebula-3.6-io/src/storage/query/ScanEdgeProcessor.cpp:106
/home/sch/nebula/nebula-3.6-io/src/storage/exec/ScanNode.h:267
/home/sch/nebula/nebula-3.6-io/src/storage/exec/ScanNode.h:286
```

结论：

`StorageClient.scanEdge` 应该会过滤 TTL 已过期的 edge 数据。

## 8. 测试证据

### 8.1 scanEdge TTL 测试

`ScanEdgeTest.TtlTest` 先执行 scan，确认有数据；等待 TTL 过期后，同一个 scan 请求返回空结果。

源码证据：

```text
/home/sch/nebula/nebula-3.6-io/src/storage/test/ScanEdgeTest.cpp:341
/home/sch/nebula/nebula-3.6-io/src/storage/test/ScanEdgeTest.cpp:373
/home/sch/nebula/nebula-3.6-io/src/storage/test/ScanEdgeTest.cpp:387
```

### 8.2 kvstore 直读仍可读到过期数据

`UpdateVertexTest` 中有注释说明：直接从 kvstore 读取时，TTL 过期数据仍可被读到。

源码证据：

```text
/home/sch/nebula/nebula-3.6-io/src/storage/test/UpdateVertexTest.cpp:1075
```

这说明 TTL 过期后：

1. 查询/scan 层面不可见。
2. 底层 RocksDB 中可能仍保留物理数据。

## 9. Compaction 物理清理

Storage 服务启动时会注册 `StorageCompactionFilterFactoryBuilder`。

源码证据：

```text
/home/sch/nebula/nebula-3.6-io/src/storage/StorageServer.cpp:102
/home/sch/nebula/nebula-3.6-io/src/storage/StorageServer.cpp:104
```

创建 RocksEngine 时会将 compaction filter factory 传给 RocksDB engine。

源码证据：

```text
/home/sch/nebula/nebula-3.6-io/src/kvstore/NebulaStore.cpp:359
/home/sch/nebula/nebula-3.6-io/src/kvstore/NebulaStore.cpp:364
```

RocksDB 执行 compaction 时会创建 `KVCompactionFilter`。

源码证据：

```text
/home/sch/nebula/nebula-3.6-io/src/kvstore/CompactionFilter.h:78
/home/sch/nebula/nebula-3.6-io/src/kvstore/CompactionFilter.h:80
/home/sch/nebula/nebula-3.6-io/src/kvstore/CompactionFilter.h:85
/home/sch/nebula/nebula-3.6-io/src/kvstore/CompactionFilter.h:87
```

`StorageCompactionFilter` 会对 tag、edge、index key 执行 TTL 判断，过期则返回无效并被 filter 清理。

源码证据：

```text
/home/sch/nebula/nebula-3.6-io/src/storage/CompactionFilter.h:44
/home/sch/nebula/nebula-3.6-io/src/storage/CompactionFilter.h:76
/home/sch/nebula/nebula-3.6-io/src/storage/CompactionFilter.h:101
/home/sch/nebula/nebula-3.6-io/src/storage/CompactionFilter.h:119
/home/sch/nebula/nebula-3.6-io/src/storage/CompactionFilter.h:131
/home/sch/nebula/nebula-3.6-io/src/storage/CompactionFilter.h:158
/home/sch/nebula/nebula-3.6-io/src/storage/CompactionFilter.h:173
```

手动 `SUBMIT JOB COMPACT` 最终会调用 RocksDB `CompactRange()`。

源码证据：

```text
/home/sch/nebula/nebula-3.6-io/src/storage/admin/CompactTask.cpp:38
/home/sch/nebula/nebula-3.6-io/src/kvstore/RocksEngine.cpp:527
/home/sch/nebula/nebula-3.6-io/src/kvstore/RocksEngine.cpp:531
```

测试 `CompactionFilterTest.TTLFilterDataExpiredTest` 也验证了：TTL 过期后执行 compaction，相关 tag/edge 数据数量变为 0。

源码证据：

```text
/home/sch/nebula/nebula-3.6-io/src/storage/test/CompactionTest.cpp:188
/home/sch/nebula/nebula-3.6-io/src/storage/test/CompactionTest.cpp:217
/home/sch/nebula/nebula-3.6-io/src/storage/test/CompactionTest.cpp:223
/home/sch/nebula/nebula-3.6-io/src/storage/test/CompactionTest.cpp:226
/home/sch/nebula/nebula-3.6-io/src/storage/test/CompactionTest.cpp:231
```

## 10. 对迁移方案的影响

### 10.1 导出语义

由于 `StorageClient.scanVertex/scanEdge` 走 storage scan 逻辑，并且 scan 逻辑会执行 TTL 判断，因此：

1. TTL 已过期的数据默认不会被导出。
2. 导出结果与 graph 查询可见数据保持一致。
3. 不需要为了过滤 TTL 过期数据绕到 graph 查询。

### 10.2 迁移期间风险

数据静止不等于时间静止。即使禁止写入，TTL 数据仍可能随墙上时间推进而过期。

风险场景：

1. 导出时某条 TTL 数据未过期，被导出。
2. 导入或校验时该数据在源集群或目标集群中过期。
3. 默认数量校验、抽样校验可能出现差异。

### 10.3 建议策略

迁移工具需要在 precheck 或报告中处理 TTL 风险：

1. 检测所有 tag/edge schema 是否配置 TTL。
2. 如果存在 TTL schema，在报告中标记 TTL 风险。
3. 测试阶段验证“目标与当前源一致”时，TTL 数据过期时间必须覆盖导出、导入、校验全过程。
4. 生产阶段验证“目标与导出快照一致”时，建议以导出文件为验收对象，而不是在很久之后对源集群重新 scan。
5. 如果需要强校验 TTL 数据，建议引入迁移参数 `ttl.safe.window.seconds`，要求 TTL 数据在迁移窗口内不会过期。

## 11. 当前仍需确认的问题

### 11.1 三种导出布局的默认值

建议默认：

```text
export.file.layout = segment
```

请确认是否接受。

### 11.2 segment 滚动阈值

三种布局都支持后，`segment` 模式仍需确认默认滚动阈值。

建议：

```text
export.segment.max.rows = 1000000
export.segment.max.bytes = 1073741824
```

即 100 万行或 1GB 先到即滚动。

请确认是否接受，或给出目标值。

### 11.3 checksum 和签名算法

建议：

1. checksum 使用 `SHA-256`。
2. 签名使用 `HMAC-SHA256`。
3. 启动导出任务前通过参数传入签名密钥文件路径。

请确认是否接受。

### 11.4 label 布局的断点语义

如果选择 `label` 布局，一个 tag/edge 一个大文件。该文件没有签名前不能复用，失败后需要整体重导。

请确认该语义是否可接受。

### 11.5 TTL 风险处理方式

请确认迁移工具对 TTL schema 的处理策略：

1. 仅报告风险，不阻断迁移。
2. 检测到 TTL schema 默认阻断，用户加 `--allow-ttl` 后继续。
3. 提供 `ttl.safe.window.seconds`，如果无法证明安全窗口，则阻断。

建议采用方案 1，测试用例中单独覆盖 TTL 迁移风险。
