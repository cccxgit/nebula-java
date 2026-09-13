# 全量场景矩阵

每一行分别在 `FIXED_STRING(256)` 和 `INT64` 图空间执行。正向每个执行项固定为 **1000 个不同顶点 + 1000 条不同主键的边**；每行内部各代表值循环分配，具体次数见 `ngql/scenarios.json`，不声称每个代表值都出现 1000 次。

| # | 场景 ID | 测试内容 | 声明类型 | 每种 VID 的数据量 |
| --- | --- | --- | --- | --- |
| 1 | `vid_values` | VID全范围代表值：整数上下界或空串、1..256字节、1..255单字节、特殊字符及无效UTF-8 | STRING | 1000 点 + 1000 边 |
| 2 | `bool_values` | BOOL真假值 | BOOL | 1000 点 + 1000 边 |
| 3 | `int8_values` | INT8上下界、相邻值、零和正负值 | INT8 | 1000 点 + 1000 边 |
| 4 | `int16_values` | INT16上下界、相邻值、零和正负值 | INT16 | 1000 点 + 1000 边 |
| 5 | `int32_values` | INT32上下界、相邻值、零和正负值 | INT32 | 1000 点 + 1000 边 |
| 6 | `int64_values` | INT64上下界、相邻值、零和正负值 | INT64 | 1000 点 + 1000 边 |
| 7 | `float_finite` | FLOAT最大有限值、最小正规及次正规数、小数和相邻值 | FLOAT | 1000 点 + 1000 边 |
| 8 | `float_signed_zero` | FLOAT正零和负零按IEEE754校验 | FLOAT | 1000 点 + 1000 边 |
| 9 | `float_nonfinite` | FLOAT NaN；FLOAT Infinity由存储拒绝，另列负向 | FLOAT | 1000 点 + 1000 边 |
| 10 | `double_finite` | DOUBLE最大有限值、最小正规及次正规数、小数和相邻值 | DOUBLE | 1000 点 + 1000 边 |
| 11 | `double_signed_zero` | DOUBLE正零和负零按IEEE754校验 | DOUBLE | 1000 点 + 1000 边 |
| 12 | `double_nonfinite` | DOUBLE NaN、正负Infinity；采用源原生读取值为基准 | DOUBLE | 1000 点 + 1000 边 |
| 13 | `string_special` | STRING所有特殊字符类别且不做Unicode规范化 | STRING | 1000 点 + 1000 边 |
| 14 | `string_all_bytes` | 每条STRING均包含全部256字节，包含NUL及不可解码字节 | STRING | 1000 点 + 1000 边 |
| 15 | `string_invalid_utf8` | 无效UTF-8、截断序列、过长编码及代理区字节 | STRING | 1000 点 + 1000 边 |
| 16 | `string_long` | 每条64KiB长STRING，以lpad服务端生成ASCII、UTF-8或全字节混合 | STRING | 1000 点 + 1000 边 |
| 17 | `string_empty` | STRING空值长度为0，与NULL区分 | STRING | 1000 点 + 1000 边 |
| 18 | `string_literal_null` | 数据库NULL、空串、字面NULL/N/V:之间不混淆 | STRING | 1000 点 + 1000 边 |
| 19 | `fixed_string_special` | FIXED_STRING保留特殊字符，不含源接口不支持的NUL | FIXED_STRING(128) | 1000 点 + 1000 边 |
| 20 | `fixed_string_capacity` | FIXED_STRING(128)空、1、127、128字节及恰好128字节UTF-8 | FIXED_STRING(128) | 1000 点 + 1000 边 |
| 21 | `all_types_null` | 所有持久化属性同时为数据库NULL，含全部地理形状约束 | 全部18种类型/地理约束 | 1000 点 + 1000 边 |
| 22 | `date_values` | DATE负年、0年、闰日及16位年份边界 | DATE | 1000 点 + 1000 边 |
| 23 | `time_microseconds` | TIME午夜、最后一秒及微秒边界 | TIME | 1000 点 + 1000 边 |
| 24 | `datetime_values` | DATETIME年份边界、闰日与微秒完整保存 | DATETIME | 1000 点 + 1000 边 |
| 25 | `timestamp_values` | TIMESTAMP纪元、2038边界及服务端可写高值 | TIMESTAMP | 1000 点 + 1000 边 |
| 26 | `duration_values` | DURATION月份/秒/微秒、混合符号与边界，不折算月份 | DURATION | 1000 点 + 1000 边 |
| 27 | `duration_microsecond_bounds` | DURATION微秒原始int32上下界、相邻值与跨秒值，不归一化 | DURATION | 1000 点 + 1000 边 |
| 28 | `geography_point` | POINT经纬度极限、负零和高精度；以源原生读取值为比较基准 | GEOGRAPHY(POINT) | 1000 点 + 1000 边 |
| 29 | `geography_line` | LINESTRING多点、日期线和高精度；以源原生读取值为比较基准 | GEOGRAPHY(LINESTRING) | 1000 点 + 1000 边 |
| 30 | `geography_polygon` | POLYGON内环、方向归一化、日期线和高精度；以源原生读取值为比较基准 | GEOGRAPHY(POLYGON) | 1000 点 + 1000 边 |
| 31 | `geography_mixed` | 泛型GEOGRAPHY混存三种形状；以源原生读取值为比较基准 | GEOGRAPHY | 1000 点 + 1000 边 |
| 32 | `zero_properties` | 1000个零属性tag与1000条零属性edge | 无属性 | 1000 点 + 1000 边 |
| 33 | `multiple_tags` | 每个VID同时具有数据tag和零属性extra_tag，保留tag集合 | STRING | 1000 点 + 1000 边，另1000份extra_tag |
| 34 | `edge_rank_bounds` | 边rank可表达上下界、-1、0、1及大整数 | STRING | 1000 点 + 1000 边 |
| 35 | `self_loops` | 1000个顶点各自带一条自环 | STRING | 1000 点 + 1000 边 |
| 36 | `parallel_edges` | 1000条边共用相同端点，用1000个rank区分；其他顶点保持独立存在 | STRING | 1000 点 + 1000 边 |
| 37 | `quoted_schema` | 带空格/中文/关键字的Schema名称和属性名称 | STRING, INT64 | 1000 点 + 1000 边 |
| 38 | `not_null_defaults` | NOT NULL属性省略写入时使用确定性DEFAULT，搬迁后原值和Schema一致 | INT64, STRING, BOOL, DATE | 1000 点 + 1000 边 |
| 39 | `combined_types` | 每条记录同时包含全部18种声明类型/地理约束，交叉组合值 | 全部18种类型/地理约束 | 1000 点 + 1000 边 |
| 40 | `pagination` | 7个分区各自分页、1000点和1000边；runner分别用limit=1,7,127,1000,1001 | STRING | 1000 点 + 1000 边 |

