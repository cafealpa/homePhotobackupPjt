"use strict";

// 대시보드 — 메인 뷰어(app.js)와 별개로 도는 독립 페이지.
// 데이터는 /api/v1/stats/* 집계 API만 쓰고, 인증은 메인과 같은 hp_auth 쿠키를 공유한다.

const $ = (id) => document.getElementById(id);

const state = {
  panel: "overview",
  summary: null,
  series: {},        // unit → [{key, count, bytes}] (한 번 받으면 새로고침 전까지 재사용)
  unit: "month",     // 촬영 추이 패널에서 보고 있는 단위
  incoming: null,
  capacity: null,
  loading: false,
  retrying: false,
};

// ── API ───────────────────────────────────────────────
async function api(path) {
  const response = await fetch(path, { credentials: "same-origin", signal: AbortSignal.timeout(15000) });
  if (response.status === 401) {
    showLogin();
    throw new Error("unauthorized");
  }
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return response.json();
}

function showLogin() {
  $("login").classList.remove("hidden");
  $("key-input").focus();
}

$("login-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const key = $("key-input").value.trim();
  if (!key) return;
  const response = await fetch("/api/v1/auth/login", {
    method: "POST",
    credentials: "same-origin",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ key }),
  });
  if (response.ok) {
    $("login").classList.add("hidden");
    $("login-error").classList.add("hidden");
    loadAll();
  } else {
    $("login-error").classList.remove("hidden");
  }
});

// ── 표시 서식 ─────────────────────────────────────────
const nf = new Intl.NumberFormat("ko-KR");

/** 바이트 → "1.2 TB" 형태 (소수 자리는 크기에 따라 조정) */
function formatBytes(bytes) {
  if (!bytes) return { value: "0", unit: "B" };
  const units = ["B", "KB", "MB", "GB", "TB", "PB"];
  const i = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1);
  const v = bytes / 1024 ** i;
  return { value: v >= 100 || i === 0 ? Math.round(v).toString() : v.toFixed(1), unit: units[i] };
}

function formatBytesText(bytes) {
  const b = formatBytes(bytes);
  return `${b.value} ${b.unit}`;
}

/** 'YYYY-MM-DDTHH:MM:SS' → 'YYYY.MM.DD' */
function formatDate(iso) {
  return iso ? iso.slice(0, 10).replace(/-/g, ".") : "—";
}

/** 시계열 키를 사람이 읽는 라벨로 — '2026' | '2026-08' | '2026-08-19' */
function formatKey(key) {
  const parts = key.split("-");
  if (parts.length === 1) return `${parts[0]}년`;
  if (parts.length === 2) return `${parts[0]}년 ${Number(parts[1])}월`;
  return `${parts[0]}.${parts[1]}.${parts[2]}`;
}

// ── 사이드바 패널 전환 ────────────────────────────────
const PANEL_TITLES = { overview: "개요", trends: "촬영 추이", storage: "저장소·작업" };

function switchPanel(panel) {
  state.panel = panel;
  document.querySelectorAll(".nav-item[data-panel]").forEach((el) => {
    el.classList.toggle("active", el.dataset.panel === panel);
  });
  document.querySelectorAll(".panel").forEach((el) => {
    el.classList.toggle("hidden", el.id !== `panel-${panel}`);
  });
  $("panel-title").textContent = PANEL_TITLES[panel] || panel;
  if (panel === "trends") loadSeries(state.unit); // 처음 열 때만 받아 온다
  redrawCurrentChart();
}

document.querySelectorAll(".nav-item[data-panel]").forEach((el) => {
  el.addEventListener("click", (e) => {
    e.preventDefault();
    switchPanel(el.dataset.panel);
  });
});

$("unit-seg").querySelectorAll("button").forEach((button) => {
  button.addEventListener("click", () => {
    state.unit = button.dataset.unit;
    $("unit-seg").querySelectorAll("button").forEach((b) => b.classList.toggle("active", b === button));
    loadSeries(state.unit);
  });
});

$("refresh-btn").addEventListener("click", () => {
  state.series = {}; // 캐시 버리고 다시
  loadAll();
});
$("operation-details").addEventListener("click", () => switchPanel("storage"));

