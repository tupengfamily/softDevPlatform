<script setup>
// 消息中心 — 站内消息列表(系统通知 / 回复通知 / 任务通知)
// 支持类型筛选、单条/全部已读、点击跳转关联内容;管理员可发布系统通知
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { pageNotifications, markRead, markAllRead, sendSystemNotice } from '../api/notifications'
import { useUserStore } from '../store/user'
import { useNotificationStore } from '../store/notification'
import { useDictStore } from '../store/dict'

const router = useRouter()
const { t } = useI18n()
const userStore = useUserStore()
const notifStore = useNotificationStore()
const dictStore = useDictStore()
const isAdmin = computed(() => userStore.roles.includes('ADMIN'))

const loading = ref(false)
const items = ref([])
const total = ref(0)
const page = ref(1)
const size = ref(10)
const typeFilter = ref('')        // ''=全部 SYSTEM/REPLY/TASK

const typeTag = { SYSTEM: 'primary', REPLY: 'warning', TASK: 'success', COLLAB_REQUEST: 'danger', COLLAB_RESULT: 'info' }
// 类型文案优先取数据字典 notification_type,后端 typeLabel 兜底
const typeText = (n) => dictStore.label('notification_type', n.type, n.typeLabel || n.type)
// 类型筛选项: 字典 notification_type 驱动,只保留已知编码,空则兜底
const typeOptions = computed(() => {
  const d = dictStore.options('notification_type').filter(o => typeTag[o.value])
  return d.length ? d : [
    { value: 'SYSTEM', label: t('notifications.type.SYSTEM') },
    { value: 'REPLY', label: t('notifications.type.REPLY') },
    { value: 'TASK', label: t('notifications.type.TASK') }
  ]
})

const load = async () => {
  loading.value = true
  try {
    const res = await pageNotifications({ type: typeFilter.value || undefined, page: page.value, size: size.value })
    items.value = res.data?.records || []
    total.value = res.data?.total || 0
  } catch (e) { /* 已统一弹错 */ } finally {
    loading.value = false
  }
}
const onTypeChange = () => { page.value = 1; load() }
const onSizeChange = (s) => { size.value = s; page.value = 1; load() }

const fmtTime = (t) => (t || '').replace('T', ' ').slice(0, 16)

// 点击消息: 标记已读 + 跳转关联内容(帖子/课时)
const openItem = async (n) => {
  if (!n.read) {
    try {
      await markRead(n.id)
      n.read = true
      notifStore.decUnread()
    } catch (e) { /* ignore */ }
  }
  if (n.relatedType === 'POST' && n.relatedId) router.push(`/forum/${n.relatedId}`)
  else if (n.relatedType === 'LESSON' && n.relatedId) router.push(`/learning/lesson/${n.relatedId}`)
  else if (n.relatedType === 'WIKI_DIR' || n.relatedType === 'WIKI_ART') {
    // 协作申请/结果 → 跳到 Wiki 页面, 用 tab 参数让目标面板聚焦
    const tab = n.type === 'COLLAB_REQUEST' ? 'requests' : 'mine'
    router.push({ path: '/wiki', query: { tab, dir: n.relatedId, art: n.relatedId } })
  }
}
const markOne = async (n) => {
  try {
    await markRead(n.id)
    n.read = true
    notifStore.decUnread()
    ElMessage.success(t('notifications.markedRead'))
  } catch (e) { /* 已统一弹错 */ }
}
const markAll = async () => {
  try {
    await markAllRead()
    items.value.forEach(n => { n.read = true })
    notifStore.clearUnread()
    ElMessage.success(t('notifications.markedAllRead'))
  } catch (e) { /* 已统一弹错 */ }
}

