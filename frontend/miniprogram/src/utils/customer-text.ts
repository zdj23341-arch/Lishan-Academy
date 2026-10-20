import {
  orderStatusLabel as sharedOrderStatusLabel,
  fulfillmentStatusLabel as sharedFulfillmentStatusLabel,
} from '../../../shared/src/commerce/order';
import { aftersaleStatusLabel as sharedAftersaleStatusLabel } from '../../../shared/src/commerce/aftersale';
import type { WithdrawalRecord } from '@jxsc/shared';

const reasonLabels: Record<string, string> = {
  UNAUTHORIZED: '请先登录或重新登录后继续',
  FORBIDDEN: '当前账号无权执行此操作，请核对权益或联系客服',
  DISTRIBUTOR_NOT_ENABLED: '当前账号尚未开通分销资格，请联系客服',
  WITHDRAWAL_NOT_READY: '收款身份或转账渠道尚未就绪，请核对收款绑定状态或联系客服',
  DISTRIBUTOR_DISABLED: '分销档案已停用，请联系客服',
  RISK_BLOCKED: '账户提现受限，请联系客服',
  OUTSTANDING_DEBT: '存在售后退款欠款，暂不可提现',
  FINANCIAL_RECONCILIATION_REQUIRED: '账务结果待核对，暂不可提现，请联系客服',
  FINANCIAL_HOLD: '相关订单账务待核对，暂不可提现',
};
const operationLabels: Record<string, string> = {
  PROCESSING: '处理中',
  SUCCEEDED: '已完成',
  FAILED: '失败',
  UNKNOWN: '结果待确认',
  NOT_FOUND: '暂未查到',
};
const paperTypeLabels: Record<string, string> = {
  PRACTICE: '练习',
  FINAL_EXAM: '期中期末',
  PAST_EXAM: '历年真题',
};
function lookup(labels: Record<string, string>, key: string, fallback: string): string {
  return Object.prototype.hasOwnProperty.call(labels, key) ? (labels[key] ?? fallback) : fallback;
}
/** Only UI/system messages belong here; business content and identifiers remain verbatim. */
export function customerMessage(value: unknown, fallback: string): string {
  const message =
    typeof value === 'string'
      ? value
      : value instanceof Error
        ? value.message
        : value &&
            typeof value === 'object' &&
            'errMsg' in value &&
            typeof value.errMsg === 'string'
          ? value.errMsg
          : '';
  if (/[\u3400-\u9fff]/.test(message)) {
    return message.replace(
      /（(PROCESSING|SUCCEEDED|FAILED|UNKNOWN|NOT_FOUND)）/g,
      (_, state: string) => `（${lookup(operationLabels, state, '结果待确认')}）`,
    );
  }
  const code =
    value && typeof value === 'object' && 'code' in value && typeof value.code === 'string'
      ? value.code
      : message.trim();
  return lookup(reasonLabels, code, fallback);
}
function customerStatus(label: string): string {
  return label.startsWith('未知状态（') ? '状态待确认' : label;
}
export const orderStatusLabel = (status: string) => customerStatus(sharedOrderStatusLabel(status));
export const fulfillmentStatusLabel = (status: string) =>
  customerStatus(sharedFulfillmentStatusLabel(status));
export const aftersaleStatusLabel = (status: string) =>
  customerStatus(sharedAftersaleStatusLabel(status));

export function paperTypeLabel(value: string | null | undefined): string {
  if (!value) return '类型待确认';
  if (/[\u3400-\u9fff]/.test(value)) return value;
  return lookup(paperTypeLabels, value.trim().toUpperCase(), '其他类型');
}

export function carrierLabel(code: string | null): string {
  if (!code) return '物流承运商';
  return lookup(
    {
      SF: '顺丰速运',
      SHUNFENG: '顺丰速运',
      SHUNFENGKUAIYUN: '顺丰快运',
      ZTO: '中通快递',
      ZHONGTONG: '中通快递',
      YTO: '圆通速递',
      YUANTONG: '圆通速递',
      STO: '申通快递',
      SHENTONG: '申通快递',
      YD: '韵达快递',
      YUNDA: '韵达快递',
      EMS: '邮政特快专递',
      YOUZHENGGUONEI: '邮政快递包裹',
      JT: '极兔速递',
      JITU: '极兔速递',
      JTEXPRESS: '极兔速递',
      JD: '京东物流',
      JINGDONG: '京东物流',
      DEBANGWULIU: '德邦快递',
      HTKY: '百世快递',
      BAISHIWULIU: '百世快运',
      ANNENGWULIU: '安能物流',
      KUAYUE: '跨越速运',
      ZHAIJISONG: '宅急送',
      TIANTIAN: '天天快递',
    },
    code.trim().toUpperCase(),
    /[\u3400-\u9fff]/.test(code) ? code : `承运商代码：${code}`,
  );
}
export function shipmentStatusLabel(status: string): string {
  return lookup(
    {
      IN_TRANSIT: '运输中',
      SIGNED: '已签收',
      RETURNED: '已退回',
      REFUSED: '已拒收',
      EXCEPTION: '物流异常',
      UNKNOWN: '物流状态待确认',
    },
    status,
    '物流状态待确认',
  );
}
export function withdrawalStatusLabel(status: string): string {
  return lookup(
    {
      REQUESTED: '已申请',
      PROCESSING: '处理中',
      UNKNOWN: '结果待确认',
      USER_ACTION_REQUIRED: '待确认收款',
      SUCCEEDED: '已到账',
      FAILED: '提现失败',
      CLOSED: '已关闭',
    },
    status,
    '提现状态待确认',
  );
}
export function commissionStatusLabel(status: string): string {
  return lookup(
    {
      AVAILABLE: '已入账可提现',
      PENDING: '售后期冻结中',
      PARTIALLY_REVERSED: '部分退款冲回',
      REVERSED: '已全额退款冲回',
    },
    status,
    '佣金状态待确认',
  );
}
export function withdrawalReason(record: WithdrawalRecord): string {
  const reason = [record.failReason, record.failedReason].find(
    (value) => value && /[\u3400-\u9fff]/.test(value),
  );
  return customerMessage(
    reason || record.errorCode || record.failReason || record.failedReason,
    '渠道提示暂不可用，请刷新原提现单或联系客服',
  );
}
