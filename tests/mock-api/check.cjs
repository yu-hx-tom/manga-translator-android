'use strict';
const assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const {createServer,translations,imagePart,args}=require('./server.cjs');
const png=Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=','base64');
const body={model:'mock-text',messages:[{role:'user',content:[{type:'text',text:'裁切 id=r0，原文字竖排'},{type:'text',text:'裁切 id=r1，原文字横排'},{type:'image_url',image_url:{url:'data:image/png;base64,'+png.toString('base64')}}]}]};
(async()=>{let checks=0;const ok=(condition)=>{assert(condition);checks++;};const temp=fs.mkdtempSync(path.join(os.tmpdir(),'manga-mock-'));fs.writeFileSync(path.join(temp,'1.png'),png);let server;
try{
 const rows=translations(body,'normal');ok(rows.length===2&&rows[0].id==='r0'&&rows[0].zh==='竖排测试文字');ok(JSON.stringify(rows)===JSON.stringify(translations(body,'normal')));ok(translations(body,'missing').length===1);ok(translations(body,'skip').every(x=>x.skip));ok(translations(body,'long')[0].zh.length>100);assert.throws(()=>args(['--delay','500']));checks++;
 server=createServer({host:'127.0.0.1',port:0,images:temp});await new Promise(r=>server.listen(0,'127.0.0.1',r));const base='http://127.0.0.1:'+server.address().port;
 let response=await fetch(base+'/v1/models');ok((await response.json()).data.length===2);
 response=await fetch(base+'/v1/chat/completions',{method:'POST',body:JSON.stringify(body)});const chat=await response.json();ok(JSON.parse(chat.choices[0].message.content).translations.length===2);
 response=await fetch(base+'/v1/responses',{method:'POST',body:JSON.stringify({model:'mock-text',input:[{role:'user',content:[{type:'input_text',text:'裁切 id=r8，原文字横排'}]}]})});ok(JSON.parse((await response.json()).output[0].content[0].text).translations[0].id==='r8');
 const multipart=Buffer.concat([Buffer.from('--test\r\nContent-Disposition: form-data; name="image"; filename="test.png"\r\nContent-Type: image/png\r\n\r\n'),png,Buffer.from('\r\n--test--\r\n')]);ok(imagePart(multipart,'multipart/form-data; boundary=test').equals(png));
 response=await fetch(base+'/v1/images/edits',{method:'POST',headers:{'content-type':'multipart/form-data; boundary=test'},body:multipart});ok(Buffer.from((await response.json()).data[0].b64_json,'base64').equals(png));
 response=await fetch(base+'/images/0');ok(Buffer.from(await response.arrayBuffer()).equals(png));ok((await fetch(base+'/images/../server.cjs')).status===404);ok((await(await fetch(base+'/?lazy=1')).text()).includes('IntersectionObserver'));
 await new Promise(r=>server.close(r));server=createServer({images:temp,rate429:100});await new Promise(r=>server.listen(0,'127.0.0.1',r));response=await fetch('http://127.0.0.1:'+server.address().port+'/v1/chat/completions',{method:'POST',body:JSON.stringify(body)});ok(response.status===429);
 await new Promise(r=>server.close(r));server=createServer({images:temp,rate503:100});await new Promise(r=>server.listen(0,'127.0.0.1',r));response=await fetch('http://127.0.0.1:'+server.address().port+'/v1/chat/completions',{method:'POST',body:JSON.stringify(body)});ok(response.status===503);
 await new Promise(r=>server.close(r));server=createServer({images:temp,scenario:'truncated'});await new Promise(r=>server.listen(0,'127.0.0.1',r));response=await fetch('http://127.0.0.1:'+server.address().port+'/v1/chat/completions',{method:'POST',body:JSON.stringify(body)});const partial=await response.json();ok(partial.choices[0].finish_reason==='length');assert.throws(()=>JSON.parse(partial.choices[0].message.content));checks++;
 await new Promise(r=>server.close(r));server=createServer({images:temp,delay:3000});await new Promise(r=>server.listen(0,'127.0.0.1',r));const started=Date.now();response=await fetch('http://127.0.0.1:'+server.address().port+'/v1/chat/completions',{method:'POST',body:JSON.stringify(body)});ok(response.status===200&&Date.now()-started>=2800);
 console.log(JSON.stringify({passed:true,checks,syntheticOnly:true,upstreamRequests:0}));
}finally{if(server?.listening)await new Promise(r=>server.close(r));const resolved=fs.realpathSync(temp);assert.equal(path.dirname(resolved),fs.realpathSync(os.tmpdir()));assert(path.basename(resolved).startsWith('manga-mock-'));fs.rmSync(resolved,{recursive:true,force:true});}
})().catch(e=>{console.error(e);process.exitCode=1;});
