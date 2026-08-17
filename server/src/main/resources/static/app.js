"use strict";

// ── 상태 ──────────────────────────────────────────────
const state = {
  view: "timeline",       // timeline | favorites | people | person
  clusterId: null,        // person 뷰에서 사용
  personName: null,
  items: [],              // 현재 뷰의 AssetDto 목록
  cursor: null,
  reachedEnd: false,
  loading: false,
  lastGroup: null,        // 그리드 헤더 그룹 키 (월 'YYYY-MM' 또는 일자 'YYYY-MM-DD')
  lightboxIndex: null,
  years: [],              // 스크럽바용 (내림차순)
  devices: {},            // deviceId → 기기 이름 (사진 소유자 표시용)
  mergeSource: null,      // 인물 합치기 모드: 원본 클러스터 (null = 모드 아님)
  mapBounds: null,        // 지도 뷰: 열려 있는 클러스터의 bbox (null = 하단 패널 닫힘)
  albumId: null,          // album 상세 뷰에서 사용
  albumName: null,
  deviceId: null,         // device 상세 뷰에서 사용
  deviceName: null,
  selecting: false,       // 다중 선택 모드 (timeline/favorites)
  selectedIds: new Set(), // 선택된 assetId
};

const $ = (id) => document.getElementById(id);

/** 스프라이트(index.html)의 머티리얼 아이콘을 인라인으로 삽입 */
const matIcon = (name) => `<svg class="icon"><use href="#i-${name}"></use></svg>`;

// ── API ───────────────────────────────────────────────
async function api(path, options = {}) {
  const response = await fetch(path, { credentials: "same-origin", ...options });
  if (response.status === 401) {
    showLogin();
    throw new Error("unauthorized");
  }
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return response;
}

// ── 로그인 ────────────────────────────────────────────
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
    boot();
  } else {
    $("login-error").classList.remove("hidden");
  }
});

// ── 뷰 전환 ───────────────────────────────────────────
const VIEW_TITLES = { timeline: "사진", favorites: "즐겨찾기", people: "인물", albums: "앨범", devices: "기기별", map: "지도", trash: "휴지통", settings: "설정" };

function switchView(view, options = {}) {
  cancelMerge();
  cancelSelection();
  closeMapPanel();
  closeDayViewer();
  state.view = view;
  state.clusterId = options.clusterId ?? null;
  state.personName = options.personName ?? null;
  state.albumId = options.albumId ?? null;
  state.albumName = options.albumName ?? null;
  state.deviceId = options.deviceId ?? null;
  state.deviceName = options.deviceName ?? null;

  document.querySelectorAll(".nav-item[data-view]").forEach((el) => {
    el.classList.toggle("active", el.dataset.view === view
      || (view === "person" && el.dataset.view === "people")
      || (view === "album" && el.dataset.view === "albums")
      || (view === "device" && el.dataset.view === "devices"));
  });
  $("view-title").textContent = view === "person"
    ? (state.personName || `인물 ${state.clusterId}`)
    : view === "album"
      ? (state.albumName || "앨범")
      : view === "device"
        ? (state.deviceName || "기기")
        : VIEW_TITLES[view];
  $("back-btn").classList.toggle("hidden", view !== "person" && view !== "album" && view !== "device");
  $("select-btn").classList.toggle("hidden", view !== "timeline" && view !== "favorites");
  $("scrubber").classList.toggle("hidden", view !== "timeline");
  const noGridView = view === "people" || view === "albums" || view === "devices" || view === "settings" || view === "map";
  $("grid-size").classList.toggle("hidden", noGridView); // 그리드가 없는 뷰엔 미적용
  $("group-by").classList.toggle("hidden", noGridView);
  $("empty-trash-btn").classList.add("hidden"); // 휴지통 뷰에서 항목이 있을 때만 loadTrash가 켠다
  $("empty").classList.add("hidden");
  $("settings-view").classList.toggle("hidden", view !== "settings");
  $("map-view").classList.toggle("hidden", view !== "map");
  $("grid").classList.toggle("hidden", view === "settings" || view === "map");
  if (view !== "settings") stopImportPolling();

  if (view === "settings") {
    $("status").textContent = "";
    loadSettings();
    // 임포트는 서버에서 도는 작업이라, 다른 화면에 있다 돌아와도 진행 상황이 그대로 이어진다
    startImportPolling();
  } else if (view === "map") {
    $("grid").innerHTML = "";
    $("status").textContent = "";
    state.items = [];
    state.cursor = null;
    state.reachedEnd = true; // 패널을 열기 전까지 loadMore가 돌지 않게
    initMapOnce();
    // 컨테이너가 화면에 보인 뒤 크기를 재계산해야 타일이 제대로 깔린다 (Leaflet 특성)
    requestAnimationFrame(() => {
      map.invalidateSize();
      refreshClusters();
    });
  } else if (view === "people") {
    $("grid").innerHTML = "";
    $("status").textContent = "";
    loadPeople();
  } else if (view === "albums") {
    $("grid").innerHTML = "";
    $("status").textContent = "";
    loadAlbums();
  } else if (view === "devices") {
    $("grid").innerHTML = "";
    $("status").textContent = "";
    loadDevicesGrid();
  } else if (view === "trash") {
    $("grid").innerHTML = "";
    $("status").textContent = "";
    loadTrash();
  } else {
    resetAndLoad(options.startCursor ?? null);
  }
}

document.querySelectorAll(".nav-item[data-view]").forEach((el) => {
  el.addEventListener("click", (e) => {
    e.preventDefault();
    switchView(el.dataset.view);
  });
});
$("back-btn").addEventListener("click", () => switchView(
  state.view === "album" ? "albums" : state.view === "device" ? "devices" : "people",
));

// ── 그리드 크기 (5단계, localStorage에 저장) ──────────
const GRID_SIZES = { xs: 100, s: 140, m: 180, l: 240, xl: 320 };

function applyGridSize(key) {
  const px = GRID_SIZES[key] || GRID_SIZES.m;
  document.documentElement.style.setProperty("--cell-min", `${px}px`);
}

{
  const saved = localStorage.getItem("gridSize") || "m";
  $("grid-size").value = GRID_SIZES[saved] ? saved : "m";
  applyGridSize($("grid-size").value);
}
$("grid-size").addEventListener("change", () => {
  const key = $("grid-size").value;
  localStorage.setItem("gridSize", key);
  applyGridSize(key);
});

// ── 기간 그룹핑 (일/주/월, localStorage에 저장) ───────
// 그리드 크기와 독립 — 헤더를 일자/주차/월 단위로 묶는다
const GROUP_MODES = new Set(["day", "week", "month"]);
let groupBy = "month";

{
  const saved = localStorage.getItem("groupBy");
  groupBy = GROUP_MODES.has(saved) ? saved : "month";
  $("group-by").value = groupBy;
}
$("group-by").addEventListener("change", () => {
  groupBy = $("group-by").value;
  localStorage.setItem("groupBy", groupBy);
  if (["timeline", "favorites", "person", "album", "trash"].includes(state.view)) {
    rerenderGrid();
  }
});

// ── 사진 그리드 (timeline / favorites / person 공용) ──
function formatMonth(yearMonth) {
  const [y, m] = (yearMonth || "").split("-");
  return y && m ? `${y}년 ${Number(m)}월` : yearMonth || "기타";
}

function formatDay(dayKey) {
  const [y, m, d] = dayKey.split("-").map(Number);
  const weekday = "일월화수목금토"[new Date(y, m - 1, d).getDay()];
  return `${y}년 ${m}월 ${d}일 (${weekday})`;
}

function formatWeek(weekKey) {
  // 'YYYY-MM-Wn' → 'YYYY년 M월 n주차'
  const [y, m, w] = weekKey.split("-");
  return `${y}년 ${Number(m)}월 ${w.slice(1)}주차`;
}

/** 현재 그룹핑 기준의 키 — 일 'YYYY-MM-DD' | 주 'YYYY-MM-Wn' | 월 'YYYY-MM' */
function groupKeyFor(asset) {
  const day = (asset.takenAt || "").slice(0, 10);
  if (groupBy === "day") return day || asset.yearMonth || "기타";
  if (groupBy === "week" && day) {
    // 월 내 주차 (1~7일 = 1주차, 8~14일 = 2주차 …)
    return `${day.slice(0, 7)}-W${Math.ceil(Number(day.slice(8, 10)) / 7)}`;
  }
  return asset.yearMonth || "기타";
}