// ── 개요 카드 ─────────────────────────────────────────
function renderCards(s) {
  const bytes = formatBytes(s.bytes);
  const free = formatBytes(s.storage.usableBytes);
  const span = s.oldestTakenAt && s.newestTakenAt
    ? `${formatDate(s.oldestTakenAt)} ~ ${formatDate(s.newestTakenAt)}`
    : "촬영일 정보 없음";

  const cards = [
    { icon: "image", label: "관리 중인 사진·동영상", value: nf.format(s.assets), note: span },
    { icon: "storage", label: "원본 용량", value: bytes.value, unit: bytes.unit, note: `남은 공간 ${free.value} ${free.unit}` },
    { icon: "camera", label: "사진", value: nf.format(s.photos), note: `동영상 ${nf.format(s.videos)}개` },
    { icon: "star", label: "즐겨찾기", value: nf.format(s.favorites), note: `휴지통 ${nf.format(s.trashed)}개` },
    { icon: "person", label: "이름 붙인 인물", value: nf.format(s.people), note: `얼굴 찾은 사진 ${nf.format(s.facesDetected)}장` },
    { icon: "phone", label: "백업 기기", value: nf.format(s.devices), note: `앨범 ${nf.format(s.albums)}개` },
    { icon: "folder", label: "키즈노트 자료", value: nf.format(s.kidsnote), note: "타임라인에는 표시되지 않음" },
    { icon: "work", label: "대기 중인 작업", value: nf.format(pendingJobs(s.jobs)), note: jobsNote(s.jobs) },
  ];

  $("cards").innerHTML = cards.map((c) => `
    <div class="card">
      <div class="stat-label"><svg class="icon"><use href="#i-${c.icon}"/></svg>${c.label}</div>
      <div class="stat-value">${c.value}${c.unit ? `<span class="unit">${c.unit}</span>` : ""}</div>
      <div class="stat-note">${c.note}</div>
    </div>
  `).join("");
}

function pendingJobs(jobs) {
  return jobs.filter((j) => j.status === "PENDING" || j.status === "RUNNING").reduce((n, j) => n + j.count, 0);
}

function jobsNote(jobs) {
  const failed = jobs.filter((j) => j.status === "FAILED").reduce((n, j) => n + j.count, 0);
  return failed > 0 ? `실패 ${nf.format(failed)}건` : pendingJobs(jobs) > 0 ? "대기·진행 중인 작업이 있어요" : "밀린 작업 없음";
}

// ── 저장소·작업 패널 ──────────────────────────────────
function renderStorage(s) {
  const { root, totalBytes, usableBytes, usedByOriginals } = s.storage;
  $("storage-root").textContent = root;
  const usedRatio = totalBytes > 0 ? (totalBytes - usableBytes) / totalBytes : 0;
  $("storage-used").style.width = `${(usedRatio * 100).toFixed(1)}%`;
  $("storage-legend").innerHTML = totalBytes > 0
    ? `디스크 ${formatBytesText(totalBytes)} 중 ${formatBytesText(totalBytes - usableBytes)} 사용 (${(usedRatio * 100).toFixed(1)}%)`
      + ` · 남은 공간 ${formatBytesText(usableBytes)} · 이 서버의 원본 ${formatBytesText(usedByOriginals)}`
    : `이 서버의 원본 ${formatBytesText(usedByOriginals)} (디스크 정보를 읽을 수 없음)`;

  const rows = s.jobs.length === 0
    ? `<tr><td colspan="3">작업이 없습니다.</td></tr>`
    : s.jobs.map((j) => `
        <tr><td>${JOB_NAMES[j.jobType] || j.jobType}</td><td>${STATUS_NAMES[j.status] || j.status}</td>
        <td class="num">${nf.format(j.count)}</td></tr>`).join("");
  $("jobs-table").innerHTML =
    `<tr><th>작업</th><th>상태</th><th class="num">건수</th></tr>${rows}`;
}

const JOB_NAMES = { THUMBNAIL: "썸네일", FACE: "얼굴 인식", CAPTION: "장면 분석" };
const STATUS_NAMES = { PENDING: "대기", RUNNING: "진행 중", DONE: "완료", FAILED: "실패" };

