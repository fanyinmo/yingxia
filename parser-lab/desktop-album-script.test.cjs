const {readFileSync} = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const {test} = require('node:test');

const source = readFileSync(path.join(__dirname,
  '../app/src/main/java/com/local/douyinsaver/DesktopAlbumScript.kt'), 'utf8');
const id = '7685772229787700580';
const otherId = '7689306999973377371';
const script = source.match(/return """([\s\S]*?)"""/)[1].replaceAll('$expectedId', id);
const poster = n => `https://p3-sign.douyinpic.com/tos-cn-i/photo-${n}.webp?signature=keep%2Bexact`;
const clip = n => `https://v26-web.douyinvod.com/video/clip-${n}.mp4?token=secret%2Bexact&x=&x=1`;
const music = 'https://sf3-cdn-tos.douyinstatic.com/obj/ies-music/bgm.mp3?token=audio-secret';
const photo = (n, extra = {}) => ({uri: `photo-key-${n}`, urlList: [poster(n)], width: 1080, height: 1440, ...extra});
const video = (n, extra = {}) => ({width: 720, height: 960, duration: 2400,
  videoId: `photo-media-clip-${n}`, playAddr: [{src: clip(n)}], ...extra});
const work = (images = [photo(0)], extra = {}) => ({awemeId: id, desc: 'Target album', images, ...extra});
const flightScript = chunk => ({textContent: `self.__pace_f.push(${JSON.stringify([1, chunk])});`});
const flightRecord = (value, key = '0') => `${key}:${JSON.stringify(value)}\n`;
const rect = visible => ({width: visible ? 420 : 0, height: visible ? 360 : 0});
const gate = (text, visible = true) => ({innerText: text, getBoundingClientRect: () => rect(visible)});

function run(options = {}) {
  const scripts = options.scripts || [];
  const slides = options.slides || [];
  const body = options.body || {innerText: ''};
  const win = {...options.window};
  if (options.router !== undefined) win._ROUTER_DATA = options.router;
  if (options.render !== undefined) win.RENDER_DATA = options.render;
  win.getComputedStyle = element => element.style || {display: 'block', visibility: 'visible', opacity: '1'};
  const selectors = selector => {
    if (selector === 'script') return scripts;
    if (selector.startsWith('.dySwiperSlide,.note-detail-container')) return options.seeds || [];
    if (selector === '.dySwiperSlide,[data-image-uri],[data-image-id]') return slides;
    if (selector.startsWith('[id*="captcha"]')) return options.captcha || [];
    if (selector.startsWith('dialog,')) return options.loginModals || [];
    if (selector === 'video') return options.globalPlayers || [];
    return [];
  };
  const context = {window: win, document: {readyState: options.state || 'complete',
    title: 'Page title', body, querySelectorAll: selectors},
    location: {href: options.page || `https://www.douyin.com/note/${id}`}, URL};
  const output = vm.runInNewContext(script.replaceAll('$allowVideoLiteral', options.allowVideo === true ? 'true' : 'false'), context, {timeout: 1500});
  return JSON.parse(output);
}

test('ordinary video extraction is opt in and never changes the default album reader', () => {
  const target = {awemeId: id, desc: 'Target video', video: video(0, {duration: 90000})};
  const router = {target, recommendation: {awemeId: otherId, video: video(9)}};
  const defaults = run({router});
  assert.equal(defaults.status, 'unavailable');
  assert.deepEqual(defaults.videoCandidates, []);
  const result = run({router, allowVideo: true, page: `https://www.douyin.com/video/${id}`});
  assert.equal(result.status, 'candidate');
  assert.equal(result.owner, id);
  assert.equal(result.videoCandidates.length, 1);
  assert.equal(result.videoCandidates[0].owner, id);
  assert.equal(result.videoCandidates[0].durationSeconds, 90);
  assert.deepEqual(result.videoCandidates[0].playUrls, [clip(0)]);
  assert.deepEqual(result.videoCandidates[0].displayPlaybackUrls, [clip(0)]);
  assert.ok(!JSON.stringify(result.videoCandidates).includes(clip(9)));
});

test('RSC video references export original signed CDN addresses of the exact work', () => {
  const stream = flightRecord({aweme_id: id, desc: 'Referenced target', video: '$a'}) +
    flightRecord(video(0, {duration: 20000, originCover: {urlList: [poster(0)]}}), 'a') +
    flightRecord({awemeId: otherId, video: video(9)}, 'b');
  const result = run({scripts: [flightScript(stream)], allowVideo: true, page: `https://www.douyin.com/video/${id}`});
  assert.equal(result.status, 'candidate');
  assert.equal(result.videoCandidates.length, 1);
  assert.deepEqual(result.videoCandidates[0].coverUrls, [poster(0)]);
  assert.deepEqual(result.videoCandidates[0].playUrls, [clip(0)]);
  assert.ok(result.videoCandidates[0].playUrls[0].includes('token=secret%2Bexact&x=&x=1'));
});

function crowdedRscTree(target) {
  const value = Object.fromEntries(Array.from({length: 60}, (_, n) =>
    [`irrelevant${n}`, Array.from({length: 250}, (_, m) => `ordinary text ${n}:${m}`)]));
  value.delayed = {exactTarget: target};
  return value;
}

test('owned RSC payload beyond 256 unrelated keys is prioritized without widening generic scans', () => {
  const wide = Object.fromEntries(Array.from({length: 300}, (_, n) => [`irrelevant${n}`, `ordinary text ${n}`]));
  wide.exactTarget = {awemeId: id, video: video(0)};
  const result = run({scripts: [flightScript(flightRecord(wide))], render: {awemeId: id}, allowVideo: true});
  assert.equal(result.status, 'candidate');
  assert.equal(result.owner, id);
  assert.deepEqual(result.videoCandidates.map(x => x.playUrls), [[clip(0)]]);
  assert.equal(result.stats.ownedRscRoots, 1);
  assert.equal(result.stats.limits.objectKeys, 1);
  assert.equal(result.stats.truncated, true);
  assert.ok(result.stats.visitedNodes < 12000);
});

test('ordinary strings cannot starve an exact owned RSC payload behind a crowded graph', () => {
  const result = run({scripts: [flightScript(flightRecord(crowdedRscTree({awemeId: id, video: video(0)})))],
    render: {awemeId: id}, allowVideo: true});
  assert.equal(result.status, 'candidate');
  assert.deepEqual(result.videoCandidates.map(x => x.playUrls), [[clip(0)]]);
  assert.equal(result.stats.ownedRscRoots, 1);
  assert.ok(result.stats.limits.queueNodes > 0);
  assert.ok(result.stats.visitedNodes < 12000);
});

