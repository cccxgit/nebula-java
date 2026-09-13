# fixed_string_special_s

FIXED_STRING保留特殊字符，不含源接口不支持的NUL

| 项目 | 实际结果 |
|---|---|
| 验收状态 | PASSED；证据审计 通过 |
| VID 类型 | `FIXED_STRING(256)` |
| 源空间 | `qa_20260914_s_fixed_string_special` |
| 目标空间 | `qa_20260914_s_fixed_string_special_target` |
| 预期点 / 边 | 1000 / 1000 |
| 源预制值核对 | 2000 条完整记录的原生属性、完整键及 Schema 符合预制表达式 |
| 源 FETCH 基准完成 | 2026-09-13T17:44:17.011576+00:00 |
| 目标导入开始 | 2026-09-13T17:44:17.011580+00:00 |
| 独立 verification | MATCH，双方 COMPLETE，无 ERROR / MISSING |
| SHOW STATS | 两端新统计均符合夹具预期 |
| console FETCH | 6 条固定查询的结果表一致 |
| scan limit | 113 |
| 完成时间 | 2026-09-13T17:45:02.648418+00:00 |

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

完整步骤、错误码、统计明细、快照清单和 JAR 摘要见 [fixed_string_special_s.json](fixed_string_special_s.json)。

全部原始 CSV、计划、快照、查询及日志见 [证据归档](../artifacts/fixed_string_special_s.tar.gz)。

归档 SHA-256：`16edd50892fa2aff7a60e9800873e75f18a5a344c024ce95a9d26a4d9c25c94c`。