function renderOperations() {
  const { summary, incoming, capacity } = state;
  const issues = [];
  const addIssue = (title, detail) => issues.push({ title, detail });
  if (!summary) addIssue("분석 상태 조회 실패", "마지막으로 표시된 통계는 이전 값일 수 있어요. 새로고침으로 다시 확인해 주세요.");
  if (!incoming) addIssue("원본 저장 대기 조회 실패", "원본 저장 상태를 확인할 수 없어요.");
  if (!capacity) addIssue("백업 수신 상태 조회 실패", "새 백업을 받을 수 있는지 확인할 수 없어요.");
  if (capacity && !capacity.accepting) addIssue("새 백업 수신 대기", capacity.reason);

  renderBackupStatus($("backup-status"), incoming, capacity);

  const progress = $("job-progress");
  progress.replaceChildren();
  if (summary) {
    for (const [type, name] of Object.entries(JOB_NAMES)) {
      const counts = { PENDING: 0, RUNNING: 0, DONE: 0, FAILED: 0 };
      for (const job of summary.jobs.filter((job) => job.jobType === type)) counts[job.status] = job.count;
      const total = Object.values(counts).reduce((sum, count) => sum + count, 0);
      const percent = total ? counts.DONE / total * 100 : 0;
      const card = document.createElement("div");
      card.className = "progress-card";
      card.innerHTML = `<h3>${name}</h3><div class="progress-value">${total ? `${percent.toFixed(1)}%` : "등록 작업 없음"}</div>
        <progress max="${total || 1}" value="${counts.DONE}" aria-label="${name} 완료율"></progress>
        <div class="job-counts">${Object.entries(STATUS_NAMES).map(([status, label]) => `<span class="${status === "FAILED" && counts[status] ? "failed" : ""}">${label} ${nf.format(counts[status])}</span>`).join("")}</div>`;
      progress.append(card);
      if (counts.FAILED) addIssue(`${name} 실패 ${nf.format(counts.FAILED)}건`, "저장소·작업 상세에서 상태별 건수를 확인해 주세요.");
    }
  }
  if (incoming) {
    const errors = incoming.items.filter((item) => item.lastError || item.status === "BLOCKED" || item.status === "LOST");
    for (const item of errors.slice(0, 5)) {
      addIssue(item.status === "LOST" ? `${item.filename} · 기기에서 재백업 필요` : `${item.filename} · 원본 저장 확인 필요`, item.lastError || "저장소·작업 상세에서 확인해 주세요.");
    }
    if (errors.length > 5 || incoming.count > incoming.items.length) addIssue("원본 저장 목록 확인", "개요에는 조회된 원본 저장 오류를 최대 5건 표시해요. 저장소·작업 상세에는 접수 순서대로 최대 100건이 표시돼요.");
  }
  const list = $("operation-issues");
  list.replaceChildren();
  for (const issue of issues) {
    const row = document.createElement("div"); row.className = "issue";
    const title = document.createElement("strong"); title.textContent = issue.title;
    const detail = document.createElement("p"); detail.textContent = issue.detail;
    row.append(title, detail); list.append(row);
  }
  if (!issues.length) {
    const empty = document.createElement("p"); empty.className = "issue-empty";
    empty.textContent = "현재 조회된 백업·분석 오류가 없어요."; list.append(empty);
  }
  const badge = $("operation-status");
  const incomplete = !summary || !incoming || !capacity;
  const busy = summary && (pendingJobs(summary.jobs) > 0 || incoming?.count > 0);
  badge.textContent = incomplete ? "일부 상태 확인 불가" : issues.length ? "확인 필요" : busy ? "대기·처리 중" : "대기 작업 없음";
  badge.className = `status-badge ${incomplete || issues.length ? "warning" : busy ? "active" : "good"}`;
}