function renderItems(newItems, startIndex) {
  const grid = $("grid");
  const fragment = document.createDocumentFragment();

  newItems.forEach((asset, i) => {
    const group = groupKeyFor(asset);
    if (group !== state.lastGroup) {
      state.lastGroup = group;
      const header = document.createElement("div");
      header.className = "month-header";
      header.dataset.year = group.split("-")[0];
      const isDay = /^\d{4}-\d{2}-\d{2}$/.test(group);
      const isWeek = /^\d{4}-\d{2}-W\d$/.test(group);
      header.textContent = isDay ? formatDay(group) : isWeek ? formatWeek(group) : formatMonth(group);
      if (isDay || isWeek || /^\d{4}-\d{2}$/.test(group)) {
        // 여정 뷰어(타임라인 + 촬영 위치 지도) 열기 — 일/주/월 헤더 공용
        const journey = document.createElement("button");
        journey.className = "day-journey-btn";
        journey.title = isDay ? "이 날의 타임라인·촬영 위치 보기"
          : isWeek ? "이 주의 타임라인·촬영 위치 보기"
            : "이 달의 타임라인·촬영 위치 보기";
        journey.innerHTML = matIcon("timeline");
        journey.addEventListener("click", () => openDayViewer(group));
        header.appendChild(journey);
      }
      fragment.appendChild(header);
      monthObserver.observe(header);
    }

    const index = startIndex + i;
    const cell = document.createElement("div");
    cell.className = "cell";
    cell.dataset.index = index; // 라이트박스 닫을 때 마지막 본 사진으로 스크롤용
    // 선택 모드 중 무한 스크롤로 새로 렌더되는 셀도 선택 상태를 물려받는다
    if (state.selecting && state.selectedIds.has(asset.id)) cell.classList.add("selected");

    const img = document.createElement("img");
    img.loading = "lazy";
    img.src = `/api/v1/assets/${asset.id}/thumb?size=400`;
    img.alt = asset.originalFilename || "";
    cell.appendChild(img);

    if (asset.mediaType === "VIDEO") {
      const badge = document.createElement("span");
      badge.className = "badge";
      badge.innerHTML = matIcon("play");
      cell.appendChild(badge);
    }
    if (asset.favorite) {
      const fav = document.createElement("span");
      fav.className = "fav-badge";
      fav.innerHTML = matIcon("star");
      cell.appendChild(fav);
    }

    // 인물 상세/앨범 상세 뷰에서만: 사진별 hover 액션
    const cellButtons = state.view === "person"
      ? [
          ["person", "다른 인물로 이동", () => openPersonPicker(asset)],
          ["close", "이 인물에서 제외", () => excludeFromPerson(asset)],
        ]
      : state.view === "album"
        ? [["close", "앨범에서 제거", () => removeFromAlbum(asset)]]
        : null;
    if (cellButtons) {
      const actions = document.createElement("div");
      actions.className = "cell-actions";
      for (const [icon, title, handler] of cellButtons) {
        const button = document.createElement("button");
        button.innerHTML = matIcon(icon);
        button.title = title;
        button.addEventListener("click", (e) => {
          e.stopPropagation();
          handler();
        });
        actions.appendChild(button);
      }
      cell.appendChild(actions);
    }

    // 길게 누르면(0.5초) 선택 모드 진입 + 해당 사진 선택 (timeline/favorites)
    if (state.view === "timeline" || state.view === "favorites") {
      attachLongPress(cell, asset);
    }

    cell.addEventListener("click", () => {
      if (consumeLongPressClick()) return; // 길게 누른 뒤 떼는 순간의 click 무시
      if (state.selecting) {
        toggleSelect(asset, cell);
        return;
      }
      openLightbox(index);
    });
    fragment.appendChild(cell);
  });
  grid.appendChild(fragment);
  $("status").textContent = state.items.length
    ? `${state.items.length}장${state.reachedEnd ? "" : "+"}`
    : "";
}

async function loadMore() {
  if (state.loading || state.reachedEnd || state.view === "people" || state.view === "albums" || state.view === "devices" || state.view === "trash" || state.view === "settings") return;
  if (state.view === "map" && !state.mapBounds) return; // 지도 뷰는 클러스터 패널이 열려 있을 때만
  state.loading = true;
  $("loading").classList.remove("hidden");
  try {
    const params = new URLSearchParams({ limit: "200" });
    if (state.cursor) params.set("cursor", state.cursor);
    if (state.view === "favorites") params.set("favorite", "true");
    if (state.view === "person") params.set("clusterId", String(state.clusterId));
    if (state.view === "album") params.set("albumId", String(state.albumId));
    if (state.view === "device") params.set("deviceId", state.deviceId);
    if (state.view === "map") {
      for (const [k, v] of Object.entries(state.mapBounds)) params.set(k, String(v));
    }
    const page = await (await api(`/api/v1/assets?${params}`)).json();
    const startIndex = state.items.length;
    state.items.push(...page.items);
    state.cursor = page.nextCursor;
    if (!page.nextCursor) state.reachedEnd = true;
    (state.view === "map" ? renderMapPanelItems : renderItems)(page.items, startIndex);

    if (state.items.length === 0 && state.reachedEnd) {
      $("empty").textContent = state.view === "favorites"
        ? "즐겨찾기한 사진이 없습니다.\n사진을 크게 열고 별 버튼으로 추가할 수 있어요."
        : state.view === "album"
          ? "앨범이 비어 있습니다.\n사진 뷰에서 '선택'으로 사진을 추가해 보세요."
          : "사진이 없습니다.";
      $("empty").classList.remove("hidden");
    }
  } catch (e) {
    console.error("loadMore failed", e);
  } finally {
    state.loading = false;
    $("loading").classList.add("hidden");
  }
}

function resetAndLoad(startCursor) {
  state.items = [];
  state.cursor = startCursor;
  state.reachedEnd = false;
  state.lastGroup = null;
  $("grid").innerHTML = "";
  $("grid").style.gridTemplateColumns = ""; // 사진 그리드 기본값
  loadMore();
}

// 무한 스크롤 (#content가 스크롤 컨테이너)
new IntersectionObserver(
  (entries) => { if (entries.some((entry) => entry.isIntersecting)) loadMore(); },
  { root: $("content"), rootMargin: "1200px" },
).observe($("sentinel"));

// ── 지도 뷰 ───────────────────────────────────────────
let map = null;           // Leaflet 인스턴스 (뷰 전환 시 파괴하지 않고 재사용)
let markerLayer = null;
let clustersAbort = null; // 팬/줌 연타 시 이전 클러스터 요청 취소용
let mapRefreshTimer = null;

function initMapOnce() {
  if (map) return;
  map = L.map("map");
  L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
    maxZoom: 19,
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a> contributors',
  }).addTo(map);
  markerLayer = L.layerGroup().addTo(map);

  const saved = JSON.parse(localStorage.getItem("mapView") || "null");
  if (saved) {
    map.setView([saved.lat, saved.lon], saved.zoom);
  } else {
    map.setView([36.5, 127.8], 7); // 한국 중심 기본
    fitMapToData();                 // 최초 진입이면 사진이 있는 곳으로 이동
  }

  // 줌 후에도 moveend가 오므로 이 하나로 팬·줌 모두 커버된다
  map.on("moveend", () => {
    const c = map.getCenter();
    localStorage.setItem("mapView", JSON.stringify({ lat: c.lat, lon: c.lng, zoom: map.getZoom() }));
    clearTimeout(mapRefreshTimer);
    mapRefreshTimer = setTimeout(refreshClusters, 300);
  });
}

/** 저장된 지도 위치가 없을 때: 전체 클러스터를 한 번 받아 데이터 위치로 fitBounds */
async function fitMapToData() {
  try {
    const clusters = await (await api(
      "/api/v1/map/clusters?zoom=0&minLat=-90&maxLat=90&minLon=-180&maxLon=180",
    )).json();
    if (clusters.length === 0) return;
    map.fitBounds(L.latLngBounds(clusters.map((c) => [c.lat, c.lon])), { maxZoom: 12, padding: [48, 48] });
  } catch (e) {
    console.error("fitMapToData failed", e);
  }
}

async function refreshClusters() {
  if (!map || state.view !== "map") return;
  clustersAbort?.abort();
  clustersAbort = new AbortController();
  const bounds = map.getBounds().pad(0.2); // 패닝 여유분
  const params = new URLSearchParams({
    zoom: String(map.getZoom()),
    minLat: String(Math.max(-90, bounds.getSouth())),
    maxLat: String(Math.min(90, bounds.getNorth())),
    minLon: String(Math.max(-180, bounds.getWest())),
    maxLon: String(Math.min(180, bounds.getEast())),
  });
  let clusters;
  try {
    clusters = await (await api(`/api/v1/map/clusters?${params}`, { signal: clustersAbort.signal })).json();
  } catch (e) {
    if (e.name !== "AbortError") console.error("refreshClusters failed", e);
    return;
  }

  markerLayer.clearLayers(); // 화면당 수십 개 수준이라 diff 없이 전체 재생성으로 충분
  for (const c of clusters) {
    const countBadge = c.count > 1
      ? `<span class="map-pin-count">${c.count > 999 ? "999+" : c.count}</span>`
      : "";
    const marker = L.marker([c.lat, c.lon], {
      icon: L.divIcon({
        className: "map-pin-wrap",
        html: `<div class="map-pin"><img src="/api/v1/assets/${c.coverAssetId}/thumb?size=400" alt="">${countBadge}</div>`,
        iconSize: [64, 72],
        iconAnchor: [32, 68], // 꼬리 끝이 실제 좌표를 가리키도록
      }),
    });
    marker.on("click", () => openMapPanel(c));
    marker.addTo(markerLayer);
  }
}

/** 핀 클릭 → 하단 패널. 클러스터 멤버 실좌표의 min/max를 그대로 bbox 필터로 사용 */
function openMapPanel(cluster) {
  state.mapBounds = {
    minLat: cluster.minLat, maxLat: cluster.maxLat,
    minLon: cluster.minLon, maxLon: cluster.maxLon,
  };
  state.items = [];
  state.cursor = null;
  state.reachedEnd = false;
  $("map-panel-items").innerHTML = "";
  $("map-panel-title").textContent = `${cluster.count}장`;
  $("map-panel").classList.add("open");
  loadMore();
}

function closeMapPanel() {
  $("map-panel").classList.remove("open");
  if (state.mapBounds === null) return;
  state.mapBounds = null;
  state.items = [];
  state.cursor = null;
  state.reachedEnd = true;
  $("map-panel-items").innerHTML = "";
}

/** renderItems의 축소판 — 월 헤더 없이 패널 그리드에 셀 추가 (라이트박스 연동 규약은 동일) */
function renderMapPanelItems(newItems, startIndex) {
  const fragment = document.createDocumentFragment();
  newItems.forEach((asset, i) => {
    const index = startIndex + i;
    const cell = document.createElement("div");
    cell.className = "cell";
    cell.dataset.index = index; // 라이트박스 닫을 때 focusGridCell이 찾는다
    const img = document.createElement("img");
    img.loading = "lazy";
    img.src = `/api/v1/assets/${asset.id}/thumb?size=400`;
    img.alt = asset.originalFilename || "";
    cell.appendChild(img);
    if (asset.mediaType === "VIDEO") {
      const badge = document.createElement("span");
      badge.className = "badge";
      badge.innerHTML = matIcon("play");
      cell.appendChild(badge);
    }
    if (asset.favorite) {
      const fav = document.createElement("span");
      fav.className = "fav-badge";
      fav.innerHTML = matIcon("star");
      cell.appendChild(fav);
    }
    cell.addEventListener("click", () => openLightbox(index));
    fragment.appendChild(cell);
  });
  $("map-panel-items").appendChild(fragment);
  $("map-panel-title").textContent = `${state.items.length}장${state.reachedEnd ? "" : "+"}`;
}

$("map-panel-close").addEventListener("click", closeMapPanel);

