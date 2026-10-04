const {readFileSync} = require('node:fs');
const vm = require('node:vm');
const assert = require('node:assert/strict');
const {test} = require('node:test');
const source = readFileSync('app/src/main/java/com/local/douyinsaver/PublicPageScript.kt', 'utf8');
const id = '7692053151259682555';
const otherId = '7689301063664454962';
const script = source.match(/return """([\s\S]*?)"""/)[1].replaceAll('$id', id);
const media = 'https://v26-web.douyinvod.com/video/test.mp4?private=not-for-diagnostics';
const mobileUrl = `https://www.iesdouyin.com/share/video/${id}/`;
function run(data, videos = [], options = {}) {
  return JSON.parse(vm.runInNewContext(script, {
    window: {_ROUTER_DATA: data}, URL,
    location: {href: options.page || mobileUrl},
    document: {readyState: 'complete', visibilityState: 'visible', title: options.title || 'Target',
      body: {innerText: options.body || ''},
      querySelector: () => null,
      querySelectorAll: selector => selector === 'video' ? videos :
        selector === '.gallery-container audio' ? (options.audio || []) : []},
  }, {timeout: 1000}));
}
function route(itemId = id, extra = {}) {
  return {loaderData: {'video_layout': null, 'video_(id)/page': {itemId, ...extra}}, errors: null};
}
function item(aweme_id = id) {
  return {aweme_id, desc: 'Target work', video: {width: 1920, height: 1080, duration: 83916,
    play_addr: {url_list: [media]}}};
}
function player(extra = {}) {
  return {id: 'video-player', className: '', parentElement: null, currentSrc: media, src: media,
    readyState: 1, videoWidth: 1920, videoHeight: 1080, duration: 83.916,
    dataset: {}, loadCount: 0, load() { this.loadCount++; }, ...extra};
}
const photo = 'https://p3-sign.douyinpic.com/tos-cn-i/example.webp?token=keep-exact';
const music = 'https://sf3-cdn-tos.douyinstatic.com/obj/ies-music/example.mp3';
function album(aweme_id = id) {
  return {aweme_id, desc: 'Album work', images: [
    {url_list: [photo], width: 1080, height: 1440},
    {url_list: ['https://p6-sign.byteimg.com/tos-cn-i/second.jpg'], width: 1440, height: 1080},
  ], music: {play_url: {url_list: [music]}, duration: 30}};
}

