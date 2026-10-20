<script setup lang="ts">
import { computed, ref } from 'vue';
import { environment } from '../api/environment';
import type { CommerceScenarioResult } from '../../../shared/src/dev/commerce-scenarios';

const enabled = () =>
  environment.provider === 'mock' && ['development', 'test'].includes(environment.mode);
const visible = computed(enabled);
const busy = ref(false);
const error = ref('');
const result = ref<CommerceScenarioResult>();
async function prepare() {
  if (!enabled() || busy.value) return;
  busy.value = true;
  try {
    const [{ prepareCommerceScenarios }, { devTransport }] = await Promise.all([
      import('../../../shared/src/dev/commerce-scenarios'),
      import('../../../shared/src/dev/transport'),
    ]);
    if (!enabled()) return;
    result.value = await prepareCommerceScenarios(devTransport, environment);
    error.value = '';
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '本地开发示例 数据准备失败';
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <details v-if="visible" class="ls-dev-panel">
    <summary>本地演示工具</summary>
    <button :disabled="busy" @click="prepare">
      {{
        busy
          ? '正在准备本地开发示例 数据…'
          : error
            ? '重试准备本地开发示例 数据'
            : '准备本地开发示例 测试交易'
      }}
    </button>
    <p>本地开发示例 数据 · 仅当前浏览器内存 · 刷新后重置 · 非真实接口联调</p>
    <p v-if="error" role="alert">{{ error }}</p>
    <div v-if="result" role="status">
      <p>本地开发示例 测试交易已准备</p>
      <ul>
        <li>
          <RouterLink :to="`/commerce/orders/${result.shipmentOrderId}`"
            >待发货订单 {{ result.shipmentOrderId }}</RouterLink
          >
        </li>
        <li>
          <RouterLink :to="`/commerce/orders/${result.returnOrderId}`"
            >退货订单 {{ result.returnOrderId }}</RouterLink
          >
          ·
          <RouterLink :to="{ path: '/commerce/aftersales', query: { id: result.returnCaseId } }"
            >退货审批 {{ result.returnCaseId }}</RouterLink
          >
        </li>
        <li>
          <RouterLink :to="`/commerce/orders/${result.refundOrderId}`"
            >退款订单 {{ result.refundOrderId }}</RouterLink
          >
          ·
          <RouterLink :to="{ path: '/commerce/aftersales', query: { id: result.refundCaseId } }"
            >退款驳回 {{ result.refundCaseId }}</RouterLink
          >
        </li>
      </ul>
    </div>
  </details>
</template>

<style scoped>
.ls-dev-panel {
  margin: 16px 22px 0;
  padding: 10px 14px;
  border: 1px solid #e5ebf3;
  border-radius: 8px;
  background: #fff;
  font-size: 12px;
  color: #617088;
}
.ls-dev-panel summary {
  cursor: pointer;
}
.ls-dev-panel button {
  padding: 8px 12px;
  border: 1px solid #e5ebf3;
  border-radius: 8px;
  background: #edf3ff;
  color: #2855b7;
  margin-top: 12px;
}
.ls-dev-panel a {
  color: #2855b7;
}
.ls-dev-panel p {
  line-height: 1.6;
}
</style>