// 패널 무한 스크롤 (기존 옵저버는 root가 #content라 패널에는 별도로 하나 더)
// mapBounds 가드: 패널이 닫혀 있을 때(등록 직후 초기 발화 포함)는 무시
new IntersectionObserver(
  (entries) => { if (state.mapBounds && entries.some((entry) => entry.isIntersecting)) loadMore(); },
  { root: $("map-panel-grid"), rootMargin: "600px" },
).observe($("map-panel-sentinel"));

// ── 하루 여정 뷰어 (일자 헤더의 타임라인 버튼) ─────────
// 라이트박스처럼 전체 화면 오버레이. 그날 사진을 시간 오름차순으로 불러와
// state.items를 잠시 바꿔치기해 기존 라이트박스를 그대로 재사용한다 (닫을 때 복원).
let dayMap = null;
let dayMarkerLayer = null;
let dayViewerOpen = false;
let dayViewerMultiDay = false; // true = 주/월 여정 (스트립 라벨에 일자 포함)
let dayViewerTruncated = false; // 상한 초과로 잘렸는지 (상태 표시용)
let daySnapshot = null;   // { items, cursor, reachedEnd } — 열기 전 상태
let dayLatLngs = [];
const DAY_VIEWER_MAX = 2000; // 주/월 모드 안전 상한 (초과분은 최신순으로 잘림)

function initDayMapOnce() {
  if (dayMap) return;
  dayMap = L.map("day-map");
  L.tileLayer("https://tile.openstreetmap.org/{z}/{x}/{y}.png", {
    maxZoom: 19,
    attribution: '&copy; <a href="https://www.openstreetmap.org/copyright" target="_blank" rel="noopener">OpenStreetMap</a> contributors',
  }).addTo(dayMap);
  dayMarkerLayer = L.layerGroup().addTo(dayMap);
  dayMap.setView([36.5, 127.8], 7);
}

async function openDayViewer(key) {
  const monthMode = /^\d{4}-\d{2}$/.test(key);
  const weekMatch = key.match(/^(\d{4}-\d{2})-W(\d)$/); // 주 모드는 월을 받아와 주차 범위로 거른다
  let items = [];
  let truncated = false;
  try {
    // 월 모드는 500장을 넘을 수 있어 커서로 전부(상한까지) 수집
    let cursor = null;
    do {
      const params = new URLSearchParams({ limit: "500" });
      if (weekMatch) params.set("yearMonth", weekMatch[1]);
      else params.set(monthMode ? "yearMonth" : "day", key);
      if (cursor) params.set("cursor", cursor);
      const page = await (await api(`/api/v1/assets?${params}`)).json();
      items.push(...page.items);
      cursor = page.nextCursor;
      if (cursor && items.length >= DAY_VIEWER_MAX) {
        truncated = true;
        break;
      }
    } while (cursor);
    if (weekMatch) {
      // 월내 주차 범위 (1~7일 = 1주차 …) — groupKeyFor의 주차 계산과 동일해야 한다
      const week = Number(weekMatch[2]);
      const fromDay = (week - 1) * 7 + 1;
      const toDay = week * 7;
      items = items.filter((a) => {
        const d = Number((a.takenAt || "").slice(8, 10));
        return d >= fromDay && d <= toDay;
      });
    }
    items.reverse(); // 서버는 최신순 → 여정은 시간 오름차순
  } catch (e) {
    alert(`사진을 불러오지 못했습니다: ${e.message}`);
    return;
  }
  if (items.length === 0) return;
  cancelSelection();
  daySnapshot = { items: state.items, cursor: state.cursor, reachedEnd: state.reachedEnd };
  state.items = items;
  state.cursor = null;
  state.reachedEnd = true; // 라이트박스 끝 근처 loadMore가 조용히 no-op 되도록
  dayViewerOpen = true;
  dayViewerMultiDay = monthMode || weekMatch !== null;
  dayViewerTruncated = truncated;
  $("day-viewer-title").textContent =
    monthMode ? formatMonth(key) : weekMatch ? formatWeek(key) : formatDay(key);
  $("day-viewer").classList.remove("hidden");
  initDayMapOnce();
  renderDayViewer();
  requestAnimationFrame(() => {
    dayMap.invalidateSize();
    fitDayMap();
  });
}

function closeDayViewer() {
  if (!dayViewerOpen) return;
  dayViewerOpen = false;
  $("day-viewer").classList.add("hidden");
  if (daySnapshot) {
    state.items = daySnapshot.items;
    state.cursor = daySnapshot.cursor;
    state.reachedEnd = daySnapshot.reachedEnd;
    daySnapshot = null;
  }
}

// 촬영자(백업 기기)별 경로 색 — 등장 순서대로 배정
const DAY_ROUTE_COLORS = ["#4a7dff", "#ff6b5e", "#3ecf8e", "#c58cff", "#ffb84a", "#4ad0e0"];

/** 지도(촬영자별 경로선 + 번호 마커)와 하단 타임라인 스트립을 state.items로부터 렌더 */
function renderDayViewer() {
  const items = state.items;
  const hasGps = (a) => a.gpsLat != null && a.gpsLon != null && !(a.gpsLat === 0 && a.gpsLon === 0);

  dayMarkerLayer.clearLayers();
  const gpsItems = items.filter(hasGps);
  dayLatLngs = gpsItems.map((a) => [a.gpsLat, a.gpsLon]); // fitBounds용 전체 좌표

  // 촬영자(기기)별 색 구분 — 지점 사이 직선은 실제 이동 경로가 아니라 오해를 줘서 긋지 않고,
  // 색과 시간순 번호만으로 각자의 동선을 따라가게 한다
  const groups = new Map(); // deviceId → { color, items }
  for (const asset of gpsItems) {
    const key = asset.deviceId || "unknown";
    if (!groups.has(key)) {
      groups.set(key, { color: DAY_ROUTE_COLORS[groups.size % DAY_ROUTE_COLORS.length], items: [] });
    }
    groups.get(key).items.push(asset);
  }

  const seqById = new Map(); // assetId → { n(기기 내 순번), color }
  for (const group of groups.values()) {
    group.items.forEach((asset, n) => {
      seqById.set(asset.id, { n: n + 1, color: group.color });
      const marker = L.marker([asset.gpsLat, asset.gpsLon], {
        icon: L.divIcon({
          className: "day-marker-wrap",
          html: `<div class="day-marker" style="background:${group.color}">${n + 1}</div>`,
          iconSize: [26, 26],
          iconAnchor: [13, 13],
        }),
      });
      marker.on("click", () => {
        const cell = $("day-strip").querySelector(`.cell[data-index="${items.indexOf(asset)}"]`);
        if (!cell) return;
        cell.scrollIntoView({ inline: "center", block: "nearest", behavior: "smooth" });
        cell.classList.add("just-viewed");
        setTimeout(() => cell.classList.remove("just-viewed"), 1600);
      });
      marker.addTo(dayMarkerLayer);
    });
  }

  // 범례: 색 → 기기 이름 (경로가 있을 때만)
  const legend = $("day-legend");
  legend.innerHTML = "";
  for (const [key, group] of groups) {
    const chip = document.createElement("span");
    chip.className = "day-legend-item";
    const name = state.devices[key] || "기타 기기";
    chip.innerHTML = `<span class="day-legend-dot" style="background:${group.color}"></span>${name} · ${group.items.length}곳`;
    legend.appendChild(chip);
  }
  legend.classList.toggle("hidden", groups.size === 0);

  $("day-map-empty").classList.toggle("hidden", dayLatLngs.length > 0);
  $("day-viewer-status").textContent =
    `${items.length}장${dayLatLngs.length ? ` · 위치 ${dayLatLngs.length}곳` : ""}`
    + (dayViewerTruncated ? ` · 최근 ${DAY_VIEWER_MAX}장만 표시` : "");

  // 타임라인 스트립 (시간 오름차순)
  const strip = $("day-strip");
  strip.innerHTML = "";
  const fragment = document.createDocumentFragment();
  items.forEach((asset, i) => {
    const cell = document.createElement("div");
    cell.className = "cell";
    cell.dataset.index = i;

    const img = document.createElement("img");
    img.loading = "lazy";
    img.src = `/api/v1/assets/${asset.id}/thumb?size=400`;
    img.alt = asset.originalFilename || "";
    cell.appendChild(img);

    const time = document.createElement("span");
    time.className = "day-time";
    const t = asset.takenAt || "";
    time.textContent = t.length < 16 ? ""
      : dayViewerMultiDay ? `${Number(t.slice(8, 10))}일 ${t.slice(11, 16)}` // 주/월 모드는 일자 포함
        : t.slice(11, 16);
    cell.appendChild(time);

    const seqInfo = seqById.get(asset.id);
    if (seqInfo) {
      const seq = document.createElement("span");
      seq.className = "day-seq";
      seq.textContent = seqInfo.n;
      seq.style.background = seqInfo.color;
      seq.title = `지도의 같은 색·번호 위치에서 촬영 (${state.devices[asset.deviceId] || "기타 기기"})`;
      cell.appendChild(seq);
    }
    if (asset.mediaType === "VIDEO") {
      const badge = document.createElement("span");
      badge.className = "badge";
      badge.innerHTML = matIcon("play");
      cell.appendChild(badge);
    }
    cell.addEventListener("click", () => openLightbox(i));
    fragment.appendChild(cell);
  });
  strip.appendChild(fragment);
}

function fitDayMap() {
  if (dayLatLngs.length === 0) return;
  dayMap.fitBounds(L.latLngBounds(dayLatLngs).pad(0.2), { maxZoom: 15 });
}

$("day-viewer-close").addEventListener("click", closeDayViewer);

