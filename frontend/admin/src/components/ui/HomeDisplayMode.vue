<script setup lang="ts">
import { getCurrentInstance } from 'vue';

const groupName = `home-display-mode-${getCurrentInstance()!.uid}`;

withDefaults(
  defineProps<{
    modelValue: 'automatic' | 'manual';
    count: number;
    busy?: boolean;
    existing?: boolean;
  }>(),
  { busy: false, existing: true },
);
const emit = defineEmits<{
  'update:modelValue': [value: 'automatic' | 'manual'];
  'update:count': [value: number];
}>();
</script>
<template>
  <fieldset class="ls-home-mode" :disabled="busy">
    <legend>首页内容怎么展示</legend>
    <p class="ls-home-mode-intro">
      控制这个栏目从哪里选择内容。当前设置保存后，还需发布首页才会对用户生效。
    </p>
    <div class="ls-home-mode-options">
      <label class="ls-home-mode-card" :class="{ selected: modelValue === 'automatic' }">
        <input
          type="radio"
          :name="groupName"
          value="automatic"
          :disabled="busy"
          :checked="modelValue === 'automatic'"
          @change="emit('update:modelValue', 'automatic')"
        />
        <span
          ><strong>自动展示最新内容</strong
          ><small>按栏目类型展示已发布内容；置顶内容优先，失效内容自动跳过。</small></span
        >
      </label>
      <label class="ls-home-mode-card" :class="{ selected: modelValue === 'manual' }">
        <input
          type="radio"
          :name="groupName"
          value="manual"
          :disabled="busy"
          :checked="modelValue === 'manual'"
          @change="emit('update:modelValue', 'manual')"
        />
        <span
          ><strong>手动精选</strong
          ><small>{{
            existing
              ? '保留当前人工选择与顺序；不自动补入其他内容。'
              : '从内容库挑选要展示的内容并安排顺序。'
          }}</small></span
        >
      </label>
    </div>
    <label v-if="modelValue === 'automatic'" class="ls-home-mode-count"
      >最多展示
      <input
        type="number"
        min="1"
        max="50"
        :value="count"
        :disabled="busy"
        @change="emit('update:count', Number(($event.target as HTMLInputElement).value))"
      />
      条 <span>可填写 1–50 条，超出数量的内容从“查看更多”进入。</span>
    </label>
    <p class="ls-home-mode-note" role="status">推荐和模式更改仅保存在当前草稿，发布首页后生效。</p>
  </fieldset>
</template>
<style scoped>
.ls-home-mode {
  min-width: 0;
  padding: 18px;
  border: 1px solid #dfe7f1;
  border-radius: 9px;
  background: #fff;
  color: #24334b;
}
.ls-home-mode legend {
  padding: 0 6px;
  font-weight: 700;
}
.ls-home-mode-intro,
.ls-home-mode-card small,
.ls-home-mode-count span {
  color: #596981;
  line-height: 1.55;
}
.ls-home-mode-options {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
  margin: 14px 0;
}
.ls-home-mode-card {
  display: flex;
  gap: 10px;
  min-width: 0;
  padding: 14px;
  border: 1px solid #dfe7f1;
  border-radius: 8px;
  cursor: pointer;
}
.ls-home-mode-card.selected {
  border-color: #2855b7;
  background: #f4f7fd;
  box-shadow: inset 0 0 0 1px #2855b7;
}
.ls-home-mode-card input {
  margin-top: 3px;
  accent-color: #2855b7;
}
.ls-home-mode-card span {
  display: grid;
  gap: 5px;
}
.ls-home-mode-card strong {
  font-size: 14px;
}
.ls-home-mode-card small {
  font-size: 13px;
}
.ls-home-mode-count {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  font-weight: 600;
}
.ls-home-mode-count input {
  width: 76px;
  height: 36px;
  padding: 0 8px;
  border: 1px solid #cbd5e3;
  border-radius: 5px;
  font: inherit;
}
.ls-home-mode-count span {
  font-weight: 400;
  font-size: 13px;
}
.ls-home-mode-note {
  margin: 14px 0 0;
  padding: 10px 12px;
  border-left: 3px solid #2855b7;
  background: #f4f7fd;
  color: #344968;
  font-size: 13px;
}
@media (max-width: 640px) {
  .ls-home-mode-options {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
