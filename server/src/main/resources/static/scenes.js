"use strict";
const $ = id => document.getElementById(id);
let page = 0, query = "", filter = "all", enabled = false, timer, loading = false, editing = false;
let signature = "", generation = 0, clusterId = "", peopleSignature = "";
async function api(path, options = {}) {
  const response = await fetch(path, {credentials: "same-origin", cache: "no-store", ...options});
  if (response.status === 401) {
    if (!$("login").open) $("login").showModal();
    throw new Error("로그인이 필요합니다.");
  }
  const text = await response.text();
  let data;
  try { data = text ? JSON.parse(text) : {}; } catch { throw new Error(`HTTP ${response.status}`); }
  if (!response.ok) throw new Error(data.error || `HTTP ${response.status}`);
  return data;
}
const post = (path, body) => api(path, {method:"POST", headers:{"Content-Type":"application/json"}, body:JSON.stringify(body)});
const saveSettings = body => api("/api/v1/admin/settings", {method:"PUT", headers:{"Content-Type":"application/json"}, body:JSON.stringify(body)});
function node(tag, className, text) { const n = document.createElement(tag); n.className = className; if (text != null) n.textContent = text; return n; }
const statusNames = {PENDING:"대기",RUNNING:"분석 중",DONE:"완료",FAILED:"실패",NONE:"큐 등록 전"};
function render(data) {
  const textAvailable = data.textSearchState === "available";
  const imageAvailable = data.imageSearchState === "available";
  let searchNote = "의미 검색을 준비 중이거나 사용할 수 없어 단어 검색 결과를 표시합니다.";
  if (textAvailable && imageAvailable) searchNote = "장면 설명과 이미지 검색을 함께 사용해요. 설명 검색에 더 비중을 두고 중복 사진을 합쳤어요.";
  else if (textAvailable) searchNote = "단어·장면 설명 검색 결과입니다. 이미지 검색은 꺼져 있거나 준비 중이에요.";
  else if (imageAvailable) searchNote = "단어·이미지 검색 결과입니다. 장면 설명 검색은 꺼져 있거나 준비 중이에요.";
  $("search-note").textContent = query ? searchNote + ((textAvailable || imageAvailable) ? " 통합 의미 후보는 최대 200장이며 최대 30초 간격으로 갱신됩니다." : "") : "";
  $("result-count").textContent = `${$("filter").selectedOptions[0].textContent} · ${data.total.toLocaleString()}장`;
  $("empty").hidden = data.items.length > 0;
  $("previous").disabled = page === 0;
  $("next").disabled = (page + 1) * 40 >= data.total;
  $("page").textContent = `${page + 1} / ${Math.max(1, Math.ceil(data.total / 40))}`;
  const nextSignature = JSON.stringify(data.items);
  if (signature === nextSignature) return;
  signature = nextSignature;
  const fragment = document.createDocumentFragment();
  for (const item of data.items) {
    const card = node("article", "card"), img = document.createElement("img"), body = node("div", "card-body");
    img.src = `/api/v1/assets/${item.assetId}/thumb?size=400`; img.alt = item.filename; img.loading = "lazy";
    img.addEventListener("error", () => { img.alt = "썸네일 준비 중 · " + item.filename; });
    body.append(node("div","badge",statusNames[item.status] || item.status), node("div","filename",item.filename),
      node("p","caption",item.caption || (item.status === "RUNNING" ? "장면을 분석하고 있어요…" : "분석 결과를 기다리고 있어요.")));
    const tags = node("div", "tags");
    item.tags.forEach(tag => tags.append(node("span", "tag", tag)));
    body.append(tags);
    if (item.error) body.append(node("p", "error", item.error));
    body.append(node("div", "meta", [item.model, item.analyzedAt?.replace("T"," ")].filter(Boolean).join(" · ")));
    card.append(img, body); fragment.append(card);
  }
  $("results").replaceChildren(fragment);
}
function renderPeople(people) {
  const next = JSON.stringify(people.map(p => [p.clusterId, p.name]));
  if (peopleSignature === next) return;
  peopleSignature = next;
  const options = [new Option("전체 인물", "")];
  for (const p of people) options.push(new Option(p.name ? `${p.name} · 그룹 ${p.clusterId}` : `이름 없는 인물 ${p.clusterId}`, String(p.clusterId)));
  // 삭제/병합된 선택을 전체 검색으로 바꿔 결과 범위를 넓히지 않는다.
  if (clusterId && !people.some(p => String(p.clusterId) === clusterId)) options.push(new Option("선택한 인물 · 삭제되거나 병합됨", clusterId));
  $("person").replaceChildren(...options);
  $("person").value = clusterId;
}
async function refresh() {
  clearTimeout(timer);
  if (loading || document.hidden || $("login").open) return;
  loading = true;
  const current = generation;
  try {
    const personQuery = clusterId ? `&clusterId=${encodeURIComponent(clusterId)}` : "";
    const [status, data, people] = await Promise.all([api("/api/v1/admin/captions/status"), api(`/api/v1/admin/captions?filter=${filter}&page=${page}&q=${encodeURIComponent(query)}${personQuery}`), api("/api/v1/faces/clusters")]);
    const w = status.worker; enabled = w.enabled;
    const ts = status.textSearch;
    if (ts) $("text-search-state").textContent = `설명 의미 검색 (로컬 JVM): ${{ready:"준비됨",indexing:"인덱싱 중",starting:"준비 중",disabled:"꺼짐",model_missing:"모델 파일 필요",unavailable:"준비 상태 확인 필요"}[ts.state] || ts.state} · ${(ts.indexed || 0).toLocaleString()}장`;
    for (const key of ["total","completed","pending","running","failed"]) $(key).textContent = status.counts[key].toLocaleString();
    $("worker-state").textContent = `${w.provider === "GEMINI" ? "GEMINI 배치" : w.provider} · ${w.model} — ${w.running ? (w.currentAssetId ? `사진 #${w.currentAssetId} 분석 중` : "작업 확인 중") : w.enabled ? (w.error ? "연결 대기" : w.batchWaiting ? `배치 결과 대기 ${w.batchWaiting.toLocaleString()}장` : status.counts.pending ? "다음 사진 대기" : "대기열 처리 완료") : "일시정지"}${!w.enabled && w.batchWaiting ? ` · 제출된 배치 ${w.batchWaiting.toLocaleString()}장` : ""}`;
    $("worker-error").textContent = [w.error, w.retryAt && w.enabled ? `재시도: ${new Date(w.retryAt).toLocaleTimeString()}` : null].filter(Boolean).join(" · ");
    $("toggle").textContent = enabled ? "분석 일시정지" : "분석 시작";
    $("toggle").disabled = editing;
    $("connection").textContent = "실시간 반영 중";
    $("updated").textContent = `${new Date().toLocaleTimeString()} 갱신`;
    if (current === generation) { renderPeople(people); render(data); }
  } catch (e) { $("connection").textContent = "연결 확인 필요"; $("message").textContent = e.message; }
  finally { loading = false; if (!$("login").open) timer = setTimeout(refresh, current === generation ? 3000 : 0); }
}
async function loadSettings() {
  const s = await api("/api/v1/admin/settings");
  $("provider").value = s.captionProvider || "LOCAL";
  $("model").value = s.geminiModel || "gemini-2.5-flash";
  $("key-file").value = s.geminiApiKeyFile || "";
}
async function action(fn) {
  if (editing) return;
  editing = true;
  document.querySelectorAll(".controls button").forEach(b => b.disabled = true);
  try { $("message").textContent = await fn(); }
  catch(e) { $("message").textContent = e.message; }
  finally { editing = false; document.querySelectorAll(".controls button").forEach(b => b.disabled = false); refresh(); }
}
$("settings-form").addEventListener("submit", e => { e.preventDefault(); action(async () => {
  const s = await api("/api/v1/admin/settings");
  await saveSettings({...s, captionProvider:$("provider").value, geminiModel:$("model").value.trim(), geminiApiKeyFile:$("key-file").value.trim()});
  return "연결 설정을 저장했어요. 연결 대기 중이었다면 표시된 재시도 시각부터 적용됩니다.";
}); });
$("toggle").addEventListener("click", () => action(async () => {
  const s = await api("/api/v1/admin/settings");
  await saveSettings({...s, captionEnabled:!s.captionEnabled});
  return s.captionEnabled ? "분석을 일시정지했어요. 이미 제출한 Gemini 배치는 Google에서 계속 처리되며, 다시 시작하면 결과를 가져옵니다." : "분석을 켰어요. 제출된 배치 결과를 확인하고 대기 중인 사진부터 처리합니다.";
}));
for (const [id, mode] of [["enqueue","missing"],["retry","failed"]]) $(id).addEventListener("click", () => action(async () => {
  const r = await post("/api/v1/admin/captions/enqueue", {mode}); return `${r.queued.toLocaleString()}장을 큐에 등록했어요.`;
}));
function changePage(next) { page = next; generation++; refresh(); }
$("search-form").addEventListener("submit", e => {e.preventDefault(); query = $("query").value.trim(); changePage(0);});
$("filter").addEventListener("change", () => {filter = $("filter").value; changePage(0);});
$("person").addEventListener("change", () => {clusterId = $("person").value; changePage(0);});
$("previous").addEventListener("click", () => changePage(Math.max(0,page-1)));
$("next").addEventListener("click", () => changePage(page+1));
$("login-form").addEventListener("submit", async e => {
  e.preventDefault();
  try { await post("/api/v1/auth/login",{key:$("login-key").value.trim()}); $("login-key").value = ""; $("login-error").textContent = ""; $("message").textContent = ""; $("login").close(); await loadSettings(); refresh(); }
  catch(e) { $("login-error").textContent = e.message; }
});
$("login").addEventListener("cancel", e => e.preventDefault());
document.addEventListener("visibilitychange", () => { if (!document.hidden) refresh(); else clearTimeout(timer); });
loadSettings().catch(e => { $("message").textContent = e.message; });
refresh();
