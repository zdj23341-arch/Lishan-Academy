<script setup lang="ts">
import UiIcon from './ui/UiIcon.vue';
import BrandMark from './ui/BrandMark.vue';
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { hasPermission } from '@jxsc/shared';
import { useSessionStore } from '../stores/session';
import { environment } from '../api/environment';
import DevCommerceScenarios from './DevCommerceScenarios.vue';

const store = useSessionStore();
const route = useRoute();
const router = useRouter();
const loggingOut = ref(false);
const logoutError = ref('');
const collapsed = ref(false);
const openedGroups = ref<string[]>([]);
const closedCurrentGroup = ref('');
const activeGroup = computed(
  () => navGroups.find((group) => group.items.some((item) => isActive(item.path)))?.title,
);
function groupOpen(title: string) {
  return (
    openedGroups.value.includes(title) ||
    (activeGroup.value === title && closedCurrentGroup.value !== title)
  );
}
function toggleGroup(title: string) {
  if (groupOpen(title)) {
    openedGroups.value = openedGroups.value.filter((value) => value !== title);
    if (activeGroup.value === title) closedCurrentGroup.value = title;
  } else {
    openedGroups.value.push(title);
    closedCurrentGroup.value = '';
  }
}
const detailSuffix = computed(() => {
  const item = navGroups.flatMap((group) => group.items).find((item) => isActive(item.path));
  return item && item.path !== '/' && route.path !== item.path ? ' / 详情' : '';
});

interface NavItem {
  path: string;
  label: string;
  icon: string;
  permission?: string;
}

interface NavGroup {
  title: string;
  items: NavItem[];
}

const navGroups: NavGroup[] = [
  {
    title: '总览',
    items: [{ path: '/', label: '工作台', icon: 'dashboard' }],
  },
  {
    title: '内容管理',
    items: [
      { path: '/content/home', label: '首页装修', icon: 'home', permission: 'CONTENT_EDIT' },
      { path: '/content/products', label: '商品管理', icon: 'goods', permission: 'CONTENT_EDIT' },
      {
        path: '/content/categories',
        label: '分类管理',
        icon: 'category',
        permission: 'CONTENT_EDIT',
      },
      {
        path: '/content/articles',
        label: '图文管理',
        icon: 'article',
        permission: 'CONTENT_EDIT',
      },
      { path: '/content/assets', label: '素材资源', icon: 'folder', permission: 'CONTENT_EDIT' },
    ],
  },
  {
    title: '教学内容',
    items: [
      { path: '/learning/courses', label: '课程管理', icon: 'video', permission: 'CONTENT_EDIT' },
      { path: '/learning/papers', label: '试卷管理', icon: 'paper', permission: 'CONTENT_EDIT' },
      {
        path: '/learning/membership/plans',
        label: '会员套餐',
        icon: 'vip',
        permission: 'CONTENT_EDIT',
      },
      {
        path: '/learning/membership/records',
        label: '会员记录',
        icon: 'record',
        permission: 'ORDER_OPERATE',
      },
    ],
  },
  {
    title: '交易履约',
    items: [
      { path: '/commerce/orders', label: '订单管理', icon: 'order', permission: 'ORDER_OPERATE' },
      {
        path: '/order/fulfillment',
        label: '发货管理',
        icon: 'truck',
        permission: 'ORDER_OPERATE',
      },
      {
        path: '/commerce/inventory',
        label: '库存管理',
        icon: 'inventory',
        permission: 'CONTENT_EDIT|ORDER_OPERATE',
      },
      {
        path: '/commerce/aftersales',
        label: '售后管理',
        icon: 'service',
        permission: 'ORDER_OPERATE|FINANCE_OPERATE',
      },
    ],
  },
  {
    title: '分销结算',
    items: [
      {
        path: '/distribution/distributors',
        label: '分销员',
        icon: 'team',
        permission: 'DISTRIBUTION_OPERATE',
      },
      {
        path: '/distribution/commissions',
        label: '佣金流水',
        icon: 'money',
        permission: 'DISTRIBUTION_OPERATE|FINANCE_OPERATE',
      },
      {
        path: '/distribution/withdrawals',
        label: '自动提现',
        icon: 'wallet',
        permission: 'FINANCE_OPERATE',
      },
    ],
  },
  {
    title: '经营管理',
    items: [
      { path: '/analytics', label: '经营数据', icon: 'chart', permission: 'ANALYTICS_READ' },
      { path: '/operations/users', label: '用户管理', icon: 'users', permission: 'USER_MANAGE' },
      {
        path: '/operations/settings',
        label: '系统设置',
        icon: 'setting',
        permission: 'SETTINGS_MANAGE',
      },
      {
        path: '/operations/audit-logs',
        label: '审计日志',
        icon: 'audit',
        permission: 'AUDIT_READ',
      },
      {
        path: '/operations/exports',
        label: '数据导出',
        icon: 'export',
        permission: 'EXPORT_READ',
      },
    ],
  },
];

