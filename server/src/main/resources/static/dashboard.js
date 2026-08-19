"use strict";

// 대시보드 — 메인 뷰어(app.js)와 별개로 도는 독립 페이지.
// 데이터는 /api/v1/stats/* 집계 API만 쓰고, 인증은 메인과 같은 hp_auth 쿠키를 공유한다.

const $ = (id) => document.getElementById(id);

const state = {
  panel: "overview",
  summary: null,
  series: {},        // unit → [{key, count, bytes}] (한 번 받으면 새로고침 전까지 재사용)
  unit: "month",     // 촬영 추이 패널에서 보고 있는 단위
};

// ── API ───────────────────────────────────────────────
async function api(path) {
  const response = await fetch(path, { credentials: "same-origin" });
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
  return failed > 0 ? `실패 ${nf.format(failed)}건` : "밀린 작업 없음";
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
  $("refresh-btn").classList.add("busy");
  $("error").classList.add("hidden");
  try {
    const summary = await api("/api/v1/stats/summary");
    state.summary = summary;
    renderCards(summary);
    renderStorage(summary);
    $("updated").textContent = `업데이트 ${new Date().toLocaleTimeString("ko-KR")}`;
    await loadSeries("year");           // 개요의 연도별 그래프
    if (state.panel === "trends") await loadSeries(state.unit);
    redrawCurrentChart();
  } catch (e) {
    if (e.message !== "unauthorized") showError(`통계를 불러오지 못했습니다: ${e.message}`);
  } finally {
    $("refresh-btn").classList.remove("busy");
  }
}

// 창 크기가 바뀌면 SVG를 다시 그린다 (뷰박스가 픽셀 기준이라 늘리면 라벨이 뭉개진다)
let resizeTimer = null;
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
