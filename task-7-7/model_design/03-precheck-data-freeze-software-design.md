# 03. Precheck 与数据静止协作软件实现设计

> 状态：软件实现设计，未开始编码。
> 本文包含两部分：迁移工具的只读 precheck，以及 Nebula Graph graphd 的数据静止内核改造。工具不自动开启或关闭冻结；操作员通过 graph gflag 手动控制。

## 1. 目标与边界

precheck 在导出、导入和校验前阻断明显不安全的条件：版本不匹配、集群不可用、源端未冻结、存在数据变更 job、目标非空或存在非 root 用户。TTL 和商业版扩展权限只告警，不阻断。

数据静止的目标是：冻结完成后，新的 graph nGQL 变更语句被拒绝；冻结前已接受的变更执行结束后，冻结状态才可被确认。正常业务只经 graphd 写入；直接调用 storage 写 RPC 不属于本期业务接入范围，若存在该链路必须另行冻结或断流。

## 2. 源码依据

| 来源 | 关键位置 | 设计结论 |
| --- | --- | --- |
| Nebula Graph 3.6 | `/home/sch/nebula/nebula-3.6-io/src/graph/service/QueryInstance.cpp:39-104` | nGQL 已完成解析后调用 `Validator::validate`，冻结检查应位于 graph validator 流程，不能通过字符串匹配原始 query。 |
| Nebula Graph 3.6 | `src/graph/validator/Validator.cpp:332-369` | `validateImpl()` 后、`checkDuplicateColName()` 与权限/计划生成前是统一拦截点；此处不依赖 `FLAGS_enable_authorize`。 |
| Nebula Graph 3.6 | `src/parser/Sentence.h:30-114` | `Sentence::Kind` 明确区分 DML、DDL、ACL、配置和 Admin Job，可建立稳定白名单/黑名单。 |
| Nebula Graph 3.6 | `src/graph/service/PermissionCheck.cpp:52-123` | 现有权限检查已按 `Sentence::Kind` 区分写 space、写 schema、写 user、写 data 和 admin job，冻结分类沿用该语义边界。 |
| Nebula Graph 3.6 | `src/parser/parser.yy:2743-2774,3180-3278` | REBUILD、DOWNLOAD、INGEST、COMPACT、BALANCE 等后台变更均构造成 `AdminJobSentence(JobOp::ADD, ...)`；`SHOW JOBS` 和 `STOP JOB` 有独立 op。 |
| Nebula Graph 3.6 | `src/graph/service/GraphFlags.cpp:37-42`、`GraphFlags.h:25-40` | graphd gflag 在 GraphFlags 中定义/声明，新增冻结开关应放在相同位置。 |
| Nebula Graph 3.6 | `resources/gflags.json` 的 `MUTABLE` 列表；`src/graph/executor/admin/ConfigExecutor.cpp:59-96` | 可运行时修改的 graph 配置需要登记为 mutable；`UPDATE CONFIGS` 会通过 `gflags::SetCommandLineOption` 更新本地并写入 meta。 |
| Nebula Graph 3.6 | `src/webservice/WebService.cpp:70-85` | graphd 已注册 `GET /flags` 和 router，冻结状态可新增只读 HTTP 路由供工具逐实例确认。 |
| nebula-java | `graph/data/ResultSet.java:144-184`、`graph/net/Session.java:91-150` | 工具可通过 graph nGQL 查询 `SHOW JOBS`、`SHOW CONFIGS`，并读取明确的成功/失败结果。 |

## 3. graphd 内核改造

### 3.1 新增 gflag 和运行状态

新增 graphd gflag：

```text
--enable_graph_mutation=true
```

`true` 表示允许变更，`false` 表示冻结。编码时新增以下文件/修改点：

```text
src/graph/service/GraphFlags.h              DECLARE_bool(enable_graph_mutation)
src/graph/service/GraphFlags.cpp            DEFINE_bool(enable_graph_mutation, true, ...)
resources/gflags.json                        将 enable_graph_mutation 加入 MUTABLE
src/graph/service/MutationFreezeManager.h   新增
src/graph/service/MutationFreezeManager.cpp 新增
src/graph/validator/MutationFreezeGuard.h   新增
src/graph/validator/MutationFreezeGuard.cpp 新增
src/graph/validator/Validator.cpp            接入 guard
src/webservice/WebService.cpp                注册只读状态路由
src/webservice/MigrationFreezeStatusHandler.* 新增
```

`MutationFreezeManager` 是 graphd 进程内唯一状态对象，维护：

```text
writable: bool
freezeEpoch: uint64
activeMutationCount: uint64
lastTransitionTime: int64
```

它使用互斥锁保护“检查 writable 并登记 in-flight mutation”的临界区。运行时把 flag 改为 `false` 的唯一受支持路径是 graph 配置更新处理器；该处理器必须调用 `MutationFreezeManager::setWritable(false)`，而不是仅直接修改 gflag。这样可先关闭入口、再等待 `activeMutationCount == 0`，防止读取旧 flag 的请求在冻结确认后才登记。

启动参数 `--enable_graph_mutation=false` 只表示进程初始只读，不需要等待 drain。HTTP `PUT /flags` 对本 flag 必须复用同一 manager，或直接拒绝该 flag，避免绕过状态转换。

### 3.2 语句分类与拦截点

`MutationFreezeGuard::classify(Sentence*)` 只读取 AST 类型，不改 `parser.yy`，也不扫描 nGQL 字符串。接入点如下：

