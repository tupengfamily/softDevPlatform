<script setup>
/**
 * 紧凑工具栏 - 像 WPS / 腾讯文档 / Office Web
 *  - 24px 高按钮, 紧凑间距
 *  - SVG 自绘图标(16px, currentColor)
 *  - 分组用 1px 竖线分隔
 *  - 支持按钮 / 下拉 / 颜色拾取 / 文本输入
 *  - tooltip 提示快捷键
 *
 * 用法:
 *   <EditorToolbar>
 *     <template #start>
 *       <TbGroup>
 *         <TbButton @click="..."><IconUndo /></TbButton>
 *       </TbGroup>
 *     </template>
 *     <template #end>
 *       <TbSelect :model-value="..." :options="..." @change="..." />
 *     </template>
 *   </EditorToolbar>
 */
import { computed, h, ref, watch } from 'vue'

const props = defineProps({
  // 工具栏整体高度
  size: { type: String, default: 'md' }  // sm | md | lg
})
</script>

<script>
// 子组件: 按钮
export const TbButton = {
  name: 'TbButton',
  props: {
    active: { type: Boolean, default: false },
    disabled: { type: Boolean, default: false },
    title: { type: String, default: '' }
  },
  setup(props, { slots }) {
    return () => h('button', {
      class: ['tb-btn', { 'tb-btn-active': props.active, 'tb-btn-disabled': props.disabled }],
      title: props.title,
      disabled: props.disabled
    }, slots.default ? slots.default() : [])
  }
}

// 子组件: 按钮组(同一组按钮紧密相邻)
export const TbGroup = {
  name: 'TbGroup',
  setup(_, { slots }) {
    return () => h('div', { class: 'tb-group' }, slots.default ? slots.default() : [])
  }
}

// 子组件: 分隔线
export const TbSep = {
  name: 'TbSep',
  setup() { return () => h('div', { class: 'tb-sep' }) }
}

// 子组件: 下拉(自定义箭头)
export const TbSelect = {
  name: 'TbSelect',
  props: {
    modelValue: { type: [String, Number, null], default: '' },
    options: { type: Array, default: () => [] },  // [{ value, label }]
    placeholder: { type: String, default: '' },
    width: { type: String, default: '100px' }
  },
  emits: ['change', 'update:modelValue'],
  setup(props, { emit }) {
    const open = ref(false)
    const onPick = (v) => {
      emit('update:modelValue', v)
      emit('change', v)
      open.value = false
    }
    return () => h('div', { class: ['tb-select', { 'tb-select-open': open.value }], style: { width: props.width } }, [
      h('button', {
        class: 'tb-sel-trigger',
        onClick: () => { open.value = !open.value }
      }, [
        h('span', { class: ['tb-sel-value', { 'tb-sel-placeholder': !props.modelValue && props.placeholder }] },
          (props.options.find(o => o.value === props.modelValue) || { label: props.placeholder || '请选择' }).label),
        h('svg', { class: 'tb-sel-arrow', viewBox: '0 0 24 24', width: 12, height: 12 }, [
          h('path', { d: 'M5 9l7 7 7-7', fill: 'none', stroke: 'currentColor', 'stroke-width': '1.6', 'stroke-linecap': 'round', 'stroke-linejoin': 'round' })
        ])
      ]),
      open.value ? h('div', { class: 'tb-sel-menu' },
        props.options.map(o => h('div', {
          class: ['tb-sel-item', { 'tb-sel-item-active': o.value === props.modelValue }],
          onClick: () => onPick(o.value)
        }, o.label))
      ) : null
    ])
  }
}

// 子组件: 颜色块(单色填充)
export const TbColor = {
  name: 'TbColor',
  props: { value: { type: String, default: '#000000' }, title: { type: String, default: '颜色' } },
  emits: ['change'],
  setup(props, { emit }) {
    return () => h('label', { class: 'tb-color', title: props.title }, [
      h('input', {
        type: 'color',
        value: props.value || '#000000',
        onChange: (e) => emit('change', e.target.value)
      }),
      h('svg', { class: 'tb-color-icon', viewBox: '0 0 24 24', width: 16, height: 16 }, [
        h('path', { d: 'M19 11h-1V3a1 1 0 0 0-1-1H7a1 1 0 0 0-1 1v8H5a3 3 0 0 0 0 6h2v3a1 1 0 0 0 1 1h8a1 1 0 0 0 1-1v-3h2a3 3 0 0 0 0-6zM8 4h8v7H8V4z', fill: 'currentColor' })
      ])
    ])
  }
}
</script>

