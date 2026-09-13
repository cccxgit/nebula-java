# fixed_string_special_i

FIXED_STRING保留特殊字符，不含源接口不支持的NUL

| 项目 | 实际结果 |
|---|---|
| 验收状态 | PASSED；证据审计 通过 |
| VID 类型 | `INT64` |
| 源空间 | `qa_20260914_i_fixed_string_special` |
| 目标空间 | `qa_20260914_i_fixed_string_special_target_final` |
| 预期点 / 边 | 1000 / 1000 |
| 源预制值核对 | 2000 条完整记录的原生属性、完整键及 Schema 符合预制表达式 |
| 源 FETCH 基准完成 | 2026-09-13T18:23:42.995738+00:00 |
| 目标导入开始 | 2026-09-13T18:23:42.995751+00:00 |
| 独立 verification | MATCH，双方 COMPLETE，无 ERROR / MISSING |
| SHOW STATS | 两端新统计均符合夹具预期 |
| console FETCH | 6 条固定查询的结果表一致 |
| scan limit | 113 |
| 完成时间 | 2026-09-13T18:24:29.170675+00:00 |

代表值分配（每组场景合计至少 1,000 条，每种值的次数如下）：

```json
{
  "quotes": 72,
  "backslash": 72,
  "literal_escapes": 72,
  "comma": 72,
  "CR": 72,
  "LF": 72,
  "CRLF": 71,
  "TAB": 71,
  "NFC": 71,
  "NFD": 71,
  "emoji_ZWJ": 71,
  "invisible": 71,
  "whitespace": 71,
  "csv_formula": 71
}
```

完整步骤、错误码、统计明细、快照清单和 JAR 摘要见 [fixed_string_special_i.json](fixed_string_special_i.json)。

全部原始 CSV、计划、快照、查询及日志见 [证据归档](../../artifacts/final/fixed_string_special_i.tar.gz)。

归档 SHA-256：`ae2735893ccb27fddf7c3af0bd889ee49c13c2bd574ac5d7cc611451755a9228`。
