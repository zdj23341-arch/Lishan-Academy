import type { OrderView } from '../contracts';
import { ApiError } from '../http';
import type { AftersaleView } from '../commerce/aftersale';
import type { AdminAftersale, DecisionPreview } from '../commerce/admin-api';
import type { DevCatalogStore } from './catalog';

export type DevRefundOutcome = 'PENDING' | 'SUCCESS' | 'FAILURE';
interface RefundOrder extends OrderView {
  version: number;
  refundedGoodsFen: number;
  refundedFreightFen: number;
}
/** Frontend scenario provider, not a backend refund processor or authorization boundary. */
export function createDevAftersaleAdmin(options: {
  cases: Map<string, AftersaleView>;
  order: (id: string) => RefundOrder;
  changed: (id: string) => void;
  catalog: DevCatalogStore;
  outcome: DevRefundOutcome;
}) {
  function fail(code: string, message: string, status = 422): never {
    throw new ApiError(code, message, null, {}, status);
  }
  function reason(body: Record<string, unknown>) {
    const value = typeof body.reason === 'string' ? body.reason.trim() : '';
    if (!value || value.length > 500) fail('VALIDATION_ERROR', '原因须为1至500字');
    return value;
  }
  function remainingQuantity(c: AftersaleView) {
    const order = options.order(c.orderId);
    if (order.item.type !== 'BOOK' || !['SHIPPED', 'RECEIVED'].includes(order.fulfillmentStatus))
      return 0;
    const received = [...options.cases.values()]
      .filter((value) => value.orderId === order.id)
      .reduce((sum, value) => sum + (value.receivedQuantity ?? 0), 0);
    return Math.max(0, order.item.quantity - received);
  }
  function view(c: AftersaleView): AdminAftersale {
    const allowedActions =
      c.status === 'REQUESTED'
        ? ['APPROVE', 'REJECT']
        : c.status === 'RETURNING'
          ? ['RECEIVE_RETURN']
          : c.status === 'RETURN_RECEIVED'
            ? ['APPROVE']
            : [];
    return { ...c, allowedActions, permittedReturnQuantity: remainingQuantity(c) };
  }
  function requireAction(c: AftersaleView, action: string) {
    if (!view(c).allowedActions.includes(action))
      fail('ACTION_NOT_ALLOWED', '当前售后状态不允许此操作', 409);
  }
  function preview(c: AftersaleView, body: Record<string, unknown>): DecisionPreview {
    requireAction(c, 'APPROVE');
    reason(body);
    const order = options.order(c.orderId);
    const goods = body.goodsRefundFen,
      freight = body.freightRefundFen;
    const remainingGoodsFen = order.goodsAmountFen - order.refundedGoodsFen;
    const remainingFreightFen = order.freightAmountFen - order.refundedFreightFen;
    if (
      typeof goods !== 'number' ||
      typeof freight !== 'number' ||
      !Number.isSafeInteger(goods) ||
      !Number.isSafeInteger(freight) ||
      goods < 0 ||
      freight < 0 ||
      !Number.isSafeInteger(goods + freight) ||
      goods + freight <= 0 ||
      goods > remainingGoodsFen ||
      freight > remainingFreightFen
    )
      fail('VALIDATION_ERROR', '退款金额超出剩余预算或格式无效');
    return {
      remainingGoodsFen,
      remainingFreightFen,
      entitlementImpact:
        order.item.type === 'BOOK'
          ? 'DEV实体图书无数字权益；库存仅按确认的退货入库数量变更'
          : 'DEV数字权益影响待课程会员场景接入，不代表正式预览结果',
      commissionImpact: 'DEV当前订单未归因分销员，无佣金冲回；分销场景另行验证',
    };
  }
  function changed(c: AftersaleView) {
    options.order(c.orderId).version++;
    options.changed(c.orderId);
  }
  function approve(c: AftersaleView, body: Record<string, unknown>) {
    preview(c, body);
    const order = options.order(c.orderId);
    const goods = body.goodsRefundFen as number,
      freight = body.freightRefundFen as number;
    if (c.type === 'RETURN_REFUND' && c.status === 'REQUESTED') {
      if (!remainingQuantity(c)) fail('ACTION_NOT_ALLOWED', '没有可退回的图书数量', 409);
      c.status = 'RETURN_REQUIRED';
      c.returnAddress = 'DEV虚构退货仓库：广东省广州市天河区前端测试路1号，请勿实际寄送';
      c.allowedActions = ['SUBMIT_RETURN_SHIPMENT'];
      order.refundStatus = 'RETURN_REQUIRED';
    } else {
      c.allowedActions = [];
      // Unshipped stock restoration needs a confirmed quantity from the provider;
      // this fixture does not infer that quantity from a refund amount.
      const outcome =
        options.outcome === 'SUCCESS' &&
        order.item.type === 'BOOK' &&
        order.fulfillmentStatus === 'PENDING_SHIPMENT'
          ? 'PENDING'
          : options.outcome;
      if (outcome === 'SUCCESS') {
        order.refundedGoodsFen += goods;
        order.refundedFreightFen += freight;
        order.refundedAmountFen = order.refundedGoodsFen + order.refundedFreightFen;
        c.status = order.refundedAmountFen === order.paidAmountFen ? 'FULL' : 'PARTIAL';
        order.refundStatus = c.status as 'FULL' | 'PARTIAL';
      } else if (outcome === 'FAILURE') {
        c.status = 'FAILED';
        c.failureReason = 'DEV模拟退款失败，未扣减可退余额';
        order.refundStatus = 'FAILED';
      } else {
        c.status = 'PROCESSING';
        order.refundStatus = 'PROCESSING';
      }
    }
    c.approvedGoodsFen = goods;
    c.approvedFreightFen = freight;
    changed(c);
    return view(c);
  }
  function reject(c: AftersaleView, body: Record<string, unknown>) {
    requireAction(c, 'REJECT');
    c.rejectionReason = reason(body);
    c.status = 'REJECTED';
    c.allowedActions = [];
    options.order(c.orderId).refundStatus = 'REJECTED';
    changed(c);
    return view(c);
  }
  function receive(c: AftersaleView, body: Record<string, unknown>) {
    requireAction(c, 'RECEIVE_RETURN');
    const quantity = body.returnedQuantity;
    if (
      typeof quantity !== 'number' ||
      !Number.isSafeInteger(quantity) ||
      quantity < 0 ||
      quantity > remainingQuantity(c)
    )
      fail('VALIDATION_ERROR', '退货入库数量无效');
    const order = options.order(c.orderId);
    const product = options.catalog.products.find((value) => value.id === order.item.productId);
    if (product?.type !== 'BOOK') fail('ACTION_NOT_ALLOWED', '退货商品库存不存在', 409);
    const available = product.book.availableStock + quantity;
    if (!Number.isSafeInteger(available)) fail('VALIDATION_ERROR', '库存数量超出范围');
    product.book.availableStock = available;
    c.returnedQuantity = quantity;
    c.receivedQuantity = quantity;
    c.status = 'RETURN_RECEIVED';
    c.allowedActions = [];
    changed(c);
    return view(c);
  }
  return { view, preview, approve, reject, receive };
}
