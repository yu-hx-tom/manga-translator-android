"""Local milestone packaging; no user credentials or network calls."""
from pathlib import Path
import hashlib,json,re,shutil,subprocess,sys,zipfile

root=Path(__file__).resolve().parents[1]
sdk=Path('D:/claude-code-space/.toolchains/android/android-sdk')
java=Path('D:/claude-code-space/.toolchains/android/jdk/jdk-17.0.20+8/bin/java.exe')
if sys.argv[1]=='colors':
    source=(root/'app/src/main/java/cn/local/manga/Ui.java').read_text(encoding='utf-8')
    pairs=re.findall(r'\b([A-Z_]+)\s*=\s*(0x[0-9a-fA-F]{8})',source)
    xml='<resources>\n    <color name="app_background">#F6F8FC</color>\n'
    xml+=''.join(f'    <color name="ui_{name.lower()}">#{value[2:].upper()}</color>\n' for name,value in pairs)
    (root/'app/src/main/res/values/colors.xml').write_text(xml+'</resources>\n',encoding='utf-8')
    print('Color tokens:',len(pairs));sys.exit()

stage=sys.argv[1];apk=root/'app/build/outputs/apk/debug/app-debug.apk'
gradle=(root/'app/build.gradle').read_text(encoding='utf-8');version=re.search(r"versionName '([^']+)'",gradle)[1]
out=root/('验证记录/1.1.0'+stage);out.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(apk) as z:
    models=[n for n in z.namelist() if n.endswith('.onnx')]
    assert models==['assets/detector.onnx'],models
    modelsha=hashlib.sha256(z.read(models[0])).hexdigest()
sign=subprocess.run([str(java),'-jar',str(sdk/'build-tools/36.0.0/lib/apksigner.jar'),'verify','--print-certs',str(apk)],capture_output=True,text=True,check=True).stdout
signer=re.search(r'certificate SHA-256 digest: (\w+)',sign)[1]
assert signer=='e61c186c4c5ea35320d6fa07fea40e26fe409cc5a0662aef752e318d8f27fe60','Existing update signature changed'
destination=root/'交付'/f'漫画翻译助手-{version}-{stage}.apk';shutil.copy2(apk,destination)
files=[p for p in (root/'app/src').rglob('*') if p.is_file() and p.suffix!='.onnx']+[root/'app/build.gradle']
with zipfile.ZipFile(out/'源码快照.zip','w',zipfile.ZIP_DEFLATED) as z:
    for p in files:z.write(p,p.relative_to(root))
result={'versionName':version,'versionCode':36,'apk':destination.name,'bytes':apk.stat().st_size,'sha256':hashlib.sha256(apk.read_bytes()).hexdigest(),'signerSha256':signer,'models':models,'modelSha256':modelsha,'offlineBuild':'passed','nativeUiAcceptance':'未验收：没有连接设备，未恢复安卓仿真实验','sourceSnapshot':'源码快照.zip'}
(out/'打包验证.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
(root/'交付'/f'{version}-{stage}说明.txt').write_text(f'{version} · {stage}\n离线构建通过；签名与 1.0.9 相同；只打包 PP-OCR。\n原生界面和真机体验未验收。此为迭代里程碑，正式交付以 1.1.0 为准。\n',encoding='utf-8')
print(json.dumps(result,ensure_ascii=True))
