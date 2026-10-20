<script setup lang="ts">
import BrandMark from '../components/ui/BrandMark.vue';
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import type { AdminLoginResult, AdminUserSummary } from '@jxsc/shared';
import { client, environment } from '../api/client';
import { session } from '../api/session';
import { useSessionStore } from '../stores/session';
const router = useRouter();
const store = useSessionStore();
const loginName = ref('');
const password = ref('');
const busy = ref(false);
const error = ref('');
const message = ref('');
const passwordChangeRequired = ref(
  session.get()?.passwordChangeRequired || session.get()?.admin?.passwordChangeRequired || false,
);
const newPassword = ref('');
const confirmPassword = ref('');
async function changePassword() {
  error.value = '';
  if (
    !password.value ||
    !newPassword.value.trim() ||
    newPassword.value.length < 12 ||
    new TextEncoder().encode(newPassword.value).length > 72
  ) {
    error.value = '请输入当前密码，新密码至少12个字符且不超过72个UTF-8字节';
    return;
  }
  if (newPassword.value !== confirmPassword.value) {
    error.value = '两次新密码不一致';
    return;
  }
  if (newPassword.value === password.value) {
    error.value = '新密码必须与当前密码不同';
    return;
  }
  busy.value = true;
  try {
    await client.request('/admin/auth/change-password', {
      method: 'POST',
      body: { currentPassword: password.value, newPassword: newPassword.value },
    });
    session.clear();
    passwordChangeRequired.value = false;
    password.value = '';
    newPassword.value = '';
    confirmPassword.value = '';
    message.value = '密码已修改，请使用新密码登录';
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '修改密码失败';
    if (!session.get()) passwordChangeRequired.value = false;
  } finally {
    busy.value = false;
  }
}
async function login() {
  if (busy.value) return;
  if (passwordChangeRequired.value) return changePassword();
  if (environment.provider !== 'mock' && (!loginName.value.trim() || !password.value)) {
    error.value = '请输入登录名和密码';
    return;
  }
  busy.value = true;
  error.value = '';
  message.value = '';
  let provisionalGeneration: number | null = null;
  try {
    const result = await client.request<AdminLoginResult>('/admin/auth/login', {
      method: 'POST',
      body: { loginName: loginName.value.trim(), password: password.value },
    });
    // The shared client takes its Bearer token from session, not ad-hoc headers.
    store.accept(result);
    provisionalGeneration = session.getGeneration();
    const admin = await client.request<AdminUserSummary>('/admin/me');
    if (session.getGeneration() !== provisionalGeneration) return;
    if (!admin?.id || !admin.loginName || !Array.isArray(admin.permissions))
      throw new Error('管理员信息响应无效，请重新登录');
    store.accept({ ...result, admin, permissions: admin.permissions });
    if (result.passwordChangeRequired || admin.passwordChangeRequired) {
      passwordChangeRequired.value = true;
      return;
    }
    password.value = '';
    await router.replace(session.takeReturnTo() ?? '/');
  } catch (cause) {
    if (provisionalGeneration === session.getGeneration()) session.clear();
    error.value = cause instanceof Error ? cause.message : '登录失败';
  } finally {
    busy.value = false;
  }
}
</script>
<template>
  <main class="ls-login-page">
    <section class="ls-login-panel">
      <div class="ls-login-brand">
        <BrandMark compact />
        <strong>力山学堂运营后台</strong>
      </div>
      <h1 class="ls-login-subtitle">
        {{ passwordChangeRequired ? '首次登录：修改密码' : '使用管理员账号登录' }}
      </h1>
      <form @submit.prevent="login">
        <template v-if="passwordChangeRequired">
          <label
            >当前密码<input
              v-model="password"
              type="password"
              autocomplete="current-password"
              placeholder="输入当前密码"
          /></label>
          <label
            >新密码<input
              v-model="newPassword"
              name="newPassword"
              type="password"
              autocomplete="new-password"
              placeholder="输入新密码"
          /></label>
          <label
            >确认新密码<input
              v-model="confirmPassword"
              name="confirmPassword"
              type="password"
              autocomplete="new-password"
              placeholder="再次输入新密码"
          /></label>
        </template>
        <template v-else-if="environment.provider !== 'mock'"
          ><label
            >账号<input
              v-model="loginName"
              autocomplete="username"
              placeholder="输入管理员账号" /></label
          ><label
            >密码<input
              v-model="password"
              type="password"
              autocomplete="current-password"
              placeholder="输入密码" /></label
        ></template>
        <p v-if="error" role="alert">{{ error }}</p>
        <p v-if="message" role="status">{{ message }}</p>
        <el-button type="primary" native-type="submit" :loading="busy">{{
          passwordChangeRequired
            ? '修改密码'
            : environment.provider === 'mock'
              ? '使用开发管理员'
              : '登录'
        }}</el-button>
      </form>
      <p v-if="!passwordChangeRequired" class="ls-login-note">
        首次登录或密码过期时，引导完成密码修改。
      </p>
      <p v-else class="ls-login-note">新密码至少 12 个字符，请使用与当前密码不同的密码。</p>
    </section>
  </main>
</template>
