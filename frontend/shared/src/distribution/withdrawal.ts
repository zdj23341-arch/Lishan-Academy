import type { DistributionApi } from './api';
import type { WithdrawalRecord, ConfirmationPayload } from './types';
import type { Session, SessionStorage } from '../session';
import { ApiError } from '../http';

export interface ReceiptConfirmationAdapter {
  confirmReceipt(payload: ConfirmationPayload): Promise<boolean>;
}
interface WithdrawalPersistence {
  session: Session;
  storage: SessionStorage;
  namespace: string;
}
interface Intent {
  key: string;
  amountFen: number;
  resourceId: string | null;
}
const terminal = (record: WithdrawalRecord) =>
  ['SUCCEEDED', 'FAILED', 'CLOSED'].includes(record.status);

export class WithdrawalController {
  public currentRecord: WithdrawalRecord | null = null;
  public loading = false;
  public polling = false;
  public error: string | null = null;
  public pendingKey: string | null = null;
  public pendingAmountFen: number | null = null;
  private intent: Readonly<Intent> | null = null;
  private pollTimer: ReturnType<typeof setTimeout> | null = null;
  private pollCount = 0;
  private pollEpoch = 0;
  private generation = 0;
  private recordEpoch = 0;
  private disposed = false;
  private unsubscribe?: () => void;
  private listeners = new Set<() => void>();

