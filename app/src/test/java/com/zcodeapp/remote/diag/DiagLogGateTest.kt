package com.zcodeapp.remote.diag

import org.junit.Assert.assertEquals
import org.junit.Test

/** 隐私门纯函数：隐私模式下内容 detail 必须整体置空，事件名不受影响。 */
class DiagLogGateTest {
    @Test
    fun privacyOnDropsDetailEntirely() {
        assertEquals("", DiagLog.gate(privacy = true, detail = "text=任何会话内容"))
        assertEquals("", DiagLog.gate(privacy = true, detail = ""))
    }

    @Test
    fun privacyOffKeepsDetail() {
        assertEquals("state={\"dead\":false}", DiagLog.gate(privacy = false, detail = "state={\"dead\":false}"))
    }
}