```text
Validator::validate()
  -> validateImpl()
  -> MutationFreezeGuard::enterIfMutation(sentence_, qctx_)
  -> checkDuplicateColName()
  -> checkPermission()
  -> toPlan()
```

成功 `enterIfMutation` 后，将 `MutationLease` 挂到 `QueryContext`；lease 在 query 正常结束、校验/优化失败、执行异常和 QueryInstance 析构路径都释放。`QueryInstance` 已持有 `QueryContext`，因此可覆盖从 validation 到 scheduler/executor 完成的整个 in-flight 区间，而不是只统计 validation 时间。

冻结时拒绝以下 `Sentence::Kind`：

| 类别 | 代表 Kind |
| --- | --- |
| 数据 DML | `kInsertVertices`、`kInsertEdges`、`kUpdateVertex`、`kUpdateEdge`、`kDeleteVertices`、`kDeleteTags`、`kDeleteEdges` |
| schema / index DDL | `kCreateTag`、`kAlterTag`、`kDropTag`、`kCreateEdge`、`kAlterEdge`、`kDropEdge`、create/drop tag/edge/fulltext index、listener 变更 |
| space/物理元数据 | create/alter/drop/clear space、host/zone/listener/snapshot 变更 |
| ACL | `kCreateUser`、`kDropUser`、`kAlterUser`、`kGrant`、`kRevoke`、改密码 |
| 后台变更 | `kAdminJob` 中 `JobOp::ADD` 与 `JobOp::RECOVER`；覆盖 balance、compact、rebuild、download、ingest、flush、stats 等 |
| 配置 | `kSetConfig`，仅冻结开关自身的状态转换请求例外 |

允许只读语句、`SHOW JOBS`/`SHOW JOB`、`STOP JOB`、`SHOW CONFIGS`，以及受控的 `UPDATE CONFIGS graph:enable_graph_mutation=true|false`。不新增 Thrift error code，统一返回可识别的错误文本前缀 `MUTATION_FROZEN`，避免改变已有客户端协议枚举；迁移工具据此前缀提供明确诊断。

### 3.3 集群冻结完成语义

`UPDATE CONFIGS graph:enable_graph_mutation=false` 的配置传播不能被单一 graphd 成功响应视为全局完成。每个 graphd 暴露：

```json
GET /migration_freeze_status
{
  "enableGraphMutation": false,
  "activeMutationCount": 0,
  "freezeEpoch": 42,
  "state": "FROZEN"
}
```

`state=FROZEN` 的条件是 `enableGraphMutation=false && activeMutationCount=0`。迁移工具不改变开关，只轮询配置中的全部 source graphd HTTP 地址；任一实例不满足则 precheck FAIL。现有 `WebService` router 是新增 handler 的源码基础，不能假定 3.6 当前已存在该路径。

该保证覆盖 graphd 接收的写语句。storaged 因 graphd DML 已排空而不再接收新的业务写 RPC；后台 job 由 AST 拦截和 precheck 的 `SHOW JOBS` 双重防护。若部署存在绕过 graphd 的 storage 写入者，precheck 必须显示 `DIRECT_STORAGE_WRITE_UNVERIFIED` 并阻断生产迁移。

## 4. 迁移工具 precheck 设计

```text
com.vesoft.nebula.migration.precheck
  PrecheckCommand, PrecheckRunner, Check, CheckResult
  SourceClusterChecker, TargetClusterChecker
  FreezeStatusChecker, JobStatusChecker, VersionChecker
  CheckReportWriter
```

| 检查 | 读取接口 | 失败级别 |
| --- | --- | --- |
| 源/目标版本为 3.8 | `GraphClient` 的版本探测和 host 信息 | FAIL |
| graph/meta/storage 可达 | 02 模块 `ClusterProbe` | FAIL |
| source space/schema/partition 可读 | `MetaClientAdapter` | FAIL |
| source graphd 冻结状态 | 每个实例 `GET /migration_freeze_status`；未部署新 handler 时明确 FAIL | FAIL |
| source 后台 job | `SHOW JOBS`，仅 RUNNING/QUEUE 的变更 job 阻断 | FAIL |
| source TTL schema | 10 模块 `TtlSchemaProbe` | WARN |
| target 无业务 space | `SHOW SPACES` + 允许列表仅系统/空 | FAIL |
| target 仅 root 用户 | `SHOW USERS` | FAIL |
| root 可审计 | `SHOW USERS` 结果 | INFO |
| 商业版扩展权限 | 04 模块 capability probe | WARN |

每个 `CheckResult` 含 `id`、`severity`、`status`、`evidence`、`remediation`。`precheck-report.json` 写入 07 模块 manifest；任何 FAIL 返回退出码 4，不启动 export/import。

## 5. 测试与完成准则

graphd 单测以 `src/graph/validator/test/MutateValidatorTest.cpp`、`ACLValidatorTest.cpp`、`AdminValidatorTest.cpp` 和 parser `ParserTest.cpp` 为基线，新增：冻结下 DML/DDL/ACL/ADD JOB 被拒绝，读取/SHOW JOBS/STOP JOB 被允许，状态开关排空 in-flight mutation，HTTP status 正确反映状态。

迁移工具集成测试验证三 graphd 中任一未冻结会阻断；全部冻结且计数归零后通过；存在 COMPACT/BALANCE/REBUILD 的 RUNNING/QUEUE job 阻断；TTL 只 warning；目标有 space 或非 root 用户阻断。

完成标准是：冻结确认之后没有新的 graph 写请求可进入执行计划，且每个 source graphd 都可被工具独立证明为 `FROZEN`。数据冻结能力必须先于生产导出工具发布。
