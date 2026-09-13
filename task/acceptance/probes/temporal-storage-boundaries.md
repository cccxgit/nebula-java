# TIMESTAMP 与 DURATION 的实际存储边界

本记录依据验收环境中的 NebulaGraph 3.6 服务端源码，核对的是表达式、INSERT、存储写入及读取完整路径。仅有 `YIELD` 能返回某个整数，不能证明该整数可写入 TIMESTAMP 属性。本次核对和补充夹具没有执行数据库写入。

源码根目录：`/home/sch/nebula/nebula_graph-3.6`。以下行号对应本机源码，便于复核。

## TIMESTAMP：0 至 9223372036 秒

`src/codec/RowWriterV2.cpp:617` 的 `write(index, int64_t)` 在属性为 TIMESTAMP 时仍调用 `TimeUtils::toTimestamp(v)`，失败即返回 `OUT_OF_RANGE`，并不因为输入已经是 int64 就直接存储。

```cpp
case PropertyType::TIMESTAMP: {
  auto ret = time::TimeUtils::toTimestamp(v);
  if (!ret.ok()) {
    return WriteResult::OUT_OF_RANGE;
  }
  auto ts = ret.value().getInt();
  memcpy(&buf_[offset], reinterpret_cast<void*>(&ts), sizeof(int64_t));
  break;
}
```

`src/common/time/TimeUtils.cpp:18` 定义 `kMaxTimestamp = std::numeric_limits<int64_t>::max() / 1000000000`，整数除法结果为 **9223372036**。同文件 `toTimestamp` 在第 165 行读取整数原值，第 172 行拒绝 `timestamp < 0 || timestamp > kMaxTimestamp`。`src/common/function/FunctionManager.cpp:1787` 的 `timestamp(...)` 函数也调用同一转换函数。

因此 `timestamp_values` 已覆盖实际可写上下界。直接把 `9223372036854775807` 放入 INSERT 的 TIMESTAMP 属性位置会触发这条存储范围检查；不能将 TIMESTAMP 的容器类型 int64 当作其有效数值范围。

## DURATION：微秒原字段保留完整 int32

服务端不把 DURATION 的 `microseconds` 限制为 `[-999999,999999]`，也不在写入时把跨秒部分搬到 `seconds`。

1. `src/common/function/FunctionManager.cpp:2794`：`duration(map)` 调用 `TimeUtils::durationFromMap`。
2. `src/common/time/TimeUtils.cpp:202`：map 中的 `microseconds` 交给 `Duration::addMicroseconds`。
3. `src/common/datatypes/Duration.h:23,116`：字段类型为 int32，`addMicroseconds(int32_t us)` 只执行 `microseconds += us`，没有除余或跨秒归一化。
4. `src/graph/util/SchemaUtil.cpp:137`：INSERT 对表达式求值后，只拒绝错误 NULL，保留正常 Duration 原字段。`src/graph/validator/MutateValidator.cpp:122,255` 将这些值交给点/边的存储请求。
5. `src/storage/BaseProcessor-inl.h:142`：`encodeRowVal` 通过 `RowWriterV2.setValue` 写入属性；`src/codec/RowWriterV2.cpp:227` 将 Duration 交给 Duration 专用写入函数。
6. `src/codec/RowWriterV2.cpp:788`：秒、微秒、月份分别按 int64、int32、int32 直接 `memcpy` 写入。
7. `src/codec/RowReaderV2.cpp:180`：读取时按相同字段宽度直接还原，仍无归一化。

```cpp
Duration& addMicroseconds(int32_t us) {
  microseconds += us;
  return *this;
}
```

`Duration.h:128` 的 `toString()` 会按秒与微秒组合展示文本，但不会修改上述原始字段。console 中看似已经换算成秒的输出，不能作为原字段被归一化的证据。

因此新增 `duration_microsecond_bounds_s` 和 `duration_microsecond_bounds_i`，每项包含 1,000 个点和 1,000 条边。固定 months=seconds=0，循环覆盖微秒：

```text
-2147483648, -2147483647, -1000000, -999999, -1, 0,
1, 999999, 1000000, 2147483646, 2147483647
```

例如 `duration({months:0,seconds:0,microseconds:2147483647})` 的期望原字段为 `[months=0,seconds=0,microseconds=2147483647]`。源码显示该值可以进入完整持久化路径；新场景的实际 INSERT、scan、搬迁与 FETCH 结果应以正式验收报告为准，本文不预先宣称通过。

扩展未改变原有 88 项的任何 metadata、362 个夹具文件或 88 个 oracle 文件摘要，见 [扩展完整性核对](duration-extension-integrity.json)。运行中的原场景可继续使用相同输入。
