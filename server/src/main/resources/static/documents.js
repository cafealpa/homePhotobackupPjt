"use strict";
const $=id=>document.getElementById(id);
let page=0, query="", searchType="", dateFrom="", dateTo="", filter="documents", generation=0, loading=false, editing=false, enabled=false, timer, detailId;
let signature="";
const types={RECEIPT:"영수증",NOTICE:"안내문",CONTRACT:"계약서",CERTIFICATE:"증명서",MEDICAL:"의료 서류",EDUCATION:"학습 자료",OTHER:"기타 문서"};
const jobs={PENDING:"대기",RUNNING:"분석 중",FAILED:"실패",DONE:"분석 완료",NONE:"미등록"};
async function api(path, options={}) {
 const r=await fetch("/api/v1/"+path,{credentials:"same-origin",cache:"no-store",...options});
 if(r.status===401){if(!$("login").open) $("login").showModal();throw new Error("로그인이 필요합니다.");}
 const data=await r.json();if(!r.ok) throw new Error(data.error||data.message||`HTTP ${r.status}`);return data;
}
const post=(path,body)=>api(path,{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify(body)});
function node(tag,cls,text){const n=document.createElement(tag);n.className=cls;n.textContent=text??"";return n;}
async function action(fn){if(editing)return;editing=true;document.querySelectorAll(".controls button").forEach(b=>b.disabled=true);
 try{$("message").textContent=await fn();}catch(e){$("message").textContent=e.message;}
 finally{editing=false;document.querySelectorAll(".controls button").forEach(b=>b.disabled=false);refresh();}}
function render(data){
 $("result-count").textContent=`${data.candidateLimited?"검색 후보":"결과"} · ${data.total.toLocaleString()}장`;
 $("search-note").textContent=query?(data.semanticState==="available"?"단어 검색과 의미 검색 순위를 합쳤어요. 단어 검색 최대 200장·의미 검색 최대 200개 구간의 후보 범위이며 전체 일치 건수는 아닙니다.":"의미 검색 준비 중으로 단어 검색 후보를 표시합니다. 최대 200개 후보입니다."):"";
 $("empty").hidden=data.items.length>0;$("previous").disabled=page===0;$("next").disabled=(page+1)*40>=data.total;$("page").textContent=`${page+1} / ${Math.max(1,Math.ceil(data.total/40))}`;
 const nextSignature=JSON.stringify(data.items);if(signature===nextSignature)return;signature=nextSignature;
 const frag=document.createDocumentFragment();
 for(const item of data.items){const card=node("article","card"),img=document.createElement("img"),body=node("div","card-body");
 img.src=`/api/v1/assets/${item.assetId}/thumb?size=400`;img.alt=item.filename;img.loading="lazy";
 const label=item.classification==="NOT_DOCUMENT"?"일반 사진":item.classification==="UNKNOWN"?"문서 판별 전":types[item.type];
 body.append(node("div","badge",`${label} · ${jobs[item.status]}`),node("div","filename",item.filename),node("p","caption",item.title||item.summary||"분석 결과를 기다리고 있어요."));
 if(item.needsReview||item.classification==="UNCERTAIN")body.append(node("p","error",item.reviewReason||"문서 판독 확인이 필요해요."));
 if(item.error)body.append(node("p","error",item.error));
 if(item.analyzedAt&&item.classification!=="NOT_DOCUMENT")body.append(node("p","meta",item.indexedRevision===item.revision?`검색 반영 완료 · ${item.chunks}개 구간`:"단어 검색 가능 · 의미 검색 반영 대기"));
 body.append(node("p","meta",[item.date,item.issuer,item.analyzedAt?.replace("T"," ")].filter(Boolean).join(" · ")));
 const open=node("button","secondary","내용 보기 / 분석");open.addEventListener("click",()=>showDetail(item.assetId));body.append(open);card.append(img,body);frag.append(card);}
 $("results").replaceChildren(frag);
}
async function refresh(){clearTimeout(timer);if(loading||document.hidden||$("login").open)return;loading=true;const current=generation;
 try{const [s,data]=await Promise.all([api("admin/documents/status"),api(`admin/documents?filter=${filter}&q=${encodeURIComponent(query)}&page=${page}&type=${encodeURIComponent(searchType)}&from=${dateFrom}&to=${dateTo}`)]);
 enabled=s.worker.enabled;for(const key of ["unknown","documents","pending","running","failed","review","indexPending","indexed"])$(key).textContent=s.counts[key].toLocaleString();
 $("toggle").textContent=enabled?"문서 분석 일시정지":"문서 분석 시작";$("toggle").disabled=editing;
 const w=s.worker;$("worker-state").textContent=`Gemini · ${w.model} — ${w.currentAssetId?`사진 #${w.currentAssetId} 분석 중`:w.running?"연결·작업 확인 중":!enabled?"일시정지":w.error?"연결 대기":s.counts.pending?"다음 사진 대기":"대기열 처리 완료"}`;
 $("worker-error").textContent=[w.error,w.retryAt?`재시도: ${new Date(w.retryAt).toLocaleTimeString()}`:null].filter(Boolean).join(" · ");
 const state={ready:"준비됨",indexing:"인덱싱 중",starting:"준비 중",disabled:"꺼짐",model_missing:"모델 파일 필요",unavailable:"검색 준비 상태 확인 필요"};
 $("index-state").textContent=`의미 검색: ${state[s.index.state]||s.index.state} · 문서 ${s.index.chunks.toLocaleString()}개 구간 · 기존 E5 모델 사용`;
 $("connection").textContent="3초마다 진행 상황 반영";$("updated").textContent=new Date().toLocaleTimeString()+" 갱신";if(current===generation)render(data);
 }catch(e){$("connection").textContent="연결 확인 필요";$("message").textContent=e.message;}
 finally{loading=false;if(!$("login").open)timer=setTimeout(refresh,current===generation?3000:0);}}
