# 只读诊断与源样本核验器证据

本目录记录只读查询与核验器自身的探针。这里没有执行 INSERT，也不能据此认定全量搬迁场景已经通过。正式搬迁结果以各场景报告为准。

## 浮点参数边界

[ScalarWireProbe.java](ScalarWireProbe.java) 对参数执行 `YIELD`，再对已构造的 FLOAT/DOUBLE 非有限值源空间执行 FETCH 和 scanVertex。完整命令、源码/JAR 摘要和实际输出在 [scalar-wire-probe.txt](scalar-wire-probe.txt)。

观察结果：规范 NaN（`7ff8000000000000`）、DOUBLE 正负 Infinity、有限极值与负零在原生参数传输中保位；其他 NaN 载荷被 bundled Thrift 归一化。源 FLOAT 扫描返回规范 NaN，源 DOUBLE 扫描返回规范 NaN 和正负 Infinity。探针没有证明 FLOAT Infinity 可以写入，当前存储范围检查会拒绝它。

仓库根目录，已有构建好的 migration JAR 时，用 JDK 11+ 复现：

```bash
java -cp migration/target/migration-3.8.4.jar task/acceptance/probes/ScalarWireProbe.java
```

也可用 JDK 8 将该 Java 文件编译到临时目录再执行。需预先存在 `qa_20260914_[s/i]_[float/double]_nonfinite` 四个源空间；密码从 `NEBULA_PASSWORD` 读取，未设置时采用本地验收默认密码。

## 源样本核验器的独立测试

[FixtureSourceOracle.java](../FixtureSourceOracle.java) 使用独立 verification JAR 中的原生编码器，按 [生成的完整 oracle](../oracles/manifest.json) 查询每个预期 ID 和完整边键。它检查实际 Tag 集合、属性、源 Schema，并用 `YIELD` 独立计算预置表达式的期望值。

唯一显式的浮点精度转换是 FLOAT 的 float32 存储后提升。地理表达式由源端 `ST_GeogFromText` 自身规范化和校验；核验器不归一化实际 FETCH 值，不使用拓扑相等或误差范围。非 NULL 表达式若得到 NULL/错误 NULL 会直接失败。

[oracle-probes/probe-summary.json](oracle-probes/probe-summary.json) 保存完整执行参数和结果文件位置。五个正常探针为 VID 字节边界、FLOAT NaN、混合地理形状、NOT NULL 默认值、全部类型组合；每项核对 1,000 个点和 1,000 条边。另有七个故意改错 oracle 的探针：错误属性、丢失无属性 Tag、正负零位差异、非法地理表达式、缺失 VID、重复 VID、错误 VID 容量，均预期核验器失败。

`*-expected-fail-oracle.json` 是故意损坏的**预期文件**；没有修改数据库。对应报告中的 `status=FAILED` 是本项探针要求的结果。`probeMatchedExpectation=true` 表示核验器正确识别该错误，不能将这种失败当作成功搬迁。

复现正常 oracle，先独立编译核验器（不用运行 Maven）：

```bash
mkdir -p /tmp/nebula-fixture-oracle-classes
javac -source 8 -target 8 \
  -cp verification/target/nebula-data-verifier-3.8.4.jar \
  -d /tmp/nebula-fixture-oracle-classes task/acceptance/FixtureSourceOracle.java
java -cp verification/target/nebula-data-verifier-3.8.4.jar:/tmp/nebula-fixture-oracle-classes \
  FixtureSourceOracle --oracle task/acceptance/oracles/vid_values_s.json \
  --out /tmp/vid-values-source-oracle-new.json
```

输出路径必须不存在。将 `--oracle` 改成某个 `*-expected-fail-oracle.json` 并使用新的输出文件即可复现拒绝结果，预期退出码为 1。正常探针预期退出码为 0、`passed=true`、`validatedRows=2000`、`validatedVertices=validatedEdges=1000`。

如需重新生成 oracle，运行：

```bash
python3 task/acceptance/generate_source_oracles.py --out /tmp/nebula-source-oracles
```

生成器会重新生成夹具并核对清单和每个实际文件的 SHA-256；文件与生成器不一致时拒绝生成，避免用旧数据清单解释新表达式。
