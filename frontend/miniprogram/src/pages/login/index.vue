<script setup lang="ts">
import { customerMessage } from '../../utils/customer-text';
import BrandMark from '../../components/ui/BrandMark.vue';
import { computed, ref, shallowRef } from 'vue';
import { onShow } from '@dcloudio/uni-app';
import {
  createPublicConfigurationApi,
  referralManager,
  usesDevelopmentLogin,
  type AgreementKind,
  type PublicAgreementLink,
  type LoginView,
} from '@jxsc/shared';
import { client, environment } from '../../api/client';
import { session } from '../../api/session';
import { distributionApi } from '../../api/distribution';
import { useSessionStore } from '../../stores/session';
const store = useSessionStore();
const busy = ref(false);
const error = ref('');
const referralError = ref('');
const navigationError = ref('');
const navigating = ref(false);
const authenticated = ref(false);
const configurationApi = createPublicConfigurationApi(client);
const agreementLinks = shallowRef<Partial<Record<AgreementKind, PublicAgreementLink>>>({});
const agreementsLoading = ref(false);
const agreementError = ref('');
const consented = ref(false);
const agreementOpening = ref(false);
let agreementFingerprint = '';
const agreementsReady = computed(
  () => !!agreementLinks.value.terms && !!agreementLinks.value.privacy,
);
function validAgreement(value: PublicAgreementLink | undefined): value is PublicAgreementLink {
  return (
    !!value &&
    typeof value.title === 'string' &&
    !!value.title.trim() &&
    typeof value.version === 'string' &&
    !!value.version.trim() &&
    typeof value.articleId === 'string' &&
    /^[1-9][0-9]{0,18}$/.test(value.articleId)
  );
}
async function loadAgreements() {
  if (agreementsLoading.value || busy.value || authenticated.value) return;
  agreementsLoading.value = true;
  agreementError.value = '';
  try {
    const configuration = await configurationApi.get();
    const links: Partial<Record<AgreementKind, PublicAgreementLink>> = {};
    for (const kind of ['terms', 'privacy'] as const) {
      const link = configuration?.agreementLinks?.[kind];
      if (validAgreement(link)) links[kind] = link;
    }
    const fingerprint = JSON.stringify(
      ['terms', 'privacy'].map((kind) => {
        const link = links[kind as AgreementKind];
        return link ? [link.articleId, link.version] : null;
      }),
    );
    if (fingerprint !== agreementFingerprint) consented.value = false;
    agreementFingerprint = fingerprint;
    agreementLinks.value = links;
    if (!agreementsReady.value) {
      consented.value = false;
      agreementError.value = '协议暂未配置完整，暂时无法登录；你可以先逛逛或稍后重试。';
    }
  } catch {
    agreementLinks.value = {};
    consented.value = false;
    agreementFingerprint = '';
    agreementError.value = '协议加载失败，请检查网络后重试。';
  } finally {
    agreementsLoading.value = false;
  }
}
onShow(() => {
  void loadAgreements();
});
function toggleConsent() {
  if (!agreementsReady.value || agreementsLoading.value || busy.value || authenticated.value)
    return;
  consented.value = !consented.value;
  error.value = '';
}
async function openAgreement(kind: AgreementKind) {
  const link = agreementLinks.value[kind];
  if (!link || agreementOpening.value || agreementsLoading.value || busy.value) return;
  agreementOpening.value = true;
  error.value = '';
  try {
    await uni.navigateTo({
      url: `/pages/article/detail?id=${encodeURIComponent(link.articleId)}&agreement=${kind}`,
      events: {
        agreementUnavailable: () => {
          consented.value = false;
          error.value = '协议正文暂不可用，请重试阅读后重新同意。';
        },
      },
    });
  } catch {
    // A failed read/navigation must not authorize login against unread content.
    consented.value = false;
    error.value = '协议页面打开失败，请重新打开后阅读。';
  } finally {
    agreementOpening.value = false;
  }
}
const developmentLogin = usesDevelopmentLogin(environment, import.meta.env.VITE_DEV_LOGIN);
async function login() {
  if (
    busy.value ||
    navigating.value ||
    authenticated.value ||
    agreementsLoading.value ||
    agreementOpening.value
  )
    return;
  if (!agreementsReady.value) {
    error.value = '协议暂不可用，请重试加载协议。';
    return;
  }
  if (!consented.value) {
    error.value = '请先阅读并同意《用户协议》和《隐私政策》。';
    return;
  }
  busy.value = true;
  error.value = '';
  try {
    let body: { fixture: string } | { code: string };
    let path: string;
    if (developmentLogin) {
      path = '/auth/dev-login';
      body = { fixture: import.meta.env.VITE_DEV_LOGIN_FIXTURE || 'buyer-active-member' };
    } else {
      const result = await uni.login({ provider: 'weixin' });
      if (!result.code) throw new Error('未获得微信登录凭证，请在微信小程序内登录');
      path = '/auth/wechat-login';
      body = { code: result.code };
    }
    const loginData = await client.request<LoginView>(path, {
      method: 'POST',
      authentication: 'none',
      body,
    });
    store.accept(loginData);
    authenticated.value = true;

    await continueAfterClaim();
  } catch (cause) {
    error.value = customerMessage(cause, '登录失败');
  } finally {
    busy.value = false;
  }
}
async function continueAfterClaim() {
  referralError.value = '';
  try {
    await referralManager.claimPending((token) => distributionApi.claimReferral(token));
  } catch (cause) {
    referralError.value = customerMessage(cause, '推荐信息认领失败，可重试或不使用推荐继续');
    return;
  }
  await continueToTarget();
}
async function continueToTarget() {
  if (navigating.value) return;
  navigating.value = true;
  navigationError.value = '';
  const target = session.takeReturnTo() ?? '/pages/home/index';
  try {
    await uni.redirectTo({ url: target });
  } catch {
    session.rememberReturnTo(target);
    navigationError.value = '登录已成功，页面跳转失败，请重试';
  } finally {
    navigating.value = false;
  }
}
async function discardReferral() {
  referralManager.clear();
  referralError.value = '';
  await continueToTarget();
}
function cancel() {
  session.takeReturnTo();
  uni.navigateBack({ fail: () => uni.reLaunch({ url: '/pages/home/index' }) });
}
</script>
<template>
  <view class="ls-login-page"
    ><BrandMark compact /><view class="ls-login-story"
      ><text class="ls-login-story-title">欢迎来到力山学堂</text
      ><text class="ls-mini-description">登录后查看已购课程、会员权益和订单。</text></view
    ><view class="ls-login-card"
      ><view v-if="referralError" role="alert" class="ls-login-error ls-alert"
        >{{ referralError }}<button @click="continueAfterClaim">重试认领推荐信息</button
        ><button @click="discardReferral">不使用推荐继续</button></view
      ><text v-if="error" role="alert" class="ls-login-error ls-alert">{{ error }}</text
      ><view v-if="navigationError" role="alert" class="ls-login-error ls-alert"
        >{{ navigationError
        }}<button :disabled="navigating" @click="continueToTarget">重试跳转</button></view
      ><view class="ls-login-agreements">
        <text v-if="agreementsLoading" class="ls-agreement-status" role="status"
          >正在加载协议…</text
        >
        <view v-if="agreementError" role="alert" class="ls-agreement-status">
          <text>{{ agreementError }}</text>
          <button
            class="ls-agreement-retry"
            :disabled="agreementsLoading || busy"
            @click="loadAgreements"
          >
            重试加载协议
          </button>
        </view>
        <button
          v-if="agreementsReady"
          class="ls-agreement-consent"
          role="checkbox"
          :aria-checked="consented"
          :disabled="agreementsLoading || busy || authenticated"
          hover-class="ls-agreement-pressed"
          @click="toggleConsent"
        >
          <text
            class="ls-agreement-check"
            :class="{ 'ls-agreement-check--selected': consented }"
            aria-hidden="true"
            >{{ consented ? '✓' : '' }}</text
          >
          <text>我已阅读并同意以下协议</text>
        </button>
        <view class="ls-agreement-links">
          <button
            v-for="kind in ['terms', 'privacy'] as const"
            v-show="agreementLinks[kind]"
            :key="kind"
            class="ls-agreement-link"
            :disabled="agreementsLoading || busy || agreementOpening"
            hover-class="ls-agreement-pressed"
            @click="openAgreement(kind)"
          >
            《{{ agreementLinks[kind]?.title }}》
          </button>
        </view> </view
      ><button
        class="ls-button ls-button--primary"
        :loading="busy"
        :disabled="
          busy ||
          navigating ||
          authenticated ||
          agreementsLoading ||
          !agreementsReady ||
          agreementOpening
        "
        @click="login"
      >
        {{ developmentLogin ? '使用开发用户登录' : '微信登录' }}</button
      ><button class="ls-button ls-login-secondary" :disabled="busy || navigating" @click="cancel">
        先逛逛
      </button></view
    ></view
  >
