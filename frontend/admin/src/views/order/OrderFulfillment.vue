<script setup lang="ts">
import { uiStatus } from '../../components/ui/labels';
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { formatFen } from '@jxsc/shared';
import { createAdminShipmentController } from '../../../../shared/src/commerce/admin';
import type { AdminOrder } from '../../../../shared/src/commerce/admin-api';
import { commerceApi } from '../../api/commerce';
import { logisticsApi, type CarrierOption } from '../../api/logistics';
import {
  controllerOptions,
  display,
  orderLink,
  useCommercePage,
} from '../../composables/useCommercePage';
const route = useRoute();
const rows = ref<AdminOrder[]>([]),
  order = ref<AdminOrder | null>(null),
  orderNo = ref(''),
  page = ref(1),
  total = ref(0);
const form = reactive({ carrierCode: '', trackingNo: '', reason: '', recipientConfirmed: false });
const carrierOptions = ref<CarrierOption[]>([]);
onMounted(async () => {
  try {
    const list = await logisticsApi.carriers();
    carrierOptions.value = Array.isArray(list) ? list : [];
  } catch {
    // Typing the code still works; the server validates against the same catalog.
    carrierOptions.value = [];
  }
});
let controller: ReturnType<typeof createAdminShipmentController> | null = null;
function clearSelection() {
  controller?.dispose();
  controller = null;
  order.value = null;
  Object.assign(form, { carrierCode: '', trackingNo: '', reason: '', recipientConfirmed: false });
}
const { busy, error, revision, run, requireRole, orderRole, invalidate, capture } = useCommercePage(
  () => {
    clearSelection();
    rows.value = [];
    orderNo.value = '';
    page.value = 1;
    total.value = 0;
  },
);
const operation = computed(() => {
  void revision.value;
  return controller?.operation.state;
});
const locked = computed(
  () =>
    busy.value ||
    !orderRole.value ||
    ['UNKNOWN', 'SUBMITTING'].includes(operation.value?.status ?? ''),
);
const allowed = computed(
  () => !!order.value?.allowedActions.some((a) => ['SHIP', 'UPDATE_SHIPMENT'].includes(a)),
);
function load(next = 1) {
  return run(async (current) => {
    requireRole('ORDER_OPERATE');
    const result = await commerceApi.orders({ orderNo: orderNo.value, page: next, pageSize: 20 });
    if (current()) {
      rows.value = result.items;
      total.value = result.total;
      page.value = next;
    }
  });
}
async function select(id: string) {
  if (busy.value) return;
  clearSelection();
  await run(async (current) => {
    requireRole('ORDER_OPERATE');
    const value = await commerceApi.order(id);
    if (!current()) return;
    if (value.id !== id) throw new Error('订单标识不匹配');
    order.value = value;
    controller = createAdminShipmentController({
      ...controllerOptions,
      orderId: id,
      getOrder: () => order.value,
      onOrder: (value) => {
        order.value = value;
        rows.value = rows.value.map((row) => (row.id === value.id ? value : row));
      },
    });
    const snapshot = controller.operation.state.requestSnapshot;
    form.carrierCode = snapshot?.carrierCode ?? value.shipment?.carrierCode ?? '';
    form.trackingNo = snapshot?.trackingNo ?? value.shipment?.trackingNo ?? '';
    form.reason = snapshot?.reason ?? '';
  });
}
function refresh() {
  return run(async (current) => {
    requireRole('ORDER_OPERATE');
    if (!order.value) return;
    const value = await commerceApi.order(order.value.id);
    if (current() && value.id === order.value?.id) {
      order.value = value;
      form.recipientConfirmed = false;
    }
  });
}
function submit(retry = false) {
  return run(async () => {
    requireRole('ORDER_OPERATE');
    if (!controller || !order.value || (locked.value && operation.value?.status === 'UNKNOWN'))
      return;
    if (!form.recipientConfirmed) throw new Error('请先核对并确认收货人');
    if (order.value.version == null) throw new Error('服务器未提供版本，不能发货');
    const body = {
      carrierCode: form.carrierCode,
      trackingNo: form.trackingNo,
      reason: form.reason,
      version: order.value.version,
    };
    await (retry ? controller.retry(body) : controller.submit(body));
  });
}
function recover() {
  return run(() => controller?.recover());
}
function beginNew() {
  return run(() => {
    controller?.beginNew();
    form.recipientConfirmed = false;
  });
}
watch(
  () => route.fullPath,
  async () => {
    invalidate();
    const current = capture();
    const id = route.query.id;
    await load();
    if (current() && typeof id === 'string') await select(id);
  },
  { immediate: true },
);
</script>
<template>
  <div class="admin-layout">
    <header class="admin-header">
      <div class="header-brand">
        <h1 class="brand-title">发货管理</h1>
        <span class="page-desc">核对收货信息，填写运单并追踪物流状态</span>
      </div>
    </header>
    <main class="admin-content">
      <p v-if="error" role="alert" class="mb-4">{{ error }}</p>
      <p v-if="busy">加载中…</p>
      <div class="admin-card shipping-list">
        <form class="shipping-filters" @submit.prevent="load()">
          <label
            >订单号 <input v-model="orderNo" :disabled="busy" placeholder="输入订单号"
          /></label>
          <button class="ls-button ls-button--primary" :disabled="busy">查询</button>
          <button
            type="button"
            class="ls-button"
            :disabled="busy"
            @click="
              orderNo = '';
              load();
            "
          >
            重置
          </button>
        </form>
        <div class="ls-table-scroll">
          <table class="admin-table">
            <thead>
              <tr>
                <th>订单 / 商品</th>
                <th>应付金额</th>
                <th>订单状态</th>
                <th>物流状态</th>
                <th>快递公司</th>
                <th>快递运单号</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in rows" :key="row.id">
                <td>
                  <router-link :to="orderLink(row.id)" class="order-no">{{
                    row.orderNo
                  }}</router-link>
                  <div class="goods-desc">
                    <span class="qty">{{ row.item.title }} × {{ row.item.quantity }}</span>
                  </div>
                </td>
                <td>¥{{ formatFen(row.payableAmountFen) }}</td>
                <td>
                  <span
                    class="ls-badge"
                    :class="{ 'ls-badge--success': row.orderStatus === 'PAID' }"
                    >{{ uiStatus(row.orderStatus) }}</span
                  >
                </td>
                <td>
                  <span
                    class="ls-badge"
                    :class="{ 'ls-badge--warning': row.fulfillmentStatus === 'PENDING_SHIPMENT' }"
                    >{{ uiStatus(row.fulfillmentStatus) }}</span
                  >
                </td>
                <td>{{ display(row.shipment?.carrierCode) }}</td>
                <td class="tracking-text">{{ display(row.shipment?.trackingNo) }}</td>
                <td>
                  <button
                    class="ls-button ls-button--link"
                    :disabled="busy"
                    @click="select(row.id)"
                  >
                    核对物流
                  </button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-if="!busy && !error && !rows.length" class="empty-state">没有匹配订单</p>
        <div class="pagination-bar">
          <span>共 {{ total }} 条 · 每页 20 条</span>
          <div class="pagination-actions">
            <button :disabled="busy || page <= 1" @click="load(page - 1)">上一页</button
            ><span>第 {{ page }} 页</span>
            <button :disabled="busy || page * 20 >= total" @click="load(page + 1)">下一页</button>
          </div>
        </div>
      </div>
      <div v-if="order" class="admin-card">
        <h2>{{ order.orderNo }}</h2>
        <p>收货人及地址：{{ display(order.address) }}</p>
        <p>
          订单：{{ uiStatus(order.orderStatus) }} · 履约：{{ uiStatus(order.fulfillmentStatus) }} ·
          退款：{{ uiStatus(order.refundStatus) }}
        </p>
        <p>当前物流：{{ display(order.shipment) }}</p>
        <details class="ls-secondary-details">
          <summary>服务器技术详情</summary>
          <p>
            版本：{{ display(order.version) }} · 当前状态：{{ uiStatus(order.orderStatus) }} /
            {{ uiStatus(order.fulfillmentStatus) }} / {{ uiStatus(order.refundStatus) }}
          </p>
          <p>当前允许操作：{{ order.allowedActions.map(uiStatus).join('、') || '无' }}</p>
        </details>
        <fieldset data-shipment-form :disabled="locked" class="shipment-fields">
          <legend>填写物流信息</legend>
          <label
            >承运商代码
            <input
              v-model="form.carrierCode"
              name="carrierCode"
              list="carrier-code-options"
              placeholder="如 shunfeng"
            />
            <datalist id="carrier-code-options">
              <option v-for="carrier in carrierOptions" :key="carrier.code" :value="carrier.code">
                {{ carrier.name }}
              </option>
            </datalist> </label
          ><label>运单号 <input v-model="form.trackingNo" name="trackingNo" /></label
          ><label>原因（更新物流必填） <input v-model="form.reason" name="reason" /></label
          ><label
            ><input
              v-model="form.recipientConfirmed"
              name="recipientConfirmed"
              type="checkbox"
            />已核对当前收货人、地址和版本</label
          >
        </fieldset>
        <p v-if="operation?.status && operation.status !== 'IDLE'" role="status">
          操作结果：{{ uiStatus(operation.status) }}
        </p>
        <details class="ls-secondary-details">
          <summary>操作技术信息</summary>
          <p>
            操作状态：{{ uiStatus(operation?.status) }}；原操作键 {{ operation?.key || '尚未提交' }}
          </p>
        </details>
        <p v-if="operation?.status === 'UNKNOWN'">
          结果未知，输入已锁定。只能查询原操作，不能发起新物流。
        </p>
        <button
          class="ls-button ls-button--primary"
          v-if="operation?.status === 'IDLE' && allowed"
          :disabled="locked"
          @click="submit()"
        >
          提交物流
        </button>
        <button
          v-if="operation?.status === 'FAILED' && allowed"
          :disabled="locked"
          @click="submit(true)"
        >
          更正后重试
        </button>
        <button :disabled="busy" @click="refresh">刷新并重新核对</button>
        <button v-if="operation?.key" :disabled="busy || !orderRole" @click="recover">
          查询原操作
        </button>
        <button
          v-if="operation?.status === 'SUCCEEDED' && allowed"
          :disabled="busy || !orderRole"
          @click="beginNew"
        >
          明确开始新的物流操作
        </button>
      </div>
    </main>
  </div>
