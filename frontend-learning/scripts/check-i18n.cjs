#!/usr/bin/env node
/**
 * 校验 i18n 词条完整性
 *
 * <p>检查项:
 *  1) .vue / .js 引用的 t('xxx') / $t('xxx') 是否在 i18n/messages/*.js 中定义
 *  2) i18n/messages/*.js 的中英两段 key 是否一致(防止漏 i18n)
 *  3) 常用 'common.*' key 是否齐全(common.cancel/common.ok 等)
 *
 * <p>用法: node scripts/check-i18n.js
 * <p>退出码: 0=通过, 1=有缺失
 */
'use strict'

const fs = require('fs')
const path = require('path')

const ROOT = path.resolve(__dirname, '..')
const SRC = path.join(ROOT, 'src')
const I18N = path.join(SRC, 'i18n/messages')
const REQUIRED_COMMON = ['common.cancel', 'common.ok', 'common.save', 'common.confirm']

let failed = false

// 1. 收集所有 t('xxx') 引用
function walk(dir, exts, skip) {
  const out = []
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (skip && skip.some(s => p.includes(s))) continue
    if (e.isDirectory()) out.push(...walk(p, exts, skip))
    else if (exts.some(x => e.name.endsWith(x))) out.push(p)
  }
  return out
}

const srcFiles = walk(SRC, ['.vue', '.js'], ['i18n/messages'])
const refs = new Set()
const tRe = /\$?t\(\s*['"]([\w\-]+\.[\w\-.]+)['"]/g
for (const f of srcFiles) {
  const txt = fs.readFileSync(f, 'utf8')
  for (const m of txt.matchAll(tRe)) refs.add(m[1])
}

// 2. 收集 i18n 定义
const i18nFiles = walk(I18N, ['.js'])
const defined = new Set()
for (const f of i18nFiles) {
  const txt = fs.readFileSync(f, 'utf8')
  for (const m of txt.matchAll(/'([\w\-.]+)'\s*:\s*['"]/g)) defined.add(m[1])
}

// 3. 找缺失引用
const missing = [...refs].filter(k => !defined.has(k)).sort()
if (missing.length) {
  failed = true
  console.error('❌ 缺失 i18n key(代码用了但词条没定义):')
  for (const k of missing) console.error('   - ' + k)
} else {
  console.log('✅ 所有 t() 引用都有 i18n 定义')
}

// 4. 检查每个 key 是否出现 2 次(中英各 1, 漏译 = 1 次)
const keyOccurrences = new Map()
for (const f of i18nFiles) {
  const txt = fs.readFileSync(f, 'utf8')
  for (const m of txt.matchAll(/'([\w\-.]+)'\s*:\s*['"]/g)) {
    keyOccurrences.set(m[1], (keyOccurrences.get(m[1]) || 0) + 1)
  }
}
const untranslated = [...keyOccurrences.entries()].filter(([, n]) => n < 2).map(([k]) => k).sort()
if (untranslated.length) {
  failed = true
  console.error('❌ 漏译 key(只出现 1 次, 中英缺一份, 实际: ' + keyOccurrences.size + ' 个 key):')
  for (const k of untranslated) console.error('   - ' + k)
} else {
  console.log(`✅ 所有 ${keyOccurrences.size} 个 key 中英都对译`)
}

// 5. 常用 key 检查
const missingCommon = REQUIRED_COMMON.filter(k => !defined.has(k))
if (missingCommon.length) {
  failed = true
  console.error('❌ 缺少常用 key:')
  for (const k of missingCommon) console.error('   - ' + k)
} else {
  console.log('✅ 常用 common.* key 齐全')
}

process.exit(failed ? 1 : 0)