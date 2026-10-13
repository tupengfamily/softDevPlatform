import request from '../utils/request'

// ==================== 我的待办 ====================

// status: 不传=全部, 0=待办, 1=已完成
export const getTodos = (status) => request({ url: '/todos', method: 'get', params: status === undefined || status === null ? {} : { status } })
export const createTodo = (data) => request({ url: '/todos', method: 'post', data })
export const updateTodo = (id, data) => request({ url: `/todos/${id}`, method: 'put', data })
export const deleteTodo = (id) => request({ url: `/todos/${id}`, method: 'delete' })