</template>

<style lang="scss" scoped>
.shipping-filters {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 12px;
  margin-bottom: 18px;
}
.shipping-filters label {
  margin: 0;
  flex: 1;
  max-width: 360px;
}
.admin-table {
  min-width: 760px;
}
.shipment-fields {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}
.shipment-fields label {
  margin: 0;
  flex: 1 1 200px;
}
.shipment-fields label:has(input[type='checkbox']) {
  flex-basis: 100%;
}
.goods-desc {
  margin-top: 6px;
}
.back-link {
  font-size: 13px;
  color: #6b7280;
  text-decoration: none;
  &:hover {
    color: #2855b7;
  }
}

.sub-desc {
  font-size: 12px;
  color: #9ca3af;
  margin-top: 4px;
  display: block;
}

.mb-4 {
  margin-bottom: 16px;
}

.admin-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 13px;

  th {
    background: #f4f7fb;
    color: #4b5563;
    font-weight: 600;
    text-align: left;
    padding: 12px 14px;
    border-bottom: 1px solid #e5ebf3;
  }

  td {
    padding: 14px;
    border-bottom: 1px solid #f3f4f6;
    vertical-align: middle;
  }
}

.order-no {
  font-weight: 600;
  color: #1f2937;
}

.goods-desc {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-width: 260px;

  .qty {
    font-size: 12px;
    color: #6b7280;
  }
}

.addr-box {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-width: 240px;

  .addr-user {
    font-weight: 500;
    color: #374151;
  }

  .addr-detail {
    font-size: 12px;
    color: #9ca3af;
  }
}

.tracking-text {
  font-family: monospace;
  font-weight: 600;
  color: #2855b7;
}
</style>
