"""Offline source hygiene and pure-Java queue checks. No translation requests."""
from pathlib import Path
import os, re, shutil, subprocess, tempfile

root = Path(__file__).resolve().parents[1]
java_home = Path(os.environ['JAVA_HOME']) / 'bin' if os.environ.get('JAVA_HOME') else None
exe = '.exe' if os.name == 'nt' else ''
javac = str(java_home / ('javac' + exe)) if java_home else shutil.which('javac')
java = str(java_home / ('java' + exe)) if java_home else shutil.which('java')
assert javac and java, 'Install JDK 17 and configure JAVA_HOME'
settings = (root / 'app/src/main/java/cn/local/manga/AppSettings.java').read_text(encoding='utf-8')
for field in ('baseUrl', 'apiKey', 'textModel', 'imageModel'):
    assert re.search(r'public String ' + field + r'\s*=\s*"";', settings), field + ' must default to empty'

# Inspect tracked files when in Git; otherwise inspect the source export.
tracked = subprocess.run(['git', '-C', str(root), 'ls-files', '-z'], capture_output=True)
paths = [root / p for p in tracked.stdout.decode('utf-8').split('\0') if p] if tracked.returncode == 0 else [p for p in root.rglob('*') if p.is_file() and not {'build', '.gradle', '.git'} & set(p.relative_to(root).parts)]
for p in paths:
    assert p.suffix.lower() not in {'.apk', '.aab', '.jks', '.keystore', '.pem', '.key', '.onnx', '.log'}, 'Private or generated file: ' + str(p.relative_to(root))
    if p.suffix.lower() in {'.java', '.js', '.html', '.xml', '.gradle', '.md'}:
        value = p.read_text(encoding='utf-8')
        assert not re.search(r'(?:sk-[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----)', value), 'Possible secret in ' + str(p.relative_to(root))

checks = ['AutoTranslationQueueChecks', 'QueueRetryChecks']
with tempfile.TemporaryDirectory(prefix='manga-source-checks-') as out:
    sources = [root / 'app/src/main/java/cn/local/manga/AutoTranslationQueue.java'] + [root / ('tests/' + name + '.java') for name in checks]
    subprocess.run([javac, '-encoding', 'UTF-8', '-d', out, *map(str, sources)], check=True)
    for name in checks:
        subprocess.run([java, '-Dfile.encoding=UTF-8', '-cp', out, 'cn.local.manga.' + name], check=True)
print('Source checks passed; API defaults are empty.')