test('actual empty mobile response keyshape is diagnosed without pretending to resolve', () => {
  // Key shape from the captured public response; request identifiers and token values omitted.
  const empty = route(id, {
    ua: '', isSpider: false, webId: '', query: {}, renderInSSR: 1, lastPath: id,
    appName: 'safari', host: 'www.iesdouyin.com', isNotSupportWebp: false, commonContext: {},
    isAiDouyinOpt: '', isVideoOptimize: true, openDirectGroup: '4', isVideoStyleOptimize: true,
    isButtonOptimize: true, isAutoOpenApp: true, darkModeAdaptation: true, serverToken: '', abParams: {},
  });
  const result = run(empty, [], {body: '抱歉出错了，请尝试在抖音内观看'});
  assert.equal(result.url, '');
  assert.equal(result.ready, 0);
  assert.equal(result.stats.itemMatches, true);
  assert.equal(result.stats.videoInfoPresent, false);
  assert.equal(result.stats.routeType, 'object');
  assert.deepEqual(result.stats.knownErrors, ['抱歉出错了', '请尝试在抖音内观看']);
  assert.ok(result.stats.pageKeys.includes('serverToken'));
  assert.ok(!JSON.stringify(result.stats).includes('safari'));
});
test('uses exact work metadata before a DOM video exists', () => {
  const result = run(route(id, {videoInfoRes: {item_list: [item()]}}));
  assert.equal(result.owner, id);
  assert.equal(result.structured, true);
  assert.equal(result.duration, 83.916);
  assert.equal(result.width, 1920);
  assert.equal(result.stats.videoInfoPresent, true);
});
test('video playback and download sources remain separate and preserve signed queries', () => {
  const entry = item();
  const playback = 'https://aweme.snssdk.com/aweme/v1/playwm/?video_id=own-video-id&token=a%2Bb&line=0';
  const download = 'https://v26-web.douyinvod.com/video/download.mp4?signature=keep%2Bexact&watermark=1';
  entry.video.play_addr.url_list = [playback];
  entry.video.download_addr = {url_list: [download]};
  const result = run(route(id, {videoInfoRes: {item_list: [entry]}}));
  assert.deepEqual(result.candidates.map(c => c.url), [playback, download]);
  assert.deepEqual(result.candidates.map(c => c.sourceField), ['play_addr', 'download_addr']);
  for (const candidate of result.candidates) {
    assert.deepEqual(candidate.playUrls, [playback]);
    assert.deepEqual(candidate.downloadUrls, [download]);
  }
});
test('another work cannot supply the target video download rendition', () => {
  const recommendation = item(otherId);
  recommendation.video.download_addr = {url_list: ['https://v26-web.douyinvod.com/video/other.mp4']};
  const result = run(route(id, {videoInfoRes: {item_list: [recommendation, item()]}}));
  assert.deepEqual(result.candidates[0].downloadUrls, []);
  assert.deepEqual(result.candidates.map(c => c.url), [media]);
});
test('later hydration of the same video retains a download source beside a repeated playback URL', () => {
  const hydrated = item();
  const download = 'https://v26-web.douyinvod.com/video/hydrated-download.mp4';
  hydrated.video.download_addr = {url_list: [download]};
  const result = run({item_list: [item(), hydrated]});
  assert.deepEqual(result.candidates.map(c => c.url), [media, download]);
  assert.deepEqual(result.candidates[0].downloadUrls, [download]);
});
test('same-work codec and bitrate alternatives retain their playback role', () => {
  const entry = item();
  const h264 = 'https://v26-web.douyinvod.com/video/h264.mp4?sig=%2F';
  const bitrate = 'https://v26-web.douyinvod.com/video/bitrate.mp4';
  entry.video.play_addr_h264 = {url_list: [h264]};
  entry.video.bit_rate = [{play_addr: {url_list: [bitrate]}}];
  const result = run({item_list: [entry]});
  assert.deepEqual(result.candidates.map(c => c.url), [media, h264, bitrate]);
  assert.deepEqual(result.candidates[0].playUrls, [media, h264, bitrate]);
  assert.deepEqual(result.candidates[0].downloadUrls, []);
});

test('exact work media URI, codec URI and bitrate URI remain available beside CDN URLs', () => {
  const entry = item();
  entry.video.play_addr.uri = 'v0200f0000main_video';
  entry.video.play_addr_h264 = {uri: 'v0200f0000h264_video'};
  entry.video.video_id = 'v0200f0000explicit_id';
  entry.video.bit_rate = [{play_addr: {uri: 'v0200f0000bitrate_id'}}];
  const result = run({item_list: [entry]});
  assert.deepEqual(result.candidates[0].mediaIds,
    ['v0200f0000explicit_id', 'v0200f0000main_video', 'v0200f0000h264_video', 'v0200f0000bitrate_id']);
  assert.equal(result.url, media);
  assert.deepEqual(result.candidates[0].playUrls, [media]);
});

test('URI-only target metadata can be handed to native policy without inventing a CDN URL', () => {
  const entry = item();
  entry.video.play_addr = {uri: 'v0200f0000only_media'};
  const result = run({item_list: [entry]});
  assert.equal(result.candidates.length, 1);
  assert.equal(result.candidates[0].sourceField, 'metadata');
  assert.equal(result.candidates[0].owner, id);
  assert.deepEqual(result.candidates[0].mediaIds, ['v0200f0000only_media']);
  assert.equal(result.candidates[0].url, '');
  assert.deepEqual(result.candidates[0].playUrls, []);
});

