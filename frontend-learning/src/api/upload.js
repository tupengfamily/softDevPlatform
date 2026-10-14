import request from '../utils/request'

/**
 * 上传图片(供富文本/Markdown 编辑器使用)
 * @param {File} file 图片文件
 * @param {string} [scope='wiki'] 业务域,用于后端分目录存放
 * @returns {Promise<{url:string,size:number,originalName:string}>}
 */
export const uploadImage = (file, scope = 'wiki') => {
  const fd = new FormData()
  fd.append('file', file)
  fd.append('scope', scope)
  return request({
    url: '/uploads/image',
    method: 'post',
    data: fd,
    timeout: 30000
  })
}
