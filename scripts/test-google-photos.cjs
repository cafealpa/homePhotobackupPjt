const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

function googlePage() {
  const elements = new Map();
  const element = (tag) => ({
    tagName: tag, children: [], textContent: '', innerHTML: '', className: '',
    querySelector() { return null; }, addEventListener() {}, blur() {},
    append(...children) { this.children.push(...children); },
    replaceChildren(...children) { this.children = children; },
  });
  const context = vm.createContext({
    document: { createElement: element },
    $(id) { if (!elements.has(id)) elements.set(id, element('div')); return elements.get(id); },
    GOOGLE_STATUSES: { FAILED: '실패' }, Date,
    fetch: async () => context.response, showLogin() {},
    setTimeout() {}, clearTimeout() {}, state: { view: 'settings' }, googleTimer: null,
  });
  const source = fs.readFileSync(path.join(__dirname, '../server/src/main/resources/static/app.js'), 'utf8');
  vm.runInContext(source.slice(source.indexOf('let googleBusy = false;'), source.indexOf('$("google-refresh").addEventListener')), context);
  const run = (code) => vm.runInContext(code, context);
  return { context, elements, run };
}

test('Google 오류 본문과 줄바꿈을 HTML 실행 없이 게시 항목에 표시한다', () => {
  const { context, elements, run } = googlePage();
  const original = '{\n"error": "<script>fixture</script>"\n}';
  context.items = [{ assetId: 1, originalFilename: 'fixture.jpg', status: 'FAILED', attempts: 1, lastError: original }];
  run('renderGooglePublications(items)');
  const error = elements.get('google-publications').children[0].children.find(node => node.className === 'google-error');
  assert.equal(error.tagName, 'pre');
  assert.equal(error.textContent, original);
  assert.equal(error.innerHTML, '');
});

test('API 오류와 JSON이 아닌 오류 응답을 페이지 메시지에 그대로 전달한다', async () => {
  const { context, elements, run } = googlePage();
  run('loadGooglePhotos = async () => {}');
  for (const original of ['{\n"error": "Google permission failure"\n}', '<html>upstream error</html>\nOriginal reason']) {
    for (const body of [JSON.stringify({ error: original }), original]) {
      context.response = { status: 403, ok: false, text: async () => body };
      await run('runGoogleAction({ blur() {} }, () => googleAction("/album/organize"))');
      // 서버의 error 필드는 오류 메시지이고, 그 외 응답은 본문 그대로다.
      const expected = body === original && original.startsWith('{') ? 'Google permission failure' : original;
      assert.equal(elements.get('google-msg').textContent, expected);
      assert.equal(elements.get('google-msg').className, 'error');
      assert.equal(elements.get('google-msg').innerHTML, '');
    }
  }
});

test('본문이 없는 오류는 HTTP 상태를 표시하고 정상 응답은 유지한다', async () => {
  const { context, run } = googlePage();
  context.response = { status: 502, ok: false, text: async () => '' };
  await assert.rejects(run('googleAction("/album/organize")'), /HTTP 502/);
  context.response = { status: 200, ok: true, text: async () => '{"included":3}' };
  assert.equal((await run('googleAction("/album/organize")')).included, 3);
});
