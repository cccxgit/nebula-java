# 准确性故障验收：bundle_integrity

结果：**PASSED**。PASSED表示成功检出故障，不表示被破坏的数据一致。

- 基线：`bool_values_s`，1000个顶点、1000条边。
- 源空间：`qa_20260914_s_bool_values`。
- 预期：`ERROR`；Reject modified CSV bytes and a duplicate key with refreshed file hash/count。
- 时间：2026-09-13T17:43:10.115301+00:00 至 2026-09-13T17:43:10.930125+00:00。

| 子检查 | 实际结果 | 检出证据 |
| --- | --- | --- |
| csv_hash | ERROR | prepare退出1；import退出1；目标在SHOW SPACES前后均不存在；原因：CSV SHA-256 mismatch |
| duplicate_key | ERROR | prepare退出1；import退出1；目标在SHOW SPACES前后均不存在；原因：Duplicate complete key in CSV |

统计和console证据使用已通过的完整基线副本。本场景不修改数据库内容；损坏bundle的import必须在创建目标之前拒绝。

- [完整证据压缩包](<../../artifacts/faults/qa_fault_260914_r2_bundle_integrity.tar.gz>)
- [完整JSON报告](<qa_fault_260914_r2_bundle_integrity.json>)
- 压缩包SHA-256：`1c0368101b30c36a549161eaba8ebe8de45dba92892f4d774a07757acddf2b7f`，120785字节。
