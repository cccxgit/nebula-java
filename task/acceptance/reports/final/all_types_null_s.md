# all_types_null_s

所有持久化属性同时为数据库NULL，含全部地理形状约束

| 项目 | 实际结果 |
|---|---|
| 验收状态 | PASSED；证据审计 通过 |
| VID 类型 | `FIXED_STRING(256)` |
| 源空间 | `qa_20260914_s_all_types_null` |
| 目标空间 | `qa_20260914_s_all_types_null_target_final` |
| 预期点 / 边 | 1000 / 1000 |
| 源预制值核对 | 2000 条完整记录的原生属性、完整键及 Schema 符合预制表达式 |
| 源 FETCH 基准完成 | 2026-09-13T18:24:37.418930+00:00 |
| 目标导入开始 | 2026-09-13T18:24:37.418935+00:00 |
| 独立 verification | MATCH，双方 COMPLETE，无 ERROR / MISSING |
| SHOW STATS | 两端新统计均符合夹具预期 |
| console FETCH | 6 条固定查询的结果表一致 |
| scan limit | 113 |
| 完成时间 | 2026-09-13T18:25:24.469102+00:00 |

代表值分配（每组场景合计至少 1,000 条，每种值的次数如下）：

```json
{
  "all_null": 1000
}
```

完整步骤、错误码、统计明细、快照清单和 JAR 摘要见 [all_types_null_s.json](all_types_null_s.json)。

全部原始 CSV、计划、快照、查询及日志见 [证据归档](../../artifacts/final/all_types_null_s.tar.gz)。

归档 SHA-256：`3358d7b33739ef6120b8e155c1f9b05847d6297893dfd17849f86814b9c69446`。
