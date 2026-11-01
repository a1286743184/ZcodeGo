"""验证 file-pick-guard.js 的语义。

为什么需要这个测试：这个脚本不改变任何业务逻辑，只让页面在「系统文件选择器盖住 App」
的那几秒里认为自己是可见的。写错了有两种后果，都很隐蔽：
  - 屏蔽过头：用户真按 Home 切后台时页面也以为可见，transport 不挂起，
    心跳照发，回来后状态是错的
  - 屏蔽不足：选择文件时页面仍然挂起，上传请求照样被丢弃（就是被修的那个 bug）

用最小 DOM 桩跑真实脚本，断言开关前后 document.visibilityState / hidden 的取值
与事件拦截行为。不需要模拟器，也不需要真实配对会话。

用法:
  node tools/capture/test_file_pick_guard.js
"""
import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
SCRIPT = os.path.join(ROOT, "app", "src", "main", "assets", "capture", "file-pick-guard.js")

HARNESS = r"""
const fs = require('fs');
const winListeners = [];
const docListeners = [];

// document 的原型上必须有 visibilityState / hidden 的 getter：
// 守卫是读原型描述符的，桩里没有就等于测了个假对象。
const docProto = {
  get visibilityState() { return globalThis.__realVis; },
  get hidden() { return globalThis.__realHidden; },
};
const documentStub = Object.create(docProto);
documentStub.addEventListener = (t, fn, cap) => docListeners.push({ t, fn, cap });

const windowStub = {
  addEventListener: (t, fn, cap) => winListeners.push({ t, fn, cap }),
};

globalThis.__realVis = 'hidden';
globalThis.__realHidden = true;
Object.defineProperty(globalThis, 'document', { value: documentStub, configurable: true });
Object.defineProperty(globalThis, 'window', { value: windowStub, configurable: true });

eval(fs.readFileSync(process.argv[2], 'utf8'));

const G = globalThis.window.__zrFilePick;
const results = [];
const check = (n, c) => results.push([n, c]);

check('未激活时反映真实 visibility', document.visibilityState === 'hidden');
check('未激活时反映真实 hidden', document.hidden === true);

G.begin();
check('激活时 visibility=visible', document.visibilityState === 'visible');
check('激活时 hidden=false', document.hidden === false);

const winCap = winListeners.find(l => l.t === 'visibilitychange' && l.cap === true);
check('已安装 window 捕获监听', !!winCap);
let stopped = false;
winCap.fn({ stopImmediatePropagation() { stopped = true; } });
check('激活时拦截 visibilitychange', stopped === true);

G.end();
check('结束后恢复 visibility=hidden', document.visibilityState === 'hidden');
check('结束后恢复 hidden=true', document.hidden === true);

let stopped2 = false;
winCap.fn({ stopImmediatePropagation() { stopped2 = true; } });
check('结束后不再拦截', stopped2 === false);

globalThis.__realVis = 'visible';
globalThis.__realHidden = false;
check('未激活时跟随真实状态变化', document.visibilityState === 'visible');

G.begin();
const snap = JSON.parse(G.snapshot());
check('snapshot 带 guard 标记', snap.guard === true);
check('snapshot 带 ws 字段', 'ws' in snap);
check('snapshot 带 bridge 字段', 'bridge' in snap);
G.end();

let failed = 0;
for (const [name, ok] of results) {
  if (!ok) failed++;
  console.log((ok ? 'PASS' : 'FAIL').padEnd(5) + name);
}
console.log(failed === 0 ? 'ALL PASS (' + results.length + ')' : failed + ' FAILED');
process.exit(failed === 0 ? 0 : 1);
"""


def main():
    if not os.path.isfile(SCRIPT):
        print("[ERROR] 找不到脚本: %s" % SCRIPT)
        return 1
    with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False, encoding="utf-8") as f:
        f.write(HARNESS)
        harness = f.name
    try:
        r = subprocess.run(["node", harness, SCRIPT], capture_output=True, text=True, timeout=60,
                           encoding="utf-8", errors="replace")
        print(r.stdout.strip())
        if r.returncode != 0:
            print(r.stderr.strip())
        return r.returncode
    except FileNotFoundError:
        print("[ERROR] 需要 node 来跑这个测试")
        return 1
    finally:
        os.unlink(harness)


if __name__ == "__main__":
    sys.exit(main())
