# 09. 校验引擎软件实现设计

> 状态：软件实现设计，未开始编码。
> 默认验收为数量一致和确定性抽样一致；全量 canonical checksum 由 `verify.full-checksum.enabled=true` 手动开启。未开启时报告必须明确“未证明全量完全一致”。

## 1. 目标与边界

校验目标集群与 export snapshot 的一致性，并按 space、tag、edge、partition 输出证据。测试阶段在源保持冻结时，snapshot 与当前源等价；生产阶段只声明目标与导出快照的比对结果。

校验不重新导出源文件，不修改源/目标数据，不把 count 一致误报为全量属性一致。TTL 存在时仍执行相同标准，风险另行标记。

## 2. 源码依据

| 来源 | 关键位置 | 设计结论 |
| --- | --- | --- |
| nebula-java | `storage/StorageClient.java:458-477,947-957` | 目标可按相同 `space + partition + label` scan，校验维度与导出一致。 |
| nebula-java | `storage/scan/ScanVertexResult.java:103-130`、`ScanEdgeResult.java:86-100` | 校验 scan 每页都必须检查全成功，部分结果不能计数或计算 digest。 |
| nebula-java | `storage/data/VertexRow.java`、`storage/data/EdgeRow.java` | count、sample 和 checksum 均使用真实 vertex/edge key，edge 包括 rank。 |
| Nebula Graph 3.6 | `/home/sch/nebula/nebula-3.6-io/src/storage/exec/ScanNode.h:72-117` | partition scan 使用 key range 和 cursor；同版本、同数据下 key 扫描序列是流式可计算的基础，但需由集成测试确认。 |
| Nebula Graph 3.6 | `src/storage/exec/TagNode.h:142-151`、`EdgeNode.h:161-170` | TTL 可使 scan 行不可见，因此 verify 必须复用 scan 语义并报告 TTL 风险。 |
| nebula-java | `graph/data/ValueWrapper.java` | 抽样/全量对比必须通过 05 canonical typed value，不能比较字符串化结果。 |

## 3. 包与输入输出

```text
com.vesoft.nebula.migration.verify
  VerifyCommand, VerifyEngine, VerificationPlanner
  TargetPartitionScanner, CountVerifier, SampleVerifier
  FullChecksumVerifier, SampleSelector, VerifyReportBuilder
```

输入为：已签名 manifest、metadata snapshot、导出 per-partition `ExportStatistics`、sample artifact 和目标 `StorageClientAdapter`。输出为 `verify-report.json`、每单位 `VerificationResult` 及 07 模块的 verify checkpoint。

## 4. 一次目标 scan 的三种计算

对一个 `space + kind + label + partition`，`TargetPartitionScanner` 只扫描一次，在逐 row 流中同时执行：

1. `count++`，得到目标行数。
2. 计算 canonical row hash；若 key 命中该 partition 的 source sample 索引，则比较 source/target row hash。
3. 当全量 checksum 启用时，更新该 partition 的 streaming digest。

这样默认 count + sample 无需为同一 partition 扫描两次，开启 full checksum 也不额外读取目标数据。sample 索引按 partition 加载，内存上界受该 partition 的 sample 数控制。

## 5. 数量与确定性抽样

导出时 06 模块保存 `exportCount[space,kind,label,partition]`。目标 count 与其精确相等才 PASS；没有“总数相等即可”的降级，因为报告维度要求 partition 级证据。

抽样规则：

```text
h = SHA-256(canonical key bytes)
select if unsigned(h[0..7]) mod verify.sample.mod == 0
```

对每个非空 partition，若上述规则未选中任何行，导出端额外保留 hash 最小的一行，保证每个非空单位至少有一个样本。sample artifact 记录 key、canonical row hash、schema hash 和 source partition，不保存不必要的完整业务属性。目标扫描时样本缺失、属性 hash 不同、出现 schema hash 不同均为 FAIL。

## 6. 手动全量 checksum

当 `verify.full-checksum.enabled=true` 时，导出与目标校验都按同一 `(space,kind,label,partition)` 计算：

```text
H0 = SHA-256("nebula-migration-row-stream-v1" || schemaHash)
Hi = SHA-256(H(i-1) || canonicalRowHash(i))
result = Hn + rowCount
```

行序取 storage scan 的 key/cursor 顺序。该策略依赖同版本 Nebula storage 对相同 key 集合给出稳定的逻辑 scan 序列；其来源是 `ScanNode` 的 prefix range 迭代。11 模块必须加入“不同 compaction 后 source/target full checksum 仍相同”的验证。若 3.8 实测不能保证顺序稳定，编码阶段必须切换为外部排序/Merkle 分桶方案后才能宣称 full checksum 可用，不能静默退化为简单 count。

full checksum 同时比较 digest 与 row count。未开启时，`FullChecksumVerifier` 输出 `SKIPPED_DISABLED`，顶层报告固定写入：`fullConsistencyProven=false` 和“未证明全量完全一致”。

## 7. 结果判定与报告

| 检查 | PASS 条件 | FAIL 证据 |
| --- | --- | --- |
| 文件完整性 | manifest 与所有 artifact checksum/signature 通过 | 文件路径、预期/实际 hash、签名状态 |
| count | source/target per-partition rows 相等 | source/target count、差值 |
| sample | 样本数相同且每 key row hash 相同 | missing/unexpected/mismatch key 摘要 |
| full checksum | 已启用且 digest/count 相同 | source/target digest/count |

顶层 `status=PASS` 的默认条件是所有 count 与 sample PASS；若启用了 full checksum，则它也必须 PASS。报告每个单位写 `ttlAffected`、scan duration、source/target count、sample expected/checked/failed、checksum status/digest（可选）、错误和证据路径。任一 FAIL 返回退出码 8。

## 8. 恢复与性能

verify checkpoint 只在一个 partition 的全部启用检查完成后更新。失败 partition 可单独重扫；如果 metadata schema hash、sample artifact 或 verify 配置发生变化，旧 checkpoint 作废。默认 sample modulus 为 `100000`，全量 checksum 由用户显式承担一次目标全扫描的时间和 storage 压力。

验证 worker 并发采用 `verify.concurrent.partitions` 静态配置，初始值复用导出 partition 并发上限但可单独下调。每个 worker 流式处理 scan page，不积累整个 partition。

## 9. 测试与完成准则

测试覆盖 count 差异、样本缺失/多余/属性差异、整数/字符串 VID、edge rank、NULL/空字符串/浮点/时间的 canonical 比较、每 partition 最小样本、full checksum 开关文本、checksum mismatch、TTL 前后差异和 verify 中断恢复。

完成标准：默认报告能为每个 space/tag/edge/partition 给出 count 和 sample 证据；开启 full checksum 后能证明同版本逻辑扫描序列的全行属性一致性；关闭时绝不使用“完全一致”措辞。
