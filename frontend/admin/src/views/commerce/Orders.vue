<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { formatFen, formatShanghaiDateTime } from '@jxsc/shared';
import type { AdminOrder } from '../../../../shared/src/commerce/admin-api';
import { commerceApi } from '../../api/commerce';
import { orderLink, useCommercePage } from '../../composables/useCommercePage';

import {
  adminOrderQuery,
  emptyOrderFilters,
  refundFilterOptions,
} from '../../../../shared/src/commerce/order-query';

const rows = ref<AdminOrder[]>([]);
const total = ref(0);
const page = ref(1);

const activeTab = ref('all');
const filters = reactive(emptyOrderFilters());

const tabs = [
  { key: 'all', label: '全部' },
  { key: 'PENDING_PAYMENT', label: '待付款' },
  { key: 'PAID', label: '已支付' },
  { key: 'PENDING_SHIPMENT', label: '待发货' },
  { key: 'SHIPPED', label: '已发货' },
  { key: 'COMPLETED', label: '已完成' },
  { key: 'AFTERSALE', label: '售后中' },
  { key: 'CANCELLED', label: '已关闭' },
];

const { busy, error, run, requireRole } = useCommercePage(() => {
  rows.value = [];
  total.value = 0;
  page.value = 1;
  Object.assign(filters, emptyOrderFilters());
  activeTab.value = 'all';
});

function load(next = 1) {
  return run(async (current) => {
    requireRole('ORDER_OPERATE');
    const result = await commerceApi.orders(adminOrderQuery(filters, activeTab.value, next));
    if (current()) {
      rows.value = result.items;
      total.value = result.total;
      page.value = next;
    }
  });
}

function switchTab(key: string) {
  if (busy.value) return;
  activeTab.value = key;
  load(1);
}

function search() {
  activeTab.value = 'all';
  return load(1);
}
function resetFilters() {
  Object.assign(filters, emptyOrderFilters());
  activeTab.value = 'all';
  return load(1);
}

function getStatusType(status: string): string {
  const map: Record<string, string> = {
    PENDING_PAYMENT: 'warning',
    PAID: 'success',
    COMPLETED: 'success',
    CANCELLED: 'default',
    PENDING_SHIPMENT: 'warning',
    SHIPPED: 'info',
    NOT_REQUIRED: 'default',
    NONE: 'default',
    REQUESTED: 'warning',
    FULL: 'danger',
    PARTIAL: 'warning',
  };
  return map[status] || 'default';
}

function getStatusLabel(status: string): string {
  const map: Record<string, string> = {
    PENDING_PAYMENT: '待付款',
    PAID: '已支付',
    COMPLETED: '已完成',
    CANCELLED: '已关闭',
    PENDING_SHIPMENT: '待发货',
    SHIPPED: '已发货',
    NOT_REQUIRED: '无需履约',
    NONE: '无售后',
    REQUESTED: '售后申请',
    FULL: '已全额退款',
    PARTIAL: '部分退款',
  };
  return map[status] || status;
}

onMounted(() => load());
</script>

