import { createRouter, createWebHashHistory } from 'vue-router';
import { hasPermission } from '@jxsc/shared';
import { session } from '../api/session';
export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    ...[
      { path: '/commerce/orders', component: () => import('../views/commerce/Orders.vue') },
      {
        path: '/commerce/orders/:id',
        component: () => import('../views/commerce/OrderDetail.vue'),
      },
    ].map((route) => ({ ...route, meta: { requiresAuth: true, permission: 'ORDER_OPERATE' } })),
    {
      path: '/commerce/inventory',
      component: () => import('../views/commerce/Inventory.vue'),
      meta: { requiresAuth: true, permissions: ['CONTENT_EDIT', 'ORDER_OPERATE'] },
    },
    {
      path: '/commerce/aftersales',
      component: () => import('../views/commerce/Aftersales.vue'),
      meta: { requiresAuth: true, permissions: ['FINANCE_OPERATE', 'ORDER_OPERATE'] },
    },
    ...[
      { path: '/content/products', component: () => import('../views/content/ProductList.vue') },
      {
        path: '/content/products/:id',
        component: () => import('../views/content/ProductEditor.vue'),
      },
      { path: '/content/categories', component: () => import('../views/content/CategoryList.vue') },
      { path: '/content/articles', component: () => import('../views/content/ArticleList.vue') },
      {
        path: '/content/articles/:id',
        component: () => import('../views/content/ArticleEditor.vue'),
      },
      { path: '/content/assets', component: () => import('../views/content/AssetList.vue') },
      { path: '/learning/courses', component: () => import('../views/learning/Courses.vue') },
      {
        path: '/learning/courses/:id',
        component: () => import('../views/learning/CourseWorkflowEditor.vue'),
      },
      { path: '/learning/papers', component: () => import('../views/learning/Papers.vue') },
      {
        path: '/learning/papers/:id',
        component: () => import('../views/learning/PaperEditor.vue'),
      },
      {
        path: '/learning/membership/plans',
        component: () => import('../views/learning/MembershipPlans.vue'),
      },
    ].map((route) => ({ ...route, meta: { requiresAuth: true, permission: 'CONTENT_EDIT' } })),
    {
      path: '/learning/membership/records',
      component: () => import('../views/learning/MembershipRecords.vue'),
      meta: { requiresAuth: true, permission: 'ORDER_OPERATE' },
    },
    { path: '/', component: () => import('../views/HomeView.vue'), meta: { requiresAuth: true } },
    { path: '/login', component: () => import('../views/LoginView.vue') },
    {
      path: '/content/home',
      component: () => import('../views/content/HomeEditor.vue'),
      meta: { requiresAuth: true, permission: 'CONTENT_EDIT' },
    },
    {
      path: '/order/fulfillment',
      component: () => import('../views/order/OrderFulfillment.vue'),
      meta: { requiresAuth: true, permission: 'ORDER_OPERATE' },
    },
    {
      path: '/analytics',
      component: () => import('../views/analytics/AnalyticsView.vue'),
      meta: { requiresAuth: true, permission: 'ANALYTICS_READ' },
    },
    {
      path: '/distribution/distributors',
      component: () => import('../views/distribution/DistributorList.vue'),
      meta: { requiresAuth: true, permission: 'DISTRIBUTION_OPERATE' },
    },
    {
      path: '/distribution/commissions',
      component: () => import('../views/distribution/CommissionList.vue'),
      meta: {
        requiresAuth: true,
        permissions: ['DISTRIBUTION_OPERATE', 'FINANCE_OPERATE'],
      },
    },
    {
      path: '/distribution/withdrawals',
      component: () => import('../views/distribution/WithdrawalList.vue'),
      meta: { requiresAuth: true, permission: 'FINANCE_OPERATE' },
    },
    {
      path: '/operations/users',
      component: () => import('../views/operations/UserList.vue'),
      meta: { requiresAuth: true, permission: 'USER_MANAGE' },
    },
    {
      path: '/operations/settings',
      component: () => import('../views/operations/SettingsView.vue'),
      meta: { requiresAuth: true, permission: 'SETTINGS_MANAGE' },
    },
    {
      path: '/operations/audit-logs',
      component: () => import('../views/operations/AuditLogsView.vue'),
      meta: { requiresAuth: true, permission: 'AUDIT_READ' },
    },
    {
      path: '/operations/exports',
      component: () => import('../views/operations/ExportsView.vue'),
      meta: { requiresAuth: true, permission: 'EXPORT_READ' },
    },
    {
      path: '/forbidden',
      component: () => import('../views/ForbiddenView.vue'),
      meta: { requiresAuth: true },
    },
  ],
});
router.beforeEach((to) => {
  const current = session.get();
  if (
    to.path !== '/login' &&
    (current?.passwordChangeRequired || current?.admin?.passwordChangeRequired)
  ) {
    session.rememberReturnTo(to.fullPath);
    return '/login';
  }
  if (to.meta.requiresAuth && !session.get()) {
    session.rememberReturnTo(to.fullPath);
    return '/login';
  }
  const perms = session.get()?.permissions;
  if (typeof to.meta.permission === 'string' && !hasPermission(perms, to.meta.permission))
    return '/forbidden';
  if (
    Array.isArray(to.meta.permissions) &&
    !to.meta.permissions.some((role) => typeof role === 'string' && hasPermission(perms, role))
  )
    return '/forbidden';
});
