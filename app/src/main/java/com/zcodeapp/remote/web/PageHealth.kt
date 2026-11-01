package com.zcodeapp.remote.web

/** 页面/管线健康状态（驱动 UI 状态条与 degraded 提示）。 */
enum class PageState {
    IDLE,
    CONNECTING,
    PAIRED,
    DEGRADED,
    CLOSED,
    ERROR,
}
