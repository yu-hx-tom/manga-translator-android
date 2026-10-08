const fs=require('node:fs'),path=require('node:path'),crypto=require('node:crypto');
const root=path.resolve(__dirname,'..'),proof=path.join(root,'验证记录/1.1.1-alpha1');
const hash=file=>crypto.createHash('sha256').update(fs.readFileSync(file)).digest('hex');
function walk(dir,base=dir){return fs.readdirSync(dir,{withFileTypes:true}).flatMap(e=>e.isDirectory()?walk(path.join(dir,e.name),base):[{path:path.relative(base,path.join(dir,e.name)).replaceAll('\\','/'),sha256:hash(path.join(dir,e.name))}]);}
const old=walk(path.join(proof,'修改前源码/src')),now=walk(path.join(root,'app/src')),before=new Map(old.map(x=>[x.path,x.sha256])),after=new Map(now.map(x=>[x.path,x.sha256]));
const changed=now.filter(x=>before.get(x.path)!==x.sha256),removed=old.filter(x=>!after.has(x.path));
if(removed.length)throw Error('Unexpected removed source files');
const algorithms=['WhiteBubbleCleaner','GrayGlyphRepair','BubbleLayout','NearbyTextLayout','Typesetter','Detector','PpOcrDetector','RepairPixels','ImageCleanup'];
const unchanged=[];for(const name of algorithms){const key='main/java/cn/local/manga/'+name+'.java';if(!before.has(key))continue;if(before.get(key)!==after.get(key))throw Error('Algorithm changed: '+name);unchanged.push(name);}
if(changed.some(x=>x.path.startsWith('main/assets/')||x.path.startsWith('main/res/')))throw Error('Unexpected assets/resource change');
const source={sourceFiles:now.length,changed,removed,unchangedAlgorithms:unchanged,assetsAndResourcesUnchanged:true,manifestUnchanged:before.get('main/AndroidManifest.xml')===after.get('main/AndroidManifest.xml'),hashes:now};fs.writeFileSync(path.join(proof,'源码核对.json'),JSON.stringify(source,null,2));
const images='E:/codexwork/漫画';const inputs=fs.readdirSync(images).filter(n=>/\.(png|jpe?g|webp)$/i.test(n)&&fs.statSync(path.join(images,n)).isFile()).sort((a,b)=>a.localeCompare(b,'en',{numeric:true})).slice(0,30).map((name,index)=>({index,name,bytes:fs.statSync(path.join(images,name)).size,sha256:hash(path.join(images,name))}));fs.writeFileSync(path.join(proof,'基准输入清单.json'),JSON.stringify({visualInspection:false,inputs},null,2));
console.log(JSON.stringify({sourceFiles:now.length,changedFiles:changed.length,unchangedAlgorithms:unchanged,assetsAndResourcesUnchanged:true,inputImages:inputs.length,visualInspection:false}));
