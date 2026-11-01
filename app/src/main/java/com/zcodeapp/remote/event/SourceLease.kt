package com.zcodeapp.remote.event

import java.util.concurrent.atomic.AtomicLong

/**
 * 事件源单一所有权（v2 3.2）：每个连接同一时刻只有一个 owner；epoch 单调递增，
 * 旧 epoch 的回调一律丢弃。进程内实现，M4 前不涉及多进程。
 */
enum class SourceOwner { PRIMARY_PAGE, NATIVE_RELAY, STANDBY }

data class Lease(val connectionId: String, val owner: SourceOwner, val epoch: Long) {
    fun isCurrent(coordinator: SourceLeaseCoordinator): Boolean = coordinator.currentEpoch(connectionId) == epoch
}

class SourceLeaseCoordinator {
    private val epochs = HashMap<String, AtomicLong>()

    @Synchronized
    fun acquire(connectionId: String, owner: SourceOwner): Lease {
        val counter = epochs.getOrPut(connectionId) { AtomicLong(0) }
        val epoch = counter.incrementAndGet()
        return Lease(connectionId, owner, epoch)
    }

    @Synchronized
    fun currentEpoch(connectionId: String): Long =
        epochs[connectionId]?.get() ?: 0L

    @Synchronized
    fun release(connectionId: String) {
        epochs.remove(connectionId)
    }
}
