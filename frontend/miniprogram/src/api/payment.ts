import { ApiError } from '@jxsc/shared';
import type {
  PaymentAdapter,
  PaymentCallback,
  PaymentMode,
} from '../../../shared/src/commerce/payment';
import { environment } from './environment';
interface WechatParams {
  timeStamp: string;
  nonceStr: string;
  package: string;
  signType: 'RSA';
  paySign: string;
}
export function createPlatformPaymentAdapter(
  mode: PaymentMode,
  request: (params: WechatParams) => Promise<PaymentCallback>,
): PaymentAdapter {
  return async (prepay) => {
    if (prepay.provider === 'DEV' && mode !== 'production') return 'cancel';
    if (prepay.provider !== 'WECHAT_PAY' || prepay.simulated)
      throw new ApiError('PROVIDER_DISABLED', '当前支付渠道不可用');
    const params = prepay.params;
    if (
      !params ||
      typeof params.timeStamp !== 'string' ||
      !/^\d+$/.test(params.timeStamp) ||
      typeof params.nonceStr !== 'string' ||
      !params.nonceStr ||
      typeof params.package !== 'string' ||
      !params.package.startsWith('prepay_id=') ||
      params.signType !== 'RSA' ||
      typeof params.paySign !== 'string' ||
      !params.paySign
    )
      throw new ApiError('INVALID_RESPONSE', '支付参数不完整，请查询订单状态');
    return request({
      timeStamp: params.timeStamp,
      nonceStr: params.nonceStr,
      package: params.package,
      signType: 'RSA',
      paySign: params.paySign,
    });
  };
}
export const platformPayment = createPlatformPaymentAdapter(environment.mode, (params) => {
  // #ifdef MP-WEIXIN
  return new Promise((resolve) => {
    uni.requestPayment({
      ...params,
      provider: 'wxpay',
      success: () => resolve('success'),
      fail: (error) => resolve(error.errMsg.includes('cancel') ? 'cancel' : 'failure'),
    });
  });
  // #endif
  // #ifndef MP-WEIXIN
  return Promise.reject(new ApiError('UNSUPPORTED_PLATFORM', '请在微信小程序内完成付款'));
  // #endif
});