async function showDetail(id){detailId=id;$("detail-title").textContent="문서 불러오는 중";$("detail-ocr").textContent="";$("detail-meta").textContent="";$("detail-review").textContent="";$("detail-message").textContent="";$("detail-image").removeAttribute("src");$("original-link").removeAttribute("href");$("reanalyze").disabled=true;if(!$("detail").open)$("detail").showModal();
 try{const d=await api(`admin/documents/${id}`);if(detailId!==id)return;$("detail-title").textContent=d.item.title||d.item.filename;$("detail-image").src=`/api/v1/assets/${id}/thumb?size=1600`;$("original-link").href=`/api/v1/assets/${id}/file`;$("detail-ocr").textContent=d.ocrText||"아직 OCR 내용이 없어요.";$("detail-meta").textContent=[d.item.date,d.item.issuer,d.model].filter(Boolean).join(" · ");$("detail-review").textContent=d.item.reviewReason;$("reanalyze").disabled=["PENDING","RUNNING"].includes(d.item.status);}
 catch(e){$("detail-message").textContent=e.message;}}
$("close-detail").onclick=()=>{detailId=null;$("detail").close();};
$("reanalyze").onclick=async()=>{const id=detailId;$("reanalyze").disabled=true;try{const r=await post("admin/documents/enqueue",{mode:"reanalyze",assetId:id,limit:1});$("detail-message").textContent=r.queued?"대기열에 등록했어요. 문서 분석을 켜면 처리합니다.":"이미 처리 중이거나 등록할 수 없는 사진입니다.";refresh();}catch(e){$("detail-message").textContent=e.message;$("reanalyze").disabled=false;}};
$("toggle").onclick=()=>action(async()=>{await post("admin/documents/control",{enabled:!enabled});return enabled?"진행 중인 사진까지 처리한 뒤 멈춥니다.":"문서 분석을 시작했어요.";});
$("retry").onclick=()=>action(async()=>{const r=await post("admin/documents/enqueue",{mode:"failed",limit:100});return `${r.queued}장을 다시 등록했어요.`;});
$("enqueue-form").onsubmit=e=>{e.preventDefault();action(async()=>{const r=await post("admin/documents/enqueue",{mode:"missing",limit:Number($("batch").value)});return `${r.queued}장을 등록했어요.`;});};
function changePage(next){page=next;generation++;refresh();}
$("search-form").onsubmit=e=>{e.preventDefault();query=$("query").value.trim();searchType=$("document-type").value;dateFrom=$("date-from").value;dateTo=$("date-to").value;if(query||searchType||dateFrom||dateTo){filter="documents";$("filter").value=filter;}changePage(0);};
$("filter").onchange=()=>{filter=$("filter").value;query="";searchType="";dateFrom="";dateTo="";$("document-type").value="";$("date-from").value="";$("date-to").value="";$("query").value="";changePage(0);};
$("previous").onclick=()=>changePage(Math.max(0,page-1));$("next").onclick=()=>changePage(page+1);
$("login-form").onsubmit=async e=>{e.preventDefault();try{await post("auth/login",{key:$("login-key").value.trim()});$("login-key").value="";$("login-error").textContent="";$("message").textContent="";$("login").close();refresh();}catch(error){$("login-error").textContent=error.message;}};
$("login").addEventListener("cancel",e=>e.preventDefault());document.addEventListener("visibilitychange",()=>{if(!document.hidden)refresh();else clearTimeout(timer);});refresh();
