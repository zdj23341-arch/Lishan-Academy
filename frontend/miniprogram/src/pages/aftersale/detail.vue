<script setup lang="ts">
import { customerMessage, aftersaleStatusLabel, carrierLabel } from '../../utils/customer-text';
import { ref, computed, onUnmounted } from 'vue';
import { onLoad, onShow } from '@dcloudio/uni-app';
import { formatFen } from '@jxsc/shared';
import {
  createAftersaleController,
  type AftersaleView,
} from '../../../../shared/src/commerce/aftersale';
import type { CustomerOrder } from '../../../../shared/src/commerce/order';
import { aftersaleApi } from '../../api/aftersale';
import { orderApi } from '../../api/order';
import { logisticsApi, type CarrierOption } from '../../api/logistics';
import { operationKey } from '../../api/commerce';
import { session, sessionStorage, operationNamespace } from '../../api/session';
const detail = ref<AftersaleView | null>(null),
  order = ref<CustomerOrder | null>(null),
  busy = ref(false),
  error = ref(''),
  fields = ref<Record<string, string>>({});
const carrier = ref(''),
  tracking = ref('');
const carrierOptions = ref<CarrierOption[]>([]);
const carrierLabels = computed(() =>
  carrierOptions.value.map((option) => `${option.name}（${option.code}）`),
);
const carrierName = computed(
  () => carrierOptions.value.find((option) => option.code === carrier.value)?.name ?? '',
);
function pickCarrier(event: { detail: { value: number | string } }) {
  const option = carrierOptions.value[Number(event.detail.value)];
  if (option) carrier.value = option.code;
}
async function loadCarriers() {
  try {
    const list = await logisticsApi.carriers();
    carrierOptions.value = Array.isArray(list) ? list : [];
  } catch {
    // The code field below still works when the catalog cannot be loaded.
    carrierOptions.value = [];
  }
}
const returnStatus = ref('IDLE');
const uncertain = computed(() => ['UNKNOWN', 'SUBMITTING'].includes(returnStatus.value));
let id = '',
  epoch = 0,
  controller: ReturnType<typeof createAftersaleController> | null = null;
const canReturn = computed(
  () =>
    detail.value?.status === 'RETURN_REQUIRED' &&
    detail.value.allowedActions.includes('SUBMIT_RETURN_SHIPMENT'),
);
const returnAddress = computed(() => {
  const value = detail.value?.returnAddress;
  if (typeof value === 'string') return value;
  if (value && typeof value === 'object')
    return Object.values(value)
      .filter((v) => typeof v === 'string')
      .join(' ');
  return '';
});
function authorized() {
  if (session.get()) return true;
  session.rememberReturnTo(`/pages/aftersale/detail?id=${encodeURIComponent(id)}`);
  uni.navigateTo({ url: '/pages/login/index' });
  return false;
}
const unsubscribe = session.subscribe(() => {
  epoch++;
  detail.value = order.value = null;
  busy.value = false;
  error.value = '';
  fields.value = {};
  carrier.value = tracking.value = '';
  controller?.dispose();
  controller = null;
});
onUnmounted(() => {
  epoch++;
  unsubscribe();
  controller?.dispose();
});
async function load() {
  if (busy.value || !authorized()) return;
  const ticket = epoch;
  busy.value = true;
  error.value = '';
  try {
    if (!id) throw new Error('缺少售后单编号');
    const value = await aftersaleApi.aftersale(id);
    if (ticket !== epoch) return;
    const current = await orderApi.order(value.orderId);
    if (ticket !== epoch) return;
    detail.value = value;
    order.value = current;
    if (!controller)
      controller = createAftersaleController({
        api: aftersaleApi,
        orderApi,
        session,
        storage: sessionStorage,
        namespace: operationNamespace,
        orderId: value.orderId,
        getOrder: () => order.value,
        getAftersale: () => detail.value,
        onOrder: (v) => {
          order.value = v;
        },
        onAftersale: (v) => {
          detail.value = v;
        },
        keyGenerator: operationKey,
      });
  } catch (cause) {
    if (ticket === epoch) error.value = customerMessage(cause, '售后加载失败');
  } finally {
    if (ticket === epoch) {
      busy.value = false;
      returnStatus.value = controller?.getReturnStatus(id) ?? 'IDLE';
    }
  }
}
async function submit(recover = false) {
  if (busy.value || !controller || !authorized()) return;
  const ticket = epoch;
  busy.value = true;
  error.value = '';
  fields.value = {};
  try {
    if (recover) await controller.recoverReturn(id);
    else
      await controller.returnShipment(id, {
        carrierCode: carrier.value,
        trackingNo: tracking.value,
      });
  } catch (cause) {
    if (ticket === epoch) {
      error.value = customerMessage(cause, '提交结果未知，请查询原操作');
      fields.value = { ...controller.state.fieldErrors };
    }
  } finally {
    if (ticket === epoch) {
      busy.value = false;
      returnStatus.value = controller?.getReturnStatus(id) ?? 'IDLE';
    }
  }
}
onLoad((options) => {
  id = options?.id || '';
  void loadCarriers();
});
onShow(() => {
  void load();
});
</script>