// ── 휴지통 뷰 ─────────────────────────────────────────
async function loadTrash() {
  try {
    const items = await (await api("/api/v1/trash")).json();
    const grid = $("grid");
    grid.innerHTML = "";
    grid.style.gridTemplateColumns = "";
    $("status").textContent = items.length
      ? `${items.length}개 · 30일 보관 후 자동 삭제`
      : "";
    $("empty-trash-btn").classList.toggle("hidden", items.length === 0);

    if (items.length === 0) {
      $("empty").textContent = "휴지통이 비어 있습니다.";
      $("empty").classList.remove("hidden");
      return;
    }
    $("empty").classList.add("hidden");

    const fragment = document.createDocumentFragment();
    for (const item of items) {
      const cell = document.createElement("div");
      cell.className = "cell";

      const img = document.createElement("img");
      img.loading = "lazy";
      img.src = `/api/v1/assets/${item.asset.id}/thumb?size=400`;
      img.alt = item.asset.originalFilename || "";
      cell.appendChild(img);

      const days = document.createElement("span");
      days.className = "trash-days";
      const remaining = Math.max(0, Math.ceil((new Date(item.purgeAt) - Date.now()) / 86400000));
      days.textContent = `D-${remaining}`;
      days.title = `${item.purgeAt.replace("T", " ")}에 자동 영구 삭제`;
      cell.appendChild(days);

      const actions = document.createElement("div");
      actions.className = "cell-actions";
      const buttons = [
        ["restore", "복원", () => restoreFromTrash(item.asset)],
        ["delete", "영구 삭제", () => purgeFromTrash(item.asset)],
      ];
      for (const [icon, title, handler] of buttons) {
        const button = document.createElement("button");
        button.innerHTML = matIcon(icon);
        button.title = title;
        button.addEventListener("click", (e) => {
          e.stopPropagation();
          handler();
        });
        actions.appendChild(button);
      }
      cell.appendChild(actions);
      fragment.appendChild(cell);
    }
    grid.appendChild(fragment);
  } catch (e) {
    console.error("loadTrash failed", e);
  }
}

async function restoreFromTrash(asset) {
  try {
    await api(`/api/v1/trash/${asset.id}/restore`, { method: "POST" });
    loadTrash();
  } catch (e) {
    console.error("restore failed", e);
  }
}

async function purgeFromTrash(asset) {
  const ok = confirm(
    `영구 삭제할까요?\n${asset.originalFilename || ""}\n\n` +
    "서버에서 완전히 삭제되어 복구할 수 없습니다. (폰의 원본은 유지됩니다)",
  );
  if (!ok) return;
  try {
    await api(`/api/v1/trash/${asset.id}`, { method: "DELETE" });
    loadTrash();
  } catch (e) {
    console.error("purge failed", e);
  }
}

async function emptyTrash() {
  const ok = confirm("휴지통을 비울까요?\n모든 항목이 영구 삭제되며 복구할 수 없습니다.");
  if (!ok) return;
  try {
    await api("/api/v1/trash/empty", { method: "POST" });
    loadTrash();
  } catch (e) {
    console.error("empty trash failed", e);
  }
}

$("empty-trash-btn").addEventListener("click", emptyTrash);

// ── 인물 뷰 ───────────────────────────────────────────
function personLabel(cluster) {
  return cluster.name || `인물 ${cluster.clusterId}`;
}

async function renamePerson(cluster) {
  const input = prompt("인물 이름", cluster.name || "");
  if (input === null) return;
  const name = input.trim();
  if (!name) return;
  try {
    await api(`/api/v1/faces/clusters/${cluster.clusterId}/name`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name }),
    });
    loadPeople();
  } catch (e) {
    alert(`이름 변경 실패: ${e.message}`);
  }
}

async function deletePerson(cluster) {
  const ok = confirm(
    `'${personLabel(cluster)}'을(를) 인물 목록에서 삭제할까요?\n사진 자체는 삭제되지 않습니다.`,
  );
  if (!ok) return;
  try {
    await api(`/api/v1/faces/clusters/${cluster.clusterId}`, { method: "DELETE" });
    loadPeople();
  } catch (e) {
    alert(`삭제 실패: ${e.message}`);
  }
}

function startMerge(cluster) {
  state.mergeSource = cluster;
  const banner = $("merge-banner");
  banner.textContent =
    `'${personLabel(cluster)}'과(와) 같은 인물을 클릭하면 하나로 합칩니다 (ESC 취소)`;
  banner.classList.remove("hidden");
  document.querySelectorAll(".person-cell").forEach((el) => {
    el.classList.toggle("merge-source", Number(el.dataset.clusterId) === cluster.clusterId);
  });
}

function cancelMerge() {
  state.mergeSource = null;
  $("merge-banner").classList.add("hidden");
  document.querySelectorAll(".person-cell.merge-source").forEach((el) => {
    el.classList.remove("merge-source");
  });
}

async function mergeInto(target) {
  const source = state.mergeSource;
  cancelMerge();
  try {
    await api(`/api/v1/faces/clusters/${source.clusterId}/merge`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ into: target.clusterId }),
    });
  } catch (e) {
    alert(`합치기 실패: ${e.message}`);
  }
  loadPeople();
}

// ── 사진별 인물 정정 (인물 상세 뷰) ────────────────────
async function reassignFace(asset, toCluster) {
  try {
    await api("/api/v1/faces/reassign", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ assetId: asset.id, fromCluster: state.clusterId, toCluster }),
    });
    resetAndLoad(null); // 현재 인물 뷰 새로고침
  } catch (e) {
    alert(`인물 변경 실패: ${e.message}`);
  }
}

async function excludeFromPerson(asset) {
  const label = state.personName || `인물 ${state.clusterId}`;
  if (!confirm(`이 사진을 '${label}'에서 제외할까요?\n사진 자체는 삭제되지 않습니다.`)) return;
  reassignFace(asset, null);
}

async function openPersonPicker(asset) {
  try {
    const clusters = (await (await api("/api/v1/faces/clusters")).json())
      .filter((c) => c.clusterId !== state.clusterId);
    if (clusters.length === 0) {
      alert("이동할 다른 인물이 없습니다.");
      return;
    }
    const list = $("person-picker-list");
    list.innerHTML = "";
    for (const cluster of clusters) {
      const cell = document.createElement("div");
      cell.className = "picker-cell";

      const img = document.createElement("img");
      img.loading = "lazy";
      img.src = `/api/v1/faces/${cluster.coverFaceId}/thumb`;
      cell.appendChild(img);

      const name = document.createElement("div");
      name.className = "picker-name";
      name.textContent = personLabel(cluster);
      cell.appendChild(name);

      cell.addEventListener("click", () => {
        closePersonPicker();
        reassignFace(asset, cluster.clusterId);
      });
      list.appendChild(cell);
    }
    $("person-picker").classList.remove("hidden");
  } catch (e) {
    alert(`인물 목록을 불러오지 못했습니다: ${e.message}`);
  }
}

function closePersonPicker() {
  $("person-picker").classList.add("hidden");
}

$("person-picker-close").addEventListener("click", closePersonPicker);
$("person-picker").addEventListener("click", (e) => {
  if (e.target === $("person-picker")) closePersonPicker();
});

async function loadPeople() {
  try {
    const clusters = await (await api("/api/v1/faces/clusters")).json();
    const grid = $("grid");
    grid.innerHTML = "";
    grid.style.gridTemplateColumns = "repeat(auto-fill, minmax(140px, 1fr))";
    $("status").textContent = `${clusters.length}명`;

    if (clusters.length === 0) {
      $("empty").textContent = "아직 인물이 없습니다.\n얼굴 분석이 끝나면 표시됩니다.";
      $("empty").classList.remove("hidden");
      return;
    }

    const fragment = document.createDocumentFragment();
    for (const cluster of clusters) {
      const cell = document.createElement("div");
      cell.className = "person-cell";
      cell.dataset.clusterId = cluster.clusterId;

      const img = document.createElement("img");
      img.loading = "lazy";
      img.src = `/api/v1/faces/${cluster.coverFaceId}/thumb`;
      cell.appendChild(img);

      const name = document.createElement("div");
      name.className = "person-name";
      name.textContent = personLabel(cluster);
      cell.appendChild(name);

      const count = document.createElement("div");
      count.className = "person-count";
      count.textContent = `${cluster.faceCount}장`;
      cell.appendChild(count);

      const actions = document.createElement("div");
      actions.className = "person-actions";
      const buttons = [
        ["edit", "이름 바꾸기", () => renamePerson(cluster)],
        ["merge", "다른 인물과 합치기", () => startMerge(cluster)],
        ["delete", "인물 목록에서 삭제", () => deletePerson(cluster)],
      ];
      for (const [icon, title, handler] of buttons) {
        const button = document.createElement("button");
        button.innerHTML = matIcon(icon);
        button.title = title;
        button.addEventListener("click", (e) => {
          e.stopPropagation();
          handler();
        });
        actions.appendChild(button);
      }
      cell.appendChild(actions);

      cell.addEventListener("click", () => {
        if (state.mergeSource !== null) {
          if (cluster.clusterId !== state.mergeSource.clusterId) mergeInto(cluster);
          return;
        }
        switchView("person", { clusterId: cluster.clusterId, personName: cluster.name });
      });
      fragment.appendChild(cell);
    }
    grid.appendChild(fragment);
  } catch (e) {
    console.error("loadPeople failed", e);
  }
}

// ── 앨범 뷰 ───────────────────────────────────────────
async function createAlbumPrompt() {
  const input = prompt("새 앨범 이름");
  if (input === null) return null;
  const name = input.trim();
  if (!name) return null;
  try {
    return await (await api("/api/v1/albums", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name }),
    })).json();
  } catch (e) {
    alert(`앨범 생성 실패: ${e.message}`);
    return null;
  }
}

async function renameAlbum(album) {
  const input = prompt("앨범 이름", album.name);
  if (input === null) return;
  const name = input.trim();
  if (!name) return;
  try {
    await api(`/api/v1/albums/${album.id}/name`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name }),
    });
    loadAlbums();
  } catch (e) {
    alert(`이름 변경 실패: ${e.message}`);
  }
}

async function deleteAlbum(album) {
  const ok = confirm(`'${album.name}' 앨범을 삭제할까요?\n사진 자체는 삭제되지 않습니다.`);
  if (!ok) return;
  try {
    await api(`/api/v1/albums/${album.id}`, { method: "DELETE" });
    loadAlbums();
  } catch (e) {
    alert(`삭제 실패: ${e.message}`);
  }
}

async function removeFromAlbum(asset) {
  const label = state.albumName || "앨범";
  if (!confirm(`이 사진을 '${label}'에서 제거할까요?\n사진 자체는 삭제되지 않습니다.`)) return;
  try {
    await api(`/api/v1/albums/${state.albumId}/assets/remove`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ assetIds: [asset.id] }),
    });
    resetAndLoad(null);
  } catch (e) {
    alert(`제거 실패: ${e.message}`);
  }
}

