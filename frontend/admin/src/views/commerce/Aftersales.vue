<script setup lang="ts">
import { uiStatus } from '../../components/ui/labels';
import { computed, reactive, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { formatFen, parseYuan } from '@jxsc/shared';
import { createAdminAftersaleController } from '../../../../shared/src/commerce/admin';
import type { AdminAftersale } from '../../../../shared/src/commerce/admin-api';
import { commerceApi } from '../../api/commerce';
import { controllerOptions, display, useCommercePage } from '../../composables/useCommercePage';
type CaseProjection = AdminAftersale & { requestedQuantity?: number; approvedQuantity?: number };
const rows = ref<CaseProjection[]>([]),
  selected = ref<CaseProjection | null>(null),
  page = ref(1),
  total = ref(0);
const filters = reactive({ id: '', orderNo: '', status: '' });
const form = reactive({
  goodsYuan: '',
  freightYuan: '',
  reason: '',
  rejectReason: '',
  returnedQuantity: '',
  reviewed: false,
});
let controller: ReturnType<typeof createAdminAftersaleController> | null = null;
function clearSelection() {
  controller?.dispose();
  controller = null;
  selected.value = null;
  Object.assign(form, {
    goodsYuan: '',
    freightYuan: '',
    reason: '',
    rejectReason: '',
    returnedQuantity: '',
    reviewed: false,
  });
}
const { busy, error, revision, run, requireRole, orderRole, financeRole, invalidate, capture } =
  useCommercePage(() => {
    clearSelection();
    rows.value = [];
    Object.assign(filters, { id: '', orderNo: '', status: '' });
    page.value = 1;
    total.value = 0;
  });
const view = computed(() => {
  void revision.value;
  return controller;
});
const operations = computed(() => {
  void revision.value;
  return controller
    ? [
        controller.approveOperation.state,
        controller.rejectOperation.state,
        controller.receiveOperation.state,
      ]
    : [];
});
const uncertain = computed(() =>
  operations.value.some((op) => ['UNKNOWN', 'SUBMITTING'].includes(op.status)),
);
const locked = computed(() => busy.value || uncertain.value);
const permitted = computed(() => selected.value?.permittedReturnQuantity);
const canReceive = computed(
  () => Number.isSafeInteger(permitted.value) && (permitted.value ?? -1) >= 0,
);
function allowed(action: string) {
  return !!selected.value?.allowedActions.includes(action);
}
function load(next = 1) {
  return run(async (current) => {
    requireAftersaleRead();
    const result = await commerceApi.aftersales({ ...filters, page: next, pageSize: 20 });
    if (current()) {
      rows.value = result.items;
      page.value = next;
      total.value = result.total;
    }
  });
}
function requireAftersaleRead() {
  if (!financeRole.value) throw new Error('读取售后单需要财务权限；退货入库另需订单操作权限');
  requireRole('FINANCE_OPERATE');
}
async function exact(id: string) {
  const result = await commerceApi.aftersales({ id, page: 1, pageSize: 20 });
  const value = result.items.find((row) => row.id === id);
  if (!value) throw new Error('服务器未返回所选售后单');
  return value;
}
function select(id: string) {
  if (busy.value) return;
  clearSelection();
  return run(async (current) => {
    requireAftersaleRead();
    const value = await exact(id);
    if (!current()) return;
    selected.value = value;
    controller = createAdminAftersaleController({
      ...controllerOptions,
      aftersaleId: id,
      getAftersale: () => selected.value,
      onAftersale: (value) => {
        selected.value = value;
        rows.value = rows.value.map((row) => (row.id === value.id ? value : row));
        form.reviewed = false;
      },
    });
    const approval = controller.approveOperation.state.requestSnapshot,
      rejection = controller.rejectOperation.state.requestSnapshot,
      receiving = controller.receiveOperation.state.requestSnapshot;
    if (approval) {
      form.goodsYuan = formatFen(approval.goodsRefundFen);
      form.freightYuan = formatFen(approval.freightRefundFen);
      form.reason = approval.reason;
    }
    if (rejection) form.rejectReason = rejection.reason;
    if (receiving) form.returnedQuantity = String(receiving.returnedQuantity);
  });
}
watch(
  () => [form.goodsYuan, form.freightYuan, form.reason],
  () => {
    form.reviewed = false;
    controller?.setDecisionInput({ goodsRefundFen: -1, freightRefundFen: -1, reason: '' });
    revision.value++;
  },
  { flush: 'sync' },
);
function decision() {
  return {
    goodsRefundFen: parseYuan(form.goodsYuan),
    freightRefundFen: parseYuan(form.freightYuan),
    reason: form.reason,
  };
}
function preview() {
  return run(async () => {
    requireRole('FINANCE_OPERATE');
    if (!controller || uncertain.value) return;
    form.reviewed = false;
    await controller.preview(decision());
  });
}
function approve(retry = false) {
  return run(async () => {
    requireRole('FINANCE_OPERATE');
    if (!controller || uncertain.value) return;
    if (!form.reviewed) throw new Error('请明确核对当前预览金额与影响');
    await (retry ? controller.retryApprove(decision()) : controller.approve(decision()));
  });
}
function reject(retry = false) {
  return run(async () => {
    requireRole('FINANCE_OPERATE');
    if (!controller || uncertain.value) return;
    const input = { reason: form.rejectReason };
    await (retry ? controller.retryReject(input) : controller.reject(input));
  });
}
function receive(retry = false) {
  return run(async () => {
    requireRole('ORDER_OPERATE');
    if (!controller || uncertain.value) return;
    const quantity = Number(form.returnedQuantity);
    if (
      !canReceive.value ||
      !form.returnedQuantity.trim() ||
      !Number.isSafeInteger(quantity) ||
      quantity < 0 ||
      quantity > permitted.value!
    )
      throw new Error('请输入允许范围内的整数退货数量');
    const input = { returnedQuantity: quantity };
    await (retry ? controller.retryReceiveReturn(input) : controller.receiveReturn(input));
  });
}
function recover(kind: 'approve' | 'reject' | 'receive') {
  return run(() =>
    kind === 'approve'
      ? controller?.recoverApprove()
      : kind === 'reject'
        ? controller?.recoverReject()
        : controller?.recoverReceiveReturn(),
  );
}
function beginNewApproval() {
  return run(() => {
    requireRole('FINANCE_OPERATE');
    if (!allowed('APPROVE') || uncertain.value) throw new Error('当前不允许新审批');
    controller?.approval.beginNew();
    controller?.setDecisionInput({ goodsRefundFen: -1, freightRefundFen: -1, reason: '' });
    form.reviewed = false;
  });
}
function refresh() {
  return run(async (current) => {
    requireAftersaleRead();
    if (!selected.value) return;
    const value = await exact(selected.value.id);
    if (current()) {
      selected.value = value;
      form.reviewed = false;
      controller?.setDecisionInput({ goodsRefundFen: -1, freightRefundFen: -1, reason: '' });
    }
  });
}
const route = useRoute();
watch(
  () => route.fullPath,
  async () => {
    invalidate();
    const current = capture();
    const id = route.query.id;
    if (id != null && (typeof id !== 'string' || !id.trim())) {
      error.value = '请指定唯一有效的售后单 ID';
      return;
    }
    filters.id = id ?? '';
    await load();
    if (current() && !error.value && id) await select(id);
  },
  { immediate: true },
);
</script>
<template>
  <div class="admin-layout">
    <header class="admin-header">
      <div>
        <h1>售后管理</h1>
        <span class="page-desc">查询退款与退货进度，核对后处理售后申请</span>
      </div>
    </header>
    <main class="admin-content">
      <div class="admin-card">
        <form @submit.prevent="load()">
          <fieldset class="filter-row" :disabled="busy">
            <label>售后单 ID <input v-model="filters.id" /></label
            ><label>订单号 <input v-model="filters.orderNo" /></label
            ><label>售后状态 <input v-model="filters.status" /></label><button>搜索</button>
          </fieldset>
        </form>
        <p v-if="busy">加载中…</p>
        <p v-if="error" role="alert">
          {{ error }} <button :disabled="busy" @click="load(page)">重试列表</button>
        </p>
        <p v-if="!busy && !error && !rows.length">没有匹配售后单</p>
        <div class="ls-table-scroll">
          <table class="admin-table">
            <thead>
              <tr>
                <th>售后单</th>
                <th>订单 ID</th>
                <th>状态</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in rows" :key="row.id">
                <td>{{ row.id }}</td>
                <td>{{ row.orderId }}</td>
                <td>{{ uiStatus(row.status) }}</td>
                <td><button :disabled="busy" @click="select(row.id)">选择售后</button></td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="pagination-bar">
          <span>第 {{ page }} 页 / 共 {{ total }} 条</span>
          <div class="pagination-actions">
            <button :disabled="busy || page <= 1" @click="load(page - 1)">上一页</button>
            <button :disabled="busy || page * 20 >= total" @click="load(page + 1)">下一页</button>
          </div>
        </div>
      </div>
      <div v-if="selected && view" class="admin-card">
        <h2>{{ selected.id }} · {{ uiStatus(selected.status) }}</h2>
        <p>类型 {{ uiStatus(selected.type) }} / 申请原因 {{ selected.reason }}</p>
        <p>
          申请商品退款 ¥{{ formatFen(selected.requestedGoodsFen) }} / 运费 ¥{{
            formatFen(selected.requestedFreightFen)
          }}
        </p>
        <p>
          已批准商品退款
          {{
            selected.approvedGoodsFen == null
              ? '不可用'
              : '¥' + formatFen(selected.approvedGoodsFen)
          }}
          / 运费
          {{
            selected.approvedFreightFen == null
              ? '不可用'
              : '¥' + formatFen(selected.approvedFreightFen)
          }}
        </p>
        <p>
          申请数量 {{ display(selected.requestedQuantity) }} / 批准数量
          {{ display(selected.approvedQuantity) }} / 寄回数量
          {{ display(selected.returnedQuantity) }} / 已收数量
          {{ display(selected.receivedQuantity) }} / 当前允许收货数量 {{ display(permitted) }}
        </p>
        <p>退货地址与说明：{{ display(selected.returnAddress) }}</p>
        <p>
          退货物流：{{ display(selected.returnCarrierCode) }} /
          {{ display(selected.returnTrackingNo) }}
        </p>
        <p>
          失败原因：{{ display(selected.failureReason) }} / 拒绝原因：{{
            display(selected.rejectionReason)
          }}
        </p>
        <details class="ls-secondary-details">
          <summary>服务器技术详情</summary>
          <p>售后状态：{{ uiStatus(selected.status) }} · 类型：{{ uiStatus(selected.type) }}</p>
          <p>当前允许操作：{{ selected.allowedActions.map(uiStatus).join('、') || '无' }}</p>
        </details>
        <p v-if="uncertain">操作结果未知，所有修改已锁定；使用对应原操作查询。</p>
        <button :disabled="busy" @click="refresh">刷新当前售后单</button>
        <template v-if="financeRole">
          <h3>财务审批</h3>
          <details :open="allowed('APPROVE') || !!view.approveOperation.state.key">
            <summary>批准退款</summary>
            <fieldset :disabled="locked || !allowed('APPROVE')">
              <label
                >商品退款（元）
                <input v-model="form.goodsYuan" name="goodsYuan" inputmode="decimal" /></label
              ><label
                >运费退款（元）
                <input v-model="form.freightYuan" name="freightYuan" inputmode="decimal" /></label
              ><label
                >审批原因 <input v-model="form.reason" name="financeReason" maxlength="500"
              /></label>
            </fieldset>
            <button :disabled="locked || !allowed('APPROVE')" @click="preview">预览决策</button>
            <div v-if="view.state.preview">
              <p>
                剩余商品预算 ¥{{ formatFen(view.state.preview.remainingGoodsFen) }} / 运费预算 ¥{{
                  formatFen(view.state.preview.remainingFreightFen)
                }}
              </p>
              <p>{{ view.state.preview.entitlementImpact }}</p>
              <p>{{ view.state.preview.commissionImpact }}</p>
              <p>
                本次精确输入：商品 ¥{{ form.goodsYuan }} / 运费 ¥{{ form.freightYuan }} / 原因
                {{ form.reason }}
              </p>
              <label
                ><input
                  v-model="form.reviewed"
                  name="reviewed"
                  type="checkbox"
                  :disabled="locked"
                />已核对当前金额、原因及权益佣金影响</label
              >
            </div>
            <p v-if="view.approveOperation.state.status !== 'IDLE'" role="status">
              操作结果：{{ uiStatus(view.approveOperation.state.status) }}
            </p>
            <details class="ls-secondary-details">
              <summary>原操作详情</summary>
              <p>
                状态：{{ uiStatus(view.approveOperation.state.status) }} · 原操作键：{{
                  view.approveOperation.state.key
                }}
              </p>
            </details>
            <button
              v-if="view.approveOperation.state.status === 'IDLE'"
              :disabled="locked || !allowed('APPROVE') || !view.state.preview || !form.reviewed"
              @click="approve()"
            >
              批准
            </button>
            <button
              v-if="view.approveOperation.state.status === 'FAILED'"
              :disabled="locked || !allowed('APPROVE') || !view.state.preview || !form.reviewed"
              @click="approve(true)"
            >
              更正后重试批准
            </button>
            <button
              v-if="view.approveOperation.state.key"
              :disabled="busy"
              @click="recover('approve')"
            >
              查询原批准操作</button
            ><button
              v-if="view.approveOperation.state.status === 'SUCCEEDED' && allowed('APPROVE')"
              :disabled="locked"
              @click="beginNewApproval"
            >
              明确开始新的批准操作
            </button>
          </details>
          <details :open="allowed('REJECT') || !!view.rejectOperation.state.key">
            <summary>拒绝申请</summary>
            <fieldset :disabled="locked || !allowed('REJECT')">
              <label
                >拒绝原因 <input v-model="form.rejectReason" name="rejectReason" maxlength="500"
              /></label>
            </fieldset>
            <p v-if="view.rejectOperation.state.status !== 'IDLE'" role="status">
              操作结果：{{ uiStatus(view.rejectOperation.state.status) }}
            </p>
            <details class="ls-secondary-details">
              <summary>原操作详情</summary>
              <p>
                状态：{{ uiStatus(view.rejectOperation.state.status) }} · 原操作键：{{
                  view.rejectOperation.state.key
                }}
              </p>
            </details>
            <button
              v-if="view.rejectOperation.state.status === 'IDLE'"
              :disabled="locked || !allowed('REJECT')"
              @click="reject()"
            >
              拒绝</button
            ><button
              v-if="view.rejectOperation.state.status === 'FAILED'"
              :disabled="locked || !allowed('REJECT')"
              @click="reject(true)"
            >
              更正后重试拒绝</button
            ><button
              v-if="view.rejectOperation.state.key"
              :disabled="busy"
              @click="recover('reject')"
            >
              查询原拒绝操作
            </button>
          </details>
        </template>
        <template v-if="orderRole"
          ><h3>仓库收货</h3>
          <details :open="allowed('RECEIVE_RETURN') || !!view.receiveOperation.state.key">
            <summary>退货收货</summary>
            <p>收货仅更新仓库返回状态，不代表退款完成。</p>
            <fieldset :disabled="locked || !allowed('RECEIVE_RETURN') || !canReceive">
              <label
                >实收退货数量
                <input v-model="form.returnedQuantity" name="returnedQuantity" inputmode="numeric"
              /></label>
            </fieldset>
            <p v-if="view.receiveOperation.state.status !== 'IDLE'" role="status">
              操作结果：{{ uiStatus(view.receiveOperation.state.status) }}
            </p>
            <details class="ls-secondary-details">
              <summary>原操作详情</summary>
              <p>
                状态：{{ uiStatus(view.receiveOperation.state.status) }} · 原操作键：{{
                  view.receiveOperation.state.key
                }}
              </p>
            </details>
            <button
              v-if="view.receiveOperation.state.status === 'IDLE'"
              :disabled="locked || !allowed('RECEIVE_RETURN') || !canReceive"
              @click="receive()"
            >
              确认收到退货</button
            ><button
              v-if="view.receiveOperation.state.status === 'FAILED'"
              :disabled="locked || !allowed('RECEIVE_RETURN') || !canReceive"
              @click="receive(true)"
            >
              更正后重试收货</button
            ><button
              v-if="view.receiveOperation.state.key"
              :disabled="busy"
              @click="recover('receive')"
            >
              查询原收货操作
            </button>
          </details>
        </template>
      </div>
    </main>
  </div>
</template>
