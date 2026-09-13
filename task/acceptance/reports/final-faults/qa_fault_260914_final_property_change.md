# 准确性故障验收：property_change

结果：**PASSED**。PASSED表示成功检出故障，不表示被破坏的数据一致。

- 基线：`bool_values_s`，1000个顶点、1000条边。
- 源空间：`qa_20260914_s_bool_values`。
- 预期：`DIFFERENT`；Flip one BOOL property while preserving every vertex and edge count。
- 时间：2026-09-13T18:14:02.561326+00:00 至 2026-09-13T18:14:55.792113+00:00。

本场景只修改新建目标 `qa_fault_260914_final_property_change`，保留现场供复核。

| 实际verification状态 | matched | 成功比较对象 | 差异对象 | 错误/缺失对象 |
| --- | --- | --- | --- | --- |
| DIFFERENT | False | 2000 | 1 | 0 |

| 新完成统计任务的计数项 | 源 | 注入后目标 |
| --- | --- | --- |
| edge/case_edge | 1000 | 1000 |
| space/edges | 1000 | 1000 |
| space/vertices | 1000 | 1000 |
| tag/case_tag | 1000 | 1000 |

FETCH显示表相同：`False`；该场景显示预期：`DIFFERENT`。原始查询、输出和针对故障对象的额外抽样均归档。

- [完整证据压缩包](<../../artifacts/final-faults/qa_fault_260914_final_property_change.tar.gz>)
- [完整JSON报告](<qa_fault_260914_final_property_change.json>)
- 压缩包SHA-256：`497ab2e321b972bc021c6cc676bcc827af89e752830d4a1a49a41301887dfec4`，209284字节。

执行版本证据：[固定执行文件摘要](execution-artifacts.json)，[该场景绑定](qa_fault_260914_final_property_change.execution-binding.json)。绑定补充到外部报告；原始归档字节保持不变。
