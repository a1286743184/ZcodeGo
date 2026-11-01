"""一键打包并部署到模拟器/真机，供后续复用。

做四件事，任何一步失败都立刻停下并给出可读原因：
  1. 构建 APK（默认 debug；--release 走签名包），带配置缓存与离线优先
  2. 安装（debug 用 install -r；包名签名不一致时自动卸载重装，避免 INSTALL_FAILED_UPDATE_INCOMPATIBLE）
  3. 启动主界面，并按需补齐重装后被重置的运行时权限
  4. 可选：建立 WebView CDP 端口映射、截图、抓 logcat

为什么不用 gradlew 手敲：重装会重置通知权限、adb forward 必须用主进程 pid、
logcat 默认缓冲太小，这三件事每次都忘，写进脚本里就不用再记。

用法:
  python tools/deploy.py                 # 构建 debug + 安装 + 启动
  python tools/deploy.py --release       # 构建 release 并安装
  python tools/deploy.py --no-build      # 跳过构建，直接用现有 APK 安装
  python tools/deploy.py --build-only
  python tools/deploy.py --cdp           # 额外建立 WebView CDP 端口
  python tools/deploy.py --shot out.png  # 额外截图
  python tools/deploy.py --logcat 200    # 额外抓最近 200 行日志

环境变量:
  ZR_SERIAL   设备序列号，默认自动取唯一在线设备
  ZR_ADB      adb 路径
"""
import argparse
import os
import shutil
import subprocess
import sys
import time

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PKG = "com.zcodeapp.remote"
APK_DEBUG = os.path.join(ROOT, "app", "build", "outputs", "apk", "debug", "app-debug.apk")
APK_RELEASE = os.path.join(ROOT, "app", "build", "outputs", "apk", "release", "app-release.apk")

ADB = os.environ.get("ZR_ADB") or shutil.which("adb") or "adb"
JAVA_HOME = os.environ.get("ZR_JAVA_HOME") or os.environ.get("JAVA_HOME") or ""
GRADLE_HOME = os.environ.get("ZR_GRADLE_HOME") or os.path.join(ROOT, ".gradle-home")


def log(msg):
    print("[deploy] %s" % msg, flush=True)


def run(args, cwd=None, timeout=1800, env=None, quiet=False):
    e = dict(os.environ)
    e.update(env or {})
    r = subprocess.run(args, cwd=cwd, capture_output=True, text=True, timeout=timeout,
                       encoding="utf-8", errors="replace", env=e)
    if not quiet:
        out = (r.stdout or "").strip()
        err = (r.stderr or "").strip()
        if r.returncode != 0:
            print(out[-4000:])
            print(err[-4000:])
    return r


def pick_serial():
    s = os.environ.get("ZR_SERIAL")
    if s:
        return s
    r = run([ADB, "devices"], quiet=True)
    devs = [l.split("\t")[0] for l in (r.stdout or "").splitlines()[1:] if "\tdevice" in l]
    if len(devs) == 1:
        return devs[0]
    if not devs:
        raise SystemExit("没有在线设备。先启动模拟器或连上真机。")
    raise SystemExit("有多台设备在线（%s），请用 ZR_SERIAL 指定。" % ", ".join(devs))


def adb(serial, *args, timeout=120, quiet=False):
    return run([ADB, "-s", serial, *args], timeout=timeout, quiet=quiet)


def build(release):
    """构建 APK。用配置缓存 + 离线优先，减少每次重复计算与网络元数据检查。"""
    task = "assembleRelease" if release else "assembleDebug"
    env = {"GRADLE_USER_HOME": GRADLE_HOME}
    if JAVA_HOME:
        env["JAVA_HOME"] = JAVA_HOME
    wrapper = os.path.join(ROOT, "gradlew.bat")
    base = [wrapper, "--console=plain", "--configuration-cache", "--build-cache", task]
    # 先试离线：依赖都已缓存时省掉每次的仓库元数据请求。失败再联网重试一次。
    for attempt, extra in ((1, ["--offline"]), (2, [])):
        t0 = time.time()
        r = run(base + extra, cwd=ROOT, env=env)
        dt = time.time() - t0
        if r.returncode == 0:
            log("构建成功 %s（%.1fs，%s）" % (task, dt, "离线" if extra else "联网"))
            return
        if attempt == 1:
            log("离线构建失败，改用联网重试（通常是新增依赖）")
    raise SystemExit("构建失败")


def apk_path(release):
    p = APK_RELEASE if release else APK_DEBUG
    if not os.path.exists(p):
        raise SystemExit("找不到 APK：%s" % p)
    return p


def install(serial, apk, release):
    """安装 APK。签名冲突（重装不同签名的包）时自动卸载重装。

    debug 用 install -r 增量覆盖；release 若与已装 debug 包签名不同会直接失败，
    此时卸载重装是唯一出路。卸载会清数据，所以只在确实冲突时才做。
    """
    r = adb(serial, "install", "-r", apk, timeout=600)
    if r.returncode == 0 and "Success" in (r.stdout or ""):
        log("安装成功（增量覆盖）")
        return
    out = (r.stdout or "") + (r.stderr or "")
    if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in out or "signatures do not match" in out:
        log("签名与已装版本不一致，卸载后重装（会清空 App 数据）")
        adb(serial, "uninstall", PKG, quiet=True)
        r2 = adb(serial, "install", apk, timeout=600)
        if r2.returncode == 0 and "Success" in (r2.stdout or ""):
            log("安装成功（卸载重装）")
            return
        print((r2.stdout or "") + (r2.stderr or ""))
        raise SystemExit("安装失败")
    print(out)
    raise SystemExit("安装失败")


