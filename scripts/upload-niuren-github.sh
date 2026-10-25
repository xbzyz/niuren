#!/usr/bin/env bash
#
# upload-niuren-github.sh
# ---------------------------------------------------------------------------
# 一键把「牛人 niuren」上传到 GitHub：
#   1) 推送源码到 github.com/xbzyz/niuren  (分支 main)
#   2) 创建 github.com/xbzyz/niuren-rules  (public) 并填充种子规则 + 地理库 + latest.json
#   3) 在 xbzyz/niuren 创建 Release v1.0.0 并上传三端生产产物
#
# 前置条件（在本机执行，需要有 github.com 网络访问）：
#   - 已安装 gh CLI（mac: brew install gh；或 https://cli.github.com）
#   - 已登录 / 持有 PAT：
#         export GH_TOKEN=ghp_xxx            # 需要 repo + (建库用) create_repo 权限
#         gh auth status                     # 确认已登录 xbzyz
#   - 在仓库根目录 polaris/ 下运行：bash scripts/upload-niuren-github.sh
#
# 说明：本脚本只做「上传」，不影响已生成的 dist/ 产物与本地提交。
# ---------------------------------------------------------------------------
set -euo pipefail

REPO_OWNER="xbzyz"
SOURCE_REPO="niuren"
RULES_REPO="niuren-rules"
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
RULES_SRC="$REPO_ROOT/design/windows/app/resources"

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

# ---- 1. 推送源码 ----
log "[1/4] 推送源码到 $REPO_OWNER/$SOURCE_REPO (main)"
git remote set-url origin "https://github.com/$REPO_OWNER/$SOURCE_REPO.git" 2>/dev/null \
  || git remote add origin "https://github.com/$REPO_OWNER/$SOURCE_REPO.git"
git push -u origin main

# ---- 2. 创建规则仓库 niuren-rules ----
log "[2/4] 创建 $REPO_OWNER/$RULES_REPO (public, 若已存在则跳过)"
if ! gh repo view "$REPO_OWNER/$RULES_REPO" >/dev/null 2>&1; then
  gh repo create "$RULES_REPO" --owner "$REPO_OWNER" --public \
    --description "牛人 (niuren) 分流规则 + 地理库统一来源仓库：随仓库更新，每 15 分钟同步上游 ACL4SSR + MetaCubeX。" \
    --homepage "https://github.com/$REPO_OWNER/$SOURCE_REPO" --confirm
else
  echo "  仓库已存在，跳过创建。"
fi

# ---- 3. 填充规则仓库种子 ----
log "[3/4] 填充 $RULES_REPO 种子（规则清单 + 地理库 + latest.json）"
TMP="$(mktemp -d)"
git clone "https://github.com/$REPO_OWNER/$RULES_REPO.git" "$TMP/rules" >/dev/null 2>&1 || true
cd "$TMP/rules"
mkdir -p dist/rules geo

# 规则：resources/rules/*.yaml -> dist/rules/<名>.list
for f in "$RULES_SRC"/rules/*.yaml; do
  [ -e "$f" ] || continue
  name="$(basename "$f" .yaml)"
  cp "$f" "dist/rules/$name.list"
done
# 地理库
cp "$RULES_SRC"/geo/* geo/ 2>/dev/null || true

# 生成 latest.json（与 App 的 rulesync.js schema=1 契约一致）
python3 - <<PY
import hashlib, json, os, datetime
files=[]
for root,_,fs in os.walk("."):
    if root.startswith("./.git"): continue
    for fn in fs:
        p=os.path.join(root,fn)
        rel=os.path.relpath(p,".").replace(os.sep,"/")
        if rel in ("latest.json",".git"): continue
        b=open(p,"rb").read()
        files.append({"path":rel,"bytes":len(b),"sha256":hashlib.sha256(b).hexdigest()})
files.sort(key=lambda x:x["path"])
doc={
  "schema":1,
  "version":"$VERSION",
  "generatedAt":datetime.datetime.now(datetime.timezone.utc).isoformat(),
  "upstream":{
    "acl":{"repo":"ACL4SSR/ACL4SSR","sha":"seed","date":"seed"},
    "meta":{"repo":"MetaCubeX/meta-rules-dat","tag":"seed","publishedAt":"seed"}
  },
  "files":files,
}
open("latest.json","w").write(json.dumps(doc,indent=2,ensure_ascii=False))
print("latest.json 条目数:",len(files))
PY

git add -A
git -c user.email="niuren@users.noreply.github.com" -c user.name="niuren" \
  commit -m "seed: niuren-rules v$VERSION (rules + geo + latest.json)" || echo "  无变更，跳过提交"
git push -u origin main || echo "  推送失败（可能需手动处理）"
cd "$REPO_ROOT"

# ---- 4. 创建 Release 并上传产物 ----
log "[4/4] 在 $REPO_OWNER/$SOURCE_REPO 创建 Release v$VERSION 并上传产物"
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

规则与地理库统一来源：github.com/$REPO_OWNER/$RULES_REPO" \
  --latest

log "完成 ✅  Release: https://github.com/$REPO_OWNER/$SOURCE_REPO/releases/tag/v$VERSION"
