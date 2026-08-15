"use strict";

// ── 상태 ──────────────────────────────────────────────
const state = {
  children: [],           // ChildDto 목록
  childId: null,
  months: [],             // 선택된 아이의 KnMonthDto 목록 (yearMonth 오름차순)
  yearMonth: null,
  flatImages: [],         // 현재 월의 사진 평탄 리스트 (라이트박스 탐색용) {assetId, caption}
  lightboxIndex: null,
};

const $ = (id) => document.getElementById(id);

// ── API ───────────────────────────────────────────────
// 로그인은 메인 페이지가 담당한다 (hp_auth 쿠키 path=/). 미인증이면 메인으로 보낸다.
async function api(path) {
  const response = await fetch(path, { credentials: "same-origin" });
  if (response.status === 401) {
    location.replace("/");
    throw new Error("unauthorized");
  }
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  return response.json();
}

// ── 부팅 ──────────────────────────────────────────────
async function boot() {
  state.children = await api("/api/v1/kidsnote/children");
  if (state.children.length === 0) {
    $("empty").classList.remove("hidden");
    return;
  }
  renderChildTabs();
  await selectChild(state.children[0].id);
}

function renderChildTabs() {
  const tabs = $("child-tabs");
  tabs.innerHTML = "";
  for (const child of state.children) {
    const btn = document.createElement("button");
    btn.className = "child-tab";
    btn.textContent = child.folderName;
    btn.title = `${child.childName} · 알림장 ${child.postCount}건 · 사진 ${child.imageCount}장`;
    btn.dataset.childId = child.id;
    btn.addEventListener("click", () => selectChild(child.id));
    tabs.appendChild(btn);
  }
}

async function selectChild(childId) {
  state.childId = childId;
  document.querySelectorAll(".child-tab").forEach((el) => {
    el.classList.toggle("active", Number(el.dataset.childId) === childId);
  });
  state.months = await api(`/api/v1/kidsnote/children/${childId}/months`);
  if (state.months.length === 0) {
    $("feed").innerHTML = "";
    $("year-select").innerHTML = "";
    $("month-chips").innerHTML = "";
    $("empty").classList.remove("hidden");
    return;
  }
  $("empty").classList.add("hidden");

  // 연도 드롭다운 (오름차순) — 기본 선택은 가장 최근 월
  const years = [...new Set(state.months.map((m) => m.yearMonth.slice(0, 4)))];
  const select = $("year-select");
  select.innerHTML = "";
  for (const year of years) {
    const option = document.createElement("option");
    option.value = year;
    option.textContent = `${year}년`;
    select.appendChild(option);
  }
  const latest = state.months[state.months.length - 1].yearMonth;
  select.value = latest.slice(0, 4);
  renderMonthChips(latest.slice(0, 4));
  await loadMonth(latest);
}

$("year-select").addEventListener("change", () => {
  const year = $("year-select").value;
  renderMonthChips(year);
  const firstOfYear = state.months.find((m) => m.yearMonth.startsWith(year));
  if (firstOfYear) loadMonth(firstOfYear.yearMonth);
});

function renderMonthChips(year) {
  const chips = $("month-chips");
  chips.innerHTML = "";
  for (const m of state.months.filter((m) => m.yearMonth.startsWith(year))) {
    const btn = document.createElement("button");
    btn.className = "month-chip";
    btn.dataset.yearMonth = m.yearMonth;
    btn.textContent = `${Number(m.yearMonth.slice(5))}월`;
    btn.title = `알림장 ${m.postCount}건 · 사진 ${m.imageCount}장`;
    btn.addEventListener("click", () => loadMonth(m.yearMonth));
    chips.appendChild(btn);
  }
}

// ── 월 피드 ───────────────────────────────────────────
async function loadMonth(yearMonth) {
  state.yearMonth = yearMonth;
  document.querySelectorAll(".month-chip").forEach((el) => {
    el.classList.toggle("active", el.dataset.yearMonth === yearMonth);
  });
  $("loading").classList.remove("hidden");
  try {
    const posts = await api(`/api/v1/kidsnote/children/${state.childId}/posts?yearMonth=${yearMonth}`);
    renderFeed(posts);
    window.scrollTo(0, 0);
  } finally {
    $("loading").classList.add("hidden");
  }
}

