#!/usr/bin/env python3
"""Audit actual acceptance evidence and render the per-case Chinese reports."""
import argparse
import hashlib
import json
import tarfile
from pathlib import Path
from datetime import datetime, timezone

ROOT = Path(__file__).resolve().parents[2]


def sha256(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(chunk)
    return result.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reports', type=Path, default=ROOT / 'task/acceptance/reports')
    parser.add_argument('--fixtures', type=Path, default=ROOT / 'task/acceptance/ngql/scenarios.json')
    args = parser.parse_args()
    metadata = json.loads(args.fixtures.read_text())
    expected = {case['id']: case for case in metadata['cases']}
    candidates = {}
    for path in args.reports.rglob('*.json'):
        report = json.loads(path.read_text())
        if report.get('id') not in expected:
            continue
        case_id = report['id']
        previous = candidates.get(case_id)
        if previous is None or report['finishedAt'] > previous[1]['finishedAt']:
            candidates[case_id] = (path, report)
    lines = ['# 真实集群场景验收汇总', '',
             '由 `summarize_campaign.py` 从实际 JSON 报告生成；未执行或缺少证据的场景不得标为通过。', '',
             '| 场景 | VID | 结果 | 点 / 边 | 独立 FETCH | 数量与抽样 | 详细报告 |',
             '|---|---|---|---|---|---|---|']
    failures, totals, reports = [], {'vertices': 0, 'edges': 0, 'rejectedRequests': 0}, []
    for case_id, case in expected.items():
        if case_id not in candidates or candidates[case_id][1]['status'] != 'PASSED':
            failures.append(case_id + ': 缺少 PASSED 报告')
            lines.append('| %s | %s | 未完成 | — | — | — | — |' % (case_id, case['vidType']))
            continue
        path, report = candidates[case_id]
        checks = []
        checks.append(report['sourceBaselineFinishedAt'] <= report['targetImportStartedAt'])
        checks.append(report['verification']['matched'])
        verification = report['verification']
        checks.append(verification['status'] == 'MATCH')
        checks.append(verification['comparedRecords'] == case['expected']['vertices'] + case['expected']['edges'])
        checks.append(all(verification[key] == 0 for key in ('differentRecords', 'errorRecords', 'differenceFields')))
        migrated = report['migrationVerification']
        checks.append(migrated['matched'] and migrated['tagRows'] == sum(case['expected']['tags'].values())
                      and migrated['edgeRows'] == case['expected']['edges'])
        checks.append(report['statsMatched'] and report['consoleSamplesMatched'])
        checks.append(report['expected'] == case['expected'])
        checks.append(report['fixtureFileHashes'] == case['files'])
        oracle = report['sourceFixtureOracle']
        checks.append(oracle['passed'] and oracle['status'] == 'PASSED' and oracle['schemaMatched'])
        checks.append(oracle['validatedVertices'] == case['expected']['vertices'])
        checks.append(oracle['validatedEdges'] == case['expected']['edges'])
        checks.append(oracle['fixtureFileHashes'] == case['files'])
        oracle_path = ROOT / ('task/acceptance/oracles/' + case_id + '.json')
        checks.append(sha256(oracle_path) == oracle['oracleSha256'])
        for side in ('sourceCapture', 'targetCapture'):
            checks.append(report[side]['status'] == 'COMPLETE')
            checks.append(report[side]['missingCount'] == 0 and report[side]['errorCount'] == 0)
        archive = report['archive']
        archive_path = ROOT / archive['file']
        checks.append(archive_path.is_file() and sha256(archive_path) == archive['sha256'])
        with tarfile.open(archive_path, 'r:gz') as bundle:
            embedded = json.load(bundle.extractfile(case_id + '/case-report.json'))
            checks.append(embedded == {key: value for key, value in report.items() if key != 'archive'})
            raw_oracle = bundle.extractfile(case_id + '/source-fixture-oracle.json').read()
            checks.append(hashlib.sha256(raw_oracle).hexdigest() == oracle['reportSha256'])
            embedded_oracle = json.loads(raw_oracle)
            checks.append({key: value for key, value in embedded_oracle.items() if key != 'records'}
                          == {key: value for key, value in oracle.items() if key != 'reportSha256'})
        if case['negative']:
            rejection = report['rejectedInputValidation']
            checks.append(rejection['rejectedRequests'] == case['expectedRejects'])
            checks.append(rejection['controlContentUnchanged'] and rejection['controlStatsUnchanged'])
            totals['rejectedRequests'] += rejection['rejectedRequests']
        if case['scenarioId'] == 'pagination':
            checks.append([item['limit'] for item in report['pagination']] == case['scanLimits'])
            for item in report['pagination']:
                result = item['report']
                checks.append(result['matched'] and result['sourceRechecked']
                              and result['tagRows'] == sum(case['expected']['tags'].values())
                              and result['edgeRows'] == case['expected']['edges'])
        if not all(checks):
            failures.append(case_id + ': 证据审计失败')
        totals['vertices'] += case['expected']['vertices']
        totals['edges'] += case['expected']['edges']
        detail = path.with_suffix('.md')
        relative_json = path.name
        archive_link = Path(__import__('os').path.relpath(archive_path, detail.parent)).as_posix()
        text = ['# ' + case_id, '', case['description'], '',
                '| 项目 | 实际结果 |', '|---|---|',
                '| 验收状态 | PASSED；证据审计 %s |' % ('通过' if all(checks) else '失败'),
                '| VID 类型 | `%s` |' % case['vidType'],
                '| 源空间 | `%s` |' % report['sourceSpace'],
                '| 目标空间 | `%s` |' % report['targetSpace'],
                '| 预期点 / 边 | %s / %s |' % (case['expected']['vertices'], case['expected']['edges']),
                '| 源预制值核对 | %s 条完整记录的原生属性、完整键及 Schema 符合预制表达式 |' % oracle['validatedRows'],
                '| 源 FETCH 基准完成 | %s |' % report['sourceBaselineFinishedAt'],
                '| 目标导入开始 | %s |' % report['targetImportStartedAt'],
                '| 独立 verification | MATCH，双方 COMPLETE，无 ERROR / MISSING |',
                '| SHOW STATS | 两端新统计均符合夹具预期 |',
                '| console FETCH | %s 条固定查询的结果表一致 |' % report['sourceConsole']['queries'],
                '| scan limit | %s |' % report['scanLimit'],
                '| 完成时间 | %s |' % report['finishedAt'], '',
                '代表值分配（每组场景合计至少 1,000 条，每种值的次数如下）：', '',
                '```json', json.dumps(report['variantCounts'], ensure_ascii=False, indent=2), '```', '']
        if case['negative']:
            text += ['另外执行 %s 次非法输入，均被拒绝；有效对照内容和数量均未改变。' % case['expectedRejects'], '']
        if case['scenarioId'] == 'pagination':
            text += ['分页复扫通过的 limit：`%s`。' % ', '.join(map(str, case['scanLimits'])), '']
        text += ['完整步骤、错误码、统计明细、快照清单和 JAR 摘要见 [%s](%s)。' % (relative_json, relative_json), '',
                 '全部原始 CSV、计划、快照、查询及日志见 [证据归档](%s)。' % archive_link, '',
                 '归档 SHA-256：`%s`。' % archive['sha256'], '']
        if not all(checks):
            text = ['# ' + case_id, '', '**证据审计失败，尚不能确认该场景通过。**', '',
                    '请检查原始报告 [%s](%s) 与对应归档，不使用未闭合的通过结论。' % (relative_json, relative_json), '']
        detail.write_text('\n'.join(text))
        rel = detail.relative_to(args.reports).as_posix()
        lines.append('| %s | %s | %s | 1000 / 1000 | %s | %s | [详情](%s) |'
                     % (case_id, case['vidType'], '通过' if all(checks) else '证据异常',
                        'MATCH' if all(checks) else '未确认', '均通过' if all(checks) else '未确认', rel))
        reports.append({'id': case_id, 'report': path.relative_to(ROOT).as_posix(),
                        'auditPassed': all(checks), 'archive': archive})
    summary = {'generatedAt': datetime.now(timezone.utc).isoformat(), 'expectedExecutions': len(expected),
               'completedExecutions': len(reports), 'totals': totals,
               'status': 'PASSED' if not failures else 'INCOMPLETE', 'failures': failures, 'cases': reports}
    args.reports.mkdir(parents=True, exist_ok=True)
    (args.reports / 'campaign-summary.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n')
    (args.reports / 'README.md').write_text('\n'.join(lines) + '\n')
    print(json.dumps({key: value for key, value in summary.items() if key != 'cases'}, ensure_ascii=False, indent=2))
    return 0 if not failures else 1


if __name__ == '__main__':
    raise SystemExit(main())
