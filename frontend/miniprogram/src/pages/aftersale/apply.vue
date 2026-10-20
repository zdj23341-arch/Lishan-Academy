<script setup lang="ts">
import { customerMessage } from '../../utils/customer-text';
import { computed, ref, onUnmounted } from 'vue';
import { onLoad, onShow } from '@dcloudio/uni-app';
import { formatFen, parseYuan } from '@jxsc/shared';
import {
  createAftersaleController,
  type RefundRequest,
} from '../../../../shared/src/commerce/aftersale';
import type { CustomerOrder } from '../../../../shared/src/commerce/order';
import { aftersaleApi } from '../../api/aftersale';
import { orderApi } from '../../api/order';
import { operationKey } from '../../api/commerce';
import { session, sessionStorage, operationNamespace } from '../../api/session';
import EvidenceUpload from '../../components/EvidenceUpload.vue';
import UiIcon from '../../components/ui/UiIcon.vue';

const order = ref<CustomerOrder | null>(null),
  busy = ref(false),
  uploading = ref(false),
  error = ref('');
const fields = ref<Record<string, string>>({}),
  evidenceIds = ref<string[]>([]);
const reason = ref(''),
  goods = ref(''),
  freight = ref('0'),
  type = ref<RefundRequest['type']>('REFUND_ONLY');
const operationStatus = ref('IDLE');
let orderId = '',
  epoch = 0,
  controller: ReturnType<typeof createAftersaleController> | null = null;
const uncertain = computed(() => ['UNKNOWN', 'SUBMITTING'].includes(operationStatus.value));
const locked = computed(
  () => busy.value || uploading.value || uncertain.value || operationStatus.value === 'SUCCEEDED',
);
const canApply = computed(() => order.value?.allowedActions.includes('APPLY_AFTERSALE') ?? false);
const canReturn = computed(
  () =>
    order.value?.item.type === 'BOOK' &&
    ['SHIPPED', 'RECEIVED'].includes(order.value.fulfillmentStatus),
);
function authorized() {
  if (session.get()) return true;
  session.rememberReturnTo(`/pages/aftersale/apply?orderId=${encodeURIComponent(orderId)}`);
  uni.navigateTo({ url: '/pages/login/index' });
  return false;
}
function sync() {
  operationStatus.value = controller?.refundOperation.state.status ?? 'IDLE';
  fields.value = { ...controller?.state.fieldErrors };
}
const unsubscribe = session.subscribe(() => {
  epoch++;
  order.value = null;
  busy.value = uploading.value = false;
  error.value = '';
  fields.value = {};
  evidenceIds.value = [];
  reason.value = goods.value = '';
  freight.value = '0';
  type.value = 'REFUND_ONLY';
  operationStatus.value = 'IDLE';
});
onUnmounted(() => {
  epoch++;
  unsubscribe();
  controller?.dispose();
});
async function load() {
  if (busy.value || uploading.value || !authorized()) return;
  const ticket = epoch;
  busy.value = true;
  error.value = '';
  try {
    if (!orderId) throw new Error('缺少订单编号');
    const value = await orderApi.order(orderId);
    if (ticket === epoch) order.value = value;
  } catch (cause) {
    if (ticket === epoch) error.value = customerMessage(cause, '订单加载失败');
  } finally {
    if (ticket === epoch) {
      busy.value = false;
      sync();
    }
  }
}
async function submit(recover = false) {
  if (busy.value || uploading.value || !controller || !authorized()) return;
  const ticket = epoch;
  busy.value = true;
  error.value = '';
  fields.value = {};
  try {
    if (recover) await controller.recoverRefund();
    else {
      const body: RefundRequest = {
        type: type.value,
        reason: reason.value,
        evidenceAssetIds: [...evidenceIds.value],
        requestedGoodsFen: parseYuan(goods.value),
        requestedFreightFen: parseYuan(freight.value),
      };
      await controller.refund(body);
    }
    if (ticket !== epoch) return;
    const id = controller.refundOperation.state.resourceId;
    if (controller.refundOperation.state.status === 'SUCCEEDED' && id)
      uni.redirectTo({ url: `/pages/aftersale/detail?id=${encodeURIComponent(id)}` });
  } catch (cause) {
    if (ticket === epoch) error.value = customerMessage(cause, '申请结果未知，请查询原操作');
  } finally {
    if (ticket === epoch) {
      busy.value = false;
      sync();
    }
  }
}
function ready(id: string) {
  if (!evidenceIds.value.includes(id) && evidenceIds.value.length < 10) evidenceIds.value.push(id);
}
function newApplication() {
  if (controller && !uncertain.value && !busy.value) {
    controller.refundOperation.reset();
    sync();
  }
}
onLoad((options) => {
  orderId = options?.orderId || '';
  controller = createAftersaleController({
    api: aftersaleApi,
    orderApi,
    session,
    storage: sessionStorage,
    namespace: operationNamespace,
    orderId,
    getOrder: () => order.value,
    onOrder: (value) => {
      order.value = value;
    },
    keyGenerator: operationKey,
  });
  sync();
});
onShow(() => {
  void load();
});
</script>

