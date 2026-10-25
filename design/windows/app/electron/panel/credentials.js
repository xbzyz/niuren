'use strict';
/**
 * 凭据存储：Windows 走 Electron safeStorage（DPAPI）加密后落 data/credentials.dat；
 * macOS **不走 safeStorage** —— 它的密钥存在登录钥匙串里，未公证的 ad-hoc 签名应用
 * 每次启动都会弹「要使用钥匙串中的机密信息」密码框，还会卡住主进程（拒绝即白屏）。
 * mac 上改用本地密钥文件 data/secret.key（0600，仅本用户可读）+ AES-256-GCM，
 * 安全边界与"便携目录里落加密数据、离开这台机器就解不开"的承诺一致。
 */

const fs = require('fs');
const crypto = require('crypto');
const paths = require('../paths');
const log = require('../logger');

let safeStorage = null;
try {
  const mod = require('electron');
  safeStorage = mod && mod.safeStorage ? mod.safeStorage : null;
} catch (_) {
  safeStorage = null;
}

const IS_MAC = process.platform === 'darwin';
const MAGIC = Buffer.from('NRKEY1\0', 'latin1').slice(0, 6); // 文件头：本地密钥加密的标记

let cache = null;

function file() { return paths.file('credentials.dat'); }

/* ---------- mac 本地密钥加密 ---------- */

let fileKey = null;

function macKey() {
  if (fileKey) return fileKey;
  try {
    const p = paths.file('secret.key');
    if (fs.existsSync(p)) {
      const k = fs.readFileSync(p);
      if (k.length === 32) { fileKey = k; return fileKey; }
    }
    fileKey = crypto.randomBytes(32);
    fs.writeFileSync(p, fileKey, { mode: 0o600 });
    try { fs.chmodSync(p, 0o600); } catch (_) {}
    return fileKey;
  } catch (e) {
    log.error('secret.key 读写失败：' + (e && e.message));
    return null;
  }
}

function macEncrypt(text) {
  const key = macKey();
  if (!key) throw new Error('本地密钥不可用');
  const iv = crypto.randomBytes(12);
  const c = crypto.createCipheriv('aes-256-gcm', key, iv);
  const ct = Buffer.concat([c.update(text, 'utf8'), c.final()]);
  return Buffer.concat([MAGIC, iv, c.getAuthTag(), ct]);
}

function macDecrypt(buf) {
  const key = macKey();
  if (!key) throw new Error('本地密钥不可用');
  if (buf.length < 34 || buf.slice(0, 6).toString('latin1') !== MAGIC.toString('latin1')) {
    throw new Error('不是本地密钥加密的文件');
  }
  const d = crypto.createDecipheriv('aes-256-gcm', key, buf.slice(6, 18));
  d.setAuthTag(buf.slice(18, 34));
  return Buffer.concat([d.update(buf.slice(34)), d.final()]).toString('utf8');
}

/* ---------- 对外 ---------- */

function available() {
  if (IS_MAC) return !!macKey();
  try { return !!(safeStorage && safeStorage.isEncryptionAvailable()); } catch (_) { return false; }
}

function load() {
  if (cache) return cache;
  try {
    const raw = fs.readFileSync(file());
    let text = null;
    if (raw.slice(0, 6).toString('latin1') === MAGIC.toString('latin1')) {
      // 本地密钥加密（mac 当前格式）：解不开（secret.key 丢了）就当作没有凭据
      try {
        text = macDecrypt(raw);
      } catch (e) {
        log.warn('credentials.dat 无法解密（secret.key 丢失？），需要重新登录：' + (e && e.message));
        text = null;
      }
    } else if (IS_MAC) {
      // mac 旧格式（早先版本的 safeStorage/钥匙串加密）：密钥在 mock 钥匙串里已不可得，重新登录
      log.warn('credentials.dat 是旧版钥匙串加密格式，需要重新登录一次');
      text = null;
    } else if (available()) {
      try {
        text = safeStorage.decryptString(raw);
      } catch (e) {
        // 解不开（换机/换用户/换 userData 目录/文件是明文）→ 默认当作没有凭据。
        // 只有显式打开自检开关时才退回明文，与 save() 的明文口子对称。
        // 这里必须留一条日志：safeStorage 的密钥存在 userData 的 Local State 里，
        // 只搬 credentials.dat 而不搬 data/electron/ 就会落到这条分支，静默失败
        // 会让"登录态又没了"变成无法排查的玄学（真机踩过）。
        text = process.env.POLARIS_ALLOW_PLAINTEXT_CREDENTIALS === '1' ? raw.toString('utf8') : null;
        if (text === null) {
          log.warn('credentials.dat 无法解密（换机或 data/electron 缓存丢失），需要重新登录：' + (e && e.message));
        }
      }
    } else {
      text = raw.toString('utf8');   // 纯 Node（没有 DPAPI）下本来就只有明文可读
    }
    if (text === null) { cache = null; return cache; }
    cache = JSON.parse(text);
  } catch (_) {
    cache = null;
  }
  return cache;
}

function save(obj) {
  cache = obj;
  try {
    const text = JSON.stringify(obj);
    if (IS_MAC) {
      fs.writeFileSync(file(), macEncrypt(text));
    } else if (available()) {
      fs.writeFileSync(file(), safeStorage.encryptString(text));
    } else if (process.env.POLARIS_ALLOW_PLAINTEXT_CREDENTIALS === '1') {
      // 仅自检用：Electron 之外没有 DPAPI，允许明文落盘以便跑端到端测试
      fs.writeFileSync(file(), Buffer.from(text, 'utf8'));
      log.warn('credentials stored WITHOUT encryption (self-test mode)');
    } else {
      log.error('safeStorage 不可用，拒绝明文保存凭据');
    }
    // 备份到 %LOCALAPPDATA%（加密后的字节原样复制）：便携目录被整包替换
    // 或云同步清掉时能回填，见 electron/backup.js 的头注释。
    require('../backup').mirrorNow();
  } catch (e) {
    log.error('credentials save failed:', e && e.message);
  }
}

function clear() {
  cache = null;
  try { fs.rmSync(file(), { force: true }); } catch (_) {}
  // 备份也要删：否则下次"文件不见了就回填"会把已经注销的登录态复活
  require('../backup').forget('credentials.dat');
}

module.exports = { load, save, clear, available };
