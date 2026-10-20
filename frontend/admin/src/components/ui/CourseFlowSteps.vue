<script setup lang="ts">
withDefaults(
  defineProps<{
    current: 'basics' | 'chapters' | 'preview' | 'publish';
    completed?: string[];
    locked?: string[];
  }>(),
  { completed: () => [], locked: () => [] },
);
const emit = defineEmits<{ select: [step: string] }>();
const steps = [
  { id: 'basics', title: '课程信息', detail: '名称、价格和封面' },
  { id: 'chapters', title: '章节与视频', detail: '安排内容和试看' },
  { id: 'preview', title: '预览课程', detail: '核对学员看到的内容' },
  { id: 'publish', title: '发布上架', detail: '检查后正式发布' },
];
</script>
<template>
  <nav class="ls-course-steps" aria-label="课程创建步骤">
    <ol>
      <li
        v-for="(step, index) in steps"
        :key="step.id"
        :class="{
          complete: completed.includes(step.id),
          current: current === step.id,
          locked: locked.includes(step.id),
        }"
      >
        <button
          type="button"
          :data-step="step.id"
          :disabled="locked.includes(step.id)"
          :aria-current="current === step.id ? 'step' : undefined"
          @click="emit('select', step.id)"
        >
          <span class="ls-course-step-marker" aria-hidden="true">{{
            completed.includes(step.id) ? '✓' : index + 1
          }}</span>
          <span class="ls-course-step-copy"
            ><strong>{{ step.title }}</strong
            ><small>{{ step.detail }}</small></span
          >
        </button>
      </li>
    </ol>
    <p>离开前请保存草稿；已保存的内容可在下次继续编辑。</p>
  </nav>
</template>
<style scoped>
.ls-course-steps {
  min-width: 0;
  padding: 18px;
  border: 1px solid #dfe7f1;
  border-radius: 9px;
  background: #fff;
  color: #24334b;
}
.ls-course-steps ol {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  list-style: none;
  padding: 0;
  margin: 0;
}
.ls-course-steps li {
  min-width: 0;
}
.ls-course-steps button {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  width: 100%;
  min-height: 54px;
  padding: 6px;
  text-align: left;
  color: inherit;
  background: transparent;
  border: 0;
  border-radius: 7px;
  cursor: pointer;
}
.ls-course-steps button:focus-visible {
  outline: 3px solid #86a9ef;
  outline-offset: 2px;
}
.ls-course-step-marker {
  display: grid;
  place-items: center;
  width: 28px;
  height: 28px;
  flex: 0 0 auto;
  border: 1px solid #cbd5e3;
  border-radius: 50%;
  color: #596981;
  font-size: 13px;
}
.current .ls-course-step-marker,
.complete .ls-course-step-marker {
  border-color: #2855b7;
  color: #fff;
  background: #2855b7;
}
.ls-course-step-copy {
  display: grid;
  min-width: 0;
  gap: 4px;
}
.ls-course-step-copy strong {
  font-size: 14px;
}
.ls-course-step-copy small,
.ls-course-steps > p {
  color: #596981;
  font-size: 12px;
  line-height: 1.5;
}
.current .ls-course-step-copy strong {
  color: #2855b7;
}
.locked {
  opacity: 0.58;
}
.ls-course-steps > p {
  margin: 12px 0 0;
}
@media (max-width: 720px) {
  .ls-course-steps ol {
    grid-template-columns: minmax(0, 1fr);
  }
  .ls-course-steps button {
    min-height: 44px;
  }
}
</style>
