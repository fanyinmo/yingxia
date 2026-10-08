package com.local.douyinsaver

/** Desktop motion addresses can be richer while their type remains unspecified.
 * Retain an already explicit type only through a unique source photo identity.
 * This policy never merges media, infers identity from order or overrides a new
 * explicit classification. A convertible video is not proof of a live capture.
 */
internal object AlbumKindPreservation {
    fun preserveKnownKinds(base: ParsedVideo, candidate: ParsedVideo): ParsedVideo {
        if (base.id != candidate.id || !base.isAlbum || !candidate.isAlbum) return candidate
        val originalByKey = base.images.filter { it.imageKey.isNotBlank() }.groupBy { it.imageKey }
        val candidateCounts = candidate.images.filter { it.imageKey.isNotBlank() }
            .groupingBy { it.imageKey }.eachCount()
        var changed = false
        val images = candidate.images.map { image ->
            if (image.kind != AlbumAssetKind.DYNAMIC || image.motion == null || image.imageKey.isBlank() ||
                candidateCounts[image.imageKey] != 1) return@map image
            val original = originalByKey[image.imageKey]?.singleOrNull() ?: return@map image
            if (original.kind !in listOf(AlbumAssetKind.LIVE, AlbumAssetKind.ANIMATED)) return@map image
            changed = true
            image.copy(kind = original.kind)
        }
        return if (changed) candidate.copy(images = images) else candidate
    }
}