<template>
  <view class="ls-mini-page aftersale-page ui-page-container">
    <view v-if="error" role="alert" class="ls-alert">{{ error }}</view>
    <template v-for="(message, field) in fields" :key="field">
      <view
        v-if="
          ![
            'type',
            'reason',
            'requestedGoodsFen',
            'requestedFreightFen',
            'evidenceAssetIds',
          ].includes(field)
        "
        role="alert"
        class="ls-alert"
        >{{ customerMessage(message, '填写内容不符合要求，请核对后重试') }}</view
      >
    </template>
    <button
      v-if="error || !order"
      class="ls-button ls-button--soft"
      :disabled="busy || uploading"
      @click="load"
    >
      刷新订单
    </button>
    <view v-if="order" class="ui-card aftersale-form">
      <view class="order-summary">
        <text class="order-summary__title">{{ order.item.title }}</text>
        <text class="order-summary__number">订单号：{{ order.orderNo }}</text>
        <text class="order-summary__amounts"
          >商品金额 ¥{{ formatFen(order.goodsAmountFen) }} · 运费 ¥{{
            formatFen(order.freightAmountFen)
          }}</text
        >
      </view>
      <view v-if="!canApply" class="application-notice"
        >当前订单不可新建售后，可查询原申请结果。</view
      >
      <view class="form-field">
        <text class="field-label">售后类型</text>
        <picker
          v-if="canReturn"
          :range="['仅退款', '退货退款']"
          :value="type === 'RETURN_REFUND' ? 1 : 0"
          :disabled="locked"
          @change="
            type = canReturn && Number($event.detail.value) === 1 ? 'RETURN_REFUND' : 'REFUND_ONLY'
          "
        >
          <view class="type-select" :class="{ 'is-locked': locked }">
            <text>{{ type === 'RETURN_REFUND' ? '退货退款' : '仅退款' }}</text>
            <view class="type-select__arrow"><UiIcon name="chevron-right" :size="16" /></view>
          </view>
        </picker>
        <view v-else class="type-select aftersale-type-readonly" :class="{ 'is-locked': locked }">
          {{ type === 'RETURN_REFUND' ? '退货退款' : '仅退款' }}
        </view>
        <text v-if="fields.type" role="alert" class="field-error">{{
          customerMessage(fields.type, '请选择售后类型')
        }}</text>
      </view>
      <view class="refund-fields">
        <view class="form-field">
          <label class="field-label" for="refund-goods">商品退款金额（元）</label>
          <input
            id="refund-goods"
            v-model="goods"
            :disabled="locked"
            inputmode="decimal"
            placeholder="最多两位小数"
          />
          <text v-if="fields.requestedGoodsFen" role="alert" class="field-error">{{
            customerMessage(fields.requestedGoodsFen, '请核对商品退款金额')
          }}</text>
        </view>
        <view class="form-field">
          <label class="field-label" for="refund-freight">运费退款金额（元）</label>
          <input
            id="refund-freight"
            v-model="freight"
            :disabled="locked"
            inputmode="decimal"
            placeholder="0.00"
          />
          <text v-if="fields.requestedFreightFen" role="alert" class="field-error">{{
            customerMessage(fields.requestedFreightFen, '请核对运费退款金额')
          }}</text>
        </view>
      </view>
      <view class="form-field">
        <label class="field-label" for="refund-reason">申请原因</label>
        <textarea
          id="refund-reason"
          v-model="reason"
          :disabled="locked"
          maxlength="500"
          placeholder="描述问题，帮助我们处理申请"
        />
        <text v-if="fields.reason" role="alert" class="field-error">{{
          customerMessage(fields.reason, '请填写申请原因')
        }}</text>
      </view>
      <EvidenceUpload
        :disabled="locked || !canApply"
        :count="evidenceIds.length"
        @ready="ready"
        @busy="uploading = $event"
        @error="error = $event"
      />
      <text v-if="fields.evidenceAssetIds" role="alert" class="field-error">{{
        customerMessage(fields.evidenceAssetIds, '请核对图片凭证')
      }}</text>
      <view v-for="(id, index) in evidenceIds" :key="id" class="evidence-record"
        >凭证{{ index + 1 }}已上传<button
          :disabled="locked"
          @click="evidenceIds = evidenceIds.filter((value) => value !== id)"
        >
          移除
        </button></view
      >
      <view class="aftersale-bottom-action">
        <button class="ui-btn ui-btn--primary" :disabled="locked || !canApply" @click="submit()">
          提交售后申请
        </button>
      </view>
    </view>
    <view v-if="uncertain">原申请结果尚未确认，请勿重新提交。</view>
    <button v-if="operationStatus !== 'IDLE'" :disabled="busy || uploading" @click="submit(true)">
      查询原申请结果
    </button>
    <button
      v-if="operationStatus === 'SUCCEEDED' && canApply"
      :disabled="busy || uploading"
      @click="newApplication"
    >
      开始新的售后申请
    </button>
  </view>
