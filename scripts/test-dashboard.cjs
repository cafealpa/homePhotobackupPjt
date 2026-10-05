const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function dashboard() {
  const elements = new Map();
  const element = () => ({
    children: [], textContent: '', innerHTML: '', className: '',
    classList: { add() {}, remove() {}, contains() { return true; } },
    addEventListener() {}, querySelectorAll() { return []; },
    append(...children) { this.children.push(...children); },
    replaceChildren(...children) { this.children = children; },
  });
  const context = vm.createContext({
    document: {
      getElementById(id) { if (!elements.has(id)) elements.set(id, element()); return elements.get(id); },
      querySelectorAll() { return []; }, createElement: element,
      createTextNode(text) { return { textContent: text }; }, addEventListener() {},
    },
    Intl, AbortSignal, setInterval() {}, addEventListener() {},
    fetch: () => new Promise(() => {}),
  });
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../server/src/main/resources/static/dashboard.js'), 'utf8'), context);
  const run = (code) => vm.runInContext(code, context);
  run(`state.summary = { jobs: [] }; state.incoming = {count:0, bytes:0, items:[]};
    state.capacity = {accepting:true, usableBytes:1024, reservedBytes:0};`);
  return { elements, run };
}

test('완료율에 실패와 대기를 포함하고 오류 건수를 따로 표시한다', () => {
  const { elements, run } = dashboard();
  run(`state.summary.jobs = [
    {jobType:'FACE', status:'DONE', count:6}, {jobType:'FACE', status:'FAILED', count:2},
    {jobType:'FACE', status:'PENDING', count:2}]; renderOperations();`);
  assert.match(elements.get('job-progress').children[1].innerHTML, /60.0%/);
  assert.match(elements.get('job-progress').children[0].innerHTML, /등록 작업 없음/);
  assert.equal(elements.get('operation-status').textContent, '확인 필요');
  assert.equal(elements.get('operation-issues').children[0].children[0].textContent, '얼굴 인식 실패 2건');
  assert.equal(run(`jobsNote([{status:'PENDING',count:2}])`), '대기·진행 중인 작업이 있어요');
});

test('조회 실패를 정상으로 표시하지 않고 파일명과 오류를 텍스트로 렌더링한다', () => {
  const { elements, run } = dashboard();
  run(`state.capacity = null; state.incoming.items = [
    {filename:'<img src=x onerror=alert(1)>', status:'LOST', lastError:'<script>error</script>'}
  ]; renderOperations();`);
  assert.equal(elements.get('operation-status').textContent, '일부 상태 확인 불가');
  const rows = elements.get('operation-issues').children;
  assert.equal(rows[1].children[0].innerHTML, '');
  assert.match(rows[1].children[0].textContent, /<img/);
  assert.equal(rows[1].children[1].textContent, '<script>error</script>');
});

test('일부 API 실패에도 성공한 상태는 갱신하고 중복 조회는 막는다', async () => {
  const { run } = dashboard();
  await run(`(async () => {
    api = async (path) => {
      if (path.includes('summary')) return {jobs:[]};
      if (path.includes('incoming')) throw new Error('network');
      return {accepting:true, usableBytes:1024, reservedBytes:0};
    };
    renderCards = () => {}; renderStorage = () => {};
    loadSeries = async () => {}; redrawCurrentChart = () => {};
    await loadAll();
  })()`);
  assert.equal(run('state.incoming'), null);
  assert.equal(run('state.capacity.accepting'), true);
  assert.equal(run('state.loading'), false);
  await run(`state.loading = true; api = () => { throw new Error('duplicate'); }; loadAll();`);
});
