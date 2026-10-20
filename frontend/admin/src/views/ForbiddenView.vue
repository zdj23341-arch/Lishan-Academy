<template>
  <main class="ls-admin-page ls-denied">
    <span class="ls-denied-symbol" aria-hidden="true">
      <UiIcon name="lock-keyhole" tone="blue" :size="24" />
    </span>
    <h1>没有操作权限</h1>
    <p class="ls-denied-description">当前账号未获得该功能权限。</p>
    <p class="ls-denied-help">如需访问，请联系系统管理员开通相应权限。</p>
    <router-link to="/" class="ls-button ls-button--primary">返回后台</router-link>
  </main>
</template>
<script setup lang="ts">
import UiIcon from '../components/ui/UiIcon.vue';
</script>
<style scoped>
.ls-denied {
  display: flex;
  flex-direction: column;
  align-items: center;
  margin: 64px auto;
  padding: 36px 28px;
  max-width: 480px;
  background: white;
  border: 1px solid #e5ebf3;
  border-radius: 12px;
  text-align: center;
}
.ls-denied-symbol {
  display: grid;
  place-items: center;
  width: 56px;
  height: 56px;
  border-radius: 50%;
  background: #edf3ff;
}
.ls-denied h1 {
  margin: 18px 0 8px;
  color: #18283e;
  font-size: 20px;
}
.ls-denied-description {
  margin: 0;
  color: #324057;
  font-size: 14px;
}
.ls-denied-help {
  margin: 8px 0 16px;
  color: #617088;
  font-size: 13px;
}
.ls-denied .ls-button--primary {
  color: white;
}
@media (max-width: 600px) {
  .ls-denied {
    margin: 32px 0;
    padding: 28px 20px;
  }
}
</style>
