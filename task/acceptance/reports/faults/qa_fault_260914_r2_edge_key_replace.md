# 准确性故障验收：edge_key_replace

结果：**PASSED**。PASSED表示成功检出故障，不表示被破坏的数据一致。

- 基线：`bool_values_s`，1000个顶点、1000条边。
- 源空间：`qa_20260914_s_bool_values`。
- 预期：`ERROR`；Replace one complete edge key with another rank while total counts stay equal。
- 时间：2026-09-13T17:41:02.373272+00:00 至 2026-09-13T17:41:56.675599+00:00。

本场景只修改新建目标 `qa_fault_260914_r2_edge_key_replace`，保留现场供复核。

| 实际verification状态 | matched | 成功比较对象 | 差异对象 | 错误/缺失对象 |
| --- | --- | --- | --- | --- |
| ERROR | False | 1999 | 0 | 1 |

| 新完成统计任务的计数项 | 源 | 注入后目标 |
| --- | --- | --- |
| edge/case_edge | 1000 | 1000 |
| space/edges | 1000 | 1000 |
| space/vertices | 1000 | 1000 |
| tag/case_tag | 1000 | 1000 |

FETCH显示表相同：`False`；该场景显示预期：`DIFFERENT_OR_MISSING`。原始查询、输出和针对故障对象的额外抽样均归档。

- [完整证据压缩包](<../../artifacts/faults/qa_fault_260914_r2_edge_key_replace.tar.gz>)
- [完整JSON报告](<qa_fault_260914_r2_edge_key_replace.json>)
- 压缩包SHA-256：`57ae5b4699dd2e73c882543b390f460dd22b4830b6f0da26e87c5b929efed0f5`，209455字节。
