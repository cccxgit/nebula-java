# datetime_values_i

DATETIME年份边界、闰日与微秒完整保存

| 项目 | 实际结果 |
|---|---|
| 验收状态 | PASSED；证据审计 通过 |
| VID 类型 | `INT64` |
| 源空间 | `qa_20260914_i_datetime_values` |
| 目标空间 | `qa_20260914_i_datetime_values_target` |
| 预期点 / 边 | 1000 / 1000 |
| 源预制值核对 | 2000 条完整记录的原生属性、完整键及 Schema 符合预制表达式 |
| 源 FETCH 基准完成 | 2026-09-13T17:46:47.729485+00:00 |
| 目标导入开始 | 2026-09-13T17:46:47.729487+00:00 |
| 独立 verification | MATCH，双方 COMPLETE，无 ERROR / MISSING |
| SHOW STATS | 两端新统计均符合夹具预期 |
| console FETCH | 6 条固定查询的结果表一致 |
| scan limit | 113 |
| 完成时间 | 2026-09-13T17:47:33.863679+00:00 |

代表值分配（每组场景合计至少 1,000 条，每种值的次数如下）：

```json
{
  "0": 84,
  "1": 84,
  "2": 84,
  "3": 84,
  "4": 83,
  "5": 83,
  "6": 83,
  "7": 83,
  "8": 83,
  "9": 83,
  "10": 83,
  "11": 83
}
```

完整步骤、错误码、统计明细、快照清单和 JAR 摘要见 [datetime_values_i.json](datetime_values_i.json)。

全部原始 CSV、计划、快照、查询及日志见 [证据归档](../artifacts/datetime_values_i.tar.gz)。

归档 SHA-256：`d854d239ac6017ae77af780e29423057c95672cd32a349b9a885a5dc06df56d3`。
