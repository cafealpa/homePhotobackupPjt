"use strict";
(() => {
  const el = id => document.getElementById(id);
  const panel=el("panel-monitoring");
  const state={data:null,selected:"INCOMING",loading:false,acting:false,stale:true,loadError:false};
  const labels={RUNNING:"실행 중",QUEUED:"작업 대기",WAITING_RESOURCE:"실행 차례 대기",IDLE:"할 일 없음 / 주기 대기",COMPLETED:"처리 완료",RETRY_WAIT:"재시도 대기",RESTARTING:"재시작 중",STOPPING:"중지 중",STOPPED:"중지 완료",NEEDS_ATTENTION:"확인 필요",DISABLED:"사용 안 함"};
  const levels={START:"시작",DONE:"완료",ERROR:"오류",RETRY:"재시도",STOP:"중지",RECOVER:"복구"};
  const num=n=>Number(n||0).toLocaleString("ko-KR");
  const time=t=>t?new Date(t).toLocaleString("ko-KR"):"—";
  const pending=p=>p.counts.pendingKnown===false?"미확정":num(p.counts.pending);
  function node(tag,cls,text){const n=document.createElement(tag);if(cls)n.className=cls;if(text!==undefined)n.textContent=text;return n;}
  function badge(p){const n=node("span","monitor-badge",labels[p.state]||p.state);n.dataset.state=p.state;return n;}
  function notice(text){el("monitor-notice").textContent=text;el("monitor-notice").classList.toggle("hidden",!text);}
  function progress(p){
    if(p.counts.total===null||p.counts.total===0)return node("p","card-sub",p.counts.total===null?"전체 개수 미확정 · 처리 건수 표시":"등록된 작업 없음");
    const value=Math.min(100,Math.max(0,p.counts.done/p.counts.total*100));
    const track=node("div","monitor-track");track.setAttribute("role","progressbar");track.setAttribute("aria-label",p.name+" 등록 작업 완료율");
    track.setAttribute("aria-valuemin","0");track.setAttribute("aria-valuemax","100");track.setAttribute("aria-valuenow",value.toFixed(1));
    const bar=node("span");bar.style.width=value+"%";track.append(bar);return track;
  }
  function button(text,action,p,allowed){const b=node("button","action-btn",text);b.type="button";b.dataset.action=action;b.dataset.id=p.id;b.disabled=!allowed||state.acting||state.stale;b.addEventListener("click",()=>act(p.id,action));return b;}
  function render(){
    const focused=document.activeElement;
    const focusKey=focused?.dataset?.action ? {action:focused.dataset.action,id:focused.dataset.id}:null;
    const data=state.data;
    el("monitor-start-all").disabled=state.stale||state.acting||!data||data.draining;
    el("monitor-stop-all").disabled=state.stale||state.acting||!data||data.draining;
    if(!data)return;
    const stats=[["실행 중",p=>p.state==="RUNNING"],["작업 / 실행 차례 / 재시도 대기",p=>["QUEUED","WAITING_RESOURCE","RETRY_WAIT"].includes(p.state)],["확인 필요",p=>p.state==="NEEDS_ATTENTION"||p.counts.failed>0||!!p.lastError],["중지 / 재시작",p=>["STOPPED","STOPPING","RESTARTING"].includes(p.state)]];
    el("monitor-summary").replaceChildren(...stats.map(([label,test])=>{const n=node("div","monitor-stat");n.append(node("span","",label),node("strong","",num(data.items.filter(test).length)+" 작업"));return n;}));
    const warnings=[];
    if(data.draining)warnings.push("서버 종료 준비 중 · 실행 중인 작업의 정리를 기다리고 있어요.");
    if(data.historyError)warnings.push(data.historyError);
    const original=data.items.find(p=>p.id==="INCOMING");
    if(original?.lastError)warnings.push(`원본 저장 확인 필요 · 미완료 ${num(original.counts.pending+original.counts.running+original.counts.failed)}개\n${original.lastError}`);
    el("monitor-warning").textContent=warnings.join("\n");el("monitor-warning").classList.toggle("hidden",warnings.length===0);
    el("monitor-time").textContent=(state.stale?"이전 조회 · ":"갱신 ")+new Date(data.at).toLocaleTimeString("ko-KR");
    el("monitor-list").replaceChildren(...data.items.map(p=>{
      const row=node("button","monitor-row");row.type="button";row.dataset.action="select";row.dataset.id=p.id;row.setAttribute("aria-pressed",String(state.selected===p.id));
      const title=node("div","monitor-row-title");title.append(node("span","",p.name),badge(p));
      const sub=node("div","monitor-row-sub");sub.append(node("span","",`완료 ${num(p.counts.done)}${p.counts.total!==null?" / "+num(p.counts.total):""}`),node("span","",`대기 ${pending(p)} · 처리 중 ${num(p.counts.running)} · 실패 ${num(p.counts.failed)}`));
      row.append(title,sub,progress(p));row.addEventListener("click",()=>{state.selected=p.id;render();});return row;
    }));
    const p=data.items.find(p=>p.id===state.selected)||data.items[0];if(!p)return;
    const detail=el("monitor-detail"),head=node("div","monitor-pane-head");head.append(node("h3","",p.name),badge(p));
    const body=node("div","monitor-body"),actions=node("div","monitor-actions");
    actions.append(button("시작","start",p,p.canStart),button("중지","stop",p,p.canStop),button("재시작","restart",p,p.canRestart));
    if(p.canRetry)actions.append(button("실패·대기 재시도","retry",p,true));
    body.append(actions,node("p","",`완료 ${num(p.counts.done)}${p.counts.total!==null?" / "+num(p.counts.total):""} · 대기 ${pending(p)} · 처리 중 ${num(p.counts.running)} · 실패 ${num(p.counts.failed)}`),progress(p));
    if(p.waitingReason)body.append(node("p","card-sub",p.waitingReason));
    const kv=node("dl","monitor-kv");
    const next=p.retryAt?`${time(p.retryAt)}${p.retryAt>Date.now()?" · "+Math.ceil((p.retryAt-Date.now())/1000)+"초 후":" · 실행 대기"}`:"—";
    const rows=[["활성 워커",`${p.current.length}개 · 실행 차례 대기 포함`],["다음 자동 재시도",next],["마지막 성공",time(p.lastSuccessAt)]];
    if(p.location)rows.push(["원본 저장 경로",p.location]);
    for(const [k,v]of rows){const n=node("div");n.append(node("dt","",k),node("dd","",v));kv.append(n);}body.append(kv);
    for(const current of p.current){const n=node("div","monitor-current",current.item||"준비 중");n.append(node("small","",`${current.waitingFor||current.stage} · 현재 단계 ${Math.max(0,Math.floor((Date.now()-current.stageAt)/1000))}초`));body.append(n);}
    if(p.state==="STOPPING")body.append(node("p","monitor-warning","중지 요청을 전달했어요. 파일·외부 호출이 정리될 때까지 중지 중으로 표시돼요."));
    if(p.lastError)body.append(node("p","monitor-warning",p.lastError));
    if(p.note)body.append(node("p","card-sub monitor-footnote",p.note));
    const logHead=node("div","monitor-pane-head");logHead.append(node("h3","","최근 진행 로그"),node("span","card-sub","서버에 최근 기록 보관"));
    const logs=node("div","monitor-logs");
    if(!p.logs.length)logs.append(node("p","monitor-empty","아직 기록된 작업 이벤트가 없어요."));
    for(const e of p.logs){const line=node("div","monitor-log");const level=node("span","monitor-log-level",levels[e.level]||e.level);level.dataset.level=e.level;const stamp=node("time","",new Date(e.at).toLocaleTimeString("en-GB",{hour12:false}));stamp.dateTime=new Date(e.at).toISOString();stamp.title=time(e.at);line.append(stamp,level,node("span","",e.message+(e.count>1?` · ${e.count}회 반복`:"")));logs.append(line);}
    detail.replaceChildren(head,body,logHead,logs);
    if(focusKey){const next=[...panel.querySelectorAll("button[data-action]")].find(b=>b.dataset.action===focusKey.action&&b.dataset.id===focusKey.id);if(next&&!next.disabled)next.focus({preventScroll:true});}
  }
  async function refresh(){
    if(state.loading||state.acting||panel.classList.contains("hidden"))return;
    state.loading=true;
    try{state.data=await api("/api/v1/admin/processes");state.stale=false;if(state.loadError){notice("");state.loadError=false;}render();}
    catch(e){state.stale=true;state.loadError=true;notice("최신 상태 조회 실패 · 이전 수치는 최신 상태가 아니에요. 새로고침 후 제어할 수 있어요.");render();}
    finally{state.loading=false;}
  }
  async function act(id,action){
    if(state.acting||state.stale)return;
    state.acting=true;render();
    try{
      const response=await fetch(`/api/v1/admin/processes/${encodeURIComponent(id)}/${action}`,{method:"POST",credentials:"same-origin",headers:{"X-HomePhoto-Action":"process-control"},signal:AbortSignal.timeout(15000)});
      const result=await response.json();
      if(!response.ok)throw new Error(result.error||`HTTP ${response.status}`);
      notice(result.accepted===false?"일부 시작 요청 실패: "+Object.values(result.errors||{}).join(" / "):({start:"시작 요청을 전달했어요.",stop:"중지 요청을 전달했어요. 완료된 작업과 수신 파일은 보존돼요.",restart:"안전하게 중지한 뒤 남은 작업부터 재개해요.",retry:`재시도 요청을 전달했어요${result.updated!==undefined?" · "+result.updated+"개":""}.`})[action]);
    }catch(e){notice("제어 요청 결과를 확인하지 못했어요: "+e.message+". 최신 상태를 확인해 주세요.");}
    finally{state.acting=false;await refresh();}
  }
  el("monitor-start-all").addEventListener("click",()=>act("all","start"));
  el("monitor-stop-all").addEventListener("click",()=>act("all","stop"));
  globalThis.HomePhotoMonitoring={refresh};
  setInterval(()=>{if(!document.hidden&&el("auto-refresh").checked)refresh();},5000);
  if(location.hash==="#monitoring")switchPanel("monitoring");
})();
