<template>
  <div class="md-editor">
    <div class="toolbar">
      <span class="hint">{{ hint || $t('myDocs.shortcutTip') }}</span>
      <span class="grow" />
      <el-radio-group v-model="mode" size="small">
        <el-radio-button value="edit">{{ $t('myDocs.modeEdit') }}</el-radio-button>
        <el-radio-button value="preview">{{ $t('myDocs.modePreview') }}</el-radio-button>
        <el-radio-button value="split">{{ $t('myDocs.modeSplit') }}</el-radio-button>
      </el-radio-group>
    </div>
    <div class="body" :class="`mode-${mode}`">
      <textarea
        v-if="mode !== 'preview'"
        ref="ta"
        v-model="text"
        class="textarea"
        :placeholder="$t('myDocs.placeholderWrite')"
        spellcheck="false"
        @input="emitChange"
      />
      <div v-if="mode !== 'edit'" class="preview md-body" v-html="rendered" />
    </div>
  </div>
</template>

<script setup>
/**
 * Markdown 编辑器: textarea + 实时渲染(开源 marked+DOMPurify)
 * <p>
 * mode 三档: edit / preview / split(双栏)
 * 编辑时实时更新 model.content, 保存走 service 的 saveModel
 */
import { computed, ref, watch } from 'vue'
import { renderMarkdown } from '../../utils/markdown'

const props = defineProps({
  model: { type: Object, required: true },
  hint: { type: String, default: '' }
})
const emit = defineEmits(['change'])

const text = ref(props.model?.content ?? '')
const mode = ref('split')  // 默认 split: 左写右看

watch(() => props.model?.fileId, () => {
  text.value = props.model?.content ?? ''
})

const rendered = computed(() => renderMarkdown(text.value || ''))

const emitChange = () => {
  emit('change', { content: text.value })
}
</script>

<style scoped>
.md-editor { display: flex; flex-direction: column; height: 100%; min-height: 0; background: #fff; }
.toolbar { display: flex; align-items: center; gap: 10px; padding: 6px 14px; border-bottom: 1px solid var(--c-border); background: #fff; }
.hint { font-size: 12px; color: var(--c-text-3); }
.grow { flex: 1; }
.body { flex: 1; min-height: 0; display: flex; }
.body.mode-edit, .body.mode-preview { display: block; }
.textarea { flex: 1; min-height: 0; padding: 16px 22px; border: 0; outline: none; resize: none; font-family: 'SF Mono', Menlo, Consolas, monospace; font-size: 14px; line-height: 1.7; color: var(--c-text-1); background: #fff; }
.body.mode-edit .textarea { width: 100%; height: 100%; }
.preview { flex: 1; min-width: 0; padding: 16px 22px; overflow: auto; background: #fff; line-height: 1.7; color: var(--c-text-1); font-size: 15px; }
.body.mode-preview .preview { width: 100%; height: 100%; }
.body.mode-split .textarea { border-right: 1px solid var(--c-border); }
.md-body :deep(h1), .md-body :deep(h2), .md-body :deep(h3) { color: var(--c-text-1); margin: 1.2em 0 0.6em; }
.md-body :deep(p) { margin: 0.6em 0; }
.md-body :deep(code) { background: #f3f4f8; padding: 2px 6px; border-radius: 3px; font-family: 'SF Mono', Menlo, Consolas, monospace; }
.md-body :deep(pre) { background: #1f2937; color: #e5e7eb; padding: 14px 16px; border-radius: 6px; overflow: auto; }
.md-body :deep(pre code) { background: transparent; color: inherit; padding: 0; }
.md-body :deep(blockquote) { border-left: 4px solid var(--c-primary); padding: 6px 12px; background: var(--c-primary-bg); margin: 0.8em 0; color: var(--c-text-2); }
.md-body :deep(table) { border-collapse: collapse; margin: 0.8em 0; }
.md-body :deep(th), .md-body :deep(td) { border: 1px solid var(--c-border); padding: 6px 10px; }
</style>