test('numeric work ids, media URLs, malformed URIs and recommendations cannot supply a media identifier', () => {
  const target = item();
  const recommendation = item(otherId);
  recommendation.video.play_addr.uri = 'v0200f0000other_work';
  for (const uri of [id, Number(id), '../v0200f0000media', 'short', 'https://v3.douyinvod.com/video.mp4', 'v0200f0000 space']) {
    target.video.play_addr.uri = uri;
    target.video.video_id = id;
    const result = run({item_list: [recommendation, target]});
    assert.ok(result.candidates.every(candidate => candidate.owner === id && candidate.mediaIds.length === 0));
    assert.ok(result.candidates.every(candidate => !candidate.url.includes('other_work')));
  }
});

test('every bitrate download address keeps its download role and signed query', () => {
  const entry = item();
  const mainDownload = 'https://v3.douyinvod.com/main.mp4?sig=original%2Bsignature';
  const rateDownload = 'https://v6.douyinvod.com/rate.mp4?sig=rate%2Bsignature';
  const codecDownload = 'https://v9.douyinvod.com/h264.mp4?sig=codec%2Bsignature';
  entry.video.download_addr = {url_list: [mainDownload]};
  entry.video.bit_rate = [{download_addr: {url_list: [rateDownload]}, download_addr_h264: {url_list: [codecDownload]}}];
  const result = run({item_list: [entry]});
  assert.deepEqual(result.candidates[0].downloadUrls, [mainDownload, rateDownload, codecDownload]);
  assert.deepEqual(result.candidates.map(candidate => candidate.url), [media, mainDownload, rateDownload, codecDownload]);
  assert.deepEqual(result.candidates.map(candidate => candidate.sourceField),
    ['play_addr', 'download_addr', 'download_addr', 'download_addr']);
});

