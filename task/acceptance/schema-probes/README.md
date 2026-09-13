# 补充 Schema、DEFAULT 与 COMMENT 验收

这组样本独立于冻结的 90 项主矩阵，专门检测 `SHOW CREATE` 展示文本无法忠实重建默认值与注释的问题。不要将本目录的预期值或小规模 DDL 试探当作完整搬迁验收结果。

## 两个正式补充场景

| 场景 | 源空间 | 目标空间 | VID | 数据量 |
|---|---|---|---|---|
| `schema_defaults_s` | `qa_schema_260914_s_source` | `qa_schema_260914_s_target` | FIXED_STRING(256) | 1000 点、1000 边 |
| `schema_defaults_i` | `qa_schema_260914_i_source` | `qa_schema_260914_i_target` | INT64 | 1000 点、1000 边 |

每个空间有 `defaults_tag` 与 `defaults_edge`，分别含 30 个属性。所有 INSERT 都省略全部属性，由服务端使用 DEFAULT：

- 15 种持久化属性类型；GEOGRAPHY 包括通用、POINT、LINESTRING、POLYGON 四种声明。
- 整数边界；FLOAT 的 0.1、负零、NaN；DOUBLE 最大有限值、负零、NaN、正 Infinity。
- STRING 默认值包含全部 256 字节、NUL、无效 UTF-8、引号、反斜杠、CR/LF、组合字符、emoji；另有空字符串与正常 NULL。FIXED_STRING 遵守无 NUL 的已有前提。
- DATE、TIME/DATETIME 微秒和 TIMESTAMP 上界；DURATION 保留原始 int32 微秒边界。
- 列 COMMENT 区分不存在、空字符串和特殊字节；表 COMMENT 恰好包含全部 256 字节；空间 COMMENT 包含 NUL、无效 UTF-8 及特殊字符。
- `timestamp()`、`rand64()`、`tostring(rand64())` 三个动态 DEFAULT，必须保留原表达式，不能用建表时的求值结果替换。

完整列定义、原表达式、COMMENT 预期 Base64、文件 SHA-256 与抽样语句见 [scenarios.json](scenarios.json)。生成器 [generate_schema_stress.py](generate_schema_stress.py) 不连接数据库，也不修改主矩阵。

27 个静态属性和 3 个动态属性的具体分布如下；两个 Tag/Edge 使用相同定义。

| 持久化类型 | 属性 | 代表 DEFAULT |
|---|---|---|
| BOOL | `b` | false |
| INT8 / INT16 / INT32 / INT64 | `i8` / `i16` / `i32` / `i64` | 各类型最小值；INT64 用 `toInteger` 表达最小值 |
| FLOAT | `f32`、`f32_zero`、`f32_nan` | 0.1、负零、规范 NaN |
| DOUBLE | `f64`、`f64_zero`、`f64_nan`、`f64_inf` | 最大有限值、负零、规范 NaN、正 Infinity |
| STRING | `s_all_bytes`、`s_invalid_utf8`、`s_special`、`s_empty`、`s_null` | 0–255 全部字节、无效 UTF-8、特殊字符、空字符串、NULL |
| FIXED_STRING(128) | `fs` | 中文、引号、反斜杠、CR/LF、emoji；不含 NUL |
| DATE | `d` | 2024-02-29 |
| TIME | `t` | 23:59:59.999999 |
| DATETIME | `dt` | 2024-02-29T23:59:59.123456 |
| TIMESTAMP | `ts` | 服务端可存储上界 9223372036 秒 |
| DURATION | `du` | months 和 microseconds 均为 -2147483648，seconds 为 0 |
| GEOGRAPHY（ANY / POINT / LINESTRING / POLYGON） | `g`、`gp`、`gl`、`ga` | 通用线、负零点、跨日期变更线的线、含内环的面；以源原生返回结构为准 |
| 动态 DEFAULT（额外 3 列） | `dynamic_ts`、`dynamic_rand`、`dynamic_text` | TIMESTAMP 的 `timestamp()`、INT64 的 `rand64()`、STRING 的 `tostring(rand64())` |

四种整数宽度分别计为持久化类型，GEOGRAPHY 的四种声明合计一种，共 15 种类型。本表说明默认值专项覆盖，不代替主矩阵每种类型的全部边界样本。

按 `space.ngql → 等待元数据传播 → schema.ngql → 等待 → data.ngql` 执行对应 `ngql/schema_defaults_s` 或 `_i` 目录中的文件。正式源空间由主验收流程创建，重复运行前应选新空间并重新生成独立夹具，不能覆盖已有数据。

## 源样本核验

动态 DEFAULT 的具体值在写入时产生，所以这组样本不使用主矩阵中“重新计算所有表达式并逐值比较”的通用 oracle。对应替代检查明确分为两部分：

