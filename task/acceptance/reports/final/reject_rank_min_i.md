# reject_rank_min_i

当前服务端rank语法不接受Long.MIN_VALUE，每条请求必须拒绝

| 项目 | 实际结果 |
|---|---|
| 验收状态 | PASSED；证据审计 通过 |
| VID 类型 | `INT64` |
| 源空间 | `qa_20260914_i_reject_rank_min` |
| 目标空间 | `qa_20260914_i_reject_rank_min_target_final` |
| 预期点 / 边 | 1000 / 1000 |
| 源预制值核对 | 2000 条完整记录的原生属性、完整键及 Schema 符合预制表达式 |
| 源 FETCH 基准完成 | 2026-09-13T18:39:21.270940+00:00 |
| 目标导入开始 | 2026-09-13T18:39:21.270945+00:00 |
| 独立 verification | MATCH，双方 COMPLETE，无 ERROR / MISSING |
| SHOW STATS | 两端新统计均符合夹具预期 |
| console FETCH | 6 条固定查询的结果表一致 |
| scan limit | 113 |
| 完成时间 | 2026-09-13T18:40:06.882603+00:00 |

代表值分配（每组场景合计至少 1,000 条，每种值的次数如下）：

```json
{
  "baseline": 1000
}
```

另外执行 1000 次非法输入，均被拒绝；有效对照内容和数量均未改变。

完整步骤、错误码、统计明细、快照清单和 JAR 摘要见 [reject_rank_min_i.json](reject_rank_min_i.json)。

全部原始 CSV、计划、快照、查询及日志见 [证据归档](../../artifacts/final/reject_rank_min_i.tar.gz)。

归档 SHA-256：`eb63b549c19c89e4a6eea005fbcc61953f53307ce7feca0d65b4339ac8114ac4`。
