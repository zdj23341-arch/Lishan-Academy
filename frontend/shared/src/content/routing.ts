import type { Target } from '../contracts';
export function targetToRoute(target: Target): string {
  const detail = {
    PRODUCT: '/pages/product/detail',
    ARTICLE: '/pages/article/detail',
    COURSE_DETAIL: '/pages/course/learn',
  } as const;
  if (!target || typeof target !== 'object') throw new Error('跳转目标无效');
  if (target.type === 'PRODUCT' || target.type === 'ARTICLE' || target.type === 'COURSE_DETAIL') {
    if (!target.id?.trim()) throw new Error('跳转目标缺少内容ID');
    return `${detail[target.type]}?id=${encodeURIComponent(target.id)}`;
  }
  const routes = {
    PRODUCT_LIST: '/pages/product/list',
    MEMBERSHIP: '/pages/membership/index',
    PAPER_LIST: '/pages/paper/list',
    LEARNING: '/pages/learning/index',
    ORDER_LIST: '/pages/order/list',
    // The logistics page requires an orderId. A homepage entry therefore opens
    // the authenticated order list where the user can choose an eligible order.
    LOGISTICS: '/pages/order/list',
    MEMBERSHIP_CENTER: '/pages/membership/index',
  };
  if (!Object.prototype.hasOwnProperty.call(routes, target.type))
    throw new Error('不支持的内部跳转类型');
  const keys =
    target.type === 'PRODUCT_LIST'
      ? ['keyword', 'type', 'categoryId', 'sort']
      : target.type === 'PAPER_LIST'
        ? ['keyword', 'schoolStage', 'stage', 'grade', 'year', 'subject', 'paperType', 'type']
        : [];
  const query = Object.entries(target.filters ?? {})
    .map(([key, value]) => {
      if (!keys.includes(key) || typeof value !== 'string')
        throw new Error(`不支持的跳转筛选项：${key}`);
      if (key === 'type' && !['COURSE', 'BOOK', 'MEMBERSHIP'].includes(value))
        throw new Error('商品类型无效');
      if (key === 'sort' && !['DEFAULT', 'PRICE_ASC', 'PRICE_DESC', 'NEWEST'].includes(value))
        throw new Error('排序无效');
      return `${key}=${encodeURIComponent(value)}`;
    })
    .join('&');
  return `${routes[target.type]}${query ? `?${query}` : ''}`;
}
