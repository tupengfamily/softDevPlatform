/**
 * 前端埋点 SDK(综合业界通用做法)
 *
 * <p>能力:
 * <ul>
 *   <li>sessionId: localStorage 持久化(关闭浏览器后再次打开同一会话)</li>
 *   <li>page_view: SPA 路由切换时自动捕获</li>
 *   <li>page_leave: visibilitychange / pagehide 触发, sendBeacon 上报</li>
 *   <li>page_perf: PerformanceObserver(FCP/LCP/FID/CLS/TTFB/DOMContentLoaded/Load)</li>
 *   <li>page_error: window.onerror + unhandledrejection</li>
 * </ul>
 *
 * <p>上报策略:
 * <ul>
 *   <li>普通事件: fetch keepalive(简单)</li>
 *   <li>页面卸载事件: navigator.sendBeacon(浏览器保证异步发完)</li>
 *   <li>批量: 队列攒满 10 条 或 1.5s flush 一次</li>
 * </ul>
 *
 * <p>业界参考: Sentry / Datadog RUM / 百度统计 / 友盟+
 */
import { useUserStore } from '../store/user'
import { useRoute, useRouter } from 'vue-router'

const SESSION_KEY = 'tracker.sessionId'
const FLUSH_INTERVAL_MS = 1500
const MAX_BATCH = 10
const TRACK_URL = '/api/track/batch'

let queue = []
let flushTimer = null
let installed = false
let currentPage = null  // 当前页面元数据 (route/url/title/enterAt)
let router = null
let pageLeaveBeacon = null  // 标记是否已在卸载时上报

/** 启动: 在 main.js 装好后调用 initTracker(router) */
export function initTracker(appRouter) {
  if (installed) return
  installed = true
  router = appRouter

  installLifecycle()
  installErrorHandlers()
  installRouteObserver()
  installUnloadBeacon()
}

/** 生成或读取 sessionId(localStorage 持久化) */
function getSessionId() {
  let sid = localStorage.getItem(SESSION_KEY)
  if (!sid) {
    sid = 'sess-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 10)
    localStorage.setItem(SESSION_KEY, sid)
  }
  return sid
}

function getUserId() {
  try {
    const u = useUserStore()
    return u.userInfo?.userId || u.userInfo?.id || null
  } catch (e) { return null }
}

function getFingerprint() {
  const ua = navigator.userAgent || ''
  return {
    userAgent: ua,
    screen: `${screen.width}x${screen.height}`,
    language: navigator.language || ''
  }
}

function nowMs() { return Date.now() }

function baseEvent(type, extras = {}) {
  const fp = getFingerprint()
  return {
    sessionId: getSessionId(),
    userId: getUserId(),
    type,
    url: location.href,
    referrer: document.referrer || null,
    timestamp: nowMs(),
    userAgent: fp.userAgent,
    screen: fp.screen,
    ...extras
  }
}

function enqueue(event) {
  queue.push(event)
  if (queue.length >= MAX_BATCH) flush()
  else scheduleFlush()
}

function scheduleFlush() {
  if (flushTimer) return
  flushTimer = setTimeout(flush, FLUSH_INTERVAL_MS)
}

function flush() {
  if (flushTimer) { clearTimeout(flushTimer); flushTimer = null }
  if (queue.length === 0) return
  const batch = queue.splice(0, queue.length)
  const body = JSON.stringify(batch)
  // sendBeacon 失败时降级 fetch keepalive
  if (pageLeaveBeacon) {
    // 卸载阶段: sendBeacon
    if (!navigator.sendBeacon(TRACK_URL, new Blob([body], { type: 'application/json' }))) {
      keepAliveFallback(body)
    }
  } else {
    fetch(TRACK_URL, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body,
      keepalive: true
    }).catch(() => { /* 静默失败 */ })
  }
}

function keepAliveFallback(body) {
  fetch(TRACK_URL, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body,
    keepalive: true
  }).catch(() => {})
}

// ============== 路由切换 ==============
function installRouteObserver() {
  if (!router) return
  router.afterEach((to, from) => {
    // 关闭前一个页面 (停留时长)
    if (currentPage) {
      const now = nowMs()
      enqueue({
        ...baseEvent('page_leave', {}),
        routeName: currentPage.route,
        pageTitle: currentPage.title,
        url: currentPage.url,
        enterAt: currentPage.enterAt,
        leaveAt: now,
        durationMs: now - currentPage.enterAt
      })
    }
    // 开始新页面
    const enterAt = nowMs()
    currentPage = {
      route: to.name || to.path || '',
      title: document.title || to.meta?.title || '',
      url: location.href,
      enterAt
    }
    enqueue({
      ...baseEvent('page_view', {}),
      routeName: currentPage.route,
      pageTitle: currentPage.title,
      url: currentPage.url,
      enterAt
    })
    // 等一帧让标题更新, 再采集性能
    setTimeout(capturePagePerf, 100)
  })
}

