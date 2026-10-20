<script setup lang="ts">
import type { Target } from '@jxsc/shared';
import ContentPicker from './ContentPicker.vue';
const props = defineProps<{ modelValue: Target; disabled?: boolean }>();
const emit = defineEmits<{ 'update:modelValue': [value: Target] }>();
function changeType(event: Event) {
  const type = (event.target as HTMLSelectElement).value as Target['type'];
  emit('update:modelValue', { type, id: null, filters: {} });
}
function changeId(ids: string[]) {
  emit('update:modelValue', {
    ...props.modelValue,
    id: ids[0] ?? null,
  });
}
function filter(key: string, event: Event) {
  const value = (event.target as HTMLSelectElement).value;
  const filters = { ...props.modelValue.filters };
  if (value) filters[key] = value;
  else delete filters[key];
  emit('update:modelValue', { ...props.modelValue, filters });
}
</script>
<template>
  <fieldset :disabled="disabled">
    <legend>内部跳转目标</legend>
    <label
      >目标类型
      <select :value="modelValue.type" @change="changeType">
        <option value="PRODUCT">商品详情</option>
        <option value="COURSE_DETAIL">课程详情</option>
        <option value="PRODUCT_LIST">商品列表</option>
        <option value="MEMBERSHIP">会员计划</option>
        <option value="MEMBERSHIP_CENTER">会员中心</option>
        <option value="PAPER_LIST">试卷列表</option>
        <option value="LEARNING">我的学习</option>
        <option value="ORDER_LIST">我的订单</option>
        <option value="LOGISTICS">订单物流</option>
        <option value="ARTICLE">图文详情</option>
      </select></label
    >
    <div
      v-if="
        modelValue.type === 'PRODUCT' ||
        modelValue.type === 'ARTICLE' ||
        modelValue.type === 'COURSE_DETAIL'
      "
    >
      <ContentPicker
        :model-value="modelValue.id ? [modelValue.id] : []"
        :kind="
          modelValue.type === 'COURSE_DETAIL'
            ? 'COURSE_DETAIL'
            : modelValue.type === 'ARTICLE'
              ? 'ARTICLE'
              : 'PRODUCT'
        "
        single
        :disabled="disabled"
        @update:model-value="changeId"
      />
    </div>
    <label v-if="modelValue.type === 'PRODUCT_LIST'"
      >商品类型
      <select :value="modelValue.filters.type ?? ''" @change="filter('type', $event)">
        <option value="">全部</option>
        <option value="COURSE">课程</option>
        <option value="BOOK">图书</option>
        <option value="MEMBERSHIP">会员</option>
      </select></label
    >
    <label v-if="modelValue.type === 'PAPER_LIST'"
      >学段
      <select :value="modelValue.filters.schoolStage ?? ''" @change="filter('schoolStage', $event)">
        <option value="">全部</option>
        <option value="PRIMARY">小学</option>
        <option value="JUNIOR">初中</option>
        <option value="SENIOR">高中</option>
      </select></label
    >
    <label v-if="modelValue.type === 'PAPER_LIST'"
      >年级
      <select :value="modelValue.filters.grade ?? ''" @change="filter('grade', $event)">
        <option value="">全部</option>
        <option v-for="grade in ['6', '7', '8', '9']" :key="grade" :value="grade">
          {{ grade }}年级
        </option>
      </select></label
    >
    <label v-if="modelValue.type === 'PAPER_LIST'"
      >试卷类型
      <select :value="modelValue.filters.paperType ?? ''" @change="filter('paperType', $event)">
        <option value="">全部</option>
        <option value="PRACTICE">练习</option>
        <option value="FINAL_EXAM">期中期末</option>
        <option value="PAST_EXAM">真题</option>
      </select></label
    >
  </fieldset>
</template>
