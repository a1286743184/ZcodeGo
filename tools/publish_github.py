"""发布一版 APK 到 GitHub Release，并更新 latest.json 清单资产。

App 内更新走 Release 上的清单：默认地址是
  https://github.com/<owner>/<repo>/releases/latest/download/latest.json
（releases/latest/download/ 永远指向最新 release，公开资产匿名可下载，无需凭据。）

每次发布做四件事：
  1. 从 release APK 读 versionName / versionCode（aapt2 badging）
  2. 创建 release（tag v<versionName>；已存在则复用）
  3. 上传 APK 资产两份：版本化名 + zcodego-latest.apk（固定名兜底）
  4. 上传 latest.json 清单（url 指向版本化资产，fallbackUrl 指向固定名资产）

用法:
  python tools/publish_github.py                # 发布 app/build/outputs/apk/release 的产物
  python tools/publish_github.py --dry-run      # 只算哈希与清单，不上传
  python tools/publish_github.py --notes "..."  # release 说明

环境变量:
  GITHUB_TOKEN  必需（repo 权限，classic 或 fine-grained 均可）
  ZR_GH_REPO    目标仓库，默认 a1286743184/ZcodeGo
  ZR_APK        指定 APK 路径（默认 release 产物）
  HTTPS_PROXY   GitHub 直连不稳时设置，如 http://127.0.0.1:7897
"""
import argparse
import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEFAULT_APK = os.path.join(ROOT, "app", "build", "outputs", "apk", "release", "app-release.apk")
API = "https://api.github.com"
UPLOADS = "https://uploads.github.com"
UA = "ZcodeGo-publish/1.0"


def read_version(apk):
    """从 aapt2 badging 读 versionName/versionCode；读不到退回解析 build.gradle.kts。"""
    bt_root = None
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if not sdk:
        lp = os.path.join(ROOT, "local.properties")
        if os.path.isfile(lp):
            m = re.search(r"sdk\.dir=(.+)", open(lp, encoding="utf-8").read())
            if m:
                sdk = m.group(1).strip()
    if sdk:
        bt = os.path.join(sdk, "build-tools")
        if os.path.isdir(bt):
            for d in sorted(os.listdir(bt), reverse=True):
                if os.path.isfile(os.path.join(bt, d, "aapt2.exe")) or \
                   os.path.isfile(os.path.join(bt, d, "aapt2")):
                    bt_root = os.path.join(bt, d)
                    break
    if bt_root:
        aapt = os.path.join(bt_root, "aapt2.exe" if os.name == "nt" else "aapt2")
        out = os.popen('"%s" dump badging "%s"' % (aapt, apk)).read()
        m = re.search(r"versionCode='(\d+)' versionName='([^']+)'", out)
        if m:
            return int(m.group(1)), m.group(2)
    src = open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()
    return (int(re.search(r"versionCode\s*=\s*(\d+)", src).group(1)),
            re.search(r'versionName\s*=\s*"([^"]+)"', src).group(1))


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def api_request(method, path, token, payload=None):
    url = path if path.startswith("http") else API + path
    data = json.dumps(payload).encode() if payload is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers={
        "User-Agent": UA,
        "Authorization": "Bearer %s" % token,
        "Accept": "application/vnd.github+json",
        "Content-Type": "application/json",
    })
    with urllib.request.urlopen(req, timeout=60) as r:
        body = r.read().decode("utf-8", "replace")
        return json.loads(body) if body else {}


def upload_asset(upload_url, name, path, token):
    """单连接 PUT；GitHub 资产上限 2GB，30MB 级 APK 直传即可。"""
    size = os.path.getsize(path)
    sep = "&" if "?" in upload_url else "?"
    url = "%s%sname=%s" % (upload_url, sep, name)
    req = urllib.request.Request(url, method="POST", headers={
        "User-Agent": UA,
        "Authorization": "Bearer %s" % token,
        "Content-Type": "application/octet-stream",
        "Content-Length": str(size),
    })
    with open(path, "rb") as f, urllib.request.urlopen(req, timeout=1800, data=f) as r:
        r.read()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apk", default=os.environ.get("ZR_APK", DEFAULT_APK))
    ap.add_argument("--notes", default="")
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    token = os.environ.get("GITHUB_TOKEN", "").strip()
    if not token and not args.dry_run:
        sys.exit("缺少 GITHUB_TOKEN 环境变量")
    repo = os.environ.get("ZR_GH_REPO", "a1286743184/ZcodeGo")

    apk = args.apk
    if not os.path.isfile(apk):
        sys.exit("找不到 APK: %s" % apk)
    code, name = read_version(apk)
    digest, size = sha256_of(apk), os.path.getsize(apk)
    tag = "v%s" % name
    versioned = "zcodego-%s-%d.apk" % (name, code)
    base = "https://github.com/%s/releases" % repo
    manifest = {
        "versionName": name,
        "versionCode": code,
        "size": size,
        "sha256": digest,
        "notes": args.notes,
        "url": "%s/download/%s/%s" % (base, tag, versioned),
        "fallbackUrl": "%s/latest/download/zcodego-latest.apk" % base,
    }
    print("[publish] %s(%d) tag=%s" % (name, code, tag))
    print("[publish] sha256=%s size=%d" % (digest, size))
    print("[publish] manifest: %s" % json.dumps(manifest, ensure_ascii=False))

    if args.dry_run:
        return 0

    # release 已存在则复用，否则创建
    try:
        rel = api_request("GET", "/repos/%s/releases/tags/%s" % (repo, tag), token)
        print("[publish] release 已存在，复用 #%s" % rel.get("id"))
    except urllib.error.HTTPError as e:
        if e.code != 404:
            raise
        rel = api_request("POST", "/repos/%s/releases" % repo, token, payload={
            "tag_name": tag,
            "target_commitish": "main",
            "name": tag,
            "body": args.notes or "Release %s" % tag,
            "draft": False,
            "prerelease": False,
        })
        print("[publish] release 创建 #%s" % rel.get("id"))

    upload_url = rel["upload_url"].split("{")[0]
    man_path = os.path.join(ROOT, "app", "build", "latest.json")
    with open(man_path, "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)
    tmp_latest = os.path.join(ROOT, "app", "build", "zcodego-latest.apk")
    if not os.path.exists(tmp_latest) or os.path.getsize(tmp_latest) != size:
        with open(apk, "rb") as s, open(tmp_latest, "wb") as d:
            d.write(s.read())

    # 已有同名资产先删（重复发布同 tag 时）
    try:
        for asset in api_request("GET", "/repos/%s/releases/%s/assets" % (repo, rel["id"]), token):
            if asset["name"] in (versioned, "zcodego-latest.apk", "latest.json"):
                api_request("DELETE", "/repos/%s/releases/assets/%s" % (repo, asset["id"]), token)
                print("[publish] 删除旧资产 %s" % asset["name"])
    except urllib.error.HTTPError:
        pass

    for name_, path_ in ((versioned, apk), ("zcodego-latest.apk", tmp_latest), ("latest.json", man_path)):
        print("[publish] 上传 %s (%d bytes)" % (name_, os.path.getsize(path_)))
        upload_asset(upload_url, name_, path_, token)
    print("[publish] 清单 https://github.com/%s/releases/latest/download/latest.json" % repo)
    return 0


if __name__ == "__main__":
    sys.exit(main())
