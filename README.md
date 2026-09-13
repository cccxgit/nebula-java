# nebula-java

![](https://img.shields.io/badge/language-java-orange.svg)
[![LICENSE](https://img.shields.io/github/license/vesoft-inc/nebula-java.svg)](https://github.com/vesoft-inc/nebula-java/blob/master/LICENSE)
[![GitHub release](https://img.shields.io/github/tag/vesoft-inc/nebula-java.svg?label=release)](https://github.com/vesoft-inc/nebula-java/releases)
[![GitHub release date](https://img.shields.io/github/release-date/vesoft-inc/nebula-java.svg)](https://github.com/vesoft-inc/nebula-java/releases)
[![codecov](https://codecov.io/gh/vesoft-inc/nebula-java/branch/master/graph/badge.svg?token=WQVAG6VMMQ)](https://codecov.io/gh/vesoft-inc/nebula-java)

Nebula Java is a Java client for developers to connect their projects to Nebula Graph.

Please be noted that [nebula-JDBC](https://github.com/vesoft-inc/nebula-jdbc)(based on nebula-java)
is the JDBC implementing for nebula graph.

> **NOTE**: Nebula Java is not thread-safe.

## 本分支新增：数据搬迁与独立内容校验

`nebula-java-3.8` 分支在 client 3.8.4 基础上增加两个独立可执行 JAR，并包含搬迁所需的扫描死锁、漏页及连接超时修复。

| 模块 | 数据流程 | 使用指导 | 实现分析 |
|---|---|---|---|
| [migration](migration/README.md) | 源 scan → CSV → 目标 INSERT → 扫描核对 | [数据搬迁操作手册](数据搬迁操作手册.md) | [搬迁实现分析](migration/系统软件实现分析报告.md) |
| [verification](verification/README.md) | 固定 ID → 两侧分别 FETCH 保存文件 → 离线比较 | [数据校验工具使用手册](数据校验工具使用手册.md) | [校验设计与实现分析](verification/设计与实现分析.md) |

搬迁与校验支持 15 种持久化属性类型，包含 GEOGRAPHY 的通用类型和 POINT、LINESTRING、POLYGON 形状约束；同时保留普通 STRING 的原始字节、FLOAT/DOUBLE 原始位和时间微秒。地理数据按源接口实际返回的形状、点/环顺序与坐标原始位保存，拒绝 NaN/Infinity 坐标。使用前需阅读手册中的源数据约束、采集期间的数据稳定要求及服务器兼容边界。独立校验工具只检查清单内对象内容，不验证全库数量或清单外数据。

浮点特殊值有明确边界：允许 FLOAT 的规范 NaN，以及 DOUBLE 的规范 NaN 和正负 Infinity；FLOAT 的正负 Infinity 和参数传输会改变载荷的非规范 NaN 在搬迁预检中拒绝。详见 [浮点重放边界](migration/README.md#浮点重放边界) 与 [只读协议探针](task/acceptance/probes/scalar-wire-probe.txt)。这些协议证据不等于全量搬迁验收已通过。

在仓库根目录，用 JDK 8 和 Maven 构建：

```bash
mvn -pl migration,verification -am package -DskipTests -Dmaven.javadoc.skip=true
```

输出分别为 `migration/target/migration-3.8.4.jar` 和 `verification/target/nebula-data-verifier-3.8.4.jar`。每个 JAR 均包含其运行依赖。上述命令跳过测试；各模块 README 提供单元测试与显式启用的真实集群验收命令。

已保存 [搬迁真实验收记录](migration/acceptance/2026-09-13/README.md) 和 [校验真实验收记录](verification/acceptance/2026-09-13/README.md)。这些历史验收使用本机 NebulaGraph 3.6 的不同空间，当时未按严格“源基准 → 导入 → 目标基准”时序执行。本次扩展验收明确采用该时序，并逐场景保存时间与原生快照证据，结果见 [任务产出报告](task/任务产出报告.md)。跨机器隔离部署不在本次同集群双空间测试范围内。文档中的 `/home/sch/...` 是验收机示例路径，部署时需按环境替换。

上述 2026-09-13 记录保留了当时不含 GEOGRAPHY 的 14 类型验收范围。新增地理类型和每场景不少于 1,000 条记录的扩展验收，执行方式与最终结果见 [全量场景手动测试指导书](task/acceptance/手动测试指导书.md) 和 [任务产出报告](task/任务产出报告.md)；不能将历史结果视为新增场景已通过。

源码、测试、分析和使用文档、精选验收报告进入版本控制；`target/`、JAR、日志及完整运行数据保留为本地构建和验收产物。

## Two main branches of this repository

In this repository, you can find two branches for the source code of Nebula Java of different
versions.

### The master branch

The master branch is for Nebula Java v2.0, which works with Nebula Graph v2.0 nightly.

This README file provides Java developers with instructions on how to connect to Nebula Graph v2.0.

### The v1.0 branch

In the v1.0 branch, you can find source code of these:

- Nebula Java v1.0, which works with Nebula Graph v1.1.0 and earlier versions only.
- Nebula Graph Exchange, Nebula Spark Connector, Nebula Flink Connector, and nebula-algorithm.

For more information,
see [README of v1.0](https://github.com/vesoft-inc/nebula-java/blob/v1.0/README.md).

### The v2.0.0-rc branch

The v2.0.0-rc branch works with Nebula Graph v2.0.0-beta and v2.0.0-rc1, but not for the latest
nightly Nebula Graph.

## Prerequisites

To use this Java client, do a check of these:

- Java 8 or a later version is installed.
- Nebula Graph is deployed. For more information,
  see [Deployment and installation of Nebula Graph](https://docs.nebula-graph.io/master/4.deployment-and-installation/1.resource-preparations/ "Click to go to Nebula Graph website").

## Modify pom.xml

If you use Maven to manage your project, add the following dependency to your `pom.xml` file.
Replace `3.0-SNAPSHOT` with an appropriate Nebula Java version.
For more versions, visit [releases](https://github.com/vesoft-inc/nebula-java/releases).

```xml

<dependency>
    <groupId>com.vesoft</groupId>
    <artifactId>client</artifactId>
    <version>3.0-SNAPSHOT</version>
</dependency>
```

There are the version correspondence between client and Nebula:

| Client version |  Nebula Version   |
|:--------------:|:-----------------:|
|     1.0.0      |       1.0.0       |
|     1.0.1      |    1.1.0,1.2.0    |
|     1.1.0      |    1.1.0,1.2.0    |
|     1.2.0      | 1.1.0,1.2.0,1.2.1 |
|   2.0.0-beta   |    2.0.0-beta     |
|   2.0.0-rc1    |     2.0.0-rc1     |
|     2.0.0      |    2.0.0,2.0.1    |
|     2.0.1      |    2.0.0,2.0.1    |
|     2.5.0      |    2.5.0,2.5.1    |
|     2.6.0      |    2.6.0,2.6.1    |
|     2.6.1      |    2.6.0,2.6.1    |
|      3.x       |        3.x        |
|  3.0-SNAPSHOT  |      nightly      |

## Graph client example

To connect to the `nebula-graphd` process of Nebula Graph:

```java
NebulaPoolConfig nebulaPoolConfig = new NebulaPoolConfig();
nebulaPoolConfig.setMaxConnSize(10);
List<HostAddress> addresses = Arrays.asList(new HostAddress("127.0.0.1", 9669), new HostAddress("127.0.0.1", 9670));

NebulaPool pool = new NebulaPool();
pool.init(addresses, nebulaPoolConfig);

Session session = pool.getSession("root", "nebula", false);

session.execute("SHOW HOSTS;");

session.release();

pool.close();
```

## Graph SessionPool example

To use SessionPool, you must config the space to connect for SessionPool.
The SessionPool is thread-safe, and support retry(release old session and get available session from
SessionPool) for both connection error, session error and
execution error(caused by bad storaged server), and the retry mechanism needs users to config
retryTimes and intervalTime between retrys.

And SessionPool maintains servers' status, can isolation broken graphd server and auto routing
restarted graphd server when you need to execute with new session， meaning your parallel is larger
than the idle session number in the session pool.

```java
List<HostAddress> addresses = Arrays.asList(new HostAddress("127.0.0.1", 9669));
String spaceName = "test";
String user = "root";
String password = "nebula";
SessionPoolConfig sessionPoolConfig = new SessionPoolConfig(addresses, spaceName, user, password);
sessionPoolConfig.setRetryTimes(3);
sessionPoolConfig.setIntervalTime(1000);

SessionPool sessionPool = new SessionPool(sessionPoolConfig);

if (!sessionPool.init()) {
  log.error("session pool init failed.");
  return;
}

ResultSet resultSet;

try {
    
  resultSet = sessionPool.execute("match (v:player) return v limit 1;");
  System.out.println(resultSet.toString());
  
} catch (IOErrorException | ClientServerIncompatibleException | AuthFailedException | BindSpaceFailedException e) {
    
  e.printStackTrace();
  System.exit(1);
  
} finally {
    
    sessionPool.close();
    
}
```
