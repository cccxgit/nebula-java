# Nebula 搬迁后内容校验工具

文档入口：[完整使用手册](../数据校验工具使用手册.md) · [设计与实现分析](设计与实现分析.md) · [搬迁工具](../migration/README.md)。

独立可执行 JAR：`verification/target/nebula-data-verifier-3.8.4.jar`。运行时包含 nebula-java 及依赖，不需要搬迁工具 JAR、nebula-console、Meta 或 Storage 端口。

流程：**固定 ID 清单 → 源端 FETCH 采集 → 搬迁 → 目标端 FETCH 采集 → 离线比较**。源、目标可以处于不同网络，采集无需同时连接两端。所有数据库操作均为只读；比较命令完全不连接数据库。

扩展验收入口：[全量场景手动测试指导书](../task/acceptance/手动测试指导书.md) · [任务产出报告](../task/任务产出报告.md)。新场景是否通过以对应执行记录为准。

## 校验范围

- 按同一份清单核对每个点的原生 VID、实际全部 Tag（包括无属性 Tag）、完整属性名称、类型及值。
- 按 `SRC + Edge 名称 + RANK + DST` 核对边及完整属性。不同空间的内部 Tag/Edge 数字 ID 不参与比较。
- 字符串按原始字节比较；浮点按返回的 IEEE binary64 位模式比较，区分正零、负零与相邻浮点数；时间按原生字段保留微秒；NULL 与空字符串区分。
- 相关 Schema 的属性名称、类型与可空性也参与比较，包含 GEOGRAPHY 的通用/限定形状差异。默认表达式、注释、索引、TTL 配置与无关 Schema 不属于内容比较。
- **只对清单列出的对象负责**。不检查清单是否覆盖全库，不验证全库数量，不查找清单外目标多出的点/边。清单内点多出 Tag 会被发现。清单内对象缺失会使校验无法通过。
- 支持 15 种持久化属性类型：BOOL、INT8/16/32/64、FLOAT、DOUBLE、STRING、FIXED_STRING、DATE、TIME、DATETIME、TIMESTAMP、DURATION、GEOGRAPHY，以及正常 NULL。GEOGRAPHY 包括通用类型（ANY）及 POINT、LINESTRING、POLYGON 形状约束；异常 NULL、非有限地理坐标及未知返回类型不能通过校验。

沿用既定数据约束：无不带 Tag 的点，字符串 VID 与 FIXED_STRING 属性不含 NUL；普通 STRING 可包含 NUL、无效 UTF-8、引号、反斜杠、CR/LF、emoji、组合字符及任意字节。这里比较的是 Graph 接口实际可读的原生数据，不宣称恢复写入服务端前已经丢失的信息。

## 使用前提

源空间应从搬迁数据导出前开始停止写入、Schema 变更和 TTL 自动过期，直到**数据导出与源端 FETCH 基准都完成**。之后源端可以恢复工作，目标按导出文件搬迁。目标端在目标采集期间保持稳定。逐条 FETCH 不是数据库一致性快照，工具无法靠文件比较消除采集期间的并发变化。

两端必须使用同一个生成好的 plan 目录，不能分别重新生成。报告中的源采集时间表示历史基准，并不代表比较时源集群的实时状态。

本地真实验证使用 NebulaGraph 3.6 和本仓库 client 3.8.4；其他版本仍需在对应环境验收。字符串 VID 查询使用原始字节的三位八进制转义，采集前执行只读探测；需要服务端 `disable_octal_escape_char=false`。本实例不能表达最小 INT64 rank，因此明确拒绝 `-9223372036854775808` rank；INT64 VID 与属性最小值仍受支持。

## 分步执行

以下命令在仓库根目录执行；部署时把 `-jar` 后的路径换成复制到该机器的 JAR 路径。

**1. 源端：从已有搬迁导出目录生成固定清单。** 这个命令只读取 CSV 与 manifest，并验证 CSV SHA-256、表头、行数和标识唯一性，不访问数据库。跨 Tag 的相同 VID 会去重，边端点也加入点清单。

```bash
java -jar verification/target/nebula-data-verifier-3.8.4.jar prepare \
  --migration-dir /data/export-A --out /data/check-plan
```

**2. 源端：保存 FETCH 基准。** 密码通过环境变量读取，不写入输出文件。

