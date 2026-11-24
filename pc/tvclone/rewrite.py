"""APK 克隆重写：包名、authorities、进程名、smali 路径、资源与 assets。"""
from __future__ import annotations

import re
import shutil
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Tuple

_ATTR = re.compile(r'(?P<name>[A-Za-z_][\w:.\-]*)\s*=\s*"(?P<val>[^"]*)"')
_COMPONENT = re.compile(r'<(?P<tag>activity-alias|activity|service|receiver|provider)(?=[\s/>])')
_SMALI_DIR = re.compile(r"^smali(_classes\d+)?$")


@dataclass
class RewriteReport:
    manifest_changes: List[str] = field(default_factory=list)
    smali_files: int = 0
    moved_dirs: int = 0
    res_changes: int = 0
    asset_changes: int = 0
    warnings: List[str] = field(default_factory=list)
    removed_shared_user: str = ""

    def changed(self) -> bool:
        return bool(self.manifest_changes or self.smali_files or self.moved_dirs or self.res_changes)


def _xml_escape(text: str) -> str:
    return (text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace('"', "&quot;"))


def _rewrite_value(value: str, old: str, new: str) -> Tuple[str, bool]:
    """把值里的旧包名换掉（支持 authorities 的 ';' 列表）。"""
    parts = value.split(";")
    hit = False
    out = []
    for part in parts:
        if part == old or part.startswith((old + ".", old + ":", old + "$")):
            part = new + part[len(old):]
            hit = True
        out.append(part)
    return ";".join(out), hit


def _find_tag_end(text: str, start: int) -> int:
    """从 '<' 找到标签的 '>'，跳过引号内的内容。"""
    quote = False
    for i in range(start, len(text)):
        ch = text[i]
        if ch == '"':
            quote = not quote
        elif ch == ">" and not quote:
            return i
    return -1


def rewrite_manifest(text: str, old: str, new: str, label_suffix: str = "",
                     fix_exported: bool = True, strip_shared_user: bool = True) -> Tuple[str, RewriteReport]:
    rep = RewriteReport()

    # 1) sharedUserId 必须先删：两个包不可能同签名，装着会 INSTALL_FAILED_SHARED_USER_INCOMPATIBLE
    if strip_shared_user:
        for attr in ("sharedUserId", "sharedUserMaxSdkVersion"):
            pat = re.compile(r'\s+android:%s\s*=\s*"[^"]*"' % attr)
            m = pat.search(text)
            if m:
                if attr == "sharedUserId":
                    rep.removed_shared_user = re.search(r'"([^"]*)"', m.group(0)).group(1)
                text = pat.sub("", text)

    # 2) 所有以旧包名为前缀的属性值：package / android:name / authorities / process / taskAffinity …
    def repl(m: "re.Match[str]") -> str:
        raw = m.group("val")
        value, hit = _rewrite_value(raw, old, new)
        if not hit:
            return m.group(0)
        if len(rep.manifest_changes) < 200:
            rep.manifest_changes.append(f"{m.group('name')}: {raw} -> {value}")
        return f'{m.group("name")}="{value}"'

    text = _ATTR.sub(repl, text)

    # 3) Android 12+ 要求带 intent-filter 的组件显式写 android:exported，否则装不上
    if fix_exported:
        pos = 0
        out = []
        while True:
            m = _COMPONENT.search(text, pos)
            if not m:
                out.append(text[pos:])
                break
            end = _find_tag_end(text, m.start())
            if end < 0:
                out.append(text[pos:])
                break
            head = text[m.start():end + 1]
            out.append(text[pos:m.start()])
            if head.rstrip().endswith("/>") or "android:exported" in head:
                out.append(head)
            else:
                close = text.find(f"</{m.group('tag')}>", end)
                body = text[end:close] if close > 0 else ""
                if "<intent-filter" in body:
                    out.append(head[:-1].rstrip() + ' android:exported="true">')
                    rep.manifest_changes.append(f"{m.group('tag')}: 补 android:exported=\"true\"")
                else:
                    out.append(head)
            pos = end + 1
        text = "".join(out)

    # 4) 应用名加后缀，桌面上能区分「原版/克隆」
    if label_suffix:
        m = re.search(r"<application\b[^>]*>", text, re.S)
        if m:
            tag = m.group(0)
            label_m = re.search(r'android:label\s*=\s*"([^"]*)"', tag)
            literal = None
            if label_m:
                literal = label_m.group(1)
            if literal and literal.startswith("@"):
                literal = None  # @string/xxx 交给调用方传原标签
            rep.manifest_changes.append(f"label: {literal or '(资源引用)'} + {label_suffix}")
            new_tag = tag
            if label_m:
                new_tag = tag[:label_m.start()] + f'android:label="{_xml_escape((literal or "@string/app_name"))}"' + tag[label_m.end():]
            else:
                new_tag = tag[:-1] + f' android:label="@string/app_name">'
            text = text[:m.start()] + new_tag + text[m.end():]

    return text, rep