  constructor(
    private api: DistributionApi,
    private confirmationAdapter?: ReceiptConfirmationAdapter,
    private persistence?: WithdrawalPersistence,
  ) {
    this.restore();
    this.unsubscribe = persistence?.session.subscribe(() => {
      this.generation++;
      this.recordEpoch++;
      this.stopPolling();
      this.currentRecord = null;
      this.loading = false;
      this.error = null;
      this.restore();
      this.notify();
    });
  }
  subscribe(listener: () => void) {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  }
  private notify() {
    for (const listener of this.listeners) listener();
  }
  private storageKey() {
    const actor = this.persistence?.session.get()?.user?.id;
    return actor
      ? `jxsc.withdrawal.v2:${JSON.stringify([this.persistence!.namespace, actor])}`
      : null;
  }
  private restore() {
    this.intent = null;
    this.pendingKey = null;
    this.pendingAmountFen = null;
    const key = this.storageKey();
    if (!key) return;
    try {
      const data = JSON.parse(this.persistence!.storage.get(key) ?? 'null');
      if (
        data &&
        typeof data.key === 'string' &&
        data.key &&
        Number.isSafeInteger(data.amountFen) &&
        data.amountFen > 0 &&
        (data.resourceId === null || typeof data.resourceId === 'string')
      ) {
        this.intent = Object.freeze(data);
        this.pendingKey = data.key;
        this.pendingAmountFen = data.amountFen;
      }
    } catch {
      /* Malformed intent cannot authorize a withdrawal. */
    }
  }
  private persist(intent: Intent | null) {
    const key = this.storageKey();
    // A failed durable write prevents the initial money request.
    if (key) {
      if (intent) this.persistence!.storage.set(key, JSON.stringify(intent));
      else this.persistence!.storage.remove(key);
    }
    this.intent = intent ? Object.freeze({ ...intent }) : null;
    this.pendingKey = intent?.key ?? null;
    this.pendingAmountFen = intent?.amountFen ?? null;
    this.notify();
  }
  private ticket() {
    return [this.generation, this.persistence?.session.getGeneration()] as const;
  }
  private assertCurrent(ticket: readonly [number, number | undefined]) {
    if (
      this.disposed ||
      ticket[0] !== this.generation ||
      ticket[1] !== this.persistence?.session.getGeneration()
    )
      throw new Error('账户已变化，请重新查询本人提现');
  }
  private assertRecordCurrent(ticket: readonly [number, number | undefined], epoch: number) {
    this.assertCurrent(ticket);
    if (epoch !== this.recordEpoch) throw new Error('提现选择或查询已更新，请查看当前单据');
  }
  private releaseRejectedIntent(error: unknown) {
    // These explicit create errors occur before any withdrawal insert, inside the
    // transaction that also reserves the idempotency key. Unknown outcomes stay frozen.
    const rejected =
      error instanceof ApiError &&
      ((error.status === 400 && error.code === 'VALIDATION_ERROR') ||
        (error.status === 409 &&
          [
            'INSUFFICIENT_BALANCE',
            'WITHDRAWAL_LIMIT',
            'DISTRIBUTOR_DISABLED',
            'RISK_BLOCKED',
            'OUTSTANDING_DEBT',
          ].includes(error.code)));
    if (rejected && this.intent && !this.intent.resourceId) this.persist(null);
  }
  private accept(record: WithdrawalRecord, matchesIntent = false) {
    if (matchesIntent && this.intent && record.amountFen !== this.intent.amountFen)
      throw new Error('原提现金额与服务端记录不一致，请联系客服核对');
    this.currentRecord = record;
    if (matchesIntent && this.intent)
      this.persist(terminal(record) ? null : { ...this.intent, resourceId: String(record.id) });
    if (['REQUESTED', 'PROCESSING', 'UNKNOWN'].includes(record.status)) this.startPolling();
    else this.stopPolling();
    this.notify();
    return record;
  }
  async submitWithdrawal(amountFen: number, idempotencyKey: string): Promise<WithdrawalRecord> {
    if (this.loading) throw new Error('原提现正在处理，请稍候查询');
    if (this.intent) {
      if (amountFen !== this.intent.amountFen || idempotencyKey !== this.intent.key)
        throw new Error('原提现尚未确认，金额与幂等键不可修改');
      const recovered = await this.recoverPending();
      if (!recovered) throw new Error('原提现结果未知，请继续查询原单，不可重新申请');
      return recovered;
    }
    if (!Number.isSafeInteger(amountFen) || amountFen <= 0 || !idempotencyKey)
      throw new Error('提现金额或幂等键无效');
    if (this.persistence && !this.persistence.session.get()?.user?.id) throw new Error('请先登录');
    this.persist({ key: idempotencyKey, amountFen, resourceId: null });
    const ticket = this.ticket();
    const epoch = ++this.recordEpoch;
    this.loading = true;
    this.error = null;
    this.notify();
    try {
      const record = await this.api.requestWithdrawal(amountFen, idempotencyKey);
      this.assertCurrent(ticket);
      // Preserve the original money result even when another history record was selected.
      if (epoch !== this.recordEpoch) {
        if (record.amountFen !== amountFen)
          throw new Error('原提现金额与服务端记录不一致，请联系客服核对');
        this.persist(
          terminal(record)
            ? null
            : { key: idempotencyKey, amountFen, resourceId: String(record.id) },
        );
        return record;
      }
      return this.accept(record, true);
    } catch (error) {
      if (ticket[0] === this.generation) {
        this.releaseRejectedIntent(error);
        this.error = error instanceof Error ? error.message : '提现结果未知';
        this.notify();
      }
      throw error;
    } finally {
      if (ticket[0] === this.generation) {
        this.loading = false;
        this.notify();
      }
    }
  }
  async recoverPending(): Promise<WithdrawalRecord | null> {
    if (!this.intent) return null;
    this.stopPolling();
    const ticket = this.ticket();
    const epoch = ++this.recordEpoch;
    const intent = this.intent;
    this.loading = true;
    this.notify();
    try {
      const result = await this.api.queryWithdrawalOperation(intent.key);
      this.assertRecordCurrent(ticket, epoch);
      if (result.state !== 'SUCCEEDED' || !result.resourceId) {
        this.error = `原提现结果尚未确认（${result.state}），请继续查询原单`;
        this.notify();
        return null;
      }
      if (intent.resourceId && String(result.resourceId) !== intent.resourceId)
        throw new Error('原提现记录不一致，请联系客服核对');
      const record = await this.api.getWithdrawal(String(result.resourceId));
      this.assertRecordCurrent(ticket, epoch);
      if (String(record.id) !== String(result.resourceId) || record.amountFen !== intent.amountFen)
        throw new Error('原提现记录不一致，请联系客服核对');
      return this.accept(record, true);
    } catch (error) {
      if (ticket[0] === this.generation && epoch === this.recordEpoch) {
        this.error = error instanceof Error ? error.message : '原提现恢复失败';
        this.notify();
      }
      return null;
    } finally {
      if (ticket[0] === this.generation && epoch === this.recordEpoch) {
        this.loading = false;
        this.notify();
      }
    }
  }
  async retryPending(): Promise<WithdrawalRecord | null> {
    if (!this.intent || this.loading) return null;
    if (this.intent.resourceId) return this.recoverPending();
    const intent = this.intent,
      ticket = this.ticket();
    const epoch = ++this.recordEpoch;
    this.loading = true;
    this.error = null;
    this.notify();
    try {
      // Explicit replay uses the durable body/key, including when lookup has no record.
      const record = await this.api.requestWithdrawal(intent.amountFen, intent.key);
      this.assertCurrent(ticket);
      if (intent.resourceId && String(record.id) !== intent.resourceId)
        throw new Error('原提现记录不一致，请联系客服核对');
      if (epoch !== this.recordEpoch) {
        if (record.amountFen !== intent.amountFen)
          throw new Error('原提现金额与服务端记录不一致，请联系客服核对');
        this.persist(terminal(record) ? null : { ...intent, resourceId: String(record.id) });
        return record;
      }
      return this.accept(record, true);
    } catch (error) {
      if (ticket[0] === this.generation) {
        this.releaseRejectedIntent(error);
        this.error = error instanceof Error ? error.message : '原提现重试失败';
        this.notify();
      }
      return null;
    } finally {
      if (ticket[0] === this.generation) {
        this.loading = false;
        this.notify();
      }
    }
  }
  async refreshCurrent(recordId?: string): Promise<WithdrawalRecord | null> {
    if (!recordId && !this.currentRecord) return null;
    if (recordId && recordId !== String(this.currentRecord?.id)) this.stopPolling();
    const ticket = this.ticket(),
      id = recordId ?? String(this.currentRecord!.id),
      epoch = ++this.recordEpoch;
    this.loading = true;
    this.notify();
    try {
      const record = await this.api.refreshWithdrawal(id);
      this.assertRecordCurrent(ticket, epoch);
      if (String(record.id) !== id) throw new Error('提现记录不一致');
      return this.accept(record, this.intent?.resourceId === id);
    } catch (error) {
      if (ticket[0] === this.generation && epoch === this.recordEpoch) {
        this.error = error instanceof Error ? error.message : '查询失败';
        this.notify();
      }
      return null;
    } finally {
      if (ticket[0] === this.generation && epoch === this.recordEpoch) {
        this.loading = false;
        this.notify();
      }
    }
  }
  async selectRecord(id: string) {
    // A scheduled poll for the old selection must not become a newer request.
    this.stopPolling();
    const ticket = this.ticket();
    const epoch = ++this.recordEpoch;
    this.loading = true;
    this.error = null;
    this.notify();
    try {
      const record = await this.api.getWithdrawal(id);
      this.assertRecordCurrent(ticket, epoch);
      if (String(record.id) !== String(id)) throw new Error('提现记录不一致');
      return this.accept(record, this.intent?.resourceId === String(id));
    } finally {
      if (ticket[0] === this.generation && epoch === this.recordEpoch) {
        this.loading = false;
        this.notify();
      }
    }
  }
  startPolling() {
    if (this.polling || this.disposed) return;
    this.pollEpoch++;
    this.polling = true;
    this.pollCount = 0;
    this.scheduleNextPoll();
    this.notify();
  }
  private scheduleNextPoll() {
    if (!this.polling || this.pollCount >= 10) {
      this.stopPolling();
      return;
    }
    const epoch = this.pollEpoch;
    this.pollTimer = setTimeout(async () => {
      if (epoch !== this.pollEpoch) return;
      this.pollCount++;
      await this.refreshCurrent();
      if (this.polling && epoch === this.pollEpoch) this.scheduleNextPoll();
    }, 3000);
  }
  stopPolling() {
    this.pollEpoch++;
    this.polling = false;
    if (this.pollTimer) clearTimeout(this.pollTimer);
    this.pollTimer = null;
    this.notify();
  }
  pause() {
    this.recordEpoch++;
    this.loading = false;
    this.stopPolling();
  }
  async invokeReceiptConfirmation(recordId?: string): Promise<boolean> {
    if (!recordId && !this.currentRecord) throw new Error('请先选择本人提现单');
    const id = recordId ?? String(this.currentRecord!.id);
    const ticket = this.ticket();
    const epoch = this.recordEpoch + 1;
    const detail = await this.selectRecord(id);
    this.assertRecordCurrent(ticket, epoch);
    if (detail.status !== 'USER_ACTION_REQUIRED')
      throw new Error('该提现单当前无需确认，请查看最新状态');
    if (!detail.confirmation?.packageInfo) throw new Error('收款确认凭据暂不可用，请刷新原单');
    if (!this.confirmationAdapter)
      throw new Error('当前运行环境未适配微信收款确认组件，请在微信手机端打开');
    const result = await this.confirmationAdapter.confirmReceipt(detail.confirmation);
    this.assertCurrent(ticket);
    if (epoch === this.recordEpoch) await this.refreshCurrent(id);
    return result;
  }
  dispose() {
    this.disposed = true;
    this.generation++;
    this.stopPolling();
    this.unsubscribe?.();
    this.listeners.clear();
  }
}