1. [SchemaMetadataProbe.java](SchemaMetadataProbe.java) 原生读取 Meta，检查列/表/空间 COMMENT 字节、默认值存在性、静态字符串原字节及动态表达式没有被改成常量。记录完整 Schema 二进制、各默认表达式原始 Base64。它对常量表达式的解码仅用于诊断，不是搬迁器的重建备用路径。
2. [SchemaDefaultsOracle.java](SchemaDefaultsOracle.java) 检查 27 个静态 DEFAULT 的 Meta 常量与原始 YIELD 表达式一致，再核对每条点/边实际值；FLOAT 仅应用明确的 float32 存储转换。动态三列核对原表达式文本、非恒定表达式类别与实际返回类型，随机默认值还应产生不同结果。它不把重新执行 rand64 得到的结果拿来比较已有记录。

逐条检查同时包含完整 VID/边键、Tag 存在性与属性集合。后续搬迁仍必须保存源 FETCH 基准，再导入目标并独立 FETCH 比较，所有动态属性的**已存储值**仍须精确相同；再检查两端 SHOW STATS 和固定 console 抽样。

独立编译和调用（不会运行 Maven）：

```bash
mkdir -p /tmp/nebula-schema-probe-classes
javac -source 8 -target 8 \
  -cp verification/target/nebula-data-verifier-3.8.4.jar \
  -d /tmp/nebula-schema-probe-classes \
  task/acceptance/schema-probes/SchemaMetadataProbe.java \
  task/acceptance/schema-probes/SchemaDefaultsOracle.java
java -cp verification/target/nebula-data-verifier-3.8.4.jar:/tmp/nebula-schema-probe-classes \
  SchemaDefaultsOracle qa_schema_260914_s_source \
  task/acceptance/schema-probes/scenarios.json schema_defaults_s \
  /tmp/schema-defaults-s-source-new.json
```

辅助程序仅连接本机 `127.0.0.1:9669/9559`，Graph 密码从 `NEBULA_PASSWORD` 读取，默认是本地验收密码。输出文件必须不存在；成功输出包含 `passed=true`、`validatedRows=2000`、两类记录各 1000，以及 Schema/COMMENT 和动态表达式检查结果。Meta 明细保存在同名 `.metadata.json` 文件。

源、目标都运行上述 oracle，正式补充报告使用 `reports/schema-stress/schema_defaults_s-source-oracle.json`、`schema_defaults_s-target-oracle.json`（INT64 对应 `_i`）。随后独立比较其原生 Meta 明细：

```bash
python3 task/acceptance/schema-probes/compare_metadata.py \
  --source task/acceptance/reports/schema-stress/schema_defaults_s-source-oracle.json.metadata.json \
  --target task/acceptance/reports/schema-stress/schema_defaults_s-target-oracle.json.metadata.json \
  --out task/acceptance/reports/schema-stress/schema_defaults_s-metadata-comparison.json
```

比较器要求双方均通过预期检查、case 相同、Tag/Edge 名称集合相同，然后比较空间 COMMENT 原始 Base64 和各表完整 `nativeSchemaBase64`。后者包含列默认表达式、列 COMMENT、表 COMMENT 与可选字段存在性；两端空间名称与采集时间不参与相等判断。退出码 0、`matched=true`、`status=MATCH` 才表示这些原始 Schema 字节相等。

## 手动完成源到新目标的全流程

下面复用本次保留的源空间，创建一个**尚不存在的新目标空间**；不会重写本次已验收目标。执行前保持源数据和 Schema 不变，并确认当前服务端与源默认表达式编码兼容。示例在仓库根目录执行，先令 `SCHEMA_SUFFIX=s`，完成后可改为 `i` 再执行一遍。所有新证据写到 `task/acceptance` 下的新目录。

