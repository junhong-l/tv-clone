"""克隆流水线：反编译 → 加固自检 → 重写 → 回编译 → 对齐 → 签名 → 校验。"""
from __future__ import annotations

import json
import re
import secrets
import shutil
import time
import zipfile
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Callable, Dict, List, Optional

from . import rewrite as rw
from .apkmeta import ApkMeta, find_app_class_file, inspect_apk
from .sdk import Sdk, ToolError, run


class CloneError(RuntimeError):
    pass


@dataclass
class Keystore:
    path: Path
    alias: str
    store_pass: str
    key_pass: str


@dataclass
class CloneResult:
    src: str
    out: str
    old_pkg: str
    new_pkg: str
    label: str
    ok: bool = False
    seconds: float = 0.0
    steps: Dict[str, float] = field(default_factory=dict)
    warnings: List[str] = field(default_factory=list)
    checks: Dict[str, str] = field(default_factory=dict)

    def to_json(self) -> str:
        return json.dumps(asdict(self), ensure_ascii=False, indent=2)


# --------------------------------------------------------------------------- #
# 签名
# --------------------------------------------------------------------------- #
def ensure_keystore(sdk: Sdk, path: Path, alias: str = "tvclone") -> Keystore:
    """没有就生成一个；口令落在同名 .properties 里（不进日志、不打印）。"""
    path = Path(path)
    props = path.with_suffix(path.suffix + ".properties")
    if path.exists() and props.exists():
        data = dict(
            line.split("=", 1) for line in props.read_text("utf-8").splitlines() if "=" in line
        )
        return Keystore(path, data.get("alias", alias), data["storePass"], data["keyPass"])

    path.parent.mkdir(parents=True, exist_ok=True)
    store_pass = secrets.token_urlsafe(18)
    run([sdk.keytool, "-genkeypair", "-keystore", str(path), "-alias", alias,
         "-keyalg", "RSA", "-keysize", "2048", "-validity", "12000",
         "-storepass", store_pass, "-keypass", store_pass,
         "-dname", "CN=TVClone, O=TVClone, C=CN"])
    props.write_text(f"alias={alias}\nstorePass={store_pass}\nkeyPass={store_pass}\n", "utf-8")
    props.chmod(0o600)
    return Keystore(path, alias, store_pass, store_pass)


def keystore_fingerprint(sdk: Sdk, ks: Keystore) -> str:
    cp = run([sdk.keytool, "-list", "-v", "-keystore", str(ks.path), "-alias", ks.alias,
              "-storepass", ks.store_pass], check=False)
    m = re.search(r"SHA256:\s*([0-9A-F:]+)", cp.stdout)
    return m.group(1) if m else "未知"


# --------------------------------------------------------------------------- #
# 主流程
# --------------------------------------------------------------------------- #
def _apktool_supports(sdk: Sdk, flag: str) -> bool:
    cp = run([sdk.apktool, "b", "--help"], check=False)
    return flag in (cp.stdout + cp.stderr)


