# Nebula CSV 搬迁工具

文档入口：[手工搬迁操作手册](../数据搬迁操作手册.md) · [实现分析报告](系统软件实现分析报告.md) · [独立 FETCH 校验工具](../verification/README.md)。文档中的本机绝对路径是验收环境示例，部署时按实际目录替换。

以数据正确性为目标：`源空间 → StorageClient.scan → CSV + manifest.json → 参数化 INSERT → 目标 scan → 完整键和值比较`。

支持同一集群的空间 A 搬到新空间 B，也支持不同集群。每个 Tag/Edge 一个 CSV，包含空表；按完整标识排序。工具逐条写入，不提供并发优化、自动重试、断点续传或回滚。发生错误时退出码为 1，目标可能是不完整空间；不会把部分完成报告成成功。

已完成本地真实集群验收：两种 VID、530 条迁移记录、14,420 次独立原生属性比较全部一致。完整范围、保留的 A/B 空间与服务端限制见 [真实验收记录](acceptance/2026-09-13/README.md)。

第一次手工操作请阅读 [从零完成搬迁的完整案例](MANUAL_WALKTHROUGH.md)：包含可直接复制、带续行符的 nGQL，新建源数据、分步导出/导入、校验、故障演练和现成大样本复用。

## 范围与前提

- 支持 BOOL、INT8/16/32/64（INT）、FLOAT、DOUBLE、STRING、FIXED_STRING(N)、DATE、TIME、DATETIME、TIMESTAMP、DURATION 和正常 NULL。
- 不支持 GEOGRAPHY；导出发现不支持的属性类型即失败。
- 源数据不存在无 Tag 的点，字符串 VID 和 FIXED_STRING 属性没有 NUL。普通 STRING 可以包含 NUL、任意字节和无效 UTF-8。
- 迁移过程中源空间停止写入及 Schema 变更，目标空间由本工具独占。重新扫描源只能检查最终状态，不能代替一致性快照。
- 当前版本拒绝启用 TTL 的 Schema，避免迁移期间自动过期。索引、账号、权限、集群配置不是本工具的迁移内容。
- 当前服务端无法用 INSERT 表达 `rank=-9223372036854775808`，工具会在导出/导入预检阶段拒绝此值。INT64 **属性与 VID** 的完整上下边界均保留支持；详见下面的语法兼容说明。
- 目标空间必须不存在，输出目录必须不存在或为空。不覆盖已有空间或导出文件；失败空间需要人工检查和清理后另选新名称重跑。
- 当前实测运行环境为用户提供的 NebulaGraph 3.6 实例；使用本仓库修复后的 client 3.8.4。实测结论参见验收报告，不能推定所有其他服务端版本都已验证。
- 当前按正确性优先，比较阶段在内存中保留全部记录，需要足够堆内存。单条字符串上限仍由服务端/RPC 能力决定。

## 构建

在仓库根目录使用 JDK 8 构建：

```bash
JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 PATH=/usr/lib/jvm/java-8-openjdk-amd64/bin:$PATH mvn -pl migration -am package -DskipTests -Dmaven.javadoc.skip=true
```

可执行文件：`migration/target/migration-3.8.4.jar`。

## 同一个数据库从 A 搬到 B

源空间 A 已有数据，目标空间 B 尚不存在：

```bash
export NEBULA_PASSWORD='nebula'
java -Xmx2g -jar migration/target/migration-3.8.4.jar migrate --source-space A --target-space B --directory /absolute/path/export-A
```

默认连接 `127.0.0.1:9669`，Meta 为 `127.0.0.1:9559`，用户 root。密码从环境变量读取，不写入清单和报告。

异机迁移可增加：

```text
--source-host SOURCE_IP --source-graph-port 9669 --source-meta-port 9559
--target-host TARGET_IP --target-graph-port 9669 --target-meta-port 9559
--source-user root --target-user root
```

可分别设置 `NEBULA_SOURCE_PASSWORD`、`NEBULA_TARGET_PASSWORD`；或用 `--source-password-env ENV_NAME`、`--target-password-env ENV_NAME` 指定变量名。此版本每侧配置一个 Graph/Meta 主机地址。

