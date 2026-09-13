# fixed_string_capacity_i

FIXED_STRING(128)空、1、127、128字节及恰好128字节UTF-8

| 项目 | 实际结果 |
|---|---|
| 验收状态 | PASSED；证据审计 通过 |
| VID 类型 | `INT64` |
| 源空间 | `qa_20260914_i_fixed_string_capacity` |
| 目标空间 | `qa_20260914_i_fixed_string_capacity_target` |
| 预期点 / 边 | 1000 / 1000 |
| 源预制值核对 | 2000 条完整记录的原生属性、完整键及 Schema 符合预制表达式 |
| 源 FETCH 基准完成 | 2026-09-13T17:45:09.469743+00:00 |
| 目标导入开始 | 2026-09-13T17:45:09.469748+00:00 |
| 独立 verification | MATCH，双方 COMPLETE，无 ERROR / MISSING |
| SHOW STATS | 两端新统计均符合夹具预期 |
| console FETCH | 6 条固定查询的结果表一致 |
| scan limit | 113 |
| 完成时间 | 2026-09-13T17:45:55.034296+00:00 |

代表值分配（每组场景合计至少 1,000 条，每种值的次数如下）：

```json
{
  "0_bytes": 200,
  "1_byte": 200,
  "127_bytes": 200,
  "128_ascii_bytes": 200,
  "128_utf8_bytes": 200
}
```

完整步骤、错误码、统计明细、快照清单和 JAR 摘要见 [fixed_string_capacity_i.json](fixed_string_capacity_i.json)。

全部原始 CSV、计划、快照、查询及日志见 [证据归档](../artifacts/fixed_string_capacity_i.tar.gz)。

归档 SHA-256：`2745c3a8c9a44302ab03226fe37c9b5e0f4b4cc95ff7c9db67bb6c2e855b96be`。