function checkPermission(permission?: string): boolean {
  if (!permission) return true;
  return permission.split('|').some((code) => hasPermission(store.current?.permissions, code));
}

const visibleGroups = computed(() =>
  navGroups
    .map((group) => ({
      ...group,
      items: group.items.filter((item) => checkPermission(item.permission)),
    }))
    .filter((group) => group.items.length > 0),
);

const currentPath = computed(() => route.path);

function isActive(path: string): boolean {
  if (path === '/') return currentPath.value === '/';
  return currentPath.value === path || currentPath.value.startsWith(`${path}/`);
}

watch(
  () => route.path,
  () => {
    closedCurrentGroup.value = '';
  },
);

const pageTitle = computed(() => {
  for (const group of navGroups) {
    for (const item of group.items) {
      if (isActive(item.path)) return item.label;
    }
  }
  return '工作台';
});

async function logout() {
  if (loggingOut.value) return;
  loggingOut.value = true;
  logoutError.value = '';
  try {
    await store.logout();
  } catch {
    logoutError.value = '会话已退出，请重新登录';
  } finally {
    loggingOut.value = false;
    await router.replace('/login');
  }
}
</script>

<template>
  <div class="admin-shell" :class="{ 'sidebar-collapsed': collapsed }">
    <header class="topbar">
      <BrandMark subtitle="运营管理中心" />
      <div class="account">
        <span v-if="environment.provider === 'mock'" class="ls-badge ls-badge--warning"
          >演示数据</span
        ><span class="user-avatar">{{
          (store.current?.admin?.loginName || 'A').charAt(0).toUpperCase()
        }}</span
        ><span>{{
          store.current?.admin?.loginName || store.current?.user?.nickname || '管理员'
        }}</span
        ><button class="ls-button ls-button--link" :disabled="loggingOut" @click="logout">
          {{ loggingOut ? '退出中…' : '退出登录' }}
        </button>
      </div>
    </header>
    <aside class="sidebar">
      <nav class="sidebar-nav" aria-label="后台业务导航">
        <section v-for="group in visibleGroups" :key="group.title" class="nav-group">
          <button
            v-if="!collapsed"
            class="nav-group-title"
            :aria-expanded="groupOpen(group.title)"
            @click="toggleGroup(group.title)"
          >
            {{ group.title
            }}<UiIcon
              :name="!groupOpen(group.title) ? 'chevron-right' : 'chevron-left'"
              :size="13"
            />
          </button>
          <div v-show="collapsed || groupOpen(group.title)">
            <router-link
              v-for="item in group.items"
              :key="item.path"
              :to="item.path"
              class="nav-item"
              :class="{ active: isActive(item.path) }"
              :aria-current="isActive(item.path) ? 'page' : undefined"
              :title="item.label"
              ><UiIcon
                :name="item.icon"
                :tone="isActive(item.path) ? 'blue' : 'neutral'"
                :size="18"
              /><span v-if="!collapsed">{{ item.label }}</span></router-link
            >
          </div>
        </section>
      </nav>
      <button
        class="collapse-btn"
        :aria-label="collapsed ? '展开导航' : '收起导航'"
        :aria-expanded="!collapsed"
        @click="collapsed = !collapsed"
      >
        <UiIcon :name="collapsed ? 'chevron-right' : 'chevron-left'" /><span v-if="!collapsed"
          >收起导航</span
        >
      </button>
    </aside>
    <div class="main-wrapper">
      <DevCommerceScenarios />
      <p v-if="logoutError" role="alert">{{ logoutError }}</p>
      <main class="main-content">
        <nav class="breadcrumb" aria-label="当前位置">
          <router-link to="/">运营后台</router-link>
          <span> / {{ activeGroup }} / {{ pageTitle }}{{ detailSuffix }}</span>
        </nav>
        <slot />
      </main>
    </div>
  </div>