function renderBackupStatus(container, incoming, capacity) {
  const diskKnown = capacity && capacity.totalBytes > 0;
  const rows = [
    ["현재 백업 수신 상태", capacity ? capacity.accepting ? "새 백업 수신 가능" : "새 백업 수신 일시 대기" : "확인 불가",
      capacity ? capacity.accepting ? "서버가 새 업로드를 받을 수 있어요. 파일 크기에 따라 수신 시 공간을 다시 확인해요."
        : `${capacity.reason} 공간을 확보한 뒤에도 안전하게 재개할 여유가 생길 때까지 대기할 수 있어요.` : "서버의 수신 상태를 조회하지 못했어요."],
    ["원본 저장 대기", incoming ? `${nf.format(incoming.count)}건 · ${formatBytesText(incoming.bytes)}` : "확인 불가",
      incoming ? incoming.count === 0 ? "서버 접수 목록에 원본 저장이 미완료된 파일이 없어요. 휴대폰에서 아직 보내지 않은 파일은 포함하지 않아요."
        : "서버가 접수했지만 원본 저장이 완료되지 않은 파일이에요. 저장 중·오류·재백업 필요 항목도 포함해요."
        : "원본 저장 대기 목록을 조회하지 못했어요."],
    ["서버 수신 디스크 여유", diskKnown ? formatBytesText(capacity.usableBytes) : "확인 불가",
      diskKnown ? `전체 ${formatBytesText(capacity.totalBytes)} 중 현재 사용 가능한 공간이에요. 파일을 처음 받는 서버 디스크 기준이며, 원본 저장소와 다를 수 있어요.`
        : "수신 디스크의 사용 가능한 공간을 확인하지 못했어요."],
    ["항상 남겨둘 최소 공간", capacity ? formatBytesText(capacity.minimumFreeBytes) : "확인 불가",
      "서버 운영을 위해 남겨두는 안전 기준이에요. 디스크 여유가 있어도 업로드 처리 공간을 제외하면 이 기준에 못 미쳐 수신이 대기할 수 있어요."],
    ["처리 중인 업로드 요청 용량", capacity ? formatBytesText(capacity.reservedBytes) : "확인 불가",
      `${capacity && capacity.reservedBytes === 0 ? "현재 예약된 요청이 없어요. " : ""}기존 ‘전송 예약’ 값으로, 처리 중인 업로드 요청 전체 크기를 공간 계산에 미리 잡아둔 값이에요. 요청이 끝나면 해제되며, 전송 완료량이나 남은 양은 아니에요.`],
    ["수신 임시 보관 공간", diskKnown ? `${formatBytesText(capacity.incomingBytes)} / ${formatBytesText(capacity.maxIncomingBytes)}` : "확인 불가",
      "원본 저장 전 파일을 보관하는 폴더의 실제 사용량 / 설정 상한이에요. 정리되지 않은 파일도 포함하므로 위 대기 목록의 용량과 다를 수 있어요."],
  ];
  const list = document.createElement("dl"); list.className = "backup-metrics";
  for (const [label, value, description] of rows) {
    const row = document.createElement("div"); row.className = "backup-metric";
    const term = document.createElement("dt"); term.textContent = label;
    const detail = document.createElement("dd");
    const number = document.createElement("strong"); number.textContent = value;
    const help = document.createElement("p"); help.textContent = description;
    detail.append(number, help); row.append(term, detail); list.append(row);
  }
  container.replaceChildren(list);
}

// ── 상위 구간 표 ──────────────────────────────────────
function renderTopTable(points) {
  const unitName = { year: "연도", month: "월", day: "날짜" }[state.unit];
  const top = [...points].sort((a, b) => b.count - a.count).slice(0, 10);
  const rows = top.length === 0
    ? `<tr><td colspan="3">데이터가 없습니다.</td></tr>`
    : top.map((p) => `<tr><td>${formatKey(p.key)}</td><td class="num">${nf.format(p.count)}장</td>
        <td class="num">${formatBytesText(p.bytes)}</td></tr>`).join("");
  $("top-table").innerHTML =
    `<tr><th>${unitName}</th><th class="num">사진 수</th><th class="num">용량</th></tr>${rows}`;
}

// ── 선 그래프 (외부 라이브러리 없이 인라인 SVG) ───────
const PAD = { top: 14, right: 14, bottom: 26, left: 52 };

/**
 * 시계열 선 그래프를 그린다. points는 [{key, count, bytes}] 오름차순.
 * 뷰박스 좌표로 그리고 마우스를 올리면 가장 가까운 점의 값을 툴팁으로 보여준다.
 */
