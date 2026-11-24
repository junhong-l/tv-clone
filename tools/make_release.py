#!/usr/bin/env python3
"""创建 GitHub Release 并上传 APK。

用法：
    GITHUB_TOKEN=ghp_xxx python3 tools/make_release.py v2.0.4

令牌权限：classic token 需要 repo；fine-grained 需要 Contents: Read and write。
"""
import json
import os
import sys
import urllib.error
import urllib.request

REPO = "junhong-l/tv-clone"
API = "https://api.github.com"
PROJECT_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def request(url, data=None, method="GET", content_type="application/json"):
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Authorization", "Bearer " + os.environ["GITHUB_TOKEN"])
    req.add_header("Accept", "application/vnd.github+json")
    req.add_header("Content-Type", content_type)
    req.add_header("User-Agent", "tvclone-release")
    try:
        with urllib.request.urlopen(req, timeout=300) as resp:
            return json.load(resp)
    except urllib.error.HTTPError as exc:
        body = exc.read().decode("utf-8", "replace")
        raise SystemExit(f"HTTP {exc.code} {url}\n{body}") from None


def main():
    if len(sys.argv) < 2:
        raise SystemExit(__doc__)
    tag = sys.argv[1]
    if not os.environ.get("GITHUB_TOKEN"):
        raise SystemExit("请先设置环境变量 GITHUB_TOKEN")

    apk = os.path.join(PROJECT_ROOT, "dist", f"TvDualManager-{tag}.apk")
    notes_file = os.path.join(PROJECT_ROOT, "docs", f"release-{tag}.md")
    if not os.path.exists(apk):
        raise SystemExit(f"找不到 {apk}，先跑 ./app/build.sh")
    if not os.path.exists(notes_file):
        raise SystemExit(f"找不到发布说明 {notes_file}")

    with open(notes_file, encoding="utf-8") as f:
        body = f.read()

    payload = json.dumps({
        "tag_name": tag,
        "name": f"双开管家 {tag}",
        "body": body,
        "draft": False,
        "prerelease": False,
    }).encode("utf-8")

    print(f"创建 release {tag} …")
    try:
        release = request(f"{API}/repos/{REPO}/releases", payload, "POST")
    except SystemExit as exc:
        if "already_exists" not in str(exc):
            raise
        print("该 tag 的 release 已存在，改为上传附件")
        release = request(f"{API}/repos/{REPO}/releases/tags/{tag}")

    upload_url = release["upload_url"].split("{")[0]
    name = os.path.basename(apk)
    size = os.path.getsize(apk)
    print(f"上传 {name}（{size / 1024:.0f} KB）…")
    with open(apk, "rb") as f:
        data = f.read()
    asset = request(
        f"{upload_url}?name={name}",
        data,
        "POST",
        "application/vnd.android.package-archive",
    )
    print("完成：", asset.get("browser_download_url", "(无链接)"))
    print("Release：", release.get("html_url", ""))


if __name__ == "__main__":
    main()
