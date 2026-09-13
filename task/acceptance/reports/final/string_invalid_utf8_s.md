# string_invalid_utf8_s

无效UTF-8、截断序列、过长编码及代理区字节

| 项目 | 实际结果 |
|---|---|
| 验收状态 | PASSED；证据审计 通过 |
| VID 类型 | `FIXED_STRING(256)` |
| 源空间 | `qa_20260914_s_string_invalid_utf8` |
| 目标空间 | `qa_20260914_s_string_invalid_utf8_target_final` |
| 预期点 / 边 | 1000 / 1000 |
| 源预制值核对 | 2000 条完整记录的原生属性、完整键及 Schema 符合预制表达式 |
| 源 FETCH 基准完成 | 2026-09-13T18:20:45.803364+00:00 |
| 目标导入开始 | 2026-09-13T18:20:45.803369+00:00 |
| 独立 verification | MATCH，双方 COMPLETE，无 ERROR / MISSING |
| SHOW STATS | 两端新统计均符合夹具预期 |
| console FETCH | 6 条固定查询的结果表一致 |
| scan limit | 113 |
| 完成时间 | 2026-09-13T18:21:32.179205+00:00 |

代表值分配（每组场景合计至少 1,000 条，每种值的次数如下）：

```json
{
  "fffe": 143,
  "c0af": 143,
  "e282": 143,
  "eda080": 143,
  "f4908080": 143,
  "80": 143,
  "61ff0062": 142
}
```

完整步骤、错误码、统计明细、快照清单和 JAR 摘要见 [string_invalid_utf8_s.json](string_invalid_utf8_s.json)。

全部原始 CSV、计划、快照、查询及日志见 [证据归档](../../artifacts/final/string_invalid_utf8_s.tar.gz)。

归档 SHA-256：`34d9fb418dfd82597da9b9836f52dc02c826c9980184537e60c01c674a32216e`。