test('prioritized RSC owner resolves only forward references from its own complete stream', () => {
  const stream = flightRecord(crowdedRscTree({awemeId: id, video: '$La'})) +
    flightRecord(video(0), 'a') + flightRecord({awemeId: otherId, video: video(9)}, 'b');
  const split = stream.indexOf('ordinary text 15:') + 11;
  const result = run({scripts: [flightScript(stream.slice(0, split)), flightScript(stream.slice(split))], allowVideo: true,
    window: {__pace_f: [[1, flightRecord(video(9), 'a')]]}});
  assert.equal(result.status, 'candidate');
  assert.equal(result.videoCandidates.length, 1);
  assert.equal(result.videoCandidates[0].owner, id);
  assert.deepEqual(result.videoCandidates[0].playUrls, [clip(0)]);
  assert.ok(!JSON.stringify(result.videoCandidates).includes(clip(9)));
});

test('bounded RSC priority never promotes other owners, conflicting ids, numeric ids or route placeholders', () => {
  const invalid = [
    {awemeId: otherId, video: video(9)},
    {awemeId: id, aweme_id: otherId, video: video(0)},
    {awemeId: Number(id), video: video(0)},
    {awemeId: id},
    {text: `awemeId=${id}; video=${clip(0)}`},
    {video: video(0)},
  ];
  for (const target of invalid) {
    const result = run({scripts: [flightScript(flightRecord(crowdedRscTree(target)))],
      render: {awemeId: id}, allowVideo: true, globalPlayers: [{src: clip(0), videoWidth: 720, videoHeight: 960, duration: 2.4}]});
    assert.equal(result.status, 'unavailable');
    assert.equal(result.stats.ownedRscRoots, 0);
    assert.deepEqual(result.videoCandidates, []);
  }
});

test('owned RSC priority shares the existing 512-root budget rather than creating a larger side queue', () => {
  const wide = Object.fromEntries(Array.from({length: 300}, (_, n) => [`irrelevant${n}`, n]));
  wide.exactTarget = {awemeId: id, video: video(0)};
  const stream = flightRecord(wide) + Array.from({length: 511}, (_, n) =>
    flightRecord({irrelevant: `ordinary text ${n}`}, (n + 1).toString(16))).join('');
  const result = run({scripts: [flightScript(stream)], allowVideo: true});
  assert.equal(result.status, 'candidate');
  assert.deepEqual(result.videoCandidates.map(x => x.playUrls), [[clip(0)]]);
  assert.equal(result.stats.rscRecords, 512);
  assert.equal(result.stats.ownedRscRoots, 1);
  assert.equal(result.stats.jsonRoots, 512);
  assert.ok(result.stats.limits.roots > 0);
  assert.ok(result.stats.visitedNodes < 12000);
});

test('owned RSC capture has a fixed cap and reports omitted payloads without relaxing media metadata', () => {
  const invalid = Array.from({length: 20}, () => ({awemeId: id, video: {width: 720, height: 960, duration: 0}}));
  const result = run({scripts: [flightScript(flightRecord(crowdedRscTree(invalid)))], allowVideo: true});
  assert.equal(result.status, 'unavailable');
  assert.deepEqual(result.videoCandidates, []);
  assert.equal(result.stats.ownedRscRoots, 16);
  assert.equal(result.stats.limits.ownedRscRoots, 4);
  assert.equal(result.stats.truncated, true);
  assert.ok(result.stats.jsonRoots <= 512);
});

test('escaped or percent-encoded RSC JSON captures exact payloads with unchanged signed URLs', () => {
  const wide = Object.fromEntries(Array.from({length: 300}, (_, n) => [`irrelevant${n}`, n]));
  wide.exactTarget = {awemeId: id, video: video(0)};
  for (const encoded of [JSON.stringify(wide), encodeURIComponent(JSON.stringify(wide))]) {
    const stream = flightRecord(encoded);
    const split = Math.floor(stream.length / 2);
    const result = run({scripts: [flightScript(stream.slice(0, split)), flightScript(stream.slice(split))], allowVideo: true});
    assert.equal(result.status, 'candidate');
    assert.equal(result.stats.ownedRscRoots, 1);
    assert.deepEqual(result.videoCandidates.map(x => x.playUrls), [[clip(0)]]);
  }
});

test('deep valid RSC JSON keeps native decoding and bounded traversal if the optional owner reviver reaches its stack limit', () => {
  const stream = `0:{"awemeId":${JSON.stringify(id)},"video":${JSON.stringify(video(0))},"irrelevant":` +
    '{"child":'.repeat(5000) + 'null' + '}'.repeat(5000) + '}\n';
  const result = run({scripts: [flightScript(stream)], allowVideo: true});
  assert.equal(result.status, 'candidate');
  assert.deepEqual(result.videoCandidates.map(x => x.playUrls), [[clip(0)]]);
  assert.equal(result.stats.ownedRscRoots, 0);
  assert.ok(result.stats.limits.ownedRscDecode > 0);
  assert.equal(result.stats.truncated, true);
  assert.ok(result.stats.visitedNodes < 12000);
});

test('video fallback cannot take a note soundtrack or recommendation as an ordinary video', () => {
  const shapes = [work([photo(0)], {video: video(0)}),
    {awemeId: id, awemeType: 68, video: video(0)},
    {awemeId: id, imageCount: 1, video: video(0)},
    {awemeId: id, imagePostInfo: {imageCount: 1}, video: video(0)},
    {awemeId: otherId, video: video(9)},
    {awemeId: Number(id), video: video(0)},
    {awemeId: id, aweme_id: otherId, video: video(0)}];
  for (const target of shapes) {
    const result = run({router: {target}, allowVideo: true, page: `https://www.douyin.com/video/${id}`});
    assert.deepEqual(result.videoCandidates, []);
  }
});

test('an explicit verification gate prevents the video candidate from becoming ready', () => {
  const result = run({router: {target: {awemeId: id, video: video(0)}}, allowVideo: true,
    page: `https://www.douyin.com/video/${id}`, captcha: [gate('请完成验证')]});
  assert.equal(result.status, 'needs_verification');
  assert.equal(result.stats.gate, 'captcha');
  assert.equal(result.videoCandidates.length, 1);
});

