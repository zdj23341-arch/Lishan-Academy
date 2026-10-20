import { onUnmounted, ref, shallowRef } from 'vue';
import { onHide, onLoad, onShow, onUnload } from '@dcloudio/uni-app';
import {
  isInternalRoute,
  protectedFileError,
  type FileAction,
  type MembershipView,
  type PaperDetail,
} from '@jxsc/shared';
import { useProtectedFiles } from './useProtectedFiles';
import { catalog } from './useCatalog';
import { session } from '../api/session';

export function usePaper(membershipState: () => MembershipView['state'] | undefined) {
  const paper = shallowRef<PaperDetail | null>(null);
  const loading = ref(false);
  const error = ref('');
  const settingsError = ref('');
  const availabilityError = ref('');
  const { platform, controller, files } = useProtectedFiles();
  let id = '';
  let generation = 0;
  let disposed = false;
  let visible = false;
  function clear() {
    generation++;
    controller.hide();
    paper.value = null;
    loading.value = false;
    error.value = '';
    settingsError.value = '';
    availabilityError.value = '';
  }
  const unsubscribe = session.subscribe(clear);
  async function load() {
    if (disposed || !visible) return;
    session.get();
    clear();
    if (!id.trim()) {
      error.value = '缺少试卷编号';
      return;
    }
    const ticket = generation;
    const actor = session.getGeneration();
    loading.value = true;
    const current = () => {
      session.get();
      return !disposed && visible && ticket === generation && actor === session.getGeneration();
    };
    try {
      const result = await catalog.paper(id);
      if (current()) paper.value = result;
    } catch {
      if (current()) error.value = '试卷加载失败，请重试';
    } finally {
      if (current()) loading.value = false;
    }
  }
  function goMembership() {
    uni.navigateTo({ url: '/pages/membership/index' });
  }
  function login() {
    const route = `/pages/paper/detail?id=${encodeURIComponent(id)}`;
    session.rememberReturnTo(isInternalRoute(route) ? route : '/pages/paper/list');
    uni.navigateTo({ url: '/pages/login/index' });
  }
  async function open(file: PaperDetail['attachments'][number], action: FileAction) {
    if (disposed || !visible || files.busy || !paper.value?.attachments.includes(file)) return;
    settingsError.value = '';
    availabilityError.value = '';
    if (!(action === 'PREVIEW' ? file.canPreview : file.canDownload)) {
      controller.clear();
      const state = membershipState();
      if (state === 'NONE' || state === 'EXPIRED') goMembership();
      else availabilityError.value = '当前附件暂不可用或权限待更新，请刷新后重试';
      return;
    }
    await controller.open(id, file, action);
  }
  async function openAlbumSettings() {
    if (files.errorCode !== 'ALBUM_PERMISSION_DENIED' || !platform.openAlbumSettings) return;
    const ticket = generation;
    settingsError.value = '';
    try {
      await platform.openAlbumSettings();
    } catch (cause) {
      if (!disposed && visible && ticket === generation)
        settingsError.value = protectedFileError(cause, 'SETTINGS_UNSUPPORTED').message;
    }
  }
  function dispose() {
    disposed = true;
    visible = false;
    clear();
    controller.dispose();
    unsubscribe();
  }
  onLoad((options) => {
    clear();
    id = options?.id ?? '';
    if (visible) void load();
  });
  onShow(() => {
    visible = true;
    void load();
  });
  onHide(() => {
    visible = false;
    clear();
  });
  onUnload(dispose);
  onUnmounted(dispose);
  return {
    paper,
    loading,
    error,
    load,
    files,
    open,
    goMembership,
    login,
    settingsError,
    availabilityError,
    canOpenAlbumSettings: !!platform.openAlbumSettings,
    openAlbumSettings,
  };
}
