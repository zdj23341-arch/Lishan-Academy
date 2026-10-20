<script setup lang="ts">
const props = withDefaults(
  defineProps<{
    kind?: 'primary' | 'secondary' | 'soft' | 'link' | 'danger';
    disabled?: boolean;
    loading?: boolean;
    type?: 'button' | 'submit' | 'reset';
  }>(),
  { kind: 'primary', disabled: false, loading: false, type: 'button' },
);
const emit = defineEmits<{ click: [event: Event] }>();
function click(event: Event) {
  if (!props.disabled && !props.loading) emit('click', event);
}
</script>
<template>
  <button
    class="ls-button"
    :class="'ls-button--' + kind"
    :type="type"
    :disabled="disabled || loading"
    :aria-busy="loading"
    @click="click"
  >
    <slot>{{ loading ? '处理中…' : '' }}</slot>
  </button>
</template>