// ── 썸네일 재시도 ─────────────────────────────────────
// 대량 임포트 직후엔 썸네일 생성이 큐에 밀려 /thumb가 한동안 404를 반환한다.
// 깨진 아이콘 대신 플레이스홀더를 보여주고, 생성될 때까지 점점 간격을 늘려 재시도한다.
const THUMB_PLACEHOLDER = "data:image/svg+xml," + encodeURIComponent(
  '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 4 4">' +
  '<rect width="4" height="4" fill="#222"/>' +
  '<text x="2" y="2.6" font-size="1.6" text-anchor="middle" fill="#555">⏳</text></svg>'
);
const MAX_THUMB_RETRIES = 40; // 최대 약 8분간 재시도

function attachThumbRetry(img, url) {
  let attempts = 0;
  img.addEventListener("error", () => {
    img.src = THUMB_PLACEHOLDER;
    if (attempts >= MAX_THUMB_RETRIES) return;
    attempts++;
    const delay = Math.min(3000 * attempts, 15000);
    setTimeout(() => {
      if (!img.isConnected) return; // 월 이동 등으로 화면에서 사라졌으면 중단
      img.src = `${url}&r=${attempts}`; // 캐시 우회용 파라미터
    }, delay);
  });
}

const WEEKDAYS = ["일", "월", "화", "수", "목", "금", "토"];

function formatDate(dateWritten) {
  const [y, m, d] = dateWritten.split("-").map(Number);
  const weekday = WEEKDAYS[new Date(y, m - 1, d).getDay()];
  return `${y}년 ${m}월 ${d}일 (${weekday})`;
}

/** 작성자명이 엄마/아빠로 끝나면 가족 글, 아니면 선생님 글 */
const isFamily = (authorName) => /(엄마|아빠)$/.test(authorName.trim());

function renderFeed(posts) {
  const feed = $("feed");
  feed.innerHTML = "";
  state.flatImages = [];

  feed.appendChild(monthNav());

  let currentDate = null;
  for (const post of posts) {
    if (post.dateWritten !== currentDate) {
      currentDate = post.dateWritten;
      const heading = document.createElement("h2");
      heading.className = "day-heading";
      heading.textContent = formatDate(currentDate);
      feed.appendChild(heading);
    }
    feed.appendChild(renderPost(post));
  }

  if (posts.length === 0) {
    const none = document.createElement("p");
    none.className = "no-posts";
    none.textContent = "이 달에는 알림장이 없습니다.";
    feed.appendChild(none);
  }

  feed.appendChild(monthNav());
}

function renderPost(post) {
  const card = document.createElement("article");
  card.className = "post";

  const header = document.createElement("div");
  header.className = "post-header";
  const badge = document.createElement("span");
  const family = isFamily(post.authorName);
  badge.className = `author-badge ${family ? "family" : "teacher"}`;
  badge.textContent = family ? "가족" : "선생님";
  header.appendChild(badge);
  const author = document.createElement("span");
  author.className = "author-name";
  author.textContent = post.authorName;
  header.appendChild(author);
  const time = document.createElement("span");
  time.className = "post-time";
  const created = new Date(post.createdAt); // UTC → 브라우저 로컬(KST)로 표시
  if (!Number.isNaN(created.getTime())) {
    time.textContent = `${String(created.getHours()).padStart(2, "0")}:${String(created.getMinutes()).padStart(2, "0")}`;
  }
  header.appendChild(time);
  card.appendChild(header);

  if (post.content.trim()) {
    const body = document.createElement("p");
    body.className = "post-content";
    body.textContent = post.content; // textContent — XSS 안전, 줄바꿈은 CSS pre-wrap이 보존
    card.appendChild(body);
  }

  if (post.images.length > 0) {
    const grid = document.createElement("div");
    grid.className = "photo-grid";
    for (const image of post.images) {
      const flatIndex = state.flatImages.length;
      state.flatImages.push({
        assetId: image.assetId,
        caption: `${formatDate(post.dateWritten)} · ${image.filename}`,
      });
      const img = document.createElement("img");
      const thumbUrl = `/api/v1/assets/${image.assetId}/thumb?size=400`;
      attachThumbRetry(img, thumbUrl);
      img.src = thumbUrl;
      img.alt = image.filename;
      img.loading = "lazy";
      img.addEventListener("click", () => openLightbox(flatIndex));
      grid.appendChild(img);
    }
    card.appendChild(grid);
  }

  if (post.video) {
    if (post.video.status === "DOWNLOADED" && post.video.assetId != null) {
      const video = document.createElement("video");
      video.controls = true;
      video.preload = "none";
      video.src = `/api/v1/assets/${post.video.assetId}/file`;
      video.className = "post-video";
      card.appendChild(video);
    } else {
      const lost = document.createElement("div");
      lost.className = "video-lost";
      lost.innerHTML = `<svg class="icon"><use href="#i-video-off"/></svg> 영상 유실 — 원본이 CDN에서 만료되었습니다`;
      if (post.video.originalFileName) {
        const name = document.createElement("span");
        name.className = "video-lost-name";
        name.textContent = post.video.originalFileName;
        lost.appendChild(name);
      }
      card.appendChild(lost);
    }
  }

  return card;
}

