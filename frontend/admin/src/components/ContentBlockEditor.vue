<script setup lang="ts">
import { ElButton } from 'element-plus';
import { ref, toRaw } from 'vue';
import type { ContentBlockInput } from '@jxsc/shared/content/admin-types';
import type { UploadServices } from '@jxsc/shared/content/upload';
import { moveItem } from '@jxsc/shared/content/validation';
import AssetPicker from './AssetPicker.vue';
const props = defineProps<{ modelValue: ContentBlockInput[]; services?: UploadServices }>();
const emit = defineEmits<{
  'update:modelValue': [value: ContentBlockInput[]];
  busy: [value: boolean];
}>();
const uploading = ref(new Set<number>());
const keys = new WeakMap<ContentBlockInput, number>();
let nextKey = 0;
function key(block: ContentBlockInput) {
  const identity = toRaw(block);
  if (!keys.has(identity)) keys.set(identity, ++nextKey);
  return keys.get(identity)!;
}
function uploadBusy(block: ContentBlockInput, value: boolean) {
  if (value) uploading.value.add(key(block));
  else uploading.value.delete(key(block));
  emit('busy', uploading.value.size > 0);
}
function add(type: 'TEXT' | 'IMAGE') {
  emit('update:modelValue', [
    ...props.modelValue,
    type === 'TEXT' ? { type, text: '' } : { type, assetId: '', alt: '' },
  ]);
}
function update(index: number, block: ContentBlockInput) {
  keys.set(toRaw(block), key(props.modelValue[index]!));
  emit(
    'update:modelValue',
    props.modelValue.map((value, i) => (i === index ? block : value)),
  );
}
function remove(index: number) {
  emit(
    'update:modelValue',
    props.modelValue.filter((_, i) => i !== index),
  );
}
function move(from: number, to: number) {
  emit('update:modelValue', moveItem(props.modelValue, from, to));
}
</script>
<template>
  <fieldset class="content-block-editor" :disabled="uploading.size > 0">
    <legend>图文内容</legend>
    <fieldset v-for="(block, index) in modelValue" :key="key(block)">
      <legend>内容 {{ index + 1 }}</legend>
      <label v-if="block.type === 'TEXT'"
        >正文
        <textarea
          :value="block.text"
          @input="
            update(index, { type: 'TEXT', text: ($event.target as HTMLTextAreaElement).value })
          "
        />
      </label>
      <template v-else
        ><AssetPicker
          :model-value="block.assetId"
          :services="services"
          @busy="uploadBusy(block, $event)"
          @update:model-value="update(index, { ...block, assetId: $event })"
        /><label
          >图片说明
          <input
            :value="block.alt"
            @input="update(index, { ...block, alt: ($event.target as HTMLInputElement).value })"
        /></label>
        <p>此处图片说明仅作本次编辑备注；需要保存并公开显示的说明，请使用“添加文字”。</p>
      </template>
      <el-button :disabled="index === 0" @click="move(index, index - 1)">上移内容</el-button
      ><el-button :disabled="index === modelValue.length - 1" @click="move(index, index + 1)"
        >下移内容</el-button
      ><el-button @click="remove(index)">删除内容</el-button>
    </fieldset>
    <el-button @click="add('TEXT')">添加文字</el-button
    ><el-button @click="add('IMAGE')">添加图片</el-button>
  </fieldset>
</template>
<style scoped>
.content-block-editor > fieldset > label {
  display: flex;
  width: 100%;
  margin-right: 0;
}
.content-block-editor textarea {
  width: 100%;
  min-height: 180px;
  line-height: 1.7;
}
</style>
