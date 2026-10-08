const {chromium}=require('C:/Users/Administrator/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const fs=require('fs'),path=require('path'),{pathToFileURL}=require('url');
const root=path.resolve(__dirname,'..'),out=path.join(root,'验证记录/1.1.0图标');
(async()=>{fs.mkdirSync(out,{recursive:true});const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});try{
    const page=await browser.newPage({viewport:{width:1100,height:900},deviceScaleFactor:1});await page.goto(pathToFileURL('E:/claudeworkspace/漫画翻译_图标设计/preview.html').href);await page.screenshot({path:path.join(out,'图标资源包预览-1100.png'),fullPage:true});
    for(const width of [320,390]){await page.setViewportSize({width,height:900});await page.screenshot({path:path.join(out,'图标资源包预览-'+width+'.png'),fullPage:true});}
    fs.writeFileSync(path.join(out,'截图说明.txt'),'图标资源包原始 preview.html 的桌面 Chromium 截图，仅作为设计参考。\nAndroid 启动器、主题图标、通知栏、原生底栏及编辑/设置页面：真机未验收。\n项目主页截图另见 1.1.0浏览器/常用网站-320.png 和 常用网站-390.png。\n');
    console.log('PASS: supplied icon preview captured at 320/390/1100 CSS pixels; native UI NOT verified');
}finally{await browser.close();}})().catch(error=>{console.error(error);process.exit(1)});
