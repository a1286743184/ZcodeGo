"""持续监听 App WebView 的 CDP 事件（Network/Log/Runtime），抓 WS 与失败原因。

wvcdp.py 是「发一条命令收一条响应」的短连接模式，拿不到事件流。
这个脚本保持长连接，收集指定域的事件，适合定位「页面静默失败」类问题。

用法:
  python watch_events.py --seconds 25 --reload
  python watch_events.py --seconds 20 --domains Network,Log,Runtime
"""
import argparse
import json
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import wvcdp


class EventWatcher:
    def __init__(self, ws_url):
        self.dev = wvcdp.DevTools(ws_url)
        self.events = []
        self._id = 0

    def enable(self, domains):
        for d in domains:
            self.dev.call("%s.enable" % d, {})

    def drain(self, seconds):
        """在 seconds 内持续读取帧，把事件收集起来（响应帧丢弃）。"""
        end = time.time() + seconds
        self.dev.s.settimeout(1.0)
        while time.time() < end:
            try:
                msg = self.dev._recv()
            except Exception:
                continue
            if not msg:
                continue
            try:
                obj = json.loads(msg)
            except Exception:
                continue
            if "method" in obj:
                self.events.append(obj)


def summarize(events):
    out = []
    for e in events:
        m = e["method"]
        p = e.get("params", {})
        if m == "Network.webSocketCreated":
            out.append("WS_CREATED %s" % p.get("url"))
        elif m == "Network.webSocketWillSendHandshakeRequest":
            out.append("WS_HANDSHAKE %s" % json.dumps(p.get("request", {}).get("url", ""))[:200])
        elif m == "Network.webSocketHandshakeResponseReceived":
            r = p.get("response", {})
            out.append("WS_RESP status=%s" % r.get("status"))
        elif m == "Network.webSocketClosed":
            out.append("WS_CLOSED")
        elif m == "Network.webSocketFrameError":
            out.append("WS_FRAME_ERR %s" % p.get("errorMessage"))
        elif m == "Network.loadingFailed":
            out.append("LOAD_FAILED %s %s" % (p.get("errorText"), p.get("type")))
        elif m == "Network.responseReceived":
            r = p.get("response", {})
            url = r.get("url", "")
            if r.get("status", 200) >= 400:
                out.append("HTTP_%s %s" % (r.get("status"), url[:140]))
        elif m == "Network.requestWillBeSent":
            url = p.get("request", {}).get("url", "")
            if url.startswith("wss://") or url.startswith("ws://"):
                out.append("REQ_WS %s" % url[:160])
        elif m == "Log.entryAdded":
            entry = p.get("entry", {})
            if entry.get("level") in ("error", "warning"):
                out.append("LOG_%s %s" % (entry.get("level"), str(entry.get("text"))[:220]))
        elif m == "Runtime.exceptionThrown":
            d = p.get("exceptionDetails", {})
            out.append("EXCEPTION %s" % str(d.get("text"))[:220])
        elif m == "Runtime.consoleAPICalled":
            if p.get("type") in ("error", "warning"):
                args = " ".join(str(a.get("value")) for a in p.get("args", []) if "value" in a)
                out.append("CONSOLE_%s %s" % (p.get("type"), args[:220]))
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--seconds", type=int, default=20)
    ap.add_argument("--domains", default="Network,Log,Runtime")
    ap.add_argument("--reload", action="store_true")
    ap.add_argument("--filter", default="")
    args = ap.parse_args()

    t = wvcdp.pick_page()
    if not t:
        print("NO_TARGET")
        return 1
    print("target:", (t.get("url") or "")[:120])
    w = EventWatcher(t["webSocketDebuggerUrl"])
    w.enable(args.domains.split(","))
    if args.reload:
        w.dev.call("Page.reload", {"ignoreCache": False})
    w.drain(args.seconds)
    lines = summarize(w.events)
    if args.filter:
        lines = [l for l in lines if args.filter.lower() in l.lower()]
    for l in lines:
        print(l)
    print("--- total events: %d, shown: %d ---" % (len(w.events), len(lines)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
