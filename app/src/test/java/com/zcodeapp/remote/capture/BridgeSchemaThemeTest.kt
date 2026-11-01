package com.zcodeapp.remote.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 桥消息 schema：主题消息必须能被解析，畸形消息必须被拒。
 *
 * 这条链路的失败方式很隐蔽——解析失败只是「壳层不跟随主题」，
 * 界面上看不出任何错误，所以把 schema 契约锁在纯 JVM 测试里。
 */
class BridgeSchemaThemeTest {

    @Test
    fun parsesThemeMessage() {
        val msg = BridgeSchema.parse(
            """{"v":2,"kind":"theme","doc":"abc123","bg":"rgb(22, 22, 22)","fg":"rgb(245, 245, 245)","cls":"dark"}""",
        )
        assertNotNull(msg)
        assertTrue(msg is BridgeSchema.BridgeMsg.Theme)
        val theme = msg as BridgeSchema.BridgeMsg.Theme
        assertEquals("rgb(22, 22, 22)", theme.bg)
        assertEquals("rgb(245, 245, 245)", theme.fg)
        assertEquals("dark", theme.cls)
    }

    @Test
    fun themeWithoutClassIsAllowed() {
        // cls 是可选的：页面可能用 CSS 变量而不是 class 切换主题。
        val msg = BridgeSchema.parse(
            """{"v":2,"kind":"theme","doc":"d1","bg":"#161616","fg":"#f5f5f5"}""",
        )
        assertTrue(msg is BridgeSchema.BridgeMsg.Theme)
        assertNull((msg as BridgeSchema.BridgeMsg.Theme).cls)
    }

    @Test
    fun themeMissingColorIsRejected() {
        assertNull(BridgeSchema.parse("""{"v":2,"kind":"theme","doc":"d1","fg":"#fff"}"""))
        assertNull(BridgeSchema.parse("""{"v":2,"kind":"theme","doc":"d1","bg":"#161616"}"""))
    }

    @Test
    fun wrongVersionIsRejected() {
        assertNull(
            BridgeSchema.parse(
                """{"v":1,"kind":"theme","doc":"d1","bg":"#161616","fg":"#f5f5f5"}""",
            ),
        )
    }
}
