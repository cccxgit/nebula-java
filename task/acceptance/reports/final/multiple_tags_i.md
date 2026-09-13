# multiple_tags_i

每个VID同时具有数据tag和零属性extra_tag，保留tag集合

| 项目 | 实际结果 |
|---|---|
| 验收状态 | PASSED；证据审计 通过 |
| VID 类型 | `INT64` |
| 源空间 | `qa_20260914_i_multiple_tags` |
| 目标空间 | `qa_20260914_i_multiple_tags_target_final` |
| 预期点 / 边 | 1000 / 1000 |
| 源预制值核对 | 2000 条完整记录的原生属性、完整键及 Schema 符合预制表达式 |
| 源 FETCH 基准完成 | 2026-09-13T18:31:39.569278+00:00 |
| 目标导入开始 | 2026-09-13T18:31:39.569282+00:00 |
| 独立 verification | MATCH，双方 COMPLETE，无 ERROR / MISSING |
| SHOW STATS | 两端新统计均符合夹具预期 |
| console FETCH | 6 条固定查询的结果表一致 |
| scan limit | 113 |
| 完成时间 | 2026-09-13T18:32:26.838698+00:00 |

代表值分配（每组场景合计至少 1,000 条，每种值的次数如下）：

```json
{
  "baseline": 1000
}
```

完整步骤、错误码、统计明细、快照清单和 JAR 摘要见 [multiple_tags_i.json](multiple_tags_i.json)。

全部原始 CSV、计划、快照、查询及日志见 [证据归档](../../artifacts/final/multiple_tags_i.tar.gz)。

归档 SHA-256：`d3c54fa44c19feb4b987568b351f3c933a5570ffb094c537e462c19a4250f3d6`。
