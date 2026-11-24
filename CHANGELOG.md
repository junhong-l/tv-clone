# Changelog

## v2.0.4 — 2026-11-24

- 焦点态重做：未选中为深色底浅色字，选中为近白底 + 3dp 蓝色描边 + 深色字。
  之前两态都是蓝色，电视上无法分辨焦点位置。
- 整行焦点同步加强（底色提亮 + 3dp 蓝边）。

## v2.0.3 — 2026-11-24

- 修复：克隆包交给系统安装器时路径错误（包写在 `cacheDir/tvdual_work/`，
  Provider 只从 `cacheDir/` 根目录取），安装器读不到文件，报「解析软件包时出现问题」。

## v2.0.2 — 2026-11-24

- 安装方式改为标准 `ACTION_VIEW` / `ACTION_INSTALL_PACKAGE` + 内置 ContentProvider，
  仅在没有可用安装器时退回 `PackageInstaller` 会话。
  （部分电视盒子的 PackageInstaller 会 commit 成功但既不弹界面也不回状态。）

## v2.0.1 — 2026-11-24

- 修复：`PendingIntent` 使用 `FLAG_IMMUTABLE` 导致系统无法回填安装状态，回调恒为 `code=-1`。
- 增加兜底：拿不到状态时直接查询包是否已安装。
- 安装失败码翻译成可读原因。

## v2.0.0 — 2026-11-24

- **端上重打包**：不再需要电脑。在电视上完成
  改二进制清单 → 改 `resources.arsc` 包名 → 流式重打包 → v2 签名 → 安装。
- 新增纯 Java 核心：`Axml`（AXML 改写）、`Arsc`（包名替换）、
  `ApkZip`（流式重打包）、`V2Signer`（自实现 APK Signature Scheme v2）。
- 主界面改为「已安装应用」列表，支持双开 / 启动 / 卸载。

## v1.0.0 — 2026-11-24

- 首版：扫描 U 盘与下载目录里的 APK 并安装，按原版/克隆配对管理已装应用。
- 配套电脑端 CLI（`pc/`）：apktool 重打包克隆、校验、批量出包。
