# time_microseconds_s

TIME午夜、最后一秒及微秒边界

| 项目 | 实际结果 |
|---|---|
| 验收状态 | PASSED；证据审计 通过 |
| VID 类型 | `FIXED_STRING(256)` |
| 源空间 | `qa_20260914_s_time_microseconds` |
| 目标空间 | `qa_20260914_s_time_microseconds_target` |
| 预期点 / 边 | 1000 / 1000 |
| 源预制值核对 | 2000 条完整记录的原生属性、完整键及 Schema 符合预制表达式 |
| 源 FETCH 基准完成 | 2026-09-13T17:45:53.538366+00:00 |
| 目标导入开始 | 2026-09-13T17:45:53.538371+00:00 |
| 独立 verification | MATCH，双方 COMPLETE，无 ERROR / MISSING |
| SHOW STATS | 两端新统计均符合夹具预期 |
| console FETCH | 6 条固定查询的结果表一致 |
| scan limit | 113 |
| 完成时间 | 2026-09-13T17:46:40.311569+00:00 |

代表值分配（每组场景合计至少 1,000 条，每种值的次数如下）：

```json
{
  "micro_0": 125,
  "micro_1": 125,
  "micro_999": 125,
  "micro_1000": 125,
  "micro_123456": 125,
  "micro_999998": 125,
  "micro_999999": 125,
  "midnight": 125
}
```

完整步骤、错误码、统计明细、快照清单和 JAR 摘要见 [time_microseconds_s.json](time_microseconds_s.json)。

全部原始 CSV、计划、快照、查询及日志见 [证据归档](../artifacts/time_microseconds_s.tar.gz)。

归档 SHA-256：`00320463f195b925c6f3214b4fc934572ad95646b52b0570f304228965a8155d`。
