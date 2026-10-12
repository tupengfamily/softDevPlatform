import request from '../utils/request'

// POST /api/sandbox/run-java — 编译并执行 Java 单文件
export const runJava = (source) =>
  request({ url: '/sandbox/run-java', method: 'post', data: { source } })

// POST /api/sandbox/run-python — 解释执行 Python 单文件(5 秒超时)
export const runPython = (source) =>
  request({ url: '/sandbox/run-python', method: 'post', data: { source } })

// POST /api/sandbox/run-sql — 在教学库执行 SELECT/INSERT/UPDATE/DELETE
export const runSql = (sql) =>
  request({ url: '/sandbox/run-sql', method: 'post', data: { sql } })

// POST /api/sandbox/reset-sql — 一键重置教学库
export const resetSql = () =>
  request({ url: '/sandbox/reset-sql', method: 'post' })

// GET /api/sandbox/sql-info — 库概览
export const getSqlInfo = () =>
  request({ url: '/sandbox/sql-info', method: 'get' })