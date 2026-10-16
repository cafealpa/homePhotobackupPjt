const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const source=fs.readFileSync('server/src/main/resources/static/app.js','utf8');
function setup(input, confirmValue=true, fail=false) {
  const button={disabled:false,textContent:'인물 일괄 해제',addEventListener(){}};
  const calls=[], alerts=[];
  const ctx=vm.createContext({prompt:()=>input,confirm:()=>confirmValue,alert:m=>alerts.push(m),$:()=>button,state:{view:'people'},cancelMerge(){},loadPeople:async()=>{},api:async(url,opts)=>{
    calls.push([url,opts]);
    if(fail) throw new Error('연결 실패');
    return {json:async()=>opts?{releasedClusters:2}:[{faceCount:1},{faceCount:1},{faceCount:2}]};
  }});
  vm.runInContext(source.slice(source.indexOf('let bulkReleaseBusy'),source.indexOf('function startMerge')),ctx);
  return {run:()=>vm.runInContext('bulkReleasePeople()',ctx),calls,alerts,button};
}
test('취소와 잘못된 장수는 요청하지 않는다',async()=>{for(const input of [null,'','0','-1','1.5','abc','2147483648']){const t=setup(input);await t.run();assert.equal(t.calls.length,0);}});
test('최종 취소는 쓰지 않고 버튼을 복구한다',async()=>{const t=setup('1',false);await t.run();assert.equal(t.calls.length,1);assert.equal(t.button.disabled,false);});
test('조건은 한 번의 일괄 요청으로 보내고 중복 클릭을 막는다',async()=>{const t=setup('1');await Promise.all([t.run(),t.run()]);assert.equal(t.calls.length,2);assert.deepEqual(JSON.parse(t.calls[1][1].body),{maxPhotoCount:1});assert.equal(t.button.disabled,false);assert.match(t.alerts[0],/2명/);});
test('실패 시 안내하고 재시도를 허용한다',async()=>{const t=setup('1',true,true);await t.run();assert.match(t.alerts[0],/연결 실패/);assert.equal(t.button.disabled,false);await t.run();assert.equal(t.calls.length,2);});
