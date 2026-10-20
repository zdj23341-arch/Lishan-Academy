<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import UiIcon from './ui/UiIcon.vue';
import { regionChildren, regionLabel, type RegionCodes } from '../utils/regions';

const props = defineProps<{ modelValue: RegionCodes; disabled?: boolean }>();
const emit = defineEmits<{ 'update:modelValue': [value: RegionCodes] }>();
const indices = ref<[number, number, number]>([0, 0, 0]);
const provinces = regionChildren('province');
const cities = computed(() => regionChildren('city', provinces[indices.value[0]]?.code));
const districts = computed(() => regionChildren('district', cities.value[indices.value[1]]?.code));
const columns = computed(() => [provinces, cities.value, districts.value]);
const label = computed(() => regionLabel(props.modelValue) || '请选择省 / 市 / 区');
function resetDraft() {
  const province = Math.max(
    0,
    provinces.findIndex((item) => item.code === props.modelValue[0]),
  );
  const cityOptions = regionChildren('city', provinces[province]?.code);
  const city = Math.max(
    0,
    cityOptions.findIndex((item) => item.code === props.modelValue[1]),
  );
  const districtOptions = regionChildren('district', cityOptions[city]?.code);
  const district = Math.max(
    0,
    districtOptions.findIndex((item) => item.code === props.modelValue[2]),
  );
  indices.value = [province, city, district];
}
watch(() => [...props.modelValue], resetDraft, { immediate: true });
function columnChange(event: { detail: { column: number; value: number } }) {
  if (props.disabled) return;
  const { column, value } = event.detail;
  if (!columns.value[column]?.[value]) return;
  const next: [number, number, number] = [...indices.value];
  next[column] = value;
  for (let index = column + 1; index < 3; index++) next[index] = 0;
  indices.value = next;
}
function confirm(event: { detail: { value: number[] } }) {
  if (props.disabled) return;
  const [provinceIndex, cityIndex, districtIndex] = event.detail.value;
  if (provinceIndex == null || cityIndex == null || districtIndex == null) return;
  const province = provinces[provinceIndex];
  const city = regionChildren('city', province?.code)[cityIndex];
  const district = regionChildren('district', city?.code)[districtIndex];
  if (!province || !city || !district) return;
  emit('update:modelValue', [province.code, city.code, district.code]);
}
</script>

<template>
  <picker
    mode="multiSelector"
    :range="columns"
    range-key="name"
    :value="indices"
    :disabled="disabled"
    @columnchange="columnChange"
    @change="confirm"
    @cancel="resetDraft"
  >
    <view class="region-option" :class="{ 'region-option--disabled': disabled }">
      <text :class="{ 'region-placeholder': !modelValue.some(Boolean) }">{{ label }}</text>
      <view class="region-chevron"><UiIcon name="chevron-right" :size="16" /></view>
    </view>
  </picker>
</template>
<style scoped>
.region-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  min-height: 44px;
  padding: 10px 12px;
  border: 1rpx solid #e1e8f1;
  border-radius: 8px;
  box-sizing: border-box;
  color: #18283e;
  background: #fff;
  font-size: 14px;
  line-height: 1.5;
}
.region-placeholder {
  color: #617088;
}
.region-chevron {
  display: flex;
  flex-shrink: 0;
  transform: rotate(90deg);
}
.region-option--disabled {
  opacity: 0.5;
}
</style>
