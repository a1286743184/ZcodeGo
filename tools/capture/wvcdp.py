"""直连模拟器里 App WebView 的 Chrome DevTools 协议，替代拉起桌面 Chrome。

为什么存在：chrome-bridge 的 bb.py 在没有连接时会自动 Popen 一个桌面 Chrome，
只为读远控页而占掉几百 MB 内存。模拟器里的 App 自己就开着 WebView 调试通道
（WebViewSessionController 在 debug 构建里 setWebContentsDebuggingEnabled(true)），
用 adb forward 把它的 abstract socket 映射到本机端口即可直接 eval，不需要浏览器。

用法:
  python wvcdp.py tabs
  python wvcdp.py eval '<js 表达式>'
  python wvcdp.py cdp Page.enable
  python wvcdp.py cdp Page.reload --params '{}'

环境变量:
  ZR_SERIAL       adb 设备序列号，默认 emulator-5554
  ZR_CDP_PORT     本机映射端口，默认 9222
  ZR_PKG          App 包名，默认 com.zcodeapp.remote
"""
import base64
import json
import os
import socket
import subprocess
import sys
import time
import urllib.request
from urllib.parse import urlparse

ADB = os.environ.get("ZR_ADB", "adb")
SERIAL = os.environ.get("ZR_SERIAL", "emulator-5554")
PORT = int(os.environ.get("ZR_CDP_PORT", "9222"))
PKG = os.environ.get("ZR_PKG", "com.zcodeapp.remote")


def _hdr(payload_len, opcode=0x81):
    h = bytearray([opcode])
    if payload_len < 126:
        h.append(0x80 | payload_len)
    elif payload_len < 65536:
        h.append(0x80 | 126)
        h += payload_len.to_bytes(2, "big")
    else:
        h.append(0x80 | 127)
        h += payload_len.to_bytes(8, "big")
    return bytes(h)


def adb(*args, timeout=30):
    r = subprocess.run([ADB, "-s", SERIAL, *args], capture_output=True, text=True,
                       timeout=timeout, encoding="utf-8", errors="replace")
    return (r.stdout or "").strip(), (r.stderr or "").strip(), r.returncode


def app_pid():
    """取 App 主进程 pid。pidof 在进程处于 GC/挂起态时会偶发返回空，
    所以再退一步用 ps 兜底，避免把「进程还在」误判成「App 没运行」。"""
    out, _, rc = adb("shell", "pidof", PKG)
    if rc == 0 and out:
        return out.split()[0]
    out, _, rc = adb("shell", "ps", "-A")
    if rc == 0:
        for line in out.splitlines():
            parts = line.split()
            if parts and parts[-1] == PKG and len(parts) >= 2:
                return parts[1]
    return None


def ensure_forward():
    """把 WebView 调试 socket 映射到本机端口。socket 名必须用主进程 pid。"""
    pid = app_pid()
    if not pid:
        # pidof 偶发读空（adb 与进程表的竞态），重试一次再判死
        time.sleep(0.6)
        pid = app_pid()
    if not pid:
        raise RuntimeError("App 未运行（%s 没有进程），先启动并停在远控页" % PKG)
    # 只移除本端口，避免误删用户其它 forward
    adb("forward", "--remove", "tcp:%d" % PORT)
    out, err, rc = adb("forward", "tcp:%d" % PORT, "localabstract:webview_devtools_remote_%s" % pid)
    if rc != 0:
        raise RuntimeError("adb forward 失败: %s %s" % (out, err))
    return pid


def targets(timeout=10):
    """列出 WebView 调试目标。forward 掉线时自动重建一次。"""
    for attempt in (0, 1):
        try:
            with urllib.request.urlopen("http://127.0.0.1:%d/json/list" % PORT, timeout=timeout) as r:
                return json.load(r)
        except Exception:
            if attempt == 0:
                ensure_forward()
                continue
            raise


def pick_page(prefer="https://zcode.z.ai"):
    ts = targets()
    pages = [t for t in ts if t.get("type") == "page"]
    for t in pages:
        if (t.get("url") or "").startswith(prefer):
            return t
    return pages[0] if pages else None


