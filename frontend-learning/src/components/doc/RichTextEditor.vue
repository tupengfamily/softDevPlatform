<template>
  <div class="rt-editor">
    <div class="toolbar">
      <span class="hint">{{ $t('myDocs.rtHint') }}</span>
      <span class="grow" />
      <el-radio-group v-model="mode" size="small">
        <el-radio-button value="edit">{{ $t('myDocs.modeEdit') }}</el-radio-button>
        <el-radio-button value="preview">{{ $t('myDocs.modePreview') }}</el-radio-button>
      </el-radio-group>
    </div>
    <div class="body" :class="`mode-${mode}`">
      <textarea
        v-if="mode === 'edit'"
        ref="ta"
        v-model="text"
        class="textarea"
        :placeholder="$t('myDocs.rtPlaceholder')"
        spellcheck="false"
        @input="emitChange"
      />
      <iframe
        v-else
        :srcdoc="text || emptyHint"
        class="preview"
        sandbox="allow-same-origin"
      />
    </div>
  </div>
</template>

<script setup>
/**
 * 富文本编辑器(HTML 源码编辑): 写 textarea, 预览用受限 sandbox iframe
 * <p>
 * 这版是"HTML 源码模式", 适合已熟悉 HTML 的开发者。
 * 后续可替换为 contenteditable + Tiptap, 但 8 章 v5 wiki 已用 Tiptap, 此处先不做重复建设。
 */
import { ref, watch } from 'vue'

const props = defineProps({
  model: { type: Object, required: true }
})
const emit = defineEmits(['change'])

const text = ref(props.model?.content ?? '')
const mode = ref('edit')
const emptyHint = '<p style="color:#999">空内容</p>'

watch(() => props.model?.fileId, () => {
  text.value = props.model?.content ?? ''
})

const emitChange = () => emit('change', { content: text.value })
</script>

<style scoped>
.rt-editor { display: flex; flex-direction: column; height: 100%; min-height: 0; background: #fff; }
.toolbar { display: flex; align-items: center; gap: 10px; padding: 6px 14px; border-bottom: 1px solid var(--c-border); background: #fff; }
.hint { font-size: 12px; color: var(--c-text-3); }
.grow { flex: 1; }
.body { flex: 1; min-height: 0; display: flex; }
.body.mode-edit { display: flex; }
.body.mode-preview { display: flex; }
.textarea { flex: 1; padding: 16px 22px; border: 0; outline: none; resize: none; font-family: 'SF Mono', Menlo, Consolas, monospace; font-size: 14px; line-height: 1.7; color: var(--c-text-1); background: #fff; }
.preview { flex: 1; width: 100%; height: 100%; border: 0; background: #fff; padding: 16px 22px; }
</style>
