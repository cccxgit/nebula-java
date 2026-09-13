# geography_polygon_s

POLYGON内环、方向归一化、日期线和高精度；以源原生读取值为比较基准

| 项目 | 实际结果 |
|---|---|
| 验收状态 | PASSED；证据审计 通过 |
| VID 类型 | `FIXED_STRING(256)` |
| 源空间 | `qa_20260914_s_geography_polygon` |
| 目标空间 | `qa_20260914_s_geography_polygon_target` |
| 预期点 / 边 | 1000 / 1000 |
| 源预制值核对 | 2000 条完整记录的原生属性、完整键及 Schema 符合预制表达式 |
| 源 FETCH 基准完成 | 2026-09-13T17:49:29.198855+00:00 |
| 目标导入开始 | 2026-09-13T17:49:29.198862+00:00 |
| 独立 verification | MATCH，双方 COMPLETE，无 ERROR / MISSING |
| SHOW STATS | 两端新统计均符合夹具预期 |
| console FETCH | 6 条固定查询的结果表一致 |
| scan limit | 113 |
| 完成时间 | 2026-09-13T17:50:15.841621+00:00 |

代表值分配（每组场景合计至少 1,000 条，每种值的次数如下）：

```json
{
  "POLYGON((0 0,2 0,2 2,0 0))": 200,
  "POLYGON((0 0,0 2,2 2,2 0,0 0))": 200,
  "POLYGON((0 0,10 0,10 10,0 10,0 0),(2 2,2 4,4 4,4 2,2 2))": 200,
  "POLYGON((179 -1,-179 -1,-179 1,179 1,179 -1))": 200,
  "POLYGON((0.000000000000001 0,1.000000000000001 0,1 1,0.000000000000001 0))": 200
}
```

完整步骤、错误码、统计明细、快照清单和 JAR 摘要见 [geography_polygon_s.json](geography_polygon_s.json)。

全部原始 CSV、计划、快照、查询及日志见 [证据归档](../artifacts/geography_polygon_s.tar.gz)。

归档 SHA-256：`fefff333f988c63239d2b7043770b468e7b5db4b369658dacbefb244aa87cf29`。
