const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

test('얼굴 상태에서 썸네일 생성 대기·생성 실패·파일 미준비를 분석 실패와 구분한다', () => {
  const source = fs.readFileSync(path.join(__dirname, '../server/src/main/resources/static/app.js'), 'utf8');
  const context = vm.createContext({});
  vm.runInContext(source.slice(source.indexOf('function faceProgressText('), source.indexOf('let faceStatusTimer')), context);
  context.status = { enabled: true, running: false, completed: 5, pending: 6, failed: 0,
    waitingThumbnail: 2, thumbnailFailed: 1, thumbnailMissing: 3 };
  const text = vm.runInContext('faceProgressText(status)', context);
  assert.match(text, /실패 0장/);
  assert.match(text, /썸네일 생성 대기 2장/);
  assert.match(text, /썸네일 생성 실패로 대기 1장/);
  assert.match(text, /1600px 파일 확인 대기 3장 \(60초 간격\)/);
});
