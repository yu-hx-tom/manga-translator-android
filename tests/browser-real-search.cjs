const {chromium}=require('C:/Users/Administrator/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const fs=require('fs'),path=require('path'),assert=require('assert');
const root=path.resolve(__dirname,'..'),out=path.join(root,'验证记录/1.0.7横栏删除');
(async()=>{const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});const results=[];try{
for(const version of ['1.0.6','current']){
 const ctx=await browser.newContext({viewport:{width:390,height:780},isMobile:true,hasTouch:true});let messages=[];
 await ctx.exposeBinding('__recordSearch',(_,message)=>messages.push(message));
 await ctx.addInitScript(()=>{window.__mangaSearchHistory={postMessage(raw){window.__recordSearch(JSON.parse(raw));}};});
 await ctx.addInitScript({path:version==='current'?path.join(root,'app/src/main/assets/browser_search.js'):path.join(out,'1.0.6-browser_search.js')});
 for(const [name,url,selector] of [['Wikipedia','https://www.wikipedia.org/','input[name=search]'],['Google','https://www.google.com/','textarea[name=q]']]){
  const page=await ctx.newPage();messages=[];
  try{await page.goto(url,{waitUntil:'commit',timeout:25000});const field=page.locator(selector).first();await field.waitFor();
   const tag=await field.evaluate(e=>e.tagName);await field.tap();await page.waitForTimeout(120);
   const loaded=messages.find(m=>m.op==='load'&&m.focused),result={version,name,url:page.url(),fieldTag:tag,recognized:!!loaded};
   if(loaded){await page.evaluate(target=>window.__mangaSearchHistory.onmessage({data:JSON.stringify({op:'fill',target,term:'Manga'})}),loaded.target);
    result.fillPassed=await field.inputValue()==='Manga';result.focusRetained=await field.evaluate(e=>document.activeElement===e);
    await field.press('Enter',{noWaitAfter:true});await page.waitForTimeout(350);result.submitSaved=messages.some(m=>m.op==='save'&&m.term==='Manga');
   }
   results.push(result);console.log(JSON.stringify(result));
  }catch(e){results.push({version,name,error:e.message});console.log(version+' '+name+' '+e.message)}finally{await page.close();}
 }
 await ctx.close();
}
fs.writeFileSync(path.join(out,'真实网站前后对比.json'),JSON.stringify({scope:'Actual website DOM in desktop Chromium touch emulation, Android message receiver mocked; not a native IME test',results},null,2));
for(const r of results.filter(r=>r.version==='current'))assert(r.recognized&&r.fillPassed&&r.focusRetained&&r.submitSaved,JSON.stringify(r));
assert(results.find(r=>r.version==='1.0.6'&&r.name==='Google').recognized===false,'old textarea omission reproduced');
}finally{await browser.close();}})().catch(e=>{console.error(e);process.exit(1)});