test('duplicate exact-work hydration merges newly published media URIs with existing URL metadata', () => {
  const hydrated = item();
  hydrated.video.play_addr.uri = 'v0200f0000later_media';
  const result = run({item_list: [item(), hydrated]});
  assert.equal(result.candidates.length, 1);
  assert.deepEqual(result.candidates[0].mediaIds, ['v0200f0000later_media']);
});
test('download-only metadata remains playable without being mislabeled as a playback field', () => {
  const entry = item();
  delete entry.video.play_addr;
  entry.video.download_addr = {url_list: [media]};
  const result = run({item_list: [entry]});
  assert.equal(result.url, media);
  assert.deepEqual(result.candidates[0].playUrls, []);
  assert.deepEqual(result.candidates[0].downloadUrls, [media]);
  assert.equal(result.candidates[0].sourceField, 'download_addr');
});
test('insecure or credential-bearing download sources cannot enter separated alternatives', () => {
  const entry = item();
  entry.video.download_addr = {url_list: ['http://v26-web.douyinvod.com/download.mp4',
    'https://user:pass@v26-web.douyinvod.com/download.mp4']};
  const result = run({item_list: [entry]});
  assert.deepEqual(result.candidates[0].downloadUrls, []);
  assert.deepEqual(result.candidates.map(c => c.url), [media]);
});
test('recommendations cannot substitute for the requested structured work', () => {
  assert.equal(run(route(id, {videoInfoRes: {item_list: [item(otherId)]}})).url, '');
  assert.equal(run(route(id, {videoInfoRes: {item_list: [item(otherId), item()]}})).owner, id);
});
test('a numeric work id is not treated as an exact string association', () => {
  assert.equal(run({item_list: [item(Number(id))]}).url, '');
});
test('incomplete structured metadata cannot claim readiness', () => {
  const entry = item();
  entry.video.duration = 0;
  assert.equal(run({item_list: [entry]}).ready, 0);
});
test('mobile video-player requires the matching path and route itemId', () => {
  assert.equal(run(route(), [player()]).owner, id);
  assert.equal(run(route(otherId), [player()]).url, '');
  assert.equal(run(undefined, [player()]).url, '');
  assert.equal(run(route(), [player()], {page: `https://www.iesdouyin.com/share/video/${otherId}/`}).url, '');
  assert.equal(run(route(), [player()], {page: `https://evil.example/share/video/${id}/`}).url, '');
  assert.equal(run(route(), [player()], {page: `https://www.douyin.com/video/${id}`}).url, '');
});
test('duplicate mobile player ids are ambiguous and rejected', () => {
  assert.equal(run(route(), [player(), player()]).url, '');
});
test('desktop target uses the nearest work container and excludes recommendation players', () => {
  const other = player({id: '', parentElement: {className: `video_${otherId}`, parentElement: null}});
  const target = player({id: '', parentElement: {className: `wrap video_${id} selected`, parentElement: null}});
  const result = run(undefined, [other, target], {page: `https://www.douyin.com/video/${id}`});
  assert.equal(result.owner, id);
  assert.equal(result.stats.players[0].target, false);
  assert.equal(result.stats.players[1].target, true);
  assert.equal(run(undefined, [other]).url, '');
  const nested = player({id: '', parentElement: {className: `video_${otherId}`,
    parentElement: {className: `video_${id}`, parentElement: null}}});
  assert.equal(run(undefined, [nested]).url, '');
});
test('DOM metadata readiness is preserved and metadata loading is requested only once', () => {
  const p = player({readyState: 0, videoWidth: 0, videoHeight: 0, duration: NaN});
  const result = run(route(), [p]);
  assert.equal(result.ready, 0);
  assert.equal(result.width, 0);
  assert.equal(result.duration, 0);
  assert.equal(p.loadCount, 1);
  assert.equal(p.preload, 'metadata');
  assert.equal(p.muted, true);
  run(route(), [p]);
  assert.equal(p.loadCount, 1);
});
test('insecure or browser-only media URLs are rejected without rewriting', () => {
  for (const bad of ['http://v26-web.douyinvod.com/a.mp4', 'blob:https://www.douyin.com/1',
    'javascript:alert(1)', '//v26-web.douyinvod.com/a.mp4',
    'https://user:secret@v26-web.douyinvod.com/a.mp4', 'https://v26-web.douyinvod.com:8443/a.mp4',
    'https://v26-web.douyinvod.com/space video.mp4', 'https://v26-web.douyinvod.com\\@evil.example/a.mp4']) {
    const entry = item();
    entry.video.play_addr.url_list = [bad];
    assert.equal(run({item_list: [entry]}).url, '', bad);
    const result = run(route(), [player({currentSrc: bad, src: bad})]);
    assert.equal(result.url, '', bad);
  }
});
test('diagnostic metadata omits full media URLs and caps public title', () => {
  const result = run(route(), [player()], {title: '字'.repeat(200)});
  assert.equal(result.stats.title.length, 120);
  assert.equal(result.stats.players[0].host, 'v26-web.douyinvod.com');
  assert.ok(!JSON.stringify(result.stats).includes('private'));
});
test('cyclic public data does not hang traversal', () => {
  const data = {video: item()};
  data.self = data;
  assert.equal(run(data).owner, id);
});
test('unsupported first host cannot conceal the later media URL', () => {
  const entry = item();
  const unsupported = 'https://unsupported.example/video.mp4';
  entry.video.play_addr.url_list = [unsupported, media];
  const result = run({item_list: [entry]});
  assert.deepEqual(result.candidates.map(c => c.url), [unsupported, media]);
  assert.ok(result.candidates.every(c => c.owner === id && c.ready === 1));
});
test('all same-work data objects and the ready DOM source remain available', () => {
  const first = item();
  first.video.play_addr.url_list = ['https://unsupported.example/video.mp4'];
  const domUrl = 'https://v26-web.douyinvod.com/video/dom.mp4';
  const result = run(route(id, {videoInfoRes: {item_list: [first, item(otherId), item()]}}),
    [player({currentSrc: domUrl, src: domUrl})]);
  assert.deepEqual(result.candidates.map(c => c.url), [first.video.play_addr.url_list[0], media, domUrl]);
  assert.deepEqual(result.candidates.map(c => c.structured), [true, true, false]);
});
test('candidate URLs are deduplicated without admitting insecure alternatives', () => {
  const entry = item();
  entry.video.play_addr.url_list = [media, media, 'http://v26-web.douyinvod.com/video/unsafe.mp4'];
  const result = run(route(id, {videoInfoRes: {item_list: [entry, entry]}}), [player()]);
  assert.equal(result.candidates.length, 1);
  assert.equal(result.candidates[0].url, media);
});
test('missing dimensions and duration cannot declare an unverified structured candidate ready', () => {
  const incomplete = item();
  delete incomplete.video.width;
  delete incomplete.video.height;
  delete incomplete.video.duration;
  const result = run(route(id, {videoInfoRes: {item_list: [incomplete]}}));
  assert.equal(result.candidates.length, 0);
  assert.equal(result.ready, 0);
});
test('bounded alternatives still reserve a slot for the actual DOM player', () => {
  const entry = item();
  entry.video.play_addr.url_list = Array.from({length: 100}, (_, n) => `https://unsupported.example/${n}.mp4`);
  const result = run(route(id, {videoInfoRes: {item_list: [entry]}}), [player()]);
  assert.equal(result.candidates.length, 64);
  assert.equal(result.candidates.at(-1).url, media);
});

