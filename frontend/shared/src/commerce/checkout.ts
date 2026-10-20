import { ApiError } from '../http';
import { formatFen } from '../money';
import type { Session, SessionStorage } from '../session';
import type { CommerceApi } from './api';
import { createOperationController } from './operation';
import type {
  CheckoutDraft,
  CheckoutProduct,
  CreateOrderRequest,
  PreviewRequest,
  QuoteView,
} from './types';

export function createCheckoutController(options: {
  api: CommerceApi;
  session: Session;
  storage: SessionStorage;
  namespace: string;
  keyGenerator?: () => string;
  now?: () => number;
}) {
  const operation = createOperationController<CreateOrderRequest>({
    ...options,
    scope: 'CREATE_ORDER',
    execute: options.api.createOrder,
    query: (key) => options.api.operation(key),
  });
  const state = {
    draft: { productId: '', quantity: 1, addressId: null } as CheckoutDraft,
    displayedQuote: null as QuoteView | null,
    loading: false,
    needsConfirmation: false,
    error: null as Error | null,
  };
  let product: CheckoutProduct | null = null;
  let epoch = 0;
  let quotedRequest: PreviewRequest | null = null;
  let quoteValid = false;
  let submitting: Promise<void> | null = null;
  const now = options.now ?? Date.now;
  function request(): PreviewRequest {
    if (!product || state.draft.productId !== product.id) throw new Error('请选择有效商品');
    const { productId, quantity } = state.draft;
    if (!Number.isSafeInteger(quantity) || quantity < 1) throw new Error('购买数量无效');
    if (product.type !== 'BOOK') {
      if (quantity !== 1) throw new Error('数字商品数量只能为1');
      return { productId, quantity };
    }
    if (quantity > Math.min(99, product.quantityLimit ?? 99, product.availableStock ?? 0))
      throw new Error('购买数量超过库存或限购');
    if (!state.draft.addressId?.trim()) throw new Error('请选择收货地址');
    return { productId, quantity, addressId: state.draft.addressId };
  }
  function liveQuote() {
    if (
      !quoteValid ||
      !state.displayedQuote ||
      JSON.stringify(request()) !== JSON.stringify(quotedRequest)
    )
      throw new Error('请重新预览订单');
    if (Date.parse(state.displayedQuote.expiresAt) <= now())
      throw new Error('报价已过期，请刷新后确认');
    return state.displayedQuote;
  }
  const unsubscribe = options.session.subscribe(() => {
    epoch++;
    quoteValid = false;
    state.displayedQuote = null;
    state.loading = false;
    state.needsConfirmation = false;
  });
  const controller = {
    state,
    operation,
    setDraft(draft: CheckoutDraft, selectedProduct: CheckoutProduct) {
      state.draft = { ...draft };
      product = { ...selectedProduct };
      epoch++;
      quoteValid = false;
      state.loading = false;
    },
    async refresh() {
      const ticket = ++epoch;
      quoteValid = false;
      state.error = null;
      state.loading = true;
      try {
        if (!options.session.get()) throw new ApiError('UNAUTHORIZED', '请先登录');
        const body = request();
        const result = await options.api.preview(body);
        if (ticket !== epoch) return;
        if (
          !result.quoteId ||
          !Number.isFinite(Date.parse(result.expiresAt)) ||
          result.currency !== 'CNY' ||
          result.item.productId !== body.productId ||
          result.item.type !== product?.type ||
          !Number.isSafeInteger(result.item.quantity) ||
          result.item.quantity < 1
        )
          throw new ApiError('INVALID_RESPONSE', '报价响应无效');
        for (const value of [
          result.goodsAmountFen,
          result.freightAmountFen,
          result.payableAmountFen,
          result.item.unitPriceFen,
        ]) {
          formatFen(value);
          if (value < 0) throw new ApiError('INVALID_RESPONSE', '报价金额无效');
        }
        const previous = state.displayedQuote;
        state.needsConfirmation =
          state.needsConfirmation ||
          (!previous && result.item.quantity !== body.quantity) ||
          (!!previous &&
            (JSON.stringify(quotedRequest) !== JSON.stringify(body) ||
              previous.goodsAmountFen !== result.goodsAmountFen ||
              previous.freightAmountFen !== result.freightAmountFen ||
              previous.payableAmountFen !== result.payableAmountFen ||
              previous.item.quantity !== result.item.quantity));
        state.displayedQuote = result;
        quotedRequest = body;
        quoteValid = true;
      } catch (error) {
        if (ticket === epoch)
          state.error = error instanceof Error ? error : new Error('订单预览失败');
        throw error;
      } finally {
        if (ticket === epoch) state.loading = false;
      }
    },
    confirmQuote() {
      liveQuote();
      state.needsConfirmation = false;
    },
    submit(attributionToken: string | null = null): Promise<void> {
      try {
        if (operation.state.key && operation.state.status !== 'FAILED') return operation.recover();
        if (submitting) return submitting;
        const quote = liveQuote();
        if (state.needsConfirmation) throw new Error('报价已变更，请确认后提交');
        if (operation.state.status === 'FAILED') {
          if (operation.state.requestSnapshot?.quoteId === quote.quoteId)
            throw new Error('原下单已失败，请刷新报价后重新确认');
          operation.reset();
        }
        const ticket = epoch + 1;
        submitting = controller
          .refresh()
          .then(() => {
            if (ticket !== epoch) throw new Error('订单选择已变更，请重新确认');
            const refreshed = liveQuote();
            if (state.needsConfirmation) throw new Error('报价已变更，请确认后提交');
            return operation.submit({ quoteId: refreshed.quoteId, attributionToken });
          })
          .finally(() => {
            submitting = null;
          });
        return submitting;
      } catch (error) {
        return Promise.reject(error);
      }
    },
    dispose() {
      epoch++;
      unsubscribe();
      operation.dispose();
    },
  };
  return controller;
}
