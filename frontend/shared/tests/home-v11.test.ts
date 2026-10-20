import { expect, it } from 'vitest';
import { createDevCatalogStore } from '../src/dev/catalog';
import { targetToRoute } from '../src/content/routing';

it('initial six shortcuts use confirmed labels and valid existing internal destinations', () => {
  const store = createDevCatalogStore();
  expect(store.home.shortcuts.map((item) => item.label)).toEqual([
    '视频课程',
    '作文书籍',
    '会员试卷',
    '我的学习',
    '会员中心',
    '订单物流',
  ]);
  expect(store.home.shortcuts.map((item) => targetToRoute(item.target))).toEqual([
    '/pages/product/list?type=COURSE',
    '/pages/product/list?type=BOOK',
    '/pages/paper/list',
    '/pages/learning/index',
    '/pages/membership/index',
    '/pages/order/list',
  ]);
});
