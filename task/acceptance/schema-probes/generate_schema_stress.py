#!/usr/bin/env python3
"""Generate isolated Schema/default-bytecode stress fixtures; no database operations."""
import base64
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def literal(value):
    if isinstance(value, str):
        value = value.encode('utf-8')
    return '"' + ''.join('\\%03o' % item for item in value) + '"'


ALL_BYTES = bytes(range(256))
SPECIAL = "默认,'\"\\\r\n\t\x00😀e\u0301".encode('utf-8') + b'\xff\xfe'
COLUMNS = [
    ('b', 'BOOL', 'false'), ('i8', 'INT8', '-128'), ('i16', 'INT16', '-32768'),
    ('i32', 'INT32', '-2147483648'), ('i64', 'INT64', 'toInteger("-9223372036854775808")'),
    ('f32', 'FLOAT', 'toFloat("0.1")'), ('f32_zero', 'FLOAT', 'toFloat("-0.0")'),
    ('f32_nan', 'FLOAT', 'toFloat("NaN")'),
    ('f64', 'DOUBLE', 'toFloat("1.7976931348623157e308")'),
    ('f64_zero', 'DOUBLE', 'toFloat("-0.0")'), ('f64_nan', 'DOUBLE', 'toFloat("NaN")'),
    ('f64_inf', 'DOUBLE', 'toFloat("Infinity")'),
    ('s_all_bytes', 'STRING', literal(ALL_BYTES)),
    ('s_invalid_utf8', 'STRING', literal(b'a\xff\xfe\x00b')),
    ('s_special', 'STRING', literal(SPECIAL)), ('s_empty', 'STRING', '""'),
    ('s_null', 'STRING', 'NULL'), ('fs', 'FIXED_STRING(128)', literal("定长,'\"\\\r\n😀")),
    ('d', 'DATE', 'date("2024-02-29")'),
    ('t', 'TIME', 'time({hour:23,minute:59,second:59,millisecond:999,microsecond:999})'),
    ('dt', 'DATETIME', 'datetime({year:2024,month:2,day:29,hour:23,minute:59,second:59,millisecond:123,microsecond:456})'),
    ('ts', 'TIMESTAMP', '9223372036'),
    ('du', 'DURATION', 'duration({months:-2147483648,seconds:0,microseconds:-2147483648})'),
    ('gp', 'GEOGRAPHY(POINT)', 'ST_GeogFromText("POINT(-0 -0)")'),
    ('gl', 'GEOGRAPHY(LINESTRING)', 'ST_GeogFromText("LINESTRING(179 0,-179 0)")'),
    ('ga', 'GEOGRAPHY(POLYGON)', 'ST_GeogFromText("POLYGON((0 0,10 0,10 10,0 10,0 0),(2 2,2 4,4 4,4 2,2 2))")'),
    ('g', 'GEOGRAPHY', 'ST_GeogFromText("LINESTRING(0 0,1 1)")'),
    ('dynamic_ts', 'TIMESTAMP', 'timestamp()'), ('dynamic_rand', 'INT64', 'rand64()'),
    ('dynamic_text', 'STRING', 'tostring(rand64())'),
]


def columns():
    result = []
    for index, (name, datatype, expression) in enumerate(COLUMNS):
        comment = ALL_BYTES if index == 0 else (SPECIAL if index % 3 == 0 else b'')
        item = {'name': name, 'type': datatype, 'expression': expression, 'nullable': name == 's_null',
                'dynamic': name.startswith('dynamic_'),
                'commentBase64': None if index % 3 == 1 else base64.b64encode(comment).decode()}
        if name in ('s_all_bytes', 's_invalid_utf8', 's_special', 's_empty'):
            raw = {'s_all_bytes': ALL_BYTES, 's_invalid_utf8': b'a\xff\xfe\x00b',
                   's_special': SPECIAL, 's_empty': b''}[name]
            item['expectedStringBase64'] = base64.b64encode(raw).decode()
        result.append(item)
    return result


def schema(table_kind, name):
    defs = []
    for item in columns():
        statement = '`%s` %s %s DEFAULT %s' % (item['name'], item['type'],
                    'NULL' if item['nullable'] else 'NOT NULL', item['expression'])
        if item['commentBase64'] is not None:
            statement += ' COMMENT ' + literal(base64.b64decode(item['commentBase64']))
        defs.append(statement)
    return 'CREATE %s `%s`(%s) comment = %s;' % (table_kind, name, ','.join(defs), literal(ALL_BYTES))


