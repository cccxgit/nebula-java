# reject_vid_domain_i

字符串VID超过256字节或整数VID超出INT64，每次写入均应拒绝

| 项目 | 实际结果 |
|---|---|
| 验收状态 | PASSED；证据审计 通过 |
| VID 类型 | `INT64` |
| 源空间 | `qa_20260914_i_reject_vid_domain` |
| 目标空间 | `qa_20260914_i_reject_vid_domain_target` |
| 预期点 / 边 | 1000 / 1000 |
| 源预制值核对 | 2000 条完整记录的原生属性、完整键及 Schema 符合预制表达式 |
| 源 FETCH 基准完成 | 2026-09-13T17:58:43.071117+00:00 |
| 目标导入开始 | 2026-09-13T17:58:43.071121+00:00 |
| 独立 verification | MATCH，双方 COMPLETE，无 ERROR / MISSING |
| SHOW STATS | 两端新统计均符合夹具预期 |
| console FETCH | 6 条固定查询的结果表一致 |
| scan limit | 113 |
| 完成时间 | 2026-09-13T17:59:29.003012+00:00 |

代表值分配（每组场景合计至少 1,000 条，每种值的次数如下）：

```json
{
  "baseline": 1000
}
```

另外执行 1000 次非法输入，均被拒绝；有效对照内容和数量均未改变。

完整步骤、错误码、统计明细、快照清单和 JAR 摘要见 [reject_vid_domain_i.json](reject_vid_domain_i.json)。

全部原始 CSV、计划、快照、查询及日志见 [证据归档](../artifacts/reject_vid_domain_i.tar.gz)。

归档 SHA-256：`d79919f65079e74dee1364b36d97a6250a3f94809da5472cce4d17827fd86a07`。