test('missing video geometry or duration does not produce a ready fallback candidate', () => {
  for (const invalid of [video(0, {width: 0}), video(0, {height: 0}), video(0, {duration: 0}),
    video(0, {duration: '2400'}), video(0, {width: 20000})]) {
    const result = run({router: {target: {awemeId: id, video: invalid}}, allowVideo: true});
    assert.deepEqual(result.videoCandidates, []);
    assert.equal(result.status, 'unavailable');
  }
});

test('a generic desktop player and route id cannot fabricate a structured video fallback', () => {
  const result = run({allowVideo: true, page: `https://www.douyin.com/video/${id}`,
    router: {recommendation: {awemeId: otherId, video: video(9)}},
    globalPlayers: [{src: clip(0), currentSrc: clip(0), videoWidth: 1920, videoHeight: 1080, duration: 90}]});
  assert.equal(result.status, 'unavailable');
  assert.deepEqual(result.videoCandidates, []);
  assert.equal(result.owner, '');
});

test('RSC chunks split in the middle of escaped JSON yield the full ordered album', () => {
  const entry = work([photo(0, {video: video(0)}), photo(1), photo(2, {video: video(2)})],
    {desc: '引号"和反斜杠\\以及换行\n标题'});
  const stream = flightRecord({tree: entry});
  const splits = [13, stream.indexOf('signature') + 5, stream.length - 17];
  const chunks = [stream.slice(0, splits[0]), stream.slice(splits[0], splits[1]),
    stream.slice(splits[1], splits[2]), stream.slice(splits[2])];
  const result = run({scripts: chunks.map(flightScript)});
  assert.equal(result.owner, id);
  assert.equal(result.status, 'candidate');
  assert.equal(result.title, entry.desc);
  assert.deepEqual(result.images.map(x => x.sourceIndex), [0, 1, 2]);
  assert.deepEqual(result.images.map(x => x.kind), ['DYNAMIC', 'STATIC', 'DYNAMIC']);
  assert.deepEqual(result.images[0].motion.playUrls, [clip(0)]);
  assert.equal(result.stats.rscChunks, 4);
  assert.equal(result.stats.rscRecords, 1);
});

test('multiple RSC records and forward references resolve the target image array', () => {
  const stream = flightRecord(['$', 'div', null, {children: '$L2'}]) +
    flightRecord({awemeId: id, desc: 'Referenced album', images: '$a'}, '2') +
    flightRecord(['$b', '$c'], 'a') + flightRecord(photo(0, {video: '$d'}), 'b') +
    flightRecord(photo(1), 'c') + flightRecord(video(0), 'd');
  const result = run({scripts: [flightScript(stream.slice(0, 120)), flightScript(stream.slice(120))]});
  assert.equal(result.images.length, 2);
  assert.deepEqual(result.images[0].motion.playUrls, [clip(0)]);
  assert.equal(result.stats.rscRecords, 6);
});

test('multiple push calls in one script handle tags without evaluating a flight expression', () => {
  const a = JSON.stringify([1, '1:I["module",["chunk"]]\n']);
  const b = JSON.stringify([1, flightRecord(work([photo(0, {video: video(0)})]), '2')]);
  const result = run({scripts: [{textContent: `self.__pace_f.push(${a}); self.__pace_f.push(${b});`}]});
  assert.equal(result.images.length, 1);
  assert.equal(result.stats.rscChunks, 2);
});

test('an escaped JSON string record can carry the exact work data', () => {
  const encoded = JSON.stringify(JSON.stringify(work([photo(0, {video: video(0)})])));
  const result = run({scripts: [flightScript(`0:${encoded}\n`)]});
  assert.equal(result.owner, id);
  assert.equal(result.images[0].motion.durationSeconds, 2.4);
});

test('RSC state after hydration is usable without script text', () => {
  const result = run({window: {__pace_f: [[0], [1, flightRecord(work([photo(0, {video: video(0)})]))]]}});
  assert.equal(result.images[0].motion.width, 720);
  assert.equal(result.stats.provenance, 'rsc');
});

test('RENDER_DATA percent encoding and router assignments are parsed as JSON', () => {
  const encoded = encodeURIComponent(JSON.stringify({aweme: work([photo(0, {video: video(0)})])}));
  const result = run({scripts: [{id: 'RENDER_DATA', textContent: encoded}]});
  assert.equal(result.stats.provenance, 'render');
  assert.equal(result.images[0].motion.height, 960);
  const assigned = run({scripts: [{textContent: `window._ROUTER_DATA = ${JSON.stringify({aweme_detail: work()})};`}]});
  assert.equal(assigned.stats.provenance, 'router');
  assert.equal(assigned.images.length, 1);
});

test('camelCase address objects, arrays, strings and bitRateList preserve signed addresses', () => {
  const download = `${clip(0)}&watermark=1`;
  const rate = `${clip(0)}&rate=high`;
  const entry = work([photo(0, {downloadUrlList: [{src: `${poster(0)}&original=1`}], video: video(0, {
    playAddr: [{src: clip(0)}, `${clip(0)}&backup=1`], downloadAddr: {src: download},
    bitRateList: [{playAddr: {src: rate}, downloadAddr: [download]}],
  })})]);
  const result = run({router: {aweme_detail: entry}});
  const image = result.images[0];
  assert.deepEqual(image.motion.playUrls, [clip(0), `${clip(0)}&backup=1`, rate]);
  assert.deepEqual(image.motion.displayPlaybackUrls, [clip(0), `${clip(0)}&backup=1`, rate]);
  assert.deepEqual(image.motion.downloadUrls, [download]);
  assert.deepEqual(image.downloadUrls, [`${poster(0)}&original=1`]);
  assert.deepEqual(image.motion.mediaIds, ['photo-media-clip-0']);
  assert.equal(image.urls[0], `${poster(0)}&original=1`);
});

test('snake_case API and image_post_info keep full image order and codec alternatives', () => {
  const entry = {aweme_id: id, desc: 'Snake album', image_post_info: {images: [
    {uri: 'first', url_list: [poster(0)], clip_type: 2},
    {uri: 'second', url_list: [poster(1)], clip_type: 3, video: {duration: 1500,
      play_addr: {uri: 'own-second-video-id', url_list: [clip(1)]},
      bit_rate: [{play_addr_h265: {url_list: [`${clip(1)}&codec=h265`]}}]}},
  ]}};
  const result = run({router: {aweme_detail: entry}});
  assert.deepEqual(result.images.map(x => x.imageKey), ['first', 'second']);
  assert.deepEqual(result.images[1].motion.playUrls, [clip(1), `${clip(1)}&codec=h265`]);
  assert.equal(result.images[1].motion.durationSeconds, 1.5);
});

