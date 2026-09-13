# double_signed_zero_s

DOUBLE正零和负零按IEEE754校验

| 项目 | 实际结果 |
|---|---|
| 验收状态 | PASSED；证据审计 通过 |
| VID 类型 | `FIXED_STRING(256)` |
| 源空间 | `qa_20260914_s_double_signed_zero` |
| 目标空间 | `qa_20260914_s_double_signed_zero_target` |
| 预期点 / 边 | 1000 / 1000 |
| 源预制值核对 | 2000 条完整记录的原生属性、完整键及 Schema 符合预制表达式 |
| 源 FETCH 基准完成 | 2026-09-13T17:38:46.972400+00:00 |
| 目标导入开始 | 2026-09-13T17:38:46.972406+00:00 |
| 独立 verification | MATCH，双方 COMPLETE，无 ERROR / MISSING |
| SHOW STATS | 两端新统计均符合夹具预期 |
| console FETCH | 6 条固定查询的结果表一致 |
| scan limit | 113 |
| 完成时间 | 2026-09-13T17:39:32.636716+00:00 |

代表值分配（每组场景合计至少 1,000 条，每种值的次数如下）：

```json
{
  "positive_zero": 500,
  "negative_zero": 500
}
```

完整步骤、错误码、统计明细、快照清单和 JAR 摘要见 [double_signed_zero_s.json](double_signed_zero_s.json)。

全部原始 CSV、计划、快照、查询及日志见 [证据归档](../artifacts/double_signed_zero_s.tar.gz)。

归档 SHA-256：`5da01269c26776b632f2d3eb978c3070f8f540d7165ff1b43e6433de38a7a431`。
