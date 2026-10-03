package com.local.douyinsaver

import org.junit.Assert.*
import org.junit.Test

class ShareLinksTest {
    @Test fun extractsRealShareTextAndHyphens() {
        assertEquals("https://v.douyin.com/8xpDGS8FC-s/", ShareLinks.extract("4.66 如何拼出霸王龙化石？ https://v.douyin.com/8xpDGS8FC-s/ 复制此链接"))
        assertEquals("https://v.douyin.com/IB-V1HXL_lo/", ShareLinks.extract("动画 https://v.douyin.com/IB-V1HXL_lo/。"))
    }

    @Test fun rejectsLookalikeDomainsAndCredentials() {
        listOf("https://douyin.com.attacker.test/video/7691956509040221327", "https://douyin.com@attacker.test/a", "https://attacker.test@douyin.com/a", "http://v.douyin.com/a", "https://v.douyin.com:8080/a").forEach {
            assertThrows(IllegalArgumentException::class.java) { ShareLinks.extract(it) }
        }
    }

    @Test fun preservesIdsWithoutNumericRounding() {
        assertEquals("7691956509040221327", ShareLinks.videoId("https://www.iesdouyin.com/share/video/7691956509040221327/?from=x"))
        assertEquals("7691877298273832226", ShareLinks.videoId("https://www.douyin.com/jingxuan?modal_id=7691877298273832226"))
        assertNull(ShareLinks.videoId("https://attacker.test/video/7691956509040221327"))
    }

    @Test fun rejectsMultipleDistinctLinks() {
        assertThrows(IllegalArgumentException::class.java) { ShareLinks.extract("https://v.douyin.com/a/ https://v.douyin.com/b/") }
    }
}
