/**
 * 前端纯函数自检的**统一入口**（零依赖）。
 *
 * 🔴 **为什么需要它**（2026-10-29 实测踩坑）：
 *
 *   原来 `package.json` 的 test 脚本是硬编码的：
 *     "test": "node test/map-geo.test.mjs"
 *   后来新增了 `test/scene-orbit.test.mjs`（360° 环绕的 29 个用例），
 *   但**没有改脚本** → `npm test` 根本不跑新文件，
 *   新增的验收闸门等于**从来没被执行过**。
 *
 *   这是一个「静默失效」型缺陷：测试文件存在、代码正确，
 *   但 CI/提交前自检看到的是「全绿」—— 因为它压根没跑那部分。
 *
 *   修法：扫描 test/ 下所有 *.test.mjs 逐个跑，
 *   任何一个失败 → 整个进程退出码非 0。
 *
 * <p>用法：
 * <pre>
 *   npm test
 * </pre>
 */

import { readdirSync } from 'node:fs'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import { dirname, join } from 'node:path'

const here = dirname(fileURLToPath(import.meta.url))
const testDir = here

const files = readdirSync(testDir)
  .filter((f) => f.endsWith('.test.mjs'))
  .sort()

if (!files.length) {
  console.error('❌ test/ 下没有任何 *.test.mjs —— 自检脚本本身失效了')
  process.exit(1)
}

console.log(`发现 ${files.length} 个测试文件：${files.join(', ')}\n`)

let failed = 0
for (const f of files) {
  console.log(`━━━ ${f} ━━━`)
  const r = spawnSync(process.execPath, [join(testDir, f)], { stdio: 'inherit' })
  if (r.status !== 0) {
    failed++
    console.error(`✗ ${f} 退出码 ${r.status}\n`)
  }
  console.log('')
}

if (failed) {
  console.error(`❌ ${failed}/${files.length} 个测试文件失败`)
  process.exit(1)
}
console.log(`✅ ${files.length}/${files.length} 个测试文件全部通过`)
