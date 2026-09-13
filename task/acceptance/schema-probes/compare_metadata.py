#!/usr/bin/env python3
"""Read-only comparison of source/target native SchemaMetadataProbe output."""
import argparse
import base64
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path


def tables(report):
    result = {}
    for table in report['tables']:
        key = table['kind'] + '/' + table['name']
        if key in result:
            raise ValueError('Duplicate table metadata: ' + key)
        raw = table['nativeSchemaBase64']
        decoded = base64.b64decode(raw, validate=True)
        if base64.b64encode(decoded).decode('ascii') != raw:
            raise ValueError('Non-canonical native Schema Base64')
        if base64.b64encode(hashlib.sha256(decoded).digest()).decode('ascii') != table['nativeSchemaSha256Base64']:
            raise ValueError('Native Schema digest differs: ' + key)
        result[key] = table
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--target', type=Path, required=True)
    parser.add_argument('--out', type=Path, required=True)
    args = parser.parse_args()
    if args.out.exists():
        parser.error('Output file must not exist')
    result = {'comparedAt': datetime.now(timezone.utc).isoformat(), 'matched': False, 'status': 'ERROR', 'differences': []}
    try:
        source_bytes, target_bytes = args.source.read_bytes(), args.target.read_bytes()
        source, target = json.loads(source_bytes), json.loads(target_bytes)
        result.update({'sourceSpace': source['space'], 'targetSpace': target['space'],
                       'sourceSnapshotSha256': hashlib.sha256(source_bytes).hexdigest(),
                       'targetSnapshotSha256': hashlib.sha256(target_bytes).hexdigest()})
        if source.get('matchedExpected') is not True or target.get('matchedExpected') is not True:
            raise ValueError('Metadata snapshot did not pass its fixture checks')
        if source['caseId'] != target['caseId']:
            raise ValueError('Metadata snapshots refer to different fixture cases')
        left, right = tables(source), tables(target)
        if set(left) != set(right):
            result['differences'].append('Tag/Edge name inventory differs')
        if source['spaceCommentBase64'] != target['spaceCommentBase64']:
            result['differences'].append('Space COMMENT bytes differ')
        compared_defaults = 0
        for key in sorted(set(left) & set(right)):
            if left[key]['nativeSchemaBase64'] != right[key]['nativeSchemaBase64']:
                result['differences'].append(key + ': native Schema bytes differ')
            if left[key]['columns'] != right[key]['columns']:
                result['differences'].append(key + ': default expression/column metadata differs')
            compared_defaults += len(left[key]['columns'])
        result['comparedTables'] = len(set(left) & set(right))
        result['comparedDefaultExpressions'] = compared_defaults
        result['status'] = 'DIFFERENT' if result['differences'] else 'MATCH'
        result['matched'] = not result['differences']
    except Exception as error:
        result['error'] = str(error)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps(result, ensure_ascii=False))
    return 0 if result['matched'] else 1


if __name__ == '__main__':
    raise SystemExit(main())