<template>
  <div class="orders-page">
    <!-- 页面头部 -->
    <div class="page-header">
      <div class="header-left">
        <h2 class="page-title">订单管理</h2>
        <span class="page-desc">查询全站交易订单、履约发货状态及售后退款流转</span>
      </div>
      <div class="header-actions">
        <button class="btn btn--secondary" :disabled="busy" @click="load(page)">
          <svg
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            stroke-width="2"
            class="btn-icon"
          >
            <path d="M23 4v6h-6M1 20v-6h6" />
            <path d="M3.51 9a9 9 0 0 1 14.85-3.36L23 10M1 14l4.64 4.36A9 9 0 0 0 20.49 15" />
          </svg>
          刷新
        </button>
        <router-link class="btn btn--secondary" to="/operations/exports">数据导出</router-link>
      </div>
    </div>

    <!-- Tab 筛选 -->
    <div class="tab-bar">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        class="tab-btn"
        :class="{ active: activeTab === tab.key }"
        :disabled="busy"
        @click="switchTab(tab.key)"
      >
        {{ tab.label }}
      </button>
    </div>

    <!-- 筛选表单 -->
    <div class="filter-card">
      <form class="filter-form" @submit.prevent="search">
        <div class="filter-grid">
          <div class="filter-item">
            <label class="filter-label">订单号</label>
            <input
              name="orderNo"
              aria-label="订单号"
              v-model="filters.orderNo"
              class="filter-input"
              placeholder="输入订单号"
            />
          </div>
          <div class="filter-item">
            <label class="filter-label">开始时间</label>
            <input
              name="createdFrom"
              aria-label="开始时间"
              v-model="filters.createdFrom"
              type="datetime-local"
              class="filter-input"
            />
          </div>
          <div class="filter-item">
            <label class="filter-label">结束时间</label>
            <input
              name="createdTo"
              aria-label="结束时间"
              v-model="filters.createdTo"
              type="datetime-local"
              class="filter-input"
            />
          </div>
          <label class="filter-item"
            ><span class="filter-label">订单状态</span
            ><select name="orderStatus" class="filter-input" v-model="filters.orderStatus">
              <option value="">全部</option>
              <option value="PENDING_PAYMENT">待支付</option>
              <option value="PAID">已支付</option>
              <option value="COMPLETED">已完成</option>
              <option value="CANCELLED">已取消</option>
            </select></label
          ><label class="filter-item"
            ><span class="filter-label">履约状态</span
            ><select
              name="fulfillmentStatus"
              class="filter-input"
              v-model="filters.fulfillmentStatus"
            >
              <option value="">全部</option>
              <option value="NOT_REQUIRED">无需配送</option>
              <option value="PENDING_SHIPMENT">待发货</option>
              <option value="SHIPPED">已发货</option>
              <option value="RECEIVED">已收货</option>
            </select></label
          ><label class="filter-item"
            ><span class="filter-label">退款状态</span
            ><select name="refundStatus" class="filter-input" v-model="filters.refundStatus">
              <option value="">全部</option>
              <option
                v-for="option in refundFilterOptions"
                :key="option.value"
                :value="option.value"
              >
                {{ option.label }}
              </option>
            </select></label
          >
          <div class="filter-item filter-actions">
            <button type="submit" class="btn btn--primary" :disabled="busy">搜索</button>
            <button type="button" class="btn btn--secondary" :disabled="busy" @click="resetFilters">
              重置
            </button>
          </div>
        </div>
      </form>
    </div>

    <!-- 状态提示 -->
    <div v-if="busy" class="loading-bar">
      <div class="loading-spinner"></div>
      <span>正在加载订单数据...</span>
    </div>
    <div v-if="error" class="error-bar" role="alert">
      <svg
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        stroke-width="2"
        class="error-icon"
      >
        <circle cx="12" cy="12" r="10" />
        <line x1="12" y1="8" x2="12" y2="12" />
        <line x1="12" y1="16" x2="12.01" y2="16" />
      </svg>
      <span>{{ error }}</span>
      <button class="btn btn--small" :disabled="busy" @click="load(page)">重试</button>
    </div>

    <!-- 数据表格 -->
    <div class="table-card">
      <div v-if="!busy && !error && !rows.length" class="empty-state">
        <svg
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          stroke-width="1.5"
          class="empty-icon"
        >
          <path d="M6 2L3 6v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V6l-3-4z" />
          <path d="M3 6h18" />
          <path d="M16 10a4 4 0 0 1-8 0" />
        </svg>
        <p class="empty-text">暂无订单数据</p>
        <p class="empty-desc">当前筛选条件下没有找到匹配的订单</p>
      </div>

      <template v-else>
        <div class="ls-table-scroll">
          <table class="data-table">
            <thead>
              <tr>
                <th class="col-order">订单号</th>
                <th class="col-goods">购买商品</th>
                <th class="col-amount">金额</th>
                <th class="col-status">订单状态</th>
                <th class="col-status">履约状态</th>
                <th class="col-status">退款状态</th>
                <th class="col-time">支付时间</th>
                <th class="col-action">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in rows" :key="row.id" class="table-row">
                <td class="col-order">
                  <router-link :to="orderLink(row.id)" class="order-link">
                    {{ row.orderNo }}
                  </router-link>
                </td>
                <td class="col-goods">
                  <div class="goods-info">
                    <span class="goods-title">{{ row.item?.title || '—' }}</span>
                    <span v-if="row.item?.quantity" class="goods-qty"
                      >×{{ row.item.quantity }}</span
                    >
                  </div>
                </td>
                <td class="col-amount">
                  <span class="amount">¥{{ formatFen(row.payableAmountFen) }}</span>
                </td>
                <td class="col-status">
                  <span class="status-tag" :class="`status-tag--${getStatusType(row.orderStatus)}`">
                    {{ getStatusLabel(row.orderStatus) }}
                  </span>
                </td>
                <td class="col-status">
                  <span
                    class="status-tag"
                    :class="`status-tag--${getStatusType(row.fulfillmentStatus)}`"
                  >
                    {{ getStatusLabel(row.fulfillmentStatus) }}
                  </span>
                </td>
                <td class="col-status">
                  <span
                    class="status-tag"
                    :class="`status-tag--${getStatusType(row.refundStatus)}`"
                  >
                    {{ getStatusLabel(row.refundStatus) }}
                  </span>
                </td>
                <td class="col-time">
                  <span class="time-text">{{ formatShanghaiDateTime(row.paidAt) }}</span>
                </td>
                <td class="col-action">
                  <router-link :to="orderLink(row.id)" class="action-link">详情</router-link>
                  <router-link
                    v-if="
                      row.orderStatus === 'PAID' && row.fulfillmentStatus === 'PENDING_SHIPMENT'
                    "
                    :to="{ path: '/order/fulfillment', query: { id: row.id } }"
                    class="action-link"
                    >发货管理</router-link
                  >
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- 分页 -->
        <div class="pagination-bar">
          <span class="pagination-info">
            共 {{ total }} 条记录，第 {{ page }} / {{ Math.max(1, Math.ceil(total / 20)) }} 页
          </span>
          <div class="pagination-actions">
            <button class="btn btn--small" :disabled="busy || page <= 1" @click="load(page - 1)">
              上一页
            </button>
            <button
              class="btn btn--small"
              :disabled="busy || page * 20 >= total"
              @click="load(page + 1)"
            >
              下一页
            </button>
          </div>
        </div>
      </template>
    </div>
  </div>
