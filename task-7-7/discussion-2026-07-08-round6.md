# 2026-07-08 第六轮需求确认与最终剩余 GAP

## 1. 本轮新增确认

### 1.1 root 用户处理

已确认采用方案 A：

1. root 导出到报告。
2. 导入时不修改目标 root。
3. 不创建 root。
4. 不修改 root 密码。
5. 不修改 root 权限。
6. 目标集群初始状态要求仅存在 root 用户。

设计含义：

1. root 作为审计信息进入 manifest/report。
2. root 不参与目标集群用户变更。
3. 目标 root 账号状态以目标集群初始化结果为准。

### 1.2 导入幂等语义

已确认：

1. 接受“同 key 同值可覆盖”。
2. 依赖目标空集群规避同 key 不同值冲突。
3. 导入中断后恢复时，已经成功写入的数据如果再次执行 `INSERT VERTEX/EDGE`，可以接受。

设计含义：

1. 不额外做导入前逐 key 比对。
2. 不额外做同 key 不同值冲突检测。
3. 目标集群 precheck 必须确保无业务 space、无业务用户。

### 1.3 权限迁移范围

已确认：

1. 仅强保证非 root 用户的 space 级角色授权。
2. 其他商业版扩展权限告警并写入报告。

设计含义：

1. 非 root 用户需要迁移 space 级角色授权。
2. root 权限只进入报告，不导入目标。
3. 对无法完整导出的扩展权限，不阻断迁移。
4. 报告中需要明确列出未迁移的扩展权限项。

### 1.4 默认参数

已确认采用以下默认值：

```text
import.batch.vertices = 500
import.batch.edges = 500
import.retry.max-attempts = 3
import.retry.backoff.ms = 1000
export.scan.limit = 1000
export.concurrent.partitions = 8
export.concurrent.labels = 1
```

设计含义：

1. 导入默认每批 500 条 vertex 或 edge。
2. 导入失败默认最多重试 3 次。
3. 重试退避默认 1000ms。
4. 每次 storage scan 默认 limit 为 1000。
5. 同一 tag/edge 下默认最多 8 个 partition 并发导出。
6. 默认同一时刻只导出 1 个 tag/edge。

### 1.5 配置文件格式

已确认：

```text
Java .properties
```

设计含义：

1. 主任务配置使用 `.properties`。
2. log4j 也使用 properties 风格配置。
3. 不引入 YAML 解析依赖。

## 2. 当前唯一明确剩余 GAP

### 2.1 partition 布局恢复语义

已确认采纳如下语义：

1. 每个 `space + tag/edge + partition` 一个文件。
2. 每个 partition 文件独立生成 `.sha256` 和 `.sig`。
3. 恢复时已签名 partition 文件跳过。
4. 未签名 partition 文件整体重导。

## 3. 需求对齐状态

当前需求讨论已无明确阻塞 GAP，可以进入正式设计方案定稿阶段。

设计方案应覆盖：

1. 需求边界与非目标。
2. 总体架构。
3. 数据静止和迁移流程。
4. meta/schema/user/role/permission 搬迁。
5. vertex/edge 导出。
6. CSV/typed JSON 编码。
7. label/partition 文件布局。
8. checksum/signature/manifest/report。
9. 导入和失败重试。
10. 校验策略。
11. TTL 风险处理。
12. 断点续导/续导入。
13. 参数设计。
14. 测试方案。
