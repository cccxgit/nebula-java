# not_null_defaults_s

NOT NULL属性省略写入时使用确定性DEFAULT，搬迁后原值和Schema一致

| 项目 | 实际结果 |
|---|---|
| 验收状态 | PASSED；证据审计 通过 |
| VID 类型 | `FIXED_STRING(256)` |
| 源空间 | `qa_20260914_s_not_null_defaults` |
| 目标空间 | `qa_20260914_s_not_null_defaults_target_final` |
| 预期点 / 边 | 1000 / 1000 |
| 源预制值核对 | 2000 条完整记录的原生属性、完整键及 Schema 符合预制表达式 |
| 源 FETCH 基准完成 | 2026-09-13T18:34:20.391451+00:00 |
| 目标导入开始 | 2026-09-13T18:34:20.391454+00:00 |
| 独立 verification | MATCH，双方 COMPLETE，无 ERROR / MISSING |
| SHOW STATS | 两端新统计均符合夹具预期 |
| console FETCH | 6 条固定查询的结果表一致 |
| scan limit | 113 |
| 完成时间 | 2026-09-13T18:35:06.593942+00:00 |

代表值分配（每组场景合计至少 1,000 条，每种值的次数如下）：

```json
{
  "all_properties_omitted": 1000
}
```

完整步骤、错误码、统计明细、快照清单和 JAR 摘要见 [not_null_defaults_s.json](not_null_defaults_s.json)。

全部原始 CSV、计划、快照、查询及日志见 [证据归档](../../artifacts/final/not_null_defaults_s.tar.gz)。

归档 SHA-256：`8cade842cc395ab8662abd50441df04e5c0886da4e83b4610fbb6f5e6b814923`。
