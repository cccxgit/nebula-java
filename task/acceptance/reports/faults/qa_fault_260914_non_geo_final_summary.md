# 准确性故障验收汇总

状态：**PASSED**。完成5/5场景，通过5，失败0，待执行0。

每个场景使用至少1000点和1000边的已通过基线。PASSED表示检出预期故障，不能解读为受损数据一致。

| 场景 | 结果 | 基线点/边 | 期望verification | 报告 | 证据 |
| --- | --- | --- | --- | --- | --- |
| property_change | PASSED | 1000 / 1000 | DIFFERENT | [property_change](qa_fault_260914_property_change.md) | [tar.gz](<../../artifacts/faults/qa_fault_260914_property_change.tar.gz>) |
| edge_key_replace | PASSED | 1000 / 1000 | ERROR | [edge_key_replace](qa_fault_260914_r2_edge_key_replace.md) | [tar.gz](<../../artifacts/faults/qa_fault_260914_r2_edge_key_replace.tar.gz>) |
| tag_membership | PASSED | 1000 / 1000 | DIFFERENT | [tag_membership](qa_fault_260914_r2_tag_membership.md) | [tar.gz](<../../artifacts/faults/qa_fault_260914_r2_tag_membership.tar.gz>) |
| bundle_integrity | PASSED | 1000 / 1000 | ERROR | [bundle_integrity](qa_fault_260914_r2_bundle_integrity.md) | [tar.gz](<../../artifacts/faults/qa_fault_260914_r2_bundle_integrity.tar.gz>) |
| snapshot_integrity | PASSED | 1000 / 1000 | ERROR | [snapshot_integrity](qa_fault_260914_r2_snapshot_integrity.md) | [tar.gz](<../../artifacts/faults/qa_fault_260914_r2_snapshot_integrity.tar.gz>) |

初次边键故障场景在注入前遇到测试脚本的空表结束标记解析问题；原始失败报告和现场已保留。修复后使用新的目标前缀和输出目录，剩余4场景全部通过。该事件不是产品代码缺陷。

- [原始失败报告](qa_fault_260914_edge_key_replace.md)
- [新目标重测报告](qa_fault_260914_r2_edge_key_replace.md)
