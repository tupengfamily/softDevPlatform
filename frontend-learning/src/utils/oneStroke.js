// 一笔画(欧拉路径)核心算法
// 规则: 给定一张无向图, 玩家从任一点起笔, 不重复地走完所有边即通关
// 数学基础:
//   - 欧拉回路 (起点==终点): 所有顶点度数为偶数
//   - 欧拉路径 (起点!=终点): 恰好 2 个顶点度数为奇数 (起点和终点)
//   - 否则无解

// ==================== 工具 ====================
/** 由节点和边构建邻接表 */
export function buildAdj (nodes, edges) {
  const adj = new Map()
  for (const n of nodes) adj.set(n.id, [])
  for (const e of edges) {
    adj.get(e.a).push({ to: e.b, edgeId: e.id })
    adj.get(e.b).push({ to: e.a, edgeId: e.id })
  }
  return adj
}

/** 每个节点的度数 */
export function computeDegrees (nodes, edges) {
  const deg = new Map()
  for (const n of nodes) deg.set(n.id, 0)
  for (const e of edges) {
    deg.set(e.a, (deg.get(e.a) || 0) + 1)
    deg.set(e.b, (deg.get(e.b) || 0) + 1)
  }
  return deg
}

/** 欧拉路径存在性判定
 *  返回 { solvable, type: 'circuit'|'path'|'none', oddNodes, connected }
 */
export function analyze (nodes, edges) {
  if (!nodes.length || !edges.length) {
    return { solvable: false, type: 'none', oddNodes: [], connected: false }
  }
  const deg = computeDegrees(nodes, edges)
  const odd = []
  for (const [id, d] of deg) if (d % 2 === 1) odd.push(id)
  const incident = nodes.filter(n => (deg.get(n.id) || 0) > 0)
  if (!incident.length) return { solvable: false, type: 'none', oddNodes: odd, connected: false }
  // 连通性 (仅看 incident)
  const adj = buildAdj(nodes, edges)
  const visited = new Set([incident[0].id])
  const stack = [incident[0].id]
  while (stack.length) {
    const u = stack.pop()
    for (const { to } of adj.get(u)) {
      if (!visited.has(to)) { visited.add(to); stack.push(to) }
    }
  }
  const connected = incident.every(n => visited.has(n.id))
  let type = 'none'
  let solvable = false
  if (connected) {
    if (odd.length === 0) { type = 'circuit'; solvable = true }
    else if (odd.length === 2) { type = 'path'; solvable = true }
  }
  return { solvable, type, oddNodes: odd, connected }
}

/** Hierholzer 算法求欧拉路径(从 start 出发), 不可解返回 null */
export function findEulerPath (nodes, edges, start) {
  if (!nodes.length || !edges.length) return null
  const adj = buildAdj(nodes, edges)
  const used = new Set()
  const stack = [start]
  const path = []
  while (stack.length) {
    const u = stack[stack.length - 1]
    let next = null
    while (adj.get(u).length) {
      const cand = adj.get(u).pop()
      if (!used.has(cand.edgeId)) { used.add(cand.edgeId); next = cand; break }
    }
    if (next) stack.push(next.to)
    else path.push(stack.pop())
  }
  if (path.length !== edges.length + 1) return null
  return path.reverse()
}

/** 给定可解关卡, 选起点 (path 类型取 oddNodes[0], circuit 类型取 nodes[0]) */
export function pickStart (nodes, edges) {
  const info = analyze(nodes, edges)
  if (!info.solvable) return null
  if (info.type === 'path') return info.oddNodes[0]
  return nodes[0].id
}

// ==================== 关卡 ====================
// viewBox: 0..1000 宽 / 0..600 高
export const VIEW = { width: 1000, height: 600 }

function edgeId (a, b) { return 'e' + Math.min(a, b) + '-' + Math.max(a, b) }

function dist (a, b) { return Math.hypot(a.x - b.x, a.y - b.y) }

