# 预置 nGQL 数据生成器

本目录的 `generate_fixtures.py` 仅使用 Python 3 标准库，生成文件而不连接数据库。`ngql/scenarios.json` 是执行器和人工验收共用的场景清单。

当前矩阵包含 **40 类正向场景 × 2 种 VID = 80 次搬迁验收**。每次正向验收包含 1,000 个不同 VID 的顶点和 1,000 条不同主键的边；多 tag 场景另外写入 1,000 份零属性 tag。另有 **5 类负向场景 × 2 种 VID = 10 组**，每组包含 1,000 次预期拒绝的请求。这些请求不计入成功搬迁数据量。

每类场景内部循环使用若干代表值。**“每场景 1,000 条”不表示每个代表值均出现 1,000 次。**清单中的 `variantCounts` 和 `variantDefinitions[].recordsPerTable` 给出具体分配；VID 场景另给出 `vidCoverage`。完整矩阵见 [SCENARIO_MATRIX.md](SCENARIO_MATRIX.md)。

## 重新生成

```bash
python3 task/acceptance/generate_fixtures.py \
  --all --output task/acceptance/ngql --prefix qa_20260914 --rows 1000
```

单独生成某个场景：

```bash
python3 task/acceptance/generate_fixtures.py \
  --output /tmp/nebula-fixtures --scenario geography_polygon \
  --vid-type 'FIXED_STRING(256)' --space qa_polygon_source --rows 1000
```

同一参数生成的文件内容、顺序和 SHA-256 完全确定。重复执行会覆盖同一路径的预置文件；应在执行验收之前选择好前缀。图空间名称必须与已有业务空间区分。

## 文件分工

每个场景目录包含四个（负向场景另外包含 `control.ngql`）可由 `nebula-console -f` 直接读取的文件，每行一个完整的 nGQL 语句：

| 文件 | 内容 | 执行时机 |
| --- | --- | --- |
| `space.ngql` | 仅 `CREATE SPACE`，7 分区、1 副本 | 首先执行；随后等待元数据传播 |
| `schema.ngql` | `USE` 和 `CREATE TAG/EDGE` | 空间生效后执行；随后再次等待传播 |
| `data.ngql` | `USE` 和所有 `INSERT`；负向文件中每条均预期拒绝 | Schema 生效后执行；负向先执行合法对照文件 |
| `control.ngql` | 仅负向场景提供，1000合法点和1000合法边 | 在拒绝请求前执行，建立验证基线 |
| `sample.ngql` | `USE` 和前、中、末位置的 3 个顶点及 3 条边的 `FETCH` | 搬迁前后各执行一次 |

例如：

```bash
/path/to/nebula-console -addr 127.0.0.1 -port 9669 -u root -p '<password>' \
  -f task/acceptance/ngql/geography_polygon_s/space.ngql

# 等待空间传播后执行 schema.ngql，等待 Schema 传播后执行 data.ngql。
```

`space.ngql` 不使用 `IF NOT EXISTS`，防止误把历史残留空间当作空白测试空间。不得把四个文件连成一批而省略传播等待。

## 执行器接口

```python
from task.acceptance.generate_fixtures import build_scenarios, generate, generate_all, render_sample_queries

# 纯内存场景定义。
definitions = build_scenarios()

# 写单个场景，返回该项 metadata。
case = generate(output_dir, "INT64", "geography_point", "qa_source", rows=1000)

# 写完整矩阵，返回 manifest；同时保存 output_dir/scenarios.json。
manifest = generate_all(output_dir, prefix="qa", rows=1000)

# 仅修改 USE 空间，FETCH 查询内容保持相同，分别针对源/目标运行。
source_sample = render_sample_queries(case, case["sourceSpace"])
target_sample = render_sample_queries(case, case["targetSpace"])
```

主要清单字段：

| 字段 | 含义 |
| --- | --- |
| `cases[]` | 每种 VID、每个场景独立的一次执行 |
| `id` / `scenarioId` | 执行 ID / 场景定义 ID |
| `vidType` | `FIXED_STRING(256)` 或 `INT64` |
| `sourceSpace` / `targetSpace` | 源空间 / 默认建议目标空间；执行器可使用自己的新目标名称 |
| `spaceFile` / `schemaFile` / `dataFile` / `sampleFile` | 相对于生成输出目录的文件路径 |
| `controlFile` | 负向场景专用合法对照文件 |
| `negativeExpectedBefore` / `negativeExpectedAfter` | 拒绝请求前后的完整预期统计对象，各1000顶点、1000边 |
| `expected.vertices` / `expected.edges` | 正向预期的不同顶点、边数量 |
| `expected.tags` / `expected.edgeTypes` | 对应 `SHOW STATS` 的每个 tag/edge 类型记录数 |
| `sampleQueries` | 无 `USE` 的独立查询列表，源/目标复用 |
| `sampleIndices` | 默认为 `0, 500, 999` |
| `columns` | 属性名称和声明类型 |
| `variantDefinitions` / `variantCounts` | 代表值表达式或组合示例，以及出现次数 |
| `scanLimits` | 通常为 `127`；分页场景为 `1,7,127,1000,1001` |
| `files` | 每个文件的字节数及 SHA-256 |
| `negative` / `expectedRejects` | 是否拒绝请求场景及预期拒绝次数 |

