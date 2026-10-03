package com.local.douyinsaver

import org.junit.Assert.*
import org.junit.Test

class FeaturePolicyTest {
    private val id = "7689301063664454962"
    private val image = "https://p3.douyinpic.com/example.jpg"
    private fun album(images: List<AlbumCandidatePolicy.ImageCandidate> = listOf(AlbumCandidatePolicy.ImageCandidate(listOf(image))),
                      owner: String = id, music: List<String> = emptyList()) = AlbumCandidatePolicy.ready(id,
        "https://www.iesdouyin.com/share/slides/$id/", owner, "标题", images, music)

    @Test fun albumsKeepOrderWithoutRequiringMusic() {
        val result = album(listOf(AlbumCandidatePolicy.ImageCandidate(listOf(image)),
            AlbumCandidatePolicy.ImageCandidate(listOf("https://p3.byteimg.com/two.webp"))))!!
        assertTrue(result.isAlbum); assertEquals(2, result.images.size)
        assertEquals(image, result.images.first().url); assertEquals("", result.bgmUrl)
    }
    @Test fun albumsRequireExactOwnerAndCompleteImages() {
        assertNull(album(owner = "7689301063664454963"))
        assertNull(album(listOf(AlbumCandidatePolicy.ImageCandidate(listOf("https://evil.test/image.jpg")))))
        assertNull(album(emptyList()))
        assertNull(album(List(101) { AlbumCandidatePolicy.ImageCandidate(listOf(image)) }))
    }
    @Test fun unsupportedFirstUrlsCannotConcealSafeImagesAndMusic() {
        val result = album(listOf(AlbumCandidatePolicy.ImageCandidate(listOf("https://evil.test/x", image))),
            music = listOf("http://unsupported.example/music", "https://sf1.douyinstatic.com/music"))!!
        assertEquals(image, result.images.single().url)
        assertEquals("https://sf1.douyinstatic.com/music", result.bgmUrl)
    }
    @Test fun newImageOriginsHaveStrictBoundaries() {
        assertTrue(MediaUrls.isAllowed(image)); assertTrue(MediaUrls.isAllowed("https://sf1.douyinstatic.com/a"))
        listOf("https://douyinpic.com.evil.test/a", "https://evil@p3.douyinpic.com/a", "http://p3.douyinpic.com/a",
            "https://p3.douyinpic.com:8080/a").forEach { assertFalse(MediaUrls.isAllowed(it)) }
    }
    @Test fun directAlbumLinksAndBatchSharesRetainExactIds() {
        listOf("note", "slides", "share/slides").forEach {
            assertEquals(id, ShareLinks.videoId("https://www.douyin.com/$it/$id"))
        }
        assertEquals(2, ShareLinks.extractAll("a https://v.douyin.com/a/ b https://v.douyin.com/b/ https://v.douyin.com/a/").size)
        assertThrows(IllegalArgumentException::class.java) { ShareLinks.extractAll("https://evil.test/x") }
    }
    @Test fun filenamesAreBoundedSafeAndUnique() {
        assertFalse(FileNames.safeStem("../a\\b:*?\"|\n").contains('/'))
        assertTrue(FileNames.safeStem("表情😀".repeat(100)).toByteArray(Charsets.UTF_8).size <= 110)
        assertEquals("douyin", FileNames.safeStem("...  "))
        val content = ParsedVideo(id, "title", "", 0.0, 0, 0)
        val first = FileNames.build(content, DownloadOptions(fileName = "my movie"), "mp4")
        assertTrue(first.startsWith("my movie_")); assertTrue(first.endsWith(".mp4"))
        assertNotEquals(first, FileNames.build(content, DownloadOptions(fileName = "my movie"), "mp4"))
    }
    @Test fun interruptedTasksRequireRetryAndWaitingOrderIsEditable() {
        val active = QueueTask("1", "https://v.douyin.com/a/", status = QueueStatus.RUNNING)
        assertEquals(QueueStatus.FAILED, QueuePolicy.recover(active).status)
        assertEquals(QueueStatus.FAILED, QueuePolicy.recover(active.copy(status = QueueStatus.READY)).status)
        val tasks = listOf(active, QueueTask("2", "b"), QueueTask("3", "c"))
        assertEquals(listOf("1", "3", "2"), QueuePolicy.move(tasks, "3", -1).map { it.key })
        assertEquals(tasks, QueuePolicy.move(tasks, "2", -1))
    }
}
