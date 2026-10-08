const {chromium}=require('C:/Users/Administrator/.cache/codex-runtimes/codex-primary-runtime/dependencies/node/node_modules/playwright');
const fs=require('fs'),path=require('path'),assert=require('assert');
const root=path.resolve(__dirname,'..'),out=path.join(root,'验证记录/1.0.8连续搜索');fs.mkdirSync(out,{recursive:true});
const baseline=process.argv.includes('--baseline');
const terms=['3D','桂井','漫画','连载','冒险','旅行','星空','猫咪','森林','城市'];
(async()=>{const browser=await chromium.launch({executablePath:'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true});try{
const ctx=await browser.newContext({viewport:{width:390,height:700},isMobile:true,hasTouch:true});let events=[];
await ctx.exposeBinding('__capture',(_,event)=>events.push(event));
await ctx.addInitScript(()=>{window.__mangaSearchHistory={postMessage(raw){window.__capture({...JSON.parse(raw),origin:location.origin});}};});
await ctx.addInitScript({path:baseline?path.join(out,'1.0.7-browser_search.js'):path.join(root,'app/src/main/assets/browser_search.js')});
await ctx.route('https://search.test/**',r=>r.fulfill({contentType:'text/html',body:'<meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><form id="form" action="/result" target="sink"><input name="q" type="search" id="q"><button>搜索</button><a href="#" class="search-icon" onclick="event.preventDefault()">⌕</a></form><iframe name="sink" hidden></iframe><div id="outside" tabindex="0">空白处</div><script>document.getElementById("form").addEventListener("submit",e=>e.preventDefault())</script>'}));
const page=await ctx.newPage();await page.goto('https://search.test/');const field=page.locator('#q');
for(let i=0;i<terms.length;i++){
 await field.fill(terms[i]);
 if(i%4===0)await field.press('Enter');
 if(i%4===1)await page.evaluate(()=>document.getElementById('form').submit());
 if(i%4===2)await page.locator('a.search-icon').tap();
 if(i%4===3)await page.evaluate(()=>{const url='/result?q='+encodeURIComponent(document.getElementById('q').value);history.pushState({},'',url);window.__capture({op:'navigation',url:location.href});});
 await page.waitForTimeout(80);
}
await field.fill('只输入未搜索');await page.locator('#outside').tap();await page.waitForTimeout(80);
assert(!events.some(e=>e.op==='save'&&e.term==='只输入未搜索'),'typing alone must not commit');
if(!baseline)for(let i=0;i<terms.length;i++){const term=terms[i];if(i%4===3)assert(events.some(e=>e.op==='candidate'&&e.term===term)&&events.some(e=>e.op==='navigation'&&decodeURIComponent(e.url).includes(term)),'navigation backup '+term);else assert(events.some(e=>e.op==='save'&&e.term===term),'save path '+term);}
if(baseline){assert(events.some(e=>e.op==='save'&&e.term==='3D'));assert(!events.some(e=>e.op==='save'&&e.term==='桂井'));console.log('BASELINE REPRODUCED: 3D saved, 桂井 via form.submit missing');}
fs.writeFileSync(path.join(out,baseline?'旧版连续搜索事件.json':'连续搜索事件.json'),JSON.stringify({terms,events},null,2));if(!baseline)console.log('PASS: 10 sequential terms across Enter/form.submit/icon/SPA plus no-save on unsubmitted input');
await ctx.close();
}finally{await browser.close();}})().catch(e=>{console.error(e);process.exit(1)});