test('a photo-owned higher-resolution camel bitrate precedes its low-resolution root without URL edits', () => {
  const low = clip('low'), high = clip('high'), backup = `${high}&cdn=backup`;
  const result = run({router: work([photo(0, {clipType: 5, video: video(0, {
    width: 480, height: 640, fps: 60, bitRate: 8000000, playAddr: [{src: low}],
    bitRateList: [{width: 1080, height: 1440, fps: 30, bitRate: 2400000,
      dataSize: 2000000, playAddr: [{src: high}, {src: backup}]}],
  })})])});
  const motion = result.images[0].motion;
  assert.deepEqual(motion.playUrls, [high, backup, low]);
  assert.deepEqual(motion.displayPlaybackUrls, [high, backup, low]);
  assert.equal(motion.width, 1080);
  assert.equal(motion.height, 1440);
  assert.equal(motion.durationSeconds, 2.4);
});

test('snake photo bitrate dimensions in play_addr and numeric FPS rank only its own variants', () => {
  const low = clip('snake-low'), high = clip('snake-high');
  const download = `${clip('snake-download')}&watermark=1`;
  const result = run({router: work([photo(0, {clip_type: 3, video: {
    width: 480, height: 640, duration: 3000, play_addr: {url_list: [low]},
    bit_rate: [{FPS: 60, bit_rate: 3200000, play_addr: {
      width: 1080, height: 1440, data_size: 2200000, url_list: [high]},
      download_addr: {url_list: [download]}}],
  }})])});
  assert.deepEqual(result.images[0].motion.playUrls, [high, low]);
  assert.deepEqual(result.images[0].motion.downloadUrls, [download]);
  assert.deepEqual(result.images[0].motion.displayPlaybackUrls, []);
  assert.equal(result.images[0].motion.width, 1080);
  assert.equal(result.images[0].motion.height, 1440);
});

test('same-resolution photo variants prefer frame rate then bitrate then size with stable ties', () => {
  const root = clip('root-30'), fastLow = clip('60-low'), fastHigh = clip('60-high'),
    large = clip('60-high-large'), same = clip('same'), lowResolution = clip('small-fast');
  const result = run({router: work([photo(0, {video: video(0, {
    width: 1080, height: 1440, fps: 30, bitRate: 9000000, dataSize: 10000000,
    playAddr: [{src: root}], bitRateList: [
      {width: 480, height: 640, fps: 120, bitRate: 20000000, playAddr: {src: lowResolution}},
      {width: 1080, height: 1440, fps: 60, bitRate: 1000000, playAddr: {src: fastLow}},
      {width: 1080, height: 1440, fps: 60, bitRate: 2000000, dataSize: 1000000, playAddr: {src: fastHigh}},
      {width: 1080, height: 1440, fps: 60, bitRate: 2000000, dataSize: 2000000, playAddr: {src: large}},
      {width: 1080, height: 1440, fps: 60, bitRate: 2000000, dataSize: 2000000, playAddr: {src: same}},
    ],
  })})])});
  const expected = [large, same, fastHigh, fastLow, root, lowResolution];
  assert.deepEqual(result.images[0].motion.playUrls, expected);
  assert.deepEqual(result.images[0].motion.displayPlaybackUrls, expected);
});

test('unknown, invalid and string quality metadata retain source order rather than claiming higher quality', () => {
  const root = clip('unknown-root'), a = clip('unknown-a'), b = clip('unknown-b'), c = clip('unknown-c');
  const result = run({router: work([photo(0, {video: video(0, {
    width: 0, height: 0, playAddr: {src: root}, bitRateList: [
      {width: '3840', height: '2160', fps: '60', bitRate: '50000000', playAddr: {src: a}},
      {width: 32768, height: 32768, fps: 10000, bitRate: Infinity, dataSize: -1, playAddr: {src: b}},
      {width: NaN, height: 1080, fps: -60, bitRate: {}, dataSize: '2000000', playAddr: {src: c}},
    ],
  })})])});
  assert.deepEqual(result.images[0].motion.playUrls, [root, a, b, c]);
  assert.deepEqual(result.images[0].motion.displayPlaybackUrls, [root, a, b, c]);
  assert.equal(result.images[0].motion.width, 0);
  assert.equal(result.images[0].motion.height, 0);
});

test('high-quality variants never import another work or another photo into a target motion', () => {
  const own = clip('target-low'), ownHigh = clip('target-high'), other = clip('other-work-high'),
    second = clip('second-photo-high'), global = clip('global-high');
  const target = work([
    photo(0, {video: video(0, {playAddr: {src: own}, bitRateList: [
      {width: 1080, height: 1440, playAddr: {src: ownHigh}},
    ]})}),
    photo(1, {video: video(1, {width: 2160, height: 3840, playAddr: {src: second}})}),
  ], {video: video(9, {width: 2160, height: 3840, playAddr: {src: global}})});
  const recommended = work([photo(0, {video: video(0, {width: 4320, height: 7680,
    playAddr: {src: other}})})], {awemeId: otherId});
  const result = run({router: {recommended, target}});
  assert.deepEqual(result.images[0].motion.playUrls, [ownHigh, own]);
  assert.deepEqual(result.images[1].motion.playUrls, [second]);
  assert.ok(!JSON.stringify(result.images).includes(other));
  assert.ok(!JSON.stringify(result.images).includes(global));
  assert.deepEqual(run({router: recommended}).images, []);
});

test('quality ordering does not grant camel display roles to snake rate lists or download fields', () => {
  const root = clip('role-root'), camel = clip('role-camel'), snake = clip('role-snake');
  const download = clip('role-download');
  const result = run({router: work([photo(0, {video: video(0, {
    playAddr: {src: root}, bitRateList: [{width: 1080, height: 1440, playAddr: {src: camel}}],
    bit_rate: [{width: 2160, height: 3840, playAddr: {src: snake}, downloadAddr: {src: download}}],
  })})])});
  const motion = result.images[0].motion;
  assert.deepEqual(motion.playUrls, [snake, camel, root]);
  assert.deepEqual(motion.displayPlaybackUrls, [camel, root]);
  assert.deepEqual(motion.downloadUrls, [download]);
});