async function loadAlbums() {
  try {
    const albums = await (await api("/api/v1/albums")).json();
    const grid = $("grid");
    grid.innerHTML = "";
    grid.style.gridTemplateColumns = "repeat(auto-fill, minmax(180px, 1fr))";
    $("status").textContent = albums.length ? `${albums.length}개 앨범` : "";

    const fragment = document.createDocumentFragment();

    // 맨 앞 "새 앨범" 카드
    const newCell = document.createElement("div");
    newCell.className = "album-cell album-new";
    newCell.innerHTML = `<div class="album-cover album-cover-empty">＋</div><div class="album-name">새 앨범</div>`;
    newCell.addEventListener("click", async () => {
      const created = await createAlbumPrompt();
      if (created) loadAlbums();
    });
    fragment.appendChild(newCell);

    for (const album of albums) {
      const cell = document.createElement("div");
      cell.className = "album-cell";

      if (album.coverAssetId != null) {
        const img = document.createElement("img");
        img.loading = "lazy";
        img.className = "album-cover";
        img.src = `/api/v1/assets/${album.coverAssetId}/thumb?size=400`;
        cell.appendChild(img);
      } else {
        const placeholder = document.createElement("div");
        placeholder.className = "album-cover album-cover-empty";
        placeholder.innerHTML = matIcon("folder");
        cell.appendChild(placeholder);
      }

      const name = document.createElement("div");
      name.className = "album-name";
      name.textContent = album.name;
      cell.appendChild(name);

      const count = document.createElement("div");
      count.className = "album-count";
      count.textContent = `${album.count}장`;
      cell.appendChild(count);

      const actions = document.createElement("div");
      actions.className = "album-actions";
      const buttons = [
        ["edit", "이름 바꾸기", () => renameAlbum(album)],
        ["delete", "앨범 삭제", () => deleteAlbum(album)],
      ];
      for (const [icon, title, handler] of buttons) {
        const button = document.createElement("button");
        button.innerHTML = matIcon(icon);
        button.title = title;
        button.addEventListener("click", (e) => {
          e.stopPropagation();
          handler();
        });
        actions.appendChild(button);
      }
      cell.appendChild(actions);

      cell.addEventListener("click", () => {
        switchView("album", { albumId: album.id, albumName: album.name });
      });
      fragment.appendChild(cell);
    }
    grid.appendChild(fragment);
  } catch (e) {
    console.error("loadAlbums failed", e);
  }
}

// ── 기기별 뷰 ─────────────────────────────────────────
async function renameDevice(device) {
  const input = prompt("기기 이름", device.name || "");
  if (input === null) return;
  const name = input.trim();
  if (!name) return;
  try {
    await api(`/api/v1/devices/${encodeURIComponent(device.id)}`, {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name }),
    });
    loadDevices(); // 라이트박스 캡션용 기기 이름 캐시 갱신
    loadDevicesGrid();
  } catch (e) {
    alert(`이름 변경 실패: ${e.message}`);
  }
}

async function loadDevicesGrid() {
  try {
    // 키즈노트는 폰 백업 기기가 아니므로 기기별 화면에서 제외
    const devices = (await (await api("/api/v1/devices")).json()).filter((d) => d.id !== "kidsnote");
    const grid = $("grid");
    grid.innerHTML = "";
    grid.style.gridTemplateColumns = "repeat(auto-fill, minmax(160px, 1fr))";
    $("status").textContent = `${devices.length}대`;

    if (devices.length === 0) {
      $("empty").textContent = "아직 백업한 기기가 없습니다.\n폰 앱으로 백업하면 기기가 등록됩니다.";
      $("empty").classList.remove("hidden");
      return;
    }

    const fragment = document.createDocumentFragment();
    for (const device of devices) {
      const cell = document.createElement("div");
      cell.className = "device-cell";

      const img = document.createElement("img");
      img.loading = "lazy";
      cell.appendChild(img);
      // 표지 = 이 기기의 최신 사진 (기기 수가 적어 개별 조회 부담 없음)
      api(`/api/v1/assets?deviceId=${encodeURIComponent(device.id)}&limit=1`)
        .then((r) => r.json())
        .then((page) => {
          if (page.items[0]) img.src = `/api/v1/assets/${page.items[0].id}/thumb?size=400`;
        })
        .catch(() => {});

      const name = document.createElement("div");
      name.className = "person-name";
      name.textContent = device.name;
      cell.appendChild(name);

      const count = document.createElement("div");
      count.className = "person-count";
      count.textContent = `${device.assetCount}장`;
      cell.appendChild(count);

      const actions = document.createElement("div");
      actions.className = "person-actions";
      const rename = document.createElement("button");
      rename.innerHTML = matIcon("edit");
      rename.title = "기기 이름 바꾸기";
      rename.addEventListener("click", (e) => {
        e.stopPropagation();
        renameDevice(device);
      });
      actions.appendChild(rename);
      cell.appendChild(actions);

      cell.addEventListener("click", () => switchView("device", {
        deviceId: device.id,
        deviceName: device.name,
      }));
      fragment.appendChild(cell);
    }
    grid.appendChild(fragment);
  } catch (e) {
    console.error("loadDevicesGrid failed", e);
  }
}

// ── 다중 선택 모드 (timeline/favorites) ────────────────
function updateSelectBar() {
  const n = state.selectedIds.size;
  $("select-count").textContent = `${n}장 선택`;
  $("select-add-btn").disabled = n === 0;
  $("select-del-btn").disabled = n === 0;
}

function startSelection() {
  state.selecting = true;
  state.selectedIds.clear();
  $("grid").classList.add("selecting");
  $("select-bar").classList.remove("hidden");
  updateSelectBar();
}

function cancelSelection() {
  if (!state.selecting) return; // switchView가 매번 부르므로 no-op 가드
  state.selecting = false;
  state.selectedIds.clear();
  $("grid").classList.remove("selecting");
  $("select-bar").classList.add("hidden");
  document.querySelectorAll(".cell.selected").forEach((el) => el.classList.remove("selected"));
}

function toggleSelect(asset, cell) {
  if (state.selectedIds.has(asset.id)) state.selectedIds.delete(asset.id);
  else state.selectedIds.add(asset.id);
  cell.classList.toggle("selected", state.selectedIds.has(asset.id));
  updateSelectBar();
}

// ── 길게 눌러 선택 모드 진입 ──────────────────────────
let longPressFired = false; // 발화 직후의 click 이벤트를 무시하기 위한 플래그

function consumeLongPressClick() {
  const fired = longPressFired;
  longPressFired = false;
  return fired;
}

function attachLongPress(cell, asset) {
  let timer = null;
  let startX = 0;
  let startY = 0;
  let touchPointer = false;

  const cancel = () => {
    if (timer !== null) {
      clearTimeout(timer);
      timer = null;
    }
  };

  cell.addEventListener("pointerdown", (e) => {
    if (state.selecting) return;               // 이미 선택 모드면 일반 클릭으로 토글
    if (e.pointerType === "mouse" && e.button !== 0) return;
    touchPointer = e.pointerType !== "mouse";
    startX = e.clientX;
    startY = e.clientY;
    cancel();
    timer = setTimeout(() => {
      timer = null;
      longPressFired = true;
      startSelection();
      toggleSelect(asset, cell);
    }, 500);
  });
  // 살짝 움직이는 건 허용, 10px 이상(스크롤/드래그)이면 취소
  cell.addEventListener("pointermove", (e) => {
    if (timer !== null && Math.hypot(e.clientX - startX, e.clientY - startY) > 10) cancel();
  });
  cell.addEventListener("pointerup", cancel);
  cell.addEventListener("pointercancel", cancel);
  cell.addEventListener("pointerleave", cancel);
  // 터치 길게 누르기의 브라우저 기본 컨텍스트 메뉴는 막는다 (마우스 우클릭은 유지)
  cell.addEventListener("contextmenu", (e) => {
    if (touchPointer || longPressFired) e.preventDefault();
  });
}

$("select-btn").addEventListener("click", () => {
  if (state.selecting) cancelSelection();
  else startSelection();
});
$("select-cancel-btn").addEventListener("click", cancelSelection);
$("select-add-btn").addEventListener("click", () => {
  if (state.selectedIds.size > 0) openAlbumPicker([...state.selectedIds]);
});

async function deleteSelected() {
  const ids = [...state.selectedIds];
  if (ids.length === 0) return;
  const ok = confirm(
    `선택한 ${ids.length}장을 휴지통으로 이동할까요?\n\n` +
    "30일 뒤 자동으로 영구 삭제되며, 그 전에는 휴지통에서 복원할 수 있습니다.\n" +
    "휴지통에 있는 동안에도 폰 재백업은 건너뜁니다.",
  );
  if (!ok) return;
  $("select-del-btn").disabled = true;
  const failed = [];
  for (const id of ids) {
    try {
      await api(`/api/v1/assets/${id}`, { method: "DELETE" });
    } catch (e) {
      failed.push(id);
    }
  }
  const deleted = new Set(ids.filter((id) => !failed.includes(id)));
  state.items = state.items.filter((a) => !deleted.has(a.id));
  cancelSelection();
  rerenderGrid();
  if (failed.length > 0) alert(`${failed.length}장은 삭제하지 못했습니다. 서버 로그를 확인해주세요.`);
}

$("select-del-btn").addEventListener("click", deleteSelected);

// ── 앨범 선택 모달 ────────────────────────────────────
async function openAlbumPicker(assetIds) {
  try {
    const albums = await (await api("/api/v1/albums")).json();
    const list = $("album-picker-list");
    list.innerHTML = "";

    // 최상단 고정: 새 앨범 만들기
    const newCell = document.createElement("div");
    newCell.className = "picker-cell";
    newCell.innerHTML = `<div class="picker-folder">＋</div><div class="picker-name">새 앨범 만들기</div>`;
    newCell.addEventListener("click", async () => {
      const created = await createAlbumPrompt();
      if (created) addToAlbum(created.id, created.name, assetIds);
    });
    list.appendChild(newCell);

    for (const album of albums) {
      const cell = document.createElement("div");
      cell.className = "picker-cell";

      if (album.coverAssetId != null) {
        const img = document.createElement("img");
        img.loading = "lazy";
        img.src = `/api/v1/assets/${album.coverAssetId}/thumb?size=400`;
        cell.appendChild(img);
      } else {
        const placeholder = document.createElement("div");
        placeholder.className = "picker-folder";
        placeholder.innerHTML = matIcon("folder");
        cell.appendChild(placeholder);
      }

      const name = document.createElement("div");
      name.className = "picker-name";
      name.textContent = `${album.name} (${album.count}장)`;
      cell.appendChild(name);

      cell.addEventListener("click", () => addToAlbum(album.id, album.name, assetIds));
      list.appendChild(cell);
    }
    $("album-picker").classList.remove("hidden");
  } catch (e) {
    alert(`앨범 목록을 불러오지 못했습니다: ${e.message}`);
  }
}

