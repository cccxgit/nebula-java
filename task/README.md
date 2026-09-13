# GEOGRAPHY 支持与全量场景验收

原始需求：[task.md](task.md)。

- [任务产出报告](任务产出报告.md)：交付内容、修复问题、真实验证结果和适用范围。
- [手动测试指导书](acceptance/手动测试指导书.md)：逐步执行源写入、scan、CSV、源 FETCH 基准、目标 INSERT、目标 FETCH、离线比较及统计抽样。
- [场景矩阵](acceptance/SCENARIO_MATRIX.md)：两种 VID、全部持久化类型、图结构和输入边界。
- [预制数据说明](acceptance/README-FIXTURES.md)及 [全部夹具元数据](acceptance/ngql/scenarios.json)：可直接使用 nebula-console 执行的 NGQL、预期数量及代表值分布。
- [逐场景实际报告](acceptance/reports/README.md)：JSON 原始报告及人工阅读版本。
- [故障检测矩阵](acceptance/FAULT_MATRIX.md)：验证修改内容、换边键、几何精度及文件损坏不会误报一致。
- [默认值与注释压力测试](acceptance/schema-probes/README.md)：全部属性类型、动态默认函数及二进制注释的补充验证。
- [归档审计说明](acceptance/probes/archive-audit-README.md)：核对报告、命令日志、CSV、ID 计划和原生快照，包含篡改检测探针。
- [运行包](deliverables/README.md)：本次通过构建和验收的两个独立 JAR。

`acceptance/artifacts/` 的压缩归档包含各次执行完整的 CSV、计划、原生快照、NGQL、console 输出及工具日志。解包后可独立运行 verification 的 `compare` 命令复核；报告中的 SHA-256 用于核对归档完整性。失败尝试保留，用于追溯发现问题及修复复测过程。

`acceptance/build/` 保存构建日志、单元测试 XML 和汇总；`acceptance/probes/` 保存用于定位服务端行为的可复现探针与输出。`target/` 中本地构建缓存不作为源代码提交，交付 JAR 和完整测试证据有独立存档。
