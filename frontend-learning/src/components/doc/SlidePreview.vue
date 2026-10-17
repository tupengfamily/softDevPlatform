<script setup>
// PPT 简易预览: 渲染 readModel 的 slides[] 为缩略图列表
// 限制: 标题 + shapes 摘要列表;不显示实际样式(只文字)
import { computed } from 'vue'

const props = defineProps({
  model: { type: Object, required: true }
})

const slides = computed(() => props.model?.slides || [])

const slideText = (s) => {
  // 优先 title 字段,否则扫 shapes 里的 paragraphs
  if (s.title) return s.title
  const texts = []
  for (const sh of (s.shapes || [])) {
    for (const p of (sh.paragraphs || [])) {
      if (p.text) texts.push(p.text)
    }
  }
  return texts.join(' / ').substring(0, 100)
}

const slideShapeCount = (s) => (s.shapes || []).length
</script>

<template>
  <div class="slide-preview">
    <div v-if="!slides.length" class="sp-empty">
      <el-empty description="空演示文稿" :image-size="60" />
    </div>
    <div v-else class="sp-list">
      <div v-for="(s, i) in slides" :key="i" class="sp-card">
        <div class="sp-card-head">
          <span class="sp-idx">{{ i + 1 }}</span>
          <span class="sp-ptitle">{{ slideText(s) || '(无标题)' }}</span>
        </div>
        <div class="sp-card-body">
          <div v-if="s.shapes?.length" class="sp-shapes">
            <div v-for="(sh, j) in s.shapes" :key="j" class="sp-shape">
              <el-tag size="small" :type="sh.kind === 'title' ? 'primary' : 'info'">{{ sh.name || sh.kind }}</el-tag>
              <span v-if="sh.paragraphs?.[0]?.text" class="sp-shape-text">{{ sh.paragraphs[0].text }}</span>
            </div>
          </div>
          <div v-else class="sp-empty-shape">空幻灯片</div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.slide-preview {
  width: 100%;
  height: 100%;
  overflow: auto;
  background: #f3f4f6;
  padding: 20px;
}
.sp-empty { padding: 60px; text-align: center; }
.sp-list { display: flex; flex-direction: column; gap: 16px; max-width: 720px; margin: 0 auto; }
.sp-card {
  background: #fff;
  border: 1px solid var(--c-border, #e5e7eb);
  border-radius: 6px;
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(0,0,0,.04);
}
.sp-card-head {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 16px;
  background: linear-gradient(135deg, #fef3c7 0%, #fde68a 100%);
  border-bottom: 1px solid var(--c-border, #e5e7eb);
}
.sp-idx {
  background: #f59e0b;
  color: #fff;
  font-weight: 700;
  padding: 2px 10px;
  border-radius: 12px;
  font-size: 13px;
}
.sp-ptitle {
  font-weight: 600;
  color: #78350f;
  font-size: 15px;
}
.sp-card-body { padding: 12px 16px; }
.sp-shapes { display: flex; flex-direction: column; gap: 6px; }
.sp-shape {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--c-text-2, #4b5563);
}
.sp-shape-text {
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.sp-empty-shape { color: var(--c-text-4, #9ca3af); font-size: 12px; font-style: italic; }
</style>