</template>
<style lang="scss" scoped>
.ls-row-cover {
  width: 42px;
  height: 54px;
  flex-shrink: 0;
}
.ls-row-cover :deep(.ls-cover-title) {
  font-size: 14px;
  line-height: 1.3;
  letter-spacing: 0;
}
.ls-row-cover :deep(.ls-cover-art) {
  padding: 7px;
}
.ls-row-cover :deep(.ls-cover-caption) {
  display: none;
}
.raw-status {
  font-size: 11px;
  opacity: 0.65;
}
.filter-form {
  width: 100%;
}
.filter-grid {
  width: 100%;
}
.form-row {
  margin: 18px 0;
}
.form-label {
  width: 100%;
}
.editor-fieldset {
  border: 0;
  padding: 0;
}
.editor-card {
  margin-top: 24px;
}
.article-meta {
  display: flex;
  align-items: center;
  gap: 12px;
}
.article-title {
  font-weight: 600;
}
.article-id {
  display: block;
  color: #617088;
  font-size: 12px;
}
.goods-info {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
}
.goods-qty {
  font-size: 12px;
  color: #617088;
}
.amount {
  font-weight: 600;
}
.btn {
  display: inline-flex;
  gap: 8px;
  align-items: center;
  text-decoration: none;
}
.btn--primary {
  background: #2855b7;
  color: #fff;
  border-color: #2855b7;
}
.col-checkbox {
  min-width: 36px;
}
.action-link {
  margin-right: 10px;
}
.error-icon {
  width: 18px;
  height: 18px;
}
.error-bar {
  display: flex;
  align-items: center;
  gap: 12px;
}
.dirty-badge {
  font-size: 12px;
  color: #617088;
}
.dirty-badge--active {
  color: #976014;
}
.alert-box {
  padding: 12px 14px;
  border-radius: 8px;
  margin: 12px 0;
}
.alert-info {
  background: #edf3ff;
  color: #2855b7;
}
.info-icon {
  width: 18px;
  height: 18px;
}
</style>