function drawLineChart(container, points) {
  const width = container.clientWidth || 600;
  const height = container.clientHeight || 220;
  if (points.length === 0) {
    container.innerHTML = `<svg viewBox="0 0 ${width} ${height}"><text class="empty-text" x="${width / 2}" y="${height / 2}" text-anchor="middle">표시할 데이터가 없습니다</text></svg>`;
    return;
  }

  const plotW = Math.max(width - PAD.left - PAD.right, 10);
  const plotH = Math.max(height - PAD.top - PAD.bottom, 10);
  const maxY = Math.max(...points.map((p) => p.count));
  const yTop = niceCeil(maxY);
  const x = (i) => PAD.left + (points.length === 1 ? plotW / 2 : (i / (points.length - 1)) * plotW);
  const y = (v) => PAD.top + plotH - (v / yTop) * plotH;

  // Y축 눈금 4칸
  const ticks = [0, 0.25, 0.5, 0.75, 1].map((f) => Math.round(yTop * f));
  const gridLines = ticks.map((t) => `
    <line class="grid-line" x1="${PAD.left}" y1="${y(t)}" x2="${PAD.left + plotW}" y2="${y(t)}"/>
    <text class="axis-label" x="${PAD.left - 8}" y="${y(t) + 3}" text-anchor="end">${compact(t)}</text>`).join("");

  // X축 라벨은 겹치지 않을 만큼만 (최대 8개)
  const step = Math.max(1, Math.ceil(points.length / 8));
  const xLabels = points.map((p, i) =>
    (i % step === 0 || i === points.length - 1)
      ? `<text class="axis-label" x="${x(i)}" y="${height - 8}" text-anchor="middle">${shortKey(p.key)}</text>`
      : "").join("");

  const line = points.map((p, i) => `${i === 0 ? "M" : "L"}${x(i).toFixed(1)},${y(p.count).toFixed(1)}`).join(" ");
  const area = `${line} L${x(points.length - 1).toFixed(1)},${y(0)} L${x(0).toFixed(1)},${y(0)} Z`;
  // 점이 많으면 동그라미는 생략 (선만으로 충분하고 DOM도 가볍다)
  const dots = points.length <= 60
    ? points.map((p, i) => `<circle class="dot" cx="${x(i).toFixed(1)}" cy="${y(p.count).toFixed(1)}" r="2.5"/>`).join("")
    : "";

  container.innerHTML = `
    <svg viewBox="0 0 ${width} ${height}" preserveAspectRatio="none">
      <defs>
        <linearGradient id="areaGradient" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stop-color="#4a7dff" stop-opacity="0.35"/>
          <stop offset="100%" stop-color="#4a7dff" stop-opacity="0"/>
        </linearGradient>
      </defs>
      ${gridLines}
      <path class="series-area" d="${area}"/>
      <path class="series-line" d="${line}"/>
      ${dots}
      ${xLabels}
      <line class="hover-line hidden" x1="0" y1="${PAD.top}" x2="0" y2="${PAD.top + plotH}"/>
      <rect x="${PAD.left}" y="${PAD.top}" width="${plotW}" height="${plotH}" fill="transparent"/>
    </svg>`;

  attachHover(container, points, { x, y, plotW, plotH });
}

/** 마우스를 따라 가장 가까운 점을 찾아 세로선 + 툴팁 표시 */
function attachHover(container, points, geom) {
  const svg = container.querySelector("svg");
  const hoverLine = container.querySelector(".hover-line");
  const tooltip = ensureTooltip();

  svg.addEventListener("mousemove", (e) => {
    const box = svg.getBoundingClientRect();
    // 뷰박스와 실제 크기가 다를 수 있어 비율로 환산한다
    const vx = ((e.clientX - box.left) / box.width) * svg.viewBox.baseVal.width;
    let best = 0;
    let bestDist = Infinity;
    points.forEach((_, i) => {
      const d = Math.abs(geom.x(i) - vx);
      if (d < bestDist) { bestDist = d; best = i; }
    });
    const p = points[best];
    hoverLine.classList.remove("hidden");
    hoverLine.setAttribute("x1", geom.x(best));
    hoverLine.setAttribute("x2", geom.x(best));
    tooltip.classList.remove("hidden");
    tooltip.innerHTML = `${formatKey(p.key)}<br><b>${nf.format(p.count)}장</b> · ${formatBytesText(p.bytes)}`;
    tooltip.style.left = `${Math.min(e.clientX + 14, innerWidth - tooltip.offsetWidth - 8)}px`;
    tooltip.style.top = `${e.clientY - 12}px`;
  });
  svg.addEventListener("mouseleave", () => {
    hoverLine.classList.add("hidden");
    tooltip.classList.add("hidden");
  });
}

