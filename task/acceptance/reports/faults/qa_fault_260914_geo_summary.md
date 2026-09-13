# 准确性故障验收汇总

状态：**PASSED**。完成2/2场景，通过2，失败0，待执行0。

每个场景使用至少1000点和1000边的已通过基线。PASSED表示检出预期故障，不能解读为受损数据一致。

| 场景 | 结果 | 基线点/边 | 期望verification | 报告 | 证据 |
| --- | --- | --- | --- | --- | --- |
| geography_coordinate_bit | PASSED | 1000 / 1000 | DIFFERENT | [geography_coordinate_bit](qa_fault_260914_geo_geography_coordinate_bit.md) | [tar.gz](<../../artifacts/faults/qa_fault_260914_geo_geography_coordinate_bit.tar.gz>) |
| geography_schema_shape | PASSED | 1000 / 1000 | ERROR | [geography_schema_shape](qa_fault_260914_geo_geography_schema_shape.md) | [tar.gz](<../../artifacts/faults/qa_fault_260914_geo_geography_schema_shape.tar.gz>) |