/** 手画前 5 关, 形状直观且保证可解 */
const HANDMADE_LEVELS = [
  // 关 1: 三角形 (3 节点, 3 边, 全部偶数度)
  {
    id: 1, title: '三角形',
    nodes: [
      { id: 1, x: 500, y: 120 },
      { id: 2, x: 200, y: 480 },
      { id: 3, x: 800, y: 480 }
    ],
    edges: [
      { id: edgeId(1, 2), a: 1, b: 2 },
      { id: edgeId(2, 3), a: 2, b: 3 },
      { id: edgeId(1, 3), a: 1, b: 3 }
    ]
  },
  // 关 2: 房子 (5 节点, 6 边, path 类型: 2 奇数度)
  {
    id: 2, title: '房子',
    nodes: [
      { id: 1, x: 300, y: 200 },
      { id: 2, x: 700, y: 200 },
      { id: 3, x: 700, y: 450 },
      { id: 4, x: 300, y: 450 },
      { id: 5, x: 500, y: 80 }
    ],
    edges: [
      { id: edgeId(1, 2), a: 1, b: 2 },
      { id: edgeId(1, 4), a: 1, b: 4 },
      { id: edgeId(2, 3), a: 2, b: 3 },
      { id: edgeId(3, 4), a: 3, b: 4 },
      { id: edgeId(1, 5), a: 1, b: 5 },
      { id: edgeId(2, 5), a: 2, b: 5 }
    ]
  },
  // 关 3: 六边形带桥 (6 节点, 7 边, path: 2 和 4 奇数度)
  {
    id: 3, title: '六边形带桥',
    nodes: [
      { id: 1, x: 500, y: 100 },
      { id: 2, x: 850, y: 350 },
      { id: 3, x: 700, y: 550 },
      { id: 4, x: 300, y: 550 },
      { id: 5, x: 150, y: 350 },
      { id: 6, x: 500, y: 350 }
    ],
    edges: [
      { id: edgeId(1, 2), a: 1, b: 2 },
      { id: edgeId(2, 3), a: 2, b: 3 },
      { id: edgeId(3, 4), a: 3, b: 4 },
      { id: edgeId(4, 5), a: 4, b: 5 },
      { id: edgeId(5, 1), a: 5, b: 1 },
      { id: edgeId(2, 6), a: 2, b: 6 },
      { id: edgeId(4, 6), a: 4, b: 6 }
    ]
  },
  // 关 4: 五角星网 K5 (5 节点, 10 边, circuit: 全偶数度 4)
  {
    id: 4, title: '五角星网',
    nodes: [
      { id: 1, x: 500, y: 100 },
      { id: 2, x: 850, y: 350 },
      { id: 3, x: 700, y: 550 },
      { id: 4, x: 300, y: 550 },
      { id: 5, x: 150, y: 350 }
    ],
    edges: (() => {
      const es = []
      for (let i = 1; i <= 5; i++) {
        for (let j = i + 1; j <= 5; j++) es.push({ id: edgeId(i, j), a: i, b: j })
      }
      return es
    })()
  },
  // 关 5: 沙漏 (5 节点, 6 边, circuit: 中心度数 4, 其余 2)
  {
    id: 5, title: '沙漏',
    nodes: [
      { id: 1, x: 200, y: 150 },
      { id: 2, x: 800, y: 150 },
      { id: 3, x: 500, y: 320 },
      { id: 4, x: 200, y: 500 },
      { id: 5, x: 800, y: 500 }
    ],
    edges: [
      { id: edgeId(1, 2), a: 1, b: 2 },
      { id: edgeId(1, 3), a: 1, b: 3 },
      { id: edgeId(2, 3), a: 2, b: 3 },
      { id: edgeId(3, 4), a: 3, b: 4 },
      { id: edgeId(3, 5), a: 3, b: 5 },
      { id: edgeId(4, 5), a: 4, b: 5 }
    ]
  }
]

/** 程序生成关卡: 圆形分布节点, 每节点尝试连 4 条远距离边, 最多 50 次重试 */
function generateLevels (startId, count, baseNodes = 6) {
  const levels = []
  for (let i = 0; i < count; i++) {
    const nodeCount = baseNodes + i
    let level = null
    for (let t = 0; t < 50; t++) {
      level = generateOneLevel(startId + i, nodeCount)
      if (analyze(level.nodes, level.edges).solvable) break
    }
    if (level && analyze(level.nodes, level.edges).solvable) levels.push(level)
  }
  return levels
}

function generateOneLevel (id, nodeCount) {
  const cx = VIEW.width / 2, cy = VIEW.height / 2
  const radius = Math.min(VIEW.width, VIEW.height) * 0.38
  const nodes = []
  for (let i = 0; i < nodeCount; i++) {
    const a = (i / nodeCount) * Math.PI * 2 - Math.PI / 2
    const jitter = (Math.random() - 0.5) * 30
    nodes.push({
      id: i + 1,
      x: Math.round(cx + Math.cos(a) * (radius + jitter)),
      y: Math.round(cy + Math.sin(a) * (radius + jitter))
    })
  }
  const targetDegree = 4
  const deg = new Map()
  nodes.forEach(n => deg.set(n.id, 0))
  const edges = []
  const seen = new Set()
  for (let i = 0; i < nodeCount; i++) {
    const u = nodes[i].id
    if (deg.get(u) >= targetDegree) continue
    const cands = nodes
      .filter(n => n.id !== u)
      .map(n => ({ id: n.id, d: dist(nodes[i], n) }))
      .sort((a, b) => b.d - a.d)
    for (const c of cands) {
      if (deg.get(u) >= targetDegree) break
      if (deg.get(c.id) >= targetDegree) continue
      const eid = edgeId(u, c.id)
      if (seen.has(eid)) continue
      seen.add(eid)
      edges.push({ id: eid, a: Math.min(u, c.id), b: Math.max(u, c.id) })
      deg.set(u, deg.get(u) + 1)
      deg.set(c.id, deg.get(c.id) + 1)
    }
  }
  return { id, title: `第 ${id} 关`, nodes, edges }
}

const GENERATED_LEVELS = generateLevels(6, 5, 6)  // id 6..10, 节点 6..10

export const LEVELS = [...HANDMADE_LEVELS, ...GENERATED_LEVELS]
export const TOTAL_LEVELS = LEVELS.length

/** 难度档 */
export const DIFFICULTY = {
  easy:   { code: 'easy',   levelRange: [1, 3],  maxHints: 5 },
  normal: { code: 'normal', levelRange: [4, 7],  maxHints: 3 },
  hard:   { code: 'hard',   levelRange: [8, 10], maxHints: 2 }
}

/** 取某难度下的关卡 */
export function getLevels (code) {
  const cfg = DIFFICULTY[code] || DIFFICULTY.normal
  return LEVELS.filter(l => l.id >= cfg.levelRange[0] && l.id <= cfg.levelRange[1])
}

/** 通过 id 取单个关卡 */
export function getLevel (id) {
  return LEVELS.find(l => l.id === id) || null
}