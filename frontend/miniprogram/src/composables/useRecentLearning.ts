import { onUnmounted, ref, shallowRef } from 'vue';
import type { RecentLearningView } from '../../../shared/src/learning/recent';
import { session } from '../api/session';
import { recentLearning } from '../api/recent-learning';

export function useRecentLearning() {
  const recent = shallowRef<RecentLearningView | null>(null);
  const loading = ref(false);
  const error = ref('');
  let sequence = 0;
  let disposed = false;
  const unsubscribe = session.subscribe(() => {
    sequence++;
    recentLearning.invalidate();
    recent.value = null;
    error.value = '';
    loading.value = false;
  });
  async function loadRecent() {
    session.get();
    const ticket = ++sequence;
    const actor = session.getGeneration();
    const active = () => {
      session.get();
      return !disposed && ticket === sequence && actor === session.getGeneration();
    };
    loading.value = true;
    error.value = '';
    recent.value = null;
    try {
      const value = await recentLearning.load();
      if (active()) recent.value = value;
    } catch {
      if (active()) error.value = '最近学习加载失败，请重试';
    } finally {
      if (active()) loading.value = false;
    }
  }
  function continueLearning() {
    const value = recent.value;
    if (!value || !session.get() || loading.value) return;
    uni.navigateTo({
      url: `/pages/course/learn?id=${encodeURIComponent(value.course.id)}&productId=${encodeURIComponent(value.course.productId)}&chapterId=${encodeURIComponent(value.chapter.id)}`,
    });
  }
  onUnmounted(() => {
    disposed = true;
    sequence++;
    recentLearning.invalidate();
    unsubscribe();
  });
  return { recent, loading, error, loadRecent, continueLearning };
}
