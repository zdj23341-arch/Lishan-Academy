interface ReceiptInvocation {
  success: () => void;
  fail: () => void;
}
declare const wx: {
  requestMerchantTransfer?: (
    options: ReceiptInvocation & { mchId: string; appId: string; package: string },
  ) => void;
  openBusinessView?: (
    options: ReceiptInvocation & { businessType: string; extraData: { package_info: string } },
  ) => void;
};

import {
  createDistributionApi,
  type ReceiptConfirmationAdapter,
  type ConfirmationPayload,
} from '@jxsc/shared';
import { client } from './client';

export const distributionApi = createDistributionApi(client);

export function withdrawalKey(): string {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = Math.floor(Math.random() * 16);
    return (c === 'x' ? r : (r & 3) | 8).toString(16);
  });
}

export const wechatReceiptConfirmationAdapter: ReceiptConfirmationAdapter = {
  async confirmReceipt(payload: ConfirmationPayload): Promise<boolean> {
    if (typeof wx === 'undefined') {
      throw new Error('当前非微信小程序环境，请在微信手机端完成收款确认');
    }

    if (typeof wx.requestMerchantTransfer === 'function' && payload.merchantId && payload.appId) {
      return new Promise<boolean>((resolve) =>
        wx.requestMerchantTransfer!({
          mchId: payload.merchantId!,
          appId: payload.appId!,
          package: payload.packageInfo,
          success: () => resolve(true),
          fail: () => resolve(false),
        }),
      );
    }
    // Older supported environments use the official business view.
    if (typeof wx.openBusinessView !== 'function') {
      throw new Error(
        '当前微信版本过低，暂不支持调起收款确认，请升级微信或前往“微信支付”服务通知确认收款',
      );
    }

    return new Promise((resolve) => {
      wx.openBusinessView!({
        businessType: 'confirmReceipt',
        extraData: {
          package_info: payload.packageInfo,
        },
        success: () => {
          // 调起成功，用户已在微信侧操作
          resolve(true);
        },
        fail: () => {
          // 用户取消或调起异常
          resolve(false);
        },
      });
    });
  },
};
