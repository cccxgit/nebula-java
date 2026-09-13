# duration_microsecond_bounds_i

DURATION微秒原始int32上下界、相邻值与跨秒值，不归一化

| 项目 | 实际结果 |
|---|---|
| 验收状态 | PASSED；证据审计 通过 |
| VID 类型 | `INT64` |
| 源空间 | `qa_20260914_i_duration_microsecond_bounds` |
| 目标空间 | `qa_20260914_i_duration_microsecond_bounds_target_final` |
| 预期点 / 边 | 1000 / 1000 |
| 源预制值核对 | 2000 条完整记录的原生属性、完整键及 Schema 符合预制表达式 |
| 源 FETCH 基准完成 | 2026-09-13T18:28:02.240400+00:00 |
| 目标导入开始 | 2026-09-13T18:28:02.240408+00:00 |
| 独立 verification | MATCH，双方 COMPLETE，无 ERROR / MISSING |
| SHOW STATS | 两端新统计均符合夹具预期 |
| console FETCH | 6 条固定查询的结果表一致 |
| scan limit | 113 |
| 完成时间 | 2026-09-13T18:28:48.794994+00:00 |

代表值分配（每组场景合计至少 1,000 条，每种值的次数如下）：

```json
{
  "microseconds_-2147483648": 91,
  "microseconds_-2147483647": 91,
  "microseconds_-1000000": 91,
  "microseconds_-999999": 91,
  "microseconds_-1": 91,
  "microseconds_0": 91,
  "microseconds_1": 91,
  "microseconds_999999": 91,
  "microseconds_1000000": 91,
  "microseconds_2147483646": 91,
  "microseconds_2147483647": 90
}
```

完整步骤、错误码、统计明细、快照清单和 JAR 摘要见 [duration_microsecond_bounds_i.json](duration_microsecond_bounds_i.json)。

全部原始 CSV、计划、快照、查询及日志见 [证据归档](../../artifacts/final/duration_microsecond_bounds_i.tar.gz)。

归档 SHA-256：`82014b0074b1ddca84889306fb30bf558c751b1bfc3af0b0915ebb11d982417e`。
