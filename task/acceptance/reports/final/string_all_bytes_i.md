# string_all_bytes_i

每条STRING均包含全部256字节，包含NUL及不可解码字节

| 项目 | 实际结果 |
|---|---|
| 验收状态 | PASSED；证据审计 通过 |
| VID 类型 | `INT64` |
| 源空间 | `qa_20260914_i_string_all_bytes` |
| 目标空间 | `qa_20260914_i_string_all_bytes_target_final` |
| 预期点 / 边 | 1000 / 1000 |
| 源预制值核对 | 2000 条完整记录的原生属性、完整键及 Schema 符合预制表达式 |
| 源 FETCH 基准完成 | 2026-09-13T18:20:48.017894+00:00 |
| 目标导入开始 | 2026-09-13T18:20:48.017896+00:00 |
| 独立 verification | MATCH，双方 COMPLETE，无 ERROR / MISSING |
| SHOW STATS | 两端新统计均符合夹具预期 |
| console FETCH | 6 条固定查询的结果表一致 |
| scan limit | 113 |
| 完成时间 | 2026-09-13T18:21:33.863331+00:00 |

代表值分配（每组场景合计至少 1,000 条，每种值的次数如下）：

```json
{
  "all_256_byte_values": 1000
}
```

完整步骤、错误码、统计明细、快照清单和 JAR 摘要见 [string_all_bytes_i.json](string_all_bytes_i.json)。

全部原始 CSV、计划、快照、查询及日志见 [证据归档](../../artifacts/final/string_all_bytes_i.tar.gz)。

归档 SHA-256：`2d4cd6b2f982566332a20fcfaf90545db6619443bd41319c29bf30bbfe73d3fc`。