function closeAlbumPicker() {
  $("album-picker").classList.add("hidden");
}

async function addToAlbum(albumId, albumName, assetIds) {
  try {
    const result = await (await api(`/api/v1/albums/${albumId}/assets`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ assetIds }),
    })).json();
    closeAlbumPicker();
    cancelSelection(); // 선택 모드였다면 종료 — 체크 해제가 성공 피드백
    const skipped = result.requested - result.added;
    $("status").textContent = skipped > 0
      ? `'${albumName}'에 ${result.added}장 추가 (${skipped}장은 이미 있음)`
      : `'${albumName}'에 ${result.added}장 추가`;
  } catch (e) {
    alert(`앨범에 추가 실패: ${e.message}`);
  }
}

$("album-picker-close").addEventListener("click", closeAlbumPicker);
$("album-picker").addEventListener("click", (e) => {
  if (e.target === $("album-picker")) closeAlbumPicker();
});

// ── 연도 스크럽바 ─────────────────────────────────────
const monthObserver = new IntersectionObserver((entries) => {
  const visible = entries.find((entry) => entry.isIntersecting);
  if (visible) setActiveYear(visible.target.dataset.year);
}, { root: $("content"), rootMargin: "0px 0px -80% 0px" });

function setActiveYear(year) {
  document.querySelectorAll(".scrub-year").forEach((el) => {
    el.classList.toggle("active", el.dataset.year === year);
  });
}

async function buildScrubber() {
  try {
    const months = await (await api("/api/v1/months")).json();
    const years = [...new Set(months.map((m) => m.yearMonth.split("-")[0]))];
    state.years = years;
    const scrubber = $("scrubber");
    scrubber.innerHTML = "";
    for (const year of years) {
      const el = document.createElement("div");
      el.className = "scrub-year";
      el.dataset.year = year;
      el.textContent = year;
      el.addEventListener("click", () => jumpToYear(year));
      scrubber.appendChild(el);
    }
  } catch (e) {
    console.error("buildScrubber failed", e);
  }
}

function jumpToYear(year) {
  if (state.view !== "timeline") switchView("timeline");
  const isLatestYear = state.years[0] === year;
  // 커서를 "해당 연도의 끝"으로 합성해 그 연도부터 과거 방향으로 로드한다
  const startCursor = isLatestYear ? null : `${Number(year) + 1}-01-01T00:00:00~0`;
  resetAndLoad(startCursor);
  $("content").scrollTop = 0;
  setActiveYear(year);
}

// ── 라이트박스 ────────────────────────────────────────
// 앞뒤 2장 프리로드 캐시: assetId → 디코딩된 <img> (탐색 시 깜빡임 방지)
const preloadCache = new Map();

function lightboxImageUrl(asset) {
  return `/api/v1/assets/${asset.id}/thumb?size=1600`;
}

function preloadAround(index) {
  const keep = new Set();
  for (let delta = -2; delta <= 2; delta++) {
    const asset = state.items[index + delta];
    if (!asset || asset.mediaType === "VIDEO") continue;
    keep.add(asset.id);
    if (!preloadCache.has(asset.id)) {
      const img = new Image();
      img.src = lightboxImageUrl(asset);
      img.alt = asset.originalFilename || "";
      if (img.decode) img.decode().catch(() => {});
      preloadCache.set(asset.id, img);
    }
  }
  // 창(±2) 밖으로 벗어난 이미지는 버린다
  for (const id of [...preloadCache.keys()]) {
    if (!keep.has(id)) preloadCache.delete(id);
  }
}

function openLightbox(index) {
  state.lightboxIndex = index;
  $("lightbox").classList.remove("hidden");
  renderLightbox();
}

function closeLightbox() {
  const lastIndex = state.lightboxIndex;
  state.lightboxIndex = null;
  $("lb-content").innerHTML = ""; // 동영상 재생 정지
  $("lightbox").classList.add("hidden");
  focusGridCell(lastIndex);
}

/** 라이트박스에서 마지막으로 본 사진을 그리드에서 가운데로 스크롤 + 잠깐 강조 */
function focusGridCell(index) {
  if (index === null) return;
  // 하루 여정 뷰어가 열려 있으면 그 스트립 안에서 찾는다 (뒤의 #grid에 같은 인덱스가 있음)
  const scope = dayViewerOpen ? $("day-strip") : document;
  const cell = scope.querySelector(`.cell[data-index="${index}"]`);
  if (!cell) return;
  cell.scrollIntoView({ block: "center" });
  cell.classList.add("just-viewed");
  setTimeout(() => cell.classList.remove("just-viewed"), 1600);
}

function moveLightbox(delta) {
  if (state.lightboxIndex === null) return;
  const next = state.lightboxIndex + delta;
  if (next < 0 || next >= state.items.length) return;
  state.lightboxIndex = next;
  renderLightbox();
  if (next >= state.items.length - 10) loadMore();
}

function renderLightbox() {
  const asset = state.items[state.lightboxIndex];
  if (!asset) return;
  const content = $("lb-content");
  content.innerHTML = "";

  if (asset.mediaType === "VIDEO") {
    const video = document.createElement("video");
    video.controls = true;
    video.autoplay = true;
    video.src = `/api/v1/assets/${asset.id}/file`;
    content.appendChild(video);
  } else {
    // 프리로드된 이미지가 있으면 그대로 붙여 깜빡임 없이 표시
    const img = preloadCache.get(asset.id) || (() => {
      const el = new Image();
      el.src = lightboxImageUrl(asset);
      el.alt = asset.originalFilename || "";
      return el;
    })();
    content.appendChild(img);
  }

  const favButton = $("lb-fav");
  favButton.innerHTML = matIcon(asset.favorite ? "star" : "star-border");
  favButton.classList.toggle("on", asset.favorite);

  const parts = [asset.originalFilename || "", (asset.takenAt || "").replace("T", " ")];
  const deviceName = state.devices[asset.deviceId];
  if (deviceName) parts.push(deviceName);
  $("lb-caption").textContent = parts.filter(Boolean).join("  ·  ");

  renderInfoPanel(asset);
  preloadAround(state.lightboxIndex);
}

// ── 정보(EXIF) 패널 ───────────────────────────────────
const TAKEN_AT_SOURCE_LABELS = {
  FILENAME: "파일명",
  EXIF: "EXIF",
  FILE_MTIME: "파일 수정시각",
  UPLOAD_TIME: "업로드 시각",
};

function formatBytes(bytes) {
  if (bytes == null) return null;
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

function formatDuration(ms) {
  if (ms == null) return null;
  const total = Math.round(ms / 1000);
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${m}:${String(s).padStart(2, "0")}`;
}

function toggleInfoPanel() {
  $("lb-info-panel").classList.toggle("hidden");
}

function renderInfoPanel(asset) {
  const list = $("lb-info-list");
  list.innerHTML = "";

  const rows = [];
  rows.push(["파일명", asset.originalFilename]);

  if (asset.takenAt) {
    const source = TAKEN_AT_SOURCE_LABELS[asset.takenAtSource] || asset.takenAtSource;
    rows.push(["촬영일시", `${asset.takenAt.replace("T", " ")} (${source})`]);
  }

  const camera = [asset.cameraMake, asset.cameraModel].filter(Boolean).join(" ");
  if (camera) rows.push(["카메라", camera]);

  if (asset.width && asset.height) {
    const mp = (asset.width * asset.height / 1e6).toFixed(1);
    rows.push(["해상도", `${asset.width} × ${asset.height} (${mp}MP)`]);
  }

  rows.push(["크기", formatBytes(asset.fileSize)]);
  rows.push(["재생시간", formatDuration(asset.durationMs)]);
  rows.push(["백업 기기", state.devices[asset.deviceId]]);

  for (const [label, value] of rows) {
    if (!value) continue;
    const dt = document.createElement("dt");
    dt.textContent = label;
    const dd = document.createElement("dd");
    dd.textContent = value;
    list.appendChild(dt);
    list.appendChild(dd);
  }

  // (0,0)은 GPS 꺼짐 상태로 저장된 값 — 위치 없음으로 취급
  if (asset.gpsLat != null && asset.gpsLon != null && !(asset.gpsLat === 0 && asset.gpsLon === 0)) {
    const dt = document.createElement("dt");
    dt.textContent = "위치";
    const dd = document.createElement("dd");
    const link = document.createElement("a");
    link.href = `https://www.openstreetmap.org/?mlat=${asset.gpsLat}&mlon=${asset.gpsLon}#map=16/${asset.gpsLat}/${asset.gpsLon}`;
    link.target = "_blank";
    link.rel = "noopener";
    link.textContent = `${asset.gpsLat.toFixed(5)}, ${asset.gpsLon.toFixed(5)}`;
    dd.appendChild(link);
    list.appendChild(dt);
    list.appendChild(dd);
  }

  loadCaption(asset, list);
}

/** 장면 분석(Phase 4) 결과를 비동기로 붙인다. 아직 분석 전이면 아무것도 표시하지 않는다. */
async function loadCaption(asset, list) {
  let data;
  try {
    data = await (await api(`/api/v1/assets/${asset.id}/caption`)).json();
  } catch (e) {
    return;
  }
  // 응답을 기다리는 사이 다른 사진으로 넘어갔으면 버린다
  if (state.items[state.lightboxIndex]?.id !== asset.id) return;
  if (!data.caption) return;

  const rows = [["장면", data.caption]];
  if (data.tags && data.tags.length) rows.push(["태그", data.tags.join(", ")]);
  for (const [label, value] of rows) {
    const dt = document.createElement("dt");
    dt.textContent = label;
    const dd = document.createElement("dd");
    dd.textContent = value;
    list.appendChild(dt);
    list.appendChild(dd);
  }
}