</template>
<style lang="scss" scoped>
@import '../styles/admin-tokens.scss';
.admin-shell {
  min-height: 100vh;
  background: $color-bg-page;
  padding-top: 65px;
}
.topbar {
  height: 65px;
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 100;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 24px;
  background: white;
  border-bottom: 1px solid $color-border;
}
.account {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 13px;
}
.user-avatar {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: $brand-primary-light;
  color: $brand-primary;
}
.sidebar {
  width: $sidebar-width;
  position: fixed;
  top: 65px;
  left: 0;
  bottom: 0;
  background: white;
  border-right: 1px solid $color-border;
  display: flex;
  flex-direction: column;
  padding: 12px 10px;
  z-index: 90;
}
.sidebar-nav {
  overflow-y: auto;
  flex: 1;
}
.nav-group-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  width: 100%;
  padding: 14px 12px 6px;
  color: $color-text-secondary;
  font-size: 12px;
  border: 0;
  background: none;
  cursor: pointer;
}
.nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 39px;
  border-radius: 7px;
  padding: 8px 12px;
  text-decoration: none;
  color: $color-text-secondary;
  font-size: 13px;
}
.nav-item:hover {
  background: $color-bg-page;
}
.nav-item.active {
  background: $brand-primary-light;
  color: $brand-primary;
  font-weight: 600;
}
.collapse-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  border: 0;
  border-top: 1px solid $color-border;
  background: white;
  color: $color-text-secondary;
  min-height: 44px;
  cursor: pointer;
}
.main-wrapper {
  margin-left: $sidebar-width;
  min-width: 0;
}
.main-content {
  padding: 22px;
  max-width: 1600px;
  margin: auto;
  min-width: 0;
}
.breadcrumb {
  display: flex;
  gap: 5px;
  font-size: 12px;
  color: $color-text-secondary;
  margin-bottom: 18px;
}
.breadcrumb a {
  color: inherit;
  text-decoration: none;
}
.sidebar-collapsed {
  .sidebar {
    width: 64px;
  }
  .main-wrapper {
    margin-left: 64px;
  }
  .nav-item {
    justify-content: center;
    padding: 10px 0;
  }
}
@media (max-width: 900px) {
  .main-content {
    padding: 16px;
  }
  .topbar {
    padding: 0 16px;
  }
}
@media (max-width: 600px) {
  .sidebar {
    width: 64px;
  }
  .main-wrapper {
    margin-left: 64px;
  }
  .nav-group-title,
  .nav-item span {
    display: none;
  }
  .nav-item {
    justify-content: center;
    padding: 10px 0;
  }
  .sidebar-nav .nav-group > div {
    display: block !important;
  }
  .account > span:not(.user-avatar) {
    display: none;
  }
  .collapse-btn span {
    display: none;
  }
  .main-content {
    padding: 12px;
  }
}
</style>
