# 准确性故障验收汇总

状态：**FAILED**。完成2/5场景，通过1，失败1，待执行3。

每个场景使用至少1000点和1000边的已通过基线。PASSED表示检出预期故障，不能解读为受损数据一致。

| 场景 | 结果 | 基线点/边 | 期望verification | 报告 | 证据 |
| --- | --- | --- | --- | --- | --- |
| property_change | PASSED | 1000 / 1000 | DIFFERENT | [property_change](qa_fault_260914_property_change.md) | [tar.gz](<../../artifacts/faults/qa_fault_260914_property_change.tar.gz>) |
| edge_key_replace | FAILED | 1000 / 1000 | ERROR | [edge_key_replace](qa_fault_260914_edge_key_replace.md) | [tar.gz](<../../artifacts/faults/qa_fault_260914_edge_key_replace.tar.gz>) |

未执行场景：tag_membership、bundle_integrity、snapshot_integrity。