function ensureTooltip() {
  let tooltip = document.querySelector(".tooltip");
  if (!tooltip) {
    tooltip = document.createElement("div");
    tooltip.className = "tooltip hidden";
    document.body.appendChild(tooltip);
  }
  return tooltip;
}

/** 축 최댓값을 1·2·5 계열의 깔끔한 수로 올림 */
function niceCeil(v) {
  if (v <= 5) return 5;
  const mag = 10 ** Math.floor(Math.log10(v));
  for (const m of [1, 2, 2.5, 5, 10]) {
    if (v <= m * mag) return m * mag;
  }
  return 10 * mag;
}

function compact(n) {
  if (n >= 10000) return `${(n / 10000).toFixed(n % 10000 === 0 ? 0 : 1)}만`;
  if (n >= 1000) return `${(n / 1000).toFixed(n % 1000 === 0 ? 0 : 1)}천`;
  return nf.format(n);
}

/** X축 라벨은 짧게 — '2026' | '26.08' | '08.19' */
function shortKey(key) {
  const parts = key.split("-");
  if (parts.length === 1) return parts[0];
  if (parts.length === 2) return `${parts[0].slice(2)}.${parts[1]}`;
  return `${parts[1]}.${parts[2]}`;
}

// ── 로딩 ──────────────────────────────────────────────
function redrawCurrentChart() {
  if (state.panel === "overview" && state.series.year) {
    drawLineChart($("chart-year"), state.series.year);
  }
  if (state.panel === "trends" && state.series[state.unit]) {
    drawLineChart($("chart-main"), state.series[state.unit]);
  }
}

async function loadSeries(unit) {
  if (state.series[unit]) { // 캐시 적중 — 그리기만
    if (unit === state.unit) {
      drawLineChart($("chart-main"), state.series[unit]);
      renderTopTable(state.series[unit]);
      renderSeriesInfo(state.series[unit]);
    }
    return;
  }
  try {
    // 일자별은 점이 너무 많아지므로 최근 730일(2년)로 제한한다
    const limit = unit === "day" ? "&limit=730" : "";
    const points = await api(`/api/v1/stats/timeseries?unit=${unit}${limit}`);
    state.series[unit] = points;
    if (unit === state.unit && state.panel === "trends") {
      drawLineChart($("chart-main"), points);
      renderTopTable(points);
      renderSeriesInfo(points);
    }
    if (unit === "year" && state.panel === "overview") drawLineChart($("chart-year"), points);
  } catch (e) {
    if (e.message !== "unauthorized") showError(`추이 데이터를 불러오지 못했습니다: ${e.message}`);
  }
}

function renderSeriesInfo(points) {
  const total = points.reduce((n, p) => n + p.count, 0);
  const unitName = { year: "연도", month: "개월", day: "일" }[state.unit];
  const capped = state.unit === "day" && points.length >= 730 ? " (최근 2년만 표시)" : "";
  $("series-info").textContent = points.length
    ? `${points.length}${unitName} · 합계 ${nf.format(total)}장${capped}`
    : "";
}

function showError(message) {
  $("error").textContent = message;
  $("error").classList.remove("hidden");
}

