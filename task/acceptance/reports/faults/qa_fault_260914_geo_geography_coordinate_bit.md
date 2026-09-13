# 准确性故障验收：geography_coordinate_bit

结果：**PASSED**。PASSED表示成功检出故障，不表示被破坏的数据一致。

- 基线：`geography_point_s`，1000个顶点、1000条边。
- 源空间：`qa_20260914_s_geography_point`。
- 预期：`DIFFERENT`；Flip exactly one low coordinate bit in one POINT value。
- 时间：2026-09-13T17:50:21.675263+00:00 至 2026-09-13T17:51:14.332586+00:00。

本场景只修改新建目标 `qa_fault_260914_geo_geography_coordinate_bit`，保留现场供复核。

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

- [完整证据压缩包](<../../artifacts/faults/qa_fault_260914_geo_geography_coordinate_bit.tar.gz>)
- [完整JSON报告](<qa_fault_260914_geo_geography_coordinate_bit.json>)
- 压缩包SHA-256：`bbe488159fefddb7e98350be1ad9a4dc7f02e026496c26413a6b778e873ce5cb`，243737字节。