```bash
set -euo pipefail
export NEBULA_PASSWORD='nebula'
SCHEMA_SUFFIX=s
SCHEMA_CASE="schema_defaults_${SCHEMA_SUFFIX}"
SCHEMA_SOURCE="qa_schema_260914_${SCHEMA_SUFFIX}_source"
SCHEMA_STAMP="$(date +%Y%m%d_%H%M%S)"
SCHEMA_TARGET="manual_schema_${SCHEMA_STAMP}_${SCHEMA_SUFFIX}"
SCHEMA_RUN_DIR="$(pwd)/task/acceptance/manual-schema-${SCHEMA_STAMP}-${SCHEMA_SUFFIX}"
mkdir -p "$SCHEMA_RUN_DIR/classes"
javac -source 8 -target 8 \
  -cp verification/target/nebula-data-verifier-3.8.4.jar \
  -d "$SCHEMA_RUN_DIR/classes" \
  task/acceptance/schema-probes/SchemaMetadataProbe.java \
  task/acceptance/schema-probes/SchemaDefaultsOracle.java
SCHEMA_CP="verification/target/nebula-data-verifier-3.8.4.jar:$SCHEMA_RUN_DIR/classes"

java -cp "$SCHEMA_CP" SchemaDefaultsOracle "$SCHEMA_SOURCE" \
  task/acceptance/schema-probes/scenarios.json "$SCHEMA_CASE" "$SCHEMA_RUN_DIR/source-oracle.json"
java -Xmx2g -jar migration/target/migration-3.8.4.jar export \
  --source-space "$SCHEMA_SOURCE" --directory "$SCHEMA_RUN_DIR/bundle" --scan-limit 113
java -jar migration/target/migration-3.8.4.jar inspect --directory "$SCHEMA_RUN_DIR/bundle"
java -jar verification/target/nebula-data-verifier-3.8.4.jar prepare \
  --migration-dir "$SCHEMA_RUN_DIR/bundle" --out "$SCHEMA_RUN_DIR/plan"
java -Xmx2g -jar verification/target/nebula-data-verifier-3.8.4.jar capture \
  --plan "$SCHEMA_RUN_DIR/plan" --space "$SCHEMA_SOURCE" --out "$SCHEMA_RUN_DIR/source"

# 源 FETCH 基准完成后才创建并写入新目标。
java -Xmx2g -jar migration/target/migration-3.8.4.jar import \
  --target-space "$SCHEMA_TARGET" --directory "$SCHEMA_RUN_DIR/bundle" --schema-wait-ms 20000
java -Xmx2g -jar migration/target/migration-3.8.4.jar verify \
  --target-space "$SCHEMA_TARGET" --directory "$SCHEMA_RUN_DIR/bundle"
java -Xmx2g -jar verification/target/nebula-data-verifier-3.8.4.jar capture \
  --plan "$SCHEMA_RUN_DIR/plan" --space "$SCHEMA_TARGET" --out "$SCHEMA_RUN_DIR/target"
java -Xmx2g -jar verification/target/nebula-data-verifier-3.8.4.jar compare \
  --source "$SCHEMA_RUN_DIR/source" --target "$SCHEMA_RUN_DIR/target" --out "$SCHEMA_RUN_DIR/comparison"
java -cp "$SCHEMA_CP" SchemaDefaultsOracle "$SCHEMA_TARGET" \
  task/acceptance/schema-probes/scenarios.json "$SCHEMA_CASE" "$SCHEMA_RUN_DIR/target-oracle.json"
python3 task/acceptance/schema-probes/compare_metadata.py \
  --source "$SCHEMA_RUN_DIR/source-oracle.json.metadata.json" \
  --target "$SCHEMA_RUN_DIR/target-oracle.json.metadata.json" \
  --out "$SCHEMA_RUN_DIR/metadata-comparison.json"

python3 - "$SCHEMA_RUN_DIR" <<'PY'
from pathlib import Path
import hashlib, json, sys
root = Path(sys.argv[1])
source, target = [json.loads((root / (side + '-oracle.json')).read_bytes()) for side in ('source', 'target')]
for side, report in [('source', source), ('target', target)]:
    assert report['passed'] is True and report['status'] == 'PASSED'
    assert report['validatedVertices'] == report['validatedEdges'] == 1000
    assert report['validatedRows'] == len(report['records']) == 2000
    assert report['schemaAndCommentsMatched'] is True
    assert report['dynamicOriginalExpressionsRetained'] is True
    assert report['metadataReportSha256'] == hashlib.sha256((root / (side + '-oracle.json.metadata.json')).read_bytes()).hexdigest()
assert source['records'] == target['records']
for name in ['comparison/comparison-report.json', 'metadata-comparison.json']:
    result = json.loads((root / name).read_bytes())
    assert result['matched'] is True and result['status'] == 'MATCH'
print('PASS: 2000 complete records, dynamic stored values, native Schema and COMMENT bytes')
PY
```

数量检查仍需在源和新目标各提交一次 `SUBMIT JOB STATS;`，记录返回 ID，等待 `SHOW JOB <id>;` 为 FINISHED 后执行 `SHOW STATS;`。`defaults_tag`、`defaults_edge`、`vertices`、`edges` 均应为 1000。这组空间只有一个 Tag，所以 Tag 行数恰好等于点数。不要使用其他空间或旧任务的统计结果。

源抽样文件为 `ngql/schema_defaults_s/sample.ngql`（INT64 对应 `_i`）；复制到新运行目录，只将首行 `USE` 改为本次新目标名称，分别通过 nebula-console `-f` 执行原文件和新文件。所有正向 console 输出不得含 `[ERROR (`。console 会格式化二进制与浮点，其显示比较不能替代上面的原生值和元数据检查。

