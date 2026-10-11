<script setup>
// 补充节点树 — 单个节点(递归渲染子节点)
import { renderContent } from '../utils/markdown'

const props = defineProps({
  node: { type: Object, required: true },
  depth: { type: Number, default: 0 },
  expanded: { type: Object, required: true }
})
const emit = defineEmits(['toggle', 'edit', 'remove', 'add-child'])

const render = (content, type) => renderContent(content, type)
const fmtTime = (t) => (t || '').replace('T', ' ').slice(0, 16)
const hasChildren = () => props.node.children && props.node.children.length > 0
const isOpen = () => props.depth === 0 || props.expanded[props.node.id]
</script>

<template>
  <div class="sup-node" :style="{ marginLeft: depth * 22 + 'px' }">
    <div class="node-head">
      <span
        v-if="hasChildren()"
        class="arrow"
        role="button"
        tabindex="0"
        @click="emit('toggle', node.id)"
        @keydown.enter="emit('toggle', node.id)"
      >{{ expanded[node.id] ? '▾' : '▸' }}</span>
      <span v-else class="arrow placeholder">·</span>
      <span class="node-title">{{ node.title }}</span>
      <el-tag v-if="node.contentType === 'richtext'" size="small" effect="plain" type="info">富文本</el-tag>
      <span class="node-meta">{{ node.username || '匿名' }} · {{ fmtTime(node.updatedAt || node.createdAt) }}</span>
      <span class="node-ops">
        <el-button link type="primary" size="small" @click="emit('add-child', node)">子节点</el-button>
        <template v-if="node.editable">
          <el-button link type="primary" size="small" @click="emit('edit', node)">编辑</el-button>
          <el-button link type="danger" size="small" @click="emit('remove', node)">删除</el-button>
        </template>
      </span>
    </div>
    <div v-if="isOpen()" class="node-content rich-body" v-html="render(node.content, node.contentType)"></div>
    <template v-if="expanded[node.id]">
      <SupplementNodeItem
        v-for="child in node.children"
        :key="child.id"
        :node="child"
        :depth="depth + 1"
        :expanded="expanded"
        @toggle="emit('toggle', $event)"
        @edit="emit('edit', $event)"
        @remove="emit('remove', $event)"
        @add-child="emit('add-child', $event)"
      />
    </template>
  </div>
</template>

<style scoped>
.sup-node { border-left: 2px solid var(--c-border); padding: 6px 0 6px 12px; margin-top: 6px; }
.node-head { display: flex; align-items: center; gap: 8px; }
.arrow { cursor: pointer; width: 16px; color: var(--c-text-3); user-select: none; }
.arrow.placeholder { cursor: default; }
.node-title { font-weight: 600; color: var(--c-text-1); font-size: var(--fs-base); }
.node-meta { color: var(--c-text-3); font-size: var(--fs-xs); }
.node-ops { margin-left: auto; display: flex; gap: 2px; }
.node-content { margin: 8px 0 4px 24px; padding: 10px 14px; background: #f8fafc; border-radius: var(--r-s); font-size: var(--fs-sm); color: var(--c-text-1); }
.node-content :deep(p) { margin: 4px 0; }
.node-content :deep(pre) { background: #0f172a; color: #e2e8f0; padding: 10px 12px; border-radius: 6px; overflow-x: auto; font-size: 12px; }
.node-content :deep(code) { background: rgba(100,116,139,.12); padding: 1px 5px; border-radius: 4px; font-size: .9em; }
.node-content :deep(pre code) { background: transparent; padding: 0; }
.node-content :deep(ul), .node-content :deep(ol) { margin: 4px 0; padding-left: 20px; }
.node-content :deep(blockquote) { border-left: 3px solid var(--c-border-strong); margin: 6px 0; padding: 2px 12px; color: var(--c-text-2); }
.node-content :deep(img) { max-width: 100%; }
</style>
