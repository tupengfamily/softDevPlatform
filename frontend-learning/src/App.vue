<script setup>
// 根组件 — el-config-provider 让 Element Plus 内置文案(分页/日期等)跟随界面语言
import { computed } from 'vue'
import { RouterView } from 'vue-router'
import { useI18n } from 'vue-i18n'
import zhCn from 'element-plus/dist/locale/zh-cn.mjs'
import en from 'element-plus/dist/locale/en.mjs'

const { locale } = useI18n()
const epLocale = computed(() => (locale.value === 'en-US' ? en : zhCn))
</script>

<template>
  <el-config-provider :locale="epLocale">
    <RouterView />
  </el-config-provider>
</template>

<style>
/* 全局样式由 main.css 控制 */
</style>