function rerenderGrid() {
  if (dayViewerOpen) { // 하루 여정 뷰어에서 삭제 시: 스트립·마커 재구성
    renderDayViewer();
    return;
  }
  if (state.view === "map") {
    $("map-panel-items").innerHTML = "";
    renderMapPanelItems(state.items, 0);
    return;
  }
  const grid = $("grid");
  grid.innerHTML = "";
  state.lastGroup = null;
  renderItems(state.items, 0);
}

async function deleteCurrent() {
  const asset = state.items[state.lightboxIndex];
  if (!asset) return;
  const ok = confirm(
    `이 사진을 휴지통으로 이동할까요?\n${asset.originalFilename || ""}\n\n` +
    "30일 뒤 자동으로 영구 삭제되며, 그 전에는 휴지통에서 복원할 수 있습니다.\n" +
    "휴지통에 있는 동안에도 폰 재백업은 건너뜁니다.",
  );
  if (!ok) return;
  try {
    await api(`/api/v1/assets/${asset.id}`, { method: "DELETE" });
    state.items.splice(state.lightboxIndex, 1);
    rerenderGrid();
    if (state.items.length === 0) {
      closeLightbox();
    } else {
      if (state.lightboxIndex >= state.items.length) {
        state.lightboxIndex = state.items.length - 1;
      }
      renderLightbox();
    }
  } catch (e) {
    console.error("delete failed", e);
    alert("삭제에 실패했습니다. 서버 로그를 확인해주세요.");
  }
}

async function toggleFavorite() {
  const asset = state.items[state.lightboxIndex];
  if (!asset) return;
  try {
    const updated = await (await api(`/api/v1/assets/${asset.id}/favorite`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ favorite: !asset.favorite }),
    })).json();
    asset.favorite = updated.favorite;
    renderLightbox();
  } catch (e) {
    console.error("favorite toggle failed", e);
  }
}

$("lb-close").addEventListener("click", closeLightbox);
$("lb-album").addEventListener("click", () => {
  const asset = state.items[state.lightboxIndex];
  if (asset) openAlbumPicker([asset.id]);
});
$("lb-fav").addEventListener("click", toggleFavorite);
$("lb-info").addEventListener("click", toggleInfoPanel);
$("lb-del").addEventListener("click", deleteCurrent);
$("lb-prev").addEventListener("click", () => moveLightbox(-1));
$("lb-next").addEventListener("click", () => moveLightbox(1));
$("lightbox").addEventListener("click", (e) => {
  if (e.target === $("lightbox")) closeLightbox();
});

// 마우스 휠: 아래로 = 다음, 위로 = 이전. 정보 패널 위에서는 패널 스크롤 우선.
let wheelAccum = 0;
let lastWheelNav = 0;
$("lightbox").addEventListener("wheel", (e) => {
  if (state.lightboxIndex === null) return;
  const panel = $("lb-info-panel");
  if (!panel.classList.contains("hidden") && panel.contains(e.target)) return;
  e.preventDefault();
  const now = Date.now();
  if (now - lastWheelNav < 120) return; // 트랙패드 관성 스크롤 연타 방지
  wheelAccum += e.deltaY;
  if (Math.abs(wheelAccum) >= 50) {
    moveLightbox(wheelAccum > 0 ? 1 : -1);
    wheelAccum = 0;
    lastWheelNav = now;
  }
}, { passive: false });

// 마우스 가운데(휠) 클릭: 정보 패널 토글
$("lightbox").addEventListener("mousedown", (e) => {
  if (e.button === 1 && state.lightboxIndex !== null) e.preventDefault(); // 자동 스크롤 모드 방지
});
$("lightbox").addEventListener("auxclick", (e) => {
  if (e.button === 1 && state.lightboxIndex !== null) {
    e.preventDefault();
    toggleInfoPanel();
  }
});

// 모바일: 라이트박스 좌우 스와이프로 이전/다음 (세로 제스처와 구분)
let touchStartX = null;
let touchStartY = null;
$("lightbox").addEventListener("touchstart", (e) => {
  if (e.touches.length !== 1) { touchStartX = null; return; }
  touchStartX = e.touches[0].clientX;
  touchStartY = e.touches[0].clientY;
}, { passive: true });
$("lightbox").addEventListener("touchend", (e) => {
  if (touchStartX === null || state.lightboxIndex === null) return;
  const dx = e.changedTouches[0].clientX - touchStartX;
  const dy = e.changedTouches[0].clientY - touchStartY;
  touchStartX = null;
  if (Math.abs(dx) > 60 && Math.abs(dx) > Math.abs(dy) * 1.5) {
    moveLightbox(dx < 0 ? 1 : -1);
  }
}, { passive: true });

document.addEventListener("keydown", (e) => {
  if (e.key === "Escape" && !$("album-picker").classList.contains("hidden")) {
    closeAlbumPicker();
    return;
  }
  if (e.key === "Escape" && !$("person-picker").classList.contains("hidden")) {
    closePersonPicker();
    return;
  }
  if (e.key === "Escape" && state.selecting) {
    cancelSelection();
    return;
  }
  if (e.key === "Escape" && state.mergeSource !== null) {
    cancelMerge();
    return;
  }
  if (e.key === "Escape" && dayViewerOpen && state.lightboxIndex === null) {
    closeDayViewer();
    return;
  }
  if (state.lightboxIndex === null) return;
  if (e.key === "Escape") closeLightbox();
  else if (e.key === "ArrowLeft") moveLightbox(-1);
  else if (e.key === "ArrowRight") moveLightbox(1);
  else if (e.key === "f") toggleFavorite();
  else if (e.key === "i") toggleInfoPanel();
  else if (e.key === "Delete") deleteCurrent();
});

// ── 설정 뷰 ───────────────────────────────────────────
let loadedApiKey = null; // API 키 변경 감지용 (변경 시 재로그인 안내)

/** 서버가 열려 있는 LAN 주소·포트 표시 (설정 페이지 상단) */
async function loadServerInfo() {
  const box = $("set-server-info");
  try {
    const info = await (await api("/api/v1/admin/server-info")).json();
    box.innerHTML = "";
    if (info.addresses.length === 0) {
      box.textContent = `포트 ${info.port} (LAN IP를 찾지 못했습니다)`;
      return;
    }
    for (const address of info.addresses) {
      const line = document.createElement("code");
      line.className = "server-addr";
      line.textContent = `http://${address}:${info.port}`;
      box.appendChild(line);
    }
  } catch (e) {
    box.textContent = "서버 정보를 불러오지 못했습니다";
  }
}

async function loadSettings() {
  const msg = $("settings-msg");
  msg.textContent = "";
  msg.className = "";
  loadServerInfo();
  try {
    const s = await (await api("/api/v1/admin/settings")).json();
    loadedApiKey = s.apiKey;
    $("set-storage-root").value = s.storageRoot;
    $("set-api-key").value = s.apiKey;
    $("set-ffmpeg-path").value = s.ffmpegPath;
    $("set-trash-days").value = s.trashRetentionDays;
    $("set-caption-enabled").checked = s.captionEnabled;
    $("set-caption-url").value = s.captionBaseUrl;
    $("set-caption-model").value = s.captionModel;
    $("set-caption-timeout").value = s.captionTimeoutSeconds;
  } catch (e) {
    msg.textContent = "설정을 불러오지 못했습니다";
    msg.className = "error";
  }
}

