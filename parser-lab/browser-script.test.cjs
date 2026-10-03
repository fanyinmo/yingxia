const {readFileSync} = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const {test} = require('node:test');
const source = readFileSync('app/src/main/java/com/local/douyinsaver/PublicPageScript.kt', 'utf8');
const id = '7692053151259682555';
const script = source.match(/return """([\s\S]*?)"""/)[1].replaceAll('$id', id);
function run(data, videos = []) {
  return JSON.parse(vm.runInNewContext(script, {
    window: {_ROUTER_DATA: data}, URL,
    location: {href: `https://www.iesdouyin.com/share/video/${id}/`},
    document: {readyState: 'complete', visibilityState: 'visible', title: 'Target',
      querySelector: () => null,
      querySelectorAll: selector => selector === 'video' ? videos : []},
  }, {timeout: 1000}));
}
function item(aweme_id) {
  return {aweme_id, desc: 'Target work', video: {width: 1920, height: 1080, duration: 83916,
    play_addr: {url_list: ['https://v26-web.douyinvod.com/video/test.mp4']}}};
}
test('uses exact public work metadata before a video element exists', () => {
  const result = run({loaderData: {page: {videoInfoRes: {item_list: [item(id)]}}}});
  assert.equal(result.owner, id);
  assert.equal(result.structured, true);
  assert.equal(result.duration, 83.916);
  assert.equal(result.width, 1920);
});
test('does not accept a recommendation with another id', () => {
  const result = run({item_list: [item('7689301063664454962')]});
  assert.equal(result.url, '');
});
test('finds target after unrelated recommendation data', () => {
  assert.equal(run({item_list: [item('7689301063664454962'), item(id)]}).owner, id);
});
test('does not accept an insecure structured media URL', () => {
  const entry = item(id);
  entry.video.play_addr.url_list = ['http://v26-web.douyinvod.com/video/test.mp4'];
  assert.equal(run({item_list:[entry]}).url, '');
});
test('empty mobile page yields no pretend result', () => {
  assert.equal(run({loaderData: {page: {itemId: id}}}).url, '');
});
