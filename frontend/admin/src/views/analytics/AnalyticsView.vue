<script setup lang="ts">
import AnalyticsPanel from '../../components/AnalyticsPanel.vue';
</script>
<template>
  <div class="admin-layout">
    <header class="admin-header">
      <div class="header-brand">
        <h1 class="brand-title">经营数据</h1>
        <p class="page-desc">查看真实销售趋势、订单与资金指标</p>
      </div>
      <router-link class="ls-button" to="/">返回工作台</router-link>
    </header>
    <main class="admin-content"><AnalyticsPanel /></main>
  </div>
</template>
