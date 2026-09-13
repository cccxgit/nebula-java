# 准确性故障验收：geography_schema_shape

结果：**PASSED**。PASSED表示成功检出故障，不表示被破坏的数据一致。

- 基线：`geography_point_s`，1000个顶点、1000条边。
- 源空间：`qa_20260914_s_geography_point`。
- 预期：`ERROR`；Change the Tag declaration from GEOGRAPHY(POINT) to GEOGRAPHY(LINESTRING)。
- 时间：2026-09-13T18:30:57.207720+00:00 至 2026-09-13T18:32:11.119298+00:00。

本场景只修改新建目标 `qa_fault_260914_final_geo_geography_schema_shape`，保留现场供复核。

| 实际verification状态 | matched | 成功比较对象 | 差异对象 | 错误/缺失对象 |
| --- | --- | --- | --- | --- |
| ERROR | False | 1000 | 0 | 1000 |

| 新完成统计任务的计数项 | 源 | 注入后目标 |
| --- | --- | --- |
| edge/case_edge | 1000 | 1000 |
| space/edges | 1000 | 1000 |
| space/vertices | 1000 | 1000 |
| tag/case_tag | 1000 | 1000 |

FETCH显示表相同：`True`；该场景显示预期：`SAME_OR_QUERY_ERROR`。原始查询、输出和针对故障对象的额外抽样均归档。

- [完整证据压缩包](<../../artifacts/final-faults/qa_fault_260914_final_geo_geography_schema_shape.tar.gz>)
- [完整JSON报告](<qa_fault_260914_final_geo_geography_schema_shape.json>)
- 压缩包SHA-256：`01033b99061a585e8fb0530ac0845654be65f32cd1206a09cdd3788633916df1`，252671字节。

执行版本证据：[固定执行文件摘要](execution-artifacts.json)，[该场景绑定](qa_fault_260914_final_geo_geography_schema_shape.execution-binding.json)。绑定补充到外部报告；原始归档字节保持不变。
