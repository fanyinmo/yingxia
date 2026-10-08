package com.local.douyinsaver

/** Read-only desktop probe. Photo clips and optional video fallbacks require exact work ownership. */
object DesktopAlbumScript {
    fun extract(expectedId: String, allowVideo: Boolean = false): String {
        require(expectedId.matches(Regex("\\d{10,25}")))
        val allowVideoLiteral = if (allowVideo) "true" else "false"
        return """
            (function() {
                'use strict';
                const expectedId = '$expectedId';
                const allowVideo = $allowVideoLiteral;
                const MAX_IMAGES = 200, MAX_NODES = 12000, MAX_ROOTS = 512;
                const MAX_SCRIPTS = 512, MAX_TEXT = 2097152, MAX_SCRIPT_TOTAL = 8388608;
                const MAX_URLS = 16, MAX_RSC_RECORDS = 512, MAX_DOM = 200, MAX_KEYS = 1024;
                const asText = (x, n) => typeof x === 'string' ? x.slice(0, n) : '';
                const num = x => typeof x === 'number' && Number.isFinite(x) && x > 0 ? x : 0;
                const dim = x => num(x) <= 16384 ? Math.floor(num(x)) : 0;
                const unique = values => Array.from(new Set(values.filter(Boolean))).slice(0, MAX_URLS);
                const safeUrl = value => {
                    if (typeof value !== 'string' || value.length > 2048 || !/^https:\/\//i.test(value) ||
                        /[\u0000-\u0020\\]/.test(value)) return '';
                    try {
                        const u = new URL(value);
                        return u.protocol === 'https:' && !u.username && !u.password &&
                            (!u.port || u.port === '443') ? value : '';
                    } catch (_) { return ''; }
                };
                const host = value => { try { return new URL(value).hostname; } catch (_) { return ''; } };
                const state = ['loading', 'interactive', 'complete'].includes(document.readyState)
                    ? document.readyState : 'unknown';
                const stats = {state: state, title: asText(document.title, 120), pageHost: '', pageMatches: false,
                    scripts: 0, rscChunks: 0, rscRecords: 0, jsonRoots: 0, hydrationRoots: 0, ownedRscRoots: 0,
                    visitedNodes: 0, matchingOwners: 0, albumCandidates: 0, videoCandidates: 0, images: 0, declaredImages: 0,
                    motions: 0, missingPosters: 0, domPairedImages: 0, truncated: false,
                    conflictingAlbums: false, captchaVisible: false, loginGateVisible: false,
                    gate: 'none', provenance: 'none', mediaHosts: [], targetImages: [], targetImagesOmitted: 0,
                    workVariants: {},
                    limits: {roots: 0, jsonString: 0, jsonLiteral: 0, scriptSize: 0, scriptTotal: 0,
                        scriptCount: 0, rscChunks: 0, rscRecords: 0, windowRscTotal: 0,
                        ownedRscRoots: 0, ownedRscDecode: 0,
                        workImages: 0, albumCandidates: 0, objectKeys: 0, queueNodes: 0, variantImages: 0}};
                const truncated = reason => { stats.truncated = true; stats.limits[reason]++; };
                const result = {page: asText(location.href, 4096), owner: '', title: '', images: [],
                    bgmUrls: [], bgmDuration: 0, videoCandidates: [], status: 'loading', stats: stats};
                try {
                    const u = new URL(location.href);
                    const m = u.pathname.match(/^\/(?:note|video|slides)\/(\d{10,25})\/?$/);
                    stats.pageHost = u.hostname;
                    stats.pageMatches = u.protocol === 'https:' && !u.username && !u.password &&
                        (!u.port || u.port === '443') && (u.hostname === 'douyin.com' ||
                            u.hostname.endsWith('.douyin.com')) && !!m && m[1] === expectedId;
                } catch (_) {}
                if (!stats.pageMatches) { result.status = 'unavailable'; return JSON.stringify(result); }

                const roots = [], ownedRscRoots = [];
                const addRoot = (value, source, refs) => {
                    if (value === undefined || value === null) return;
                    if (roots.length >= MAX_ROOTS) { truncated('roots'); return; }
                    roots.push({value: value, source: source, refs: refs || new Map()});
                    stats.jsonRoots++;
                };
                const jsonLiteral = (value, captureOwner) => {
                    if (typeof captureOwner !== 'function') return JSON.parse(value);
                    try {
                        return JSON.parse(value, (_key, child) => { captureOwner(child); return child; });
                    } catch (error) {
                        // A reviver can hit the JavaScript stack before the native JSON decoder.
                        // Keep the previous bounded traversal usable, discarding partial captures.
                        if (!error || error.name !== 'RangeError') throw error;
                        captureOwner.reset(); truncated('ownedRscDecode');
                        return JSON.parse(value);
                    }
                };
                const decodeJson = (value, captureOwner) => {
                    let x = value;
                    for (let i = 0; i < 4 && typeof x === 'string'; i++) {
                        if (x.length > MAX_TEXT) { truncated('jsonString'); return null; }
                        const t = x.trim();
                        if (!t) return null;
                        try {
                            if (/^%(?:7b|5b|22)/i.test(t)) x = decodeURIComponent(t);
                            else if (/^[\[{"]/.test(t)) x = jsonLiteral(t, captureOwner);
                            else return null;
                        } catch (_) { return null; }
                    }
                    return x && typeof x === 'object' ? x : null;
                };
                // Reads one JSON literal; JavaScript expressions are deliberately not evaluated.
                const readLiteral = (text, start, captureOwner) => {
                    let p = start;
                    while (p < text.length && /\s/.test(text[p])) p++;
                    const begin = p, first = text[p];
                    if (first !== '[' && first !== '{' && first !== '"') return null;
                    let depth = 0, quoted = false, escaped = false;
                    for (; p < text.length && p - begin <= MAX_TEXT; p++) {
                        const c = text[p];
                        if (quoted) {
                            if (escaped) escaped = false;
                            else if (c === '\\') escaped = true;
                            else if (c === '"') {
                                quoted = false;
                                if (first === '"' && depth === 0) {
                                    try { return {value: jsonLiteral(text.slice(begin, p + 1), captureOwner), end: p + 1}; }
                                    catch (_) { return null; }
                                }
                            }
                        } else if (c === '"') quoted = true;
                        else if (c === '[' || c === '{') depth++;
                        else if (c === ']' || c === '}') {
                            depth--;
                            if (depth === 0) {
                                try { return {value: jsonLiteral(text.slice(begin, p + 1), captureOwner), end: p + 1}; }
                                catch (_) { return null; }
                            }
                        }
                    }
                    if (p - begin > MAX_TEXT) truncated('jsonLiteral');
                    return null;
                };
                const parseFlight = chunks => {
                    if (!chunks.length) return;
                    const stream = chunks.join('');
                    const refs = new Map(), values = [];
                    let pos = 0, records = 0;
                    while (pos < stream.length && records < MAX_RSC_RECORDS) {
                        while (pos < stream.length && /\s/.test(stream[pos])) pos++;
                        const marker = stream.slice(pos, pos + 64).match(/^([0-9a-fA-F]+):/);
                        if (!marker) {
                            const next = stream.indexOf('\n', pos);
                            if (next < 0) break;
                            pos = next + 1; continue;
                        }
                        const key = marker[1].toLowerCase();
                        let start = pos + marker[0].length;
                        // RSC tags such as I/D precede JSON records; text/binary records are ignored.
                        if (/[A-Za-z]/.test(stream[start] || '') && /[\[{]/.test(stream[start + 1] || '')) start++;
                        // Capture only actual same-work objects during the already bounded JSON
                        // decode. Generic traversal can exhaust its queue on unrelated strings or
                        // omit an owner beyond an object's first 256 keys. These roots retain this
                        // stream's refs Map; forward references resolve after all records are read.
                        const owned = [];
                        let omitted = 0;
                        const captureOwner = value => {
                            if (!value || typeof value !== 'object' || Array.isArray(value)) return;
                            const ids = [value.awemeId, value.aweme_id]
                                .filter(x => x !== undefined && x !== null && x !== '');
                            if (!ids.length || !ids.every(x => typeof x === 'string' && x === expectedId) ||
                                !['video', 'images', 'image_list', 'imagePostInfo', 'image_post_info']
                                    .some(key => Object.prototype.hasOwnProperty.call(value, key))) return;
                            if (owned.length + ownedRscRoots.length >= 16) { omitted++; return; }
                            owned.push(value);
                        };
                        captureOwner.reset = () => { owned.length = 0; omitted = 0; };
                        const parsed = readLiteral(stream, start, captureOwner);
                        records++;
                        if (parsed) {
                            if (typeof parsed.value === 'string' && !decodeJson(parsed.value, captureOwner)) {
                                owned.length = 0; omitted = 0;
                            }
                            owned.forEach(value => ownedRscRoots.push({value: value, source: 'rsc', refs: refs}));
                            if (omitted) {
                                stats.truncated = true; stats.limits.ownedRscRoots += omitted;
                            }
                            refs.set(key, parsed.value); values.push(parsed.value); pos = parsed.end;
                        } else {
                            const next = stream.indexOf('\n', start);
                            if (next < 0) break;
                            pos = next + 1;
                        }
                    }
                    if (records >= MAX_RSC_RECORDS && pos < stream.length) truncated('rscRecords');
                    stats.rscRecords += refs.size;
                    values.forEach(x => addRoot(x, 'rsc', refs));
                };
                const collectChunk = (payload, chunks) => {
                    if (!Array.isArray(payload) || payload[0] !== 1 || typeof payload[1] !== 'string') return;
                    if (payload[1].length > MAX_TEXT) { truncated('jsonString'); return; }
                    if (chunks.length >= MAX_RSC_RECORDS) { truncated('rscChunks'); return; }
                    chunks.push(payload[1]); stats.rscChunks++;
                };
                const scriptElements = document.querySelectorAll('script');
                if (scriptElements.length > MAX_SCRIPTS) truncated('scriptCount');
                const scripts = Array.prototype.slice.call(scriptElements, 0, MAX_SCRIPTS);
                const scriptChunks = [];
                let scriptTotal = 0;
                scripts.forEach(script => {
                    const text = typeof script.textContent === 'string' ? script.textContent : '';
                    stats.scripts++;
                    if (text.length > MAX_TEXT) { truncated('scriptSize'); return; }
                    if (scriptTotal + text.length > MAX_SCRIPT_TOTAL) { truncated('scriptTotal'); return; }
                    scriptTotal += text.length;
                    const push = /(?:self|window)\.__pace_f\.push\s*\(/g;
                    let match, pushes = 0;
                    while ((match = push.exec(text)) && pushes++ < MAX_RSC_RECORDS) {
                        const literal = readLiteral(text, push.lastIndex);
                        if (literal) {
                            if (/^\s*\)/.test(text.slice(literal.end, literal.end + 64))) collectChunk(literal.value, scriptChunks);
                            push.lastIndex = literal.end;
                        }
                    }
                    const scriptId = asText(script.id, 80);
                    if (['RENDER_DATA', '_RENDER_DATA', '__NEXT_DATA__', '__INITIAL_STATE__', '__INITIAL_DATA__'].includes(scriptId) ||
                        script.type === 'application/json') {
                        const x = decodeJson(text);
                        if (x) addRoot(x, scriptId.includes('RENDER') ? 'render' : 'hydration');
                    }
                    const assignment = /(?:window|self)\.(?:_ROUTER_DATA|_RENDER_DATA|RENDER_DATA|__INITIAL_STATE__|__INITIAL_DATA__|__NEXT_DATA__)\s*=\s*/g;
                    let assigned, count = 0;
                    while ((assigned = assignment.exec(text)) && count++ < 32) {
                        const literal = readLiteral(text, assignment.lastIndex);
                        if (!literal) continue;
                        const x = typeof literal.value === 'string' ? decodeJson(literal.value) : literal.value;
                        if (x) addRoot(x, assigned[0].includes('ROUTER') ? 'router' :
                            assigned[0].includes('RENDER') ? 'render' : 'hydration');
                        assignment.lastIndex = literal.end;
                    }
                });
                parseFlight(scriptChunks);
                const windowChunks = [];
                if (Array.isArray(window.__pace_f)) {
                    let total = 0;
                    window.__pace_f.slice(0, MAX_RSC_RECORDS).forEach(payload => {
                        const n = Array.isArray(payload) && typeof payload[1] === 'string' ? payload[1].length : 0;
                        if (total + n <= MAX_SCRIPT_TOTAL) { total += n; collectChunk(payload, windowChunks); }
                        else truncated('windowRscTotal');
                    });
                }
                parseFlight(windowChunks);
                ['_ROUTER_DATA', '_RENDER_DATA', 'RENDER_DATA', '__INITIAL_STATE__', '__INITIAL_DATA__', '__NEXT_DATA__'].forEach(key => {
                    const value = window[key];
                    addRoot(typeof value === 'string' ? decodeJson(value) : value,
                        key.includes('ROUTER') ? 'router' : key.includes('RENDER') ? 'render' : 'hydration');
                });
                const domSeeds = Array.from(document.querySelectorAll(
                    '.dySwiperSlide,.note-detail-container,[data-aweme-id],[data-awemeid]')).slice(0, 64);
                if (document.body) domSeeds.push(document.body);
                domSeeds.forEach(el => {
                    Object.keys(el).slice(0, 128).filter(key => key.startsWith('__reactFiber') ||
                        key.startsWith('__reactProps') || key.startsWith('__reactContainer')).forEach(key => {
                        addRoot(el[key], 'hydration'); stats.hydrationRoots++;
                    });
                });
                const resolve = (value, refs) => {
                    let x = value;
                    const seen = new Set();
                    for (let i = 0; i < 16 && typeof x === 'string' && x[0] === String.fromCharCode(36); i++) {
                        const m = x.slice(1).match(/^(?:L|@)?([0-9a-fA-F]+)$/);
                        if (!m || seen.has(m[1]) || !refs.has(m[1].toLowerCase())) break;
                        seen.add(m[1]); x = refs.get(m[1].toLowerCase());
                    }
                    return x;
                };
                const urls = (value, refs, audio, depth) => {
                    if ((depth || 0) > 4) return [];
                    const x = resolve(value, refs);
                    if (typeof x === 'string') {
                        const u = audio && /^http:\/\//i.test(x) ? safeUrl(x.replace(/^http:/i, 'https:')) : safeUrl(x);
                        return u ? [u] : [];
                    }
                    if (Array.isArray(x)) return unique(x.slice(0, MAX_URLS).flatMap(v => urls(v, refs, audio, (depth || 0) + 1)));
                    if (!x || typeof x !== 'object') return [];
                    return unique(['src', 'url', 'uri', 'url_list', 'urlList'].flatMap(key =>
                        urls(x[key], refs, audio, (depth || 0) + 1)));
                };
                const photoKey = image => {
                    const key = image && [image.uri, image.imageUri, image.image_id, image.imageId]
                        .find(x => typeof x === 'string' && x.length > 0 && x.length <= 2048 && !/[\u0000-\u0020]/.test(x));
                    return key || '';
                };
                const mime = image => {
                    const value = image && (image.mimeType || image.mime_type);
                    return ['image/jpeg', 'image/png', 'image/webp', 'image/gif', 'image/heic', 'image/heif', 'image/avif'].includes(value)
                        ? value : '';
                };
                const mediaId = x => typeof x === 'string' && /^[A-Za-z0-9_-]{10,256}$/.test(x) && /[A-Za-z]/.test(x) ? x : '';
                const motion = (raw, refs, ownedCamelPlayback) => {
                    const video = resolve(raw, refs);
                    if (!video || typeof video !== 'object' || Array.isArray(video)) return null;
                    const rates = [video.bitRateList, video.bit_rate, video.bitrate].flatMap(x => {
                        const r = resolve(x, refs); return Array.isArray(r) ? r.slice(0, 16).map(x => resolve(x, refs)) : [];
                    }).filter(x => x && typeof x === 'object' && !Array.isArray(x)).slice(0, 32);
                    const addresses = (entry, prefix) => [prefix, prefix + '_h264', prefix + '_265', prefix + '_h265',
                        prefix + '_bytevc1', prefix + '_bytevc2', prefix === 'play_addr' ? 'playAddr' : 'downloadAddr']
                        .map(key => resolve(entry[key], refs));
                    // Rank only representations read from this photo's own video object. Unknown,
                    // invalid and equal metadata retain their original order; URLs are never edited.
                    const bounded = (value, max) => num(value) <= max ? num(value) : 0;
                    const rank = (entry, index) => {
                        const play = addresses(entry, 'play_addr');
                        const addressObjects = play.flatMap(x => Array.isArray(x) ? x : [x])
                            .map(x => resolve(x, refs)).filter(x => x && typeof x === 'object' && !Array.isArray(x));
                        const dimensions = [entry, ...addressObjects].find(x => dim(x.width) && dim(x.height));
                        const width = dimensions ? dim(dimensions.width) : 0, height = dimensions ? dim(dimensions.height) : 0;
                        const first = keys => [entry, ...addressObjects].flatMap(x => keys.map(key => x[key]));
                        const number = (keys, max) => first(keys).map(value => bounded(value, max)).find(Boolean) || 0;
                        return {entry: entry, index: index, width: width, height: height,
                            quality: [Math.max(width, height), Math.min(width, height), number(['fps', 'FPS'], 240),
                                number(['bitRate', 'bit_rate'], 1000000000), number(['dataSize', 'data_size'], 2147483648)]};
                    };
                    const ordered = [video, ...rates].map(rank).sort((a, b) => {
                        for (let i = 0; i < a.quality.length; i++) if (a.quality[i] !== b.quality[i])
                            return b.quality[i] - a.quality[i];
                        return a.index - b.index;
                    });
                    const play = ordered.flatMap(x => addresses(x.entry, 'play_addr'));
                    const download = ordered.flatMap(x => addresses(x.entry, 'download_addr'));
                    const playUrls = unique(play.flatMap(x => urls(x, refs)));
                    const downloadUrls = unique(download.flatMap(x => urls(x, refs)));
                    // This role belongs only to an exactly owned desktop video DTO. Snake
                    // addresses, live-photo fallback data and DOM players never receive it.
                    const camelRates = resolve(video.bitRateList, refs);
                    const displayEntries = new Set([video,
                        ...(Array.isArray(camelRates) ? camelRates.slice(0, 16).map(x => resolve(x, refs))
                            .filter(x => x && typeof x === 'object' && !Array.isArray(x)) : [])]);
                    const displayPlaybackUrls = ownedCamelPlayback ? unique(ordered
                        .filter(x => displayEntries.has(x.entry)).map(x => x.entry.playAddr)
                        .flatMap(x => urls(x, refs))).filter(url => playUrls.includes(url)) : [];
                    const mediaIds = unique([mediaId(video.video_id), mediaId(video.videoId), ...play.flatMap(x => {
                        const a = Array.isArray(x) ? x : [x];
                        return a.slice(0, MAX_URLS).map(v => v && typeof v === 'object' ? mediaId(v.uri) : '');
                    })]);
                    if (!playUrls.length && !downloadUrls.length && !mediaIds.length) return null;
                    const preferred = ordered.find(x => addresses(x.entry, 'play_addr').some(value => urls(value, refs).length)) || ordered[0];
                    return {playUrls: playUrls, downloadUrls: downloadUrls, mediaIds: mediaIds,
                        displayPlaybackUrls: displayPlaybackUrls,
                        width: preferred.width || dim(video.width), height: preferred.height || dim(video.height),
                        durationSeconds: num(video.durationSeconds) || num(video.duration) / 1000};
                };
                const clipEligible = new WeakSet(), imageDiagnostics = new WeakMap();
                const imageEntry = (raw, index, refs) => {
                    const image = resolve(raw, refs);
                    const x = image && typeof image === 'object' && !Array.isArray(image) ? image : {};
                    const display = unique([x.urlList, x.url_list, x.displayImage, x.display_image].flatMap(v => urls(v, refs)));
                    const download = unique([x.downloadUrl, x.download_url, x.downloadAddr, x.download_addr,
                        x.downloadUrlList, x.download_url_list].flatMap(v => urls(v, refs)));
                    const liveInfo = resolve(x.livePhotoInfo || x.live_photo_info, refs);
                    const ownVideo = resolve(x.video, refs);
                    const videoObject = ownVideo && typeof ownVideo === 'object' && !Array.isArray(ownVideo) ? ownVideo : {};
                    // Preserve the already verified official 1=Video, 2=Image, 3=LivePhoto,
                    // 4=Default policy for mobile numeric tags. Desktop camel clipType=5 was
                    // observed with an owned playAddr; the flag alone does not prove motion.
                    const numericTag = value => typeof value === 'number' && Number.isFinite(value) ? value : undefined;
                    const snakeTag = numericTag(x.clip_type), camelTag = numericTag(x.clipType);
                    const tag = snakeTag === undefined ? camelTag : snakeTag;
                    const hasTag = tag !== undefined;
                    const desktopFive = snakeTag === undefined && camelTag === 5 && urls(videoObject.playAddr, refs).length > 0;
                    const mayHaveClip = !hasTag || [1, 3, 4].includes(tag) || desktopFive;
                    const clip = mayHaveClip ? motion(x.video, refs, true) ||
                        (!desktopFive ? motion(liveInfo && liveInfo.video, refs) : null) : null;
                    const out = {urls: unique([...download, ...display]), displayUrls: display, downloadUrls: download,
                        width: dim(x.width), height: dim(x.height), kind: tag === 3 ? 'LIVE' : clip && tag === 1 ? 'ANIMATED' : clip ? 'DYNAMIC' : 'STATIC',
                        mimeType: mime(x), imageKey: photoKey(x), sourceIndex: index};
                    if (mayHaveClip) clipEligible.add(out);
                    if (clip) out.motion = clip;
                    // Diagnostics describe only the owned photo object's shape, never payload values.
                    const valueType = value => value === undefined || value === null ? 'missing' :
                        Array.isArray(value) ? 'array' : ['object', 'string', 'number', 'boolean'].includes(typeof value) ?
                            typeof value : 'other';
                    const ownClip = motion(x.video, refs);
                    const playAddr = resolve(videoObject.playAddr, refs), playSnake = resolve(videoObject.play_addr, refs);
                    const camelUrls = urls(playAddr, refs), snakeUrls = urls(playSnake, refs);
                    const rateCount = [videoObject.bitRateList, videoObject.bit_rate, videoObject.bitrate].reduce((total, raw) => {
                        const rates = resolve(raw, refs); return total + (Array.isArray(rates) ? Math.min(rates.length, 16) : 0);
                    }, 0);
                    const diagnostic = {sourceIndex: index, kind: out.kind, fields: {},
                        ownVideoPresent: Object.prototype.hasOwnProperty.call(x, 'video'), ownVideoType: valueType(ownVideo),
                        ownVideoObject: !!(ownVideo && typeof ownVideo === 'object' && !Array.isArray(ownVideo)),
                        videoKeys: ['playAddr', 'play_addr', 'downloadAddr', 'download_addr', 'bitRateList', 'bit_rate',
                            'bitrate', 'width', 'height', 'duration', 'durationSeconds', 'videoId', 'video_id']
                            .filter(key => Object.prototype.hasOwnProperty.call(videoObject, key)),
                        playAddrType: valueType(playAddr), play_addrType: valueType(playSnake),
                        playAddrUrls: camelUrls.length, play_addrUrls: snakeUrls.length,
                        playAddrHosts: unique(camelUrls.map(host)), play_addrHosts: unique(snakeUrls.map(host)),
                        bitrateCount: rateCount, mediaIdPresent: !!(ownClip && ownClip.mediaIds.length),
                        candidateMotion: !!clip, playUrls: clip ? clip.playUrls.length : 0,
                        displayPlaybackUrls: clip ? clip.displayPlaybackUrls.length : 0,
                        downloadUrls: clip ? clip.downloadUrls.length : 0, mediaIds: clip ? clip.mediaIds.length : 0,
                        hosts: ownClip ? unique([...ownClip.playUrls, ...ownClip.downloadUrls].map(host)) : []};
                    ['uri', 'imageUri', 'imageId', 'image_id', 'urlList', 'url_list', 'displayImage', 'display_image',
                        'downloadUrl', 'download_url', 'downloadAddr', 'download_addr', 'downloadUrlList', 'download_url_list',
                        'width', 'height', 'video', 'livePhotoInfo', 'live_photo_info', 'clip_type', 'clipType',
                        'mimeType', 'mime_type'].forEach(key => diagnostic.fields[key] = Object.prototype.hasOwnProperty.call(x, key));
                    ['clip_type', 'clipType'].forEach(key => {
                        if (typeof x[key] === 'number' && Number.isFinite(x[key])) diagnostic[key] = x[key];
                    });
                    imageDiagnostics.set(out, diagnostic);
                    return out;
                };
                const bgm = (work, refs) => {
                    const video = resolve(work.video, refs) || {}, music = resolve(work.music, refs) || {};
                    const own = unique([video.play_addr, video.playAddr].flatMap(x => urls(x, refs, true)));
                    const musicUrls = urls(music.play_url || music.playUrl, refs, true);
                    return {urls: unique([...own, ...musicUrls]),
                        duration: own.length && num(video.duration) ? num(video.duration) / 1000 :
                            musicUrls.length ? num(music.duration) : 0};
                };
                const candidates = [], ownAudio = [];
                let ownDuration = 0, declaredMax = 0;
                const variantFields = ['img_bitrate', 'imgBitrate', 'imageBitrate', 'image_bit_rate', 'imagePostInfo', 'image_post_info'];
                variantFields.forEach(key => stats.workVariants[key] = {present: false, entries: 0, images: 0,
                    scannedImages: 0, videoObjects: 0, playUrls: 0, downloadUrls: 0, mediaIds: 0});
                let variantBudget = 1200;
                const inspectVariants = (work, refs) => variantFields.forEach(key => {
                    const summary = {present: Object.prototype.hasOwnProperty.call(work, key), entries: 0, images: 0,
                        scannedImages: 0, videoObjects: 0, playUrls: 0, downloadUrls: 0, mediaIds: 0};
                    const value = resolve(work[key], refs);
                    const entries = Array.isArray(value) ? value.slice(0, 16) : value && typeof value === 'object' ? [value] : [];
                    summary.entries = Array.isArray(value) ? Math.min(value.length, 16) : entries.length;
                    entries.forEach(raw => {
                        const entry = resolve(raw, refs);
                        if (!entry || typeof entry !== 'object') return;
                        const images = resolve(entry.images, refs);
                        if (!Array.isArray(images)) return;
                        summary.images += Math.min(images.length, MAX_IMAGES);
                        if (variantBudget <= 0 && images.length) { truncated('variantImages'); return; }
                        images.slice(0, MAX_IMAGES).forEach(rawImage => {
                            if (variantBudget <= 0) { truncated('variantImages'); return; }
                            variantBudget--; summary.scannedImages++;
                            const image = resolve(rawImage, refs), video = image && resolve(image.video, refs);
                            if (video && typeof video === 'object' && !Array.isArray(video)) summary.videoObjects++;
                            const clip = motion(video, refs);
                            if (clip) {
                                summary.playUrls += clip.playUrls.length; summary.downloadUrls += clip.downloadUrls.length;
                                summary.mediaIds += clip.mediaIds.length;
                            }
                        });
                    });
                    const previous = stats.workVariants[key];
                    previous.present = previous.present || summary.present;
                    Object.keys(summary).filter(name => name !== 'present').forEach(name =>
                        previous[name] = Math.max(previous[name], summary[name]));
                });
                // Preferred roots replace generic slots, never enlarge the total root budget.
                const ownedValues = new Set(ownedRscRoots.map(entry => entry.value));
                const pendingRoots = [...ownedRscRoots, ...roots.filter(entry => !ownedValues.has(entry.value))];
                if (pendingRoots.length > MAX_ROOTS) truncated('roots');
                const queue = pendingRoots.slice(0, MAX_ROOTS), visited = new Set();
                stats.ownedRscRoots = ownedRscRoots.length; stats.jsonRoots = queue.length;
                for (let cursor = 0; cursor < queue.length && cursor < MAX_NODES; cursor++) {
                    const entry = queue[cursor];
                    let value = resolve(entry.value, entry.refs);
                    if (typeof value === 'string') value = decodeJson(value);
                    if (!value || typeof value !== 'object' || visited.has(value)) continue;
                    visited.add(value); stats.visitedNodes++;
                    const ids = [value.awemeId, value.aweme_id].filter(x => x !== undefined && x !== null && x !== '');
                    if (ids.length && ids.every(x => typeof x === 'string' && x === expectedId)) {
                        stats.matchingOwners++;
                        inspectVariants(value, entry.refs);
                        const audio = bgm(value, entry.refs);
                        if (ownAudio.length < 64) ownAudio.push(...audio.urls.slice(0, 64 - ownAudio.length));
                        if (!ownDuration && audio.duration) ownDuration = audio.duration;
                        const post = resolve(value.imagePostInfo || value.image_post_info, entry.refs) || {};
                        const arrays = [value.images, post.images, value.image_list, post.image_list]
                            .map(x => resolve(x, entry.refs));
                        const images = arrays.find(x => Array.isArray(x) && x.length > 0);
                        // The work-level video of a note can be BGM or a placeholder. Only a
                        // separate ordinary-video fallback may inspect the owned desktop DTO.
                        const type = value.awemeType === undefined ? value.aweme_type : value.awemeType;
                        const declaredPhotos = Math.max(num(value.imageCount), num(value.image_count),
                            num(post.imageCount), num(post.image_count));
                        if (allowVideo && !images && declaredPhotos === 0 && ![2, 68, 150].includes(type) &&
                            result.videoCandidates.length < 16) {
                            const candidate = motion(value.video, entry.refs, true);
                            if (candidate && candidate.width > 0 && candidate.height > 0 && candidate.durationSeconds > 0) {
                                const videoObject = resolve(value.video, entry.refs) || {};
                                result.videoCandidates.push({...candidate, owner: expectedId,
                                    title: asText(value.desc || value.title, 500), provenance: entry.source,
                                    coverUrls: unique(['originCover', 'origin_cover', 'cover', 'dynamicCover', 'dynamic_cover']
                                        .flatMap(key => urls(videoObject[key], entry.refs)))});
                            }
                        }
                        if (images) {
                            const declared = Math.max(images.length, num(value.imageCount), num(value.image_count),
                                num(post.imageCount), num(post.image_count));
                            declaredMax = Math.max(declaredMax, declared);
                            if (images.length > MAX_IMAGES) truncated('workImages');
                            else if (candidates.length >= 16) truncated('albumCandidates');
                            else candidates.push({images: images.map((x, i) => imageEntry(x, i, entry.refs)),
                                declared: declared, title: asText(value.desc || value.title, 500), source: entry.source});
                        }
                    }
                    const keys = Object.keys(value);
                    // RSC exact-owner roots were captured during bounded JSON decoding;
                    // avoid traversing their unrelated payloads a second time. Router and
                    // live hydration roots need room beyond the old first-256-key cutoff.
                    const keyLimit = entry.source === 'rsc' ? 256 : MAX_KEYS;
                    if (keys.length > keyLimit) truncated('objectKeys');
                    keys.slice(0, keyLimit).forEach(key => {
                        if (['stateNode', 'ref', '_debugOwner', '_debugInfo'].includes(key)) return;
                        if (queue.length >= MAX_NODES) { truncated('queueNodes'); return; }
                        let child;
                        try { child = value[key]; } catch (_) { return; }
                        if (child && (typeof child === 'object' || typeof child === 'string'))
                            queue.push({value: child, refs: entry.refs, source: entry.source});
                    });
                }
                stats.albumCandidates = candidates.length;
                const score = album => [album.images.length, album.images.filter(x => x.urls.length).length,
                    album.images.filter(x => x.motion).length];
                candidates.sort((a, b) => {
                    const sa = score(a), sb = score(b);
                    for (let i = 0; i < sa.length; i++) if (sa[i] !== sb[i]) return sb[i] - sa[i];
                    return 0;
                });
                const selected = candidates[0];
                stats.videoCandidates = result.videoCandidates.length;
                if (!selected && result.videoCandidates.length) {
                    result.owner = expectedId; result.title = result.videoCandidates[0].title;
                    stats.provenance = result.videoCandidates[0].provenance;
                }
                if (selected) {
                    result.owner = expectedId; result.title = selected.title; result.images = selected.images;
                    stats.provenance = selected.source;
                    const keys = new Map();
                    selected.images.forEach(image => {
                        if (!image.imageKey) return;
                        if (keys.has(image.imageKey)) keys.set(image.imageKey, null);
                        else keys.set(image.imageKey, image);
                    });
                    // A later complete copy can enrich a photo only by its exact key, never by order.
                    candidates.slice(1).forEach(album => {
                        if (album.images.length !== selected.images.length) return;
                        if (album.images.some((image, i) => image.imageKey && selected.images[i].imageKey &&
                            image.imageKey !== selected.images[i].imageKey)) stats.conflictingAlbums = true;
                        album.images.forEach(image => {
                            const target = image.imageKey && keys.get(image.imageKey);
                            if (!target) return;
                            ['displayUrls', 'downloadUrls'].forEach(key => target[key] = unique([...target[key], ...image[key]]));
                            target.urls = unique([...target.downloadUrls, ...target.displayUrls]);
                            if (!target.motion && image.motion && clipEligible.has(target)) { target.motion = image.motion; if (target.kind === 'STATIC') target.kind = 'DYNAMIC'; }
                            else if (target.motion && image.motion) {
                                ['playUrls', 'downloadUrls', 'mediaIds', 'displayPlaybackUrls'].forEach(key =>
                                    target.motion[key] = unique([...target.motion[key], ...image.motion[key]]));
                                target.motion.displayPlaybackUrls = target.motion.displayPlaybackUrls
                                    .filter(url => target.motion.playUrls.includes(url));
                            }
                        });
                    });
                    result.bgmUrls = unique(ownAudio); result.bgmDuration = ownDuration;
                }
                const visible = element => {
                    if (!element || typeof element.getBoundingClientRect !== 'function') return false;
                    const r = element.getBoundingClientRect();
                    if (!r || r.width <= 0 || r.height <= 0) return false;
                    if (typeof window.getComputedStyle === 'function') {
                        const s = window.getComputedStyle(element);
                        if (s && (s.display === 'none' || s.visibility === 'hidden' || s.opacity === '0')) return false;
                    }
                    return true;
                };
                const elements = selector => Array.from(document.querySelectorAll(selector)).slice(0, MAX_DOM);
                stats.captchaVisible = elements('[id*="captcha"],[class*="captcha"],iframe[src*="captcha"],[data-e2e*="captcha"]')
                    .some(visible);
                stats.loginGateVisible = elements('dialog,[role="dialog"],[aria-modal="true"],[class*="login-modal"],'+
                    '[class*="loginModal"],[class*="login-dialog"],[class*="loginDialog"],[data-e2e="login-page"]')
                    .some(element => visible(element) && /(?:扫码|手机号|账号|验证码|立即)?登[录陆]/.test(asText(element.innerText || element.textContent, 2000)));
                stats.gate = stats.captchaVisible ? 'captcha' : stats.loginGateVisible ? 'login' : 'none';
                // DOM is only an enrichment of an already complete, exactly owned metadata array.
                // No generic first video, current carousel position, or route-only ownership is used.
                const attr = (element, names) => {
                    for (const name of names) {
                        const v = typeof element.getAttribute === 'function' ? element.getAttribute(name) : null;
                        if (typeof v === 'string' && v) return v;
                    }
                    return '';
                };
                const domOwner = element => {
                    for (let node = element, i = 0; node && i < 32; node = node.parentElement, i++) {
                        const value = attr(node, ['data-aweme-id', 'data-awemeid']);
                        if (value) return value === expectedId ? expectedId : '';
                        const classes = typeof node.className === 'string' ? node.className.split(/\s+/) : [];
                        const matches = classes.filter(x => /^video_\d{10,25}$/.test(x));
                        if (matches.length) return matches.length === 1 && matches[0].slice(6) === expectedId ? expectedId : '';
                    }
                    return '';
                };
                if (selected) elements('.dySwiperSlide,[data-image-uri],[data-image-id]').forEach(slide => {
                    if (domOwner(slide) !== expectedId || typeof slide.querySelector !== 'function') return;
                    const key = attr(slide, ['data-image-uri', 'data-image-id']);
                    if (!key) return;
                    const matches = result.images.filter(x => x.imageKey === key);
                    if (matches.length !== 1) return;
                    const image = matches[0], poster = slide.querySelector('img'), player = slide.querySelector('video');
                    const posterUrl = poster ? safeUrl(poster.currentSrc || poster.src) : '';
                    if (posterUrl) {
                        image.displayUrls = unique([...image.displayUrls, posterUrl]);
                        image.urls = unique([...image.downloadUrls, ...image.displayUrls]);
                    }
                    const source = player && typeof player.querySelector === 'function' ? player.querySelector('source') : null;
                    const clipUrl = player ? safeUrl(player.currentSrc || player.src || (source && source.src)) : '';
                    if (clipUrl && clipEligible.has(image)) {
                        const clip = {playUrls: [clipUrl], downloadUrls: [], mediaIds: [], displayPlaybackUrls: [], width: dim(player.videoWidth),
                            height: dim(player.videoHeight), durationSeconds: num(player.duration)};
                        if (!image.motion) image.motion = clip;
                        else image.motion.playUrls = unique([...image.motion.playUrls, clipUrl]);
                        if (image.kind === 'STATIC') image.kind = 'DYNAMIC';
                    }
                    if (posterUrl || clipUrl) stats.domPairedImages++;
                });
                stats.images = result.images.length; stats.declaredImages = declaredMax;
                stats.missingPosters = result.images.filter(x => !x.urls.length).length;
                stats.motions = result.images.filter(x => x.motion).length;
                stats.targetImages = result.images.slice(0, 12).map(image => {
                    const diagnostic = imageDiagnostics.get(image);
                    if (!diagnostic) return {sourceIndex: image.sourceIndex, kind: image.kind};
                    return {...diagnostic, kind: image.kind, candidateMotion: !!image.motion,
                        playUrls: image.motion ? image.motion.playUrls.length : 0,
                        displayPlaybackUrls: image.motion ? image.motion.displayPlaybackUrls.length : 0,
                        downloadUrls: image.motion ? image.motion.downloadUrls.length : 0,
                        mediaIds: image.motion ? image.motion.mediaIds.length : 0};
                });
                stats.targetImagesOmitted = Math.max(0, result.images.length - stats.targetImages.length);
                stats.mediaHosts = unique([...result.bgmUrls, ...result.images.flatMap(x => [
                    ...x.urls, ...(x.motion ? [...x.motion.playUrls, ...x.motion.downloadUrls] : [])])].map(host));
                if (stats.gate !== 'none') result.status = 'needs_verification';
                else if (selected && !stats.missingPosters && result.images.length >= declaredMax) result.status = 'candidate';
                else if (!selected && allowVideo && result.videoCandidates.length) result.status = 'candidate';
                else result.status = state === 'complete' ? 'unavailable' : 'loading';
                return JSON.stringify(result);
            })();
        """.trimIndent()
    }
}
