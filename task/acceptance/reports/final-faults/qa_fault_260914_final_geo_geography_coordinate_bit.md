# 准确性故障验收：geography_coordinate_bit

结果：**PASSED**。PASSED表示成功检出故障，不表示被破坏的数据一致。

- 基线：`geography_point_s`，1000个顶点、1000条边。
- 源空间：`qa_20260914_s_geography_point`。
- 预期：`DIFFERENT`；Flip exactly one low coordinate bit in one POINT value。
- 时间：2026-09-13T18:30:01.755594+00:00 至 2026-09-13T18:30:57.166090+00:00。

本场景只修改新建目标 `qa_fault_260914_final_geo_geography_coordinate_bit`，保留现场供复核。

| 实际verification状态 | matched | 成功比较对象 | 差异对象 | 错误/缺失对象 |
| --- | --- | --- | --- | --- |
| DIFFERENT | False | 2000 | 1 | 0 |

| 新完成统计任务的计数项 | 源 | 注入后目标 |
| --- | --- | --- |
| edge/case_edge | 1000 | 1000 |
| space/edges | 1000 | 1000 |
| space/vertices | 1000 | 1000 |
| tag/case_tag | 1000 | 1000 |

FETCH显示表相同：`False`；该场景显示预期：`DISPLAY_MAY_MATCH`。原始查询、输出和针对故障对象的额外抽样均归档。

坐标原生位：`40667fffffffffff` → `40667ffffffffffe`，异或为`0000000000000001`；目标单比特变化确认：`True`。console显示精度不能代替该断言。

- [完整证据压缩包](<../../artifacts/final-faults/qa_fault_260914_final_geo_geography_coordinate_bit.tar.gz>)
- [完整JSON报告](<qa_fault_260914_final_geo_geography_coordinate_bit.json>)
- 压缩包SHA-256：`2d48cf1c02a4d665d224e8c26092162629c5b47bd9d1aa6787daa55397b8bcd4`，244269字节。

执行版本证据：[固定执行文件摘要](execution-artifacts.json)，[该场景绑定](qa_fault_260914_final_geo_geography_coordinate_bit.execution-binding.json)。绑定补充到外部报告；原始归档字节保持不变。