<template>
  <view class="ls-mini-page aftersale-page ui-page-container"
    ><view class="aftersale-header"
      ><text class="ls-mini-heading">售后进度</text
      ><text class="ls-mini-description">退款与退货结果以订单实际状态为准。</text></view
    >
    <view v-if="error" role="alert" class="ls-alert">{{ error }}</view
    ><view v-for="(message, field) in fields" :key="field" role="alert" class="ls-alert">{{
      customerMessage(message, '填写内容不符合要求，请核对后重试')
    }}</view>
    <button class="ls-button ls-button--soft" :disabled="busy" @click="load">刷新售后状态</button>
    <view v-if="detail" class="ui-card">
      <text>{{ aftersaleStatusLabel(detail.status) }} · {{ detail.id }}</text>
      <view>申请原因：{{ detail.reason }}</view>
      <view>申请商品退款：¥{{ formatFen(detail.requestedGoodsFen) }}</view
      ><view>申请运费退款：¥{{ formatFen(detail.requestedFreightFen) }}</view>
      <view v-if="detail.approvedGoodsFen !== null"
        >已批准商品金额：¥{{ formatFen(detail.approvedGoodsFen) }}</view
      ><view v-if="detail.approvedFreightFen !== null"
        >已批准运费金额：¥{{ formatFen(detail.approvedFreightFen) }}</view
      >
      <view v-if="detail.rejectionReason">拒绝原因：{{ detail.rejectionReason }}</view
      ><view v-if="detail.failureReason"
        >失败原因：{{
          customerMessage(detail.failureReason, '售后处理暂未成功，请刷新结果或联系客服')
        }}</view
      >
      <view v-if="detail.returnedQuantity !== null"
        >实际退货数量：{{ detail.returnedQuantity }}</view
      ><view v-if="detail.receivedQuantity !== null"
        >已收到数量：{{ detail.receivedQuantity }}</view
      >
      <view v-if="returnAddress">退货地址：{{ returnAddress }}</view>
      <view v-if="detail.returnTrackingNo"
        >已提交物流：{{ carrierLabel(detail.returnCarrierCode) }}
        {{ detail.returnTrackingNo }}</view
      >
      <view v-if="canReturn">
        <picker
          v-if="carrierOptions.length"
          :range="carrierLabels"
          :disabled="busy || uncertain"
          @change="pickCarrier"
        >
          <view class="carrier-picker">物流公司：{{ carrierName || '请选择' }}</view>
        </picker>
        <view>物流公司代码</view
        ><input
          v-model="carrier"
          :disabled="busy || uncertain"
          maxlength="40"
          placeholder="选择上方公司，或填写代码（如 shunfeng）"
        />
        <view>退货运单号</view
        ><input v-model="tracking" :disabled="busy || uncertain" maxlength="100" />
        <button class="ui-btn ui-btn--primary" :disabled="busy || uncertain" @click="submit()">
          提交退货物流
        </button>
      </view>
      <view v-if="uncertain">原提交结果尚未确认，请先查询。</view>
      <button v-if="returnStatus !== 'IDLE'" :disabled="busy" @click="submit(true)">
        查询原退货提交结果
      </button>
    </view>
  </view>
</template>
<style lang="scss" scoped>
@import '../../styles/tokens.scss';
.aftersale-page {
  padding: 32rpx 32rpx calc(80rpx + env(safe-area-inset-bottom));
}
.aftersale-page > .ui-card {
  margin: 24rpx 0;
  padding: 32rpx;
}
.aftersale-page input,
.aftersale-page textarea,
.aftersale-page .carrier-picker {
  width: 100%;
  padding: 20rpx 24rpx;
  margin: 12rpx 0 28rpx;
  background: $color-bg-page;
  border: 1rpx solid $color-border;
  border-radius: 16rpx;
  min-height: 88rpx;
}
.aftersale-page button {
  margin: 20rpx 0;
  font-size: 28rpx;
  border-radius: 16rpx;
}
.aftersale-page .ui-card > view {
  margin: 16rpx 0;
  line-height: 1.7;
}
.aftersale-page .ui-card > text {
  font-size: 30rpx;
  font-weight: 600;
  display: block;
  margin-bottom: 24rpx;
}
.aftersale-page .ls-alert {
  padding: 24rpx;
  background: #fff0ee;
  border-radius: 16rpx;
  margin-bottom: 24rpx;
}
</style>
