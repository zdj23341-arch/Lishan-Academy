<script setup lang="ts">
import AnalyticsPanel from '../components/AnalyticsPanel.vue';
import { ref, onMounted } from 'vue';
import { hasPermission, type HomeView } from '@jxsc/shared';
import { client } from '../api/client';
import { useSessionStore } from '../stores/session';
import { useCommercePage } from '../composables/useCommercePage';

const store = useSessionStore();
const home = ref<HomeView | null>(null);
const {
  busy: loading,
  error,
  run,
} = useCommercePage(() => {
  home.value = null;
});

async function load() {
  await run(async (current) => {
    const value = await client.request<HomeView>('/home');
    if (current()) home.value = value;
  });
}

onMounted(load);
</script>

<template>
  <div class="dashboard">
    <div class="page-header">
      <div>
        <h1>工作台</h1>
        <p class="page-desc">掌握经营与内容发布情况</p>
      </div>
      <button class="ls-button ls-button--secondary" :disabled="loading" @click="load">
        刷新发布状态
      </button>
    </div>
    <AnalyticsPanel compact v-if="hasPermission(store.current?.permissions, 'ANALYTICS_READ')" />
    <section v-if="home" class="admin-card">
      <div class="ls-page-header">
        <h2>内容发布概览</h2>
        <router-link
          v-if="hasPermission(store.current?.permissions, 'CONTENT_EDIT')"
          to="/content/home"
          class="ls-button ls-button--soft"
          >首页装修</router-link
        >
      </div>
      <div class="home-status-grid">
        <div>
          <span>轮播横幅</span><strong>{{ home.banners.length }}</strong>
        </div>
        <div>
          <span>快捷入口</span><strong>{{ home.shortcuts.length }}</strong>
        </div>
        <div>
          <span>内容模块</span><strong>{{ home.sections.length }}</strong>
        </div>
      </div>
    </section>
    <p v-if="loading" role="status">正在读取首页发布状态…</p>
    <p v-if="error" role="alert">{{ error }}</p>
  </div>
</template>
<style lang="scss" scoped>
.home-status-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  margin-top: 20px;
}
.home-status-grid span {
  display: block;
  color: #617088;
  font-size: 12px;
}
.home-status-grid strong {
  display: block;
  margin-top: 8px;
  font-size: 24px;
  color: #18283e;
}
@media (max-width: 760px) {
  .home-status-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
