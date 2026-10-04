package com.local.douyinsaver

import org.junit.Assert.*
import org.junit.Test
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class FailureMessagesTest {
    @Test fun keepsSpecificHttpReasonButRemovesSignedAddress() {
        val message = FailureMessages.describe(IllegalArgumentException(
            "无水印来源 HTTP 403 https://v3.douyinvod.com/file?signature=private_value"))
        assertTrue(message.contains("无水印来源 HTTP 403"))
        assertFalse(message.contains("signature"))
        assertFalse(message.contains("private_value"))
    }

    @Test fun distinguishesWrappedNetworkAndStorageFailures() {
        assertTrue(FailureMessages.describe(RuntimeException(UnknownHostException())).contains("无法找到视频服务器"))
        assertTrue(FailureMessages.describe(RuntimeException(SSLHandshakeException("secret"))).contains("安全连接失败"))
        assertTrue(FailureMessages.describe(SecurityException("content://private")).contains("重新选择文件夹"))
        assertFalse(FailureMessages.describe(RuntimeException("token=private")).contains("private"))
    }
}