<template>
  <div class="editor-toolbar" :class="['et-' + size]">
    <div class="et-row">
      <slot name="start" />
      <span class="et-fill" />
      <slot name="end" />
    </div>
    <slot name="second-row" />
  </div>
</template>

<style scoped>
.editor-toolbar {
  display: flex;
  flex-direction: column;
  background: #fff;
  border-bottom: 1px solid var(--c-border);
  user-select: none;
}
.et-row { display: flex; align-items: center; gap: 2px; padding: 2px 8px; min-height: 36px; flex-wrap: wrap; }
.editor-toolbar :deep(.tb-group) { display: inline-flex; align-items: center; gap: 1px; }
.editor-toolbar :deep(.tb-sep) { width: 1px; height: 18px; background: var(--c-border); margin: 0 4px; }
.editor-toolbar :deep(.tb-fill) { flex: 1; }

.editor-toolbar :deep(.tb-btn) {
  display: inline-flex; align-items: center; justify-content: center;
  min-width: 26px; height: 26px; padding: 0 5px; margin: 0;
  background: transparent; color: var(--c-text-2);
  border: 1px solid transparent; border-radius: 4px;
  cursor: pointer; font: inherit; line-height: 1;
  transition: background .12s, color .12s;
}
.editor-toolbar :deep(.tb-btn:hover) { background: rgba(0,0,0,.06); color: var(--c-text-1); }
.editor-toolbar :deep(.tb-btn-active) { background: var(--c-primary-bg, #e8f0fe); color: var(--c-primary, #1a73e8); }
.editor-toolbar :deep(.tb-btn-disabled) { opacity: .35; cursor: not-allowed; }
.editor-toolbar :deep(.tb-btn-disabled:hover) { background: transparent; color: var(--c-text-2); }
.editor-toolbar :deep(.tb-btn svg) { display: block; }

.editor-toolbar :deep(.tb-select) {
  position: relative; display: inline-block; height: 26px;
  border: 1px solid var(--c-border); border-radius: 4px; background: #fff;
}
.editor-toolbar :deep(.tb-select-open) { border-color: var(--c-primary, #1a73e8); }
.editor-toolbar :deep(.tb-sel-trigger) {
  display: flex; align-items: center; justify-content: space-between;
  gap: 4px; width: 100%; height: 100%;
  padding: 0 6px; background: transparent; border: 0; cursor: pointer;
  color: var(--c-text-1); font: inherit;
}
.editor-toolbar :deep(.tb-sel-value) { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; text-align: left; }
.editor-toolbar :deep(.tb-sel-placeholder) { color: var(--c-text-4, #999); }
.editor-toolbar :deep(.tb-sel-arrow) { color: var(--c-text-3, #666); flex: none; }

.editor-toolbar :deep(.tb-sel-menu) {
  position: absolute; left: 0; top: 100%;
  min-width: 100%; max-height: 280px; overflow-y: auto;
  background: #fff; border: 1px solid var(--c-border); border-radius: 4px;
  box-shadow: 0 4px 16px rgba(0,0,0,.10); z-index: 50; padding: 4px 0;
}
.editor-toolbar :deep(.tb-sel-item) {
  padding: 4px 12px; cursor: pointer; color: var(--c-text-1);
  font-size: 13px; line-height: 1.5;
}
.editor-toolbar :deep(.tb-sel-item:hover) { background: rgba(0,0,0,.05); }
.editor-toolbar :deep(.tb-sel-item-active) { background: var(--c-primary-bg, #e8f0fe); color: var(--c-primary, #1a73e8); }

.editor-toolbar :deep(.tb-color) {
  display: inline-flex; align-items: center; justify-content: center;
  width: 26px; height: 26px; cursor: pointer; color: var(--c-text-2);
  border-radius: 4px;
}
.editor-toolbar :deep(.tb-color:hover) { background: rgba(0,0,0,.06); color: var(--c-text-1); }
.editor-toolbar :deep(.tb-color input) { position: absolute; opacity: 0; width: 0; height: 0; }
.editor-toolbar :deep(.tb-color-icon) { display: block; }

/* 紧凑尺寸 */
.editor-toolbar.et-sm :deep(.tb-btn) { min-width: 22px; height: 22px; }
.editor-toolbar.et-md { font-size: 13px; }
.editor-toolbar.et-lg { font-size: 14px; }
</style>
