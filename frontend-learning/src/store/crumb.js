// 面包屑动态标题共享状态
// route.meta 是导航时合并的普通对象,直接赋值不会触发视图更新,
// 因此课时页将标题写入此响应式 ref,Layout 面包屑读取它。
import { ref } from 'vue'

export const lessonCrumbTitle = ref('')
