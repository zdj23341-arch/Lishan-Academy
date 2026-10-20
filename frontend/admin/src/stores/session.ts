import { defineStore } from 'pinia';
import { shallowRef, onScopeDispose } from 'vue';
import type { LoginView } from '@jxsc/shared';
import { session } from '../api/session';
import { client } from '../api/client';
export const useSessionStore = defineStore('session', () => {
  const current = shallowRef(session.get());
  onScopeDispose(
    session.subscribe((value) => {
      current.value = value;
    }),
  );
  function accept(value: LoginView) {
    session.set(value);
    current.value = session.get();
  }
  function refresh() {
    current.value = session.get();
  }
  async function logout() {
    try {
      await client.request('/admin/auth/logout', { method: 'POST' });
    } finally {
      session.clear();
      current.value = null;
    }
  }
  return { current, accept, refresh, logout };
});