// ---------- 管理员: 发布系统通知 ----------
const sendVisible = ref(false)
const sending = ref(false)
const sendForm = reactive({ title: '', content: '' })
const submitNotice = async () => {
  if (!sendForm.title.trim()) { ElMessage.warning(t('notifications.needTitle')); return }
  if (!sendForm.content.trim()) { ElMessage.warning(t('notifications.needContent')); return }
  sending.value = true
  try {
    const res = await sendSystemNotice({ ...sendForm })
    ElMessage.success(res.message || t('notifications.publishSuccess'))
    sendVisible.value = false
    sendForm.title = ''
    sendForm.content = ''
    await load()
    notifStore.fetchUnread()   // 管理员自己也是接收者,刷新铃铛
  } catch (e) { /* 已统一弹错 */ } finally {
    sending.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="page" v-loading="loading">
    <div class="hero">
      <div>
        <h1>{{ $t('notifications.title') }}</h1>
        <p>{{ $t('notifications.subtitle') }}</p>
      </div>
      <div class="hero-actions">
        <el-button v-if="isAdmin" type="primary" plain @click="sendVisible = true">
          <el-icon><Bell /></el-icon> {{ $t('notifications.sendNotice') }}
        </el-button>
        <el-button @click="markAll"><el-icon><CircleCheck /></el-icon> {{ $t('notifications.markAllRead') }}</el-button>
      </div>
    </div>

    <div class="filters">
      <el-radio-group v-model="typeFilter" @change="onTypeChange">
        <el-radio-button value="">{{ $t('common.all') }}</el-radio-button>
        <el-radio-button v-for="o in typeOptions" :key="o.value" :value="o.value">{{ o.label }}</el-radio-button>
      </el-radio-group>
    </div>

    <div class="msg-list">
      <div
        v-for="n in items"
        :key="n.id"
        class="msg-item"
        :class="{ unread: !n.read, clickable: n.relatedType }"
        role="button"
        tabindex="0"
        @click="openItem(n)"
        @keydown.enter="openItem(n)"
      >
        <div class="msg-main">
          <div class="msg-title">
            <el-tag size="small" effect="plain" :type="typeTag[n.type] || 'info'">{{ typeText(n) }}</el-tag>
            <b>{{ n.title }}</b>
            <span v-if="!n.read" class="unread-dot">{{ $t('notifications.unread') }}</span>
          </div>
          <div class="msg-content">{{ n.content }}</div>
          <div class="msg-meta">{{ fmtTime(n.createTime) }}</div>
        </div>
        <el-button
          v-if="!n.read"
          size="small"
          text
          class="mark-btn"
          @click.stop="markOne(n)"
        >{{ $t('notifications.markRead') }}</el-button>
      </div>
      <el-empty v-if="!items.length && !loading" :description="$t('notifications.empty')" />
    </div>

    <div class="pager" v-if="total > size">
      <el-pagination
        background
        layout="total, prev, pager, next, sizes"
        :total="total"
        v-model:current-page="page"
        v-model:page-size="size"
        :page-sizes="[10, 20, 50]"
        @current-change="load"
        @size-change="onSizeChange"
      />
    </div>

    <!-- 发布系统通知 -->
    <el-dialog v-model="sendVisible" :title="$t('notifications.sendNotice')" width="560px" destroy-on-close>
      <el-form label-position="top">
        <el-form-item :label="$t('notifications.titleLabel')" required>
          <el-input v-model="sendForm.title" maxlength="200" :placeholder="$t('notifications.titlePlaceholder')" />
        </el-form-item>
        <el-form-item :label="$t('notifications.contentLabel')" required>
          <el-input v-model="sendForm.content" type="textarea" :rows="5" maxlength="2000" :placeholder="$t('notifications.contentPlaceholder')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="sendVisible = false">{{ $t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="sending" @click="submitNotice">{{ $t('notifications.publish') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page { padding: 24px 32px 40px; }
.hero {
  display: flex; align-items: center; justify-content: space-between;
  background: linear-gradient(120deg, #eef4ff 0%, #f6f1ff 60%, #fff 100%);
  border: 1px solid #e8edf9; border-radius: var(--r-l); padding: 22px 26px; margin-bottom: 16px;
}
.hero h1 { margin: 0 0 6px; font-size: var(--fs-xl); }
.hero p { margin: 0; color: #6b7280; font-size: var(--fs-base); }
.hero-actions { display: flex; gap: 10px; }
.filters { margin-bottom: 14px; }
.msg-list { background: #fff; border: 1px solid var(--c-border); border-radius: var(--r-m); overflow: hidden; }
.msg-item {
  display: flex; align-items: center; gap: 12px; padding: 14px 20px;
  border-bottom: 1px solid #f1f3f8; transition: background .12s;
}
.msg-item.clickable { cursor: pointer; }
.msg-item.clickable:hover { background: #f8fafd; }
.msg-item.unread { background: #f6f9ff; }
.msg-main { flex: 1; min-width: 0; }
.msg-title { display: flex; align-items: center; gap: 8px; font-size: var(--fs-base); color: var(--c-text-1); }
.msg-title b { font-weight: 600; }
.unread-dot {
  padding: 1px 8px; border-radius: var(--r-full); font-size: var(--fs-xs);
  background: #fee2e2; color: #dc2626;
}
.msg-content {
  margin-top: 4px; color: var(--c-text-2); font-size: var(--fs-sm);
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
}
.msg-meta { margin-top: 5px; color: #94a3b8; font-size: var(--fs-xs); }
.mark-btn { flex: none; }
.pager { display: flex; justify-content: flex-end; margin-top: 14px; }
</style>
