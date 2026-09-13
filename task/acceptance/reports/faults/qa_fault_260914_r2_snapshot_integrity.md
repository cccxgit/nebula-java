# 准确性故障验收：snapshot_integrity

结果：**PASSED**。PASSED表示成功检出故障，不表示被破坏的数据一致。

- 基线：`bool_values_s`，1000个顶点、1000条边。
- 源空间：`qa_20260914_s_bool_values`。
- 预期：`ERROR`；Reject truncation, mixed plan identity, forged COMPLETE and identical MISSING records。
- 时间：2026-09-13T17:43:10.962048+00:00 至 2026-09-13T17:43:12.420452+00:00。

| 子检查 | 实际结果 | 检出证据 |
| --- | --- | --- |
| truncation | ERROR | matched=False，errorRecords=0 |
| wrong_plan | ERROR | matched=False，errorRecords=0 |
| same_missing | ERROR | matched=False，errorRecords=2000 |
| false_complete | ERROR | matched=False，errorRecords=0 |

统计和console证据使用已通过的完整基线副本。本场景不修改数据库内容；损坏bundle的import必须在创建目标之前拒绝。

- [完整证据压缩包](<../../artifacts/faults/qa_fault_260914_r2_snapshot_integrity.tar.gz>)
- [完整JSON报告](<qa_fault_260914_r2_snapshot_integrity.json>)
- 压缩包SHA-256：`03c1be72edb429ef4f373407095ce3f9c5d1e9efab817ab443310d331eb51a4a`，211974字节。
