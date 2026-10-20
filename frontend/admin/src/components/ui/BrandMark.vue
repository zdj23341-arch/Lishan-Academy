<script setup lang="ts">
withDefaults(defineProps<{ compact?: boolean; subtitle?: string }>(), {
  compact: false,
  subtitle: 'LISHAN LEARNING',
});
</script>
<template>
  <div class="ls-brand">
    <span class="ls-brandmark">山</span>
    <div v-if="!compact" class="ls-brandcopy">
      <span class="ls-brandtitle">力山学堂</span><span class="ls-brandcaption">{{ subtitle }}</span>
    </div>
  </div>
</template>
<style scoped>
.ls-brand {
  display: flex;
  align-items: center;
  gap: 12px;
}
.ls-brandmark {
  width: 34px;
  height: 34px;
  border-radius: 9px;
  background: #2855b7;
  color: white;
  font-family: STSong, SimSun, serif;
  font-size: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-weight: 700;
  flex-shrink: 0;
}
.ls-brandcopy {
  display: flex;
  flex-direction: column;
}
.ls-brandtitle {
  font-size: 17px;
  font-weight: 700;
  letter-spacing: 1px;
  color: #18283e;
}
.ls-brandcaption {
  font-size: 11px;
  letter-spacing: 1px;
  color: #617088;
}
</style>
