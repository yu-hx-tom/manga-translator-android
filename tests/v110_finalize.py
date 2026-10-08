from pathlib import Path
import hashlib,json,subprocess,zipfile
root=Path(__file__).resolve().parents[1]
delivery=root/'交付'; records=root/'验证记录/1.1.0体验优化'
report=json.loads((records/'打包验证.json').read_text(encoding='utf-8'))
baseline=delivery/'漫画翻译助手-1.0.9-常用网站.apk'
report['baselineBytes']=baseline.stat().st_size
report['increaseBytes']=report['bytes']-report['baselineBytes']
assert 0<report['increaseBytes']<=1000000
assert report['modelSha256']=='d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e'
original=json.loads((root/'验证记录/1.1.0基线/源码SHA256.json').read_text(encoding='utf-8'))
protected=['ApiClient','AppSettings','AutoTranslationQueue','TranslationEngine','Detector','WhiteBubbleCleaner','BubbleLayout','ComicProject','ProjectStore','ExportJob','ExportWriters','PageDraft','PageComposer']
report['unchangedCoreFiles']=[]
for name in protected:
    relative='app/src/main/java/cn/local/manga/'+name+'.java'
    assert hashlib.sha256((root/relative).read_bytes()).hexdigest()==original[relative],name
    report['unchangedCoreFiles'].append(name+'.java')
report['fontMappingException']='TextRenderer 系统粗黑字型映射为 Typeface.BOLD；字号、样式编辑的更改在 UI 层。'
aapt=Path('D:/claude-code-space/.toolchains/android/android-sdk/build-tools/36.0.0/aapt.exe')
badging=subprocess.check_output([str(aapt),'dump','badging','app-debug.apk'],cwd=root/'app/build/outputs/apk/debug',encoding='utf-8')
assert "name='cn.local.manga'" in badging and "versionCode='36'" in badging and "versionName='1.1.0'" in badging
(records/'APK信息.txt').write_text(badging,encoding='utf-8')
report['packageVerified']=True
for target in [records/'打包验证.json',delivery/'1.1.0打包验证.json']:
    target.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
(delivery/'1.1.0-SHA256.txt').write_text(report['sha256']+'  '+report['apk']+'\n',encoding='utf-8')
notes='''漫画翻译助手 1.1.0（versionCode 36）

安装：用本目录“漫画翻译助手-1.1.0-体验优化.apk”覆盖安装，不要先卸载。包名 cn.local.manga，签名与 1.0.9 相同；启动器名称保留“漫游浏览器”。

本次改动
1. 接入提供的 81 个图标资源，应用与通知图标统一；底部三个入口、全局翻译按钮、进度环和任务状态重做。
2. 浏览器菜单改为底部面板；常用网站栏缩短，圆圈下的名称单行省略，完整名称仍可在编辑中查看。
3. 本地翻译统一文件夹、图片、压缩包导入；单张图片直接进入编辑。旧文件夹记录放入折叠区。
4. 汉化工程卡片增加缩略封面和管理入口；阅读页提供状态标签、失败重试、快速跳页。
5. 编辑页保留上方预览，下方抽屉可收起、半屏、展开；段落字号统一。内嵌样式面板分为文字、颜色、排版、位置，实时预览，取消回滚，完成只记一步撤销。
6. 导出改为底部面板，显示估算体积并用缩略图选页；PNG 隐藏质量滑条。设置、收藏/历史、日志不显示底栏。
7. 不新增字体或 Maven 依赖；系统中文字体最终效果受设备字体支持影响。

已验证
主 APK 和设备检查 APK 离线编译成功；八组既有主机脚本全部通过。
当前调用 290 项；算法 298 项；UI 31 项模型/文件检查、267 项源码检查及图标黑名单 0 项；导入复制 12 项；队列重试 78 项；缓存 34 项；搜索 10+24 项；常用网站 19+11 项。
1.1.0 主页专用检查 13 项通过；图标包字节一致性检查连同源码检查 349 项通过。主页 HTML 保存 320/390 宽截图；图标资源包预览另有 320/390/1100 宽截图。
检测模型仍仅 PP-OCR，哈希未变；签名与 1.0.9 一致，APK 增加 {increase} 字节（小于 1 MB）。主机检查不替代手机性能测试。

未验收
未连接 Android 设备；未恢复此前停止的仿真实验。原生 320/390dp 截图、主题图标/启动器遮罩、通知剪影、实际键盘显示、Tab 动画、50 工程首屏 <500ms、手机内存、1080×2400 预览比例、中文字体差异、旧工程打开及手机端导入/翻译/导出均待真机验收。Ui110Checks 已编译但未运行；桌面图标预览不代表启动器实测。Lint 未完成。

七阶段 alpha1–alpha6 与正式版 APK、说明和源码快照均保留。构建、主机检查和设备验收边界见“验证记录/1.1.0体验优化”。
'''.format(increase=report['increaseBytes'])
(delivery/'1.1.0体验优化说明.txt').write_text(notes,encoding='utf-8')
(delivery/'1.1.0-体验优化说明.txt').write_text(notes,encoding='utf-8')
(root/'新版项目说明.txt').write_text(notes+'\n构建：powershell -NoProfile -ExecutionPolicy Bypass -File .\\构建.ps1\n主机脚本位于 tests；使用既有 Android 工具链与调试签名。\n',encoding='utf-8')
guide=(root/'使用说明.md').read_text(encoding='utf-8').replace('# 漫画翻译助手 1.0.9 使用说明','# 漫画翻译助手 1.1.0 使用说明').replace('交付/漫画翻译助手-1.0.9-常用网站.apk','交付/漫画翻译助手-1.1.0-体验优化.apk').replace('设置 → ④ 汉化工作台 → 缓存管理','设置 → 汉化工作台 → 缓存管理').replace('颜色、描边、内置字体','颜色、描边、系统字体')
guide=guide.replace('## 开始使用','## 1.1.0 体验改动\n\n底栏统一为三个入口和翻译按钮，任务进度显示在其上方。菜单与导出使用底部面板；设置、收藏/历史、日志不显示底栏。常用网站名称单行省略。\n\n本地导入单张图片直接进入编辑页；旧文件夹记录仍可在折叠区打开。编辑页抽屉可收起、半屏或展开；样式分文字、颜色、排版、位置四页，取消会回滚，完成只记一步撤销。字号增减与样式面板使用同一数值。导出可通过缩略图选页，体积仅供估算。\n\n已完成离线构建和主机回归；没有连接 Android 设备，原生界面与手机性能待验收。详见 [1.1.0 体验优化说明](交付/1.1.0体验优化说明.txt)。\n\n## 开始使用',1)
(root/'使用说明.md').write_text(guide,encoding='utf-8')
for stage in ['图标基础','导航外壳','浏览器','本地翻译','汉化工具','编辑页','体验优化']:
    folder=root/('验证记录/1.1.0'+stage)
    (folder/'设备验收状态.txt').write_text('原生界面未验收：未连接 Android 设备，未恢复仿真实验。没有原生 320dp / 390dp 截图。\n主页 HTML 的 320/390 截图见 1.1.0浏览器；图标资源包网页预览见 1.1.0图标。这两项不是原生截图。\n',encoding='utf-8')
print(json.dumps({'version':report['versionName'],'apkBytes':report['bytes'],'increaseBytes':report['increaseBytes'],'protectedFiles':len(protected),'packageVerified':True}))