test('static albums retain ordered images and music without video metadata', () => {
  const result = run(route(id, {videoInfoRes: {item_list: [album()]}}));
  assert.equal(result.candidates.length, 0);
  assert.equal(result.albums.length, 1);
  assert.equal(result.albums[0].owner, id);
  assert.equal(result.albums[0].images.length, 2);
  assert.equal(result.albums[0].images[0].urls[0], photo);
  assert.equal(result.albums[0].images[1].width, 1440);
  assert.deepEqual(result.albums[0].bgmUrls, [music]);
  assert.equal(result.albums[0].bgmDuration, 30);
});

test('a single display-only note keeps its source role and own soundtrack without a marked download variant', () => {
  const entry = album();
  const signed = 'https://p5-sign.douyinpic.com/one~tplv-dy-aweme-images:q75.webp?signature=a%2Bb%3D&x=&x=1';
  entry.images = [{uri: 'one-photo', width: 1080, height: 1440, url_list: [signed]}];
  delete entry.music;
  entry.video = {play_addr: {uri: music}, duration: 12000};
  const result = run({loaderData: {'note_(id)/page': {itemId: id, videoInfoRes: {item_list: [entry]}}}}, [],
    {page: `https://www.iesdouyin.com/share/note/${id}/`});
  assert.equal(result.candidates.length, 0);
  assert.equal(result.albums.length, 1);
  assert.equal(result.albums[0].owner, id);
  assert.deepEqual(result.albums[0].images[0], {
    urls: [signed], displayUrls: [signed], downloadUrls: [], width: 1080, height: 1440,
  });
  assert.deepEqual(result.albums[0].bgmUrls, [music]);
  assert.equal(result.albums[0].bgmDuration, 12);
});
test('album display and download variants retain their original signed addresses', () => {
  const entry = album();
  const download = 'https://p3.douyinpic.com/photo~watermark,image.jpg?signature=keep%2Bexact';
  entry.images[0].download_url_list = [download];
  const image = run({item_list: [entry]}).albums[0].images[0];
  assert.deepEqual(image.displayUrls, [photo]);
  assert.deepEqual(image.downloadUrls, [download]);
  assert.deepEqual(image.urls, [download, photo]);
});
test('img_bitrate photos bind by URI despite a different variant order', () => {
  const entry = album();
  entry.images[0].uri = 'photo-a';
  entry.images[1].uri = 'photo-b';
  const first = 'https://p3.douyinpic.com/a-display.jpg?token=keep%2B';
  const second = 'https://p3.douyinpic.com/b-display.jpg';
  entry.img_bitrate = [{name: 'gear_480p', images: [
    {uri: 'photo-b', url_list: [second]}, {uri: 'photo-a', url_list: [first]},
    {uri: 'unrelated', url_list: ['https://p3.douyinpic.com/unrelated.jpg']},
  ]}];
  const images = run({item_list: [entry]}).albums[0].images;
  assert.deepEqual(images[0].displayUrls, [photo, first]);
  assert.deepEqual(images[1].displayUrls, [entry.images[1].url_list[0], second]);
  assert.ok(!JSON.stringify(images).includes('unrelated.jpg'));
});
test('img_bitrate cannot attach by position or obtain a matching URI from another work', () => {
  const entry = album();
  entry.images[0].uri = 'photo-a';
  entry.img_bitrate = [{images: [{uri: 'other-uri', url_list: ['https://p3.douyinpic.com/wrong.jpg']},
    {url_list: ['https://p3.douyinpic.com/no-uri.jpg']}]}];
  const other = album(otherId);
  other.img_bitrate = [{images: [{uri: 'photo-a', url_list: ['https://p3.douyinpic.com/other-work.jpg']}]}];
  const result = run({item_list: [other, entry]});
  assert.equal(result.albums.length, 1);
  assert.deepEqual(result.albums[0].images[0].displayUrls, [photo]);
  assert.ok(!JSON.stringify(result.albums).includes('wrong.jpg'));
  assert.ok(!JSON.stringify(result.albums).includes('no-uri.jpg'));
  assert.ok(!JSON.stringify(result.albums).includes('other-work.jpg'));
});