## 已执行的小规模 DDL 试探

`qa_schema_260914_ddl_probe` 是独立的 INT64 试探空间，不属于两个正式补充样本或主 90 项。该空间成功创建全部 30 列的 Tag/Edge；写入 2 个点和 1 条边，全部属性省略以触发默认值，随后 FETCH 成功。没有将不支持的地理类型混进失败建表请求，也没有删减默认值。

原始证据在 [ddl-probe](ddl-probe)：`space-result.txt`、`schema-result.txt`、`data-result.txt`；原生 Schema/default/comment 核对结果为 [native-metadata.json](ddl-probe/native-metadata.json)。结果日志可能含 NUL 与无效 UTF-8，应按字节读取。此小试探没有覆盖正式两组各 1000 点、1000 边的搬迁闭环。

## 两组正式补充样本的结果

2026-09-14（北京时间），两种 VID 场景的源、目标 oracle 均通过，各自检查 1000 个点和 1000 条边。每条记录的完整身份、属性集合和 `fieldsSha256` 有序列表完全一致，包括动态 DEFAULT 在源端已产生的实际值。两端原生 Meta 比较也通过：每组 2 张表、60 份默认表达式，以及空间/表/列 COMMENT 的字节完全相同。

| VID | 源 oracle | 目标 oracle | 原生 Meta 比较 | 完整记录比较 |
|---|---|---|---|---|
| FIXED_STRING(256) | [PASSED](../reports/schema-stress/schema_defaults_s-source-oracle.json) | [PASSED](../reports/schema-stress/schema_defaults_s-target-oracle.json) | [MATCH](../reports/schema-stress/schema_defaults_s-metadata-comparison.json) | [2000 行 MATCH](../reports/schema-stress/schema_defaults_s-record-comparison.json) |
| INT64 | [PASSED](../reports/schema-stress/schema_defaults_i-source-oracle.json) | [PASSED](../reports/schema-stress/schema_defaults_i-target-oracle.json) | [MATCH](../reports/schema-stress/schema_defaults_i-metadata-comparison.json) | [2000 行 MATCH](../reports/schema-stress/schema_defaults_i-record-comparison.json) |

原生 Meta 明细位于对应 oracle 的 `.json.metadata.json` 文件；oracle 记录其 SHA-256，比较报告绑定两端输入文件的 SHA-256。上述结果仅代表这两个同集群、不同空间的补充场景，主矩阵结果另见 [主验收目录](../reports)。

比较器另外保存了 [5 个离线探针](comparison-probes/summary.json)：相同输入应 MATCH，空间注释变化应 DIFFERENT，Schema 摘要损坏、重复表及未完成预期检查应 ERROR。输入及对应输出全部在 [comparison-probes](comparison-probes)，这些人工构造探针不算真实迁移样本。历史试探与执行摘要已归档到 [prototype-logs](../probes/prototype-logs)，[归档清单](../probes/prototype-logs/archive.json) 保存原路径、字节数和 SHA-256；正式判定以上面的逐场景报告为准。

## 原始 Meta Schema 复制的实现边界

Java 已生成 `MetaService.Client.createTag/createEdge` 与对应 request，可用原生 `Schema` 保留 `ColumnDef.default_value`、`ColumnDef.comment`、`SchemaProp.comment` 及可选字段的存在性。不要把 SHOW CREATE 的字符串默认值、裸 DATE 或 COMMENT 展示文本当成可直接执行的 DDL。

本机 Meta 请求不带 Graph 用户或 session 身份；搬迁应保留已有流程中已认证 Graph 会话创建全新空间的步骤，再在该空间复制 Schema。开启授权时，服务端 `PermissionManager::canWriteSpace` 只允许 GOD 创建空间。Meta 写入遇到 `E_LEADER_CHANGED` 时明确失败，不应把响应当作成功；当前工具不提供重试/断点恢复。

默认表达式是 Nebula 内部编码，包含表达式种类、部分原生宽度数据和 Compact Thrift 值。源码要求表达式枚举只在末尾追加以维持兼容，但 `verifyClientVersion` 只检查客户端白名单，不证明所有跨版本/平台的默认表达式编码兼容。需要兼容的源、目标服务器编码；建表后必须原生回读完整 Schema，逐字节验证。Meta 的类型检查还可能重编码超长 FIXED_STRING 默认值，写后验证不可省略。动态函数在目标建表时会被检查求值，但必须继续保留原表达式字节。

本次 PASSED 明确限于本机 NebulaGraph 3.6、同一个集群、同版本与同平台的原生 DEFAULT 编码。未以本组结果证明异版本、异平台或异地集群的表达式编码兼容；这些环境需要独立进行建表后原生字节检查与数据往返验收。
