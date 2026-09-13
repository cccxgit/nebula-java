# date_values_i

DATE负年、0年、闰日及16位年份边界

| 项目 | 实际结果 |
|---|---|
| 验收状态 | PASSED；证据审计 通过 |
| VID 类型 | `INT64` |
| 源空间 | `qa_20260914_i_date_values` |
| 目标空间 | `qa_20260914_i_date_values_target_final` |
| 预期点 / 边 | 1000 / 1000 |
| 源预制值核对 | 2000 条完整记录的原生属性、完整键及 Schema 符合预制表达式 |
| 源 FETCH 基准完成 | 2026-09-13T18:25:31.614015+00:00 |
| 目标导入开始 | 2026-09-13T18:25:31.614020+00:00 |
| 独立 verification | MATCH，双方 COMPLETE，无 ERROR / MISSING |
| SHOW STATS | 两端新统计均符合夹具预期 |
| console FETCH | 6 条固定查询的结果表一致 |
| scan limit | 113 |
| 完成时间 | 2026-09-13T18:26:17.281332+00:00 |

代表值分配（每组场景合计至少 1,000 条，每种值的次数如下）：

```json
{
  "0": 167,
  "1": 167,
  "2": 167,
  "3": 167,
  "4": 166,
  "5": 166
}
```

完整步骤、错误码、统计明细、快照清单和 JAR 摘要见 [date_values_i.json](date_values_i.json)。

全部原始 CSV、计划、快照、查询及日志见 [证据归档](../../artifacts/final/date_values_i.tar.gz)。

归档 SHA-256：`88f921e029bf0f6b5b2b288e11d66a50b1c9d8155a0c95d5ad5739045f27b885`。