def write(path, lines):
    path.parent.mkdir(parents=True, exist_ok=True)
    data = ('\n'.join(lines) + '\n').encode('utf-8')
    path.write_bytes(data)
    return {'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}


def main():
    cases = []
    for suffix, vid_type in [('s', 'FIXED_STRING(256)'), ('i', 'INT64')]:
        case_id = 'schema_defaults_' + suffix
        directory = Path('ngql') / case_id
        source = 'qa_schema_260914_' + suffix + '_source'
        vids = [literal('schema_%04d' % i) if suffix == 's' else str(i + 100) for i in range(1000)]
        files = {}
        file_lines = {'space.ngql': ['CREATE SPACE `%s`(partition_num=7,replica_factor=1,vid_type=%s) comment = %s;' % (source, vid_type, literal(SPECIAL))],
                      'schema.ngql': ['USE `%s`;' % source, schema('TAG', 'defaults_tag'), schema('EDGE', 'defaults_edge')]}
        data = ['USE `%s`;' % source]
        for i in range(1000):
            data += ['INSERT VERTEX `defaults_tag`() VALUES %s:();' % vids[i],
                     'INSERT EDGE `defaults_edge`() VALUES %s->%s@%d:();' % (vids[i], vids[(i + 1) % 1000], i - 500)]
        file_lines['data.ngql'] = data
        samples = []
        for i in (0, 500, 999):
            samples += ['FETCH PROP ON * %s YIELD vertex AS v;' % vids[i],
                        'FETCH PROP ON `defaults_edge` %s->%s@%d YIELD edge AS e;' % (vids[i], vids[(i + 1) % 1000], i - 500)]
        file_lines['sample.ngql'] = ['USE `%s`;' % source] + samples
        for name, lines in file_lines.items():
            relative = str(directory / name)
            files[relative] = write(ROOT / relative, lines)
        cases.append({'id': case_id, 'scenarioId': 'schema_defaults', 'sourceSpace': source,
                      'targetSpace': 'qa_schema_260914_' + suffix + '_target', 'vidType': vid_type,
                      'description': '全类型原生DEFAULT、二进制COMMENT、动态函数表达式原字节保留',
                      'rows': 1000, 'negative': False, 'files': files,
                      'spaceFile': str(directory / 'space.ngql'), 'schemaFile': str(directory / 'schema.ngql'),
                      'dataFile': str(directory / 'data.ngql'), 'sampleFile': str(directory / 'sample.ngql'),
                      'sampleQueries': samples, 'columns': columns(),
                      'expected': {'vertices': 1000, 'edges': 1000, 'tags': {'defaults_tag': 1000}, 'edgeTypes': {'defaults_edge': 1000}},
                      'spaceCommentBase64': base64.b64encode(SPECIAL).decode(),
                      'tableCommentBase64': base64.b64encode(ALL_BYTES).decode()})
    (ROOT / 'scenarios.json').write_text(json.dumps({'formatVersion': 1, 'supplementary': True,
        'description': 'Supplementary schema stress; independent of the frozen primary 90-case matrix.',
        'cases': cases}, ensure_ascii=False, indent=2) + '\n')
    # Isolated quick DDL/INSERT probe; never shares any primary or final stress source space.
    probe = ROOT / 'ddl-probe'
    write(probe / 'space.ngql', ['CREATE SPACE `qa_schema_260914_ddl_probe`(partition_num=1,replica_factor=1,vid_type=INT64) comment = %s;' % literal(SPECIAL)])
    write(probe / 'schema.ngql', ['USE `qa_schema_260914_ddl_probe`;', schema('TAG', 'defaults_tag'), schema('EDGE', 'defaults_edge')])
    write(probe / 'data.ngql', ['USE `qa_schema_260914_ddl_probe`;', 'INSERT VERTEX `defaults_tag`() VALUES 1:(),2:();',
                               'INSERT EDGE `defaults_edge`() VALUES 1->2@0:();',
                               'FETCH PROP ON * 1 YIELD vertex AS v;', 'FETCH PROP ON `defaults_edge` 1->2@0 YIELD edge AS e;'])
    print(json.dumps({'caseIds': [item['id'] for item in cases], 'columnsPerTable': len(COLUMNS)}))


if __name__ == '__main__':
    main()
