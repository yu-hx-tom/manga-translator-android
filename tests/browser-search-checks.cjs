const {chromium}=require('C:/Users/Administrator/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const fs=require('fs'),path=require('path'),assert=require('assert');
const root=path.resolve(__dirname,'..'),out=path.join(root,'验证记录/1.0.8连续搜索');fs.mkdirSync(out,{recursive:true});
const script=fs.readFileSync(path.join(root,'app/src/main/assets/browser_search.js'),'utf8');
(async()=>{
 const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});
 try{
 const context=await browser.newContext({viewport:{width:390,height:700},isMobile:true,hasTouch:true});const page=await context.newPage();let checks=0;
 const check=(ok,label)=>{assert(ok,label);checks++;};
 await page.route('https://search.test/**',r=>r.fulfill({contentType:'text/html',body:'<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><form><input type="search" name="q"><button>搜索</button></form><input type="search" id="second"><input id="ordinary"><textarea name="q" id="multi"></textarea><input class="searchInput" id="custom"><form action="/search"><input name="text" id="actionSearch"></form><input type="password" id="password"><form><input type="search" id="login"><input type="password"></form><script>document.querySelector("form").onsubmit=e=>e.preventDefault();window.inputs=0;document.addEventListener("input",()=>window.inputs++)</script>'}));
 await page.addInitScript(()=>{window.messages=[];window.__mangaSearchHistory={postMessage(raw){window.messages.push(JSON.parse(raw));}};});
 await page.goto('https://search.test/');await page.evaluate(script);
 await page.locator('input[name=q]').tap();
 const target=await page.evaluate(()=>messages.filter(m=>m.op==='load').at(-1).target);
 check(await page.evaluate(()=>messages.at(-1).focused),'focus reports active search to native');
 const fill=(term,target)=>page.evaluate(({term,target})=>window.__mangaSearchHistory.onmessage({data:JSON.stringify({op:'fill',target,term})}),{term,target});
 await fill('历史词条',target);check(await page.locator('input[name=q]').inputValue()==='历史词条','native reply fills field');
 check(await page.evaluate(()=>document.activeElement.name==='q'),'fill preserves web focus');
 check(await page.evaluate(()=>inputs===1),'site input handler notified');
 await page.locator('input[name=q]').press('Enter');check(await page.evaluate(()=>messages.some(m=>m.op==='save'&&m.term==='历史词条')),'Enter persists term');
 await page.locator('input[name=q]').fill('搜索按钮');await page.locator('form button').tap();check(await page.evaluate(()=>messages.some(m=>m.op==='save'&&m.term==='搜索按钮')),'button persists term');
 for(const id of ['ordinary','password','login']){await page.locator('#'+id).tap();await page.waitForTimeout(20);check(await page.evaluate(()=>messages.at(-1).op==='hide'),'hide on '+id);}
 await fill('不能覆盖',target);check(await page.locator('#login').inputValue()==='','old reply cannot fill login field');
 await page.locator('#second').tap();await fill('旧目标',target);check(await page.locator('#second').inputValue()==='','stale target rejected');
 const next=await page.evaluate(()=>messages.filter(m=>m.op==='load').at(-1).target);await fill('新目标',next);check(await page.locator('#second').inputValue()==='新目标','current target accepted');
 await page.locator('#second').tap();check(await page.evaluate(()=>messages.at(-1).op==='load'),'retap refreshes accessory');
 check(await page.locator('body').evaluate(el=>!el.querySelector('nav')&&!document.querySelector('[style*="2147483647"]')),'no webpage overlay competes with IME');
 await page.goto('https://search.test/next');await page.evaluate(script);await page.locator('input[name=q]').tap();await fill('旧页面',target);check(await page.locator('input[name=q]').inputValue()==='','old document fill rejected');
 for(const id of ['custom','actionSearch']){await page.locator('#'+id).tap();check(await page.evaluate(()=>messages.filter(m=>m.op==='load').at(-1).focused),'custom search detected '+id);}
 await page.locator('#multi').tap();const multi=await page.evaluate(()=>messages.filter(m=>m.op==='load').at(-1));check(multi.focused,'textarea recognized');await fill('多行搜索框',multi.target);check(await page.locator('#multi').inputValue()==='多行搜索框','textarea native fill');await page.locator('#multi').press('Enter');check(await page.evaluate(()=>messages.some(m=>m.op==='save'&&m.term==='多行搜索框')),'textarea submits history');
 const home=await context.newPage();await home.route('https://manga-home.invalid/**',r=>r.fulfill({contentType:'text/html',body:fs.readFileSync(path.join(root,'app/src/main/assets/browser_home.html'),'utf8')}));await home.goto('https://manga-home.invalid/');await home.waitForTimeout(700);
 for(const width of [320,390]){await home.setViewportSize({width,height:700});check(await home.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'home no overflow '+width);}
 await home.locator('.search').tap();await home.waitForURL('https://manga-home.invalid/search');check(true,'home search entry works');
 fs.writeFileSync(path.join(out,'网页桥接检查.json'),JSON.stringify({checks,result:'PASS',scope:'Desktop Chromium bridge/focus checks only; native accessory and physical IME unverified'},null,2));console.log('PASS '+checks+' bridge/focus/layout checks');
 }finally{await browser.close();}
})().catch(e=>{console.error(e);process.exit(1)});
