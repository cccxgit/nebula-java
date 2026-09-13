# string_special_i

STRING所有特殊字符类别且不做Unicode规范化

| 项目 | 实际结果 |
|---|---|
| 验收状态 | PASSED；证据审计 通过 |
| VID 类型 | `INT64` |
| 源空间 | `qa_20260914_i_string_special` |
| 目标空间 | `qa_20260914_i_string_special_target` |
| 预期点 / 边 | 1000 / 1000 |
| 源预制值核对 | 2000 条完整记录的原生属性、完整键及 Schema 符合预制表达式 |
| 源 FETCH 基准完成 | 2026-09-13T17:40:30.178842+00:00 |
| 目标导入开始 | 2026-09-13T17:40:30.178847+00:00 |
| 独立 verification | MATCH，双方 COMPLETE，无 ERROR / MISSING |
| SHOW STATS | 两端新统计均符合夹具预期 |
| console FETCH | 6 条固定查询的结果表一致 |
| scan limit | 113 |
| 完成时间 | 2026-09-13T17:41:15.903834+00:00 |

代表值分配（每组场景合计至少 1,000 条，每种值的次数如下）：

```json
{
  "quotes": 56,
  "backslash": 56,
  "literal_escapes": 56,
  "comma": 56,
  "CR": 56,
  "LF": 56,
  "CRLF": 56,
  "TAB": 56,
  "NUL": 56,
  "embedded_NUL": 56,
  "trailing_NUL": 55,
  "NFC": 55,
  "NFD": 55,
  "emoji_ZWJ": 55,
  "invisible": 55,
  "whitespace": 55,
  "csv_formula": 55,
  "mixed": 55
}
```

完整步骤、错误码、统计明细、快照清单和 JAR 摘要见 [string_special_i.json](string_special_i.json)。

全部原始 CSV、计划、快照、查询及日志见 [证据归档](../artifacts/string_special_i.tar.gz)。

归档 SHA-256：`2615ca0af57af22d16cf22f8376f71df1feff1ef9d8798e97aa782505a1d0de0`。
