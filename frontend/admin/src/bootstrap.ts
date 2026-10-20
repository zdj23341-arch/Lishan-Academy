import { createApp, type Component } from 'vue';
import { createPinia } from 'pinia';
import ElementPlus from 'element-plus';
import zhCn from 'element-plus/es/locale/lang/zh-cn';
import type { Router } from 'vue-router';

export function createAdminApplication(component: Component, router: Router) {
  return createApp(component).use(createPinia()).use(router).use(ElementPlus, { locale: zhCn });
}