参数 `--scan-limit 100` 控制扫描页大小，`--timeout-ms 60000` 控制连接请求超时，`--schema-wait-ms 20000` 给数据库元数据缓存传播留出时间。Schema 等待不是失败重试。

## 分步执行

```bash
java -jar migration/target/migration-3.8.4.jar export --source-space A --directory /absolute/path/export-A
java -jar migration/target/migration-3.8.4.jar inspect --directory /absolute/path/export-A
java -jar migration/target/migration-3.8.4.jar import --target-space B --directory /absolute/path/export-A
java -jar migration/target/migration-3.8.4.jar verify --target-space B --directory /absolute/path/export-A
```

- `export`：读取 Schema 和完整扫描结果，最后写入 `manifest.json` 作为导出完成标记。
- `inspect`：离线检查所有文件 SHA-256、表头、行数、完整键唯一性、类型、NULL 与编码格式。
- `import`：预检通过后创建新空间，复制属性 Schema 并逐条参数写入，再验证目标与导出基准一致。
- `verify`：同时验证源与目标均匹配导出基准，源空间名从清单读取。
- `migrate`：一次完成导出、导入和源/目标校验。

`migrate` / `import` / `verify` 成功时输出 JSON，并写入 `verification-report.json`。`matched=true` 表示完整键及属性编码一致，`verifiedAt` 记录本次校验时间；报告不代表后续写入后的状态。`sourceRechecked=true` 表示也重新核对了源；`import` 本身只验证目标，因而该字段为 false。

`tagRows` 是各 Tag 的记录数之和，同一个点附带多个 Tag 时分别计数；不能把它当成去重后的点数。`edgeRows` 按 Edge 类型、源 VID、目标 VID、rank 计数。

## CSV 协议 v1

清单保存实际属性名称、准确类型、可空性、默认表达式、注释与建表语句；CSV 用固定 ASCII 列名，避免属性名影响分隔规则。

```csv
_vid,p0,p1
V:dXNlcjE=,V:YWJjAGRlZg==,N
```

上例字符串 VID 为 user1，p0 是 `abc<NUL>def`，p1 是正常 NULL。

```csv
_src,_dst,_rank,p0
V:dXNlcjE=,V:dXNlcjI=,V:Nw==,V:YSxi
```

上例边从 user1 指向 user2，rank=7，p0 为 `a,b`。

正常 NULL 为 `N`；非 NULL 为 `V:` 加标准 Base64，带必要填充、无换行。空字符串为 `V:`，与 NULL 区分。空字段、缺列、错误 NULL 状态和未知类型均拒绝。

| 类型 | Base64 前的载荷 |
|---|---|
| BOOL | ASCII true/false |
| 整数、TIMESTAMP | 十进制 ASCII；TIMESTAMP 为 Unix 秒 |
| FLOAT/DOUBLE | scan 返回 fVal 的 raw binary64，16 位小写十六进制 ASCII |
| STRING/FIXED_STRING | 原始 sVal 字节，不经过 Java String |
| DATE | 紧凑整数数组 `[year,month,day]` |
| TIME | `[hour,minute,second,totalMicrosecond]` |
| DATETIME | `[year,month,day,hour,minute,second,totalMicrosecond]` |
| DURATION | `[months,seconds,microseconds]`，不换算月份或归一化字段 |

解码后直接构造原生 Value 参数，不把 Base64、十六进制或日期数组直接拼进 nGQL。源/目标比较对浮点使用位模式，对字符串使用字节；不使用 nGQL 近似浮点相等。IEEE NaN 的载荷可能被底层 Thrift 规范化，特殊非有限值不能笼统承诺往返，导入预检和最终比较会阻止静默成功。

### NebulaGraph 3.6 的标识参数兼容

真实实例拒绝 INSERT 的 VID 位置使用 `$参数`。工具因此只把属性作为原生参数传递：普通整数 VID/rank 严格生成十进制字面量，字符串 VID 的每个原始字节生成独立三位八进制转义 `\ooo`，不会先进行 UTF-8 解码。这同时避免引号、换行、反斜杠和非法 UTF-8 改变语句或标识。

