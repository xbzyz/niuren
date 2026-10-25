#!/usr/bin/env bash
#
# upload-niuren-github.sh
# ---------------------------------------------------------------------------
# 一键把「牛人 niuren」上传到 GitHub（单仓库方案：规则/地理库已随源码入库）。
#   1) 推送源码到 github.com/xbzyz/niuren  (分支 main)
#   2) 在 xbzyz/niuren 创建 Release v1.0.0 并上传三端生产产物
#
# 前置条件（在本机执行，需要有 github.com 网络访问）：
#   - 已安装 gh CLI（mac: brew install gh；或 https://cli.github.com）
#   - 已持有对 xbzyz/niuren 有「写」权限的令牌：
#         export GH_TOKEN=ghp_xxx            # 经典 PAT 勾选 repo 作用域
#         gh auth status                     # 确认已登录 xbzyz
#   - 在仓库根目录 polaris/ 下运行：bash scripts/upload-niuren-github.sh
#
# 说明：本脚本只做「上传」，不影响已生成的 dist/ 产物与本地提交。
# ---------------------------------------------------------------------------
set -euo pipefail

REPO_OWNER="xbzyz"
SOURCE_REPO="niuren"
VERSION="1.0.0"

# 脚本位于 polaris/scripts/，父目录即仓库根（git 仓库）
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# dist/ 可能在仓库内，也可能在 workspace 根（与 polaris 同级）；自动探测
if [ -d "$REPO_ROOT/dist" ]; then
  DIST_DIR="$REPO_ROOT/dist"
elif [ -d "$(dirname "$REPO_ROOT")/dist" ]; then
  DIST_DIR="$(dirname "$REPO_ROOT")/dist"
else
  echo "错误：找不到 dist/ 目录（已查 $REPO_ROOT/dist 与 $(dirname "$REPO_ROOT")/dist）" >&2
  exit 1
fi

log() { echo -e "\n\033[1;32m==> $*\033[0m"; }

# ---- 0. 环境检查 ----
log "环境检查"
if ! command -v gh >/dev/null 2>&1; then
  echo "错误：未找到 gh CLI，请先安装 https://cli.github.com" >&2
  exit 1
fi
if ! gh auth status >/dev/null 2>&1; then
  echo "错误：gh 未登录。请先 'export GH_TOKEN=...' 或 'gh auth login'。" >&2
  exit 1
fi
gh auth status 2>/dev/null | head -3

cd "$REPO_ROOT"

# ---- 1. 推送源码（含 dist/rules、geo、latest.json） ----
log "[1/2] 推送源码到 $REPO_OWNER/$SOURCE_REPO (main)"
git remote set-url origin "https://github.com/$REPO_OWNER/$SOURCE_REPO.git" 2>/dev/null \
  || git remote add origin "https://github.com/$REPO_OWNER/$SOURCE_REPO.git"
git push -u origin main

# ---- 2. 创建 Release 并上传产物 ----
log "[2/2] 在 $REPO_OWNER/$SOURCE_REPO 创建 Release v$VERSION 并上传产物"
gh release create "v$VERSION" \
  "$DIST_DIR/apk/niuren-$VERSION.apk" \
  "$DIST_DIR/windows/niuren-setup-$VERSION.exe" \
  "$DIST_DIR/windows/niuren-portable-$VERSION.zip" \
  "$DIST_DIR/mac/niuren-mac-$VERSION.zip" \
  "$DIST_DIR/mac/niuren-mac-$VERSION.pkg" \
  --title "牛人 niuren v$VERSION" \
  --notes "牛人 v$VERSION 生产版本。

- Windows：niuren-setup-$VERSION.exe（安装版）/ niuren-portable-$VERSION.zip（便携版）
- macOS：niuren-mac-$VERSION.pkg（PKG 安装）/ niuren-mac-$VERSION.zip（免安装）
- Android：niuren-$VERSION.apk

规则与地理库随源码同仓：github.com/$REPO_OWNER/$SOURCE_REPO（dist/rules/ + geo/ + latest.json）" \
  --latest

log "完成 ✅  Release: https://github.com/$REPO_OWNER/$SOURCE_REPO/releases/tag/v$VERSION"
