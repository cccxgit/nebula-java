# geography_point_s

POINT经纬度极限、负零和高精度；以源原生读取值为比较基准

| 项目 | 实际结果 |
|---|---|
| 验收状态 | PASSED；证据审计 通过 |
| VID 类型 | `FIXED_STRING(256)` |
| 源空间 | `qa_20260914_s_geography_point` |
| 目标空间 | `qa_20260914_s_geography_point_target_final` |
| 预期点 / 边 | 1000 / 1000 |
| 源预制值核对 | 2000 条完整记录的原生属性、完整键及 Schema 符合预制表达式 |
| 源 FETCH 基准完成 | 2026-09-13T18:29:08.349361+00:00 |
| 目标导入开始 | 2026-09-13T18:29:08.349365+00:00 |
| 独立 verification | MATCH，双方 COMPLETE，无 ERROR / MISSING |
| SHOW STATS | 两端新统计均符合夹具预期 |
| console FETCH | 6 条固定查询的结果表一致 |
| scan limit | 113 |
| 完成时间 | 2026-09-13T18:29:54.417258+00:00 |

代表值分配（每组场景合计至少 1,000 条，每种值的次数如下）：

```json
{
  "POINT(0 0)": 112,
  "POINT(-0 -0)": 111,
  "POINT(180 90)": 111,
  "POINT(-180 -90)": 111,
  "POINT(179.99999999999997 0.000000000000001)": 111,
  "POINT(-179.99999999999997 -0.000000000000001)": 111,
  "POINT(121.47370123456789 31.23040123456789)": 111,
  "POINT(0 90)": 111,
  "POINT(0 -90)": 111
}
```

完整步骤、错误码、统计明细、快照清单和 JAR 摘要见 [geography_point_s.json](geography_point_s.json)。

全部原始 CSV、计划、快照、查询及日志见 [证据归档](../../artifacts/final/geography_point_s.tar.gz)。

归档 SHA-256：`2ecfabe523ae6813160335ee13df2838219bc7eebc988ca43a0ccc4a554b06b8`。
