<script setup>
/**
 * 运营管理 Layout(父路由)
 *
 * <p>结构:
 * <ul>
 *   <li>顶部: 标题 + 6 子页面 el-tabs 二级导航</li>
 *   <li>中部: <router-view> 渲染当前子页面</li>
 * </ul>
 *
 * <p>子页面: OpOverview / OpPages / OpUserJourney / OpErrors / OpPerformance / OpClients
 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()

const tabs = [
  { path: '/operation/overview',    name: 'OpOverview',    label: '运营概览',   icon: 'Odometer' },
  { path: '/operation/pages',       name: 'OpPages',       label: '页面清单',   icon: 'Document' },
  { path: '/operation/user-journey', name: 'OpUserJourney', label: '用户旅程',   icon: 'Connection' },
  { path: '/operation/errors',      name: 'OpErrors',      label: '错误日志',   icon: 'Warning' },
  { path: '/operation/performance', name: 'OpPerformance', label: '性能监控',   icon: 'Histogram' },
  { path: '/operation/clients',     name: 'OpClients',     label: '客户端画像', icon: 'DataLine' }
]

const activeTab = computed(() => route.path)
const goTab = (path) => router.push(path)
</script>

<template>
  <div class="op-layout">
    <header class="op-header">
      <div>
        <h2>{{ $t('nav.operation', '运营管理') }}</h2>
        <p class="op-sub">{{ $t('operation.subtitle', 'PV / UV / 停留时长 / 性能 / 错误 / 用户旅程 / 客户端画像') }}</p>
      </div>
      <el-button-group class="op-shading">
        <el-button v-for="t in tabs" :key="t.path" :type="activeTab === t.path ? 'primary' : 'default'" :icon="t.icon" size="small" @click="goTab(t.path)">
          {{ t.label }}
        </el-button>
      </el-button-group>
    </header>
    <div class="op-content">
      <router-view />
    </div>
  </div>
</template>

<style scoped>
.op-layout { padding: 0 8px 24px; }
.op-header { display: flex; justify-content: space-between; align-items: flex-end; flex-wrap: wrap; gap: 12px; margin-bottom: 16px; }
.op-header h2 { margin: 0 0 4px; font-size: 22px; font-weight: 600; }
.op-sub { margin: 0; color: var(--c-text-3); font-size: 13px; }
.op-shading { display: flex; flex-wrap: wrap; gap: 4px; }
.op-content { min-height: 60vh; }
</style>