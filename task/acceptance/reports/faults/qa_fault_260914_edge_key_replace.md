# 准确性故障验收：edge_key_replace

结果：**FAILED**。PASSED表示成功检出故障，不表示被破坏的数据一致。

- 基线：`bool_values_s`，1000个顶点、1000条边。
- 源空间：`qa_20260914_s_bool_values`。
- 预期：`ERROR`；Replace one complete edge key with another rank while total counts stay equal。
- 时间：2026-09-13T17:39:00.371890+00:00 至 2026-09-13T17:39:50.040784+00:00。

本场景只修改新建目标 `qa_fault_260914_edge_key_replace`，保留现场供复核。

失败原因：`Unterminated console result table`。

统计和console证据使用已通过的完整基线副本。本场景不修改数据库内容；损坏bundle的import必须在创建目标之前拒绝。

- [完整证据压缩包](<../../artifacts/faults/qa_fault_260914_edge_key_replace.tar.gz>)
- [完整JSON报告](<qa_fault_260914_edge_key_replace.json>)
- 压缩包SHA-256：`8b102242d9ace1a222654b765658de701878df79f5fb448e7c049177c7155e78`，178351字节。