class DevTools:
    """一条 CDP 会话。devtools socket 会被页面重载、渲染进程重建掐断，
    所以 call/evaluate 在断线时自动重连一次再重试同一条命令。"""

    def __init__(self, ws_url, timeout=20):
        self._ws_url = ws_url
        self._timeout = timeout
        self._connect()

    def _connect(self):
        u = urlparse(self._ws_url)
        self.s = socket.create_connection((u.hostname, u.port), timeout=self._timeout)
        key = base64.b64encode(os.urandom(16)).decode()
        req = (
            "GET %s HTTP/1.1\r\nHost: %s:%s\r\n"
            "Upgrade: websocket\r\nConnection: Upgrade\r\n"
            "Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\n\r\n"
            % (u.path, u.hostname, u.port, key)
        )
        self.s.sendall(req.encode())
        buf = b""
        while b"\r\n\r\n" not in buf:
            chunk = self.s.recv(4096)
            if not chunk:
                raise RuntimeError("websocket 握手无响应")
            buf += chunk
        if b"101" not in buf.split(b"\r\n")[0]:
            raise RuntimeError("websocket 握手失败: " + buf[:200].decode(errors="replace"))
        self._id = 0

    def reconnect(self):
        """换一条新连接，并把 ws_url 刷新到当前页面（页面重载后 target id 可能变）。"""
        self.close()
        t = pick_page()
        if t:
            self._ws_url = t["webSocketDebuggerUrl"]
        self._connect()

    def _send(self, text):
        data = text.encode()
        mask = os.urandom(4)
        masked = bytes(b ^ mask[i % 4] for i, b in enumerate(data))
        self.s.sendall(_hdr(len(data)) + mask + masked)

    def _recv(self):
        head = self.s.recv(2)
        if len(head) < 2:
            return None
        ln = head[1] & 0x7F
        if ln == 126:
            ln = int.from_bytes(self.s.recv(2), "big")
        elif ln == 127:
            ln = int.from_bytes(self.s.recv(8), "big")
        data = b""
        while len(data) < ln:
            part = self.s.recv(ln - len(data))
            if not part:
                break
            data += part
        return data.decode(errors="replace")

    def call(self, method, params=None, timeout_frames=60):
        """发一个 CDP 方法，等同 id 的响应，返回 result（失败返回带 error 的 dict）。

        断线（ConnectionReset / OSError）时重连一次并重试同一条命令；
        页面重载导致的断线是常态，不该让上层驱动脚本整体失败。
        """
        for attempt in (0, 1):
            try:
                return self._call_once(method, params, timeout_frames)
            except (ConnectionError, OSError):
                if attempt == 0:
                    try:
                        self.reconnect()
                    except Exception:
                        raise
                    continue
                raise

    def _call_once(self, method, params, timeout_frames):
        self._id += 1
        want = self._id
        self._send(json.dumps({"id": want, "method": method, "params": params or {}}))
        for _ in range(timeout_frames):
            msg = self._recv()
            if msg is None:
                return None
            try:
                obj = json.loads(msg)
            except Exception:
                continue
            if obj.get("id") != want:
                continue
            if "error" in obj:
                return {"error": obj["error"]}
            return obj.get("result", {})
        return None

    def evaluate(self, expr, timeout_frames=60):
        res = self.call("Runtime.evaluate", {
            "expression": expr, "returnByValue": True, "awaitPromise": True,
        }, timeout_frames=timeout_frames)
        if res is None:
            return None
        if "error" in res:
            return "ERROR: " + json.dumps(res["error"], ensure_ascii=False)[:600]
        if "exceptionDetails" in res:
            return "EXCEPTION: " + json.dumps(res["exceptionDetails"], ensure_ascii=False)[:600]
        return res.get("result", {}).get("value")

    def close(self):
        try:
            self.s.close()
        except Exception:
            pass


def eval_on_page(expr, prefer="https://zcode.z.ai"):
    """打开一条短连接 eval 一个表达式；页面不在时返回 (None, 原因)。

    WebView 的 devtools 会话会被页面重载、渲染进程重建等原因掐断，
    短连接模式下重连一次即可，不需要调用方处理。
    """
    last = None
    for attempt in (0, 1):
        t = pick_page(prefer)
        if not t:
            return None, "WebView 未停在 zcode.z.ai 页面"
        dev = None
        try:
            dev = DevTools(t["webSocketDebuggerUrl"])
            return dev.evaluate(expr), None
        except (ConnectionError, OSError) as e:
            last = e
            if attempt == 0:
                time.sleep(1.0)
                continue
            return None, "devtools 连接失败: %s" % e
        finally:
            if dev is not None:
                dev.close()
    return None, "devtools 连接失败: %s" % last


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "tabs"
    try:
        if cmd == "tabs":
            for t in targets():
                print("%s | %s | %s" % (t.get("type"), t.get("id"), (t.get("url") or "")[:100]))
            return 0
        if cmd == "eval":
            out, err = eval_on_page(sys.argv[2])
            print(err if err else (out if out is not None else "NULL"))
            return 0 if err is None else 1
        if cmd == "cdp":
            params = {}
            if "--params" in sys.argv:
                params = json.loads(sys.argv[sys.argv.index("--params") + 1])
            t = pick_page()
            if not t:
                print("NO_TARGET")
                return 1
            dev = DevTools(t["webSocketDebuggerUrl"])
            try:
                print(json.dumps(dev.call(sys.argv[2], params), ensure_ascii=False)[:2000])
            finally:
                dev.close()
            return 0
    except Exception as e:
        print("FAIL: %s" % e)
        return 1
    print("usage: wvcdp.py tabs|eval <expr>|cdp <method> [--params json]")
    return 1


if __name__ == "__main__":
    sys.exit(main())
