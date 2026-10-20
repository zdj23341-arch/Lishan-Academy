<script setup lang="ts">
import { customerMessage } from '../../utils/customer-text';
import { computed, ref, onUnmounted } from 'vue';
import { onLoad, onShow } from '@dcloudio/uni-app';
import { createAddressController } from '../../../../shared/src/commerce/address';
import { commerceApi } from '../../api/commerce';
import { session } from '../../api/session';
import { findAddress } from '../../utils/address';
import type { RegionCodes } from '../../utils/regions';
import RegionPicker from '../../components/RegionPicker.vue';

const controller = createAddressController(commerceApi);
const name = ref(''),
  phone = ref(''),
  detail = ref('');
const region = ref<RegionCodes>(['', '', '']);
const isDefault = ref(false),
  isEdit = ref(false),
  busy = ref(false),
  ready = ref(false);
const error = ref(''),
  fieldErrors = ref<Record<string, string>>({});
const regionError = computed(() =>
  [
    ...new Set(
      ['provinceCode', 'cityCode', 'districtCode']
        .map((key) => fieldErrors.value[key])
        .filter(Boolean),
    ),
  ].join('；'),
);
let id = '',
  version = 0,
  epoch = 0;
function authorized() {
  if (session.get()) return true;
  session.rememberReturnTo(`/pages/address/edit${id ? `?id=${encodeURIComponent(id)}` : ''}`);
  uni.navigateTo({ url: '/pages/login/index' });
  return false;
}
const unsubscribe = session.subscribe(() => {
  epoch++;
  ready.value = false;
  busy.value = false;
  name.value = phone.value = detail.value = '';
  region.value = ['', '', ''];
  isDefault.value = false;
  version = 0;
  error.value = '';
  fieldErrors.value = {};
});
function changeDefault(event: Event | { detail: { value: boolean } }) {
  if (!busy.value && ready.value && 'detail' in event) isDefault.value = event.detail.value;
}
function inputPhone(event: unknown) {
  const input = event as { detail?: { value?: unknown }; target?: { value?: unknown } };
  phone.value = String(input.detail?.value ?? input.target?.value ?? '');
}
onUnmounted(() => {
  epoch++;
  unsubscribe();
});
async function load(force = false) {
  if (busy.value || !authorized() || (ready.value && !force)) return;
  if (!id) {
    ready.value = true;
    return;
  }
  const ticket = ++epoch;
  busy.value = true;
  error.value = '';
  try {
    const address = await findAddress(id);
    if (ticket !== epoch) return;
    if (!address) throw new Error('地址不存在，请返回列表刷新');
    name.value = address.name;
    phone.value = address.phone;
    detail.value = address.detail;
    region.value = [address.provinceCode, address.cityCode, address.districtCode];
    isDefault.value = address.isDefault;
    version = address.version;
    ready.value = true;
  } catch (reason) {
    if (ticket === epoch) error.value = customerMessage(reason, '加载地址失败');
  } finally {
    if (ticket === epoch) busy.value = false;
  }
}
async function save() {
  if (busy.value || !authorized() || !ready.value) return;
  const ticket = epoch;
  busy.value = true;
  error.value = '';
  fieldErrors.value = {};
  try {
    await controller.save(
      {
        version,
        name: name.value,
        phone: phone.value,
        provinceCode: region.value[0],
        cityCode: region.value[1],
        districtCode: region.value[2],
        detail: detail.value,
        isDefault: isDefault.value,
      },
      id || undefined,
    );
    if (ticket === epoch) uni.navigateBack();
  } catch (reason) {
    if (ticket === epoch) {
      fieldErrors.value = { ...controller.state.fieldErrors };
      error.value = Object.keys(fieldErrors.value).length
        ? ''
        : customerMessage(reason, '保存地址失败');
    }
  } finally {
    if (ticket === epoch) busy.value = false;
  }
}
onLoad((options) => {
  id = options?.id || '';
  isEdit.value = !!id;
});
onShow(() => {
  void load();
});
</script>

