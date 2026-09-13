# 最终构建准确性故障验收

结果：**7 / 7 PASSED**。PASSED 表示成功检出故障，所有受损数据或文件均未被判断为 MATCH。

每项使用最终构建正式迁移验收已通过的 1000 个顶点、1000 条边基线。数据库故障先重新采集源基线，再导入新的独立目标；源空间和已通过的正式迁移目标未作修改。主迁移矩阵独立验收，本报告单独覆盖 7 项故障。

| 故障 | 预期 / 实际 | 结果细节 | 报告 | 证据 |
| --- | --- | --- | --- | --- |
| property_change | DIFFERENT / DIFFERENT | 比较 2000；差异 1；错误/缺失 0；退出 2 | [Markdown](qa_fault_260914_final_property_change.md) | [tar.gz](<../../artifacts/final-faults/qa_fault_260914_final_property_change.tar.gz>) |
| edge_key_replace | ERROR / ERROR | 比较 1999；差异 0；错误/缺失 1；退出 1 | [Markdown](qa_fault_260914_final_edge_key_replace.md) | [tar.gz](<../../artifacts/final-faults/qa_fault_260914_final_edge_key_replace.tar.gz>) |
| tag_membership | DIFFERENT / DIFFERENT | 比较 2000；差异 1；错误/缺失 0；退出 2 | [Markdown](qa_fault_260914_final_tag_membership.md) | [tar.gz](<../../artifacts/final-faults/qa_fault_260914_final_tag_membership.tar.gz>) |
| geography_coordinate_bit | DIFFERENT / DIFFERENT | 比较 2000；差异 1；错误/缺失 0；退出 2 | [Markdown](qa_fault_260914_final_geo_geography_coordinate_bit.md) | [tar.gz](<../../artifacts/final-faults/qa_fault_260914_final_geo_geography_coordinate_bit.tar.gz>) |
| geography_schema_shape | ERROR / ERROR | 比较 1000；差异 0；错误/缺失 1000；退出 1 | [Markdown](qa_fault_260914_final_geo_geography_schema_shape.md) | [tar.gz](<../../artifacts/final-faults/qa_fault_260914_final_geo_geography_schema_shape.tar.gz>) |
| bundle_integrity | ERROR / ERROR | 两项 prepare 和 import 均退出 1，目标空间前后均不存在 | [Markdown](qa_fault_260914_final_bundle_integrity.md) | [tar.gz](<../../artifacts/final-faults/qa_fault_260914_final_bundle_integrity.tar.gz>) |
| snapshot_integrity | ERROR / ERROR | 四项均退出 1；相同 2000 个 MISSING 仍为 ERROR | [Markdown](qa_fault_260914_final_snapshot_integrity.md) | [tar.gz](<../../artifacts/final-faults/qa_fault_260914_final_snapshot_integrity.tar.gz>) |

坐标只改变经度最低一位：`40667fffffffffff` → `40667ffffffffffe`，XOR 为 `1`；原生坐标断言通过，点边计数保持一致。console 仅用于辅助展示。

POINT 约束改为 LINESTRING 的实际 ALTER 成功；采集准确报告 1000 个顶点形状错误，另 1000 条边可比，整体 ERROR。

7 份归档的字节数、SHA-256、归档内报告、全部 145 份命令日志哈希，以及基线完整快照哈希均已核对。每份外部报告通过独立绑定文件引用固定执行文件摘要；原始归档保持不变。

- migration JAR SHA-256：`f760ab235da35fadc8a0ffb1db7f1a26e9522bdb7b22732bab755524cd721c54`
- verifier JAR SHA-256：`dc8ecf68fe311d64c15efa08cfec2d6db1ed61956be5c31ac3d8df404b5cc099`
- [详细 JSON 汇总](final-summary.json)
- [归档内部证据审计](final-evidence-audit.json)
- [执行文件结束复核](final-execution-audit.json)
- [固定执行文件摘要](execution-artifacts.json)

本轮七项全部一次通过。旧构建的故障报告与测试脚本历史失败记录保留在独立历史目录，不计入本轮最终构建结果。
