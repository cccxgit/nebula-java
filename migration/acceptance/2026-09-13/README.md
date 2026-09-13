# 本地真实集群验收

2026-09-13，在用户提供的 `/home/sch/nebula-run/nebula-3.6` 实例上完成同一集群内的空间搬迁。Graph 地址 `127.0.0.1:9669`，服务端构建标识 `de9b3ed80`，客户端来自本仓库 `nebula-java-3.8` 分支（Maven 版本 3.8.4）。

完整验收结果：**PASSED**。原始报告见 [acceptance-report.json](acceptance-report.json)。

63 项不依赖真实数据库的专项测试及 1 项完整真实集群验收通过，合计 64 项。最终构建使用 JDK 8；打包后的 JAR 又在 JDK 17 上通过 `help`、离线 `inspect`、整数空间 `verify` 和额外一次完整 `migrate`。

这次 JAR 命令行搬迁把字符串源空间的 265 条记录导入新空间 `mig_mtzi85s0_cli_b`，scan limit 为 5，并重新确认源、目标一致，见 [CLI 搬迁报告](cli-migration-report.json)。整数空间又以 scan limit 3 验证通过，见 [CLI 整数验证报告](cli-integer-verification-report.json)。这两份报告对应包含 Storage timeout 修复的最终可执行文件。

| VID 类型 | 源空间 A | 目标空间 B | Tag 记录数 | Edge 记录数 |
|---|---|---|---:|---:|
| FIXED_STRING(128) | mig_mtzi85s0_s_a | mig_mtzi85s0_s_b | 134 | 131 |
| INT64 | mig_mtzi85s0_i_a | mig_mtzi85s0_i_b | 134 | 131 |

每组 127 个属性样本同时写入点与边，加上多 Tag、无属性记录、自环和多 rank 边，共 265 条记录；两组共 530 条迁移记录。Tag 记录数是各 Tag 的行数之和，不是去重后的点数。

覆盖全部 14 种受支持的持久化属性类型（不含 GEOGRAPHY）。源和目标分别按照独立构造的样本逐字段回读比较，每组每侧 3,605 次，合计 **14,420 次原生属性比较**。字符串直接比较字节数组，浮点直接比较 IEEE 754 位模式，时间和 DURATION 比较原始字段，不借助格式化显示或 nGQL 近似相等。

验收路径：源 INSERT → 源 FETCH 核对输入 → StorageClient.scanVertex/scanEdge → CSV 编码 → 解码 → 目标 INSERT → 目标 FETCH 核对原始样本 → 源/目标完整键和属性集合比较。

初始 scan limit 分别为 7、11；两组又分别使用 1、13、1000 重新扫描源和目标，均一致。每组产生 8 个 CSV，其中包括空 Tag/Edge 的表头文件。CSV 和完整清单仍保存在仓库 `migration/target/acceptance/mtzi85s0/bundle_s` 与 `bundle_i`，实际数据库空间也保留供复查。

额外的负向验收均通过：

- 篡改 CSV 后，SHA-256 预检拒绝。
- 目标空间已经存在时，拒绝重新导入。
- 将目标 DOUBLE 0.1 改成其相邻浮点值（只改变一个位），一致性检查发现差异；恢复后源、目标再次一致。
- 服务端拒绝最小 INT64 rank 的 INSERT 语法，工具也在导入预检时明确拒绝该值。

服务端兼容限制：此实例的 VID 位置不接受 `$参数`。普通 STRING 属性使用原生参数，可以完整保留 NUL 和无效 UTF-8；字符串 VID 使用经过所有非零字节验证的八进制字面量。INT64 VID 的最小值通过 `toInteger("-9223372036854775808")` 写入并完整回读。

**rank=-9223372036854775808 没有被计作成功搬迁样本。** 此服务端无法用 INSERT 语法表达它；十进制、八进制、十六进制、函数和算术表达式写法均被拒绝。真实成功样本覆盖 rank 范围的 `-9223372036854775807` 和 `9223372036854775807` 两端。INT64 属性与 VID 的最小值、最大值均实际往返成功。

迁移前提仍为源空间停止写入和 Schema 变更、没有活跃 TTL、没有无 Tag 的点、FIXED_STRING 和字符串 VID 不含 NUL。这里的成功结论对应上述实际服务端和样本，不代表任意版本、无限长度字符串或任意非有限浮点值已经得到验证。
