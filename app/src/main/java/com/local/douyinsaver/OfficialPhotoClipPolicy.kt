package com.local.douyinsaver

/** Numeric clip types from the official public slides renderer, without coercing guessed flags. */
internal object OfficialPhotoClipPolicy {
    fun allowsVideo(hasClipType: Boolean, clipType: Any?): Boolean =
        !hasClipType || (clipType is Number && clipType.toDouble() in listOf(1.0, 3.0, 4.0))

    fun declaresLive(clipType: Any?): Boolean = clipType is Number && clipType.toDouble() == 3.0
}
