<script setup lang="ts">
withDefaults(defineProps<{ compact?: boolean; subtitle?: string }>(), {
  compact: false,
  subtitle: '阅读与成长',
});
</script>
<template>
  <view class="ls-brand"
    ><text class="ls-brandmark">山</text
    ><view v-if="!compact" class="ls-brandcopy"
      ><text class="ls-brandtitle">力山学堂</text
      ><text v-if="subtitle" class="ls-brandcaption">{{ subtitle }}</text></view
    ></view
  >
</template>
<style scoped>
.ls-brand {
  display: flex;
  align-items: center;
  gap: 24rpx;
}
.ls-brandmark {
  width: 68rpx;
  height: 68rpx;
  border-radius: 18rpx;
  background: #2855b7;
  color: white;
  font-family: STSong, SimSun, serif;
  font-size: 44rpx;
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
  font-size: 34rpx;
  font-weight: 700;
  letter-spacing: 2rpx;
  color: #18283e;
}
.ls-brandcaption {
  font-size: 22rpx;
  letter-spacing: 2rpx;
  color: #617088;
}
</style>
