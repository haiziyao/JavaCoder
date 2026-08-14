package com.jcoder.ui.web;

/** MyCoder 单文件 Web 页面。 */
public final class WebPage {
    private WebPage() {}

    public static String html() {
        return """
                <!doctype html>
                <html lang="zh-CN">
                <head>
                  <meta charset="UTF-8">
                  <meta name="viewport" content="width=device-width,initial-scale=1">
                  <title>MyCoder</title>
                  <style>
                    :root {
                      --blue:#4d6bfe; --blue-dark:#3152e8; --blue-soft:#eef2ff;
                      --canvas:#f7f8fa; --panel:#fff; --soft:#f4f6f8; --line:#e7e9ee;
                      --text:#1f2329; --muted:#697386; --faint:#9aa3b2;
                      --green:#159447; --amber:#c57916; --red:#d23838;
                      --shadow:0 12px 40px rgba(26,38,70,.08);
                    }
                    *{box-sizing:border-box} html,body{width:100%;height:100%;margin:0}
                    body{overflow:hidden;color:var(--text);background:var(--canvas);font-family:Inter,ui-sans-serif,-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;-webkit-font-smoothing:antialiased}
                    button,textarea,select{font:inherit} button{color:inherit}
                    .app-shell{display:grid;grid-template-columns:54px 246px minmax(0,1fr);height:100%}

                    .activity-bar{display:flex;flex-direction:column;align-items:center;gap:8px;padding:12px 8px;background:#f0f2f5;border-right:1px solid var(--line)}
                    .activity-logo{display:grid;place-items:center;width:36px;height:36px;margin-bottom:12px;border-radius:11px;color:#fff;background:linear-gradient(145deg,#6580ff,#3d5df0);font-size:14px;font-weight:800;box-shadow:0 7px 18px rgba(77,107,254,.25)}
                    .activity-spacer{flex:1}
                    .icon-button{display:grid;place-items:center;width:36px;height:36px;border:0;border-radius:9px;background:transparent;color:#737d8d;cursor:pointer;transition:.16s ease}
                    .icon-button:hover,.icon-button.active{color:var(--blue);background:#fff;box-shadow:0 2px 10px rgba(27,39,75,.07)}
                    .icon-button svg{width:19px;height:19px;fill:none;stroke:currentColor;stroke-width:1.8}

                    .sidebar{display:flex;flex-direction:column;min-width:0;background:#f7f8fa;border-right:1px solid var(--line)}
                    .sidebar-head{padding:19px 18px 12px}.brand-title{font-size:15px;font-weight:720;letter-spacing:-.02em}.brand-subtitle{margin-top:4px;color:var(--faint);font-size:11px}
                    .new-chat{display:flex;align-items:center;justify-content:center;gap:7px;width:calc(100% - 28px);margin:8px 14px 14px;padding:10px 12px;border:1px solid #dbe1ff;border-radius:9px;color:var(--blue-dark);background:#f4f6ff;cursor:pointer;font-size:13px;font-weight:620}
                    .new-chat:hover{border-color:#bfc9ff;background:var(--blue-soft)}
                    .sidebar-scroll{flex:1;min-height:0;padding:2px 14px 18px;overflow:auto}.section-label{margin:15px 3px 7px;color:var(--faint);font-size:10px;font-weight:750;letter-spacing:.09em;text-transform:uppercase}
                    .info-card{padding:4px 10px;border:1px solid var(--line);border-radius:10px;background:rgba(255,255,255,.66)}
                    .info-row{display:flex;align-items:center;justify-content:space-between;gap:10px;padding:8px 0;border-bottom:1px solid #eceef2;font-size:12px}.info-row:last-child{border-bottom:0}.info-key{color:var(--muted)}
                    .info-value{max-width:126px;overflow:hidden;color:#394150;font-family:ui-monospace,"Cascadia Code",Consolas,monospace;font-size:11px;text-overflow:ellipsis;white-space:nowrap}
                    .mode-select{width:100%;padding:9px 10px;border:1px solid #d9dde5;border-radius:9px;outline:none;color:#333b49;background:#fff;font-size:12px}.mode-select:focus{border-color:#9cafff;box-shadow:0 0 0 3px rgba(77,107,254,.09)}
                    .tool-list{display:flex;flex-direction:column;gap:4px}.tool-chip{padding:7px 9px;border-radius:7px;color:#596476;font-family:ui-monospace,"Cascadia Code",Consolas,monospace;font-size:11px}.tool-chip:hover{color:#2d3748;background:#edf0f4}.tool-chip::before{content:"›";margin-right:7px;color:var(--blue);font-weight:800}

                    .workspace{display:flex;min-width:0;min-height:0;background:var(--panel)}.chat-column{display:flex;flex:1;flex-direction:column;min-width:0}
                    .topbar{display:flex;align-items:center;gap:12px;height:58px;padding:0 22px;border-bottom:1px solid var(--line);background:rgba(255,255,255,.94);backdrop-filter:blur(12px)}
                    .topbar-title{font-size:13px;font-weight:680}.topbar-path{overflow:hidden;color:var(--faint);font-family:ui-monospace,"Cascadia Code",Consolas,monospace;font-size:11px;text-overflow:ellipsis;white-space:nowrap}.topbar-spacer{flex:1}
                    .mode-pill{padding:4px 9px;border:1px solid #dce2ff;border-radius:999px;color:var(--blue-dark);background:#f5f7ff;font-size:10px;font-weight:750}
                    .run-state{display:flex;align-items:center;gap:7px;color:var(--muted);font-size:12px;white-space:nowrap}.state-dot{width:7px;height:7px;border-radius:50%;background:#aeb5c1}.run-state.running .state-dot{background:var(--amber);animation:breathe 1.3s ease-in-out infinite}.run-state.success .state-dot{background:var(--green)}.run-state.error .state-dot{background:var(--red)}
                    @keyframes breathe{50%{opacity:.35;transform:scale(.82)}}

                    .chat{flex:1;min-height:0;overflow:auto;background:#fff}.chat-inner{width:min(860px,calc(100% - 48px));margin:0 auto;padding:32px 0 48px}
                    .welcome{padding:9vh 6px 28px}.welcome-mark{display:grid;place-items:center;width:44px;height:44px;border-radius:14px;color:#fff;background:linear-gradient(145deg,#6b83ff,#425fe9);box-shadow:0 12px 24px rgba(77,107,254,.22);font-weight:800}.welcome h1{margin:19px 0 7px;font-size:25px;letter-spacing:-.035em}.welcome p{margin:0;color:var(--muted);font-size:14px;line-height:1.7}
                    .quick-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:9px;margin-top:22px}.quick-card{padding:12px 13px;border:1px solid var(--line);border-radius:11px;color:#4d5768;background:#fbfbfc;font-size:12px}
                    .message{margin:0 0 26px}.message-head{display:flex;align-items:center;gap:8px;margin-bottom:8px;color:var(--faint);font-size:11px}.message-avatar{display:grid;place-items:center;width:22px;height:22px;border-radius:7px;font-size:9px;font-weight:800}.message.user .message-head{justify-content:flex-end}.message.user .message-avatar{order:2;color:#fff;background:var(--blue)}.message.assistant .message-avatar{color:var(--blue-dark);background:var(--blue-soft)}
                    .message-body{color:#292f3a;font-size:14px;line-height:1.78;overflow-wrap:anywhere}.message.user .message-body{width:fit-content;max-width:78%;margin-left:auto;padding:10px 14px;border-radius:13px 13px 4px 13px;color:#283247;background:#eef2ff;white-space:pre-wrap}.message-body p{margin:0 0 9px}.message-body p:last-child{margin-bottom:0}.message-body ul{margin:7px 0 10px;padding-left:21px}.message-body li{margin:3px 0}.message-body code{padding:1px 5px;border:1px solid #e0e5ec;border-radius:4px;color:#3152c8;background:#f3f5f8;font-family:ui-monospace,"Cascadia Code",Consolas,monospace;font-size:.88em}
                    .code-block{margin:12px 0;overflow:hidden;border:1px solid #313131;border-radius:10px;background:#1e1e1e;box-shadow:0 8px 24px rgba(0,0,0,.09)}.code-head{display:flex;align-items:center;justify-content:space-between;height:32px;padding:0 12px;border-bottom:1px solid #343434;color:#909090;background:#252526;font-family:ui-monospace,"Cascadia Code",Consolas,monospace;font-size:10px}.code-block pre{margin:0;padding:14px 16px;overflow:auto;color:#d4d4d4;font-family:ui-monospace,"Cascadia Code",Consolas,monospace;font-size:12px;line-height:1.65;white-space:pre}.code-block pre code{padding:0;border:0;color:inherit;background:transparent;font-size:inherit}
                    .typing{display:flex;gap:5px;padding:8px 0}.typing i{width:6px;height:6px;border-radius:50%;background:#a9b0bc;animation:typing 1.1s infinite}.typing i:nth-child(2){animation-delay:.16s}.typing i:nth-child(3){animation-delay:.32s}@keyframes typing{50%{opacity:.25;transform:translateY(-2px)}}

                    .tool-card{margin:-8px 0 18px 30px;overflow:hidden;border:1px solid var(--line);border-radius:9px;background:#fafbfc}.tool-card[open]{box-shadow:0 3px 12px rgba(29,38,58,.04)}.tool-card summary{display:flex;align-items:center;gap:8px;min-height:38px;padding:0 12px;cursor:pointer;color:#475267;list-style:none;font-family:ui-monospace,"Cascadia Code",Consolas,monospace;font-size:11px}.tool-card summary::-webkit-details-marker{display:none}.tool-card summary::before{content:"›";color:var(--faint);font-size:16px;transition:transform .15s}.tool-card[open] summary::before{transform:rotate(90deg)}
                    .tool-kind{padding:2px 6px;border-radius:4px;color:#4a5eac;background:#edf1ff;font-size:9px;font-weight:800;letter-spacing:.06em}.tool-card.error{border-color:#f0c7c7;background:#fffafa}.tool-card.error .tool-kind{color:var(--red);background:#ffeded}.tool-output{max-height:270px;margin:0;padding:12px 14px;overflow:auto;border-top:1px solid var(--line);color:#586274;background:#fff;font-family:ui-monospace,"Cascadia Code",Consolas,monospace;font-size:11px;line-height:1.58;white-space:pre-wrap;overflow-wrap:anywhere}

                    .composer-wrap{padding:13px 24px 20px;background:linear-gradient(180deg,rgba(255,255,255,0),#fff 24%)}.composer{width:min(860px,100%);margin:0 auto;border:1px solid #d9dde5;border-radius:15px;background:#fff;box-shadow:var(--shadow);transition:.16s}.composer:focus-within{border-color:#a8b6ff;box-shadow:0 12px 42px rgba(39,58,132,.12),0 0 0 3px rgba(77,107,254,.07)}
                    .composer textarea{display:block;width:100%;max-height:190px;min-height:55px;padding:14px 15px 7px;resize:none;border:0;outline:0;color:var(--text);background:transparent;font-size:14px;line-height:1.55}.composer textarea::placeholder{color:#a4acb8}.composer-foot{display:flex;align-items:center;gap:8px;padding:7px 8px 8px 13px}.composer-hint{color:var(--faint);font-size:10px}.composer-spacer{flex:1}.send-button{display:grid;place-items:center;width:34px;height:34px;border:0;border-radius:10px;color:#fff;background:var(--blue);cursor:pointer;transition:.16s}.send-button:hover{background:var(--blue-dark);transform:translateY(-1px)}.send-button:disabled{cursor:not-allowed;opacity:.45;transform:none}.send-button svg{width:17px;height:17px;fill:none;stroke:currentColor;stroke-width:2}

                    .inspector{width:0;overflow:hidden;border-left:0 solid var(--line);background:#fbfbfc;transition:width .2s ease}.inspector.open{width:360px;border-left-width:1px}.inspector-inner{display:flex;flex-direction:column;width:360px;height:100%}.inspector-head{display:flex;align-items:center;height:58px;padding:0 14px;border-bottom:1px solid var(--line);background:#fff}.inspector-title{font-size:12px;font-weight:700}.inspector-head .icon-button{margin-left:auto;width:30px;height:30px}.tabs{display:flex;gap:3px;padding:9px 10px;border-bottom:1px solid var(--line);background:#fff}.tab{padding:6px 8px;border:0;border-radius:6px;color:var(--muted);background:transparent;cursor:pointer;font-size:10px}.tab:hover{background:#f3f4f6}.tab.active{color:var(--blue-dark);background:var(--blue-soft);font-weight:700}.panel{display:none;flex:1;margin:0;padding:12px;overflow:auto;color:#515d70;background:#fbfbfc;font-family:ui-monospace,"Cascadia Code",Consolas,monospace;font-size:10.5px;line-height:1.62}.panel.active{display:block}
                    ::-webkit-scrollbar{width:9px;height:9px}::-webkit-scrollbar-thumb{border:2px solid transparent;border-radius:10px;background:#cfd4dc;background-clip:padding-box}

                    .jtree{font-family:ui-monospace,"Cascadia Code",Consolas,monospace;font-size:11.5px;line-height:1.7;white-space:nowrap}.jtree ul{list-style:none;margin:0;padding:0 0 0 16px;border-left:1px solid var(--line)}.jtree li{position:relative}.jtree .toggle{display:inline-block;width:14px;color:#aab2c0;cursor:pointer;user-select:none;font-family:inherit;transition:transform .12s}.jtree .toggle.collapsed{transform:rotate(-90deg)}.jtree .toggle.leaf{visibility:hidden}.jtree .key{color:#3152c8}.jtree .pun{color:#7c8594}.jtree .str{color:#0d7c3f;white-space:normal;overflow-wrap:anywhere}.jtree .str::before,.jtree .str::after{content:'"'}.jtree .num{color:#b3562b}.jtree .boo{color:#8a3ec4}.jtree .null{color:#8a3ec4;font-style:italic}.jtree .meta{color:#9aa3b2;font-style:italic}.jtree .kv{white-space:normal;overflow-wrap:anywhere}
                    @media(max-width:1050px){.app-shell{grid-template-columns:50px 218px minmax(0,1fr)}.inspector.open{position:absolute;z-index:20;top:0;right:0;height:100%;box-shadow:-18px 0 45px rgba(31,39,58,.12)}}
                    @media(max-width:720px){.app-shell{grid-template-columns:48px minmax(0,1fr)}.sidebar{display:none}.chat-inner{width:calc(100% - 28px)}.quick-grid{grid-template-columns:1fr}.topbar{padding:0 14px}.topbar-path{display:none}.composer-wrap{padding:10px 12px 14px}.inspector.open{width:calc(100% - 48px)}.inspector-inner{width:100%}}
                  </style>
                </head>
                <body>
                  <div class="app-shell">
                    <nav class="activity-bar" aria-label="主导航">
                      <div class="activity-logo">M</div>
                      <button class="icon-button active" title="对话" aria-label="对话"><svg viewBox="0 0 24 24"><path d="M5 5h14v10H9l-4 4V5Z"/></svg></button>
                      <button class="icon-button" id="open-inspector" title="检查器" aria-label="打开调试面板"><svg viewBox="0 0 24 24"><rect x="4" y="4" width="16" height="16" rx="2"/><path d="M9 4v16M9 9h11"/></svg></button>
                      <div class="activity-spacer"></div>
                      <button class="icon-button" title="本地 Agent" aria-label="本地 Agent"><svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="8"/><path d="M12 8v4l3 2"/></svg></button>
                    </nav>
                    <aside class="sidebar">
                      <div class="sidebar-head"><div class="brand-title">MyCoder</div><div class="brand-subtitle">Local coding agent</div></div>
                      <button class="new-chat" id="new-chat"><span>＋</span> 新建会话</button>
                      <div class="sidebar-scroll">
                        <div class="section-label">Workspace</div>
                        <div class="info-card"><div class="info-row"><span class="info-key">目录</span><span class="info-value" id="workdir">-</span></div><div class="info-row"><span class="info-key">模式</span><span class="info-value" id="mode-text">NORMAL</span></div></div>
                        <div class="section-label">Agent mode</div>
                        <select class="mode-select" id="mode-select" aria-label="Agent 模式"><option value="NORMAL">NORMAL · 常规</option><option value="PLAN">PLAN · 规划</option><option value="EXECUTE_PLAN">EXECUTE_PLAN · 执行</option></select>
                        <div class="section-label">Tools</div><div class="tool-list" id="tool-list"></div>
                      </div>
                    </aside>
                    <main class="workspace">
                      <section class="chat-column">
                        <header class="topbar"><span class="topbar-title">Agent session</span><span class="topbar-path" id="topbar-path">local workspace</span><div class="topbar-spacer"></div><span class="mode-pill" id="mode-badge">NORMAL</span><div class="run-state" id="run-state" data-testid="run-state"><span class="state-dot"></span><span id="run-state-text">就绪</span></div></header>
                        <div class="chat" id="chat"><div class="chat-inner" id="chat-inner"><section class="welcome" id="welcome"><div class="welcome-mark">M</div><h1>今天要改什么？</h1><p>读取项目、执行命令、修改代码。工具过程会留在对话里，Prompt 和请求快照放在右侧检查器。</p><div class="quick-grid"><div class="quick-card">定位一个具体 Bug，并做最小修改</div><div class="quick-card">先读相关代码，再实现新功能</div></div></section></div></div>
                        <div class="composer-wrap"><div class="composer"><textarea id="input" rows="1" placeholder="给 MyCoder 一个明确任务…" aria-label="给 MyCoder 发送指令"></textarea><div class="composer-foot"><span class="composer-hint">Enter 发送 · Shift + Enter 换行</span><div class="composer-spacer"></div><button class="send-button" id="send-btn" title="发送" aria-label="发送"><svg viewBox="0 0 24 24"><path d="m5 12 14-7-4 14-3-6-7-1Z"/><path d="m12 13 7-8"/></svg></button></div></div></div>
                      </section>
                      <aside class="inspector" id="inspector"><div class="inspector-inner"><div class="inspector-head"><span class="inspector-title">Context Inspector</span><button class="icon-button" id="close-inspector" title="关闭" aria-label="关闭调试面板">×</button></div><div class="tabs"><button class="tab active" data-panel="prompt">SYSTEM</button><button class="tab" data-panel="json">REQUEST</button><button class="tab" data-panel="history">HISTORY</button></div><pre class="panel active" id="panel-prompt"></pre><div class="panel" id="panel-json"></div><div class="panel" id="panel-history"></div></div></aside>
                    </main>
                  </div>
                  <script>
                    let running=false,currentAssistantEl=null,currentAssistantBuf='',currentEs=null,runFailed=false;
                    const $=id=>document.getElementById(id);
                    function escapeHtml(v){return String(v??'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;')}
                    function removeWelcome(){const w=$('welcome');if(w)w.remove()}
                    function scrollDown(){const c=$('chat');c.scrollTop=c.scrollHeight}
                    function setRunState(text,tone){$('run-state').className='run-state'+(tone?' '+tone:'');$('run-state-text').textContent=text}
                    function createMessage(role,content){
                      removeWelcome();const wrap=document.createElement('article');wrap.className='message '+role;
                      const head=document.createElement('div');head.className='message-head';const avatar=document.createElement('span');avatar.className='message-avatar';avatar.textContent=role==='user'?'YOU':'AI';const label=document.createElement('span');label.textContent=role==='user'?'你':'MyCoder';head.append(avatar,label);
                      const body=document.createElement('div');body.className='message-body';if(role==='user')body.textContent=content||'';wrap.append(head,body);$('chat-inner').appendChild(wrap);scrollDown();return body
                    }
                    function ensureAssistant(){const body=createMessage('assistant','');body.innerHTML='<span class="typing"><i></i><i></i><i></i></span>';return body}
                    function addToolCard(title,output,error){
                      removeWelcome();const card=document.createElement('details');card.className='tool-card'+(error?' error':'');card.open=true;const summary=document.createElement('summary');const kind=document.createElement('span');kind.className='tool-kind';kind.textContent=error?'ERROR':'TOOL';const name=document.createElement('span');name.textContent=title;summary.append(kind,name);const pre=document.createElement('pre');pre.className='tool-output';pre.textContent=output||'(无输出)';card.append(summary,pre);$('chat-inner').appendChild(card);scrollDown()
                    }
                    function renderInline(t){return escapeHtml(t).replace(/`([^`]+)`/g,'<code>$1</code>').replace(/\\*\\*([^*]+)\\*\\*/g,'<strong>$1</strong>')}
                    function renderMarkdown(source){
                      const lines=String(source||'').split('\\n');let html='',paragraph=[],list=[],inFence=false,language='',code=[];
                      const flushP=()=>{if(paragraph.length){html+='<p>'+paragraph.map(renderInline).join('<br>')+'</p>';paragraph=[]}};
                      const flushL=()=>{if(list.length){html+='<ul>'+list.map(i=>'<li>'+renderInline(i)+'</li>').join('')+'</ul>';list=[]}};
                      for(const line of lines){const fence=line.trim().match(/^```(.*)$/);if(fence){if(!inFence){flushP();flushL();inFence=true;language=fence[1].trim();code=[]}else{html+='<div class="code-block"><div class="code-head"><span>'+escapeHtml(language||'text')+'</span><span>CODE</span></div><pre><code>'+escapeHtml(code.join('\\n'))+'</code></pre></div>';inFence=false}continue}if(inFence){code.push(line);continue}const item=line.match(/^\\s*[-*]\\s+(.+)$/);if(item){flushP();list.push(item[1]);continue}if(!line.trim()){flushP();flushL();continue}paragraph.push(line)}
                      flushP();flushL();if(inFence)html+='<div class="code-block"><div class="code-head"><span>'+escapeHtml(language||'text')+'</span><span>CODE</span></div><pre><code>'+escapeHtml(code.join('\\n'))+'</code></pre></div>';return html
                    }
                    function formatArgs(args){if(args==null||args==='')return'';let v=args;if(typeof args==='string'){try{v=JSON.parse(args)}catch(_){return args}}try{return JSON.stringify(v,null,2)}catch(_){return String(v)}}
                    function truncate(v,max){const t=String(v||'');return t.length>max?t.slice(0,max)+'\\n… 已截断':t}
                    function el(tag,cls,text){const n=document.createElement(tag);if(cls)n.className=cls;if(text!=null)n.textContent=text;return n}
                    function primSpan(v){if(v===null)return el('span','null','null');const t=typeof v;if(t==='string')return el('span','str',v);if(t==='number')return el('span','num',String(v));if(t==='boolean')return el('span','boo',String(v));return el('span','str',String(v))}
                    function jNode(key,val){
                      const li=el('li','jnode');
                      if(val&&typeof val==='object'){
                        const isArr=Array.isArray(val);const head=el('span','kv');const toggle=el('span','toggle','▾');head.appendChild(toggle);
                        if(key!=null)head.appendChild(el('span','key',key));head.appendChild(el('span','pun',': '));head.appendChild(el('span','pun',isArr?'[':'{'));head.appendChild(el('span','meta',isArr?(' '+val.length+' 项'):(' '+Object.keys(val).length+' 键')));
                        const ul=el('ul');if(isArr){val.forEach(v=>ul.appendChild(jNode(null,v)))}else{Object.keys(val).forEach(k=>ul.appendChild(jNode(k,val[k])))}
                        const closing=el('span','pun',isArr?']':'}');const wrap=el('div','jc');wrap.appendChild(head);wrap.appendChild(ul);wrap.appendChild(closing);
                        toggle.addEventListener('click',()=>{toggle.classList.toggle('collapsed');ul.style.display=toggle.classList.contains('collapsed')?'none':'block'});li.appendChild(wrap);
                      } else {const head=el('span','kv');if(key!=null)head.appendChild(el('span','key',key));head.appendChild(el('span','pun',': '));head.appendChild(primSpan(val));li.appendChild(head)}
                      return li
                    }
                    function mountJsonTree(container,value){
                      container.innerHTML='';const root=el('div','jtree');container.appendChild(root);let obj=value;if(typeof value==='string'){try{obj=JSON.parse(value)}catch(_){obj=null}}
                      if(obj===null||obj===undefined){root.appendChild(el('span','meta','(暂无请求)'));return}
                      const ul=el('ul');ul.style.borderLeft='0';ul.style.paddingLeft='0';root.appendChild(ul);ul.appendChild(jNode(null,obj));
                    }
                    async function sendMessage(){
                      const input=$('input'),text=input.value.trim();if(!text||running)return;running=true;runFailed=false;input.value='';resizeInput();$('send-btn').disabled=true;setRunState('正在连接','running');createMessage('user',text);currentAssistantEl=ensureAssistant();currentAssistantBuf='';
                      try{const response=await fetch('/api/chat',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({message:text})});if(!response.ok)throw new Error('HTTP '+response.status);const payload=await response.json();if(!payload.runId)throw new Error('后端未返回 runId');openEventStream(payload.runId)}catch(error){addToolCard('提交失败',error.message,true);finishRun('提交失败','error')}
                    }
                    function openEventStream(runId){
                      currentEs=new EventSource('/api/event?runId='+encodeURIComponent(runId));currentEs.onopen=()=>setRunState('模型运行中','running');currentEs.onmessage=e=>{try{handleEvent(JSON.parse(e.data))}catch(_){}};
                      currentEs.addEventListener('done',()=>{closeEventStream();finishRun(runFailed?'运行失败':'已完成',runFailed?'error':'success')});
                      currentEs.onerror=()=>{if(!running)return;closeEventStream();addToolCard('连接中断','浏览器与 Agent 的事件流已断开。后端任务可能仍在运行，请查看历史记录。',true);finishRun('连接中断','error')}
                    }
                    function closeEventStream(){if(currentEs)currentEs.close();currentEs=null}
                    function handleEvent(data){
                      switch(data.type){
                        case'text':if(!currentAssistantEl){currentAssistantEl=ensureAssistant();currentAssistantBuf=''}currentAssistantBuf+=data.delta||'';currentAssistantEl.innerHTML=renderMarkdown(currentAssistantBuf);setRunState('正在回答','running');scrollDown();break;
                        case'tool_call':addToolCard('调用 '+data.name,formatArgs(data.args),false);currentAssistantEl=null;currentAssistantBuf='';setRunState('正在调用 '+data.name,'running');break;
                        case'tool_result':addToolCard(data.error==='true'?'工具执行失败':'工具执行完成',truncate(data.output,5000),data.error==='true');setRunState('等待模型继续','running');break;
                        case'turn_complete':setRunState('第 '+data.turn+' 轮完成','running');break;
                        case'error':runFailed=true;addToolCard('Agent 错误',data.message,true);setRunState('运行失败','error');break
                      }
                    }
                    function finishRun(text,tone){running=false;$('send-btn').disabled=false;currentAssistantEl=null;currentAssistantBuf='';setRunState(text||'就绪',tone||'');refreshState()}
                    async function refreshState(){
                      try{const response=await fetch('/api/state');const state=await response.json();$('workdir').textContent=state.workDir||'-';$('topbar-path').textContent=state.workDir||'local workspace';$('mode-text').textContent=state.mode||'NORMAL';$('mode-badge').textContent=state.mode||'NORMAL';$('mode-select').value=state.mode||'NORMAL';$('panel-prompt').textContent=state.systemPrompt||'(空)';mountJsonTree($('panel-json'),state.lastRequestJson);renderHistory($('panel-history'),state.history||[]);renderTools(state.tools||[])}catch(_){setRunState('状态读取失败','error')}
                    }
                    function renderHistory(container,h){container.innerHTML='';const root=el('div','jtree');container.appendChild(root);if(!h||!h.length){root.appendChild(el('span','meta','(空)'));return}const ul=el('ul');ul.style.borderLeft='0';ul.style.paddingLeft='0';root.appendChild(ul);h.forEach((item,idx)=>{const li=el('li','jnode');const head=el('span','kv');const toggle=el('span','toggle','▾');head.appendChild(toggle);head.appendChild(el('span','key','['+idx+']'));head.appendChild(el('span','pun',' '));head.appendChild(el('span','meta',item.role||''));head.appendChild(el('span','pun',' {'));const sub=el('ul');const content=item.content!=null&&item.content!=='';if(content){const c=el('li','jnode');c.appendChild(el('span','key','content'));c.appendChild(el('span','pun',': '));c.appendChild(el('span','str',String(item.content)));sub.appendChild(c)}if(item.toolCalls){const tc=el('li','jnode');const th=el('span','kv');const tt=el('span','toggle','▾');th.appendChild(tt);th.appendChild(el('span','key','toolCalls'));th.appendChild(el('span','pun',': '));th.appendChild(el('span','pun','['));const tul=el('ul');item.toolCalls.forEach(t=>tul.appendChild(jNode(null,t)));th.appendChild(tul);th.appendChild(el('span','pun',']'));tt.addEventListener('click',()=>{tt.classList.toggle('collapsed');tul.style.display=tt.classList.contains('collapsed')?'none':'block'});tc.appendChild(th);sub.appendChild(tc)}if(item.toolResults){const tr=el('li','jnode');const rh=el('span','kv');const rt=el('span','toggle','▾');rh.appendChild(rt);rh.appendChild(el('span','key','toolResults'));rh.appendChild(el('span','pun',': '));rh.appendChild(el('span','pun','['));const rul=el('ul');item.toolResults.forEach(r=>rul.appendChild(jNode(null,r)));rh.appendChild(rul);rh.appendChild(el('span','pun',']'));rt.addEventListener('click',()=>{rt.classList.toggle('collapsed');rul.style.display=rt.classList.contains('collapsed')?'none':'block'});tr.appendChild(rh);sub.appendChild(tr)}if(sub.children.length){head.appendChild(sub)}head.appendChild(el('span','pun','}'));toggle.addEventListener('click',()=>{toggle.classList.toggle('collapsed');sub.style.display=toggle.classList.contains('collapsed')?'none':'block'});li.appendChild(head);ul.appendChild(li)})}
                    function renderTools(tools){const list=$('tool-list');list.innerHTML='';if(!tools.length){const e=document.createElement('div');e.className='tool-chip';e.textContent='(无)';list.appendChild(e);return}tools.forEach(t=>{const e=document.createElement('div');e.className='tool-chip';e.textContent=t.name;e.title=t.description||'';list.appendChild(e)})}
                    function resizeInput(){const input=$('input');input.style.height='auto';input.style.height=Math.min(input.scrollHeight,190)+'px'}
                    function setInspector(open){$('inspector').classList.toggle('open',open);$('open-inspector').classList.toggle('active',open)}
                    $('send-btn').addEventListener('click',sendMessage);$('input').addEventListener('input',resizeInput);$('input').addEventListener('keydown',e=>{if(e.key==='Enter'&&!e.shiftKey){e.preventDefault();sendMessage()}});
                    $('mode-select').addEventListener('change',async e=>{const r=await fetch('/api/mode',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({mode:e.target.value})});if(r.ok)refreshState()});
                    $('new-chat').addEventListener('click',async()=>{if(running)return;const r=await fetch('/api/reset',{method:'POST'});if(!r.ok)return;$('chat-inner').innerHTML='<section class="welcome" id="welcome"><div class="welcome-mark">M</div><h1>新会话已准备好</h1><p>给我一个具体任务，我会展示每一步工具执行过程。</p></section>';setRunState('就绪','');refreshState()});
                    $('open-inspector').addEventListener('click',()=>setInspector(true));$('close-inspector').addEventListener('click',()=>setInspector(false));
                    document.querySelectorAll('.tab').forEach(tab=>tab.addEventListener('click',()=>{document.querySelectorAll('.tab').forEach(i=>i.classList.remove('active'));document.querySelectorAll('.panel').forEach(i=>i.classList.remove('active'));tab.classList.add('active');$('panel-'+tab.dataset.panel).classList.add('active')}));
                    refreshState();$('input').focus();
                  </script>
                </body>
                </html>
                """;
    }
}