整数 VID 的最小值使用 `toInteger("-9223372036854775808")`，已通过实际 INSERT/FETCH 验证。INT64 属性最小值仍直接使用原生 iVal 参数。此服务端的 rank 语法只允许带可选正负号的整数，无法使用转换函数或算术表达式，而且扫描器拒绝该最小值的绝对值；十进制、八进制、十六进制、表达式和函数写法均实测失败。因此可往返 rank 范围为 `[-9223372036854775807, 9223372036854775807]`，工具明确拒绝更小的那个值，绝不替换、跳过或宣称成功。

目标写入前，用只读查询验证 1..255 的所有字节能原样解析。需要 Graph 服务允许八进制转义（`disable_octal_escape_char=false`）；工具不会自动修改服务器配置。不满足时在建目标空间前拒绝。NUL VID 仍按既定源数据约束排除。

## 测试

不会访问数据库的单元/回归测试：

```bash
JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 PATH=/usr/lib/jvm/java-8-openjdk-amd64/bin:$PATH mvn -pl migration -am test -Dtest=ValueCodecTest,MigrationBundleTest,ScanIteratorLifecycleTest,ScanIteratorPaginationTest,PartScanQueueTest -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false
```

显式启用真实集群验收：

```bash
JAVA_HOME=/usr/lib/jvm/java-8-openjdk-amd64 PATH=/usr/lib/jvm/java-8-openjdk-amd64/bin:$PATH mvn -pl migration -am test -Dtest=RealClusterRoundTripTest -Dsurefire.failIfNoSpecifiedTests=false -DfailIfNoTests=false -Dnebula.acceptance=true
```

验收默认使用本地 root/nebula，仅供指定的本地测试实例。连接参数可用 `-Dnebula.host=...`、`-Dnebula.graphPort=...`、`-Dnebula.metaPort=...`、`-Dnebula.user=...`、`-Dnebula.password=...` 覆盖。

验收创建唯一名称的两组 A/B 空间：一组字符串 VID，一组整数 VID；不会清空已有空间。空间和导出文件保留供检查。测试报告与 CSV 位于 `migration/target/acceptance/<run-id>/`。

验收流程包含独立的原生值断言：样本 INSERT → 按已知完整键 FETCH 核对输入 → scan/CSV/目标 INSERT → 目标 FETCH 对原样本逐字段核对 → 源目标多种页大小扫描比较。另检查 CSV 篡改拒绝、已有空间拒绝以及目标单个浮点位改变能被发现。

每组包含 127 组属性样本，每组同时写入 Tag 和 Edge。额外覆盖多 Tag、无属性 Tag/Edge、空 Tag/Edge、带空格的 Schema 名、默认值 Schema、自环和相同端点不同 rank 的多条边。

| 样本类别 | 覆盖 |
|---|---|
| NULL/布尔 | 全属性 NULL、每列单独 NULL、true/false |
| 整数 | INT8/16/32/64 的最小值、最大值、-1、0、1；超过 double 精确整数范围的 INT64 |
| FLOAT/DOUBLE | 正负最大有限值、正负最小正规值、正负最小非正规值、正零/负零、0.1、1.23456789、1 的相邻浮点数 |
| STRING | 空字符串、字面 NULL/N/V:、引号、反斜杠、CR/LF/CRLF/TAB、独立/中间/末尾 NUL、emoji/ZWJ、NFC/NFD 组合字符、BOM、混合字符 |
| 原始字节 | 全部 256 种字节；6 类无效 UTF-8；1,048,593 字节二进制串；1,048,576 字节 emoji 串 |
| FIXED_STRING | 空值、空白、特殊字符、128 字节 ASCII 和 128 字节 emoji，均不含 NUL |
| DATE | 年份 -32768、-1、0、2024、9999、32767，以及闰日 |
| TIME/DATETIME | 微秒 0、1、999、1000、123456、999999；23:59:59 和闰日 |
| TIMESTAMP | 0、1、业务日期、2038 边界两侧、9223372036 秒 |
| DURATION | 零、月份与秒/微秒组合、负值、混合符号，以及秒/月字段整数边界 |
| 标识 | 字符串 VID 的特殊字符、无效 UTF-8、空字符串、128 字节边界、NFC/NFD；INT64 VID 完整上下界 |

正常往返之外，真实服务端测试也确认最小 rank 的语法拒绝，以及工具的前置拒绝。报告分别记录成功覆盖和此限制，不把它算作成功搬迁样本。
