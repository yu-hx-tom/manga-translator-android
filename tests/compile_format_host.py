from pathlib import Path
import subprocess, json, sys, zipfile
sys.stdout.reconfigure(encoding='utf-8')
root=Path(__file__).resolve().parents[1]
phase=sys.argv[1]
out=root/'build/规范化'/phase/'all-host-verified'
assert not out.exists(), 'Use a fresh phase name; do not reuse partial classes'
out.mkdir(parents=True,exist_ok=True)
tests=root/'tests'
jdk=Path('D:/claude-code-space/.toolchains/android/jdk/jdk-17.0.20+8/bin')
prod=root/'app/build/intermediates/javac/release/compileReleaseJavaWithJavac/classes'
android=Path('D:/claude-code-space/.toolchains/android/android-sdk/platforms/android-36/android.jar')
aar=next(Path('D:/claude-code-space/.toolchains/android/gradle-home/caches').rglob('onnxruntime-android-1.22.0.aar'))
onnx=[out/'onnx-classes.jar']
with zipfile.ZipFile(aar) as z: onnx[0].write_bytes(z.read('classes.jar'))
cp=';'.join(map(str,[prod,tests/'deps/json-20250517.jar',android,*onnx]))
fixtures=[p for p in (tests/'storage-host').rglob('*.java') if p.name!='PerformanceDiagnostics.java']
fixtures += [tests/p for p in ['api-host/android/os/SystemClock.java','host-geometry/android/graphics/Rect.java','0.9.4验证/请求与设置/host/android/graphics/Bitmap.java','v090-cleanup-host/android/graphics/BitmapFactory.java','v073-cache-host/android/util/Base64.java','v073-cache-host/android/webkit/CookieManager.java','v060-host/cn/local/manga/Detector.java','rtdetr-session-host/cn/local/manga/RtDetrDetector.java']]
excluded={'AdaptiveTypographyReview.java','CacheBenchmark.java','ProductionCleanupFlowChecks.java','V090AnchorChecks.java','V090MaskAudit.java','V090PlainRenderingChecks.java','V090SurfaceChecks.java','RtDetrSessionChecks.java','V073IntegrationChecks.java'}
sources=[p for p in sorted(tests.glob('*.java')) if p.name not in excluded]
fixtures=[p for p in fixtures if p.name!='RtDetrDetector.java']
args=['-encoding','UTF-8','-cp',cp,'-d',str(out/'classes'),*map(str,fixtures+sources)]
argfile=out/'javac.args';argfile.write_text('\n'.join('"'+s.replace('\\','/')+'"' for s in args),encoding='utf-8')
r=subprocess.run([str(jdk/'javac.exe'),'-J-Dfile.encoding=UTF-8','@'+str(argfile)],capture_output=True)
(out/'compile.log').write_bytes(r.stdout+r.stderr)
print('ALL_HOST_COMPILE',r.returncode,'ONNX',onnx)
print((r.stdout+r.stderr).decode('utf-8',errors='replace')[:16000])
if r.returncode:sys.exit(r.returncode)
coverage=[p.relative_to(root).as_posix() for p in sources+fixtures]
for p in sorted(tests.rglob('*.java')):
 if p.relative_to(root).as_posix() in coverage or p.name in excluded-{'RtDetrSessionChecks.java','V073IntegrationChecks.java'}:continue
 dest=out/'isolated'/p.relative_to(tests).with_suffix('')
 dest.mkdir(parents=True,exist_ok=True)
 extra=[tests/'rtdetr-session-host/cn/local/manga/RtDetrDetector.java'] if p.name in {'RtDetrSessionChecks.java','V073IntegrationChecks.java'} else []
 rr=subprocess.run([str(jdk/'javac.exe'),'-J-Dfile.encoding=UTF-8','-encoding','UTF-8','-cp',str(out/'classes')+';'+cp,'-d',str(dest),str(p),*map(str,extra)],capture_output=True)
 (dest/'compile.log').write_bytes(rr.stdout+rr.stderr)
 if rr.returncode: print('ISOLATED_FAILED',p.relative_to(tests));print((rr.stdout+rr.stderr).decode('utf-8',errors='replace')[:1500]);sys.exit(rr.returncode)
 coverage.append(p.relative_to(root).as_posix())
(out/'coverage.json').write_text(json.dumps({'compiledSources':sorted(coverage),'excluded':sorted(excluded-{'RtDetrSessionChecks.java','V073IntegrationChecks.java'})},ensure_ascii=False,indent=2),encoding='utf-8')
print('COVERAGE',len(coverage),'of',len(list(tests.rglob('*.java'))))
