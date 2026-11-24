"""读取 APK 元信息（包名/版本/标签/SDK/架构/加固迹象）。"""
from __future__ import annotations

import re
import zipfile
from dataclasses import dataclass, field
from pathlib import Path
from typing import List, Optional

from .sdk import Sdk, ToolError, run


@dataclass
class ApkMeta:
    path: Path
    package: str
    version_code: str = "0"
    version_name: str = ""
    label: str = ""
    min_sdk: int = 21
    target_sdk: int = 30
    size: int = 0
    split: str = ""
    native_abis: List[str] = field(default_factory=list)
    launchable: str = ""
    leanback: bool = False
    dex_count: int = 0
    has_shared_user: bool = False
    shared_user: str = ""
    app_class: str = ""
    app_component_factory: str = ""

    @property
    def is_split(self) -> bool:
        return bool(self.split)

    def summary(self) -> str:
        abis = ",".join(self.native_abis) or "无 native"
        size = f"{self.size / 1048576:.1f}MB" if self.size >= 1048576 else f"{self.size / 1024:.0f}KB"
        return (f"{self.package} v{self.version_name}({self.version_code}) "
                f"「{self.label}」 minSdk={self.min_sdk} targetSdk={self.target_sdk} "
                f"{size} dex={self.dex_count} {abis}")


def badging(sdk: Sdk, apk: Path) -> str:
    cp = run([sdk.aapt2, "dump", "badging", apk], check=False)
    if cp.returncode != 0 or "package: name=" not in cp.stdout:
        raise ToolError(f"不是可解析的 APK（aapt2 dump badging 失败）: {apk}\n{(cp.stderr or '')[-800:]}")
    return cp.stdout


def manifest_tree(sdk: Sdk, apk: Path) -> str:
    return run([sdk.aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk], check=False).stdout


_ATTR_LINE = re.compile(r'^\s*A: (?:http://[^:]+:)?(?P<name>[A-Za-z0-9_\-]+)(?:\([^)]*\))?=(?P<val>.*)$')


def application_attrs(tree: str) -> dict:
    """取 <application> 自身的属性（只认第一个子元素之前的 A: 行）。"""
    lines = tree.splitlines()
    attrs = {}
    for i, line in enumerate(lines):
        m = re.match(r"^(\s*)E: application\b", line)
        if not m:
            continue
        indent = len(m.group(1))
        for nxt in lines[i + 1:]:
            if not nxt.strip():
                continue
            lead = len(nxt) - len(nxt.lstrip())
            if lead <= indent and nxt.strip():
                break
            if nxt.strip().startswith("E:") or nxt.strip().startswith("C:"):
                break
            am = _ATTR_LINE.match(nxt)
            if am:
                attrs[am.group("name")] = am.group("val").split(" (Raw:")[0].strip().strip('"')
        break
    return attrs


def inspect_apk(sdk: Sdk, apk: Path) -> ApkMeta:
    apk = Path(apk)
    if not apk.exists():
        raise ToolError(f"文件不存在: {apk}")
    out = badging(sdk, apk)

    def grab(pattern: str, default: str = "") -> str:
        m = re.search(pattern, out, re.M)
        return m.group(1) if m else default

    pkg = grab(r"^package: name='([^']+)'")
    meta = ApkMeta(
        path=apk,
        package=pkg,
        version_code=grab(r"^package:.*versionCode='([^']*)'", "0"),
        version_name=grab(r"^package:.*versionName='([^']*)'"),
        split=grab(r"^package:.*split='([^']*)'"),
        label=(re.search(r"^application-label:'(.*)'$", out, re.M) or
               re.search(r"^application-label-[^:]*:'(.*)'$", out, re.M) or [None, ""])[1],
        min_sdk=int(grab(r"^minSdkVersion:'(\d+)'") or grab(r"^sdkVersion:'(\d+)'") or 21),
        target_sdk=int(grab(r"^targetSdkVersion:'(\d+)'") or 30),
        size=apk.stat().st_size,
        launchable=grab(r"^launchable-activity: name='([^']*)'"),
        leanback="leanback-launchable-activity" in out,
    )
    meta.native_abis = re.findall(r"'([a-z0-9_\-]+)'", grab(r"^native-code:(.*)$"))

    with zipfile.ZipFile(apk) as zf:
        names = zf.namelist()
        meta.dex_count = sum(1 for n in names if re.fullmatch(r"classes\d*\.dex", n))

    tree = manifest_tree(sdk, apk)
    m = re.search(r'android:sharedUserId\([^)]*\)="([^"]+)"', tree)
    if m:
        meta.has_shared_user, meta.shared_user = True, m.group(1)

    app = application_attrs(tree)
    name = app.get("name", "")
    if name:
        meta.app_class = name if name.startswith(pkg + ".") else (
            pkg + name if name.startswith(".") else pkg + "." + name
        )
    meta.app_component_factory = app.get("appComponentFactory", "")
    return meta


def find_app_class_file(decoded: Path, app_class: str) -> Optional[Path]:
    """反编译后确认 Application 类真的在 smali 里（不在 = 加固壳）。"""
    if not app_class:
        return None
    rel = app_class.replace(".", "/")
    if not rel.startswith("/"):
        rel = rel.lstrip("/")
    for smali in sorted(decoded.glob("smali*")):
        cand = smali / (rel + ".smali")
        if cand.exists():
            return cand
    return None
