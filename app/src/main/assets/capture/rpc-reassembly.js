// rpc-reassembly.js — 帧过滤器：决定哪些 WS 文本帧值得跨桥（无截断：要么整帧、要么跳过并要求重同步）
(function () {
  if (window.__zrFrameFilter) return;
  var MAX_FRAME = 1572864; // 1.5 MiB：覆盖 512KiB 分片经 base64 + JSON 膨胀后的尺寸
  var RE_FRAME = /"zcode_type"\s*:\s*"(rpc-frame|mobile-task-view-update|mobile-task-view-request|bridge-degraded|workspace-bridge-open|workspace-bridge-ready|bootstrap-request)"/;

  window.__zrFrameFilter = function (text) {
    if (typeof text !== 'string') return false;
    if (text.length < 64) return text.charAt(0) === '{' || text.charAt(0) === '['; // 短控制帧（auth_ack 等）整体转发
    if (text.length > MAX_FRAME) {
      if (window.__zrTransport) window.__zrTransport.needsResync();
      return false;
    }
    return RE_FRAME.test(text);
  };
})();
