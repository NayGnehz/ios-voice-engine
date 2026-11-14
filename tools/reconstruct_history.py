"""Reconstruct version history from retained project sources, without rewriting releases.

Uses Git plumbing to create a new history branch; never checks out old code,
executes historical builders, includes APK binaries, or backdates commits.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


def sha(data):
    return hashlib.sha256(data).hexdigest()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('repo', 'research', 'apks', 'git', 'aapt2', 'output'):
        p.add_argument('--' + name, required=True, type=Path)
    p.add_argument('--published-ref', default='eae11d620b9fa770c2b0842750eafb818c7ebce3')
    p.add_argument('--date', default='2026-11-14', help='Disclosure date, not a Git author-date override')
    a = p.parse_args()
    env = os.environ.copy()
    env.update(GIT_AUTHOR_NAME='NayGnehz', GIT_AUTHOR_EMAIL='',
               GIT_COMMITTER_NAME='NayGnehz', GIT_COMMITTER_EMAIL='')
    # The commit timestamp is the real execution time, not a historical guess.
    for key in ('GIT_AUTHOR_DATE', 'GIT_COMMITTER_DATE'):
        env.pop(key, None)

    def git(*args, data=None):
        return subprocess.run([str(a.git), '-C', str(a.repo), *args], input=data,
                              stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              check=True, env=env).stdout

    def json_bytes(value):
        return (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8')

    def tree(files):
        nodes = {}
        for path, data in sorted(files.items()):
            node = nodes
            parts = path.split('/')
            for part in parts[:-1]:
                node = node.setdefault(part, {})
            node[parts[-1]] = git('hash-object', '-w', '--stdin', data=data).decode().strip()
        def build(node):
            lines = []
            for name, value in sorted(node.items()):
                kind, mode, oid = ('tree', '040000', build(value)) if isinstance(value, dict) else ('blob', '100644', value)
                lines.append(f'{mode} {kind} {oid}\t{name}\0'.encode())
            return git('mktree', '-z', data=b''.join(lines)).decode().strip()
        return build(nodes)

    def commit(files, parent, message):
        return git('commit-tree', tree(files), '-p', parent, '-m', message).decode().strip()

    def snapshot(ref):
        result = {}
        for entry in git('ls-tree', '-r', '-z', ref).split(b'\0'):
            if not entry:
                continue
            fields, path = entry.split(b'\t', 1)
            result[path.decode()] = git('cat-file', 'blob', fields.split()[2].decode())
        return result

    published = snapshot(a.published_ref)
    files = {n: published[n] for n in ('.gitattributes', '.gitignore', 'LICENSE', 'THIRD_PARTY.md')}
    records, inventory = [], {}
    parent = git('rev-list', '--max-parents=0', a.published_ref).decode().strip()

    def add_file(src, dest, transform=None):
        original = src.read_bytes()
        data = transform(original) if transform else original
        changes = []
        if transform:
            changes.append('documented reverse reconstruction from later retained source')
        if src.suffix == '.ps1' and b'pass:android' in data:
            data = data.replace(b'pass:android', b'env:VOICE_STORE_PASSWORD')
            changes.append('historical signing password literal replaced by environment-variable reference')
        files[dest] = data
        inventory[dest] = {'source': src.relative_to(a.research).as_posix(),
                           'source_sha256': sha(original), 'stored_sha256': sha(data),
                           'transformations': changes}

    def research_files(names):
        for name in names:
            add_file(a.research / name, 'research/' + name)

    def project_sources(folder, omit=(), transforms=None):
        transforms = transforms or {}
        # Deliberately excludes compiled output, real logs, evidence telemetry and inputs.
        selected = []
        for area in ('src', 'test', 'production', 'diagnostic'):
            base = a.research / folder / area
            if not base.exists():
                continue
            for src in base.rglob('*'):
                rel = src.relative_to(a.research / folder).as_posix()
                if src.is_file() and src.suffix in ('.java', '.xml') and not any(x in src.parts for x in ('classes', 'dex')):
                    selected.append((src, rel))
        for name in ('build.ps1', 'package_apks.py', 'patch_marks.py', 'test_marks_patch.py'):
            src = a.research / folder / name
            if src.exists():
                selected.append((src, name))
        for src, rel in sorted(selected):
            if rel not in omit:
                add_file(src, 'research/' + folder + '/' + rel, transforms.get(rel))

    def apk(path):
        data = path.read_bytes()
        result = subprocess.run([str(a.aapt2), 'dump', 'badging', str(path)], check=True,
                                capture_output=True, encoding='utf-8').stdout
        line = next(x for x in result.splitlines() if x.startswith('package:'))
        fields = dict(re.findall(r"(name|versionCode|versionName)='([^']*)'", line))
        return {'filename': path.name, 'bytes': len(data), 'sha256': sha(data), **fields}

    def stage(number, slug, title, status, explanation, artifacts):
        nonlocal parent
        record = {'sequence': number, 'id': slug, 'title': title, 'status': status,
                  'reconstructed_on': a.date, 'note': explanation, 'artifacts': [apk(p) for p in artifacts],
                  'source_inventory': dict(sorted(inventory.items()))}
        files['RECONSTRUCTION.json'] = json_bytes(record)
        if number < 9:
            files['README.md'] = (f'# iOS 声音引擎 · 历史重建 {number:02d}\n\n{title}\n\n'
                f'本提交于 {a.date} 事后重建，提交时间为实际重建时间，不是原开发时间。\n\n'
                f'材料状态：`{status}`。{explanation}\n\n'
                '保留的源码/脚本位于 `research/`；APK 仅记录身份与校验和，不上传第三方二进制、音色数据、私钥或私人日志。'
                '历史脚本沿用当时的目录和工具假设，仅供追溯，未在本轮重新构建或验证旧 APK。'
                '查阅 `RECONSTRUCTION.json` 了解材料清单、变换及实际留存 APK 版本。\n\n'
                '社区基础：https://bbs.tatans.cn/topic/25344\n'
                'ARM64 运行库发布来源：https://github.com/poretsky/poretsky.github.io\n').encode()
        parent = commit(files, parent, f'Reconstruct {number:02d}: {title} [retrospective]')
        record['commit'] = parent
        record['tag'] = 'reconstructed/' + f'{number:02d}-' + slug
        records.append(record)

    stage(0, 'community-baseline', '社区原包基线记录', 'artifact-metadata-only',
          '原社区封装没有可验证的开放源码；这里只记录实际输入 APK 身份，不伪造原项目源码。',
          [a.research.parent / '苹果中文语音库_clone.apk'])
    research_files(['build_trial.py'])
    stage(1, 'arm64-trial', 'ARM64 运行库替换试验', 'retained-source',
          '保留的试验构建脚本；这一阶段尚无后续重复 close、定点模块和就绪修复。',
          [a.research / 'tingting-arm64-trial.apk'])
    research_files(['build_direct_voice.py', 'patch_fd_close.py', 'build_fix1.ps1', 'verify_fix1.py'])
    for name in ('DiagnosticActivity.java', 'ExitTrace.java', 'AndroidManifest.xml', 'package_probe.py'):
        add_file(a.research / 'diagnostic' / name, 'research/diagnostic/' + name)
    for src in (a.research / 'diagnostic/res').rglob('*.xml'):
        add_file(src, 'research/' + src.relative_to(a.research).as_posix())
    stage(2, 'fd-close-fix1', '单音色无图标与重复 close 修复', 'retained-source',
          '现存修复一脚本将单音色、无图标和重复关闭修复组合在一起；更早未单独留存的代码不拆成猜测快照。签名密码参数已脱敏。',
          [a.research / 'ios-tingting-arm64-fix1.apk', a.research / 'tingting-diagnostic-fix1.apk'])
    research_files(['build_fix2.py', 'build_fix2.ps1', 'elf_view.py', 'verify_fix2.py'])
    for src in (a.research / 'diagnostic-fix2').rglob('*'):
        if src.is_file() and src.suffix in ('.java', '.xml', '.py') and not any(n in src.parts for n in ('classes', 'dex', '__pycache__')):
            add_file(src, 'research/' + src.relative_to(a.research).as_posix())
    stage(3, 'bet3-fix2', '修复二：匹配定点 BET3 模块', 'retained-source',
          '修正音色头中的 select/synth 组件声明，语音模型本身不变。未重新执行旧构建。',
          [a.research / 'ios-tingting-arm64-fix2.apk', a.research / 'tingting-diagnostic-fix2.apk'])
    research_files(['package_compressed.py'])
    stage(4, 'compressed', '压缩安装包且保留资源 FD 访问', 'retained-source',
          '原生库 ZIP 压缩；XMF 保持可直接读取的存储方式。版本号与修复二相同，以序号区分。',
          [a.apks / 'iOS-婷婷_64位.apk'])
    stage(5, 'ready-initial', '启动就绪修复 1.1 / 诊断 1.3', 'artifact-metadata-only',
          'APK 留存，但这一轮的完整 Java 源码已被下一版覆盖；此提交只记录版本和校验和，不拿后来的代码冒充原件。',
          [a.apks / 'iOS-婷婷_启动修复版.apk', a.apks / '婷婷诊断_1.3.apk'])
    project_sources('readiness')
    add_file(a.research / 'readiness/verify.py', 'research/readiness/verify.py')
    stage(6, 'ready-v2', '启动就绪 1.2 / 诊断 1.4', 'retained-source',
          '保留的 readiness 源码含就绪等待和进程独立日志；不导入用户诊断日志、设备身份或运行采样。',
          [a.apks / 'iOS-婷婷_启动修复版_v2.apk', a.apks / '婷婷诊断_1.4.apk'])

    def replace(old, new):
        def apply(data):
            if old not in data:
                raise ValueError('documented reconstruction anchor missing')
            return data.replace(old, new)
        return apply

    service = 'src/com/android/appletts/compat/ReadyTtsService.java'
    def old_service(data):
        old = b'int rate = SpeechRate.toNative(request.getSpeechRate());'
        ending = b'\r\n' if b'\r\n' in data else b'\n'
        return replace(old, b'int rate = request.getSpeechRate();' + ending +
                       b'            if (rate > 100) rate = rate / 2 + 100;  // Retain the original wrapper\'s rate curve.')(data)

    def old_diagnostic(data):
        data = replace(b'1.6-rate', b'1.5-words')(data)
        return replace('1.6（倍速修复、词边界）'.encode(), '1.5（词边界）'.encode())(data)

    def old_build(data):
        data = replace('iOS-婷婷_倍速修复版_v1.4.apk'.encode(), 'iOS-婷婷_逐词高亮版.apk'.encode())(data)
        data = replace('婷婷诊断_1.6.apk'.encode(), '婷婷诊断_1.5.apk'.encode())(data)
        return b'\n'.join(line for line in data.split(b'\n') if b'Copy-Item -LiteralPath $output' not in line)

    transforms = {service: old_service, 'src/local/tingting/diagnostic/DiagnosticActivity.java': old_diagnostic,
                  'production/AndroidManifest.xml': lambda d: d.replace(b'versionCode="8"', b'versionCode="7"').replace(b'1.4-rate', b'1.3-words'),
                  'diagnostic/AndroidManifest.xml': lambda d: d.replace(b'versionCode="7"', b'versionCode="6"').replace(b'1.6-rate', b'1.5-words'),
                  'build.ps1': old_build}
    project_sources('word-highlighting', omit=('src/com/android/appletts/compat/SpeechRate.java', 'test/SpeechRateCheck.java'), transforms=transforms)
    stage(7, 'word-highlighting', '逐词位置回调 1.3 / 诊断 1.5', 'documented-reconstruction',
          '词回调代码保留；倍速调用、版本标签、诊断说明和打包文件名按已记录的后续变更逆向还原。不是完整原始文件逐字备份，不声称重现同一个签名 APK；旧版 verify.py 未单独恢复。',
          [a.apks / 'iOS-婷婷_逐词高亮版.apk', a.apks / '婷婷诊断_1.5.apk'])
    project_sources('word-highlighting')
    add_file(a.research / 'word-highlighting/verify.py', 'research/word-highlighting/verify.py')
    stage(8, 'rate-fix', '连续倍速 1.4 / 诊断 1.6', 'retained-source',
          '恢复为实际留存的倍速修复源码，包含 50～400 夹限和 0.02 步进映射回归。签名密码参数已脱敏。',
          [a.apks / 'iOS-婷婷_倍速修复版_v1.4.apk', a.apks / '婷婷诊断_1.6.apk'])
    files = {n: data for n, data in published.items() if not n.startswith('.github/')}
    inventory = {n: {'source': 'published:' + a.published_ref + ':' + n,
                     'source_sha256': sha(data), 'stored_sha256': sha(data), 'transformations': []}
                 for n, data in files.items()}
    stage(9, 'five-voices-1.5.0', '五音色独立包 1.5.0 已发布源码', 'published-source',
          '直接取自已经公开的提交；仅不引入发布工作流。src、tools、tests、音色配置及构建入口与发布版逐字相同。Release 与其原标签保持不变。', [])
    current_manifest = json.loads(published['docs/VERIFICATION-1.5.0.json'])
    records[-1]['released_artifacts'] = current_manifest['artifacts']
    branch = 'history/reconstructed-20261114'
    git('update-ref', 'refs/heads/' + branch, parent, '0' * 40)
    for record in records:
        git('update-ref', 'refs/tags/' + record['tag'], record['commit'], '0' * 40)
    a.output.mkdir(parents=True, exist_ok=True)
    report = {'disclosure': 'Retrospective reconstruction; real current commit timestamps; no original history rewritten',
              'published_ref_unchanged': a.published_ref, 'branch': branch, 'tip': parent,
              'stages': records}
    (a.output / 'history-index.json').write_bytes(json_bytes(report))
    print(json.dumps({'branch': branch, 'tip': parent,
                      'stages': [{k: s[k] for k in ('sequence', 'id', 'status', 'commit', 'tag')} for s in records]}, indent=2))


if __name__ == '__main__':
    main()
