# 准确性故障检测矩阵

`run_fault_campaign.py` 用于验证：工具遇到真实差异、缺失对象或损坏文件时，能够明确拒绝通过。报告中的 **PASSED 表示成功检出预期故障，不表示被破坏的数据一致**。

每个场景使用已经通过完整搬迁验收的 **至少1000顶点 + 1000边** 基线。数据库故障每次从基线迁移包导入一个全新的目标空间，只破坏该新目标。原源空间和已经通过的目标空间保持原样。文件故障修改独立副本，保留原始基线包、计划、快照、统计和 console 抽样证据。

这里的1000点/1000边是每个故障场景的完整对照集合；单对象故障通常只修改其中一个对象，不声称每种破坏操作都执行1000次。

## 场景

| 场景 ID | 基线 | 注入内容 | 预期 verification | 数量与抽样应说明什么 |
| --- | --- | --- | --- | --- |
| `property_change` | `bool_values_s` 或 `_i` | 将1个顶点的 BOOL 属性翻转 | `DIFFERENT`，退出码2 | 点、边和每种tag/edge数量相同；针对被修改顶点的FETCH结果不同 |
| `edge_key_replace` | BOOL基线 | 删除1条旧边，保持端点与属性不变、改rank后插入1条新边 | 原计划中的旧边为`MISSING`，整体`ERROR`，退出码1 | 总数量仍相同；旧边FETCH为空、新边FETCH存在，证明数量相同不能代表身份相同 |
| `tag_membership` | BOOL基线 | 删除1个VID的原tag，再给同VID增加新的零属性`fault_extra` | `DIFFERENT`，退出码2 | 顶点和边总数相同，原tag变999份、新tag为1份；完整vertex FETCH显示tag集合变化 |
| `geography_coordinate_bit` | `geography_point_s` 或 `_i` | 从源原生快照选择一个非边界POINT，经度64位表示只翻转最低1位 | `DIFFERENT`，退出码2 | 所有数量相同；原生坐标位必须不同，console显示可能因格式精度相同，允许并记录这种情况 |
| `geography_schema_shape` | POINT基线 | 将目标tag的属性声明从`GEOGRAPHY(POINT)`改为`GEOGRAPHY(LINESTRING)`，原数据保持POINT | 采集发现原生形状不符合声明，整体`ERROR`，退出码1 | 数量可能完全相同；`SHOW CREATE TAG`明确约束变化。普通FETCH可能仍显示原POINT，也可能报查询错误，不能据显示相同而通过 |
| `bundle_integrity` | BOOL基线 | 两个独立子检查：修改CSV而不更新摘要；追加重复主键并刷新文件摘要和行数 | `prepare`和`migration import`均`ERROR`、退出码1，理由分别匹配摘要不符/重复主键 | 每个子检查使用独立新目标名称；前后SHOW SPACES确认未创建目标。复制已通过基线的统计和抽样证据 |
| `snapshot_integrity` | BOOL基线 | 四个独立子检查：截断记录文件；混用另一份真实生成计划的身份；把包含MISSING的快照标成COMPLETE；两边均包含同样的2000个MISSING | 四次均`ERROR`，退出码1 | 不写数据库；同样缺失不得视为相等，伪造完成标志不能跳过结果计数与记录校验 |

共7个场景，5个数据库故障检查，加上2个迁移包子检查和4个快照子检查，合计11个明确的检出断言。每次还会先运行无故障基线比较，必须为MATCH。

## 准备与运行

先使用 `run_campaign.py` 完成至少一个BOOL场景及一个POINT场景，确认对应工作目录存在 `case-report.json` 且状态为PASSED，同时具有原始 `bundle/`、`plan/`、`source/`、`target/`、统计和抽样目录。

```bash
python3 task/acceptance/run_fault_campaign.py \
  --baseline-work verification/target/task-campaign/bool_values_s \
  --geography-baseline-work verification/target/task-campaign/geography_point_s \
  --work verification/target/fault-campaign-20260914 \
  --reports task/acceptance/reports/faults \
  --archives task/acceptance/artifacts/faults \
  --target-prefix fault_20260914
```

