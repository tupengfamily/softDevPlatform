<script setup>
// Word 文档简易预览: 渲染 readModel 的 blocks 数组为纯文本视图
// 适用于 OO 关闭场景: 不依赖 OO 也能给用户"打开文件名 → 看到内容摘要"的体验
import { computed } from 'vue'

const props = defineProps({
  model: { type: Object, required: true }   // readModel: { blocks, styles, hasNumbering, ... }
})

const blocks = computed(() => props.model?.blocks || [])
const styles = computed(() => props.model?.styles || [])

// 块级样式: 标题居中加粗放大, 段落首行缩进
const blockClass = (b) => {
  const cls = ['wp-block']
  if (b.type === 'heading') {
    cls.push(`wp-h${b.level || 1}`)
  } else if (b.type === 'paragraph') {
    cls.push('wp-p')
    if (b.align && b.align !== 'left') cls.push(`wp-a-${b.align}`)
  } else if (b.type === 'list') {
    cls.push('wp-list')
  }
  return cls.join(' ')
}
</script>

<template>
  <div class="word-preview">
    <div v-if="!blocks.length" class="wp-empty">
      <el-empty description="空文档" :image-size="60" />
    </div>
    <div v-else class="wp-page">
      <div
        v-for="(b, i) in blocks"
        :key="i"
        :class="blockClass(b)"
      >{{ b.text || ' ' }}</div>
    </div>
  </div>
</template>

<style scoped>
.word-preview {
  width: 100%;
  height: 100%;
  overflow: auto;
  background: #f3f4f6;
  padding: 24px 0;
}
.wp-page {
  max-width: 760px;
  margin: 0 auto;
  background: #fff;
  border: 1px solid var(--c-border, #e5e7eb);
  border-radius: 4px;
  padding: 48px 64px;
  box-shadow: 0 2px 8px rgba(0,0,0,.04);
  min-height: 600px;
  font-family: 'PingFang SC', 'Microsoft YaHei', 'SF Pro', serif;
  color: #1f2937;
  line-height: 1.7;
}
.wp-empty { padding: 60px; text-align: center; }
.wp-block { margin: 0 0 14px 0; word-break: break-word; }
.wp-h1 { font-size: 28px; font-weight: 700; margin-top: 20px; }
.wp-h2 { font-size: 22px; font-weight: 700; margin-top: 18px; }
.wp-h3 { font-size: 18px; font-weight: 600; margin-top: 14px; }
.wp-h4, .wp-h5, .wp-h6 { font-size: 16px; font-weight: 600; }
.wp-p { font-size: 15px; text-indent: 0; }
.wp-a-center { text-align: center; }
.wp-a-right { text-align: right; }
.wp-a-justify { text-align: justify; }
.wp-list { padding-left: 24px; list-style: disc; }
</style>
