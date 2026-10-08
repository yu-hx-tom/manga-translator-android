from pathlib import Path
import hashlib
import json
import zipfile
from datetime import datetime, timezone

root = Path(__file__).resolve().parents[1]
delivery = root / '交付'
proof = root / '验证记录/1.1.2存储改造'
apk = delivery / '漫画翻译助手-1.1.2-存储改造.apk'
target = delivery / '漫画翻译助手-1.1.2-源码.zip'


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


package = json.loads((delivery / '1.1.2打包验证.json').read_text(encoding='utf-8-sig'))
assert digest(apk) == package['sha256'], 'APK differs from verified package'
assert package['versionCode'] == 41 and package['versionName'] == '1.1.2'
files = set()


def add(path):
    assert path.is_file(), path
    assert path.resolve().is_relative_to(root), path
    files.add(path)


for name in ['.gitignore', 'build.gradle', 'settings.gradle', 'gradle.properties',
             '构建.ps1', '使用说明.md', '新版项目说明.txt', '第三方与模型来源.md', '电脑端并发设置.md',
             'app/build.gradle']:
    add(root / name)
for path in (root / 'app/src').rglob('*'):
    if path.is_file():
        add(path)
for path in (root / 'tests').glob('*.java'):
    add(path)
for name in ['验证1.1.1-alpha1.ps1', '验证1.1.2存储.ps1', '验证1.1.2配套回归.ps1',
             '打包验证1.1.2.ps1', '打包源码1.1.2.py', 'home-sites-checks.cjs', 'deps/json-20250517.jar']:
    add(root / 'tests' / name)
for name in ['api-host', 'host-geometry', 'rtdetr-session-host', 'storage-host', 'v060-host',
             'v073-cache-host', 'v090-cleanup-host', '0.9.4验证/请求与设置/host', 'mock-api']:
    for path in (root / 'tests' / name).rglob('*'):
        if path.is_file() and path.suffix in {'.java', '.py', '.cjs', '.md', '.bat'}:
            add(path)
for path in delivery.iterdir():
    if path.is_file() and path.suffix in {'.md', '.txt'} and 'SHA256' not in path.name:
        add(path)
add(delivery / '1.1.2打包验证.json')
for name in ['1.1.2发布构建.log', '导航图标字节码.txt', '未改动算法与设置哈希.json',
             'release-badging.txt', 'release-signature.txt', '迁移核对.md', '需求与证据.md',
             '任务书原文.md', '开工前源码.zip', 'lint-results-debug.xml', 'lint-results-release.xml',
             'lint-results-debug.html', 'lint-results-release.html']:
    add(proof / name)
for name in ['主机存储检查', '算法与请求回归', '配套回归']:
    for path in (proof / name).iterdir():
        if path.is_file() and path.suffix in {'.json', '.log', '.png'}:
            add(path)
add(proof / '配套回归/ImportCopyChecks/导入复制测试.json')
add(root / '验证记录/1.1.2普查/普查报告.md')

for path in files:
    relative = path.relative_to(root)
    assert not any(part in {'build', '.gradle', '__pycache__', 'classes'} for part in relative.parts), relative
    assert path.suffix.lower() not in {'.apk', '.keystore', '.jks', '.pem', '.p12', '.db', '.class'}, relative
for path in (root / 'app/src/main').rglob('*'):
    if path.is_file():
        assert path.stat().st_mtime_ns <= apk.stat().st_mtime_ns, f'APK predates source: {path}'
with zipfile.ZipFile(proof / '开工前源码.zip') as baseline:
    assert all(name.startswith('src/') or name in {'build.gradle', '构建.ps1'}
               for name in baseline.namelist()), 'Unexpected baseline contents'
    assert not any(Path(name).suffix.lower() in {'.keystore', '.jks', '.pem', '.p12'} for name in baseline.namelist())

entries = {path.relative_to(root).as_posix(): {'bytes': path.stat().st_size, 'sha256': digest(path)}
           for path in sorted(files)}
manifest = {'version': '1.1.2', 'apkSha256': package['sha256'], 'files': entries}
temporary = target.with_suffix('.zip.tmp')
with zipfile.ZipFile(temporary, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
    for relative in entries:
        archive.write(root / relative, relative)
    archive.writestr('源码文件清单.json', json.dumps(manifest, ensure_ascii=False, indent=2))
with zipfile.ZipFile(temporary) as archive:
    assert len(archive.namelist()) == len(entries) + 1
    assert archive.testzip() is None
    assert json.loads(archive.read('源码文件清单.json')) == manifest
    for relative, expected in entries.items():
        with archive.open(relative) as stream:
            actual = hashlib.file_digest(stream, 'sha256').hexdigest()
        assert actual == expected['sha256'] == digest(root / relative), relative
        assert archive.getinfo(relative).file_size == expected['bytes'], relative
temporary.replace(target)
source_hash = digest(target)
report = {'versionName': '1.1.2', 'filesVerified': len(entries), 'bytes': target.stat().st_size,
          'sha256': source_hash, 'apkSha256': package['sha256'], 'zipReadbackPassed': True,
          'allCurrentSourceFilesIncluded': True, 'privateKeysIncluded': False,
          'phoneVerified': False, 'checkedAt': datetime.now(timezone.utc).isoformat()}
(delivery / '1.1.2源码打包验证.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
(delivery / '1.1.2-SHA256.txt').write_text(
    f"{package['sha256']}  {apk.name}\n{source_hash}  {target.name}\n", encoding='utf-8')
print(json.dumps(report, ensure_ascii=False, indent=2))