基线工作目录可以位于其他位置，以实际路径为准。`--target-prefix` 需要全新且只包含字母、数字和下划线；完整生成空间名不能超过63个字符。没有显式传入时，脚本自动使用时间和随机后缀。

只执行文件故障，无需 geography 基线。迁移包检查会查询SHOW SPACES并执行必须在预检阶段拒绝的import；快照检查完全离线：

```bash
python3 task/acceptance/run_fault_campaign.py \
  --baseline-work verification/target/task-campaign/bool_values_s \
  --work verification/target/file-faults-20260914 \
  --only bundle_integrity,snapshot_integrity
```

脚本仍需使用已经打包的 verifier JAR 进行实际 prepare/compare。默认 JAR 路径与 `run_campaign.py` 相同，脚本不会构建、修改或发布代码。

数据库连接参数支持 `--console`、`--host`、`--port`、`--meta-port`、`--user` 和 `--password-env`，与主campaign保持一致。建议使用已有安全环境变量传递密码，报告会隐藏命令行中的密码值。

## 每个数据库场景的执行顺序

1. 验证历史基线报告通过，且至少包含1000点和1000边。
2. 复制原始基线证据，并重新离线比较基线源/目标快照，必须MATCH。
3. 在源空间重新执行统计、console抽样和原生FETCH采集，并与原基线比较，确认源内容稳定。
4. **源采集完成后**，从独立迁移包副本导入全新目标空间。
5. 对新目标采集、比较、统计和抽样，确认无故障时仍MATCH。
6. 将目标变更保存为完整、可由`nebula-console -f`执行的`.ngql`文件，然后执行故障注入。需要新建或改变Schema的场景单独等待元数据传播。
7. 再次采集目标，用同一份计划比较，核对期望状态、退出码与差异。
8. 重新完成源/目标统计任务，核对预期计数。源统计不得改变。
9. 保存原始FETCH抽样及针对故障对象的额外查询；允许缺失、查询错误或Schema变化场景呈现其真实结果。
10. 每个场景独立生成报告和完整证据压缩包。失败立即停止，不继续把后续场景标记通过。

脚本不删除空间，也不清理注入后的新目标，便于人工复核；报告中保存实际目标名称。任何已有工作目录、同名报告或压缩包都会导致拒绝覆盖。需要重跑时选择新的工作目录和目标前缀。

## 地理坐标位变化的判定

脚本读取源快照中 `GEOGRAPHY(POINT)` 的原生坐标十六进制位表示，选择经度绝对值在1与180之间的点，把经度位模式与整数1异或，再以17位有效数字构造nGQL中的WKT。纬度保持原位模式。

目标采集后，脚本不仅要求verification返回DIFFERENT，还检查目标坐标必须恰好等于预期的经度新位模式和原纬度位模式。因此，如果服务端归一化消除了注入的变化，场景会失败，不能冒充已测试了“一位差异”。报告保留 `oldXBits`、`newXBits`、`xor` 和 `nativeOneBitChangeConfirmed`。

console打印可能舍入两个相邻浮点值，因此该场景允许原始FETCH显示相同，并记录 `displayPrecisionIsNotNativeEquality=true`。是否通过取决于原生位断言和verification结果，不能要求console字符串必然显示出一位差异。

地理形状Schema注入依据本地3.6源码 `src/meta/MetaServiceUtils.cpp` 的 `isLegalTypeConversion`：除FIXED_STRING外，相同基础类型允许转换，GEOGRAPHY的形状约束仍属于同一基础类型。因此该版本预期允许POINT改为LINESTRING。脚本不把ALTER被拒绝计作成功检出；若其他服务端版本拒绝此变更，场景会停止并报告注入失败，需要按该版本另设计可执行的Schema差异场景。