test('image_post_info and original/download variants preserve fallback order', () => {
  const entry = album();
  delete entry.images;
  entry.image_post_info = {images: [{
    download_url: {url_list: ['https://unsupported.example/original.jpg', photo]},
    download_addr: {url_list: ['https://p3.douyinpic.com/download.jpg']},
    url_list: [photo], display_image: {url_list: ['https://p3.douyinpic.com/preview.jpg']},
  }]};
  const result = run({aweme_detail: entry});
  assert.deepEqual(result.albums[0].images[0].urls, ['https://unsupported.example/original.jpg', photo,
    'https://p3.douyinpic.com/download.jpg', 'https://p3.douyinpic.com/preview.jpg']);
});

test('recommendation albums and imprecise numeric ids cannot provide photos or music', () => {
  assert.equal(run({items: [album(otherId)]}).albums.length, 0);
  assert.equal(run({items: [album(Number(id))]}).albums.length, 0);
  const result = run({items: [album(otherId), album()]});
  assert.equal(result.albums.length, 1);
  assert.equal(result.albums[0].owner, id);
});

test('album video placeholders never replace static images with a soundtrack video', () => {
  const entry = album();
  entry.video = item().video;
  const result = run({item_list: [entry]});
  assert.equal(result.candidates.length, 0);
  assert.equal(result.albums.length, 1);
});

test('unsafe album or soundtrack addresses are omitted without losing image positions', () => {
  const entry = album();
  entry.images[0].url_list = ['http://p3.douyinpic.com/a.jpg', 'blob:https://www.douyin.com/1',
    'https://user:secret@p3.douyinpic.com/a.jpg', 'https://p3.douyinpic.com:8443/a.jpg', photo];
  entry.images[1].url_list = ['https://p3.douyinpic.com/with space.jpg'];
  entry.music.play_url.url_list = ['http://sf3-cdn-tos.douyinstatic.com/a.mp3', music];
  const result = run({item_list: [entry]});
  assert.deepEqual(result.albums[0].images[0].urls, [photo]);
  assert.deepEqual(result.albums[0].images[1].urls, []);
  assert.deepEqual(result.albums[0].bgmUrls, ['https://sf3-cdn-tos.douyinstatic.com/a.mp3', music]);
});

test('albums without music still supply static images and album work data stays out of stats', () => {
  const entry = album();
  delete entry.music;
  const result = run({item_list: [entry]});
  assert.deepEqual(result.albums[0].bgmUrls, []);
  assert.equal(result.albums[0].bgmDuration, 0);
  assert.ok(!JSON.stringify(result.stats).includes('token'));
});

