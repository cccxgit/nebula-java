# string_long_i

每条64KiB长STRING，以lpad服务端生成ASCII、UTF-8或全字节混合

| 项目 | 实际结果 |
|---|---|
| 验收状态 | PASSED；证据审计 通过 |
| VID 类型 | `INT64` |
| 源空间 | `qa_20260914_i_string_long` |
| 目标空间 | `qa_20260914_i_string_long_target` |
| 预期点 / 边 | 1000 / 1000 |
| 源预制值核对 | 2000 条完整记录的原生属性、完整键及 Schema 符合预制表达式 |
| 源 FETCH 基准完成 | 2026-09-13T17:42:21.670839+00:00 |
| 目标导入开始 | 2026-09-13T17:42:21.670841+00:00 |
| 独立 verification | MATCH，双方 COMPLETE，无 ERROR / MISSING |
| SHOW STATS | 两端新统计均符合夹具预期 |
| console FETCH | 6 条固定查询的结果表一致 |
| scan limit | 113 |
| 完成时间 | 2026-09-13T17:43:16.956996+00:00 |

代表值分配（每组场景合计至少 1,000 条，每种值的次数如下）：

```json
{
  "ascii_64KiB": 334,
  "utf8_64KiB": 333,
  "all_bytes_64KiB": 333
}
```

完整步骤、错误码、统计明细、快照清单和 JAR 摘要见 [string_long_i.json](string_long_i.json)。

全部原始 CSV、计划、快照、查询及日志见 [证据归档](../artifacts/string_long_i.tar.gz)。

归档 SHA-256：`f64b8c39e70ceca7e63b52d55175ec6bfc4667f95a93b14e04c268db2445ba89`。