test('highest owned bitrate remains available before the URL cap truncates lower representations', () => {
  const rates = Array.from({length: 16}, (_, n) => ({width: 720 + n * 40, height: 960 + n * 40,
    playAddr: {src: clip(`bounded-${n}`)}}));
  const result = run({router: work([photo(0, {video: video(0, {bitRateList: rates})})])});
  const motion = result.images[0].motion;
  assert.equal(motion.playUrls.length, 16);
  assert.equal(motion.playUrls[0], clip('bounded-15'));
  assert.deepEqual(motion.displayPlaybackUrls, motion.playUrls);
});

test('null and missing poster slots stay in place instead of becoming a shorter candidate', () => {
  const result = run({router: work([photo(0), null, photo(2, {urlList: [], video: video(2)}), photo(3)])});
  assert.equal(result.status, 'unavailable');
  assert.equal(result.images.length, 4);
  assert.deepEqual(result.images.map(x => x.sourceIndex), [0, 1, 2, 3]);
  assert.deepEqual(result.images[1].urls, []);
  assert.deepEqual(result.images[2].motion.playUrls, [clip(2)]);
  assert.equal(result.stats.missingPosters, 2);
});

test('same-id hydration may fill a missing poster only by exact image identity', () => {
  const first = work([photo(0), photo(1, {urlList: [], video: video(1)}), photo(2)]);
  const later = work([photo(0), photo(1), photo(2)]);
  const result = run({router: {items: [first, later]}});
  assert.equal(result.status, 'candidate');
  assert.deepEqual(result.images.map(x => x.sourceIndex), [0, 1, 2]);
  assert.equal(result.images[1].urls[0], poster(1));
  assert.deepEqual(result.images[1].motion.playUrls, [clip(1)]);
});

test('a sparse motion array cannot replace or enrich the full owned array by position', () => {
  const full = work([photo(0), photo(1), photo(2)]);
  const sparse = work([photo(1, {video: video(1)})]);
  const result = run({router: {items: [full, sparse]}});
  assert.equal(result.images.length, 3);
  assert.equal(result.stats.motions, 0);
  assert.deepEqual(result.images.map(x => x.imageKey), ['photo-key-0', 'photo-key-1', 'photo-key-2']);
});

test('reordered hydration contributes clips by photo key without moving the selected array', () => {
  const main = work([photo(0, {video: video(0)}), photo(1), photo(2)]);
  const reordered = work([photo(2), photo(0), photo(1, {video: video(1)})]);
  const result = run({router: {items: [main, reordered]}});
  assert.deepEqual(result.images.map(x => x.imageKey), ['photo-key-0', 'photo-key-1', 'photo-key-2']);
  assert.deepEqual(result.images.map(x => x.sourceIndex), [0, 1, 2]);
  assert.deepEqual(result.images[0].motion.playUrls, [clip(0)]);
  assert.deepEqual(result.images[1].motion.playUrls, [clip(1)]);
  assert.equal(result.images[2].motion, undefined);
  assert.equal(result.stats.conflictingAlbums, true);
});

test('an audio-less copy cannot claim the duration of a later exact-work soundtrack', () => {
  const first = work([photo(0)], {music: {duration: 100}});
  const later = {awemeId: id, video: {playAddr: {src: music}, duration: 12500}};
  const result = run({router: {items: [first, later]}});
  assert.deepEqual(result.bgmUrls, [music]);
  assert.equal(result.bgmDuration, 12.5);
});

test('a declared count larger than the returned array remains incomplete', () => {
  const result = run({router: work([photo(0, {video: video(0)})], {imageCount: 3})});
  assert.equal(result.status, 'unavailable');
  assert.equal(result.stats.declaredImages, 3);
  assert.equal(result.images.length, 1);
});

test('over-limit albums are refused without truncating to a downloadable subset', () => {
  const result = run({router: work(Array.from({length: 201}, (_, n) => photo(n)))});
  assert.equal(result.status, 'unavailable');
  assert.equal(result.images.length, 0);
  assert.equal(result.stats.truncated, true);
});

test('recommendations, imprecise numeric owners and conflicting id spellings cannot supply resources', () => {
  const recommendation = work([photo(1, {video: video(1)})], {awemeId: otherId, video: video(9)});
  const rounded = work([photo(2, {video: video(2)})], {awemeId: Number(id)});
  const conflict = work([photo(3, {video: video(3)})], {aweme_id: otherId});
  const target = work([photo(0, {video: video(0)})]);
  const result = run({router: {items: [recommendation, rounded, conflict, target]}});
  assert.deepEqual(result.images[0].motion.playUrls, [clip(0)]);
  assert.equal(result.stats.matchingOwners, 1);
  assert.ok(!JSON.stringify(result.images).includes(clip(1)));
  assert.deepEqual(run({router: recommendation}).images, []);
});

test('official static placeholder tag 2 ignores its video and work-level BGM is never photo motion', () => {
  const entry = work([photo(0, {clip_type: 2, video: video(0)}), photo(1, {clipType: 2, video: video(1)})],
    {video: {playAddr: [{src: music}], duration: 32000}});
  const result = run({router: entry, globalPlayers: [{src: clip(9)}]});
  assert.deepEqual(result.images.map(x => x.kind), ['STATIC', 'STATIC']);
  assert.ok(result.images.every(x => !x.motion));
  assert.deepEqual(result.bgmUrls, [music]);
  assert.equal(result.bgmDuration, 32);
});

test('known LivePhoto tag 3 remains LIVE when its required clip is missing', () => {
  const result = run({router: work([photo(0, {clip_type: 3}), photo(1, {clipType: 3})])});
  assert.deepEqual(result.images.map(x => x.kind), ['LIVE', 'LIVE']);
  assert.ok(result.images.every(x => !x.motion));
  assert.equal(result.stats.motions, 0);
});

test('unknown tags and animation flags cannot guess LIVE or acquire a placeholder video', () => {
  const result = run({router: work([photo(0, {clip_type: 5, video: video(0)}),
    photo(1, {clipType: 6, video: video(1)}), photo(2, {livePhotoType: 1}),
    photo(3, {isLivePhoto: true}), photo(4, {format: 'gif'})])});
  assert.ok(result.images.every(x => x.kind === 'STATIC' && !x.motion));
});