## 负向请求矩阵

每一行也分别在两种 VID 空间执行。先执行独立 `control.ngql` 建立 **1000个合法顶点和1000条合法边**，然后执行 **1000次必须拒绝的请求**。使用 verification、统计和抽样检查拒绝前后对照数据未被污染；拒绝请求不计入正向搬迁样本。

| 场景 ID | 输入问题 | 每种 VID 的请求量 | 对照点/边数量 | 拒绝文件允许新增点/边 |
| --- | --- | --- | --- | --- |
| `reject_vid_domain` | 字符串VID超过256字节或整数VID超出INT64，每次写入均应拒绝 | 1000 | 1000 / 1000 | 0 / 0 |
| `reject_int8_range` | INT8属性128/-129，存储必须拒绝越界值 | 1000 | 1000 / 1000 | 0 / 0 |
| `reject_float_infinity` | FLOAT属性正负Infinity，存储范围检查必须拒绝 | 1000 | 1000 / 1000 | 0 / 0 |
| `reject_rank_min` | 当前服务端rank语法不接受Long.MIN_VALUE，每条请求必须拒绝 | 1000 | 1000 / 1000 | 0 / 0 |
| `reject_geography` | 非法经纬度、非法线/多边形写入NOT NULL地理属性必须拒绝 | 1000 | 1000 / 1000 | 0 / 0 |

## 验证步骤

1. 确认前提和源空间为空，创建空间及 Schema，分别等待元数据传播。
2. 执行预置 nGQL，正向核对无错误和预期写入数量；负向先写control并保存基线，再核对每次非法请求确实拒绝。
3. 停止源数据写入，运行 migration 到全新的目标空间。
4. 从同一迁移清单创建 verification 查询计划，独立 FETCH 源与目标，再离线比较。任何 ERROR、MISSING 或字段差异都不通过。
5. 在源/目标分别执行统计任务并等待完成，比较不同顶点、边及每个 tag/edge 的数量。
6. 分别执行相同的前/中/末 3 个顶点和 3 条边 FETCH，保留源/目标结果作为辅助证据。
7. 分页场景额外分别采用 limit 1、7、127、1000、1001 重复迁移及 verification。
8. 每个执行项独立输出报告，记录实际空间、文件摘要、命令结果、统计、verification结论和抽样证据；所有项完成后汇总。

共 **40 类正向 × 2 VID = 80 次正向验收**，最低 **80000 个顶点 + 80000 条边**；另 **10 组负向、共10000次拒绝请求**，以及其 **10000点 + 10000边合法对照数据**。重试和额外分页轮次不计入上述最低数量。

完整生成方法、metadata接口和数据域限制见 [README-FIXTURES.md](README-FIXTURES.md)。
