# reject_int8_range_i

INT8属性128/-129，存储必须拒绝越界值

| 项目 | 实际结果 |
|---|---|
| 验收状态 | PASSED；证据审计 通过 |
| VID 类型 | `INT64` |
| 源空间 | `qa_20260914_i_reject_int8_range` |
| 目标空间 | `qa_20260914_i_reject_int8_range_target_final` |
| 预期点 / 边 | 1000 / 1000 |
| 源预制值核对 | 2000 条完整记录的原生属性、完整键及 Schema 符合预制表达式 |
| 源 FETCH 基准完成 | 2026-09-13T18:37:21.579241+00:00 |
| 目标导入开始 | 2026-09-13T18:37:21.579244+00:00 |
| 独立 verification | MATCH，双方 COMPLETE，无 ERROR / MISSING |
| SHOW STATS | 两端新统计均符合夹具预期 |
| console FETCH | 6 条固定查询的结果表一致 |
| scan limit | 113 |
| 完成时间 | 2026-09-13T18:38:08.088407+00:00 |

代表值分配（每组场景合计至少 1,000 条，每种值的次数如下）：

```json
{
  "baseline": 1000
}
```

另外执行 1000 次非法输入，均被拒绝；有效对照内容和数量均未改变。

完整步骤、错误码、统计明细、快照清单和 JAR 摘要见 [reject_int8_range_i.json](reject_int8_range_i.json)。

全部原始 CSV、计划、快照、查询及日志见 [证据归档](../../artifacts/final/reject_int8_range_i.tar.gz)。

归档 SHA-256：`c63e1689093d61110584ab5418242629cbb9df54dd4de22c11cd1f5004729e40`。
