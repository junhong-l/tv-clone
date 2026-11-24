"""Android SDK / 构建工具发现与子进程封装。"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, List, Optional, Sequence


class ToolError(RuntimeError):
    """外部工具执行失败。"""


CANDIDATE_ROOTS = [
    "/opt/android-sdk",
    "/usr/lib/android-sdk",
    "~/Android/Sdk",
    "~/Library/Android/sdk",
]


def _version_key(name: str):
    return tuple(int(x) if x.isdigit() else 0 for x in re.split(r"[.\-]", name))


@dataclass
class Sdk:
    root: Path
    build_tools: Path
    android_jar: Path
    apktool: str
    keytool: str

    # ---- 工具路径 ----
    def tool(self, name: str) -> str:
        p = self.build_tools / name
        if not p.exists():
            raise ToolError(f"缺少 {name}（在 {self.build_tools} 下找不到）")
        return str(p)

    @property
    def aapt2(self) -> str:
        return self.tool("aapt2")

    @property
    def zipalign(self) -> str:
        return self.tool("zipalign")

    @property
    def apksigner(self) -> str:
        return self.tool("apksigner")

    @property
    def adb(self) -> str:
        p = self.root / "platform-tools" / "adb"
        return str(p) if p.exists() else (shutil.which("adb") or "")

    def zipalign_supports_page_align(self) -> bool:
        try:
            cp = run([self.zipalign, "-h"], check=False)
        except Exception:
            return False
        return "-P" in (cp.stdout + cp.stderr)


def find_sdk(explicit: Optional[str] = None) -> Sdk:
    roots: List[Path] = []
    if explicit:
        roots.append(Path(explicit).expanduser())
    for key in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        val = os.environ.get(key)
        if val:
            roots.append(Path(val).expanduser())
    roots += [Path(p).expanduser() for p in CANDIDATE_ROOTS]

    root = next((r for r in roots if (r / "build-tools").is_dir()), None)
    if root is None:
        raise ToolError(
            "找不到 Android SDK。用 --sdk 指定，或设置 ANDROID_SDK_ROOT。"
            f" 已尝试: {', '.join(str(r) for r in roots)}"
        )

    bt_dirs = [d for d in (root / "build-tools").iterdir() if d.is_dir() and (d / "aapt2").exists()]
    if not bt_dirs:
        raise ToolError(f"{root}/build-tools 下没有可用的 build-tools（需要含 aapt2 的版本）")
    # 16KB 页对齐需要 build-tools 35+，优先选 35 以上的最高版本
    bt = max(bt_dirs, key=lambda d: _version_key(d.name))

    plat_dirs = [d for d in (root / "platforms").iterdir() if (d / "android.jar").exists()]
    if not plat_dirs:
        raise ToolError(f"{root}/platforms 下没有 android.jar")
    aj = max(plat_dirs, key=lambda d: _version_key(d.name.replace("android-", ""))) / "android.jar"

    apktool = shutil.which("apktool")
    if not apktool:
        raise ToolError("找不到 apktool（需要 2.7+，装法见 README）")
    keytool = shutil.which("keytool")
    if not keytool:
        java_home = os.environ.get("JAVA_HOME")
        cand = Path(java_home) / "bin" / "keytool" if java_home else None
        keytool = str(cand) if cand and cand.exists() else ""
        if not keytool:
            raise ToolError("找不到 keytool（装 JDK，或设置 JAVA_HOME）")

    return Sdk(root=root, build_tools=bt, android_jar=aj, apktool=apktool, keytool=keytool)


def run(cmd: Sequence, cwd: Optional[Path] = None, check: bool = True, timeout: int = 3600) -> subprocess.CompletedProcess:
    args = [str(c) for c in cmd]
    try:
        cp = subprocess.run(args, cwd=str(cwd) if cwd else None,
                            capture_output=True, text=True, timeout=timeout)
    except FileNotFoundError as exc:
        raise ToolError(f"命令不存在: {args[0]}") from exc
    except subprocess.TimeoutExpired as exc:
        raise ToolError(f"命令超时({timeout}s): {' '.join(args[:3])} …") from exc
    if check and cp.returncode != 0:
        tail = (cp.stderr or cp.stdout or "").strip()
        tail = "\n".join(tail.splitlines()[-15:])
        raise ToolError(
            f"{Path(args[0]).name} 执行失败（exit={cp.returncode}）\n"
            f"命令: {' '.join(args[:6])}{' …' if len(args) > 6 else ''}\n{tail}"
        )
    return cp


def check_tools(sdk: Sdk) -> List[str]:
    """doctor：逐项自检，返回可直接打印的报告行。"""
    lines = []
    lines.append(f"SDK root      : {sdk.root}")
    lines.append(f"build-tools   : {sdk.build_tools.name}")
    lines.append(f"android.jar   : {sdk.android_jar}")
    lines.append(f"apktool       : {sdk.apktool}  ({run([sdk.apktool, '--version'], check=False).stdout.strip()})")
    for name, path in (("aapt2", sdk.aapt2), ("zipalign", sdk.zipalign), ("apksigner", sdk.apksigner)):
        lines.append(f"{name:<13} : {path}")
    lines.append(f"16KB page align: {'支持 (-P)' if sdk.zipalign_supports_page_align() else '不支持（将退回 -p 4）'}")
    adb = sdk.adb
    lines.append(f"adb           : {adb or '未安装（不影响出包）'}")
    return lines