多 tag 场景 `expected.vertices=1000`，但两个 tag 的记录数各为 1000，不能把 tag 记录数相加当作不同顶点总数。平行边场景仍有 1000 个顶点，但 1000 条边共同使用两个端点，以 rank 区分。

## 正向、负向验收边界

正向场景必须完整完成：源写入成功、搬迁成功、verification 原生快照比较通过、源/目标 `SUBMIT JOB STATS` 完成后统计一致、源/目标独立 FETCH 抽样对照。console 的显示结果只作辅助，不能替代二进制字符串和浮点等原生值比较。

负向场景应使用独立的新源空间。执行器先运行场景的 `controlFile`，创建1000个合法顶点和1000条合法边，保存 verification/统计基线，再执行 1000 次预期拒绝请求，核对拒绝次数，最后对对照数据执行搬迁、verification 与统计校验，确认未被污染。`data.ngql` 中的预期错误不应被当作导入器故障直接吞掉；需要核对每条请求结果。负向清单 `expected`、`negativeExpectedBefore` 和 `negativeExpectedAfter` 均包含1000个合法顶点和1000条合法边。`expectedAddedByRejectedRequests` 为零，表示拒绝文件自身不得增加点或边；也不得修改既有对照值。

## 字符串、浮点和地理数据约定

- 所有字符串字节均生成三位八进制转义。graphd 必须允许八进制转义；可先执行 `YIELD size("\000") AS byte_count;`，应为 `1`。如果结果不同或报错，应在数据写入前停止。
- STRING 属性包含全部 256 字节及中间 NUL。字符串 VID 和 FIXED_STRING 不包含 NUL，原因是源端扫描/定长字符串路径自身会截断，不能靠迁移文件恢复。禁止声称覆盖原存储中不可见的 NUL 后缀。
- 字符串 VID 场景覆盖空串、1 至 256 字节长度、1 至 255 的所有单字节、NFC/NFD、引号、换行、制表符、emoji 和无效 UTF-8。整数 VID 覆盖完整 int64 上下界以及超出 double 精确整数范围的值。
- FLOAT 首次入库会按 float32 精度存储；Geography 首次入库可能合法归一化，例如移除极近的连续点。比较基准是源库实际返回的原生值，不能把输入 WKT 的排版或输入十进制文本当作存储结果。
- FLOAT 的 NaN 可以写入，但 FLOAT 的正负 Infinity 被当前存储范围检查拒绝，单列负向场景；DOUBLE 的 NaN 和正负 Infinity 属于正向场景。NaN 场景覆盖数据库返回的 NaN 值，不承诺保留不同 NaN payload。正负零和有限浮点值需要按工具定义的规范位模式比较。
- TIMESTAMP 的存储写入路径限定 `[0, 9223372036]` 秒；整数本身可达 INT64 上界，不表示 TIMESTAMP 属性能写入该上界。DURATION 的微秒字段则保留完整 int32，`duration_microsecond_bounds` 独立覆盖 `-2147483648`、`2147483647`、相邻值与 `±1000000`，不把跨秒微秒归一化；详见 [写入路径依据](probes/temporal-storage-boundaries.md)。
- 主长字符串场景每条为 64KiB，使用 `lpad` 服务端生成 ASCII、UTF-8 和全字节模式，保持 `.ngql` 文件较小。已有 1MiB 样本作为补充，不代表所有长度或资源规模都已验证。
- 当前服务端不支持 `rank=-9223372036854775808` 的字面量语法，因此该值单列为拒绝场景；可写下界 `-9223372036854775807` 属于正向 rank 场景。
- 不测试高并发、性能、断点恢复。这套有限矩阵也不代表已经穷尽任意输入组合、所有服务端版本或所有物理存储状态。

`expression-probe-report.json` 记录生成前在实际服务上的只读表达式验证。它没有执行 CREATE/INSERT，不能代替完整搬迁验收报告。
