import type { Session, SessionStorage } from '../session';
import type { ClaimReferralResult } from './types';
declare const uni: {
  setStorageSync?(key: string, value: unknown): void;
  getStorageSync?(key: string): unknown;
  removeStorageSync?(key: string): void;
};

export interface CachedAttribution {
  productId: string;
  attributionToken: string;
  expiresAt: string;
  savedAt: number;
  claimed?: boolean;
}
interface Binding {
  session: Session;
  storage: SessionStorage;
  namespace: string;
}
export class ReferralManager {
  private memory = new Map<string, CachedAttribution>();
  private binding?: Binding;
  private actor: string | null = null;
  private epoch = 0;
  private unsubscribe?: () => void;
  context() {
    return JSON.stringify([this.epoch, this.binding?.session.getGeneration()]);
  }
  namespace() {
    return this.binding?.namespace ?? 'unbound';
  }
  authenticated() {
    return !!this.binding?.session.get()?.user;
  }
  bind(binding: Binding) {
    this.unsubscribe?.();
    this.binding = binding;
    this.actor = binding.session.get()?.user?.id ?? null;
    this.epoch++;
    this.unsubscribe = binding.session.subscribe((login) => {
      const next = login?.user?.id ?? null;
      if (next === this.actor) return;
      const guest = !this.actor ? this.read() : null;
      this.clear();
      this.actor = next;
      this.epoch++;
      // Only an anonymous touchpoint may cross the first login boundary.
      if (next && guest) this.write({ ...guest, claimed: false });
    });
  }
  private key() {
    return this.binding
      ? `jxsc.attribution.v2:${JSON.stringify([this.binding.namespace, this.actor ?? 'guest'])}`
      : 'jxsc.attribution.unbound.v2';
  }
  private write(data: CachedAttribution) {
    const key = this.key();
    if (this.binding) this.binding.storage.set(key, JSON.stringify(data));
    else if (typeof uni !== 'undefined' && uni.setStorageSync) uni.setStorageSync(key, data);
    else if (typeof localStorage !== 'undefined') localStorage.setItem(key, JSON.stringify(data));
    this.memory.set(key, data);
  }
  private read(): CachedAttribution | null {
    const key = this.key();
    let data = this.memory.get(key) ?? null;
    try {
      if (this.binding) {
        const raw = this.binding.storage.get(key);
        data = raw ? JSON.parse(raw) : null;
      } else if (typeof uni !== 'undefined' && uni.getStorageSync)
        data = (uni.getStorageSync(key) as CachedAttribution | undefined) || data;
      else if (typeof localStorage !== 'undefined') {
        const raw = localStorage.getItem(key);
        if (raw) data = JSON.parse(raw);
      }
    } catch {
      data = null;
    }
    if (!data || typeof data.attributionToken !== 'string' || typeof data.productId !== 'string')
      return null;
    if (!Number.isFinite(Date.parse(data.expiresAt)) || Date.parse(data.expiresAt) <= Date.now()) {
      this.clear();
      return null;
    }
    return data;
  }
  saveAttribution(productId: string, token: string, expiresAt: string) {
    this.write({
      productId: String(productId),
      attributionToken: token,
      expiresAt,
      savedAt: Date.now(),
      claimed: !this.binding,
    });
  }
  getValidTokenForProduct(productId: string): string | null {
    const data = this.read();
    return data &&
      data.productId === String(productId) &&
      (!this.binding || (!!this.actor && data.claimed))
      ? data.attributionToken
      : null;
  }
  getPendingAttribution() {
    return this.read();
  }
  async claimPending(claim: (token: string) => Promise<ClaimReferralResult>) {
    const data = this.read();
    if (!data) return;
    if (this.binding && !this.actor) throw new Error('请先登录后认领推荐信息');
    if (data.claimed) return;
    const epoch = this.epoch,
      generation = this.binding?.session.getGeneration();
    const result = await claim(data.attributionToken);
    if (epoch !== this.epoch || generation !== this.binding?.session.getGeneration())
      throw new Error('账户已变化，请重新认领推荐信息');
    if (!result.claimed || String(result.productId) !== data.productId)
      throw new Error('推荐信息未成功认领，可重试或不使用推荐继续');
    if (this.read()?.attributionToken !== data.attributionToken) return;
    this.write({ ...data, claimed: true });
  }
  clear() {
    const key = this.key();
    this.memory.delete(key);
    try {
      if (this.binding) this.binding.storage.remove(key);
      else if (typeof uni !== 'undefined' && uni.removeStorageSync) uni.removeStorageSync(key);
      else if (typeof localStorage !== 'undefined') localStorage.removeItem(key);
    } catch {
      /* A failed read never falls back to an old actor. */
    }
  }
  dispose() {
    this.unsubscribe?.();
    this.epoch++;
  }
}
export const referralManager = new ReferralManager();
