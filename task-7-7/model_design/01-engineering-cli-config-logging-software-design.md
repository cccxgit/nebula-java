# 01. 工程入口、CLI、配置与日志软件实现设计

> 状态：软件实现设计，未开始编码。
> 运行基线：迁移工具使用当前仓库的 nebula-java `3.8.4`；服务端行为参考 `/home/sch/nebula/nebula-3.6-io`。编码前必须以目标 Nebula Graph 3.8 集群完成兼容性测试，3.8 的实际行为优先于 3.6 源码推断。

## 1. 目标与边界

本模块建立迁移工具的可运行骨架，统一命令、Java `.properties`、日志、任务工作目录、进程锁和退出码。它不连接 Nebula 集群，不解析数据文件，也不实现导出、导入或校验业务。

约束如下：

1. 新增代码仅使用 JDK 8 和当前 nebula-java 已有依赖；不引入 Spark、Flink、Nebula Importer 或额外运行时组件。
2. 配置不支持运行中热更新。每次命令启动时读取、校验并固化一份脱敏后的配置摘要到 manifest。
3. 密码、HMAC 固定密钥和原始连接串中可能含有的敏感字段不得写日志、报告或异常栈摘要。
4. 同一 `export.dir` 同时只能运行一个迁移子命令。

## 2. 源码依据

| 来源 | 关键位置 | 对本模块的结论 |
| --- | --- | --- |
| nebula-java | `pom.xml:38-41` | 当前父工程仅聚合 `client` 和 `examples`，迁移工具应新增独立 `migration` Maven 子模块，不能污染客户端 API。 |
| nebula-java | `client/pom.xml` 的 `maven-compiler-plugin` | 客户端以 Java 8 编译；迁移模块同样固定 `source/target=8`，不能使用 record、`Path.of` 等高版本 API。 |
| nebula-java | `client/pom.xml` 的 `slf4j-api`、`slf4j-log4j12`、`junit` 依赖 | 工具日志沿用 SLF4J 到 log4j 1.x 绑定，测试沿用 JUnit 4 模式，不额外引入日志或命令行框架。 |
| nebula-java | `client/src/main/java/com/vesoft/nebula/client/graph/net/NebulaPool.java:23-35` | 客户端使用 `LoggerFactory`，迁移工具使用相同的 SLF4J 门面，避免两套日志上下文。 |
| Nebula Graph 3.6 | `/home/sch/nebula/nebula-3.6-io/src/graph/service/GraphFlags.cpp:10-114` | 服务端配置大量依赖 gflag；工具配置只表达连接与迁移行为，不能把服务端 gflag 静默写入本地配置。 |

## 3. Maven 与目录设计

编码阶段在根 `pom.xml` 增加 `migration` 模块，产物坐标为 `com.vesoft:nebula-migration:${project.version}`，直接依赖本仓库 `client` 模块。模块只新增以下目录，不修改 `client` 的 public API：

```text
migration/
  pom.xml
  src/main/java/com/vesoft/nebula/migration/
    cli/
    config/
    logging/
    workspace/
    common/
  src/main/resources/
    log4j.properties
    migration.properties.example
  src/test/java/com/vesoft/nebula/migration/
  src/test/resources/
bin/
  migration-precheck.sh
  migration-export.sh
  migration-import.sh
  migration-verify.sh
  migration-retry-failed.sh
```

`migration` 仅声明 JDK、`client` 和客户端已有的 SLF4J/log4j 依赖。发布采用单一可执行 jar；构建阶段可以使用 Maven 打包插件把 nebula-java 及其已有传递依赖装入 jar，但不得增加新的业务运行时依赖。

## 4. Java 包与核心类型

```text
com.vesoft.nebula.migration
  cli       MigrationMain, CommandRouter, MigrationCommand, ExitCode
  config    MigrationProperties, PropertiesLoader, ConfigValidator, SecretMasker
  logging   LogBootstrap, JobMdc
  workspace JobWorkspace, WorkspaceLock, AtomicFileSupport
  common    JobContext, Clock, MigrationException, ErrorCategory
```

| 类型 | 职责 |
| --- | --- |
| `MigrationMain` | 唯一 Java `main`，解析命令和 `-c <properties>`，初始化日志、工作目录和上下文。 |
| `MigrationCommand` | `precheck`、`export`、`import`、`verify`、`retry-failed` 的统一接口：`int run(JobContext context)`。 |
| `CommandRouter` | 把第一个位置参数映射为命令；拒绝未知命令、重复参数和缺失 `-c`。 |
| `MigrationProperties` | 不可变的强类型配置对象；所有模块只读该对象，禁止直接读取 `System.getProperties()`。 |
| `ConfigValidator` | 聚合格式、取值范围、地址、目录和跨字段约束，错误一次性返回，不在运行中再发现基本配置错误。 |
| `JobWorkspace` | 管理 `manifest.json`、`meta/`、`data/`、`failures/`、`logs/`、`samples/` 等路径；不允许路径逃逸出 `export.dir`。 |
| `WorkspaceLock` | 使用 `FileChannel.tryLock()` 创建 `job.lock`；拿不到锁时退出，不抢占其他进程。 |
| `JobContext` | 保存 job id、配置、workspace、时钟、日志 MDC、模块服务注册表；不保存明文密码的字符串副本。 |

