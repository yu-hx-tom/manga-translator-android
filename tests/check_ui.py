"""Run static UI gates with JDK 17; no device or translation API needed."""
from pathlib import Path
import os,shutil,subprocess,tempfile
root=Path(__file__).resolve().parents[1]
suffix='.exe' if os.name=='nt' else ''
def tool(name):
    return str(Path(os.environ['JAVA_HOME'])/'bin'/(name+suffix)) if os.environ.get('JAVA_HOME') else shutil.which(name)
with tempfile.TemporaryDirectory(prefix='manga-ui-') as out:
    checks=['IconGlyphChecks','Ui110SourceChecks']
    subprocess.run([tool('javac'),'-encoding','UTF-8','-d',out,*[str(root/'tests'/(name+'.java')) for name in checks]],check=True)
    for name in checks:
        subprocess.run([tool('java'),'-Dfile.encoding=UTF-8','-cp',out,name,str(root)],check=True)