def clone(
    sdk: Sdk,
    apk: Path,
    suffix: str = ".clone1",
    out_dir: Path = Path("out"),
    ks: Optional[Keystore] = None,
    label_suffix: str = " ②",
    rewrite_res: bool = True,
    rewrite_assets: bool = False,
    keep_work: bool = False,
    force: bool = False,
    log: Callable[[str], None] = print,
) -> CloneResult:
    apk = Path(apk).expanduser().resolve()
    out_dir = Path(out_dir).expanduser().resolve()
    out_dir.mkdir(parents=True, exist_ok=True)

    t_all = time.time()
    meta: ApkMeta = inspect_apk(sdk, apk)
    if meta.is_split:
        raise CloneError(
            f"{apk.name} 是 split APK（split='{meta.split}'），克隆必须用 base.apk；"
            "分卷包请先合并成单一 APK。"
        )
    if not suffix or not re.fullmatch(r"[.A-Za-z0-9_]+", suffix):
        raise CloneError(f"非法后缀: {suffix!r}（只允许字母数字点下划线）")
    if meta.dex_count == 0:
        raise CloneError(
            f"{apk.name} 里没有 classes.dex（资源包或未打包完的中间产物），无法克隆。"
        )

    new_pkg = meta.package + suffix
    new_label = (meta.label or meta.package.split(".")[-1]) + (label_suffix or "")
    result = CloneResult(src=str(apk), out="", old_pkg=meta.package,
                         new_pkg=new_pkg, label=new_label)
    log(f"源包 : {meta.summary()}")
    log(f"克隆 : {new_pkg}「{new_label}」")

    # 资源里引用了包名的情况先统计，用于报告
    res_hits = rw.scan_res_strings(sdk, apk, meta.package)

    work = out_dir / f".work_{new_pkg}"
    if work.exists():
        shutil.rmtree(work)
    dec = work / "dec"

    # 1) 反编译（必须解码资源：apktool 的 -r 模式不解码二进制 manifest）
    t = time.time()
    log("[1/8] 反编译（解码 manifest / 资源 / dex）…")
    run([sdk.apktool, "d", "-f", "-o", str(dec), str(apk)])
    result.steps["decode"] = time.time() - t

    # 2) 加固自检：类不在 smali 里 = 壳，重签名必闪退
    if meta.app_class and not find_app_class_file(dec, meta.app_class):
        msg = (f"加固包：{meta.app_class} 不在 smali 中（入口由 native 外壳解密）。"
               "重签名后必然闪退，B 方案不适用，请改用多用户方案或原包直装。")
        if not force:
            raise CloneError(msg + "\n（确认要试可加 --force；失败率极高）")
        result.warnings.append(msg)
    if meta.app_component_factory and "shell" in meta.app_component_factory.lower():
        result.warnings.append(f"检测到 appComponentFactory={meta.app_component_factory}，疑似加固壳")
    if meta.has_shared_user:
        result.warnings.append(f"已移除 android:sharedUserId={meta.shared_user}（不移除必装不上）")

    # 3) 重写 manifest
    t = time.time()
    mf = dec / "AndroidManifest.xml"
    text = mf.read_text("utf-8")
    text, rep = rw.rewrite_manifest(text, meta.package, new_pkg)
    text = rw.apply_label(text, new_label)
    mf.write_text(text, "utf-8")
    result.steps["manifest"] = time.time() - t
    log(f"[2/8] manifest 改写 {len(rep.manifest_changes)} 处")

    # 4) 重写 smali
    t = time.time()
    sm = rw.rename_smali(dec, meta.package, new_pkg)
    result.steps["smali"] = time.time() - t
    log(f"[3/8] smali 改写 {sm.smali_files} 个文件 / 搬迁 {sm.moved_dirs} 个包目录")
    if sm.moved_dirs == 0:
        result.warnings.append(f"没找到 {meta.package.replace('.', '/')} 的 smali 目录，包名可能只存在于资源里")

    # 5) 资源 / assets
    t = time.time()
    if rewrite_res:
        rw.rewrite_res(dec, meta.package, new_pkg, sm)
        log(f"[4/8] 资源改写 {sm.res_changes} 处（arsc 中包名引用 {res_hits} 处）")
    else:
        log("[4/8] 资源文本未替换（--no-res-rewrite）")
    if rewrite_assets and not rewrite_res:
        rw.rewrite_res(dec, meta.package, new_pkg, sm)
        log(f"[4/8] assets 改写 {sm.asset_changes} 处")
    result.steps["res"] = time.time() - t

    # 6) 回编译
    t = time.time()
    unsigned = work / "unsigned.apk"
    log("[5/8] 回编译 …")
    build_cmd = [sdk.apktool, "b", str(dec), "-o", str(unsigned)]
    if _apktool_supports(sdk, "--use-aapt2"):
        build_cmd.append("--use-aapt2")
    try:
        run(build_cmd)
    except ToolError:
        result.warnings.append("aapt2 回编译失败，退回 apktool 默认 aapt 重试一次")
        run(build_cmd[:-1] if build_cmd[-1] == "--use-aapt2" else build_cmd)
    result.steps["build"] = time.time() - t

    # 7) 对齐 + 签名
    t = time.time()
    aligned = work / "aligned.apk"
    if sdk.zipalign_supports_page_align():
        run([sdk.zipalign, "-f", "-P", "16", "4", str(unsigned), str(aligned)])
    else:
        run([sdk.zipalign, "-f", "-p", "4", str(unsigned), str(aligned)])
    out = out_dir / f"{new_pkg}.apk"
    if out.exists():
        out.unlink()
    ks = ks or ensure_keystore(sdk, out_dir.parent / "keystore" / "tvclone.jks")
    run([sdk.apksigner, "sign", "--ks", str(ks.path), "--ks-key-alias", ks.alias,
         "--ks-pass", f"pass:{ks.store_pass}", "--key-pass", f"pass:{ks.key_pass}",
         "--min-sdk-version", str(meta.min_sdk),
         "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
         "--v3-signing-enabled", "true", "--v4-signing-enabled", "false",
         "--out", str(out), str(aligned)])
    result.steps["sign"] = time.time() - t
    log(f"[6/8] 已签名 -> {out.name}")

    # 8) 校验
    t = time.time()
    checks = verify(sdk, out, expect_pkg=new_pkg, old_pkg=meta.package)
    ids_src = resource_ids(sdk, apk)
    ids_out = resource_ids(sdk, out)
    checks["res_ids"] = (f"OK({len(ids_out)})" if ids_src == ids_out
                         else f"DIFF({len(ids_src)}→{len(ids_out)})")
    result.checks = checks
    result.steps["verify"] = time.time() - t
    log("[7/8] " + " / ".join(f"{k}:{v}" for k, v in checks.items()))

    if not keep_work:
        shutil.rmtree(work, ignore_errors=True)
    result.out = str(out)
    result.ok = checks.get("package") == "OK" and checks.get("signature") == "OK"
    result.seconds = time.time() - t_all
    (out_dir / f"{new_pkg}.json").write_text(result.to_json(), "utf-8")
    log(f"[8/8] 完成 {result.seconds:.1f}s  ->  {out}")
    return result


