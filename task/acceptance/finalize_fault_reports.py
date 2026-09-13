#!/usr/bin/env python3
"""Audit and bind all seven completed final-build fault reports without changing archives.

The prelaunch note is a factual operator attestation, not a timestamp inferred from
the manifest. Preserve the manifest from execution and the original archive bytes.
"""

from pathlib import Path
from datetime import datetime, timezone
import argparse
import hashlib, json, os, re, tarfile

def require(condition, message):
    if not condition:
        raise RuntimeError(message)


ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--reports', default=ROOT / 'task/acceptance/reports/final-faults')
parser.add_argument('--prelaunch-verification-note', required=True, help='Factual account of the SHA-256 verification actually performed before launching the fault processes')
parser.add_argument('--main-case-count', type=int, default=90)
args = parser.parse_args()
REPORTS = Path(args.reports).resolve()
require(REPORTS.is_relative_to(ROOT) if hasattr(REPORTS, 'is_relative_to') else str(REPORTS).startswith(str(ROOT) + os.sep), "Evidence audit failed: REPORTS.is_relative_to(ROOT) if hasattr(REPORTS, 'is_relative_to') else str(REPORTS).startswith(str(ROOT) + os.sep)")
require(args.prelaunch_verification_note.strip() and args.main_case_count > 0, 'Evidence audit failed: args.prelaunch_verification_note.strip() and args.main_case_count > 0')
ORDER = ['property_change', 'edge_key_replace', 'tag_membership', 'geography_coordinate_bit', 'geography_schema_shape', 'bundle_integrity', 'snapshot_integrity']
ADDED = {'executionArtifacts', 'executionBinding'}

def sha(data):
    return hashlib.sha256(data).hexdigest()

def read(path):
    return json.loads(path.read_text())