async function loadAll() {
  if (state.loading || state.retrying) return;
  state.loading = true;
  $("refresh-btn").classList.add("busy");
  $("error").classList.add("hidden");
  try {
    const results = await Promise.allSettled([
      api("/api/v1/stats/summary"), api("/api/v1/admin/incoming-uploads"), api("/api/v1/backup-capacity"),
    ]);
    [state.summary, state.incoming, state.capacity] = results.map((result) => result.status === "fulfilled" ? result.value : null);
    if (state.summary) { renderCards(state.summary); renderStorage(state.summary); }
    if (state.incoming && state.capacity) renderIncoming(state.incoming, state.capacity);
    else {
      $("incoming-summary").textContent = "최신 원본 저장·수신 상태를 불러오지 못했어요. 다시 새로고침해 주세요.";
      $("incoming-items").replaceChildren();
    }
    renderOperations();
    const failed = results.some((result) => result.status === "rejected");
    $("updated").textContent = `${failed ? "일부 조회 실패" : "업데이트"} ${new Date().toLocaleTimeString("ko-KR")}`;
    if (failed) showError("일부 상태를 갱신하지 못했어요. 남아 있는 통계는 이전 값일 수 있어요.");
    await loadSeries("year");           // 개요의 연도별 그래프
    if (state.panel === "trends") await loadSeries(state.unit);
    redrawCurrentChart();
  } catch (e) {
    if (e.message !== "unauthorized") showError(`통계를 불러오지 못했습니다: ${e.message}`);
  } finally {
    state.loading = false;
    $("refresh-btn").classList.remove("busy");
  }
}

async function loadIncoming() {
  const [summary, capacity] = await Promise.all([api("/api/v1/admin/incoming-uploads"), api("/api/v1/backup-capacity")]);
  state.incoming = summary; state.capacity = capacity;
  renderIncoming(summary, capacity);
  renderOperations();
}

function renderIncoming(summary, capacity) {
  renderBackupStatus($("incoming-summary"), summary, capacity);
  if (summary.oldestReceivedAt) {
    const oldest = document.createElement("p"); oldest.className = "progress-note";
    oldest.textContent = `가장 오래된 접수: ${new Date(summary.oldestReceivedAt).toLocaleString("ko-KR")}`;
    $("incoming-summary").append(oldest);
  }
  const list = $("incoming-items");
  list.replaceChildren();
  const labels = { PENDING: "연결 대기 · 자동 재시도", RUNNING: "저장 중", BLOCKED: "확인 필요", LOST: "기기에서 재백업 필요" };
  for (const item of summary.items) {
    const row = document.createElement("div");
    row.className = "incoming-item";
    const title = document.createElement("p");
    title.textContent = `${item.filename} · ${formatBytesText(item.bytes)} · ${labels[item.status]} · 시도 ${item.attempts}회`;
    row.append(title);
    if (item.lastError) {
      const error = document.createElement("p");
      error.className = "card-sub";
      error.textContent = item.lastError;
      row.append(error);
    }
    if (item.status === "PENDING" || item.status === "BLOCKED") {
      const button = document.createElement("button");
      button.textContent = "지금 재시도";
      button.addEventListener("click", async () => {
        if (state.retrying) return;
        state.retrying = true;
        button.disabled = true;
        try {
          const response = await fetch(`/api/v1/admin/incoming-uploads/${encodeURIComponent(item.hash)}/retry`, {
            method: "POST", credentials: "same-origin", headers: { "Content-Type": "application/json" }, body: "{}", signal: AbortSignal.timeout(15000),
          });
          if (!response.ok) throw new Error(`HTTP ${response.status}`);
          await loadIncoming();
        } catch (e) { showError(`재시도 요청 실패: ${e.message}`); button.disabled = false; }
        finally { state.retrying = false; }
      });
      row.append(button);
    }
    list.append(row);
  }
  if (summary.count > summary.items.length) {
    const note = document.createElement("p");
    note.textContent = "접수 순서대로 최대 100건을 표시합니다.";
    list.append(note);
  }
}

// 창 크기가 바뀌면 SVG를 다시 그린다 (뷰박스가 픽셀 기준이라 늘리면 라벨이 뭉개진다)
let resizeTimer = null;
setInterval(() => {
  if ($("auto-refresh").checked && !document.hidden && $("login").classList.contains("hidden")) loadAll();
}, 15000);
document.addEventListener("visibilitychange", () => {
  if (!document.hidden && $("auto-refresh").checked && $("login").classList.contains("hidden")) loadAll();
});
addEventListener("resize", () => {
  clearTimeout(resizeTimer);
  resizeTimer = setTimeout(redrawCurrentChart, 150);
});

(async function init() {
  try {
    await api("/api/v1/auth/check");
    loadAll();
  } catch (_) {
    // 401 → showLogin()이 이미 호출됨
  }
})();
