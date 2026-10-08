/* Search terms only: top document, explicit search inputs, no general form autofill. */
(()=>{'use strict';
if(window.top!==window||window.__mangaSearchInstalled||location.hostname==='manga-home.invalid'||!window.__mangaSearchHistory)return;
window.__mangaSearchInstalled=true;
const bridge=window.__mangaSearchHistory;
let input=null,target='',sequence=0;const documentKey=Date.now().toString(36)+Math.random().toString(36);
function isSearch(el){
 if(!(el instanceof HTMLInputElement||el instanceof HTMLTextAreaElement)||el.disabled||el.readOnly)return false;
 if(el instanceof HTMLInputElement&&!['search','text',''].includes(el.type))return false;
 const hint=[el.name,el.id,el.className,el.placeholder,el.getAttribute('aria-label')].join(' ').replace(/([a-z])([A-Z])/g,'$1 $2');
 if(/password|passwd|email|token|验证码|密码|邮箱/i.test(hint)||el.closest('form')?.querySelector('input[type=password]'))return false;
 const form=el.form,action=form?new URL(form.action||location.href,location.href).pathname:'';
 return el.type==='search'||el.enterKeyHint==='search'||el.getAttribute('role')==='searchbox'||el.closest('[role=search]')!==null||/(^|[\s_-])(q|s|query|keyword|keywords|search|searchword|searchkey|searchstr|searchterm)([\s_-]|$)|搜索|搜尋|検索|关键词|关键字|關鍵詞|關鍵字/i.test(hint)||(/(?:^|\/)(?:search|search.php|search.html)(?:\/|$)/i.test(action)&&form.querySelectorAll('input:not([type=hidden]):not([type=submit]):not([type=button]),textarea').length===1);
}
function send(op,term){bridge.postMessage(JSON.stringify({op,term:term||'',field:input?.name||'',target,focused:!!input&&document.activeElement===input}));}
// Keep a transient candidate in native memory. Only a matching search navigation commits it.
function candidate(field){if(isSearch(field)){const value=field.value.trim();if(value&&value.length<=256)send('candidate',value);}}
document.addEventListener('input',e=>candidate(e.target),true);
document.addEventListener('compositionend',e=>candidate(e.target),true);
bridge.onmessage=e=>{try{const data=JSON.parse(e.data);if(data.op!=='fill'||data.target!==target||!isSearch(input)||!input.isConnected||document.activeElement!==input||typeof data.term!=='string'||data.term.length>256)return;
 const field=input,prototype=field instanceof HTMLTextAreaElement?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;Object.getOwnPropertyDescriptor(prototype,'value').set.call(field,data.term);field.dispatchEvent(new Event('input',{bubbles:true}));field.dispatchEvent(new Event('change',{bubbles:true}));field.focus({preventScroll:true});field.setSelectionRange(data.term.length,data.term.length);
}catch(_){}};
document.addEventListener('focusin',e=>{if(isSearch(e.target)){input=e.target;target=documentKey+':'+(++sequence);send('load');candidate(input);}else send('hide');},true);
document.addEventListener('focusout',()=>setTimeout(()=>{if(document.activeElement!==input)send('hide');},0),true);
// Tapping the still-focused input after dismissing the keyboard refreshes the native accessory.
document.addEventListener('pointerup',e=>{if(e.target===input&&document.activeElement===input)send('load');},true);
function save(target){if(isSearch(target)){const term=target.value.trim();if(term&&term.length<=256)send('save',term);}}
function saveForm(form){const fields=Array.from(form.querySelectorAll('input,textarea')).filter(isSearch);save(fields.includes(input)?input:fields.length===1?fields[0]:null);}
document.addEventListener('submit',e=>saveForm(e.target),true);
// form.submit() bypasses the submit event (common on mobile search buttons).
const originalSubmit=HTMLFormElement.prototype.submit;
HTMLFormElement.prototype.submit=function(){saveForm(this);return originalSubmit.apply(this,arguments);};
document.addEventListener('search',e=>save(e.target),true);
document.addEventListener('keydown',e=>{if(e.isTrusted&&e.key==='Enter'&&!e.isComposing)save(e.target);},true);
document.addEventListener('click',e=>{if(!e.isTrusted||!input||!input.isConnected)return;
 const button=e.target.closest('button,input[type=submit],[role=button],a,[onclick],[class*=search i],[id*=search i]');
 if(!button||button===input||button.contains(input))return;
 const hint=[button.textContent,button.id,button.className,button.getAttribute('aria-label'),button.title,button.getAttribute('href'),button.querySelector('[class*=search i],[aria-label*=搜索]')?'search':''].join(' ');
 const form=input.form;let scope=input.parentElement;for(let i=0;i<2&&scope&&!scope.contains(button);i++)scope=scope.parentElement;
 if((form&&(button.form===form||form.contains(button))&&(button.type==='submit'||/搜索|搜尋|検索|search/i.test(hint)))||(!form&&scope?.contains(button)&&/搜索|搜尋|検索|search/i.test(hint)))save(input);
},true);

})();