def fix_permissions(serial):
    """重装会把运行时权限打回默认值。通知权限直接放行，
    否则后续所有通知类验证都白跑（这是本项目踩过的坑）。"""
    adb(serial, "shell", "cmd", "appops", "set", PKG, "POST_NOTIFICATION", "allow", quiet=True)
    adb(serial, "shell", "pm", "grant", PKG, "android.permission.POST_NOTIFICATIONS", quiet=True)
    log("通知权限已放行")


def fix_proxy(serial, proxy=None):
    """模拟器网络默认走宿主直连。

    历史背景：宿主开着 Clash 的 fake-ip 时，模拟器内会把域名解析到 198.18.x.x，
    那个网段在模拟器里不可路由，表现为 App 页面白屏、Chrome 报
    DNS_PROBE_FINISHED_NO_INTERNET，而宿主机 curl 同一个域名却是 200。

    但本项目要访问的 zcode.z.ai 与 bigmodel.cn 都是国内直连可达的站点，
    走代理反而会引入不必要的失败点（实测：配上宿主代理后 bigmodel.cn 直接超时）。
    所以默认**清空**模拟器代理，让流量直连。
    只有当确实需要走宿主 Clash 时才用 --proxy 10.0.2.2:7897 显式指定。
    """
    target = proxy if proxy is not None else os.environ.get("ZR_EMU_PROXY", "")
    if not target:
        adb(serial, "shell", "settings", "put", "global", "http_proxy", ":0", quiet=True)
        log("模拟器代理已清空（直连）")
        return
    adb(serial, "shell", "settings", "put", "global", "http_proxy", target, quiet=True)
    log("模拟器代理 -> %s" % target)


def enlarge_logcat(serial):
    """logcat 默认 2MiB，几十个事件就冲掉，调大后再抓。"""
    adb(serial, "logcat", "-G", "16M", quiet=True)


def launch(serial):
    adb(serial, "shell", "am", "force-stop", PKG, quiet=True)
    r = adb(serial, "shell", "am", "start", "-n", "%s/.MainActivity" % PKG)
    if "Error" in (r.stdout or "") or "Exception" in (r.stdout or ""):
        print(r.stdout)
        raise SystemExit("启动失败")
    time.sleep(2.0)
    pid = app_pid(serial)
    log("已启动 %s (pid=%s)" % (PKG, pid))
    return pid


def app_pid(serial):
    r = adb(serial, "shell", "pidof", PKG, quiet=True)
    out = (r.stdout or "").strip()
    return out.split()[0] if out else None


def setup_cdp(serial, port=9222):
    """建立 WebView devtools 端口映射。socket 名必须带主进程 pid，
    进程重启后旧 forward 就失效，所以每次都重建。"""
    pid = app_pid(serial)
    if not pid:
        raise SystemExit("App 未运行，无法建立 CDP 映射")
    adb(serial, "forward", "--remove", "tcp:%d" % port, quiet=True)
    r = adb(serial, "forward", "tcp:%d" % port, "localabstract:webview_devtools_remote_%s" % pid)
    if r.returncode != 0:
        print((r.stdout or "") + (r.stderr or ""))
        raise SystemExit("adb forward 失败")
    log("WebView CDP: http://127.0.0.1:%d/json/list" % port)


def screenshot(serial, path):
    r = subprocess.run([ADB, "-s", serial, "exec-out", "screencap", "-p"],
                       capture_output=True, timeout=120)
    if r.returncode != 0 or not r.stdout:
        raise SystemExit("截图失败")
    with open(path, "wb") as f:
        f.write(r.stdout)
    log("截图: %s (%d bytes)" % (path, len(r.stdout)))


def dump_logcat(serial, lines):
    r = adb(serial, "logcat", "-d", "-t", str(lines), quiet=True)
    print(r.stdout or "")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--release", action="store_true")
    ap.add_argument("--no-build", action="store_true")
    ap.add_argument("--build-only", action="store_true")
    ap.add_argument("--cdp", action="store_true")
    ap.add_argument("--proxy", help="模拟器 HTTP 代理，默认清空（直连）；如需走宿主 Clash 传 10.0.2.2:7897")
    ap.add_argument("--no-proxy", action="store_true", help="不改模拟器代理设置")
    ap.add_argument("--shot")
    ap.add_argument("--logcat", type=int)
    args = ap.parse_args()

    if not os.path.exists(ADB) and shutil.which(ADB) is None:
        raise SystemExit("找不到 adb：%s（可用 ZR_ADB 覆盖）" % ADB)

    if not args.no_build:
        build(args.release)
    if args.build_only:
        return 0

    serial = pick_serial()
    log("设备: %s" % serial)
    install(serial, apk_path(args.release), args.release)
    fix_permissions(serial)
    if not args.no_proxy:
        fix_proxy(serial, args.proxy)
    enlarge_logcat(serial)
    launch(serial)
    if args.cdp:
        setup_cdp(serial)
    if args.shot:
        screenshot(serial, args.shot)
    if args.logcat:
        dump_logcat(serial, args.logcat)
    return 0


if __name__ == "__main__":
    sys.exit(main())
