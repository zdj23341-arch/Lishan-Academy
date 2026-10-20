<script setup lang="ts">
import { uiStatus } from '../../components/ui/labels';
import { computed, onMounted, reactive, ref } from 'vue';
import { createAdminInventoryController } from '../../../../shared/src/commerce/admin';
import { ApiError } from '@jxsc/shared';
import {
  hasInventoryStock,
  hasInventoryVersion,
  type AdminInventoryProduct,
} from '../../../../shared/src/commerce/admin-api';
import { commerceApi } from '../../api/commerce';
import { controllerOptions, display, useCommercePage } from '../../composables/useCommercePage';
const rows = ref<AdminInventoryProduct[]>([]),
  product = ref<AdminInventoryProduct | null>(null),
  keyword = ref(''),
  page = ref(1),
  total = ref(0);
const form = reactive({ delta: '', reason: '' });
const refreshRequired = ref(false);
let controller: ReturnType<typeof createAdminInventoryController> | null = null;
function clearSelection() {
  controller?.dispose();
  controller = null;
  product.value = null;
  form.delta = '';
  form.reason = '';
  refreshRequired.value = false;
}
const { busy, error, revision, run, requireRole, orderRole, contentRole } = useCommercePage(() => {
  clearSelection();
  rows.value = [];
  keyword.value = '';
  page.value = 1;
  total.value = 0;
});
const operation = computed(() => {
  void revision.value;
  return controller?.operation.state;
});
const locked = computed(
  () =>
    busy.value ||
    !orderRole.value ||
    !hasInventoryStock(product.value) ||
    !hasInventoryVersion(product.value) ||
    refreshRequired.value ||
    ['UNKNOWN', 'SUBMITTING'].includes(operation.value?.status ?? ''),
);
function load(next = 1) {
  return run(async (current) => {
    requireInventoryRead();
    const result = await commerceApi.products({ keyword: keyword.value, page: next, pageSize: 20 });
    if (current()) {
      if (result.items.some((item) => item.type !== 'BOOK')) {
        clearSelection();
        rows.value = [];
        total.value = 0;
        page.value = 1;
        throw new Error('服务未按图书类型返回库存列表，请稍后重试');
      }
      rows.value = result.items;
      total.value = result.total;
      page.value = next;
    }
  });
}
function requireInventoryRead() {
  requireRole('CONTENT_EDIT', 'ORDER_OPERATE');
}
function select(id: string) {
  if (busy.value) return;
  clearSelection();
  return run(async (current) => {
    requireInventoryRead();
    const value = await commerceApi.product(id);
    if (!current()) return;
    if (value.id !== id || value.type !== 'BOOK') throw new Error('只能调整当前图书库存');
    product.value = {
      ...value,
      title: value.title ?? rows.value.find((row) => row.id === id)?.title,
    };
    controller = createAdminInventoryController({
      ...controllerOptions,
      productId: id,
      getProduct: () => product.value,
      onProduct: (value) => {
        product.value = { ...value, title: value.title ?? product.value?.title };
        rows.value = rows.value.map((row) =>
          row.id === value.id ? { ...row, ...value, title: value.title ?? row.title } : row,
        );
      },
    });
    const snapshot = controller.operation.state.requestSnapshot;
    if (snapshot) {
      form.delta = String(snapshot.delta);
      form.reason = snapshot.reason;
    }
  });
}
function submit(retry = false) {
  return run(async () => {
    requireRole('ORDER_OPERATE');
    if (!controller) return;
    if (refreshRequired.value || !hasInventoryVersion(product.value))
      throw new Error('请先刷新服务器库存并核对后再重试');
    const body = {
      delta: Number(form.delta),
      reason: form.reason,
      version: product.value!.book!.inventoryVersion!,
    };
    try {
      await (retry ? controller.retry(body) : controller.submit(body));
    } catch (cause) {
      if (
        cause instanceof ApiError &&
        cause.status === 409 &&
        controller.operation.state.status === 'FAILED'
      )
        refreshRequired.value = true;
      throw cause;
    }
  });
}
function recover() {
  return run(() => controller?.recover());
}
function beginNew() {
  return run(() => {
    controller?.beginNew();
    form.delta = '';
    form.reason = '';
  });
}
function refresh() {
  return run(async (current) => {
    requireInventoryRead();
    if (!product.value) return;
    const value = await commerceApi.product(product.value.id);
    if (current() && value.id === product.value?.id) {
      product.value = { ...value, title: value.title ?? product.value.title };
      refreshRequired.value = !hasInventoryStock(value) || !hasInventoryVersion(value);
    }
  });
}
onMounted(() => load());
</script>
<template>
  <div class="admin-layout">
    <header class="admin-header">
      <div>
        <h1>图书库存</h1>
        <span class="page-desc">核对图书可用、锁定和实物库存</span>
      </div>
    </header>
    <main class="admin-content">
      <div class="admin-card">
        <form class="filter-form" @submit.prevent="load()">
          <label>搜索图书 <input v-model="keyword" :disabled="busy" /></label
          ><button :disabled="busy">搜索</button>
        </form>
        <p v-if="busy">加载中…</p>
        <p v-if="error" role="alert">
          {{ error }} <button :disabled="busy" @click="load(page)">重试列表</button>
        </p>
        <p v-if="!busy && !error && !rows.length">没有匹配图书</p>
        <div class="ls-table-scroll">
          <table class="admin-table">
            <thead>
              <tr>
                <th>图书</th>
                <th>可用</th>
                <th>锁定</th>
                <th>实物</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in rows" :key="row.id">
                <td>{{ row.title }}</td>
                <td>{{ display(row.book?.availableStock) }}</td>
                <td>{{ display(row.book?.lockedStock) }}</td>
                <td>{{ display(row.book?.physicalStock) }}</td>
                <td>
                  <button :disabled="busy || (!contentRole && !orderRole)" @click="select(row.id)">
                    {{
                      orderRole && hasInventoryStock(row) && hasInventoryVersion(row)
                        ? '调整库存'
                        : '查看库存'
                    }}
                  </button>
                </td>
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
      <div v-if="product" class="admin-card">
        <h2>{{ product.title }}</h2>
        <p v-if="!orderRole">调整库存需要订单操作权限，当前可查看服务器库存。</p>
        <p v-if="!hasInventoryStock(product) || !hasInventoryVersion(product)" role="status">
          库存数据暂不可用，暂时不能调整；请刷新后重试。
        </p>
        <p v-if="refreshRequired" role="status">
          库存已变化，请刷新服务器库存并核对后，再更正重试。
        </p>
        <p>
          可用库存：{{ display(product.book?.availableStock) }}；锁定库存：{{
            display(product.book?.lockedStock)
          }}；实物库存：{{ display(product.book?.physicalStock) }}
        </p>
        <fieldset :disabled="locked">
          <label
            >调整数量（非零整数）
            <input v-model="form.delta" name="delta" inputmode="numeric" /></label
          ><label>调整原因 <input v-model="form.reason" name="reason" maxlength="500" /></label>
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
        <p v-if="operation?.status === 'UNKNOWN'">结果未知，保留原请求，只能查询原操作。</p>
        <button v-if="operation?.status === 'IDLE'" :disabled="locked" @click="submit()">
          提交调整</button
        ><button v-if="operation?.status === 'FAILED'" :disabled="locked" @click="submit(true)">
          更正后重试
        </button>
        <button v-if="operation?.key" :disabled="busy || !orderRole" @click="recover">
          查询原操作</button
        ><button :disabled="busy" @click="refresh">刷新服务器库存</button>
        <button
          v-if="operation?.status === 'SUCCEEDED'"
          :disabled="busy || !orderRole"
          @click="beginNew"
        >
          明确开始新的库存调整
        </button>
      </div>
    </main>
  </div>
</template>
