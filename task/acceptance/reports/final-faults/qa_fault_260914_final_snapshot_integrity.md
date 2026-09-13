# 准确性故障验收：snapshot_integrity

结果：**PASSED**。PASSED表示成功检出故障，不表示被破坏的数据一致。

- 基线：`bool_values_s`，1000个顶点、1000条边。
- 源空间：`qa_20260914_s_bool_values`。
- 预期：`ERROR`；Reject truncation, mixed plan identity, forged COMPLETE and identical MISSING records。
- 时间：2026-09-13T18:17:04.336817+00:00 至 2026-09-13T18:17:05.848669+00:00。

| 子检查 | 实际结果 | 检出证据 |
| --- | --- | --- |
| truncation | ERROR | matched=False，errorRecords=0 |
| wrong_plan | ERROR | matched=False，errorRecords=0 |
| same_missing | ERROR | matched=False，errorRecords=2000 |
| false_complete | ERROR | matched=False，errorRecords=0 |

统计和console证据使用已通过的完整基线副本。本场景不修改数据库内容；损坏bundle的import必须在创建目标之前拒绝。

- [完整证据压缩包](<../../artifacts/final-faults/qa_fault_260914_final_snapshot_integrity.tar.gz>)
- [完整JSON报告](<qa_fault_260914_final_snapshot_integrity.json>)
- 压缩包SHA-256：`1cdd91d9640ada352d69a843f6e699cab09851696585a0200f34670d05d80ab5`，212195字节。

执行版本证据：[固定执行文件摘要](execution-artifacts.json)，[该场景绑定](qa_fault_260914_final_snapshot_integrity.execution-binding.json)。绑定补充到外部报告；原始归档字节保持不变。
