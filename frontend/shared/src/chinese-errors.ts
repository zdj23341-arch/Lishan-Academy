// Codes stay unchanged for permission checks and recovery; only display text is localized.
const messages = {
  UNAUTHORIZED: '登录已失效，请重新登录',
  FORBIDDEN: '暂无操作权限',
  NOT_FOUND: '内容不存在或已下架',
  VALIDATION_ERROR: '请检查填写的信息',
  INVALID_ARGUMENT: '请检查填写的信息',
  INVALID_REQUEST: '请求参数有误，请检查后重试',
  VERSION_CONFLICT: '数据已更新，请刷新后重试',
  STALE_VERSION: '数据已更新，请刷新后重试',
  CONFLICT: '当前状态已变化，请刷新后重试',
  SHIPPING_UNAVAILABLE: '当前收货地区暂不支持配送，请更换地址或联系客服',
  OUT_OF_STOCK: '库存不足，请调整购买数量',
  INSUFFICIENT_STOCK: '库存不足，请调整购买数量',
  PRODUCT_UNAVAILABLE: '商品暂不可购买',
  MEMBERSHIP_REQUIRED: '请先开通试卷会员',
  MEMBERSHIP_EXPIRED: '会员已到期，请续费后重试',
  DISTRIBUTOR_NOT_ENABLED: '当前账号尚未开通分销资格，请联系客服',
  WITHDRAWAL_NOT_READY: '收款身份或转账渠道尚未就绪，请核对收款绑定状态或联系客服',
  DISTRIBUTOR_DISABLED: '分销档案已停用，请联系客服',
  COURSE_ACCESS_DENIED: '暂无课程学习权限，请先购买课程',
  RATE_LIMITED: '操作过于频繁，请稍后重试',
  TOO_MANY_REQUESTS: '操作过于频繁，请稍后重试',
  FILE_NOT_READY: '文件尚未就绪，请稍后重试',
  IDEMPOTENCY_CONFLICT: '重复操作的信息不一致，请先查询原操作结果',
} satisfies Record<string, string>;

export function chineseErrorMessage(code: string, message: string, status: number): string {
  if (/[\u3400-\u9fff]/.test(message)) return message;
  if (code === 'VALIDATION_ERROR' && message === 'Course terms are required')
    return '课程商品尚未关联课程配置，请到课程管理关联该商品并保存有效期后再上架';
  if (Object.hasOwn(messages, code)) return messages[code as keyof typeof messages];
  if (status === 401) return messages.UNAUTHORIZED;
  if (status === 403) return messages.FORBIDDEN;
  if (status === 404) return messages.NOT_FOUND;
  if (status === 409) return messages.CONFLICT;
  if (status === 429) return messages.RATE_LIMITED;
  if (status >= 500) return '服务暂时不可用，请稍后重试';
  return '请求未完成，请检查信息后重试';
}

export function chineseFieldError(message: string): string {
  if (/[\u3400-\u9fff]/.test(message)) return message;
  if (/must not be (blank|null|empty)/i.test(message)) return '不能为空';
  if (/must be (greater|less)|size must be between/i.test(message)) return '数值或长度超出允许范围';
  if (/must be a well-formed email/i.test(message)) return '邮箱格式不正确';
  return '填写的信息不符合要求';
}