```bash
export NEBULA_PASSWORD='nebula'
java -Xmx2g -jar verification/target/nebula-data-verifier-3.8.4.jar capture \
  --plan /data/check-plan --space A --host 127.0.0.1 --port 9669 \
  --user root --out /data/check-source
```

将整个 `/data/check-plan` 和 `/data/check-source` 复制到目标环境或最终比较机器；按原搬迁流程把数据写入 B。

**3. 目标端：使用完全相同的清单采集。** 该命令只需目标集群连接信息。两端密码不同，可各自设置环境变量，也可使用 `--password-env VARIABLE_NAME` 指定变量。

```bash
export NEBULA_PASSWORD='nebula'
java -Xmx2g -jar verification/target/nebula-data-verifier-3.8.4.jar capture \
  --plan /data/check-plan --space B --host 127.0.0.1 --port 9669 \
  --user root --out /data/check-target
```

**4. 任意机器：离线比较两份采集文件。** 不需要密码、源地址或目标地址。

```bash
java -Xmx2g -jar verification/target/nebula-data-verifier-3.8.4.jar compare \
  --source /data/check-source --target /data/check-target --out /data/check-result
```

结果位于 `/data/check-result/comparison-report.json`。所有输出目录必须不存在或为空；不会覆盖旧基准、旧结果。不提供断点续传或失败重试；失败后使用新的输出目录重新采集。

## 结果与退出码

| 结果 | 含义 | 退出码 |
|---|---|---|
| `MATCH` | 同一清单的所有对象均成功采集，内容及相关类型定义一致 | 0 |
| `DIFFERENT` | 采集完整，但至少一个字段、Tag 成员或相关 Schema 不同 | 2 |
| `ERROR` | 查询失败、对象缺失、快照不完整、文件损坏或清单不一致，不能宣称一致 | 1 |

采集命令成功标记为 `COMPLETE`；任一对象 `MISSING`/`ERROR` 或相关 Schema 检查失败都会标为 `INCOMPLETE`，退出 1。两侧发生相同查询错误也不能得到 `MATCH`。进程中断导致没有 manifest 的目录不是有效快照。

报告包含计划 ID、源/目标空间与采集时间、比较记录数、差异对象数、无法比较对象数以及差异字段。这里的记录数是清单对象数，不能作为全库数量结论。最多展示 1,000 条差异明细，较长值展示前 256 个字符；完整原始编码保留在各自 `records.jsonl` 中。

## 文件格式

```text
check-plan/
  plan.json          # 计划 ID、VID 类型、键文件 SHA-256、清单数量
  vertices.csv       # vid
  edges.csv          # src,edge,rank,dst
check-source/        # check-target 结构相同
  manifest.json      # 计划身份、相关 Schema、完成状态、记录及请求键摘要
  records.jsonl      # 每个请求对象一条原生字段记录
check-result/
  comparison-report.json
```

ID CSV 单元格使用 `V:` 加标准 Base64，带必要填充、无换行。字符串 VID/Edge 名称编码原始字节，整数 VID/rank 编码十进制 ASCII。VID 类型由 plan 保存。例如 user1、user2 以及 follow 边、rank=7：

```csv
vid
V:dXNlcjE=
V:dXNlcjI=
```

```csv
src,edge,rank,dst
V:dXNlcjE=,V:Zm9sbG93,V:Nw==,V:dXNlcjI=
```

如果已有独立的 ID CSV，可绕过搬迁导出文件生成计划；两个 CSV 都必须存在，无相应对象时保留表头即可，整个计划不能为空。此入口只按明确给出的点清单查询点，不自动加入边端点。

```bash
java -jar verification/target/nebula-data-verifier-3.8.4.jar prepare-ids \
  --vertices /data/vertices.csv --edges /data/edges.csv \
  --vid-type 'FIXED_STRING(128)' --out /data/check-plan
```

结果文件采用 UTF-8 JSON Lines，每条记录包含 `key`、`kind`、`status` 和排序后的 `fields`。字段的实际值再次编码成带类型的 ASCII JSON 字符串，因此任意二进制内容不会改变文件行边界。示例载荷：

