"""tvclone 命令行入口。"""
from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

from . import engine
from .apkmeta import inspect_apk
from .engine import CloneError, clone, collect_apks, ensure_keystore, keystore_fingerprint, verify
from .sdk import Sdk, ToolError, check_tools, find_sdk

CIRCLED = "①②③④⑤⑥⑦⑧⑨"


def _log(msg: str) -> None:
    print(msg, flush=True)


def _add_common(p: argparse.ArgumentParser) -> None:
    p.add_argument("--sdk", help="Android SDK 根目录（默认自动探测 /opt/android-sdk）")
    p.add_argument("-o", "--out", default="out", help="输出目录（默认 ./out）")
    p.add_argument("--ks", help="签名 keystore（默认 out/../keystore/tvclone.jks，缺失自动生成）")
    p.add_argument("--label-suffix", default=None,
                   help="桌面名后缀，默认按序号给 ②③…；传空串则不加")
    p.add_argument("--no-res-rewrite", dest="no_res_rewrite", action="store_true",
                   help="不替换 res/ 文本中的包名（资源仍会重新编译）")
    p.add_argument("--rewrite-assets", action="store_true", help="同时替换 assets 里的文本包名")
    p.add_argument("--keep-work", action="store_true", help="保留中间目录便于排查")
    p.add_argument("--force", action="store_true", help="加固包也强行尝试")


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="tvclone",
        description="电视端应用双开 · B 方案（改包名重打包克隆）",
    )
    sub = p.add_subparsers(dest="cmd", required=True)

    d = sub.add_parser("doctor", help="检查本机工具链")
    d.add_argument("--sdk")

    i = sub.add_parser("inspect", help="查看 APK 信息与克隆可行性")
    i.add_argument("apks", nargs="+")
    i.add_argument("--sdk")

    c = sub.add_parser("clone", help="克隆单个 APK")
    c.add_argument("apk")
    c.add_argument("-n", "--count", type=int, default=1, help="生成几份克隆（默认 1）")
    c.add_argument("--suffix", help="自定义包名后缀（默认 .clone1/.clone2…）")
    _add_common(c)

    b = sub.add_parser("batch", help="批量克隆目录下所有 APK")
    b.add_argument("target", help="APK 文件或目录（递归找 *.apk）")
    b.add_argument("-n", "--count", type=int, default=1)
    _add_common(b)

    v = sub.add_parser("verify", help="校验一个 APK（含克隆包）")
    v.add_argument("apk")
    v.add_argument("--expect-pkg")
    v.add_argument("--old-pkg", help="检查 manifest 里是否还残留旧包名")
    v.add_argument("--sdk")

    ins = sub.add_parser("install", help="adb 安装到电视")
    ins.add_argument("apks", nargs="+")
    ins.add_argument("--serial")
    ins.add_argument("--sdk")

    ls = sub.add_parser("list", help="列出电视上已装的原版/克隆配对")
    ls.add_argument("--serial")
    ls.add_argument("--sdk")

    cl = sub.add_parser("clean", help="清理 out/ 下的中间目录")
    cl.add_argument("-o", "--out", default="out")
    return p


def _label_for(index: int, count: int, explicit) -> str:
    if explicit is not None:
        return explicit
    return " " + (CIRCLED[index] if 0 <= index < len(CIRCLED) else f"({index + 1})")


def cmd_doctor(args) -> int:
    sdk = find_sdk(args.sdk)
    for line in check_tools(sdk):
        print(line)
    return 0


def cmd_inspect(args) -> int:
    sdk = find_sdk(args.sdk)
    rc = 0
    for apk in args.apks:
        m = inspect_apk(sdk, Path(apk))
        print(f"\n== {Path(apk).name}")
        print(f"   {m.summary()}")
        print(f"   leanback 入口: {m.leanback}   launchable: {m.launchable or '-'}")
        print(f"   Application : {m.app_class or '-'}")
        if m.has_shared_user:
            print(f"   ⚠ sharedUserId: {m.shared_user} —— 克隆时会移除")
        if m.is_split:
            print(f"   ✗ split APK（{m.split}），需先合并")
        hits = engine.rw.scan_res_strings(sdk, Path(apk), m.package)
        print(f"   resources.arsc 中包名引用: {hits} 处"
              f"{'（克隆时会一并替换）' if hits else ''}")
        if m.app_component_factory:
            print(f"   ⚠ appComponentFactory: {m.app_component_factory}")
    return rc


def _do_clone(sdk, apk, args, index, count, ks) -> engine.CloneResult:
    suffix = args.suffix if getattr(args, "suffix", None) else f".clone{index + 1}"
    return clone(
        sdk, Path(apk), suffix=suffix, out_dir=Path(args.out),
        ks=ks, label_suffix=_label_for(index, count, args.label_suffix),
        rewrite_res=not args.no_res_rewrite, rewrite_assets=args.rewrite_assets,
        keep_work=args.keep_work, force=args.force, log=_log,
    )


