# parallel_edges_i

1000条边共用相同端点，用1000个rank区分；其他顶点保持独立存在

| 项目 | 实际结果 |
|---|---|
| 验收状态 | PASSED；证据审计 通过 |
| VID 类型 | `INT64` |
| 源空间 | `qa_20260914_i_parallel_edges` |
| 目标空间 | `qa_20260914_i_parallel_edges_target_final` |
| 预期点 / 边 | 1000 / 1000 |
| 源预制值核对 | 2000 条完整记录的原生属性、完整键及 Schema 符合预制表达式 |
| 源 FETCH 基准完成 | 2026-09-13T18:33:27.752386+00:00 |
| 目标导入开始 | 2026-09-13T18:33:27.752391+00:00 |
| 独立 verification | MATCH，双方 COMPLETE，无 ERROR / MISSING |
| SHOW STATS | 两端新统计均符合夹具预期 |
| console FETCH | 6 条固定查询的结果表一致 |
| scan limit | 113 |
| 完成时间 | 2026-09-13T18:34:13.634189+00:00 |

代表值分配（每组场景合计至少 1,000 条，每种值的次数如下）：

```json
{
  "baseline": 1000
}
```

完整步骤、错误码、统计明细、快照清单和 JAR 摘要见 [parallel_edges_i.json](parallel_edges_i.json)。

全部原始 CSV、计划、快照、查询及日志见 [证据归档](../../artifacts/final/parallel_edges_i.tar.gz)。

归档 SHA-256：`c19dd7c8cc1731bd37ad9b86edd7c02d3298360db8517723e19003909ec8a29b`。