def apply_label(text: str, label: str) -> str:
    """把 <application> 的 android:label 换成字面量（桌面显示名）。"""
    m = re.search(r"<application\b[^>]*>", text, re.S)
    if not m:
        return text
    tag = m.group(0)
    label_m = re.search(r'android:label\s*=\s*"[^"]*"', tag)
    if label_m:
        new_tag = tag[:label_m.start()] + f'android:label="{_xml_escape(label)}"' + tag[label_m.end():]
    else:
        new_tag = tag[:-1] + f' android:label="{_xml_escape(label)}">'
    return text[:m.start()] + new_tag + text[m.end():]


def _patterns(old: str, new: str):
    slash_old = old.replace(".", "/")
    slash_new = new.replace(".", "/")
    suffix = new[len(old):]                       # 例如 .clone1
    suffix_slash = suffix.replace(".", "/")       # 例如 /clone1
    return [
        # smali 描述符 / 路径：Lcom/demo/tvplayer/Foo; 与 com/demo/tvplayer/xxx
        (re.compile(r"(L?)" + re.escape(slash_old) + r"/(?!" + re.escape(suffix_slash.lstrip("/")) + r"/)"),
         r"\g<1>" + slash_new + "/"),
        # 点号形式：com.demo.tvplayer.Foo / "com.demo.tvplayer"
        (re.compile(r"(?<![\w.])" + re.escape(old) + r"\.(?!clone\d+\.)"), new + "."),
        (re.compile(r"(?<![\w.])" + re.escape(old) + r"(?![\w.])"), new),
    ]


def rename_smali(decoded: Path, old: str, new: str) -> RewriteReport:
    """重写所有 smali 中的包名引用，并把包目录搬到新路径。"""
    rep = RewriteReport()
    pats = [p for p in _patterns(old, new) if p[1] is not None]
    old_slash_b = old.replace(".", "/").encode()
    old_dot_b = old.encode()
    suffix_dir = new[len(old):].replace(".", "/").strip("/")

    for smali_root in sorted(p for p in decoded.iterdir() if p.is_dir() and _SMALI_DIR.match(p.name)):
        for f in smali_root.rglob("*.smali"):
            data = f.read_bytes()
            if old_slash_b not in data and old_dot_b not in data:
                continue
            text = data.decode("utf-8", "surrogateescape")
            for pat, sub in pats:
                text = pat.sub(sub, text)
            f.write_bytes(text.encode("utf-8", "surrogateescape"))
            rep.smali_files += 1

        # 目录搬迁：smali/com/demo/tvplayer -> smali/com/demo/tvplayer/clone1
        old_dir = smali_root.joinpath(*old.split("."))
        if not old_dir.is_dir():
            continue
        tmp = decoded / f".__move_{smali_root.name}"
        if tmp.exists():
            shutil.rmtree(tmp)
        shutil.move(str(old_dir), str(tmp))
        old_dir.mkdir(parents=True, exist_ok=True)
        shutil.move(str(tmp), str(old_dir / suffix_dir))
        rep.moved_dirs += 1

    return rep


def rewrite_res(decoded: Path, old: str, new: str, rep: RewriteReport) -> None:
    """--decode-res 模式：res/ 与 assets/ 里的文本包名一并替换。"""
    for sub, counter in (("res", "res"), ("assets", "asset")):
        root = decoded / sub
        if not root.is_dir():
            continue
        for f in root.rglob("*"):
            if not f.is_file() or f.stat().st_size > 4 * 1024 * 1024:
                continue
            try:
                data = f.read_bytes()
            except OSError:
                continue
            if old.encode() not in data and old.replace(".", "/").encode() not in data:
                continue
            if b"\x00" in data[:4096] and sub == "res":   # 二进制资源跳过
                continue
            text = data.decode("utf-8", "surrogateescape")
            for pat, s in _patterns(old, new):
                text = pat.sub(s, text)
            f.write_bytes(text.encode("utf-8", "surrogateescape"))
            if counter == "res":
                rep.res_changes += 1
            else:
                rep.asset_changes += 1


def scan_res_strings(sdk, apk: Path, old: str) -> int:
    """统计 resources.arsc 字符串池里出现的旧包名次数（决定要不要 --decode-res）。"""
    from .sdk import run
    cp = run([sdk.aapt2, "dump", "strings", apk], check=False)
    return (cp.stdout or "").count(old)