// ============== 页面生命周期(关闭浏览器) ==============
function installUnloadBeacon() {
  // 浏览器关闭/刷新时, pagehide 比 beforeunload 在移动端更稳定
  window.addEventListener('pagehide', () => {
    pageLeaveBeacon = true
    if (currentPage) {
      const now = nowMs()
      enqueue({
        ...baseEvent('page_leave', {}),
        routeName: currentPage.route,
        pageTitle: currentPage.title,
        url: currentPage.url,
        enterAt: currentPage.enterAt,
        leaveAt: now,
        durationMs: now - currentPage.enterAt
      })
    }
    flush()
  })
  // visibilitychange: 后台标签页(visibility=hidden), 也补一次
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') {
      if (currentPage) {
        const now = nowMs()
        enqueue({
          ...baseEvent('page_leave', {}),
          routeName: currentPage.route,
          pageTitle: currentPage.title,
          url: currentPage.url,
          enterAt: currentPage.enterAt,
          leaveAt: now,
          durationMs: now - currentPage.enterAt
        })
        // 注意: 此时不再 reset currentPage, 用户回到前台后继续累计
      }
    }
  })
}

// ============== 性能指标 ==============
function capturePagePerf() {
  if (typeof performance === 'undefined') return
  const nav = performance.getEntriesByType('navigation')[0] || {}
  const paint = performance.getEntriesByType('paint')
  const fcp = paint.find(p => p.name === 'first-contentful-paint')

  // LCP / CLS / FID 用 PerformanceObserver 累计
  const obs = collectWebVitals()

  enqueue({
    ...baseEvent('page_perf', {}),
    routeName: currentPage?.route,
    pageTitle: currentPage?.title,
    url: location.href,
    enterAt: currentPage?.enterAt,
    timestamp: nowMs(),
    ttfb: nav.responseStart && nav.requestStart ? Math.round(nav.responseStart - nav.requestStart) : null,
    domInteractive: nav.domInteractive > 0 ? Math.round(nav.domInteractive - nav.startTime) : null,
    loadComplete: nav.loadEventEnd > 0 ? Math.round(nav.loadEventEnd - nav.startTime) : null,
    fcp: fcp ? Math.round(fcp.startTime) : null,
    lcp: obs.lcp,
    fid: obs.fid,
    cls: obs.cls
  })
}

let webVitals = { lcp: null, fid: null, cls: 0 }
function collectWebVitals() {
  // LCP
  try {
    new PerformanceObserver(list => {
      const entries = list.getEntries()
      const last = entries[entries.length - 1]
      webVitals.lcp = Math.round(last.startTime)
    }).observe({ type: 'largest-contentful-paint', buffered: true })
  } catch (e) {}
  // FID
  try {
    new PerformanceObserver(list => {
      list.getEntries().forEach(e => {
        if (e.processingStart && e.startTime) {
          webVitals.fid = Math.round(e.processingStart - e.startTime)
        }
      })
    }).observe({ type: 'first-input', buffered: true })
  } catch (e) {}
  // CLS
  try {
    new PerformanceObserver(list => {
      list.getEntries().forEach(e => {
        if (!e.hadRecentInput) webVitals.cls += e.value
      })
    }).observe({ type: 'layout-shift', buffered: true })
  } catch (e) {}
  return webVitals
}

// ============== 错误捕获 ==============
function installErrorHandlers() {
  window.addEventListener('error', (event) => {
    enqueue({
      ...baseEvent('page_error', {}),
      routeName: currentPage?.route,
      pageTitle: currentPage?.title,
      url: location.href,
      errorType: 'Error',
      errorMessage: event.message || String(event.error || 'Unknown error'),
      filename: event.filename,
      lineno: event.lineno,
      colno: event.colno,
      stack: event.error && event.error.stack ? event.error.stack.substring(0, 4000) : null,
      timestamp: nowMs()
    })
    if (queue.length > 0) flush()
  })

  window.addEventListener('unhandledrejection', (event) => {
    const reason = event.reason || {}
    const msg = reason.message || (typeof reason === 'string' ? reason : 'Unhandled rejection')
    enqueue({
      ...baseEvent('page_error', {}),
      routeName: currentPage?.route,
      pageTitle: currentPage?.title,
      url: location.href,
      errorType: 'UnhandledRejection',
      errorMessage: msg,
      stack: reason.stack ? reason.stack.substring(0, 4000) : null,
      timestamp: nowMs()
    })
    if (queue.length > 0) flush()
  })
}

function installLifecycle() {
  // 兜底: 定时 flush
  setInterval(flush, 30000)
}

// ============== 工具方法 ==============
/** 手动触发业务事件 */
export function trackEvent(name, params = {}) {
  enqueue({
    ...baseEvent('custom', {}),
    routeName: currentPage?.route,
    pageTitle: currentPage?.title,
    url: location.href,
    eventName: name,
    ...params,
    timestamp: nowMs()
  })
}

/** 主动立即上报(用于关键业务节点) */
export function trackFlush() {
  flush()
}

/** 当前 sessionId(调试用) */
export function getCurrentSessionId() {
  return getSessionId()
}