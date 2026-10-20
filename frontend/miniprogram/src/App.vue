<script setup lang="ts">
import { onLaunch } from '@dcloudio/uni-app';
import { session } from './api/session';
onLaunch(() => {
  session.get();
});
</script>

<style lang="scss">
@import './styles/common.scss';
</style>