def cmd_clone(args) -> int:
    sdk = find_sdk(args.sdk)
    out_dir = Path(args.out)
    ks = ensure_keystore(sdk, Path(args.ks) if args.ks else Path("keystore/tvclone.jks"))
    print(f"签名证书 SHA-256: {keystore_fingerprint(sdk, ks)}  ({ks.path})")
    fails = 0
    for idx in range(max(1, args.count)):
        print(f"\n--- 克隆 #{idx + 1} ---")
        try:
            r = _do_clone(sdk, args.apk, args, idx, args.count, ks)
            for w in r.warnings:
                print(f"  ⚠ {w}")
        except (CloneError, ToolError) as exc:
            print(f"  ✗ {exc}")
            fails += 1
    return 1 if fails else 0


def cmd_batch(args) -> int:
    sdk = find_sdk(args.sdk)
    apks = collect_apks(Path(args.target))
    if not apks:
        print("没找到 APK")
        return 1
    ks = ensure_keystore(sdk, Path(args.ks) if args.ks else Path("keystore/tvclone.jks"))
    print(f"共 {len(apks)} 个 APK，每个出 {max(1, args.count)} 份克隆")
    rows, fails = [], 0
    for apk in apks:
        print(f"\n===== {apk.name} =====")
        for idx in range(max(1, args.count)):
            try:
                r = _do_clone(sdk, apk, args, idx, args.count, ks)
                rows.append((apk.name, r.new_pkg, "OK", f"{r.seconds:.1f}s", Path(r.out).name))
            except (CloneError, ToolError) as exc:
                fails += 1
                rows.append((apk.name, "-", "FAIL", "-", str(exc).splitlines()[0][:60]))
                print(f"  ✗ {exc}".rstrip())
                break
    print("\n== 汇总")
    print(f"{'源包':<34}{'克隆包名':<40}{'结果':<6}{'耗时':<8}说明")
    for row in rows:
        print(f"{row[0]:<34}{row[1]:<40}{row[2]:<6}{row[3]:<8}{row[4]}")
    print(f"\n成功 {len(rows) - fails} / 失败 {fails}")
    return 1 if fails else 0


def cmd_verify(args) -> int:
    sdk = find_sdk(args.sdk)
    checks = verify(sdk, Path(args.apk), expect_pkg=args.expect_pkg, old_pkg=args.old_pkg)
    for k, v in checks.items():
        print(f"{k:<16} {v}")
    bad = [k for k, v in checks.items() if str(v).startswith("FAIL")]
    return 1 if bad else 0


def cmd_install(args) -> int:
    sdk = find_sdk(args.sdk)
    if not sdk.adb:
        print("找不到 adb")
        return 1
    rc = 0
    for apk in args.apks:
        cmd = [sdk.adb] + (["-s", args.serial] if args.serial else []) + ["install", "-r", "-d", apk]
        from .sdk import run
        cp = run(cmd, check=False)
        ok = "Success" in cp.stdout
        print(f"{'✓' if ok else '✗'} {apk}: {(cp.stdout or cp.stderr).strip().splitlines()[-1] if (cp.stdout or cp.stderr).strip() else ''}")
        rc |= 0 if ok else 1
    return rc


def cmd_list(args) -> int:
    import re as _re
    sdk = find_sdk(args.sdk)
    cmd = [sdk.adb] + (["-s", args.serial] if args.serial else []) + ["shell", "pm", "list", "packages", "-3"]
    from .sdk import run
    cp = run(cmd, check=False)
    pkgs = [l.split(":", 1)[1].strip() for l in cp.stdout.splitlines() if l.startswith("package:")]
    groups = {}
    for p in pkgs:
        base = _re.sub(r"\.clone\d+$", "", p)
        groups.setdefault(base, []).append(p)
    for base, items in sorted(groups.items()):
        mark = "双开" if len(items) > 1 else "单份"
        print(f"[{mark}] {base}")
        for it in sorted(items):
            print(f"        {it}" + ("  (原版)" if it == base else "  (克隆)"))
    if not groups:
        print("设备上没有第三方应用，或 adb 未连接")
        return 1
    return 0


def cmd_clean(args) -> int:
    out = Path(args.out)
    n = 0
    for d in out.glob(".work_*"):
        shutil.rmtree(d, ignore_errors=True)
        n += 1
    print(f"清理 {n} 个中间目录")
    return 0


def main(argv=None) -> int:
    args = build_parser().parse_args(argv)
    handler = {
        "doctor": cmd_doctor, "inspect": cmd_inspect, "clone": cmd_clone,
        "batch": cmd_batch, "verify": cmd_verify, "install": cmd_install,
        "list": cmd_list, "clean": cmd_clean,
    }[args.cmd]
    try:
        return handler(args)
    except (ToolError, CloneError) as exc:
        print(f"错误: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