test('desktop camel clipType 5 uses its own playAddr in a complete exact-work RSC album', () => {
  const target = work([photo(0, {clipType: 5, video: video(0)}), photo(1),
    photo(2, {clipType: 5, video: video(2, {playAddr: {src: clip(2)}})})]);
  const recommended = work([photo(9, {clipType: 5, video: video(9)})], {awemeId: otherId});
  const result = run({scripts: [flightScript(flightRecord({target, recommended}))]});
  assert.equal(result.status, 'candidate');
  assert.equal(result.owner, id);
  assert.equal(result.stats.declaredImages, 3);
  assert.deepEqual(result.images.map(x => x.sourceIndex), [0, 1, 2]);
  assert.deepEqual(result.images.map(x => x.kind), ['DYNAMIC', 'STATIC', 'DYNAMIC']);
  assert.deepEqual(result.images[0].motion.displayPlaybackUrls, [clip(0)]);
  assert.deepEqual(result.images[2].motion.displayPlaybackUrls, [clip(2)]);
  assert.ok(!JSON.stringify(result.images).includes(clip(9)));
  assert.equal(result.stats.targetImages[0].clipType, 5);
  assert.equal(result.stats.targetImages[0].displayPlaybackUrls, 1);
});

test('snake clip_type 5 retains the unknown-tag refusal even beside desktop camel 5', () => {
  const result = run({router: work([photo(0, {clip_type: 5, video: video(0)}),
    photo(1, {clip_type: 5, clipType: 5, video: video(1)}),
    photo(2, {clipType: 2, video: video(2)}), photo(3, {clipType: 7, video: video(3)})])});
  assert.ok(result.images.every(x => x.kind === 'STATIC' && !x.motion));
  assert.equal(result.stats.motions, 0);
});

test('desktop camel 5 flag, bitrate, ids, downloads and alternate live info cannot prove its own playAddr', () => {
  const result = run({router: work([photo(0, {clipType: 5}),
    photo(1, {clipType: 5, video: {bitRateList: [{playAddr: {src: clip(1)}}]}}),
    photo(2, {clipType: 5, video: {videoId: 'photo-media-clip-2', downloadAddr: {src: clip(2)}}}),
    photo(3, {clipType: 5, livePhotoInfo: {video: video(3)}}),
    photo(4, {clipType: 5, video: {play_addr: {url_list: [clip(4)]}}}),
    photo(5, {clipType: 5, video: video(5, {playAddr: 'http://v26-web.douyinvod.com/video/insecure.mp4'})})]),
  slides: [slide('photo-key-0', id, 0), slide('photo-key-1', id, 1)]});
  assert.equal(result.status, 'candidate');
  assert.ok(result.images.every(x => x.kind === 'STATIC' && !x.motion));
  assert.equal(result.stats.motions, 0);
});

test('desktop camel 5 cannot take another owner or a work/global player without photo binding', () => {
  const recommended = work([photo(0, {clipType: 5, video: video(0)})], {awemeId: otherId});
  assert.deepEqual(run({router: recommended}).images, []);
  const target = work([photo(0, {clipType: 5})], {video: video(9)});
  const result = run({router: {recommended, target}, globalPlayers: [{src: clip(0)}],
    slides: [slide('photo-key-0', otherId, 0), slide('unbound-key', id, 0)]});
  assert.equal(result.owner, id);
  assert.equal(result.images[0].kind, 'STATIC');
  assert.equal(result.images[0].motion, undefined);
  assert.deepEqual(result.bgmUrls, [clip(9)]);
});

test('display playback role is a play subset from only own camel playAddr and own bitRateList', () => {
  const camel = clip('camel'), backup = clip('backup'), rate = clip('camel-rate');
  const snake = clip('snake'), snakeRate = clip('snake-rate'), snakeListCamel = clip('snake-list-camel');
  const download = clip('download'), fallback = clip('live-info'), dom = clip('dom');
  const result = run({router: work([photo(0, {video: video(0, {
    playAddr: [{src: camel}, backup], play_addr: {url_list: [snake]}, downloadAddr: {src: download},
    bitRateList: [{playAddr: {src: rate}, play_addr: {url_list: [snakeRate]}}],
    bit_rate: [{playAddr: {src: snakeListCamel}}],
  })}), photo(1, {livePhotoInfo: {video: {playAddr: {src: fallback}}}})]),
  slides: [slide('photo-key-0', id, 'dom')]});
  const own = result.images[0].motion;
  assert.deepEqual(own.displayPlaybackUrls, [camel, backup, rate]);
  for (const url of own.displayPlaybackUrls) assert.ok(own.playUrls.includes(url));
  for (const url of [snake, snakeRate, snakeListCamel, download, dom])
    assert.ok(!own.displayPlaybackUrls.includes(url));
  assert.ok(own.playUrls.includes(dom));
  assert.deepEqual(result.images[1].motion.playUrls, [fallback]);
  assert.deepEqual(result.images[1].motion.displayPlaybackUrls, []);
});

test('display playback roles stay within the capped play list after same-key snapshot merging', () => {
  const first = work([photo(0, {video: video(0, {playAddr: Array.from({length: 16}, (_, n) => clip(n))})})]);
  const second = work([photo(0, {video: video('later')})]);
  const result = run({router: {items: [first, second]}});
  const motion = result.images[0].motion;
  assert.equal(motion.playUrls.length, 16);
  assert.deepEqual(motion.displayPlaybackUrls, motion.playUrls);
  assert.ok(!motion.displayPlaybackUrls.includes(clip('later')));
});

test('known video/default tags and untagged desktop metadata accept their own resource objects', () => {
  const result = run({router: work([photo(0, {clipType: 1, video: video(0)}),
    photo(1, {clip_type: 4, video: video(1)}), photo(2, {video: video(2)})])});
  assert.deepEqual(result.images.map(x => x.kind), ['ANIMATED', 'DYNAMIC', 'DYNAMIC']);
  assert.equal(result.stats.motions, 3);
});

test('hydration undefined and null clip tags remain untagged without suppressing owned video', () => {
  const result = run({router: work([
    photo(0, {clipType: undefined, video: video(0)}), photo(1, {clipType: null, video: video(1)}),
    photo(2, {clip_type: undefined, video: video(2)}), photo(3, {clip_type: null, video: video(3)}),
    photo(4, {clip_type: null, clipType: 3, video: video(4)}),
  ])});
  assert.equal(result.stats.motions, 5);
  assert.deepEqual(result.images.map(x => x.kind), ['DYNAMIC', 'DYNAMIC', 'DYNAMIC', 'DYNAMIC', 'LIVE']);
  assert.equal(result.stats.targetImages[0].fields.clipType, true);
  assert.equal(result.stats.targetImages[0].clipType, undefined);
  assert.equal(result.stats.targetImages[1].clipType, undefined);
  assert.equal(result.stats.targetImages[4].clipType, 3);
});