脚本也提供显式替代模式 `--schema-shape-mode generic`：将POINT约束放宽为泛型GEOGRAPHY，保持原生POINT值不变，预期改为`DIFFERENT`、退出码2。这一模式检查“声明约束不同而数据值相同”能否检出，与默认的“原生形状不符合新声明”是不同断言。脚本不会自动退回该模式，应使用新的工作目录和目标前缀单独执行、如实记录。

## 证据与报告

每个场景的工作目录包括：

- `baseline-proof/`：完整控制迁移包、计划、原始通过快照、统计与console样本。
- 数据库场景的 `source/`、`target-before/`、`target-after/`：故障前后的原生采集。
- `mutation/*.ngql`：可复制执行的全部目标变更；Schema场景还包含 `schema/` 中的前后定义。
- 新提交统计任务的ID、状态、`SHOW STATS`完整输出和解析结果。
- `fault-source-console/`、`fault-target-console/`：原始查询、原始输出及规范化显示表。
- 文件故障的独立损坏副本、生成的另一份计划、每个检出子检查报告。
- `fault-report.json`、`fault-report.md`：预期状态、实际状态、退出码、计数、时间和证据摘要。

外部报告目录每场景一个JSON和Markdown，压缩目录每场景一个`.tar.gz`。Markdown包含控制数据量、实际差异/错误、统计和抽样结果、JSON及压缩包链接。每个场景结束后更新本批次的`<target-prefix>_summary.json`和`.md`，区分通过、失败和尚未执行的场景。压缩包记录大小和SHA-256；每个场景归档不重复放入运行JAR，本次JAR统一交付于`task/deliverables/`，构建测试证据位于`task/acceptance/build/`。

CSV摘要、计划身份和快照计数检查用于发现损坏、重复、混批及未完成数据。快照负向案例是明确标记的文件故障注入，不声称在数据库中删除了2000个对象；验证结果也不等同于对恶意重写全部文件及摘要的数字签名认证。

## 最终构建的执行版本绑定

最终构建重跑使用独立的 `reports/final-faults/` 和 `artifacts/final-faults/`，不得从历史 `faults/` 补足未完成项。运行时保留固定的 `execution-artifacts.json`，列出实际执行与交付的两个 JAR、执行脚本及 fixture 清单的大小和 SHA-256。启动前应实际核对执行 JAR 与交付 JAR 一致；如清单文件在启动后才写出，应如实记载已经发生的启动前检查，不能把文件写入时间伪装成检查时间。地理批次还保存 `geography-launch-check.json` 作为其启动前的固定文件复核记录。

全部七项完成后，`finalize_fault_reports.py` 进行离线审计和绑定：

```bash
python3 task/acceptance/finalize_fault_reports.py \
  --reports task/acceptance/reports/final-faults \
  --prelaunch-verification-note '填入本轮确实完成的启动前SHA-256核验记录及清单写入时机，不能把事后检查声明为启动前检查'
```

该工具要求七项最新报告均通过、每项基线至少1000点和1000边，核对真实 compare 状态和退出码、全部命令日志、采集文件、归档内部原始报告和摘要，并再次核对固定执行文件未变。任何缺项、差异或已有绑定都会拒绝继续。它生成 `final-execution-audit.json`、每场景的 `.execution-binding.json`、最终汇总和证据审计。每份外部报告增加两个引用字段 `executionArtifacts` 与 `executionBinding`；归档及归档内的原始报告保持原字节，绑定文件记录原外部报告摘要以供复核。

绑定同时检查每条实际 `java -jar` 命令，记录真正调用的 JAR 集合。`snapshot_integrity` 仅调用 verifier，其余六项调用 migration 与 verifier；不能把构建目录里存在但未调用的 JAR 声称为该场景实际执行。最终交付报告生成器必须验证这些绑定，不能仅凭旧报告的 PASSED 或目前文件名相同认定它属于最终构建。摘要绑定用于证据一致性和构建来源复核，不替代可信时间戳或数字签名。