test('image counts are bounded instead of silently truncating a larger album', () => {
  const entry = album();
  entry.images = Array.from({length: 201}, () => ({url_list: [photo]}));
  assert.equal(run({item_list: [entry]}).albums.length, 0);
});

test('video cover candidates retain safe original, cover, and fallback addresses', () => {
  const entry = item();
  entry.video.origin_cover = {url_list: [photo]};
  entry.video.cover = {url_list: ['http://p3.douyinpic.com/unsafe.jpg', 'https://p3.douyinpic.com/cover.jpg']};
  const result = run({item_list: [entry]});
  assert.deepEqual(result.candidates[0].coverUrls, [photo, 'https://p3.douyinpic.com/cover.jpg']);
  assert.equal(result.candidates[0].url, media);
});

test('slides route data and public note location retain exact album ownership', () => {
  const data = {loaderData: {'slides_(id)/page': {itemId: id, videoInfoRes: {item_list: [album()]}}}};
  const result = run(data, [], {page: `https://www.iesdouyin.com/share/slides/${id}/`});
  assert.equal(result.stats.itemMatches, true);
  assert.equal(result.albums[0].owner, id);
});

test('official note player BGM comes from the same work video.play_addr.uri', () => {
  // Field relationship verified in the public official note_(id)/page script.
  const ownMusic = 'https://sf3-cdn-tos.douyinstatic.com/obj/ies-music/own-clip.mp3?signature=keep-exact';
  const entry = album();
  entry.video = {play_addr: {uri: ownMusic}, duration: 12500};
  const result = run({item_list: [entry]});
  assert.deepEqual(result.albums[0].bgmUrls, [ownMusic, music]);
  assert.equal(result.albums[0].bgmDuration, 12.5);
  assert.equal(result.candidates.length, 0);
});

test('official note BGM URI works even when music metadata has no play_url', () => {
  const entry = album();
  entry.music = {title: 'Track title', mid: '12345'};
  entry.video = {play_addr: {uri: music}};
  const result = run({item_list: [entry]});
  assert.deepEqual(result.albums[0].bgmUrls, [music]);
  assert.equal(result.albums[0].images.length, 2);
});

test('music play_url absolute URI and URL variants are supported without inventing an opaque id URL', () => {
  const entry = album();
  entry.music.play_url = {uri: music, url_list: []};
  assert.deepEqual(run({item_list: [entry]}).albums[0].bgmUrls, [music]);
  entry.music.play_url = {uri: 'music-resource-id', url: music};
  assert.deepEqual(run({item_list: [entry]}).albums[0].bgmUrls, [music]);
  entry.music.play_url = {uri: 'music-resource-id', url_list: []};
  assert.deepEqual(run({item_list: [entry]}).albums[0].bgmUrls, []);
});

test('the same album audio-bearing MP4 can supply BGM without becoming a video candidate', () => {
  const entry = album();
  delete entry.music;
  entry.video = {play_addr: {uri: 'opaque-media-id', url_list: [media]}, duration: 8000};
  const result = run({item_list: [entry]});
  assert.deepEqual(result.albums[0].bgmUrls, [media]);
  assert.equal(result.candidates.length, 0);
  assert.equal(result.albums[0].bgmDuration, 8);
});

test('note audio HTTP addresses are upgraded to TLS while credentials, wrong ports, and malformed URLs stay rejected', () => {
  const entry = album();
  delete entry.music;
  entry.video = {play_addr: {uri: music.replace('https:', 'http:'), url_list: [
    'http://user:secret@sf3-cdn-tos.douyinstatic.com/a.mp3',
    'http://sf3-cdn-tos.douyinstatic.com:80/a.mp3',
    'http://sf3-cdn-tos.douyinstatic.com/with space.mp3',
    'blob:https://www.douyin.com/1', 'opaque-uri',
  ]}};
  assert.deepEqual(run({item_list: [entry]}).albums[0].bgmUrls, [music]);
});