test('late React props are bounded hydration candidates belonging only to the exact work', () => {
  const props = {recommended: work([photo(1, {video: video(1)})], {awemeId: otherId}),
    detail: work([photo(0, {video: video(0)})])};
  props.circular = props;
  const result = run({seeds: [{__reactProps$test: props}]});
  assert.equal(result.stats.provenance, 'hydration');
  assert.equal(result.stats.hydrationRoots, 1);
  assert.deepEqual(result.images[0].motion.playUrls, [clip(0)]);
});

test('invalid script expressions are never executed or accepted as JSON pushes', () => {
  const target = JSON.stringify([1, flightRecord(work([photo(0, {video: video(0)})]))]);
  const result = run({scripts: [{textContent: `self.__pace_f.push(${target} + window.execute());`},
    {textContent: 'window._ROUTER_DATA = window.execute();'}],
  window: {execute: () => { throw Error('script must not be executed'); }}});
  assert.equal(result.images.length, 0);
  assert.equal(result.stats.rscChunks, 0);
});

test('unsafe URLs are removed while URL roles, source position and byte-exact queries survive', () => {
  const result = run({router: work([photo(0, {urlList: ['http://p3.douyinpic.com/no.jpg',
    'https://user:password@p3.douyinpic.com/no.jpg', 'https://p3.douyinpic.com:8443/no.jpg',
    'https://p3.douyinpic.com/with space.jpg', poster(0)],
  video: video(0, {playAddr: ['blob:https://www.douyin.com/x', clip(0)]})})])});
  assert.deepEqual(result.images[0].displayUrls, [poster(0)]);
  assert.deepEqual(result.images[0].motion.playUrls, [clip(0)]);
});

test('page URL and exact desktop route ownership are mandatory', () => {
  for (const page of [`https://evil.test/note/${id}`, `https://www.douyin.com/note/${otherId}`,
    `http://www.douyin.com/note/${id}`, `https://www.douyin.com:8443/note/${id}`,
    `https://www.iesdouyin.com/share/note/${id}/`]) {
    const result = run({page, router: work([photo(0, {video: video(0)})])});
    assert.equal(result.owner, '');
    assert.equal(result.status, 'unavailable');
  }
  for (const route of ['note', 'video', 'slides']) {
    assert.equal(run({page: `https://www.douyin.com/${route}/${id}/?from=share`, router: work()}).owner, id);
  }
});

test('a persistent login button and caption mentioning captcha are not verification gates', () => {
  const result = run({router: work(), body: {innerText: '登录按钮。标题：验证码'},
    buttons: [gate('登录')]});
  assert.equal(result.status, 'candidate');
  assert.equal(result.stats.loginGateVisible, false);
  assert.equal(result.stats.captchaVisible, false);
});

test('visible captcha or login modal requires verification but hidden gates do not', () => {
  const captcha = run({router: work(), captcha: [gate('拖动滑块')]});
  assert.equal(captcha.status, 'needs_verification');
  assert.equal(captcha.stats.gate, 'captcha');
  const modal = run({router: work(), loginModals: [gate('请扫码登录后继续观看')]});
  assert.equal(modal.status, 'needs_verification');
  assert.equal(modal.stats.gate, 'login');
  assert.equal(run({router: work(), captcha: [gate('验证', false)],
    loginModals: [gate('扫码登录', false)]}).status, 'candidate');
});

function slide(key, owner = id, n = 0, extra = {}) {
  const attrs = {'data-image-uri': key, 'data-aweme-id': owner};
  const img = {currentSrc: poster(n), src: poster(n)};
  const player = {currentSrc: clip(n), src: clip(n), videoWidth: 720, videoHeight: 960, duration: 2.4};
  return {getAttribute: name => attrs[name] || null, className: 'dySwiperSlide', parentElement: null,
    querySelector: name => name === 'img' ? img : name === 'video' ? player : null, ...extra};
}

test('DOM enrichment needs both exact work and unique photo key; a generic video is ignored', () => {
  const result = run({router: work([photo(0), photo(1)]),
    slides: [slide('photo-key-0', otherId, 0), slide('wrong-key', id, 1)], globalPlayers: [{src: clip(9)}]});
  assert.equal(result.stats.motions, 0);
  assert.equal(result.stats.domPairedImages, 0);
  const paired = run({router: work([photo(0), photo(1)]), slides: [slide('photo-key-1', id, 1)]});
  assert.equal(paired.stats.motions, 1);
  assert.deepEqual(paired.images[1].motion.playUrls, [clip(1)]);
  assert.deepEqual(paired.images[1].motion.displayPlaybackUrls, []);
  assert.equal(paired.images[0].motion, undefined);
});

test('DOM cannot invent a complete album, reorder missing slots, or bypass known static clip tags', () => {
  assert.deepEqual(run({slides: [slide('photo-key-0')]}).images, []);
  const filled = run({router: work([photo(0), photo(1, {urlList: []}), null]),
    slides: [slide('photo-key-1', id, 1)]});
  assert.equal(filled.images.length, 3);
  assert.deepEqual(filled.images.map(x => x.sourceIndex), [0, 1, 2]);
  assert.equal(filled.images[1].urls[0], poster(1));
  assert.deepEqual(filled.images[2].urls, []);
  const staticResult = run({router: work([photo(0, {clip_type: 2})]), slides: [slide('photo-key-0')]});
  assert.equal(staticResult.images[0].kind, 'STATIC');
  assert.equal(staticResult.images[0].motion, undefined);
});

test('stats contain sanitized counts, flags, titles and hosts, never signed URLs or cookies', () => {
  const result = run({router: work([photo(0, {video: video(0)})],
    {video: {playAddr: {src: music}}, cookie: 'sessionid=do-not-log'}),
  window: {documentCookie: 'sessionid=do-not-log'}});
  const diagnostics = JSON.stringify(result.stats);
  assert.ok(!diagnostics.includes('https://'));
  assert.ok(!diagnostics.includes('secret'));
  assert.ok(!diagnostics.includes('sessionid'));
  assert.ok(!diagnostics.includes(id));
  assert.ok(result.stats.mediaHosts.includes('v26-web.douyinvod.com'));
});

