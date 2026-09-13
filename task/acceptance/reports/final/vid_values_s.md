# vid_values_s

VID全范围代表值：整数上下界或空串、1..256字节、1..255单字节、特殊字符及无效UTF-8

| 项目 | 实际结果 |
|---|---|
| 验收状态 | PASSED；证据审计 通过 |
| VID 类型 | `FIXED_STRING(256)` |
| 源空间 | `qa_20260914_s_vid_values` |
| 目标空间 | `qa_20260914_s_vid_values_target_final` |
| 预期点 / 边 | 1000 / 1000 |
| 源预制值核对 | 2000 条完整记录的原生属性、完整键及 Schema 符合预制表达式 |
| 源 FETCH 基准完成 | 2026-09-13T18:12:50.632942+00:00 |
| 目标导入开始 | 2026-09-13T18:12:50.632946+00:00 |
| 独立 verification | MATCH，双方 COMPLETE，无 ERROR / MISSING |
| SHOW STATS | 两端新统计均符合夹具预期 |
| console FETCH | 6 条固定查询的结果表一致 |
| scan limit | 113 |
| 完成时间 | 2026-09-13T18:13:36.864263+00:00 |

代表值分配（每组场景合计至少 1,000 条，每种值的次数如下）：

```json
{
  "baseline": 1000
}
```

完整步骤、错误码、统计明细、快照清单和 JAR 摘要见 [vid_values_s.json](vid_values_s.json)。

全部原始 CSV、计划、快照、查询及日志见 [证据归档](../../artifacts/final/vid_values_s.tar.gz)。

归档 SHA-256：`d26f558efd5ca80fd91ea585ee124445e08d0504994f42224151640b3b9619b2`。