test('late same-work hydration contributes BGM and recommendation audio cannot be substituted', () => {
  const target = album();
  delete target.music;
  const rec = album(otherId);
  rec.video = {play_addr: {uri: music}};
  const data = {item_list: [target, rec]};
  assert.deepEqual(run(data).albums[0].bgmUrls, []);
  target.video = {play_addr: {uri: music}};
  assert.deepEqual(run(data).albums[0].bgmUrls, [music]);
});

test('a music-less first copy cannot conceal a later matching work audio object', () => {
  const target = album();
  delete target.music;
  const result = run({item_list: [target, {aweme_id: id, video: {play_addr: {uri: music}}}]});
  assert.equal(result.albums.length, 1);
  assert.deepEqual(result.albums[0].bgmUrls, [music]);
});

test('official note loader key contributes exact route diagnostics and structured album data', () => {
  const data = {loaderData: {'note_(id)/page': {itemId: id, videoInfoRes: {item_list: [album()]}}}};
  const result = run(data, [], {page: `https://www.iesdouyin.com/share/note/${id}/`});
  assert.equal(result.stats.itemMatches, true);
  assert.equal(result.stats.videoInfoPresent, true);
  assert.deepEqual(result.albums[0].bgmUrls, [music]);
});

function audioPlayer(extra = {}) {
  return {className: 'hide', parentElement: null, currentSrc: music, src: music, duration: 12, ...extra};
}
test('late official note audio DOM is used only after the first renderer work and route are exactly associated', () => {
  const entry = album();
  delete entry.music;
  const data = {loaderData: {'note_(id)/page': {itemId: id, videoInfoRes: {item_list: [entry]}}}};
  const options = {page: `https://www.iesdouyin.com/share/note/${id}/`, audio: [audioPlayer()]};
  const result = run(data, [], options);
  assert.deepEqual(result.albums[0].bgmUrls, [music]);
  assert.equal(result.albums[0].bgmDuration, 12);
  assert.equal(result.stats.albumAudio.target, true);
  assert.equal(result.stats.albumAudio.host, 'sf3-cdn-tos.douyinstatic.com');
  assert.deepEqual(run(undefined, [], options).albums, []);
  data.loaderData['note_(id)/page'].itemId = otherId;
  assert.deepEqual(run(data, [], options).albums[0].bgmUrls, []);
});

test('note DOM audio from recommendations, duplicate players, or wrong page cannot provide BGM', () => {
  const entry = album();
  delete entry.music;
  const data = {loaderData: {'note_(id)/page': {itemId: id, videoInfoRes: {item_list: [album(otherId), entry]}}}};
  const options = {page: `https://www.iesdouyin.com/share/note/${id}/`, audio: [audioPlayer()]};
  assert.deepEqual(run(data, [], options).albums[0].bgmUrls, []);
  data.loaderData['note_(id)/page'].videoInfoRes.item_list = [entry];
  assert.deepEqual(run(data, [], {...options, audio: [audioPlayer(), audioPlayer()]}).albums[0].bgmUrls, []);
  assert.deepEqual(run(data, [], {...options, page: `https://www.iesdouyin.com/share/note/${otherId}/`}).albums[0].bgmUrls, []);
  assert.deepEqual(run(data, [], {...options, page: `https://evil.test/share/note/${id}/`}).albums[0].bgmUrls, []);
  assert.deepEqual(run(data, [], {...options, audio: [audioPlayer({parentElement: {className: `video_${otherId}`, parentElement: null}})]}).albums[0].bgmUrls, []);
});

test('opaque or unsafe DOM audio is not converted into a downloadable soundtrack', () => {
  const entry = album();
  delete entry.music;
  const data = {loaderData: {'note_(id)/page': {itemId: id, videoInfoRes: {item_list: [entry]}}}};
  for (const src of ['opaque-id', 'blob:https://www.douyin.com/1', 'http://user:secret@sf3.douyinstatic.com/a.mp3']) {
    const result = run(data, [], {page: `https://www.iesdouyin.com/share/note/${id}/`,
      audio: [audioPlayer({currentSrc: src, src})]});
    assert.deepEqual(result.albums[0].bgmUrls, []);
  }
});
