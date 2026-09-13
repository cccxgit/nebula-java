# 本次交付运行包

本目录包含两个独立的可执行 JAR：

| 文件 | 用途 |
|---|---|
| [migration-3.8.4.jar](migration-3.8.4.jar) | scan 导出、CSV 导入、目标逐键逐属性回读比较；包含 GEOGRAPHY 支持 |
| [nebula-data-verifier-3.8.4.jar](nebula-data-verifier-3.8.4.jar) | 准备 ID 计划、源/目标独立 FETCH 采集、快照离线比较 |
| [SHA256SUMS](SHA256SUMS) | 两个 JAR 的 SHA-256 摘要 |

```bash
cd /home/sch/nebula/nebula-java/task/deliverables
sha256sum -c SHA256SUMS
java -jar migration-3.8.4.jar --help
java -jar nebula-data-verifier-3.8.4.jar --help
```

构建方式：在工程根目录执行 `bash task/acceptance/build.sh`。它会构建两个模块及 client 依赖，运行本次选定的无外部数据库单元测试并生成独立运行包。真实集群验收另外由场景运行器执行，不能把打包成功等同于真实搬迁通过。

详细参数和端到端步骤见 [手动测试指导书](../acceptance/手动测试指导书.md)，实际结果见 [任务产出报告](../任务产出报告.md)。Java 运行环境要求为 8 或以上。
