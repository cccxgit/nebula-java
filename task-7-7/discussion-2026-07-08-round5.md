# 2026-07-08 第五轮需求确认与剩余 GAP

## 1. 本轮新增确认

### 1.1 默认 HMAC 语义

已确认：

1. 默认 HMAC 使用固定内置密钥。
2. 设计文档需要声明：默认 HMAC 不作为安全防篡改能力。
3. 默认 HMAC 主要用于标识文件导出完成，防止误用未完成文件。
4. 文件完整性校验以 `SHA-256` checksum 为主。

### 1.2 label 布局并发写入方式

已确认：

1. `label` 布局采用多 partition 并发 scan。
2. 同一个 tag/edge 文件采用单 writer 串行写入。
3. 文件行顺序不作为一致性标准。
4. `label` 布局 checkpoint 粒度为 tag/edge 文件级。
5. `label` 文件无签名时整体重导。

### 1.3 CSV 编码

已确认：

1. CSV 使用 RFC4180 风格转义。
2. `NULL` 使用 `\N` 表示。
3. 空字符串使用 `""` 表示。
4. 逗号、双引号、换行字段使用双引号包裹。
5. 字段内双引号写作两个双引号。
6. 列类型、列顺序、VID 类型写入 schema metadata，不只依赖 CSV header。

### 1.4 JSON 编码

已确认：

1. JSON 采用 typed JSON。
2. JSON 字段显式保存 `type/value`，避免类型歧义。

示例：

```json
{
  "vid": {"type": "string", "value": "player100"},
  "props": {
    "name": {"type": "string", "value": "Tim Duncan"},
    "age": {"type": "int", "value": 42},
    "memo": {"type": "null", "value": null}
  }
}
```

### 1.5 导入批量失败重试

已确认：

1. 批量 `INSERT VERTEX/EDGE` 失败后，降级到单行重试。
2. 单行仍失败后，写入失败记录文件。
3. 失败记录建议包含：
   - 原始文件路径
   - 行号
   - vertex/edge key
   - 原始行文本
   - 生成的 nGQL
   - 错误码
   - 错误消息
   - 重试次数

### 1.6 schema/index 失败后的目标集群清理责任

已确认：

1. schema/index 创建失败后，导入脚本退出。
2. 要求用户手动清理目标集群。
3. 工具不自动执行 `DROP SPACE`、`DROP USER` 等破坏性操作。

### 1.7 root 用户处理

本轮答复为：

```text
不跳过
```

当前理解：

1. root 用户不应被简单跳过。
2. root 相关信息仍需要进入迁移处理范围。

但该结论与前一轮已确认的“目标集群初始状态要求仅 root 用户，root 用户不做处理”存在语义冲突，需要继续澄清。

## 2. 当前仍需确认的 GAP

### 2.1 root 用户处理语义冲突

此前已确认：

1. 目标集群初始状态要求仅存在 `root` 用户。
2. `root` 用户不做处理。
3. 密码无需导出，目标用户使用默认密码 `nebula`。

本轮又确认：

1. root 不跳过。

需要明确“root 不跳过”的具体含义。

候选方案：

#### 方案 A：root 导出到报告，但导入不修改目标 root

1. 导出源集群 root 用户信息。
2. 在 manifest/report 中记录 root 存在。
3. 导入时不创建 root、不修改 root 密码、不修改 root 权限。
4. 目标 root 保持目标集群现状。

#### 方案 B：root 参与完整迁移，但不处理密码

1. 导出源集群 root 权限。
2. 导入时不创建 root，因为目标已有 root。
3. 不修改 root 密码。
4. 需要尝试同步 root 的角色/权限。

#### 方案 C：root 完整跳过

1. root 不导出。
2. root 不导入。
3. root 不改密。
4. root 不改权限。

当前用户答复排除了方案 C，但方案 A 和方案 B 仍需选择。

建议：

```text
采用方案 A：root 导出到报告，但导入不修改目标 root。
```

理由：

1. 避免破坏目标集群管理员账号。
2. 保留审计信息。
3. 与“目标集群初始状态仅 root”兼容。

### 2.2 导入幂等语义

此前已建议：

1. 重复导入同一份导出文件时，允许同 key 同值覆盖。
2. 目标集群 precheck 要求无业务数据，不处理“同 key 不同值”的业务冲突。
3. 导入中断后恢复，已成功导入的数据再次执行 `INSERT` 可以接受。

仍需确认是否接受。

建议：

```text
接受上述语义。
```

### 2.3 partition 布局恢复语义

需要确认：

1. 每个 `space + tag/edge + partition` 一个文件。
2. 每个 partition 文件独立生成 `sha256` 和 `sig`。
3. 恢复时已签名 partition 文件跳过。
4. 未签名 partition 文件整体重导。

建议：

```text
接受上述语义。
```

### 2.4 权限迁移范围

此前确认用户、角色、权限都需要搬迁。当前还需明确具体范围。

需要确认：

1. 是否迁移非 root 用户的 space 级角色授权？
2. 是否迁移商业版用户 privilege、IP whitelist、资源限制等扩展权限？
3. 如果某类权限无法通过 nebula-java 或 nGQL 完整导出，是报错、告警，还是跳过？

建议：

```text
迁移非 root 用户的 space 级角色授权；无法完整导出的商业版扩展权限先告警并写入报告。
```

如果 root 采用方案 A，则 root 权限只记录到报告，不同步到目标。

### 2.5 导入批大小和重试参数默认值

当前只确认了批量失败后降级单行重试，但还需要默认参数。

建议默认值：

```text
import.batch.vertices = 500
import.batch.edges = 500
import.retry.max-attempts = 3
import.retry.backoff.ms = 1000
```

需要确认是否接受。

### 2.6 导出 scan batch size 和并发默认值

需要确定默认导出参数。

建议默认值：

```text
export.scan.limit = 1000
export.concurrent.partitions = 8
export.concurrent.labels = 1
```

说明：

1. `export.scan.limit` 对应每次 storage scan 返回行数。
2. `export.concurrent.partitions` 控制同一 tag/edge 下 partition 并发数。
3. `export.concurrent.labels` 默认 1，避免多个 tag/edge 同时导出导致源集群压力过大。

需要确认是否接受。

### 2.7 配置文件格式

需要确认工具配置使用哪种格式。

候选：

1. Java `.properties`
2. YAML
3. JSON

建议：

```text
使用 Java .properties。
```

理由：

1. 与 Java/Shell 工具链简单匹配。
2. 不额外引入 YAML 解析依赖。
3. log4j 配置也可使用 properties。

### 2.8 导出报告和 manifest 文件名

建议固定输出：

```text
manifest.json
export-report.json
import-report.json
verify-report.json
```

需要确认是否接受。

## 3. 建议优先确认

建议优先确认以下 6 个问题：

1. root 采用方案 A 还是方案 B？
2. 导入幂等是否接受“同 key 同值可覆盖，依赖目标空集群规避不同值冲突”？
3. partition 布局是否按 partition 文件独立签名和恢复？
4. 权限迁移是否仅强保证非 root 用户 space 级角色授权，其他扩展权限告警写报告？
5. 默认导入批大小、重试参数是否接受？
6. 配置文件是否使用 Java `.properties`？
