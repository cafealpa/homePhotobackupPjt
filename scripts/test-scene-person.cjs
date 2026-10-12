const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.join(__dirname, '../server/src/main/resources/static/scenes.js'), 'utf8');

function setup() {
  const elements = new Map();
  const requests = [];
  const context = vm.createContext({
    document: { hidden: false },
    $: id => {
      if (!elements.has(id)) elements.set(id, { open: false, replaceChildren(...children) { this.children = children; } });
      return elements.get(id);
    },
    Option: function(text, value) { this.text = text; this.value = value; },
    clearTimeout() {}, setTimeout() {},
    api: async url => {
      requests.push(url);
      if (url.endsWith('/status')) return { worker: {}, counts: { total: 1, completed: 1, pending: 0, running: 0, failed: 0 } };
      if (url.endsWith('/clusters')) return [{ clusterId: 0, name: '민수' }];
      return { items: [], total: 0 };
    },
  });
  vm.runInContext('let peopleSignature = "", clusterId = "", timer, loading = false, generation = 0, enabled = false, editing = false, filter = "completed", page = 0, query = "케이크"; let rendered = 0; function render() { rendered++; }', context);
  vm.runInContext(source.slice(source.indexOf('function renderPeople('), source.indexOf('async function loadSettings(')), context);
  return { context, elements, requests };
}

test('인물 그룹 0도 검색 요청에 포함하고 해제하면 전체 검색한다', async () => {
  const { context, requests } = setup();
  await vm.runInContext('clusterId = "0"; refresh()', context);
  assert.ok(requests.some(url => url.includes('q=') && url.endsWith('&clusterId=0')));
  requests.length = 0;
  await vm.runInContext('clusterId = ""; refresh()', context);
  assert.ok(requests.some(url => url.includes('q=') && !url.includes('clusterId')));
});

test('인물 이름 변경을 반영하고 삭제된 선택을 전체로 바꾸지 않는다', () => {
  const { context, elements } = setup();
  vm.runInContext('clusterId = "0"; renderPeople([{clusterId:0,name:"민수"}]); renderPeople([{clusterId:0,name:"지우"}]);', context);
  assert.match(elements.get('person').children[1].text, /지우/);
  vm.runInContext('renderPeople([])', context);
  assert.equal(elements.get('person').value, '0');
  assert.match(elements.get('person').children[1].text, /삭제되거나 병합됨/);
});

test('이전 검색 응답은 인물 선택 변경 후 결과를 덮어쓰지 않는다', async () => {
  const { context } = setup();
  await vm.runInContext('const pendingRefresh = refresh(); generation++; pendingRefresh;', context);
  assert.equal(vm.runInContext('rendered', context), 0);
});
