'use strict';
/**
 * 路径解析 —— 便携模式的单点真相。
 *
 * 两种发行形态，数据目录不同：
 *   · 便携版（zip，解压即用）：<exe 所在目录>/data
 *   · 安装版（NSIS 安装包）：%APPDATA%/niuren/data
 *     判别依据：包内 resources/installed.flag（只有安装版会带这个文件）
 *
 * 优先级：
 *   1. 安装版：%APPDATA%/niuren            （卸载/重装不丢配置，Program Files 也没权限问题）
 *   2. 便携版且目录可写：<exe 所在目录>/data （绿色、可整目录拷走、U 盘可跑）
 *   3. 打包后但目录只读：%APPDATA%/Polaris （U 盘只读等降级场景）
 *   4. 开发态：<app>/.devdata
 *
 * 本模块刻意不强依赖 electron：core/ 与 util/ 下的逻辑要能在纯 Node 下跑自检，
 * 所以 require 失败时退到一个基于 __dirname 的开发态根目录。
 */

/** 安装版标记：`<exe 目录>/resources/installed.flag`，只有 NSIS 安装包会带 */
const INSTALLED_FLAG_REL = ['resources', 'installed.flag'];
/** 安装版的数据目录名（挂在 app.getPath('appData') 下） */
const INSTALLED_DATA_DIR = 'niuren';

const fs = require('fs');
const path = require('path');

let electronApp = null;
try {
  const mod = require('electron');
  electronApp = mod && mod.app ? mod.app : null;
} catch (_) {
  electronApp = null;
}

const IS_ELECTRON = !!electronApp;
const IS_PACKAGED = IS_ELECTRON ? electronApp.isPackaged : false;

function canWrite(dir) {
  try {
    fs.mkdirSync(dir, { recursive: true });
    fs.accessSync(dir, fs.constants.W_OK);
    return true;
  } catch (_) {
    return false;
  }
}

let cachedRoot = null;
let portable = false;
let installWritableCache = null;

/** 打包后的 exe 所在目录（安装版 = 安装目录根，便携版 = 解压目录根） */
function exeDir() {
  return IS_PACKAGED ? path.dirname(electronApp.getPath('exe')) : '';
}

/** 是不是安装版（包里带 installed.flag） */
function isInstalled() {
  if (!IS_PACKAGED) return false;
  try { return fs.existsSync(path.join(exeDir(), ...INSTALLED_FLAG_REL)); } catch (_) { return false; }
}

/** 安装目录能否写入 —— 自更新的前提（便携版与安装版都按这个判，不再复用 portable） */
function installWritable() {
  if (installWritableCache !== null) return installWritableCache;
  if (!IS_PACKAGED) { installWritableCache = false; return false; }
  const probe = path.join(exeDir(), '.polaris-write-probe');
  installWritableCache = canWrite(probe);
  try { fs.rmSync(probe, { recursive: true, force: true }); } catch (_) {}
  return installWritableCache;
}

function root() {
  if (cachedRoot) return cachedRoot;

  // 逃生口：POLARIS_DATA_DIR 可以把数据目录指到别处。
  // 自检要拿真实用户的会话跑、但又不能动他那份 data 时靠它；
  // 正常用户不设这个变量，行为与以前完全一样。
  const forced = (process.env.POLARIS_DATA_DIR || '').trim();
  if (forced) {
    if (canWrite(path.join(forced, '.polaris-write-probe'))) {
      try { fs.rmSync(path.join(forced, '.polaris-write-probe'), { recursive: true, force: true }); } catch (_) {}
      cachedRoot = forced;
      portable = IS_PACKAGED;
      return cachedRoot;
    }
  }

  if (IS_PACKAGED) {
    // 安装版：数据一律落在 %APPDATA%/niuren，跟安装目录解耦
    if (isInstalled()) {
      cachedRoot = path.join(electronApp.getPath('appData'), INSTALLED_DATA_DIR);
      portable = false;
      return cachedRoot;
    }
    const exeDirPath = exeDir();
    const probe = path.join(exeDirPath, '.polaris-write-probe');
    if (canWrite(probe)) {
      try { fs.rmSync(probe, { recursive: true, force: true }); } catch (_) {}
      cachedRoot = exeDirPath;
      portable = true;
    } else {
      cachedRoot = path.join(electronApp.getPath('appData'), 'Polaris');
      portable = false;
    }
  } else {
    // 开发态 / 纯 Node 自检：都落在 app 目录下的 .devdata
    const appDir = IS_ELECTRON ? electronApp.getAppPath() : path.resolve(__dirname, '..');
    cachedRoot = path.join(appDir, '.devdata');
    portable = false;
  }
  return cachedRoot;
}

const paths = {
  root,
  isElectron: IS_ELECTRON,
  isPackaged: IS_PACKAGED,
  isPortable: () => portable,
  /** 安装版（NSIS 装出来的）：数据在 %APPDATA%/niuren */
  isInstalled,
  /** 安装目录（也就是 exe 所在目录）能不能写 —— 自更新按这个判，与数据目录无关 */
  installWritable,
  data: () => path.join(root(), 'data'),
  logs: () => path.join(root(), 'data', 'logs'),
  profiles: () => path.join(root(), 'data', 'profiles'),
  providers: () => path.join(root(), 'data', 'providers'),
  /** 内置分流规则种子（resources/rules/*.yaml，随包分发，离线可用） */
  rules: () => (IS_PACKAGED
    ? path.join(process.resourcesPath, 'rules')
    : path.resolve(__dirname, '..', 'resources', 'rules')),
  /**
   * 内核实际读写规则集的目录：必须在 data/ 内，否则 mihomo 会以「不安全路径」拒载。
   * 目录名与 rule-provider 的 path 前缀（rulesets.CACHE_DIR = 'polaris-rules'）必须一致 ——
   * mihomo 把 path 解析成 <profileDir>/<path>，profileDir 就是 `-d` 指的 data/。
   */
  polarisRules: () => path.join(root(), 'data', 'polaris-rules'),
  /** 自动更新的下载与解压暂存区 */
  updateDir: () => path.join(root(), 'data', 'update'),
  cache: () => path.join(root(), 'data', 'cache'),
  file: (...rel) => path.join(root(), 'data', ...rel),
  core: () => (IS_PACKAGED
    ? path.join(process.resourcesPath, 'core')
    : path.resolve(__dirname, '..', 'core')),
  /** 规则库（geoip.metadb / geosite.dat / ASN.mmdb），随包分发 */
  geo: () => (IS_PACKAGED
    ? path.join(process.resourcesPath, 'geo')
    : path.resolve(__dirname, '..', 'resources', 'geo')),
  ensureAll() {
    for (const d of [paths.data(), paths.logs(), paths.profiles(), paths.providers(), paths.polarisRules(), paths.cache(), paths.updateDir()]) {
      fs.mkdirSync(d, { recursive: true });
    }
    return paths;
  },
};

module.exports = paths;