## 5. 命令与 Shell 契约

Shell 仅负责查找 jar、设置 JVM 参数、转发参数和返回 Java 退出码。它不拼装 nGQL、不解析 properties、不修改源或目标集群。

```text
java -jar nebula-migration.jar precheck -c migration.properties
java -jar nebula-migration.jar export -c migration.properties
java -jar nebula-migration.jar import -c migration.properties
java -jar nebula-migration.jar verify -c migration.properties
java -jar nebula-migration.jar retry-failed -c migration.properties
```

Shell 入口固定映射为：

| Shell | Java 子命令 |
| --- | --- |
| `bin/migration-precheck.sh` | `precheck` |
| `bin/migration-export.sh` | `export` |
| `bin/migration-import.sh` | `import` |
| `bin/migration-verify.sh` | `verify` |
| `bin/migration-retry-failed.sh` | `retry-failed` |

启动顺序为：解析参数 -> 读取 properties -> 校验配置 -> 建立工作目录锁 -> 初始化 log4j/MDC -> 执行命令 -> 刷新日志和状态文件 -> 释放资源与锁。命令对已有任务目录的可执行性由 07 模块的任务状态机决定。

## 6. 配置模型

`PropertiesLoader` 使用 `java.util.Properties`，UTF-8 文件由 `InputStreamReader` 显式指定编码读取。所有地址以逗号分隔的 `host:port` 表达，并转换为 nebula-java `HostAddress`；IPv6 必须使用 `[addr]:port` 格式。

| 分组 | 必填项 | 默认或校验规则 |
| --- | --- | --- |
| 源连接 | `source.meta.addresses`、`source.graph.addresses`、`source.user`、`source.password` | 地址列表至少一个；导出 storage 使用 source meta 地址发现 storage leader。 |
| 目标连接 | `target.meta.addresses`、`target.graph.addresses`、`target.user`、`target.password` | `import`、`verify` 必填；`precheck` 根据检查对象要求。 |
| 工作目录 | `export.dir` | 绝对路径；创建前规范化；不允许根目录、空字符串或符号链接逃逸。 |
| 导出 | `export.format`、`export.file.layout`、`export.scan.limit`、`export.concurrent.partitions`、`export.concurrent.labels` | 默认 `csv`、`label`、`1000`、`8`、`1`；仅允许 `csv/json` 和 `label/partition`。 |
| 导入 | `import.batch.vertices`、`import.batch.edges`、`import.retry.max-attempts`、`import.retry.backoff.ms` | 默认 `500`、`500`、`3`、`1000`；均为启动时固定值。 |
| 校验 | `verify.full-checksum.enabled`、`verify.sample.mod` | 默认 `false`、`100000`；`sample.mod >= 1`。 |
| 可选连接参数 | `*.timeout.ms`、`*.connection-retry`、`*.execution-retry`、`*.ssl.enabled` | 由 02 模块映射到 nebula-java 连接配置；未配置时使用工具显式默认值并写入 manifest。 |

未知键默认拒绝，避免 `source.graph.address` 这类拼写错误被静默忽略。允许前缀 `x.` 作为部署方自定义注释键，但不会传入任何运行模块。

## 7. 日志、审计与退出码

log4j 使用滚动文件 appender 写入 `<export.dir>/logs/`，同时可选 console appender。每条日志写入 MDC：`jobId`、`command`、`cluster=source|target`、`space`、`label`、`partition`、`file`。高频行级日志仅使用 DEBUG；任务、文件、批次和失败事件使用 INFO/WARN/ERROR。

| 退出码 | 含义 | 是否可恢复 |
| --- | --- | --- |
| `0` | 当前命令成功，状态与报告已落盘 | 不适用 |
| `2` | 参数或 properties 非法 | 修正配置后重试 |
| `3` | 工作目录锁、manifest 或本地文件完整性问题 | 按报告处理后重试 |
| `4` | precheck 或数据冻结前置条件不满足 | 处理集群状态后重试 |
| `5` | 源端导出或元数据快照失败 | 按 checkpoint 续导 |
| `6` | 目标 schema/user/role 创建失败 | 用户手动清理目标后重新导入 |
| `7` | 数据导入仍有最终失败记录 | 修复后执行 `retry-failed` 或人工清理后重导入 |
| `8` | 校验失败或文件签名/checksum 不通过 | 保留证据，禁止宣告成功 |
| `9` | 未分类内部错误 | 保留异常栈和 manifest 状态后排查 |

## 8. 测试与完成准则

单元测试覆盖 properties 编码、地址解析、默认值、未知键、敏感字段脱敏、路径逃逸、锁竞争、命令路由、退出码和日志 MDC。测试形式沿用 nebula-java 的 JUnit 4 结构，参考 `client/src/test/java/com/vesoft/nebula/client/graph/net/TestSession.java`。

本模块完成的标准是：五个 Shell 命令都能启动相同 jar；无有效配置时不连接网络；并发启动同一任务目录时只有一个进程获得锁；日志和错误报告不出现密码或固定 HMAC 密钥。
