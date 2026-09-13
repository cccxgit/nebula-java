# 准确性故障验收最终汇总

结果：**7 / 7 PASSED**。这里的 PASSED 表示成功检出故障，所有受损数据或文件均未被判断为 MATCH。

每项都采用已通过原生值校验的 1000 个顶点、1000 条边基线。数据库故障均先重新采集源基线，再导入新的独立目标；源空间和正式迁移验收的已通过目标未作修改。主迁移矩阵为 90 项，本报告单独覆盖 7 项准确性故障。

| 故障 | 预期 / 实际 | 结果细节 | 报告 | 证据 |
| --- | --- | --- | --- | --- |
| property_change | DIFFERENT / DIFFERENT | 比较 2000；差异 1；错误/缺失 0；退出 2 | [Markdown](qa_fault_260914_property_change.md) | [tar.gz](<../../artifacts/faults/qa_fault_260914_property_change.tar.gz>) |
| edge_key_replace | ERROR / ERROR | 比较 1999；差异 0；错误/缺失 1；退出 1 | [Markdown](qa_fault_260914_r2_edge_key_replace.md) | [tar.gz](<../../artifacts/faults/qa_fault_260914_r2_edge_key_replace.tar.gz>) |
| tag_membership | DIFFERENT / DIFFERENT | 比较 2000；差异 1；错误/缺失 0；退出 2 | [Markdown](qa_fault_260914_r2_tag_membership.md) | [tar.gz](<../../artifacts/faults/qa_fault_260914_r2_tag_membership.tar.gz>) |
| geography_coordinate_bit | DIFFERENT / DIFFERENT | 比较 2000；差异 1；错误/缺失 0；退出 2 | [Markdown](qa_fault_260914_geo_geography_coordinate_bit.md) | [tar.gz](<../../artifacts/faults/qa_fault_260914_geo_geography_coordinate_bit.tar.gz>) |
| geography_schema_shape | ERROR / ERROR | 比较 1000；差异 0；错误/缺失 1000；退出 1 | [Markdown](qa_fault_260914_geo_geography_schema_shape.md) | [tar.gz](<../../artifacts/faults/qa_fault_260914_geo_geography_schema_shape.tar.gz>) |
| bundle_integrity | ERROR / ERROR | 2 项 prepare + import 均退出 1；目标空间前后均不存在 | [Markdown](qa_fault_260914_r2_bundle_integrity.md) | [tar.gz](<../../artifacts/faults/qa_fault_260914_r2_bundle_integrity.tar.gz>) |
| snapshot_integrity | ERROR / ERROR | 4 项均退出 1；相同 2000 个 MISSING 仍为 ERROR | [Markdown](qa_fault_260914_r2_snapshot_integrity.md) | [tar.gz](<../../artifacts/faults/qa_fault_260914_r2_snapshot_integrity.tar.gz>) |

单比特坐标场景只改变一个 POINT 经度：`40667fffffffffff` → `40667ffffffffffe`，XOR 为 `1`；原生坐标确认变化，顶点和边的统计数量保持一致。console 抽样仅辅助展示，不决定原生值相等。

地理约束场景的 `ALTER TAG ... GEOGRAPHY(LINESTRING)` 在本次真实服务端执行成功。已有 POINT 值与新声明不符，采集准确报告 1000 个顶点错误，另 1000 条边可比；最终状态为 ERROR。

数据库场景均保存故障注入前后的最新统计任务、针对故障对象的 FETCH 原生结果及直接可执行的 nGQL。文件故障场景保存完整的已通过基线计数、样本和快照。

7 份完整归档的字节数、SHA-256、归档内报告、全部 143 份命令日志哈希，以及基线完整快照哈希均已核对。

- [详细 JSON 汇总](final-summary.json)
- [归档内部证据审计](final-evidence-audit.json)

首次边键场景在故障注入前遇到 console 空结果结束标记的脚本解析问题。修改仅限测试样本解析器，保留原始输出；已在新的目标空间复测通过，不涉及产品代码错误。
- [原失败报告](qa_fault_260914_edge_key_replace.md)；[通过的复测报告](qa_fault_260914_r2_edge_key_replace.md)。