// ── 이전/다음 월 이동 ─────────────────────────────────
function monthNav() {
  const nav = document.createElement("div");
  nav.className = "month-nav";
  const index = state.months.findIndex((m) => m.yearMonth === state.yearMonth);

  const prev = state.months[index - 1];
  const next = state.months[index + 1];
  for (const [target, label] of [
    [prev, prev ? `← ${prev.yearMonth}` : null],
    [next, next ? `${next.yearMonth} →` : null],
  ]) {
    const btn = document.createElement("button");
    if (target) {
      btn.textContent = label;
      btn.addEventListener("click", () => {
        $("year-select").value = target.yearMonth.slice(0, 4);
        renderMonthChips(target.yearMonth.slice(0, 4));
        loadMonth(target.yearMonth);
      });
    } else {
      btn.disabled = true;
      btn.textContent = "·";
    }
    nav.appendChild(btn);
  }
  return nav;
}

// ── 라이트박스 (읽기 전용) ────────────────────────────
function openLightbox(index) {
  state.lightboxIndex = index;
  $("lightbox").classList.remove("hidden");
  renderLightbox();
}

function closeLightbox() {
  state.lightboxIndex = null;
  $("lb-content").innerHTML = "";
  $("lightbox").classList.add("hidden");
}

function moveLightbox(delta) {
  if (state.lightboxIndex === null) return;
  const next = state.lightboxIndex + delta;
  if (next < 0 || next >= state.flatImages.length) return;
  state.lightboxIndex = next;
  renderLightbox();
}

function renderLightbox() {
  const item = state.flatImages[state.lightboxIndex];
  if (!item) return;
  const content = $("lb-content");
  content.innerHTML = "";
  const img = new Image();
  const thumbUrl = `/api/v1/assets/${item.assetId}/thumb?size=1600`;
  attachThumbRetry(img, thumbUrl);
  img.src = thumbUrl;
  img.alt = item.caption;
  content.appendChild(img);
  $("lb-caption").textContent = `${item.caption}  ·  ${state.lightboxIndex + 1}/${state.flatImages.length}`;
}

$("lb-close").addEventListener("click", closeLightbox);
$("lb-prev").addEventListener("click", () => moveLightbox(-1));
$("lb-next").addEventListener("click", () => moveLightbox(1));
$("lightbox").addEventListener("click", (e) => {
  if (e.target === $("lightbox")) closeLightbox();
});

// 마우스 휠: 아래로 = 다음, 위로 = 이전 (메인 뷰어와 동일한 동작)
let wheelAccum = 0;
let lastWheelNav = 0;
$("lightbox").addEventListener("wheel", (e) => {
  if (state.lightboxIndex === null) return;
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

document.addEventListener("keydown", (e) => {
  if (state.lightboxIndex === null) return;
  if (e.key === "Escape") closeLightbox();
  else if (e.key === "ArrowLeft") moveLightbox(-1);
  else if (e.key === "ArrowRight") moveLightbox(1);
});

boot().catch((e) => {
  console.error(e);
  $("empty").classList.remove("hidden");
});
