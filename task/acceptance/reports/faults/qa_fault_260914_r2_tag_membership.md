# 准确性故障验收：tag_membership

结果：**PASSED**。PASSED表示成功检出故障，不表示被破坏的数据一致。

- 基线：`bool_values_s`，1000个顶点、1000条边。
- 源空间：`qa_20260914_s_bool_values`。
- 预期：`DIFFERENT`；Replace a vertex's original Tag with a new zero-property Tag。
- 时间：2026-09-13T17:41:56.704222+00:00 至 2026-09-13T17:43:10.078217+00:00。

本场景只修改新建目标 `qa_fault_260914_r2_tag_membership`，保留现场供复核。

| 实际verification状态 | matched | 成功比较对象 | 差异对象 | 错误/缺失对象 |
| --- | --- | --- | --- | --- |
| DIFFERENT | False | 2000 | 1 | 0 |

| 新完成统计任务的计数项 | 源 | 注入后目标 |
| --- | --- | --- |
| edge/case_edge | 1000 | 1000 |
| space/edges | 1000 | 1000 |
| space/vertices | 1000 | 1000 |
| tag/case_tag | 1000 | 999 |
| tag/fault_extra | — | 1 |

FETCH显示表相同：`False`；该场景显示预期：`DIFFERENT`。原始查询、输出和针对故障对象的额外抽样均归档。

- [完整证据压缩包](<../../artifacts/faults/qa_fault_260914_r2_tag_membership.tar.gz>)
- [完整JSON报告](<qa_fault_260914_r2_tag_membership.json>)
- 压缩包SHA-256：`ed2d15ff320b67ad82749c2843dbfc2eb1cfeb1928ca3d76331a1d92d4b84e8f`，209949字节。
