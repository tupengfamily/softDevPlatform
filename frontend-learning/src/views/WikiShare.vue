<script setup>
// Wiki 文章分享页(公开只读,无需登录): 凭分享令牌查看
import { onMounted, ref, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { getSharedArticle } from '../api/personal'
import { renderContent } from '../utils/markdown'

const route = useRoute()
const router = useRouter()
const { t } = useI18n()
const article = ref(null)
const loading = ref(true)
const error = ref('')

const rendered = computed(() => renderContent(article.value?.content || '', article.value?.contentType))
const fmtTime = (t) => (t || '').replace('T', ' ').slice(0, 16)

const needLogin = ref(false)

onMounted(async () => {
  try {
    const res = await getSharedArticle(route.params.token)
    article.value = res.data
  } catch (e) {
    // 分享设置了"需要登录": 后端返回 401, 给出登录入口而不是泛化错误
    if (e?.response?.status === 401) {
      needLogin.value = true
    } else {
      error.value = t('wikiShare.error')
    }
  } finally {
    loading.value = false
  }
})

const goLogin = () => {
  router.push({ path: '/login', query: { redirect: route.fullPath } })
}
</script>

<template>
  <div class="share-page">
    <div class="share-card" v-loading="loading">
      <template v-if="article">
        <div class="badge">{{ $t('wikiShare.badge') }}</div>
        <h1>{{ article.title }}</h1>
        <div class="meta">
          <span>{{ $t('wikiShare.updatedAt', { time: fmtTime(article.updateTime) }) }}</span>
          <span>· {{ $t('wikiShare.viewCount', { n: article.viewCount }) }}</span>
        </div>
        <div class="body rich-body" v-html="rendered"></div>
      </template>
      <el-result v-else-if="needLogin" icon="warning" :title="$t('wikiShare.needLogin')" :sub-title="$t('wikiShare.needLoginTip')">
        <template #extra>
          <el-button type="primary" @click="goLogin">{{ $t('wikiShare.goLogin') }}</el-button>
        </template>
      </el-result>
      <el-result v-else-if="!loading" icon="warning" :title="error || $t('wikiShare.notFound')" :sub-title="$t('wikiShare.notFoundTip')">
      </el-result>
    </div>
  </div>
</template>

<style scoped>
.share-page { min-height: 100vh; background: #f7f8fb; padding: 32px 16px; }
.share-card {
  max-width: 820px; margin: 0 auto; background: #fff;
  border: 1px solid var(--c-border); border-radius: var(--r-l);
  padding: 34px 42px; box-shadow: 0 4px 16px rgba(20,30,60,.06);
}
.badge { display: inline-block; padding: 4px 12px; background: var(--c-primary-bg); color: var(--c-primary); border-radius: 999px; font-size: var(--fs-xs); margin-bottom: 14px; }
.share-card h1 { margin: 0 0 10px; font-size: 26px; line-height: 1.35; color: var(--c-text-1); }
.meta { color: var(--c-text-3); font-size: var(--fs-sm); display: flex; gap: 8px; padding-bottom: 16px; border-bottom: 1px solid #f1f3f8; }
.body { margin-top: 20px; font-size: var(--fs-base); line-height: 1.85; color: var(--c-text-1); }
.body :deep(p) { margin: 8px 0; }
.body :deep(h1), .body :deep(h2), .body :deep(h3) { font-size: var(--fs-lg); margin: 22px 0 10px; }
.body :deep(pre) { background: #0f172a; color: #e2e8f0; padding: 12px 14px; border-radius: 8px; overflow-x: auto; font-size: 13px; }
.body :deep(code) { background: rgba(100,116,139,.12); padding: 1px 5px; border-radius: 4px; font-size: .9em; }
.body :deep(pre code) { background: transparent; padding: 0; }
.body :deep(img) { max-width: 100%; }
.body :deep(blockquote) { border-left: 3px solid var(--c-border-strong); margin: 10px 0; padding: 2px 14px; color: var(--c-text-2); }
.body :deep(table) { border-collapse: collapse; width: 100%; margin: 12px 0; }
.body :deep(th), .body :deep(td) { border: 1px solid var(--c-border); padding: 8px 12px; text-align: left; }
</style>