<template>
  <view class="ls-mini-page address-edit-page ui-page-container">
    <view v-if="error" role="alert" class="ls-alert"
      >{{ error
      }}<button v-if="isEdit" :disabled="busy" @click="load(true)">重新加载地址</button></view
    >
    <view class="form-card ui-card">
      <view class="form-row" data-field="name">
        <text class="form-label">收货人</text>
        <input
          v-model="name"
          :disabled="busy || !ready"
          class="form-input"
          placeholder="请填写收件人姓名"
          placeholder-class="input-placeholder"
        />
        <text v-if="fieldErrors.name" role="alert" class="field-error">{{
          customerMessage(fieldErrors.name, '请核对收货人')
        }}</text>
      </view>

      <view class="form-row" data-field="phone">
        <text class="form-label">手机号码</text>
        <input
          :value="phone"
          @input="inputPhone"
          :disabled="busy || !ready"
          class="form-input"
          type="number"
          maxlength="11"
          placeholder="请填写11位手机号码"
          placeholder-class="input-placeholder"
        />
        <text v-if="fieldErrors.phone" role="alert" class="field-error">{{
          customerMessage(fieldErrors.phone, '请核对手机号码')
        }}</text>
      </view>

      <view class="form-row" data-field="region">
        <text class="form-label">所在地区</text>
        <RegionPicker v-model="region" :disabled="busy || !ready" class="region-picker" />
        <text v-if="regionError" role="alert" class="field-error">{{
          customerMessage(regionError, '请选择完整地区')
        }}</text>
      </view>

      <view class="form-row form-row--textarea">
        <text class="form-label">详细地址</text>
        <textarea
          v-model="detail"
          :disabled="busy || !ready"
          class="form-textarea"
          placeholder="如道路、门牌号、小区、楼栋号、单元室等"
          placeholder-class="input-placeholder"
        />
        <text v-if="fieldErrors.detail" role="alert" class="field-error">{{
          customerMessage(fieldErrors.detail, '请核对详细地址')
        }}</text>
      </view>
      <view class="switch-card">
        <text class="switch-title">设为默认地址</text>
        <switch
          :checked="isDefault"
          :disabled="busy || !ready"
          color="#2855b7"
          @change="changeDefault"
        />
      </view>
    </view>

    <!-- 底部保存按钮 -->
    <view class="bottom-bar">
      <button
        class="ui-btn ui-btn--primary save-btn"
        :disabled="busy || !ready"
        :loading="busy"
        @click="save"
      >
        {{ isEdit ? '保存修改' : '确认添加' }}
      </button>
    </view>
  </view>
</template>

<style lang="scss" scoped>
@import '../../styles/tokens.scss';

.address-edit-page {
  padding: 16px 16px calc(96px + env(safe-area-inset-bottom));
}

.form-card {
  padding: 16px;
  margin: 0;
  border-radius: 13px;
}

.form-row {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 8px;
  padding: 0 0 16px;

  &:last-child {
    border-bottom: none;
  }

  &--textarea {
    align-items: stretch;
  }

  .form-label {
    width: 100%;
    flex-shrink: 0;
    font-size: 12px;
    color: $color-text-secondary;
    line-height: 1.55;
    font-weight: 400;
  }

  .form-input {
    width: 100%;
    box-sizing: border-box;
    height: 44px;
    min-height: 44px;
    padding: 10px 12px;
    border: 1rpx solid $color-border;
    border-radius: 8px;
    min-width: 0;
    font-size: 14px;
    color: $color-text-main;
  }

  .region-picker {
    flex: 1;
    min-width: 0;
  }

  .picker-value {
    display: flex;
    align-items: center;
    justify-content: space-between;
    font-size: 28rpx;
    color: $color-text-main;

    .svg-arrow {
      width: 32rpx;
      height: 32rpx;
      color: $color-text-secondary;
    }
  }

  .form-textarea {
    width: 100%;
    box-sizing: border-box;
    padding: 10px 12px;
    border: 1rpx solid $color-border;
    border-radius: 8px;
    min-width: 0;
    height: 72px;
    font-size: 14px;
    color: $color-text-main;
    line-height: 1.45;
  }

  .input-placeholder {
    color: $color-text-muted;
    font-size: 14px;
  }
}
.field-error {
  color: #c04436;
  font-size: 12px;
  line-height: 1.5;
}

.switch-card {
  min-height: 44px;
  display: flex;
  align-items: center;
  justify-content: space-between;

  .switch-title {
    font-size: 14px;
    line-height: 1.55;
    font-weight: 400;
    color: $color-text-main;
  }
}

.bottom-bar {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 990;
  padding: 12px 16px calc(12px + env(safe-area-inset-bottom));
  background: #fff;
  border-top: 1rpx solid $color-border;

  .save-btn {
    width: 100%;
    height: 44px;
    min-height: 44px;
    margin: 0;
    border-radius: 8px;
    font-size: 14px;
    font-weight: 400;
    box-shadow: none;
  }
}
</style>