def resource_ids(sdk: Sdk, apk: Path) -> List[str]:
    """资源 ID 集合，用来确认回编译没让资源 ID 漂移。"""
    out = run([sdk.aapt2, "dump", "resources", apk], check=False).stdout
    return sorted(set(re.findall(r"resource 0x7f[0-9a-f]+", out)))


def verify(sdk: Sdk, apk: Path, expect_pkg: Optional[str] = None,
           old_pkg: Optional[str] = None) -> Dict[str, str]:
    apk = Path(apk)
    checks: Dict[str, str] = {}

    cp = run([sdk.aapt2, "dump", "badging", apk], check=False)
    m = re.search(r"^package: name='([^']+)'", cp.stdout, re.M)
    got = m.group(1) if m else "?"
    checks["package"] = "OK" if (expect_pkg is None or got == expect_pkg) else f"FAIL({got})"

    cp = run([sdk.apksigner, "verify", "--verbose", "--print-certs", apk], check=False)
    checks["signature"] = "OK" if cp.returncode == 0 and "Verified using v2 scheme" in cp.stdout else "FAIL"
    m = re.search(r"SHA-256 digest: ([0-9a-f]+)", cp.stdout)
    checks["cert"] = (m.group(1)[:16] + "…") if m else "?"

    cp = run([sdk.zipalign, "-c", "-P", "16", "4", apk], check=False)
    if cp.returncode != 0:
        cp = run([sdk.zipalign, "-c", "4", apk], check=False)
    checks["zipalign"] = "OK" if cp.returncode == 0 else "FAIL"

    tree = run([sdk.aapt2, "dump", "xmltree", "--file", "AndroidManifest.xml", apk], check=False).stdout
    if old_pkg:
        scrub = re.sub(re.escape(old_pkg) + r"\.clone\d+", "", tree)
        leaks = len(re.findall(r"(?<![\w.])" + re.escape(old_pkg) + r"(?![\w])", scrub))
        checks["manifest_leak"] = "OK" if leaks == 0 else f"FAIL({leaks})"

        # dex 里的类描述符：Lcom/old/pkg/ 后面必须紧跟 cloneN/
        suffix = (expect_pkg[len(old_pkg):] if expect_pkg and expect_pkg.startswith(old_pkg) else "").replace(".", "/").strip("/")
        needle = ("L" + old_pkg.replace(".", "/") + "/").encode()
        ok_needle = (suffix + "/").encode() if suffix else None
        dex_leaks = 0
        with zipfile.ZipFile(apk) as zf:
            for name in zf.namelist():
                if not re.fullmatch(r"classes\d*\.dex", name):
                    continue
                data = zf.read(name)
                start = 0
                while True:
                    idx = data.find(needle, start)
                    if idx < 0:
                        break
                    after = data[idx + len(needle): idx + len(needle) + len(ok_needle or b"")]
                    if ok_needle is None or after != ok_needle:
                        dex_leaks += 1
                    start = idx + 1
        checks["dex_leak"] = "OK" if dex_leaks == 0 else f"FAIL({dex_leaks})"

    m = re.search(r"^leanback-launchable-activity: name='([^']+)'", run(
        [sdk.aapt2, "dump", "badging", apk], check=False).stdout, re.M)
    checks["leanback"] = "OK" if m else "无"
    auth = re.findall(r'android:authorities\([^)]*\)="([^"]+)"', tree)
    checks["authorities"] = str(sum(len(a.split(";")) for a in auth)) + " 条"
    return checks


# --------------------------------------------------------------------------- #
# 批量
# --------------------------------------------------------------------------- #
def collect_apks(target: Path) -> List[Path]:
    target = Path(target).expanduser()
    if target.is_file():
        return [target]
    if target.is_dir():
        return sorted(
            p for p in target.rglob("*.apk")
            if p.is_file() and "build" not in p.parts and not p.name.endswith(".idsig")
        )
    raise CloneError(f"路径不存在: {target}")
