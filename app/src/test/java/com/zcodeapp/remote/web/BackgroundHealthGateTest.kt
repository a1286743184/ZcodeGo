package com.zcodeapp.remote.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 后台连接健康判据。
 *
 * 这个判据错了的两个方向都有代价：
 * - 该恢复不恢复 → 息屏期间连接早已静默死亡，用户永远收不到提醒
 * - 不该恢复乱恢复 → 正在正常工作的连接被反复重载，用户回到 App 发现页面刷了
 *
 * 关键点：必须区分「WS 名义上 open」与「页面 JS 还在跑」。
 * 前者在 CPU 被冻结时也会保持 open，只看它永远发现不了问题。
 *
 * 【2026-10-30】业务帧静默判据已删除。下面 `longIdleWithLiveHeartbeatIsNotRecovered`
 * 锁住的就是删除它的理由——真机日志里 29 分钟 bizCount 冻结、ws 全程 open，
 * 旧判据每 30 分钟重载一次，纯属误伤。
 */
class BackgroundHealthGateTest {

    private val now = 1_800_000_000_000L

    private fun probe(
        ws: String = "open",
        wsAgeMs: Long = 1_000,
        everOpen: Boolean = true,
        lastRxAt: Long = 0,
        lastTxAt: Long = 0,
        lastBizRxAt: Long = 0,
        bridge: Boolean = true,
    ) = """{"ws":"$ws","wsAgeMs":$wsAgeMs,"everOpen":$everOpen,"lastRxAt":$lastRxAt,"lastTxAt":$lastTxAt,"lastBizRxAt":$lastBizRxAt,"bridge":$bridge}"""

    @Test
    fun recentlyActiveConnectionIsHealthy() {
        val v = BackgroundHealthGate.evaluate(
            probe(lastRxAt = now - 5_000, lastTxAt = now - 10_000),
            now,
        )
        assertFalse("刚有收发不该重连", v.recover)
        assertEquals("healthy", v.reason)
    }

    @Test
    fun brieflyIdleConnectionIsHealthy() {
        // 【真机日志教训】13:16:18 报 silent-91226（91 秒）后重载 → 用户看到白屏。
        // 任务空闲时本来就不该有收发，91 秒的安静是正常的。
        val v = BackgroundHealthGate.evaluate(
            probe(lastRxAt = now - 91_000, lastTxAt = now - 91_000),
            now,
        )
        assertFalse("空闲 91 秒不该被判死", v.recover)
        assertEquals("healthy", v.reason)
    }

    /**
     * 【2026-10-30 真机日志回归】这是删掉业务帧判据的核心依据。
     *
     * 原始日志（diag-20261028-09.log）：
     * ```
     * 08:11:54 bizAge=29s    bizCount=5110
     * ...（29 分钟，ws 全程 open，一次 ws.closed 都没有）...
     * 08:41:26 bizAge=1801s  bizCount=5110   ← 旧判据在此重载
     * ```
     *
     * 心跳（lastRxAt/lastTxAt）一直在更新，说明页面 JS 活得好好的；
     * bizCount 冻结只是「任务空闲时页面不推业务帧」，属于正常状态。
     * 旧判据把它当死亡信号，于是每 30 分钟自己重载一次。
     */
    @Test
    fun longIdleWithLiveHeartbeatIsNotRecovered() {
        val v = BackgroundHealthGate.evaluate(
            probe(
                lastRxAt = now - 2_000,          // 心跳刚走
                lastTxAt = now - 8_000,
                lastBizRxAt = now - 1_801_507,   // 业务帧 30 分钟没动（正常空闲）
            ),
            now,
        )
        assertFalse("心跳在走 = 页面活着，业务帧冻结不算死", v.recover)
        assertEquals("healthy", v.reason)
    }

    /**
     * 业务帧判据删掉之后，真正的冻结仍然必须被发现。
     *
     * 冻结的特征是**心跳也停了**（lastRxAt/lastTxAt 原地不动），
     * 而不是只有业务帧不动。这条锁住「删判据没有把该抓的也一起放过」。
     */
    @Test
    fun frozenPageWithStalledHeartbeatIsRecovered() {
        val v = BackgroundHealthGate.evaluate(
            probe(
                lastRxAt = now - 700_000,
                lastTxAt = now - 700_000,
                lastBizRxAt = now - 1_800_000,
            ),
            now,
        )
        assertTrue("心跳与业务帧全停，必须重连", v.recover)
        assertTrue(v.reason.startsWith("silent-"))
    }

    @Test
    fun silentlyOpenConnectionIsRecovered() {
        // 真的被冻结会持续静默；10 分钟后仍无任何收发才判死。
        val v = BackgroundHealthGate.evaluate(
            probe(lastRxAt = now - 700_000, lastTxAt = now - 700_000),
            now,
        )
        assertTrue("长时间静默的 open 连接必须重连", v.recover)
        assertTrue(v.reason.startsWith("silent-"))
    }

    @Test
    fun noTrafficRecordedIsNotJudged() {
        // 页面刚起、还没记过收发：不能误判成死连接。
        val v = BackgroundHealthGate.evaluate(probe(lastRxAt = 0, lastTxAt = 0), now)
        assertFalse(v.recover)
        assertEquals("no-traffic-yet", v.reason)
    }

