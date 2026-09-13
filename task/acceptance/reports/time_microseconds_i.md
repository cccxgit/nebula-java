# time_microseconds_i

TIME午夜、最后一秒及微秒边界

| 项目 | 实际结果 |
|---|---|
| 验收状态 | PASSED；证据审计 通过 |
| VID 类型 | `INT64` |
| 源空间 | `qa_20260914_i_time_microseconds` |
| 目标空间 | `qa_20260914_i_time_microseconds_target` |
| 预期点 / 边 | 1000 / 1000 |
| 源预制值核对 | 2000 条完整记录的原生属性、完整键及 Schema 符合预制表达式 |
| 源 FETCH 基准完成 | 2026-09-13T17:46:57.129081+00:00 |
| 目标导入开始 | 2026-09-13T17:46:57.129085+00:00 |
| 独立 verification | MATCH，双方 COMPLETE，无 ERROR / MISSING |
| SHOW STATS | 两端新统计均符合夹具预期 |
| console FETCH | 6 条固定查询的结果表一致 |
| scan limit | 113 |
| 完成时间 | 2026-09-13T17:47:42.977550+00:00 |

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

完整步骤、错误码、统计明细、快照清单和 JAR 摘要见 [time_microseconds_i.json](time_microseconds_i.json)。

全部原始 CSV、计划、快照、查询及日志见 [证据归档](../artifacts/time_microseconds_i.tar.gz)。

归档 SHA-256：`da5791962200f1e60e225c030518074c3173206f408c1b5cb498d55dc621ac20`。
