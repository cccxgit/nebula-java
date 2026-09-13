# string_literal_null_i

数据库NULL、空串、字面NULL/N/V:之间不混淆

| 项目 | 实际结果 |
|---|---|
| 验收状态 | PASSED；证据审计 通过 |
| VID 类型 | `INT64` |
| 源空间 | `qa_20260914_i_string_literal_null` |
| 目标空间 | `qa_20260914_i_string_literal_null_target_final` |
| 预期点 / 边 | 1000 / 1000 |
| 源预制值核对 | 2000 条完整记录的原生属性、完整键及 Schema 符合预制表达式 |
| 源 FETCH 基准完成 | 2026-09-13T18:22:35.869406+00:00 |
| 目标导入开始 | 2026-09-13T18:22:35.869411+00:00 |
| 独立 verification | MATCH，双方 COMPLETE，无 ERROR / MISSING |
| SHOW STATS | 两端新统计均符合夹具预期 |
| console FETCH | 6 条固定查询的结果表一致 |
| scan limit | 113 |
| 完成时间 | 2026-09-13T18:23:23.250222+00:00 |

代表值分配（每组场景合计至少 1,000 条，每种值的次数如下）：

```json
{
  "database_null": 200,
  "empty": 200,
  "literal_NULL": 200,
  "literal_N": 200,
  "literal_V_prefix": 200
}
```

完整步骤、错误码、统计明细、快照清单和 JAR 摘要见 [string_literal_null_i.json](string_literal_null_i.json)。

全部原始 CSV、计划、快照、查询及日志见 [证据归档](../../artifacts/final/string_literal_null_i.tar.gz)。

归档 SHA-256：`495a7116695b5f05f10b5856f63ff7414e58614de1935ca0a7a22b2204d43903`。
