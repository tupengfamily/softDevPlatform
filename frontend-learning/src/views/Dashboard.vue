<script setup>
/**
 * 仪表盘
 * <p>
 * - 4 个统计卡片
 * - 自绘柱状图(最近 7 天注册/操作)
 * - 用户状态分布
 */
import { ref, onMounted, computed } from 'vue'
import { getSummary, getRegistrations, getOperations, getStatusDistribution } from '../api/dashboard'

const summary = ref({})
const registrations = ref([])
const operations = ref([])
const statusDist = ref([])
const loading = ref(false)

const regMax = computed(() => Math.max(1, ...registrations.value.map(x => x.count)))
const opMax = computed(() => Math.max(1, ...operations.value.map(x => x.count)))

const formatShortDate = (iso) => iso.slice(5) // MM-DD

onMounted(async () => {
  loading.value = true
  try {
    const [s, r, o, d] = await Promise.all([
      getSummary(), getRegistrations(), getOperations(), getStatusDistribution()
    ])
    summary.value = s.data || {}
    registrations.value = r.data || []
    operations.value = o.data || []
    statusDist.value = d.data || []
  } catch (_) {} finally { loading.value = false }
})
</script>

<template>
  <div class="page-container">
    <div class="page-header">
      <div class="page-title">{{ $t('dashboard.title') }}</div>
      <div class="page-subtitle">Spring Boot 3 + Vue 3 + Element Plus + SQLite + JWT</div>
    </div>

    <!-- 顶部统计卡片 -->
    <el-row :gutter="16" v-loading="loading">
      <el-col :span="6" :xs="12">
        <el-card shadow="hover" class="stat-card stat-blue">
          <div class="stat-label">{{ $t('dashboard.stat.totalUsers') }}</div>
          <div class="stat-num">{{ summary.totalUsers ?? 0 }}</div>
        </el-card>
      </el-col>
      <el-col :span="6" :xs="12">
        <el-card shadow="hover" class="stat-card stat-green">
          <div class="stat-label">{{ $t('dashboard.stat.activeUsers') }}</div>
          <div class="stat-num">{{ summary.activeUsers ?? 0 }}</div>
        </el-card>
      </el-col>
      <el-col :span="6" :xs="12">
        <el-card shadow="hover" class="stat-card stat-orange">
          <div class="stat-label">{{ $t('dashboard.stat.rolesPermissions') }}</div>
          <div class="stat-num">{{ summary.totalRoles ?? 0 }} / {{ summary.totalPermissions ?? 0 }}</div>
        </el-card>
      </el-col>
      <el-col :span="6" :xs="12">
        <el-card shadow="hover" class="stat-card stat-purple">
          <div class="stat-label">{{ $t('dashboard.stat.ops') }}</div>
          <div class="stat-num">{{ summary.todayOps ?? 0 }} / {{ summary.totalOps ?? 0 }}</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 7 日趋势 -->
    <el-row :gutter="16" style="margin-top:16px;">
      <el-col :span="12" :xs="24">
        <el-card shadow="never">
          <template #header>
            <strong>{{ $t('dashboard.chart.newUsers') }}</strong>
          </template>
          <div class="chart">
            <div v-for="item in registrations" :key="item.date" class="chart-col">
              <div class="chart-value">{{ item.count }}</div>
              <div class="chart-bar" :style="{ height: (item.count / regMax * 100) + '%' }"></div>
              <div class="chart-label">{{ formatShortDate(item.date) }}</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="12" :xs="24">
        <el-card shadow="never">
          <template #header>
            <strong>{{ $t('dashboard.chart.operations') }}</strong>
          </template>
          <div class="chart">
            <div v-for="item in operations" :key="item.date" class="chart-col">
              <div class="chart-value">{{ item.count }}</div>
              <div class="chart-bar bar-purple" :style="{ height: (item.count / opMax * 100) + '%' }"></div>
              <div class="chart-label">{{ formatShortDate(item.date) }}</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 用户状态分布 -->
    <el-card shadow="never" style="margin-top:16px;">
      <template #header>
        <strong>{{ $t('dashboard.statusDist.title') }}</strong>
      </template>
      <el-table :data="statusDist" stripe>
        <el-table-column :label="$t('dashboard.statusDist.status')">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'danger'">
              {{ row.status === 1 ? $t('dashboard.status.enabled') : $t('dashboard.status.disabled') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="count" :label="$t('dashboard.statusDist.count')" />
      </el-table>
    </el-card>
  </div>
</template>

<style scoped>
.stat-card { text-align: center; }
.stat-card .stat-label { font-size: 13px; color: #6b7280; }
.stat-card .stat-num { font-size: 32px; font-weight: 600; color: #303133; padding: 8px 0; }
.stat-blue   { border-top: 3px solid #2563eb; }
.stat-green  { border-top: 3px solid #67c23a; }
.stat-orange { border-top: 3px solid #e6a23c; }
.stat-purple { border-top: 3px solid #8e44ad; }

.chart {
  display: flex; align-items: flex-end; justify-content: space-around;
  height: 220px; padding: 16px 0;
}
.chart-col {
  display: flex; flex-direction: column; align-items: center; justify-content: flex-end;
  flex: 1; height: 100%; position: relative;
}
.chart-value { font-size: 12px; color: #606266; margin-bottom: 6px; }
.chart-bar {
  width: 70%; max-width: 36px; min-height: 2px;
  background: linear-gradient(180deg, #2563eb, #6692f1); border-radius: var(--r-s) var(--r-s) 0 0;
  transition: height 0.4s;
}
.chart-bar.bar-purple {
  background: linear-gradient(180deg, #8e44ad, #b07cc6);
}
.chart-label { font-size: 12px; color: #6b7280; margin-top: 6px; }
</style>