| 原生值 | fields 中保存的载荷（外层 JSON 会再转义引号） |
|---|---|
| NULL | `["null"]` |
| 空字符串 | `["string",""]` |
| `abc<NUL>def` | `["string","YWJjAGRlZg=="]` |
| INT64 最大值 | `["int","9223372036854775807"]` |
| DOUBLE 负零 | `["float","8000000000000000"]` |
| GEOGRAPHY 点 `(1,2)` | `["geography","point",["3ff0000000000000","4000000000000000"]]` |

Tag/属性名称以原始字节 Base64 表示为字段路径；无属性 Tag 仍有 `tag/<名称Base64>` 的 `present` 标记。FLOAT/DOUBLE 的具体 Schema 类型另外存于 manifest；数值载荷始终使用接口返回的 binary64 原始位。比较器解析结构并按键比较，不直接比较含空间名/采集时间的整个文件，也不依赖查询返回顺序。

普通 `fVal` 的 NaN 和正负 Infinity 也按原始位比较；不同 NaN 载荷与正负零不会被归一化成相同值。独立校验能够记录某种位模式，不代表搬迁器可以写入该值：当前搬迁允许 FLOAT 的规范 NaN（`7ff8000000000000`）、DOUBLE 的规范 NaN 和正负 Infinity，拒绝 FLOAT 的正负 Infinity 及会被 Thrift 改变载荷的其他 NaN。异常 `NullType.NaN` 始终报错。见 [搬迁浮点边界](../migration/README.md#浮点重放边界) 与 [只读协议证据](../task/acceptance/probes/scalar-wire-probe.txt)。

GEOGRAPHY 的独立校验载荷是 `geography + shape + 坐标树`：线使用 `[[xBits,yBits],...]`，面使用 `[[[xBits,yBits],...],...]`，所有分量均为 16 位小写 raw binary64 字符串。形状、坐标和环的顺序、负零、单个坐标位差异均参与比较。`GEOGRAPHY` 与 `GEOGRAPHY(POINT)` 即使存了同一个点，相关 Schema 也不相同。非有限坐标报错；工具不做 WKT/JTS 转换或拓扑等价判断。

比较基准是源端实际 FETCH 返回的原生数据。源服务端可能已经规范化几何，因此不要求输入 WKT 文本、相邻重复坐标等规范化前形式在目标原样出现；目标应与源返回的形状树及坐标位一致。

## 构建与测试

在仓库根目录使用 JDK 8 构建：

```bash
JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 PATH=/usr/lib/jvm/java-8-openjdk-amd64/bin:$PATH \
  mvn -pl verification -am package -DskipTests -Dmaven.javadoc.skip=true
```

本地单元测试（不访问数据库）：

```bash
JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 PATH=/usr/lib/jvm/java-8-openjdk-amd64/bin:$PATH \
  mvn -pl verification -am test \
  -Dtest=NativeValueCodecTest,IdentifiersTest,FetchCollectorTest,PlanBundleTest,OfflineComparatorTest,VerifierCliTest \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false
```

真实验收需指定已有完整类型样本导出目录、源空间与**独立的可修改测试目标空间**；负向测试会修改并恢复目标的属性、Tag 和边，源端只读：

```bash
JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 PATH=/usr/lib/jvm/java-8-openjdk-amd64/bin:$PATH \
  mvn -pl verification -am test -Dtest=VerifierRealClusterTest \
  -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false \
  -Dnebula.verifier.acceptance=true \
  -Dnebula.verifier.bundle=/absolute/path/fixture-bundle \
  -Dnebula.verifier.source=fixture_A -Dnebula.verifier.target=disposable_fixture_B
```

2026-09-13 的真实测试覆盖当时已支持的 14 类型、极值、微秒、原始字节，以及单个浮点位变化、无属性 Tag 缺失/增加、无属性边缺失、错误查询和恢复后的再次匹配。历史结果见 [验收记录](acceptance/2026-09-13/README.md)，其范围不包含新增 GEOGRAPHY。地理类型及每场景千条级的扩展结果见 [任务产出报告](../task/任务产出报告.md)，执行步骤见 [全量测试指导书](../task/acceptance/手动测试指导书.md)。

当前版本逐条查询并在内存中比较全部记录，追求正确性和明确失败；需为采集和比较预留足够堆内存，单条数据大小也受服务器/RPC 限制。