    /**
     * 【2026-10-31 真机日志回归】代发心跳不得掩盖僵尸连接。
     *
     * 这是上一版判据（取 max(lastRxAt, lastTxAt)）被证伪的确切现场：
     * ```
     * 01:00:00 txAge=30s rxAge=392s      ← 我们在发，一个字节收不到
     * 01:12:30 txAge=30s rxAge=1143s     ← 一路涨到 1293s，从没恢复
     * ```
     *
     * 原生代发心跳刷新了 lastTxAt，旧判据因此算出「刚有收发」→ healthy，
     * 连接单向死透却再没触发过一次恢复。这就是「保活开着但收不到通知」。
     *
     * 新判据只看入站，所以这条必须判死，且必须是 urgent（后台立即重建）——
     * 页面收不到 close 事件、定时器也停了，它自己永远不可能恢复。
     */
    @Test
    fun zombieConnectionWithOnlyOutboundTrafficIsRecovered() {
        val v = BackgroundHealthGate.evaluate(
            probe(
                lastRxAt = now - 392_000,   // 入站 392 秒前就停了
                lastTxAt = now - 30_000,    // 代发心跳还在刷新出站
            ),
            now,
        )
        assertTrue("只有出站在动 = 僵尸连接，必须重建", v.recover)
        assertTrue("僵尸连接必须后台立即处理", v.urgent)
        assertTrue("reason 应标明是入站静默", v.reason.startsWith("silent-rx-"))
    }

    @Test
    fun inboundTrafficAloneKeepsConnectionHealthy() {
        // 反向：入站在动、出站停了也不该判死。
        // 页面定时器可能停了（我们的代发心跳接管），但服务端仍在推帧，
        // 说明连接是活的，此时重建只会白白打断会话。
        val v = BackgroundHealthGate.evaluate(
            probe(lastRxAt = now - 3_000, lastTxAt = now - 400_000),
            now,
        )
        assertFalse("入站在动说明连接是活的", v.recover)
        assertEquals("healthy", v.reason)
    }

    @Test
    fun inboundSilenceJustUnderThresholdIsHealthy() {
        // 阈值边界：119 秒不该判死（服务端 ack 超时是 30 秒，这里留了 4 倍余量）。
        val v = BackgroundHealthGate.evaluate(
            probe(lastRxAt = now - 119_000, lastTxAt = now - 10_000),
            now,
        )
        assertFalse("阈值内不该判死", v.recover)
        assertEquals("healthy", v.reason)
    }

    @Test
    fun inboundSilenceJustOverThresholdIsRecovered() {
        val v = BackgroundHealthGate.evaluate(
            probe(lastRxAt = now - 121_000, lastTxAt = now - 10_000),
            now,
        )
        assertTrue("超过阈值必须重建", v.recover)
        assertTrue(v.urgent)
    }

    @Test
    fun nonUrgentVerdictsAreDeferredNotRebuilt() {
        // closed 且超过宽限期：该恢复，但页面自己还有恢复链路，
        // 所以不能标 urgent——后台重建的代价（丢会话、丢草稿）不该白付。
        val v = BackgroundHealthGate.evaluate(
            probe(ws = "closed", wsAgeMs = 60_000, lastRxAt = now - 60_000),
            now,
        )
        assertTrue("断开超宽限要恢复", v.recover)
        assertFalse("但应推迟到前台，不后台重建", v.urgent)
    }

    @Test
    fun freshlyClosedConnectionGetsGrace() {
        // 刚断开：页面自己在重连，别抢。
        val v = BackgroundHealthGate.evaluate(
            probe(ws = "closed", wsAgeMs = 2_000, lastRxAt = now - 3_000),
            now,
        )
        assertFalse(v.recover)
        assertEquals("reconnecting", v.reason)
    }

    @Test
    fun longClosedConnectionIsRecovered() {
        val v = BackgroundHealthGate.evaluate(
            probe(ws = "closed", wsAgeMs = 60_000, lastRxAt = now - 70_000),
            now,
        )
        assertTrue("断开太久应兜底重连", v.recover)
        assertTrue(v.reason.startsWith("closed-"))
    }

    @Test
    fun neverOpenedIsLeftAlone() {
        val v = BackgroundHealthGate.evaluate(
            probe(ws = "none", wsAgeMs = -1, everOpen = false),
            now,
        )
        assertFalse("从未连上不属于「掉线」", v.recover)
    }

    @Test
    fun missingBridgeSkipsRecovery() {
        val v = BackgroundHealthGate.evaluate(
            probe(bridge = false, lastRxAt = now - 999_000),
            now,
        )
        assertFalse("文档正在换，别插手", v.recover)
    }

    @Test
    fun garbageInputNeverRecovers() {
        assertFalse(BackgroundHealthGate.evaluate(null, now).recover)
        assertFalse(BackgroundHealthGate.evaluate("", now).recover)
        assertFalse(BackgroundHealthGate.evaluate("nope", now).recover)
        assertFalse(BackgroundHealthGate.evaluate("""{"err":"x"}""", now).recover)
    }
}