test('oversized scripts and cyclic deep state remain bounded', () => {
  const cyclic = {}; cyclic.next = cyclic;
  const result = run({router: {cyclic, own: work()}, scripts: [{textContent: ' '.repeat(2097153)}]});
  assert.equal(result.stats.truncated, true);
  assert.equal(result.images.length, 1);
  assert.ok(result.stats.visitedNodes < 12000);
});

test('a large unrelated inline script is skipped while a complete owned RSC array remains a candidate', () => {
  const unrelated = {textContent: `window.unrelated = "${'x'.repeat(2097152)}";`};
  const result = run({scripts: [unrelated, flightScript(flightRecord(work([photo(0, {video: video(0)})])))]});
  assert.equal(result.status, 'candidate');
  assert.equal(result.images.length, 1);
  assert.equal(result.stats.motions, 1);
  assert.equal(result.stats.truncated, true);
  assert.equal(result.stats.limits.scriptSize, 1);
  assert.equal(result.stats.limits.queueNodes, 0);
  assert.equal(result.stats.rscRecords, 1);
});

test('wide unrelated hydration objects record a separate limit without invalidating target completeness', () => {
  const unrelated = Object.fromEntries(Array.from({length: 1500}, (_, n) => [`field${n}`, n]));
  const result = run({router: {unrelated, target: work([photo(0, {video: video(0)})])}});
  assert.equal(result.status, 'candidate');
  assert.equal(result.stats.truncated, true);
  assert.equal(result.stats.limits.objectKeys, 1);
  assert.equal(result.stats.limits.workImages, 0);
  assert.equal(result.stats.visitedNodes < 12000, true);
});

test('exact-work video and album beyond the former 256-key cutoff remain reachable within bounded hydration', () => {
  const prefix = Object.fromEntries(Array.from({length: 300}, (_, n) => [`field${n}`, {text: 'unrelated'}]));
  const target = {awemeId: id, video: video(0)};
  const result = run({router: {...prefix, target}, allowVideo: true});
  assert.equal(result.owner, id);
  assert.equal(result.videoCandidates.length, 1);
  assert.deepEqual(result.videoCandidates[0].playUrls, [clip(0)]);
  const album = run({router: {...prefix, target: work([photo(0, {video: video(0)})])}});
  assert.equal(album.status, 'candidate');
  assert.equal(album.images.length, 1);
  assert.ok(album.stats.visitedNodes < 12000);
});

test('target image diagnostics show owned video shape with only whitelist names, counts and hosts', () => {
  const result = run({router: work([photo(0, {clipType: undefined, privateField: 'photo-key-secret',
    video: video(0, {privateCookie: 'sessionid=secret', bitRateList: [{playAddr: {src: clip(1)}}]})}),
  photo(1, {video: null}), photo(2)])});
  const summary = result.stats.targetImages[0];
  assert.equal(summary.sourceIndex, 0);
  assert.equal(summary.fields.video, true);
  assert.equal(summary.fields.urlList, true);
  assert.equal(summary.fields.url_list, false);
  assert.equal(summary.fields.privateField, undefined);
  assert.equal(summary.ownVideoPresent, true);
  assert.equal(summary.ownVideoObject, true);
  assert.equal(summary.ownVideoType, 'object');
  assert.deepEqual(summary.videoKeys, ['playAddr', 'bitRateList', 'width', 'height', 'duration', 'videoId']);
  assert.equal(summary.playAddrType, 'array');
  assert.equal(summary.playAddrUrls, 1);
  assert.deepEqual(summary.playAddrHosts, ['v26-web.douyinvod.com']);
  assert.equal(summary.bitrateCount, 1);
  assert.equal(summary.mediaIdPresent, true);
  assert.equal(summary.playUrls, 2);
  assert.equal(summary.candidateMotion, true);
  assert.equal(result.stats.targetImages[1].ownVideoPresent, true);
  assert.equal(result.stats.targetImages[1].ownVideoType, 'missing');
  assert.equal(result.stats.targetImages[2].ownVideoPresent, false);
  const sanitized = JSON.stringify(result.stats);
  for (const secret of ['https://', 'token=', 'secret', 'photo-key-', id, 'privateCookie'])
    assert.ok(!sanitized.includes(secret), `must not contain ${secret}`);
});

test('target image summaries are capped separately from the complete album array', () => {
  const result = run({router: work(Array.from({length: 15}, (_, n) => photo(n)))});
  assert.equal(result.images.length, 15);
  assert.equal(result.stats.targetImages.length, 12);
  assert.equal(result.stats.targetImagesOmitted, 3);
  assert.equal(result.stats.truncated, false);
});

test('owned variant diagnostics count known structures without mapping a clip by position or recommendation', () => {
  const target = work([photo(0)], {imgBitrate: [{images: [photo(0, {video: video(0)})]}],
    image_bit_rate: null, imagePostInfo: {images: [photo(1), photo(2)]}});
  const unrelated = work([photo(9)], {awemeId: otherId,
    img_bitrate: [{images: [photo(9, {video: video(9)})]}]});
  const result = run({router: {items: [unrelated, target, {...target}]}});
  assert.equal(result.stats.workVariants.imgBitrate.present, true);
  assert.equal(result.stats.workVariants.imgBitrate.entries, 1);
  assert.equal(result.stats.workVariants.imgBitrate.images, 1);
  assert.equal(result.stats.workVariants.imgBitrate.videoObjects, 1);
  assert.equal(result.stats.workVariants.imgBitrate.playUrls, 1);
  assert.equal(result.stats.workVariants.imgBitrate.mediaIds, 1);
  assert.equal(result.stats.workVariants.imagePostInfo.images, 2);
  assert.equal(result.stats.workVariants.image_bit_rate.present, true);
  assert.equal(result.stats.workVariants.image_bit_rate.images, 0);
  assert.equal(result.stats.workVariants.img_bitrate.present, false);
  assert.equal(result.stats.motions, 0);
  assert.equal(result.images[0].motion, undefined);
  assert.ok(!JSON.stringify(result.stats.workVariants).includes('https://'));
});

test('incomplete data uses loading until page completion, then unavailable', () => {
  assert.equal(run({state: 'loading'}).status, 'loading');
  assert.equal(run().status, 'unavailable');
  assert.equal(run({state: 'interactive', router: work([photo(0, {urlList: []})])}).status, 'loading');
});