$("settings-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const msg = $("settings-msg");
  const body = {
    storageRoot: $("set-storage-root").value.trim(),
    apiKey: $("set-api-key").value.trim(),
    ffmpegPath: $("set-ffmpeg-path").value.trim(),
    trashRetentionDays: Number($("set-trash-days").value),
    captionEnabled: $("set-caption-enabled").checked,
    captionBaseUrl: $("set-caption-url").value.trim(),
    captionModel: $("set-caption-model").value.trim(),
    captionTimeoutSeconds: Number($("set-caption-timeout").value),
  };
  msg.textContent = "저장 중…";
  msg.className = "";
  try {
    const response = await fetch("/api/v1/admin/settings", {
      method: "PUT",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    if (!response.ok) {
      // 400이면 서버 검증 메시지를 그대로 보여준다
      let detail = `HTTP ${response.status}`;
      try { detail = (await response.json()).error || detail; } catch (_) {}
      msg.textContent = detail;
      msg.className = "error";
      return;
    }
    const result = await response.json();
    const notes = [];
    if (result.restartRequired.length > 0) notes.push("저장소 경로 변경은 서버 재시작 후 적용됩니다");
    if (loadedApiKey !== null && body.apiKey !== loadedApiKey) {
      notes.push("API 키가 변경되어 잠시 후 다시 로그인해야 합니다");
      loadedApiKey = body.apiKey;
    }
    msg.textContent = "저장됐습니다" + (notes.length ? ` — ${notes.join(", ")}` : "");
    msg.className = "ok";
  } catch (e) {
    // API 키를 바꿨으면 이 시점부터 쿠키가 무효 → 로그인 화면으로
    msg.textContent = "저장 요청 실패 — API 키를 변경했다면 새 키로 다시 로그인하세요";
    msg.className = "error";
  }
});

$("settings-restart").addEventListener("click", async () => {
  if (!confirm("서버를 재시작할까요?\n잠시 연결이 끊겼다가 자동으로 다시 연결됩니다.")) return;
  const msg = $("settings-msg");
  const btn = $("settings-restart");
  try {
    await api("/api/v1/admin/restart", { method: "POST" });
  } catch (e) {
    msg.textContent = "재시작 요청 실패";
    msg.className = "error";
    return;
  }
  btn.disabled = true;
  msg.textContent = "재시작 중… 서버가 다시 뜨면 자동으로 새로고침됩니다";
  msg.className = "";
  await new Promise((r) => setTimeout(r, 3000));
  for (let i = 0; i < 40; i++) {
    try {
      const r = await fetch("/api/v1/health", { cache: "no-store" });
      if (r.ok) { location.reload(); return; }
    } catch (_) { /* 아직 안 떴음 */ }
    await new Promise((r) => setTimeout(r, 2000));
  }
  btn.disabled = false;
  msg.textContent = "서버가 다시 응답하지 않습니다 — 서버 로그를 확인해 주세요";
  msg.className = "error";
});

// ── 대용량 로컬 임포트 ────────────────────────────────
// 서버가 자기 디스크의 폴더를 통째로 훑어 들여온다. 몇 시간이 걸릴 수 있으므로
// 상태는 서버가 갖고 있고 화면은 폴링만 한다 — 새로고침하거나 창을 닫아도 계속 돈다.

let importPollTimer = null;
let importWasRunning = false; // 실행 → 종료로 넘어가는 순간에만 목록을 새로고침하기 위한 표시

const IMPORT_PHASE_LABEL = {
  SCANNING: "폴더를 훑는 중",
  IMPORTING: "가져오는 중",
  DONE: "완료",
  CANCELLED: "중지됨",
  ERROR: "오류",
};

// 라이트박스의 formatBytes/formatDuration과 이름이 겹치지 않게 따로 둔다
// (저쪽은 "1.5 GB" · 동영상 길이 "3:07" 포맷이라 용도가 다르다)
function formatImportBytes(bytes) {
  if (!bytes) return "0KB";
  const units = [[1024 ** 4, "TB", 2], [1024 ** 3, "GB", 1], [1024 ** 2, "MB", 0]];
  for (const [size, unit, digits] of units) {
    if (bytes >= size) return `${(bytes / size).toFixed(digits)}${unit}`;
  }
  return `${Math.round(bytes / 1024)}KB`;
}

function formatElapsed(ms) {
  const total = Math.round(ms / 1000);
  const h = Math.floor(total / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  if (h > 0) return `${h}시간 ${m}분`;
  if (m > 0) return `${m}분 ${s}초`;
  return `${s}초`;
}

function renderImportStatus(st) {
  const progress = $("imp-progress");
  const running = st.running;
  const finished = st.phase === "DONE" || st.phase === "CANCELLED" || st.phase === "ERROR";

  $("imp-start").disabled = running;
  $("imp-scan").disabled = running;
  $("imp-stop").classList.toggle("hidden", !running);
  $("imp-path").disabled = running;

  if (!running && !finished) {
    progress.classList.add("hidden");
    return;
  }
  progress.classList.remove("hidden");

  const scanning = st.phase === "SCANNING";
  // 미리 확인은 세기만 하고 끝난다 — 0/1500 (0%) 같은 진행률을 보여 주면 오해를 부른다
  const countOnly = scanning || st.mode === "SCAN";
  const percent = st.total > 0 ? Math.round((st.processed / st.total) * 100) : 0;

  // 스캔 중엔 전체 개수를 모른다 → 퍼센트 대신 훑는 애니메이션
  const fill = $("imp-bar-fill");
  fill.classList.toggle("indeterminate", scanning);
  fill.style.width = scanning ? "" : `${percent}%`;
  $("imp-bar").classList.toggle("hidden", countOnly && !scanning);

  const parts = [];
  parts.push(`<b>${IMPORT_PHASE_LABEL[st.phase] || st.phase}</b>`);
  if (countOnly) {
    parts.push(`${(scanning ? st.scannedFiles : st.total).toLocaleString()}개`);
    if (st.totalBytes > 0) parts.push(formatImportBytes(st.totalBytes));
    if (!scanning && st.freeBytes > 0) parts.push(`저장소 여유 ${formatImportBytes(st.freeBytes)}`);
  } else {
    parts.push(`${st.processed.toLocaleString()} / ${st.total.toLocaleString()}장 (${percent}%)`);
    if (st.totalBytes > 0) parts.push(`${formatImportBytes(st.processedBytes)} / ${formatImportBytes(st.totalBytes)}`);
    if (st.imported > 0) parts.push(`신규 <b>${st.imported.toLocaleString()}</b>`);
    if (st.duplicates > 0) parts.push(`중복 ${st.duplicates.toLocaleString()}`);
    if (st.failed > 0) parts.push(`실패 ${st.failed.toLocaleString()}`);
  }
  if (st.elapsedMs >= 1000) parts.push(`경과 ${formatElapsed(st.elapsedMs)}`);
  // 속도를 같이 보여준다 — 남은 시간이 길게 나올 때 디스크가 느린 건지 판단할 근거가 된다
  if (st.bytesPerSec > 0) parts.push(`${formatImportBytes(st.bytesPerSec)}/s`);
  if (st.etaMs != null) parts.push(`남은 시간 약 ${formatElapsed(st.etaMs)}`);
  // flex 컨테이너라 각 항목을 span으로 감싸야 gap이 항목 사이에만 들어간다
  $("imp-stats").innerHTML = parts.map((p) => `<span>${p}</span>`).join("");

  $("imp-current").textContent = st.currentFile ? `처리 중: ${st.currentFile}` : "";

  const msg = $("imp-msg");
  if (st.message) {
    msg.textContent = st.message;
    msg.className = st.phase === "ERROR" ? "error" : st.phase === "CANCELLED" ? "warn" : "ok";
  } else if (running) {
    msg.textContent = "";
    msg.className = "";
  }
  if (st.lastError) $("imp-current").textContent = `마지막 오류: ${st.lastError}`;
}

async function pollImportStatus() {
  let st;
  try {
    st = await (await api("/api/v1/admin/import/status")).json();
  } catch (e) {
    return; // 재시작 중 등 — 다음 폴링에서 회복
  }
  renderImportStatus(st);
  if (st.running) {
    importWasRunning = true;
    return;
  }
  // 끝났으면 폴링을 멈춘다. 마지막 상태(완료 메시지)는 화면에 그대로 남는다.
  stopImportPolling();
  if (importWasRunning) {
    importWasRunning = false;
    if (st.imported > 0) refreshAfterImport();
  }
}

function startImportPolling() {
  if (importPollTimer !== null) return;
  pollImportStatus();
  importPollTimer = setInterval(pollImportStatus, 1500);
}

function stopImportPolling() {
  if (importPollTimer === null) return;
  clearInterval(importPollTimer);
  importPollTimer = null;
}

/** 임포트로 사진이 늘었으면 스크럽바 연도 목록과 기기 이름을 다시 읽어 둔다 */
function refreshAfterImport() {
  buildScrubber();   // 연도 스크럽바 (임포트로 과거 연도가 새로 생겼을 수 있다)
  loadDevices();     // '서버 임포트' 기기가 이번에 처음 등록됐을 수 있다
}

async function requestImport(mode) {
  const sourcePath = $("imp-path").value.trim();
  const msg = $("imp-msg");
  if (!sourcePath) {
    msg.textContent = "가져올 폴더 경로를 입력하세요.";
    msg.className = "error";
    return;
  }
  if (mode === "MOVE" && !confirm(
    "이동 방식으로 가져올까요?\n\n" +
    `${sourcePath}\n\n` +
    "이 폴더의 사진·동영상이 저장소로 옮겨집니다. 원본 폴더에서는 사라집니다.\n" +
    "(이미 저장소에 있는 중복 파일은 원본 자리에 그대로 남습니다)"
  )) return;

  msg.textContent = "";
  msg.className = "";
  try {
    const response = await fetch("/api/v1/admin/import", {
      method: "POST",
      credentials: "same-origin",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ sourcePath, mode }),
    });
    if (response.status === 409) {
      msg.textContent = "이미 임포트가 진행 중입니다.";
      msg.className = "warn";
      startImportPolling();
      return;
    }
    if (!response.ok) {
      // 400이면 서버 검증 메시지(경로 오류 등)를 그대로 보여준다
      let detail = `HTTP ${response.status}`;
      try { detail = (await response.json()).error || detail; } catch (_) {}
      msg.textContent = detail;
      msg.className = "error";
      return;
    }
    startImportPolling();
  } catch (e) {
    msg.textContent = "임포트 시작 요청에 실패했습니다.";
    msg.className = "error";
  }
}

$("imp-scan").addEventListener("click", () => requestImport("SCAN"));
$("imp-start").addEventListener("click", () => {
  const mode = document.querySelector('input[name="imp-mode"]:checked').value;
  requestImport(mode);
});
$("imp-stop").addEventListener("click", async () => {
  $("imp-stop").disabled = true;
  try {
    await api("/api/v1/admin/import/stop", { method: "POST" });
  } finally {
    $("imp-stop").disabled = false;
  }
});

// ── 브라우저 뒤로 가기 ────────────────────────────────
// 뒤로 가기가 페이지 이탈이 되지 않도록 가드 히스토리 항목을 하나 유지한다.
// 뒤로 가기 = 열린 레이어를 ESC처럼 한 겹 닫기 → 하위 메뉴면 상위 메뉴로 →
// 타임라인 최상위에서만 실제로 페이지를 떠난다.
function handleBack() {
  if (!$("album-picker").classList.contains("hidden")) { closeAlbumPicker(); return true; }
  if (!$("person-picker").classList.contains("hidden")) { closePersonPicker(); return true; }
  if (state.lightboxIndex !== null) { closeLightbox(); return true; }
  if (dayViewerOpen) { closeDayViewer(); return true; }
  if (state.selecting) { cancelSelection(); return true; }
  if (state.mergeSource !== null) { cancelMerge(); return true; }
  if (state.view === "map" && state.mapBounds) { closeMapPanel(); return true; }
  if (state.view === "person") { switchView("people"); return true; }
  if (state.view === "album") { switchView("albums"); return true; }
  if (state.view !== "timeline") { switchView("timeline"); return true; }
  return false;
}

history.replaceState({ app: true }, "");
history.pushState({ guard: true }, "");
window.addEventListener("popstate", () => {
  if (handleBack()) {
    history.pushState({ guard: true }, ""); // 가드 복원 — 다음 뒤로 가기도 우리가 받는다
  } else {
    history.back(); // 더 닫을 게 없으면 진짜 이탈
  }
});

// ── 시작 ──────────────────────────────────────────────
async function loadDevices() {
  try {
    const devices = await (await api("/api/v1/devices")).json();
    state.devices = Object.fromEntries(devices.map((d) => [d.id, d.name]));
  } catch (e) {
    console.error("loadDevices failed", e);
  }
}

function boot() {
  buildScrubber();
  loadDevices();
  switchView("timeline");
}

(async function init() {
  try {
    await api("/api/v1/auth/check");
    boot();
  } catch (_) {
    // 401 → showLogin()이 이미 호출됨
  }
})();
