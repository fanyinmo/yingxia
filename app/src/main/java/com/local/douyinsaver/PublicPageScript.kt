package com.local.douyinsaver

/** Extracts only public data associated with the exact requested work. */
object PublicPageScript {
    fun extract(id: String): String {
        require(id.matches(Regex("\\d{10,25}")))
        return """
            (function() {
                const id = '$id';
                const players = Array.from(document.querySelectorAll('video'));
                const route = window._ROUTER_DATA;
                const mobile = route && route.loaderData &&
                    (route.loaderData['video_(id)/page'] || route.loaderData['note_(id)/page'] ||
                        route.loaderData['slides_(id)/page']);
                const text = (value, limit) => typeof value === 'string' ? value.slice(0, limit) : '';
                const finite = value => typeof value === 'number' && Number.isFinite(value) ? value : 0;
                const https = value => {
                    if (typeof value !== 'string' || !/^https:\/\//i.test(value) || /[\u0000-\u0020\\]/.test(value)) return '';
                    try {
                        const url = new URL(value);
                        return url.protocol === 'https:' && !url.username && !url.password &&
                            (!url.port || url.port === '443') ? value : '';
                    } catch (_) { return ''; }
                };
                const host = value => {
                    try { return new URL(value).hostname; } catch (_) { return ''; }
                };
                const owner = player => {
                    for (let node = player; node; node = node.parentElement) {
                        const classes = typeof node.className === 'string' ? node.className.split(/\s+/) : [];
                        const owners = classes.filter(c => /^video_\d{10,25}$/.test(c));
                        if (owners.length) return owners.length === 1 ? owners[0].slice(6) : '';
                    }
                    return '';
                };
                let mobileLocation = false;
                let albumLocation = false;
                try {
                    const pageUrl = new URL(location.href);
                    const match = pageUrl.pathname.match(/^\/share\/video\/(\d{10,25})\/?$/);
                    const albumMatch = pageUrl.pathname.match(/^\/share\/(?:video|note|slides)\/(\d{10,25})\/?$/);
                    const pageHost = pageUrl.hostname.toLowerCase();
                    const trustedPage = pageUrl.protocol === 'https:' && !pageUrl.username && !pageUrl.password &&
                        (!pageUrl.port || pageUrl.port === '443') &&
                        (pageHost === 'iesdouyin.com' || pageHost.endsWith('.iesdouyin.com') ||
                         pageHost === 'douyin.com' || pageHost.endsWith('.douyin.com'));
                    mobileLocation = trustedPage && !!match && match[1] === id;
                    albumLocation = trustedPage && !!albumMatch && albumMatch[1] === id;
                } catch (_) {}
                const mobilePlayers = players.filter(p => p.id === 'video-player');
                const desktopPlayers = players.filter(p => owner(p) === id);
                const mobileMatches = !!mobile && mobile.itemId === id;
                const mobilePlayer = mobileLocation && mobileMatches && mobilePlayers.length === 1 &&
                    (!owner(mobilePlayers[0]) || owner(mobilePlayers[0]) === id) ? mobilePlayers[0] : null;
                const v = desktopPlayers.length === 1 ? desktopPlayers[0] : mobilePlayer;
                const visibleText = (document.body && document.body.innerText) || '';
                const knownErrors = ['抱歉出错了', '请尝试在抖音内观看', '作品已删除', '私密视频', '验证码']
                    .filter(phrase => visibleText.includes(phrase));
                const stats = {
                    state: document.readyState,
                    visibility: document.visibilityState,
                    videos: players.length,
                    target: !!v,
                    routeType: typeof route,
                    pageKeys: mobile && typeof mobile === 'object' ? Object.keys(mobile).slice(0, 60) : [],
                    itemMatches: mobileMatches,
                    videoInfoPresent: !!mobile && Object.prototype.hasOwnProperty.call(mobile, 'videoInfoRes'),
                    knownErrors: knownErrors,
                    title: text(document.title, 120),
                    players: players.slice(0, 8).map(p => ({
                        ready: finite(p.readyState), width: finite(p.videoWidth), height: finite(p.videoHeight),
                        duration: finite(p.duration), host: host(p.currentSrc || p.src || ''),
                        target: p === v
                    })),
                    captchaVisible: Array.from(document.querySelectorAll('[id*="captcha"],iframe[src*="captcha"]'))
                        .some(e => { const r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; })
                };
                const canonical = document.querySelector('link[rel="canonical"]');
                const result = {page: location.href, owner: '', url: '', title: '', ready: 0,
                    width: 0, height: 0, duration: 0, structured: false, stats: stats, candidates: [],
                    albums: [], canonical: canonical ? text(canonical.href, 2048) : ''};
                const urlList = value => {
                    if (typeof value === 'string') return [https(value)].filter(Boolean);
                    if (Array.isArray(value)) return value.slice(0, 64).map(https).filter(Boolean);
                    return value && typeof value === 'object' ? urlList(value.url_list) : [];
                };
                const displayImageUrls = image => {
                    if (!image || typeof image !== 'object') return [];
                    return [image.url_list, image.display_image].flatMap(urlList);
                };
                const downloadImageUrls = image => {
                    if (!image || typeof image !== 'object') return [];
                    return [image.download_url, image.download_addr, image.download_url_list].flatMap(urlList);
                };
                const imageAlternates = work => {
                    const byUri = new Map();
                    // The official note page pairs bitrate/display and original images by URI.
                    // Position alone cannot associate a variant with the requested photo.
                    (Array.isArray(work.img_bitrate) ? work.img_bitrate : []).slice(0, 16).forEach(gear => {
                        (gear && Array.isArray(gear.images) ? gear.images : []).slice(0, 200).forEach(image => {
                            if (!image || typeof image.uri !== 'string' || !image.uri || image.uri.length > 2048) return;
                            const matching = byUri.get(image.uri) || [];
                            if (matching.length < 16) matching.push(image);
                            byUri.set(image.uri, matching);
                        });
                    });
                    return byUri;
                };
                const imageSources = (image, alternates) => {
                    if (!image || typeof image !== 'object') return {urls: [], displayUrls: [], downloadUrls: []};
                    const matching = typeof image.uri === 'string' && image.uri ? alternates.get(image.uri) || [] : [];
                    const displayUrls = Array.from(new Set([image, ...matching].flatMap(displayImageUrls))).slice(0, 64);
                    const downloadUrls = Array.from(new Set([image, ...matching].flatMap(downloadImageUrls))).slice(0, 64);
                    return {urls: Array.from(new Set([...downloadUrls, ...displayUrls])).slice(0, 64),
                        displayUrls: displayUrls, downloadUrls: downloadUrls};
                };
                const addressUrls = value => Array.from(new Set([
                    ...urlList(value), value && typeof value === 'object' ? https(value.uri) : '',
                    value && typeof value === 'object' ? https(value.url) : ''].filter(Boolean))).slice(0, 64);
                const videoRates = video => [video.bit_rate, video.bitrate].filter(Array.isArray)
                    .flatMap(rates => rates.slice(0, 16)).filter(rate => rate && typeof rate === 'object').slice(0, 16);
                const codecAddresses = (video, prefix) => [prefix, prefix + '_h264', prefix + '_265',
                    prefix + '_h265', prefix + '_bytevc1', prefix + '_bytevc2'].map(key => video[key]);
                const videoPlayAddresses = video => [video, ...videoRates(video)]
                    .flatMap(rate => codecAddresses(rate, 'play_addr'));
                const videoPlayUrls = video => Array.from(new Set(videoPlayAddresses(video).flatMap(addressUrls))).slice(0, 64);
                const videoDownloadUrls = video => Array.from(new Set([video, ...videoRates(video)]
                    .flatMap(rate => codecAddresses(rate, 'download_addr')).flatMap(addressUrls))).slice(0, 64);
                const mediaId = value => typeof value === 'string' && /^[A-Za-z0-9_-]{10,256}$/.test(value) &&
                    /[A-Za-z]/.test(value) ? value : '';
                const videoMediaIds = video => Array.from(new Set([mediaId(video.video_id),
                    ...videoPlayAddresses(video).map(address => address && typeof address === 'object'
                        ? mediaId(address.uri) : '')].filter(Boolean))).slice(0, 16);
                const dynamicImage = (image, alternates) => {
                    if (!image || typeof image !== 'object') return {};
                    const matching = typeof image.uri === 'string' && image.uri ? alternates.get(image.uri) || [] : [];
                    const originals = [image, ...matching];
                    // A clip belongs to this photo only through its own video object or an exact
                    // image-URI variant. The work's video/audio and DOM players are not substitutes.
                    // The official slides renderer defines 1=Video, 2=Image, 3=LivePhoto, 4=Default.
                    // Keep untagged older metadata; tagged static/unknown entries cannot supply a clip.
                    const clips = originals.filter(entry => entry &&
                        (!Object.prototype.hasOwnProperty.call(entry, 'clip_type') || [1, 3, 4].includes(entry.clip_type)))
                        .map(entry => entry.video)
                        .filter(clip => clip && typeof clip === 'object' && !Array.isArray(clip));
                    if (clips.length) {
                        const clip = clips.find(entry => videoPlayUrls(entry).length || videoMediaIds(entry).length) || clips[0];
                        const address = clip.play_addr || clip.download_addr || {};
                        return {kind: originals.some(entry => entry && entry.clip_type === 3) ? 'LIVE' :
                            originals.some(entry => entry && entry.clip_type === 1) ? 'ANIMATED' : 'DYNAMIC', motion: {
                            playUrls: Array.from(new Set(clips.flatMap(videoPlayUrls))).slice(0, 64),
                            downloadUrls: Array.from(new Set(clips.flatMap(videoDownloadUrls))).slice(0, 64),
                            mediaIds: Array.from(new Set(clips.flatMap(videoMediaIds))).slice(0, 16),
                            width: finite(clip.width) || finite(address.width),
                            height: finite(clip.height) || finite(address.height),
                            durationSeconds: finite(clip.duration) / 1000}};
                    }
                    // A declared Live Photo without a clip must not be reported as a completed still.
                    if (originals.some(entry => entry && entry.clip_type === 3)) return {kind: 'LIVE'};
                    const displayed = [image, ...matching].flatMap(displayImageUrls);
                    // GIF is only a hint for the UI. Native downloads inspect the file bytes;
                    // WebP/PNG may also animate and cannot be classified from the suffix alone.
                    const gif = displayed.some(value => {
                        try { return /\.gif$/i.test(new URL(value).pathname); } catch (_) { return false; }
                    });
                    return gif ? {mimeType: 'image/gif'} : {};
                };
                const audioUrl = value => {
                    const secure = https(value);
                    if (secure) return secure;
                    // The public note player supplies an absolute audio URI; use TLS only.
                    // Native code validates the resulting origin before it can be downloaded.
                    return typeof value === 'string' && /^http:\/\//i.test(value)
                        ? https(value.replace(/^http:/i, 'https:')) : '';
                };
                const audioUrls = value => {
                    if (typeof value === 'string') return [audioUrl(value)].filter(Boolean);
                    if (Array.isArray(value)) return value.slice(0, 64).map(audioUrl).filter(Boolean);
                    if (!value || typeof value !== 'object') return [];
                    return Array.from(new Set([audioUrl(value.uri),
                        ...audioUrls(value.url_list), audioUrl(value.url)].filter(Boolean))).slice(0, 64);
                };
                const ownBgm = work => {
                    const address = work.video && work.video.play_addr;
                    const ownAudio = audioUrls(address);
                    const music = work.music || {};
                    return {urls: Array.from(new Set([...ownAudio, ...audioUrls(music.play_url)])).slice(0, 64),
                        duration: ownAudio.length && finite(work.video.duration) > 0
                            ? finite(work.video.duration) / 1000 : finite(music.duration)};
                };
                const candidateUrls = new Set();
                const addCandidate = candidate => {
                    if (!candidate.url) return;
                    if (candidateUrls.has(candidate.url)) {
                        const previous = result.candidates.find(entry => entry.url === candidate.url);
                        ['playUrls', 'downloadUrls', 'mediaIds'].forEach(key => {
                            previous[key] = Array.from(new Set([...(previous[key] || []), ...(candidate[key] || [])])).slice(0, 64);
                        });
                        return;
                    }
                    if (result.candidates.length >= 64) return;
                    candidateUrls.add(candidate.url);
                    result.candidates.push(candidate);
                };
                // Metadata belongs to the exact work; recommendations cannot substitute for it.
                const queue = [route];
                const seen = new Set();
                const ownedAudio = [];
                const ownedPhotoVariants = new Map();
                const albumOrigins = [];
                for (let cursor = 0; cursor < queue.length && cursor < 12000; cursor++) {
                    const x = queue[cursor];
                    if (!x || typeof x !== 'object' || seen.has(x)) continue;
                    seen.add(x);
                    if (x.aweme_id === id) {
                        const bgm = ownBgm(x);
                        if (ownedAudio.length < 64) ownedAudio.push(...bgm.urls.slice(0, 64 - ownedAudio.length));
                        const post = x.image_post_info || {};
                        const images = [x.images, post.images, x.image_list, post.image_list]
                            .find(value => Array.isArray(value) && value.length > 0);
                        if (images && images.length <= 200 && result.albums.length < 16) {
                            const alternates = imageAlternates(x);
                            const album = {owner: id, title: text(x.desc, 500),
                                images: images.map(image => ({...imageSources(image, alternates), ...dynamicImage(image, alternates),
                                    imageKey: text(image && image.uri, 2048),
                                    width: finite(image && image.width), height: finite(image && image.height)})),
                                bgmUrls: bgm.urls, bgmDuration: bgm.duration};
                            result.albums.push(album);
                            albumOrigins.push({album: album, images: images});
                            images.forEach(image => {
                                if (!image || typeof image.uri !== 'string' || !image.uri || image.uri.length > 2048) return;
                                const matching = ownedPhotoVariants.get(image.uri) || [];
                                [image, ...(alternates.get(image.uri) || [])].forEach(variant => {
                                    if (matching.length < 32 && !matching.includes(variant)) matching.push(variant);
                                });
                                ownedPhotoVariants.set(image.uri, matching);
                            });
                        }
                        if (images && images.length > 0) {
                            // A note's video field can be a soundtrack/placeholder, not a rendered video.
                            Object.values(x).forEach(y => {
                                if (queue.length < 12000 && y && typeof y === 'object') queue.push(y);
                            });
                            continue;
                        }
                    }
                    if (x.aweme_id === id && x.video && typeof x.video === 'object') {
                        const m = x.video;
                        const addr = m.play_addr || m.download_addr || {};
                        const playUrls = videoPlayUrls(m);
                        const downloadUrls = videoDownloadUrls(m);
                        const mediaIds = videoMediaIds(m);
                        const urls = Array.from(new Set([...playUrls, ...downloadUrls]));
                        const width = finite(m.width) || finite(addr.width);
                        const height = finite(m.height) || finite(addr.height);
                        const duration = finite(m.duration) / 1000;
                        if (width > 0 && height > 0 && duration > 0) {
                            // Native code owns the host policy. Keep alternatives so an unsupported
                            // first address cannot conceal a valid later address or the DOM source.
                            urls.slice(0, 64).map(https).filter(Boolean).forEach(url => {
                                if (result.candidates.length < 63 || candidateUrls.has(url)) addCandidate({owner: id, url: url,
                                    title: text(x.desc, 500), ready: 1, width: width, height: height,
                                    duration: duration, structured: true,
                                    sourceField: playUrls.includes(url) ? 'play_addr' : 'download_addr',
                                    playUrls: playUrls, downloadUrls: downloadUrls, mediaIds: mediaIds,
                                    coverUrls: [m.origin_cover, m.cover, m.dynamic_cover].flatMap(urlList)});
                            });
                            if (!urls.length && mediaIds.length && result.candidates.length < 63) {
                                // Native source policy constructs entries from this exact work's media IDs.
                                result.candidates.push({owner: id, url: '', title: text(x.desc, 500), ready: 1,
                                    width: width, height: height, duration: duration, structured: true,
                                    sourceField: 'metadata', playUrls: playUrls, downloadUrls: downloadUrls,
                                    mediaIds: mediaIds, coverUrls: [m.origin_cover, m.cover, m.dynamic_cover].flatMap(urlList)});
                            }
                        }
                    }
                    Object.values(x).forEach(y => {
                        if (queue.length < 12000 && y && typeof y === 'object') queue.push(y);
                    });
                }
                // Partial hydration can expose the same work more than once. A music-less
                // first copy must not conceal this work's later public audio fields.
                const sameWorkAudio = Array.from(new Set(ownedAudio)).slice(0, 64);
                // A later copy of the exact work may expose a photo clip after its still image.
                // Join only by its URI; a reordered or absent URI cannot borrow a neighbor's clip.
                albumOrigins.forEach(({album, images}) => images.forEach((image, index) => {
                    Object.assign(album.images[index], dynamicImage(image, ownedPhotoVariants));
                }));
                result.albums.forEach(album => {
                    album.bgmUrls = Array.from(new Set([...album.bgmUrls, ...sameWorkAudio])).slice(0, 64);
                    if (!(album.bgmDuration > 0)) {
                        album.bgmDuration = (result.albums.find(item => item.bgmDuration > 0) || {}).bgmDuration || 0;
                    }
                });
                // The official note player may hydrate its audio element without updating router
                // data. Bind this DOM fallback to the same first work used by that note renderer.
                const firstWork = mobile && mobile.videoInfoRes && Array.isArray(mobile.videoInfoRes.item_list)
                    ? mobile.videoInfoRes.item_list[0] : null;
                const galleryAudio = Array.from(document.querySelectorAll('.gallery-container audio'));
                const audio = galleryAudio.length === 1 ? galleryAudio[0] : null;
                const audioClasses = audio && typeof audio.className === 'string' ? audio.className.split(/\s+/) : [];
                const targetAudio = albumLocation && mobileMatches && firstWork && firstWork.aweme_id === id &&
                    result.albums.length > 0 && audio && audioClasses.includes('hide') &&
                    (!owner(audio) || owner(audio) === id) ? audio : null;
                const audioSrc = targetAudio ? audioUrl(targetAudio.currentSrc || targetAudio.src || '') : '';
                stats.albumAudio = {count: galleryAudio.length, target: !!targetAudio, host: host(audioSrc)};
                if (audioSrc) result.albums.forEach(album => {
                    album.bgmUrls = Array.from(new Set([...album.bgmUrls, audioSrc])).slice(0, 64);
                    if (finite(targetAudio.duration) > 0) album.bgmDuration = finite(targetAudio.duration);
                });
                if (v) {
                    const src = https(v.currentSrc || v.src || '');
                    // A mobile player can expose the official iesdouyin playback mirror before
                    // loading dimensions. Accept its metadata only when the opaque video_id is
                    // also present in this exact work's structured play address. A page ID,
                    // recommendation player, unmatched clip or arbitrary CDN is insufficient.
                    let playbackId = '';
                    try {
                        const address = new URL(src);
                        const ids = address.searchParams.getAll('video_id');
                        if (['aweme.snssdk.com', 'www.iesdouyin.com'].includes(address.hostname.toLowerCase()) &&
                            ['/aweme/v1/play/', '/aweme/v1/playwm/'].includes(address.pathname) && ids.length === 1)
                            playbackId = mediaId(ids[0]);
                    } catch (_) {}
                    const sameVideo = playbackId ? result.candidates.find(entry => entry.structured &&
                        (entry.mediaIds || []).includes(playbackId) && entry.owner === id) : null;
                    // Request metadata once. Repeated play/pause or load calls can prevent readiness.
                    v.muted = true;
                    v.volume = 0;
                    if (v.readyState === 0 && src && !v.dataset.saverMetadataRequested) {
                        v.dataset.saverMetadataRequested = '1';
                        v.preload = 'metadata';
                        v.load();
                    }
                    addCandidate({owner: id, url: src,
                        title: text((document.querySelector('h1') || {}).textContent || document.title, 500),
                        ready: finite(v.readyState) || (sameVideo ? 1 : 0),
                        duration: finite(v.duration) || (sameVideo ? sameVideo.duration : 0),
                        width: finite(v.videoWidth) || (sameVideo ? sameVideo.width : 0),
                        height: finite(v.videoHeight) || (sameVideo ? sameVideo.height : 0), structured: false,
                        sourceField: 'dom', playUrls: src ? [src] : [], downloadUrls: []});
                }
                // Preserve the original single-candidate shape for callers while they migrate.
                if (result.candidates.length) Object.assign(result, result.candidates[0]);
                return JSON.stringify(result);
            })()
        """.trimIndent()
    }
}
