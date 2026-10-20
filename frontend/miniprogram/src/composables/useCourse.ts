import { computed, getCurrentInstance, onUnmounted, reactive, ref, shallowRef } from 'vue';
import { onHide, onLoad, onShow, onUnload } from '@dcloudio/uni-app';
import { createCourseController, type CourseController, type CourseState } from '@jxsc/shared';
import { learningApi } from '../api/learning';
import { session } from '../api/session';
import { recentLearning } from '../api/recent-learning';

export function useCourse() {
  const state = shallowRef<CourseState | null>(null);
  const routeError = ref('');
  const mediaError = ref('');
  const fullscreen = ref(false);
  const playbackRates = [1, 1.25, 1.5, 2];
  const playbackRate = ref(1);
  const playbackRateError = ref('');
  const playbackRateMenuOpen = ref(false);
  const componentInstance = getCurrentInstance()?.proxy;
  let controller: CourseController | null = null;
  let pendingHide: Promise<void> = Promise.resolve();
  let lifecycle = 0;
  let requestedChapter: string | undefined;
  let lastRememberedAt = 0;

  function remember(force = false) {
    const current = controller?.state;
    if (!current?.authorization || !current.course || !current.selectedChapterId) return;
    if (!force && Date.now() - lastRememberedAt < 5000) return;
    recentLearning.record({
      course: current.course,
      chapterId: current.selectedChapterId,
      positionSeconds: current.positionSeconds,
      authorization: current.authorization,
      generation: session.getGeneration(),
    });
    lastRememberedAt = Date.now();
  }

  function hide() {
    playbackRateMenuOpen.value = false;
    if (lastRememberedAt) remember(true);
    lifecycle++;
    fullscreen.value = false;
    const pending = controller?.hide() ?? Promise.resolve();
    // Retain earlier hide work: a second hide after started=false returns immediately.
    pendingHide = Promise.all([pendingHide, pending]).then(() => undefined);
    return pendingHide;
  }
  function close() {
    const retiring = controller;
    const pending = hide();
    controller = null;
    state.value = null;
    playbackRate.value = 1;
    playbackRateError.value = '';
    // dispose cancels queued progress; final hide must drain before disposal.
    void pending.finally(() => retiring?.dispose());
  }
  function setup(id: string, productId?: string, chapterId?: string) {
    close();
    pendingHide = Promise.resolve();
    requestedChapter = chapterId;
    lastRememberedAt = 0;
    routeError.value = id.trim() ? '' : '缺少课程编号';
    mediaError.value = '';
    if (routeError.value) return;
    controller = createCourseController({ api: learningApi, session, courseId: id, productId });
    const observable = reactive<CourseState>({ ...controller.state });
    // The core writes its plain object directly, including timer/session callbacks.
    // Forward every field through Vue; course reads also return nested reactive proxies.
    for (const key of Object.keys(controller.state) as Array<keyof CourseState>) {
      Object.defineProperty(controller.state, key, {
        configurable: true,
        enumerable: true,
        get: () => observable[key],
        set: (value: CourseState[typeof key]) => {
          Reflect.set(observable, key, value);
        },
      });
    }
    state.value = observable;
  }
  async function load() {
    const current = controller;
    const ticket = ++lifecycle;
    mediaError.value = '';
    await pendingHide;
    if (current && current === controller && ticket === lifecycle) {
      await current.load();
      if (current === controller && ticket === lifecycle && requestedChapter) {
        const id = requestedChapter;
        requestedChapter = undefined;
        if (current.state.course?.chapters.some((chapter) => chapter.id === id))
          await current.select(id);
      }
    }
  }
  function select(id: string) {
    playbackRateMenuOpen.value = false;
    if (lastRememberedAt) remember(true);
    lastRememberedAt = 0;
    mediaError.value = '';
    fullscreen.value = false;
    return controller?.select(id);
  }
  async function play() {
    playbackRateMenuOpen.value = false;
    if (lastRememberedAt) remember(true);
    lastRememberedAt = 0;
    mediaError.value = '';
    await controller?.play();
  }
  function videoError() {
    void hide();
    mediaError.value = '视频播放失败，请重新播放以获取授权';
  }
  let previousAuthorization: unknown;
  let mediaKey = 0;
  function applyPlaybackRate(videoId: string, rate: number) {
    try {
      uni.createVideoContext(videoId, componentInstance).playbackRate(rate);
      playbackRateError.value = '';
      return true;
    } catch {
      playbackRateError.value = '倍速设置失败，请重新选择或重新播放后重试';
      return false;
    }
  }
  function setPlaybackRate(rate: number) {
    const binding = media.value;
    if (!binding || state.value?.authorizing || !playbackRates.includes(rate)) return;
    binding.setRate(rate);
    playbackRateMenuOpen.value = false;
  }
  function togglePlaybackRateMenu() {
    if (!media.value || state.value?.authorizing) return;
    playbackRateMenuOpen.value = !playbackRateMenuOpen.value;
  }
  const media = computed(() => {
    const current = controller;
    const authorization = state.value?.authorization;
    if (!authorization || !current) return null;
    if (authorization !== previousAuthorization) {
      previousAuthorization = authorization;
      mediaKey++;
    }
    const isCurrent = () => current === controller && current.state.authorization === authorization;
    const videoId = `course-video-${mediaKey}`;
    let appliedRate = 1;
    const setRate = (rate: number) => {
      if (!isCurrent()) return;
      if (applyPlaybackRate(videoId, rate)) appliedRate = rate;
      playbackRate.value = appliedRate;
    };
    return {
      key: mediaKey,
      videoId,
      authorization,
      // New native players start at 1x; retain the choice when chapters/grants change.
      ready: () => {
        if (isCurrent()) current.resume();
        setRate(playbackRate.value);
      },
      setRate,
      timeUpdate: (event: Event | { detail: { currentTime: number } }) => {
        const detail = (event as { detail?: { currentTime?: unknown } }).detail;
        if (
          isCurrent() &&
          typeof detail?.currentTime === 'number' &&
          Number.isFinite(detail.currentTime)
        ) {
          current.timeUpdate(detail.currentTime);
          if (isCurrent()) remember();
        }
      },
      pause: () => {
        if (isCurrent()) {
          if (lastRememberedAt) remember(true);
          void current.pause();
        }
      },
      ended: () => {
        if (isCurrent()) {
          fullscreen.value = false;
          if (lastRememberedAt) remember(true);
          void current.ended();
        }
      },
      error: () => {
        if (isCurrent()) videoError();
      },
      fullscreenChange: (event: Event | { detail: { fullScreen: boolean } }) => {
        const detail = (event as { detail?: { fullScreen?: unknown } }).detail;
        if (isCurrent()) {
          fullscreen.value = detail?.fullScreen === true;
          playbackRateMenuOpen.value = false;
        }
      },
    };
  });
  onLoad((options) => setup(options?.id ?? '', options?.productId, options?.chapterId));
  onShow(() => {
    void load();
  });
  onHide(() => {
    void hide();
  });
  onUnload(close);
  onUnmounted(close);
  return {
    state,
    routeError,
    mediaError,
    fullscreen,
    playbackRates,
    playbackRate,
    playbackRateError,
    playbackRateMenuOpen,
    togglePlaybackRateMenu,
    closePlaybackRateMenu: () => {
      playbackRateMenuOpen.value = false;
    },
    setPlaybackRate,
    media,
    load,
    select,
    play,
    retryProgress: () => controller?.retryProgress(),
  };
}
