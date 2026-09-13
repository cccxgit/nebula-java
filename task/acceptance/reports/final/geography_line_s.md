# geography_line_s

LINESTRING多点、日期线和高精度；以源原生读取值为比较基准

| 项目 | 实际结果 |
|---|---|
| 验收状态 | PASSED；证据审计 通过 |
| VID 类型 | `FIXED_STRING(256)` |
| 源空间 | `qa_20260914_s_geography_line` |
| 目标空间 | `qa_20260914_s_geography_line_target_final` |
| 预期点 / 边 | 1000 / 1000 |
| 源预制值核对 | 2000 条完整记录的原生属性、完整键及 Schema 符合预制表达式 |
| 源 FETCH 基准完成 | 2026-09-13T18:28:56.538829+00:00 |
| 目标导入开始 | 2026-09-13T18:28:56.538831+00:00 |
| 独立 verification | MATCH，双方 COMPLETE，无 ERROR / MISSING |
| SHOW STATS | 两端新统计均符合夹具预期 |
| console FETCH | 6 条固定查询的结果表一致 |
| scan limit | 113 |
| 完成时间 | 2026-09-13T18:29:44.573608+00:00 |

代表值分配（每组场景合计至少 1,000 条，每种值的次数如下）：

```json
{
  "LINESTRING(0 0,1 1)": 200,
  "LINESTRING(179 0,-179 0)": 200,
  "LINESTRING(-180 -80,-90 -20,0 0,90 20,180 80)": 200,
  "LINESTRING(-0 -0,0.000000000000001 0.000000000000001,1 1)": 200,
  "LINESTRING(121.47370123456789 31.23040123456789,121.57370123456789 31.33040123456789)": 200
}
```

完整步骤、错误码、统计明细、快照清单和 JAR 摘要见 [geography_line_s.json](geography_line_s.json)。

全部原始 CSV、计划、快照、查询及日志见 [证据归档](../../artifacts/final/geography_line_s.tar.gz)。

归档 SHA-256：`035d514a54bc86881148115c97a4406c01edca50b7d161dd1124e77cb6ca34fc`。