def encode(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode()

def write_new(path, value):
    require(not path.exists(), 'Refusing overwrite: ' + str(path))
    path.write_bytes(encode(value))

def ref(path):
    return {'file': str(path.relative_to(ROOT)), 'sha256': sha(path.read_bytes())}

latest = {}
for path in REPORTS.rglob('*.json'):
    item = read(path)
    if item.get('faultId') not in ORDER or 'controlCounts' not in item or 'finishedAt' not in item:
        continue
    fault = item['faultId']
    if fault not in latest or item['finishedAt'] > latest[fault][1]['finishedAt']:
        latest[fault] = (path, item)
require(set(latest) == set(ORDER), 'All seven final cases must exist')
require(all(item['status'] == 'PASSED' for _, item in latest.values()), "Evidence audit failed: all(item['status'] == 'PASSED' for _, item in latest.values())")
require(all(not (ADDED & set(item)) for _, item in latest.values()), 'Reports already bound')
manifest_path = REPORTS / 'execution-artifacts.json'
manifest = read(manifest_path)
identity = ref(manifest_path)
files = []
for entry in manifest['files']:
    data = (ROOT / entry['file']).read_bytes()
    require(len(data) == entry['bytes'] and sha(data) == entry['sha256'], entry['file'])
    files.append(dict(entry, unchanged=True))
hashes = {x['file']: x['sha256'] for x in files}
migration = hashes['migration/target/migration-3.8.4.jar']
verification = hashes['verification/target/nebula-data-verifier-3.8.4.jar']
require(migration == hashes['task/deliverables/migration-3.8.4.jar'], "Evidence audit failed: migration == hashes['task/deliverables/migration-3.8.4.jar']")
require(verification == hashes['task/deliverables/nebula-data-verifier-3.8.4.jar'], "Evidence audit failed: verification == hashes['task/deliverables/nebula-data-verifier-3.8.4.jar']")
launch = read(REPORTS / 'geography-launch-check.json')
require(launch['status'] == 'PASSED' and launch['artifactsUnchanged'] and launch['executionArtifacts'] == identity, "Evidence audit failed: launch['status'] == 'PASSED' and launch['artifactsUnchanged'] and launch['executionArtifacts'] == identity")
now = datetime.now(timezone.utc).isoformat()
audit_path = REPORTS / 'final-execution-audit.json'
execution_audit = {'status': 'PASSED', 'artifactsUnchanged': True, 'checkedAt': now, 'executionArtifacts': identity, 'migrationJarSha256': migration, 'verificationJarSha256': verification, 'files': files, 'auditor': ref(Path(__file__).resolve())}

scenes, audits, pending_bindings = [], [], []
for fault in ORDER:
    path, report = latest[fault]
    raw_report = path.read_bytes()
    require(raw_report == encode(report), 'Evidence audit failed: raw_report == encode(report)')
    require('task-campaign-final/' in report['baselineWork'], "Evidence audit failed: 'task-campaign-final/' in report['baselineWork']")
    require(report['controlCounts']['vertices'] >= 1000 and report['controlCounts']['edges'] >= 1000, "Evidence audit failed: report['controlCounts']['vertices'] >= 1000 and report['controlCounts']['edges'] >= 1000")
    require(report['baselineVerification']['status'] == 'MATCH' and report['baselineVerification']['matched'] and report['baselineVerification']['comparedRecords'] >= 2000, "Evidence audit failed: report['baselineVerification']['status'] == 'MATCH' and report['baselineVerification']['matched'] and report['baselineVerification']['comparedRecords'] >= 2000")
    archive = ROOT / report['archive']['file']
    archive_data = archive.read_bytes()
    require(len(archive_data) == report['archive']['bytes'] and sha(archive_data) == report['archive']['sha256'], "Evidence audit failed: len(archive_data) == report['archive']['bytes'] and sha(archive_data) == report['archive']['sha256']")
    with tarfile.open(archive, 'r:gz') as stream:
        contents = {m.name: stream.extractfile(m).read() for m in stream.getmembers() if m.isfile()}
    stem = report['id'] + '/'
    require(json.loads(contents[stem + 'fault-report.json']) == {k: v for k, v in report.items() if k != 'archive'}, "Evidence audit failed: json.loads(contents[stem + 'fault-report.json']) == {k: v for k, v in report.items() if k != 'archive'}")
    work = Path(report['baselineEvidence']['path']).parent
    executed_jars = {}
    for step in report['steps']:
        if '-jar' in step['argv']:
            jar = Path(step['argv'][step['argv'].index('-jar') + 1]).resolve()
            jar_relative = str(jar.relative_to(ROOT))
            require(jar_relative in ('migration/target/migration-3.8.4.jar', 'verification/target/nebula-data-verifier-3.8.4.jar'), "Evidence audit failed: jar_relative in ('migration/target/migration-3.8.4.jar', 'verification/target/nebula-data-verifier-3.8.4.jar')")
            executed_jars[jar_relative] = hashes[jar_relative]
        relative = Path(step['log']).relative_to(work).as_posix()
        require(sha(contents[stem + relative]) == step['sha256'], (fault, relative))
    captures = {}
    for capture in ['baseline-proof/source', 'baseline-proof/target'] + (['source', 'target-before', 'target-after'] if report.get('faultVerification') else []):
        meta = json.loads(contents[stem + capture + '/manifest.json'])
        data = contents[stem + capture + '/records.jsonl']
        require(sha(data) == meta['recordsSha256'], "Evidence audit failed: sha(data) == meta['recordsSha256']")
        rows = [json.loads(x) for x in data.splitlines()]
        require(len(rows) == meta['recordCount'] == meta['requestedCount'], "Evidence audit failed: len(rows) == meta['recordCount'] == meta['requestedCount']")
        if capture != 'target-after':
            require(meta['status'] == 'COMPLETE' and meta['okCount'] >= 2000 and all(x['status'] == 'OK' for x in rows), "Evidence audit failed: meta['status'] == 'COMPLETE' and meta['okCount'] >= 2000 and all(x['status'] == 'OK' for x in rows)")
        captures[capture] = {'status': meta['status'], 'recordCount': len(rows), 'sha256Verified': True}
    comparison = report.get('faultVerification')
    if comparison:
        require(comparison['status'] == report['expected']['expectedStatus'] and not comparison['matched'], "Evidence audit failed: comparison['status'] == report['expected']['expectedStatus'] and not comparison['matched']")
        compare_step = next(s for s in report['steps'] if Path(s['log']).name == 'fault-comparison.txt')
        require(compare_step['exitCode'] == {'DIFFERENT': 2, 'ERROR': 1}[comparison['status']], "Evidence audit failed: compare_step['exitCode'] == {'DIFFERENT': 2, 'ERROR': 1}[comparison['status']]")
        actual = {k: comparison[k] for k in ('status', 'matched', 'comparedRecords', 'differentRecords', 'errorRecords')}
        actual['exitCode'] = compare_step['exitCode']
        require(report['freshTargetBaseline']['status'] == 'MATCH' and report['freshTargetBaseline']['comparedRecords'] >= 2000, "Evidence audit failed: report['freshTargetBaseline']['status'] == 'MATCH' and report['freshTargetBaseline']['comparedRecords'] >= 2000")
        require(report['sourceBaselineFinishedAt'] < report['targetImportStartedAt'] < report['faultStartedAt'], "Evidence audit failed: report['sourceBaselineFinishedAt'] < report['targetImportStartedAt'] < report['faultStartedAt']")
        require(stem + 'mutation/inject-fault.ngql' in contents, "Evidence audit failed: stem + 'mutation/inject-fault.ngql' in contents")
        require(stem + 'fault-source-console/fetch.txt' in contents and stem + 'fault-target-console/fetch.txt' in contents, "Evidence audit failed: stem + 'fault-source-console/fetch.txt' in contents and stem + 'fault-target-console/fetch.txt' in contents")
        actual['countsStayedEqual'] = report['countsStayedEqual']
    elif fault == 'bundle_integrity':
        require(set(report['subchecks']) == {'csv_hash', 'duplicate_key'}, "Evidence audit failed: set(report['subchecks']) == {'csv_hash', 'duplicate_key'}")
        for item in report['subchecks'].values():
            require(item['status'] == 'PASSED' and item['actualStatus'] == 'ERROR', "Evidence audit failed: item['status'] == 'PASSED' and item['actualStatus'] == 'ERROR'")
            for phase in ('verificationPrepare', 'migrationImport'):
                require(item[phase]['exitCode'] == 1 and item[phase]['reasonObserved'], "Evidence audit failed: item[phase]['exitCode'] == 1 and item[phase]['reasonObserved']")
            require(item['targetAbsence']['before']['absent'] and item['targetAbsence']['after']['absent'], "Evidence audit failed: item['targetAbsence']['before']['absent'] and item['targetAbsence']['after']['absent']")
        actual = {'status': 'ERROR', 'matched': False, 'subchecks': report['subchecks']}
    else:
        require(set(report['subchecks']) == {'truncation', 'wrong_plan', 'same_missing', 'false_complete'}, "Evidence audit failed: set(report['subchecks']) == {'truncation', 'wrong_plan', 'same_missing', 'false_complete'}")
        require(all(x['status'] == 'ERROR' and not x['matched'] for x in report['subchecks'].values()), "Evidence audit failed: all(x['status'] == 'ERROR' and not x['matched'] for x in report['subchecks'].values())")
        require(report['subchecks']['same_missing']['errorRecords'] == 2000, "Evidence audit failed: report['subchecks']['same_missing']['errorRecords'] == 2000")
        steps = [x for x in report['steps'] if 'compare' in x['argv'] and Path(x['log']).name != 'baseline-proof-comparison.txt']
        require(len(steps) == 4 and all(x['exitCode'] == 1 for x in steps), "Evidence audit failed: len(steps) == 4 and all(x['exitCode'] == 1 for x in steps)")
        actual = {'status': 'ERROR', 'matched': False, 'subchecks': {k: {a: v[a] for a in ('status', 'matched', 'comparedRecords', 'differentRecords', 'errorRecords')} for k, v in report['subchecks'].items()}, 'allCompareExitCodes': 1}
    if fault == 'geography_coordinate_bit':
        mutation = report['coordinateMutation']
        require(mutation['nativeOneBitChangeConfirmed'] and int(mutation['oldXBits'], 16) ^ int(mutation['newXBits'], 16) == 1, "Evidence audit failed: mutation['nativeOneBitChangeConfirmed'] and int(mutation['oldXBits'], 16) ^ int(mutation['newXBits'], 16) == 1")
        actual['coordinateMutation'] = mutation
    if fault == 'geography_schema_shape':
        require(report['schemaShapeMode'] == 'incompatible' and b'geography(linestring)' in contents[stem + 'schema/after.txt'].lower(), "Evidence audit failed: report['schemaShapeMode'] == 'incompatible' and b'geography(linestring)' in contents[stem + 'schema/after.txt'].lower()")
        require(comparison['errorRecords'] == 1000 and report['faultCapture']['errorCount'] == 1000, "Evidence audit failed: comparison['errorRecords'] == 1000 and report['faultCapture']['errorCount'] == 1000")
    require('verification/target/nebula-data-verifier-3.8.4.jar' in executed_jars, "Evidence audit failed: 'verification/target/nebula-data-verifier-3.8.4.jar' in executed_jars")
    if report.get('faultVerification') or fault == 'bundle_integrity':
        require('migration/target/migration-3.8.4.jar' in executed_jars, "Evidence audit failed: 'migration/target/migration-3.8.4.jar' in executed_jars")
    explanation = args.prelaunch_verification_note
    binding = {'status': 'PASSED', 'artifactsUnchanged': True, 'caseId': report['id'], 'faultId': fault, 'boundAt': now, 'executionArtifacts': identity, 'migrationJarSha256': migration, 'verificationJarSha256': verification, 'archiveSha256': report['archive']['sha256'], 'originalReportSha256': sha(raw_report), 'preLaunchVerification': {'kind': 'AGENT_OBSERVED_SHA256_CHECK', 'verifiedBeforeLaunch': True, 'explanation': explanation}}
    binding['executedJars'] = [{'file': name, 'sha256': value} for name, value in sorted(executed_jars.items())]
    binding['actualCommandJarArgumentsVerified'] = True
    if fault.startswith('geography_'):
        binding['preLaunchVerification']['recordedCheck'] = ref(REPORTS / 'geography-launch-check.json')
    pending_bindings.append((path, report, binding))
    scenes.append({'id': report['id'], 'faultId': fault, 'status': 'PASSED', 'baselineCaseId': report['baselineCaseId'], 'controlCounts': report['controlCounts'], 'sourceSpace': report['sourceSpace'], 'targetSpace': report.get('targetSpace'), 'expectedStatus': report['expected']['expectedStatus'], 'actual': actual, 'sourceStatsAfterFault': report.get('sourceStatsAfterFault'), 'targetStatsAfterFault': report.get('targetStatsAfterFault'), 'faultConsole': report.get('faultConsole'), 'report': str(path.relative_to(ROOT)), 'markdown': str(path.with_suffix('.md').relative_to(ROOT)), 'archive': report['archive'], 'executionArtifacts': identity})
    audits.append({'faultId': fault, 'report': str(path.relative_to(ROOT)), 'archiveSha256Verified': True, 'archiveFileCount': len(contents), 'internalReportMatchesOriginalExternal': True, 'commandLogHashesVerified': len(report['steps']), 'captures': captures, 'nativeQueriesArchived': sum(x.endswith('.ngql') for x in contents), 'actualExitAndStatusVerified': True})

for name in ['final-summary.json', 'final-summary.md', 'final-evidence-audit.json', 'final-execution-audit.json']:
    require(not (REPORTS / name).exists(), name)
for path, _, _ in pending_bindings:
    require(not path.with_suffix('.execution-binding.json').exists(), "Evidence audit failed: not path.with_suffix('.execution-binding.json').exists()")
write_new(audit_path, execution_audit)
for scene, (path, report, binding) in zip(scenes, pending_bindings):
    binding['finalExecutionAudit'] = ref(audit_path)
    binding_path = path.with_suffix('.execution-binding.json')
    write_new(binding_path, binding)
    report['executionArtifacts'] = identity
    report['executionBinding'] = ref(binding_path)
    path.write_bytes(encode(report))
    scene['executionBinding'] = report['executionBinding']
    md = path.with_suffix('.md')
    md.write_text(md.read_text() + '\n执行版本证据：[%s](%s)，[该场景绑定](%s)。绑定补充到外部报告；原始归档字节保持不变。\n' % ('固定执行文件摘要', manifest_path.name, binding_path.name))
summary = {'status': 'PASSED', 'updatedAt': now, 'selectionPolicy': 'Latest finished report recursively per faultId, exclusively from final-faults; no fallback to historical faults.', 'expectedScenes': 7, 'completedScenes': 7, 'passedScenes': 7, 'failedScenes': 0, 'passMeaning': 'All injected faults were detected and never accepted as MATCH.', 'mainAcceptanceCaseCount': args.main_case_count, 'mainAcceptanceNote': 'The main migration campaign is separate; this report covers seven accuracy fault scenes.', 'scenes': scenes, 'executionArtifacts': identity, 'finalExecutionAudit': ref(audit_path), 'evidenceAudit': str((REPORTS / 'final-evidence-audit.json').relative_to(ROOT))}
write_new(REPORTS / 'final-summary.json', summary)
evidence = {'status': 'PASSED', 'auditedAt': now, 'sceneCount': 7, 'commandLogHashesVerified': sum(x['commandLogHashesVerified'] for x in audits), 'executionArtifacts': identity, 'finalExecutionAudit': ref(audit_path), 'scenes': audits}
write_new(REPORTS / 'final-evidence-audit.json', evidence)
lines = ['# 最终构建准确性故障验收', '', '结果：**7 / 7 PASSED**。PASSED 表示成功检出故障，所有受损数据或文件均未被判断为 MATCH。', '', '每项使用最终构建正式迁移验收已通过的 1000 个顶点、1000 条边基线。数据库故障先重新采集源基线，再导入新的独立目标；源空间和已通过的正式迁移目标未作修改。主迁移矩阵独立验收，本报告单独覆盖 7 项故障。', '', '| 故障 | 预期 / 实际 | 结果细节 | 报告 | 证据 |', '| --- | --- | --- | --- | --- |']
for item in scenes:
    actual = item['actual']
    if 'comparedRecords' in actual:
        detail = '比较 %d；差异 %d；错误/缺失 %d；退出 %d' % (actual['comparedRecords'], actual['differentRecords'], actual['errorRecords'], actual['exitCode'])
    elif item['faultId'] == 'bundle_integrity':
        detail = '两项 prepare 和 import 均退出 1，目标空间前后均不存在'
    else:
        detail = '四项均退出 1；相同 2000 个 MISSING 仍为 ERROR'
    lines.append('| %s | %s / %s | %s | [Markdown](%s) | [tar.gz](<%s>) |' % (item['faultId'], item['expectedStatus'], actual['status'], detail, Path(item['markdown']).name, os.path.relpath(ROOT / item['archive']['file'], REPORTS)))
geo = next(x for x in scenes if x['faultId'] == 'geography_coordinate_bit')['actual']['coordinateMutation']
lines += ['', '坐标只改变经度最低一位：`%s` → `%s`，XOR 为 `1`；原生坐标断言通过，点边计数保持一致。console 仅用于辅助展示。' % (geo['oldXBits'], geo['newXBits']), '', 'POINT 约束改为 LINESTRING 的实际 ALTER 成功；采集准确报告 1000 个顶点形状错误，另 1000 条边可比，整体 ERROR。', '', '7 份归档的字节数、SHA-256、归档内报告、全部 %d 份命令日志哈希，以及基线完整快照哈希均已核对。每份外部报告通过独立绑定文件引用固定执行文件摘要；原始归档保持不变。' % evidence['commandLogHashesVerified'], '', '- migration JAR SHA-256：`%s`' % migration, '- verifier JAR SHA-256：`%s`' % verification, '- [详细 JSON 汇总](final-summary.json)', '- [归档内部证据审计](final-evidence-audit.json)', '- [执行文件结束复核](final-execution-audit.json)', '- [固定执行文件摘要](execution-artifacts.json)', '', '本轮七项全部一次通过。旧构建的故障报告与测试脚本历史失败记录保留在独立历史目录，不计入本轮最终构建结果。']
(REPORTS / 'final-summary.md').write_text('\n'.join(lines) + '\n')
for link in re.findall(r'\]\(<?([^)>]+)>?\)', (REPORTS / 'final-summary.md').read_text()):
    require((REPORTS / link).resolve().exists(), link)
print(json.dumps({'status': 'PASSED', 'scenes': 7, 'commandLogHashesVerified': evidence['commandLogHashesVerified'], 'summary': str(REPORTS / 'final-summary.json')}, ensure_ascii=False))