</template>
<style lang="scss" scoped>
@import '../../styles/tokens.scss';
.aftersale-page {
  padding: 16px 16px calc(96px + env(safe-area-inset-bottom));
}
.aftersale-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
  margin: 0;
  padding: 16px;
  border-radius: 13px;
}
.order-summary {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding-bottom: 16px;
  border-bottom: 1px solid $color-border;
}
.order-summary__title {
  font-size: 14px;
  font-weight: 600;
  line-height: 1.5;
}
.order-summary__number,
.order-summary__amounts {
  font-size: 12px;
  color: $color-text-secondary;
  line-height: 1.6;
  overflow-wrap: anywhere;
}
.form-field {
  display: flex;
  flex-direction: column;
  min-width: 0;
  gap: 8px;
}
.field-label {
  font-size: 13px;
  color: $color-text-secondary;
  line-height: 1.5;
}
.refund-fields {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.aftersale-form input,
.aftersale-form textarea,
.type-select {
  width: 100%;
  box-sizing: border-box;
  padding: 10px 12px;
  background: #fff;
  border: 1px solid $color-border;
  border-radius: 8px;
  min-height: 44px;
  font-size: 14px;
  line-height: 1.5;
}
.aftersale-form input {
  height: 44px;
}
.aftersale-form textarea {
  height: 96px;
}
.type-select {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.is-locked {
  opacity: 0.6;
}
.type-select__arrow {
  display: flex;
  flex-shrink: 0;
  transform: rotate(90deg);
}
.field-error {
  color: #c04436;
  font-size: 12px;
  line-height: 1.5;
}
.application-notice {
  padding: 12px;
  border-radius: 8px;
  background: #fff5df;
  color: #976014;
  font-size: 13px;
  line-height: 1.6;
}
.aftersale-page button {
  font-size: 14px;
  border-radius: 8px;
  min-height: 44px;
  min-width: 44px;
  box-shadow: none;
}
.evidence-record {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  font-size: 13px;
  line-height: 1.6;
}
.aftersale-page .ls-alert {
  padding: 12px;
  background: #fff0ee;
  border-radius: 8px;
  margin-bottom: 16px;
}
.aftersale-bottom-action {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  z-index: 990;
  padding: 12px 16px calc(12px + env(safe-area-inset-bottom));
  background: #fff;
  border-top: 1rpx solid $color-border;
}
.aftersale-page .ui-card > .aftersale-bottom-action {
  margin: 0;
}
.aftersale-bottom-action .ui-btn {
  width: 100%;
  min-height: 44px;
  margin: 0;
  font-weight: 400;
}
</style>