</template>
<style scoped>
.ls-login-page {
  padding: 81px 26px 30px;
  min-height: calc(100vh - 100px);
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  gap: 20px;
}
.ls-login-page :deep(.ls-brand) {
  justify-content: center;
}
.ls-login-page :deep(.ls-brandmark) {
  width: 72px;
  height: 72px;
  font-size: 39px;
  border-radius: 20px;
}
.ls-login-story {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  gap: 20px;
}
.ls-login-story-title {
  font-family: STSong, SimSun, serif;
  font-size: 27px;
  font-weight: 650;
  line-height: 1.4;
}
.ls-login-story .ls-mini-description {
  font-size: 12px;
  line-height: 1.55;
  color: #617088;
}
.ls-login-card {
  padding: 0;
  border: 0;
  background: transparent;
  box-shadow: none;
  display: flex;
  flex-direction: column;
  gap: 20px;
}
.ls-login-card .ls-button {
  width: 100%;
  min-height: 45px;
  padding: 8px 14px;
  margin: 0;
  font-size: 14px;
  line-height: 1.55;
  border-radius: 8px;
}
.ls-login-card .ls-login-secondary {
  background: transparent;
  color: #2855b7;
  border: 0;
}
.ls-login-secondary::after {
  border: 0;
}
.ls-login-agreements {
  display: flex;
  flex-direction: column;
  gap: 8px;
  color: #617088;
  font-size: 12px;
  line-height: 1.6;
}
.ls-agreement-status {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.ls-agreement-consent,
.ls-agreement-link,
.ls-agreement-retry {
  min-height: 44px;
  margin: 0;
  padding: 8px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  font-size: 12px;
  line-height: 1.6;
  color: #2855b7;
}
.ls-agreement-consent::after,
.ls-agreement-link::after,
.ls-agreement-retry::after {
  border: 0;
}
.ls-agreement-consent {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 8px;
  text-align: left;
  color: #617088;
}
.ls-agreement-check {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  flex: 0 0 18px;
  border: 1px solid #617088;
  border-radius: 4px;
  box-sizing: border-box;
}
.ls-agreement-check--selected {
  color: #fff;
  background: #2855b7;
  border-color: #2855b7;
}
.ls-agreement-links {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
}
.ls-agreement-link {
  width: auto;
  flex: 0 1 auto;
  max-width: 100%;
  overflow-wrap: anywhere;
}
.ls-agreement-pressed {
  background: #edf3ff;
}
</style>
