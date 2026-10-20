<script setup lang="ts">
import { useRoute } from 'vue-router';
import { useSessionStore } from './stores/session';
import AdminShell from './components/AdminShell.vue';
const route = useRoute();
const store = useSessionStore();
</script>
<template>
  <AdminShell v-if="store.current && route.path !== '/login'"><RouterView /></AdminShell>
  <RouterView v-else />
</template>
