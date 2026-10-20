<script setup lang="ts">
import { formatShanghaiDateTime } from '@jxsc/shared';
import { computed, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { formatFen } from '@jxsc/shared';
import type { AdminOrder } from '../../../../shared/src/commerce/admin-api';
import { commerceApi } from '../../api/commerce';
import { useCommercePage } from '../../composables/useCommercePage';
import { orderStatusLabel, fulfillmentStatusLabel } from '../../../../shared/src/commerce/order';
import { aftersaleStatusLabel } from '../../../../shared/src/commerce/aftersale';
import UiStatusBadge from '../../components/ui/UiStatusBadge.vue';
const route = useRoute(),
  order = ref<AdminOrder | null>(null);
const id = computed(() => String(route.params.id ?? ''));
const address = computed(() => {
  const value = order.value?.address;
  return value && typeof value === 'object' ? (value as Record<string, unknown>) : null;
});
function addressField(...keys: string[]) {
  for (const key of keys) {
    const value = address.value?.[key];
    if (typeof value === 'string' && value.trim()) return value;
  }
  return '—';
}
const { busy, error, run, requireRole, invalidate } = useCommercePage(() => {
  order.value = null;
});
function load() {
  const selected = id.value;
  return run(async (current) => {
    requireRole('ORDER_OPERATE');
    const value = await commerceApi.order(selected);
    if (current() && value.id === selected) order.value = value;
  });
}
watch(
  id,
  () => {
    invalidate();
    void load();
  },
  { immediate: true },
);
</script>
<template>
  <div class="admin-layout">
    <header class="admin-header">
      <div>
        <h1>订单详情</h1>
        <span class="page-desc">核对订单、付款和收货信息，追踪履约进度</span>
      </div>
      <button class="ls-button" :disabled="busy" @click="load">刷新</button>
    </header>
    <main class="admin-content">
      <p v-if="busy" role="status">正在加载订单…</p>
      <p v-if="error" role="alert">
        {{ error }} <button :disabled="busy" @click="load">重试</button>
      </p>
      <p v-if="order" class="order-notice">
        {{
          order.item.type === 'BOOK'
            ? '实体图书请核对收货信息后处理物流。'
            : '课程、会员等数字商品无需填写物流信息。'
        }}
      </p>
      <div class="admin-card">
        <template v-if="order">
          <div class="order-heading">
            <div>
              <h2>{{ order.orderNo }}</h2>
            </div>
            <div class="status-row">
              <UiStatusBadge :label="orderStatusLabel(order.orderStatus)" tone="blue" />
              <UiStatusBadge :label="fulfillmentStatusLabel(order.fulfillmentStatus)" />
              <UiStatusBadge :label="aftersaleStatusLabel(order.refundStatus)" tone="warning" />
            </div>
          </div>
          <div class="order-summary">
            <div>
              <span>付款金额</span
              ><strong>{{
                order.paidAmountFen == null ? '未提供' : '¥' + formatFen(order.paidAmountFen)
              }}</strong>
            </div>
            <div>
              <span>支付时间</span><strong>{{ formatShanghaiDateTime(order.paidAt) }}</strong>
            </div>
            <div>
              <span>订单商品</span
              ><strong>{{ order.item.title }} × {{ order.item.quantity }}</strong>
            </div>
            <div>
              <span>收货信息</span
              ><strong v-if="address"
                >{{ addressField('name', 'recipientName') }} ·
                {{ addressField('phone', 'recipientPhone') }}<br />{{
                  addressField('detail', 'detailAddress')
                }}</strong
              ><strong v-else>{{
                order.item.type === 'BOOK' ? '暂无收货信息' : '数字商品无需收货地址'
              }}</strong>
            </div>
          </div>
          <div class="ls-table-scroll order-items">
            <table>
              <thead>
                <tr>
                  <th>商品</th>
                  <th>单价</th>
                  <th>数量</th>
                  <th>商品金额</th>
                </tr>
              </thead>
              <tbody>
                <tr>
                  <td>{{ order.item.title }}</td>
                  <td>
                    {{
                      order.item.unitPriceFen == null
                        ? '不可用'
                        : '¥' + formatFen(order.item.unitPriceFen)
                    }}
                  </td>
                  <td>{{ order.item.quantity }}</td>
                  <td>¥{{ formatFen(order.goodsAmountFen) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <div class="order-actions">
            <router-link
              v-if="order.allowedActions.some((a) => ['SHIP', 'UPDATE_SHIPMENT'].includes(a))"
              class="ls-button ls-button--primary"
              :to="{ path: '/order/fulfillment', query: { id: order.id } }"
              >核对收货人并处理物流</router-link
            >
            <router-link class="ls-button" to="/commerce/orders">返回订单列表</router-link>
          </div>
          <details class="order-more">
            <summary>金额明细与物流轨迹</summary>
            <div class="detail-grid">
              <section class="detail-section">
                <h3>金额与时间</h3>
                <dl class="details">
                  <dt>商品金额</dt>
                  <dd>¥{{ formatFen(order.goodsAmountFen) }}</dd>
                  <dt>运费</dt>
                  <dd>¥{{ formatFen(order.freightAmountFen) }}</dd>
                  <dt>应付金额</dt>
                  <dd class="amount">¥{{ formatFen(order.payableAmountFen) }}</dd>
                  <dt>实付金额</dt>
                  <dd>
                    {{
                      order.paidAmountFen == null ? '不可用' : '¥' + formatFen(order.paidAmountFen)
                    }}
                  </dd>
                  <dt>已退款金额</dt>
                  <dd>
                    {{
                      order.refundedAmountFen == null
                        ? '不可用'
                        : '¥' + formatFen(order.refundedAmountFen)
                    }}
                  </dd>
                  <dt>支付时间</dt>
                  <dd>{{ formatShanghaiDateTime(order.paidAt) }}</dd>
                  <dt>完成时间</dt>
                  <dd>{{ formatShanghaiDateTime(order.completedAt) }}</dd>
                </dl>
              </section>
              <section class="detail-section">
                <h3>收货信息</h3>
                <dl v-if="address" class="details">
                  <dt>收货人</dt>
                  <dd>{{ addressField('name', 'recipientName') }}</dd>
                  <dt>联系电话</dt>
                  <dd>{{ addressField('phone', 'recipientPhone') }}</dd>
                  <dt>详细地址</dt>
                  <dd>{{ addressField('detail', 'detailAddress') }}</dd>
                  <dt>地区编码</dt>
                  <dd>
                    {{ addressField('provinceCode') }} / {{ addressField('cityCode') }} /
                    {{ addressField('districtCode') }}
                  </dd>
                </dl>
                <p v-else>
                  {{ order.item.type === 'BOOK' ? '暂无收货信息' : '数字商品无需收货地址' }}
                </p>
                <h3>物流信息</h3>
                <template v-if="order.shipment">
                  <dl class="details">
                    <dt>承运商编码</dt>
                    <dd>{{ order.shipment.carrierCode || '—' }}</dd>
                    <dt>运单号</dt>
                    <dd>{{ order.shipment.trackingNo || '—' }}</dd>
                    <dt>发货时间</dt>
                    <dd>{{ formatShanghaiDateTime(order.shipment.shippedAt) }}</dd>
                    <dt>签收时间</dt>
                    <dd>{{ formatShanghaiDateTime(order.shipment.receivedAt) }}</dd>
                  </dl>
                  <p v-if="order.shipment.simulated" class="eyebrow">本地模拟物流</p>
                  <ol v-if="order.shipment.events?.length" class="timeline">
                    <li v-for="(event, index) in order.shipment.events" :key="index">
                      <time>{{ formatShanghaiDateTime(event.occurredAt) }}</time>
                      <p>{{ event.description || '暂无事件说明' }}</p>
                    </li>
                  </ol>
                  <p v-else>暂无物流轨迹</p>
                </template>
                <p v-else>暂无物流信息</p>
              </section>
            </div>
            <p class="eyebrow">订单版本 {{ order.version ?? '—' }}</p>
          </details>
        </template>
        <p v-else-if="!busy && !error" class="empty-state">暂无订单信息</p>
      </div>
    </main>
  </div>
</template>
<style scoped>
.order-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 20px;
  margin: 0 0 20px;
}
.order-heading h2 {
  margin: 4px 0;
  overflow-wrap: anywhere;
}
.eyebrow {
  color: var(--ls-sub);
  font-size: 13px;
}
.status-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.detail-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 24px;
  margin-top: 20px;
}
.order-notice {
  padding: 12px 16px;
  border-radius: 8px;
  background: var(--ls-tint);
  color: var(--ls-primary);
  font-size: 13px;
}
.order-summary {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 20px;
  margin-bottom: 20px;
  font-size: 13px;
}
.order-summary span {
  display: block;
  color: var(--ls-sub);
  margin-bottom: 7px;
}
.order-summary strong {
  font-weight: 400;
  line-height: 1.7;
  overflow-wrap: anywhere;
}
.order-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 22px;
}
.order-more {
  border-top: 1px solid var(--ls-line);
  margin-top: 20px;
  padding-top: 16px;
}
.order-more summary {
  color: var(--ls-sub);
  cursor: pointer;
  font-size: 13px;
}
.order-items table {
  min-width: 480px;
}
@media (max-width: 736px) {
  .order-summary {
    grid-template-columns: 1fr;
  }
}
.detail-section {
  min-width: 0;
  border: 1px solid var(--ls-line);
  border-radius: 12px;
  padding: 20px;
}
.product-summary {
  display: grid;
  grid-template-columns: 120px minmax(0, 1fr);
  gap: 20px;
  align-items: center;
}
.product-summary h4 {
  overflow-wrap: anywhere;
  margin: 0 0 12px;
}
.product-summary :deep(.ls-cover-art) {
  padding: 10px 12px;
  gap: 4px;
}
.product-summary :deep(.ls-cover-title) {
  font-size: 16px;
  letter-spacing: 0;
  -webkit-line-clamp: 2;
}
.product-summary :deep(.ls-cover-caption) {
  font-size: 10px;
}
.details {
  display: grid;
  grid-template-columns: 100px minmax(0, 1fr);
  gap: 14px;
  margin: 24px 0;
  font-size: 14px;
}
.details dt {
  color: var(--ls-sub);
}
.details dd {
  margin: 0;
  overflow-wrap: anywhere;
}
.amount {
  font-weight: 700;
  color: var(--ls-primary);
}
.timeline {
  padding-left: 20px;
}
.timeline li {
  margin-bottom: 20px;
  overflow-wrap: anywhere;
}
.timeline time {
  color: var(--ls-sub);
  font-size: 12px;
}
@media (max-width: 1100px) {
  .detail-grid {
    grid-template-columns: 1fr;
  }
  .order-heading {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
