# combined_types_s

每条记录同时包含全部18种声明类型/地理约束，交叉组合值

| 项目 | 实际结果 |
|---|---|
| 验收状态 | PASSED；证据审计 通过 |
| VID 类型 | `FIXED_STRING(256)` |
| 源空间 | `qa_20260914_s_combined_types` |
| 目标空间 | `qa_20260914_s_combined_types_target_final` |
| 预期点 / 边 | 1000 / 1000 |
| 源预制值核对 | 2000 条完整记录的原生属性、完整键及 Schema 符合预制表达式 |
| 源 FETCH 基准完成 | 2026-09-13T18:35:22.522268+00:00 |
| 目标导入开始 | 2026-09-13T18:35:22.522276+00:00 |
| 独立 verification | MATCH，双方 COMPLETE，无 ERROR / MISSING |
| SHOW STATS | 两端新统计均符合夹具预期 |
| console FETCH | 6 条固定查询的结果表一致 |
| scan limit | 113 |
| 完成时间 | 2026-09-13T18:36:10.529740+00:00 |

代表值分配（每组场景合计至少 1,000 条，每种值的次数如下）：

```json
{
  "combination_0": 53,
  "combination_1": 53,
  "combination_2": 53,
  "combination_3": 53,
  "combination_4": 53,
  "combination_5": 53,
  "combination_6": 53,
  "combination_7": 53,
  "combination_8": 53,
  "combination_9": 53,
  "combination_10": 53,
  "combination_11": 53,
  "combination_12": 52,
  "combination_13": 52,
  "combination_14": 52,
  "combination_15": 52,
  "combination_16": 52,
  "combination_17": 52,
  "combination_18": 52
}
```

完整步骤、错误码、统计明细、快照清单和 JAR 摘要见 [combined_types_s.json](combined_types_s.json)。

全部原始 CSV、计划、快照、查询及日志见 [证据归档](../../artifacts/final/combined_types_s.tar.gz)。

归档 SHA-256：`4562373d2f3322667f4c38dc7c04653e7cc73e3e734eca028ca4a74b53faf5eb`。
