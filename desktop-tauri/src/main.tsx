import { createRoot } from 'react-dom/client';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { invoke, isTauri } from '@tauri-apps/api/core';
import { getCurrentWindow } from '@tauri-apps/api/window';
import { applyAccepted, newDraft, parseReview, placeIssues, SaveQueue, wordCount, type Draft, type Workspace } from './domain';
import './styles.css';

type Mode = 'write' | 'review';
function Glyph({name}: {name:'book'|'plus'|'write'|'check'|'arrow'|'close'}) {
  const paths={book:'M4 4h6c2 0 2 2 2 2s0-2 2-2h6v16h-6c-2 0-2 1-2 1s0-1-2-1H4Z M12 6v15',plus:'M12 5v14M5 12h14',write:'m5 15-1 5 5-1L20 8l-4-4Z M14 6l4 4',check:'m5 12 4 4L19 6',arrow:'M5 12h14m-6-6 6 6-6 6',close:'m6 6 12 12M6 18 18 6'};
  return <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d={paths[name]}/></svg>;
}
const time = (value: number) => new Intl.DateTimeFormat('zh-CN',{month:'numeric',day:'numeric',hour:'2-digit',minute:'2-digit'}).format(value);
function App() {
  const [workspace,setWorkspace]=useState<Workspace|null>(null); const current=useRef<Workspace|null>(null); const queue=useRef<SaveQueue|null>(null);
  const [loadingError,setLoadingError]=useState<string|null>(null); const [saving,setSaving]=useState(false); const [saveError,setSaveError]=useState<string|null>(null);
  const [mode,setMode]=useState<Mode>('write'); const [search,setSearch]=useState(''); const [importing,setImporting]=useState(false); const [raw,setRaw]=useState('');
  const [selected,setSelected]=useState<number|null>(null); const [closeError,setCloseError]=useState(false); const closing=useRef(false);
  useEffect(()=>{
    if (!isTauri()) {setLoadingError('请运行 Windows 桌面程序打开工作台。');return;}
    let live=true;
    invoke<Workspace>('load_workspace').then(saved=>{if(!live)return;current.current=saved;queue.current=new SaveQueue(saved.revision,s=>invoke<Workspace>('save_workspace',{workspace:s}),(pending,error)=>{setSaving(pending);setSaveError(error);});setWorkspace(saved);}).catch(error=>{if(live)setLoadingError(String(error));});
    return ()=>{live=false;};
  },[]);
  useEffect(()=>{
    if (!isTauri()) return;
    let disposed=false; let unlisten:(()=>void)|undefined;
    void getCurrentWindow().onCloseRequested(async event=>{
      if(closing.current)return;
      event.preventDefault();
      try {await queue.current?.flush();closing.current=true;await getCurrentWindow().close();} catch {setCloseError(true);}
    }).then(remove=>{if(disposed)remove();else unlisten=remove;});
    const shortcut=(event:KeyboardEvent)=>{if((event.ctrlKey||event.metaKey)&&event.key.toLowerCase()==='s'){event.preventDefault();if(current.current)void queue.current?.enqueue(current.current).catch(()=>undefined);}};
    window.addEventListener('keydown',shortcut);
    return ()=>{disposed=true;unlisten?.();window.removeEventListener('keydown',shortcut);};
  },[]);
  function change(transform:(state:Workspace)=>Workspace) {
    if(!current.current)return;
    const next=transform(current.current);current.current=next;setWorkspace(next);void queue.current?.enqueue(next).catch(()=>undefined);
  }
  const draft=workspace?.drafts.find(d=>d.id===workspace.activeId)??null;
  function edit(patch:Partial<Draft>) {if(!draft)return;change(state=>({...state,drafts:state.drafts.map(d=>d.id===draft.id?{...d,...patch,updatedAt:Date.now()}:d)}));}
  function add(parent:Draft|null=null,text?:string) {const created=newDraft(parent);if(text!==undefined)created.text=text;change(state=>({...state,activeId:created.id,drafts:[created,...state.drafts]}));setMode('write');setSelected(null);}
  let review=null; let reviewError:string|null=null;
  if(draft?.reviewText)try {review=parseReview(draft.reviewText);} catch(error){reviewError=error instanceof Error?error.message:String(error);}
  const placements=draft&&review?placeIssues(draft.reviewSource,review.issues):[]; const sourceMatches=draft?.text===draft?.reviewSource;
  function focusIssue(id:number) {setSelected(id);document.getElementById(`issue-${id}`)?.scrollIntoView({block:'nearest',behavior:'smooth'});}
  function markedText():ReactNode[] {
    if(!draft)return[];
    const nodes:ReactNode[]=[];let at=0;
    for(const p of placements.filter(p=>p.start!==null).sort((a,b)=>a.start!-b.start!)){
      nodes.push(draft.reviewSource.slice(at,p.start!));
      nodes.push(<mark key={p.issue.id} className={`${p.issue.must?'must':'better'} ${selected===p.issue.id?'focused':''}`} tabIndex={0} role="button" aria-label={`批注 ${p.issue.id+1}：${p.issue.reason||p.issue.category}`} onClick={()=>focusIssue(p.issue.id)} onKeyDown={event=>{if(event.key==='Enter'||event.key===' '){event.preventDefault();focusIssue(p.issue.id);}}}>{draft.reviewSource.slice(p.start!,p.end!)}<sup>{p.issue.id+1}</sup></mark>);at=p.end!;
    }
    nodes.push(draft.reviewSource.slice(at));return nodes;
  }
  if(loadingError)return <main className="startup"><div className="brand-square"><Glyph name="book"/></div><h1>写作工作台暂时无法打开</h1><p role="alert">{loadingError}</p><p>原有学习应用和资料保持独立。请检查原型数据目录，保留文件后再重试。</p></main>;
  if(!workspace)return <main className="startup"><div className="brand-square"><Glyph name="book"/></div><h1>正在打开本机草稿</h1></main>;
  const listed=workspace.drafts.filter(d=>`${d.title} ${d.text}`.toLowerCase().includes(search.toLowerCase()));
  return <div className="app-shell">
    <aside className="rail"><div className="brand"><div className="brand-square"><Glyph name="book"/></div><span>朱批</span><small>写作工作台</small></div><div className="rail-caption">我的书桌</div><div className="rail-active"><Glyph name="write"/><span>写作</span><span className="count">{workspace.drafts.length}</span></div><button className="new-draft" onClick={()=>add()} disabled={workspace.drafts.length>=100}><Glyph name="plus"/>新建作文</button><label className="search-label"><span>草稿与修改稿</span><input aria-label="搜索草稿" placeholder="搜索标题或正文" value={search} onChange={event=>setSearch(event.target.value)}/></label><div className="draft-list">{listed.length===0?<p className="library-empty">{search?'没有找到相关草稿':'从一篇作文开始。你的文字会保存在这台电脑。'}</p>:listed.map(item=><button key={item.id} className={`draft-row ${item.id===draft?.id?'active':''}`} onClick={()=>{change(state=>({...state,activeId:item.id}));setSelected(null);}}><span className="draft-kind">{item.parentId?'修改稿':'作文草稿'}<span>{wordCount(item.text)} 词</span></span><strong>{item.title||'未命名作文'}</strong><span className="draft-preview">{item.text||'还没有开始写作'}</span><span className="draft-date">{time(item.updatedAt)}</span></button>)}</div><div className="rail-footer"><span className="local-dot"/>独立桌面原型 · 仅本地保存</div></aside>
    <div className="workbench"><header className="topbar"><div className="breadcrumb">我的书桌 <span>/</span> 写作 <span>/</span> <strong>{draft?.title||'新的篇章'}</strong></div><div className={`save-status ${saveError?'error':''}`} role="status"><span className="local-dot"/>{saveError?'保存失败，当前编辑仍在窗口中':saving?'正在保存…':'已保存到这台电脑'}</div><button className="quiet-button" disabled={!draft} onClick={()=>{if(current.current)void queue.current?.enqueue(current.current).catch(()=>undefined);}}>保存 <kbd>Ctrl S</kbd></button></header>
      {saveError&&<div className="error-banner" role="alert">{saveError}<button onClick={()=>{if(current.current)void queue.current?.enqueue(current.current).catch(()=>undefined);}}>重试保存</button></div>}
      {!draft?<main className="welcome"><span className="eyebrow">A PLACE TO THINK, THEN WRITE</span><h1>让想法，落在纸上。</h1><p>从题目到初稿，再到逐处修改。<br/>保留每一版，慢慢写得更清楚。</p><button className="primary-button" onClick={()=>add()}><Glyph name="plus"/>开始一篇作文</button><span className="welcome-note">草稿自动保存在本机 · 无需登录</span></main>:<>
      <div className="workspace-toolbar"><div className="phase-tabs" role="tablist" aria-label="工作台模式"><button role="tab" aria-selected={mode==='write'} className={mode==='write'?'selected':''} onClick={()=>setMode('write')}><span>01</span> 写作</button><button role="tab" aria-selected={mode==='review'} className={mode==='review'?'selected':''} onClick={()=>setMode('review')}><span>02</span> 批注 {review&&<b>{review.issues.length}</b>}</button></div><div className="toolbar-actions"><span className="word-total">{wordCount(draft.text)} <small>词</small></span><button className="outlined-button" onClick={()=>{setRaw(draft.reviewText);setImporting(true);}}>导入已有批改</button></div></div>
      <div className="document-grid"><main className="document-scroll"><article className="manuscript"><div className="document-meta"><span>WRITING / 作文</span><span>{draft.parentId?'修改稿 · 原稿保留':'独立草稿'}</span></div><input className="document-title" aria-label="作文标题" placeholder="给这篇作文起个名字" value={draft.title} maxLength={1000} onChange={event=>edit({title:event.target.value})}/><details className="prompt" open={draft.text.length===0}><summary>题目与写作要求 <span>可选</span></summary><textarea aria-label="题目与写作要求" placeholder="在这里放入题目、给定句或你想讨论的问题…" value={draft.prompt} maxLength={60000} onChange={event=>edit({prompt:event.target.value})}/></details><div className="paper-rule"/>{mode==='write'?<textarea className="essay-editor" aria-label="作文正文" spellCheck lang="en" placeholder="Write your first sentence here…" value={draft.text} maxLength={30000} onChange={event=>edit({text:event.target.value})}/>:<><div className="snapshot-label">批注对应导入时的原稿{!sourceMatches&&<strong>当前草稿已修改</strong>}</div><div className="annotated-essay">{draft.reviewText?markedText():draft.text||'写下第一稿，再导入已有批改。'}</div></>}<footer className="paper-footer"><span>{draft.text.length.toLocaleString()} 字符</span><span>每一版，都值得留下。</span></footer></article></main>
      <aside className="review-panel"><div className="panel-heading"><span className="eyebrow">REVIEW & REFINE</span><h2>{mode==='write'?'写作手边':'逐处批注'}</h2></div>{mode==='write'?<><div className="writing-tip"><span className="note-number">01</span><h3>先把观点说清楚</h3><p>写下你的立场，再用一个具体例子展开。初稿不用一次写完美。</p></div><div className="writing-tip"><span className="note-number">02</span><h3>修改时，保留上一版</h3><p>导入已有批改后，逐条决定是否采纳。生成的修改稿会另存，原稿继续保留。</p></div><div className="small-note">这个原型不发送 AI 请求。可离线导入现有写作批改的 JSONL 回复，体验批注和改稿。</div></>:<>
      {!draft.reviewText?<div className="panel-empty"><div className="empty-glyph"><Glyph name="write"/></div><h3>把修改落到原句上</h3><p>导入一份已有批改，原文中能找到的引用会与右侧批注对应。</p><button className="outlined-button" onClick={()=>{setRaw('');setImporting(true);}}>导入批改 JSONL</button></div>:reviewError?<div className="panel-empty"><h3>已保留批改原文</h3><p role="alert">{reviewError}</p><button className="outlined-button" onClick={()=>{setRaw(draft.reviewText);setImporting(true);}}>查看原文并修改</button></div>:review&&<><div className="review-summary"><div><strong>{review.issues.filter(i=>i.must).length}</strong><span>需修改</span></div><div><strong>{review.issues.filter(i=>!i.must).length}</strong><span>可润色</span></div><div><strong>{draft.accepted.length}</strong><span>已采纳</span></div></div>{review.band!==null&&<p className="band-note">AI 模拟档位 {review.band} / 15 · 仅供参考<br/>{review.summary}</p>}{placements.map(p=><section id={`issue-${p.issue.id}`} key={p.issue.id} className={`issue-card ${p.issue.must?'must':'better'} ${selected===p.issue.id?'focused':''}`}><button className="issue-heading" onClick={()=>focusIssue(p.issue.id)}><span className="issue-number">{p.issue.id+1}</span><span>{p.issue.category}</span><small>{p.issue.must?'需修改':'可润色'}</small></button><div className="correction"><del>{p.issue.quote||'缺少引用'}</del><strong>{p.issue.fix||'删去'}</strong></div>{p.issue.reason&&<p>{p.issue.reason}</p>}{p.start===null&&<p className="unlocated">原稿未定位，不会自动应用。</p>}<div className="issue-actions"><button disabled={p.start===null} className={draft.accepted.includes(p.issue.id)?'accepted':''} onClick={()=>edit({accepted:draft.accepted.includes(p.issue.id)?draft.accepted.filter(id=>id!==p.issue.id):[...draft.accepted,p.issue.id],dismissed:draft.dismissed.filter(id=>id!==p.issue.id)})}><Glyph name="check"/>{draft.accepted.includes(p.issue.id)?'已采纳':'采纳'}</button><button className={draft.dismissed.includes(p.issue.id)?'dismissed':''} onClick={()=>edit({dismissed:draft.dismissed.includes(p.issue.id)?draft.dismissed.filter(id=>id!==p.issue.id):[...draft.dismissed,p.issue.id],accepted:draft.accepted.filter(id=>id!==p.issue.id)})}>{draft.dismissed.includes(p.issue.id)?'已忽略':'忽略'}</button></div></section>)}<div className="revision-action"><button className="primary-button" disabled={!sourceMatches||draft.accepted.length===0} onClick={()=>add(draft,applyAccepted(draft.reviewSource,placements,draft.accepted))}>生成修改稿 <Glyph name="arrow"/></button><p>{sourceMatches?'按已采纳批注另存一版，保留原稿。':'当前草稿与批注原稿不同，请重新导入后再生成。'}</p></div></>}
      </>}</aside></div></>}
      <footer className="statusbar"><span><span className="local-dot"/>本机草稿</span><span>Windows 桌面原型 <i>·</i> 不自动上传 <i>·</i> 与现有学习库独立</span></footer>
    </div>
    {importing&&<div className="modal-backdrop"><section className="import-dialog" role="dialog" aria-modal="true" aria-labelledby="import-title"><header><div><span className="eyebrow">SAVED FEEDBACK</span><h2 id="import-title">导入已有写作批改</h2></div><button className="icon-button" aria-label="关闭导入" onClick={()=>setImporting(false)}><Glyph name="close"/></button></header><p>粘贴已有批改的完整 JSONL。只使用原回复中的批注，原文未定位的修改不会自动应用。</p><textarea aria-label="批改 JSONL 原文" placeholder={'{"type":"issue","quote":"原句引用","fix":"建议写法","reason":"修改原因","category":"语法","level":"must"}'} value={raw} maxLength={200000} onChange={event=>setRaw(event.target.value)}/><footer><span>纯本地解析 · 不发送 AI 请求</span><button className="primary-button" disabled={!raw.trim()} onClick={()=>{edit({reviewText:raw,reviewSource:draft?.text??'',accepted:[],dismissed:[]});setMode('review');setSelected(null);setImporting(false);}}>保存并查看批注 <Glyph name="arrow"/></button></footer></section></div>}
    {closeError&&<div className="modal-backdrop"><section className="close-dialog" role="alertdialog" aria-labelledby="close-title"><h2 id="close-title">仍有内容未保存</h2><p>关闭会丢失窗口中尚未成功保存的编辑，旧草稿文件仍保留。</p><button className="outlined-button" onClick={()=>setCloseError(false)}>返回继续编辑</button><button className="danger-button" onClick={()=>{closing.current=true;void getCurrentWindow().close();}}>放弃未保存内容并关闭</button></section></div>}
  </div>;
}
createRoot(document.getElementById('root')!).render(<App/>);
