// @vitest-environment jsdom
import { afterEach, expect, it, vi } from 'vitest';
import { mount, type VueWrapper } from '@vue/test-utils';
import ShortcutGrid from '../src/components/ShortcutGrid.vue';
import HomeCarousel from '../src/components/HomeCarousel.vue';
import HomeSection from '../src/components/HomeSection.vue';
import ProductCard from '../src/components/ProductCard.vue';
import { navigate } from '../src/composables/useCatalog';
import type { HomeView, ProductCard as ProductCardModel } from '@jxsc/shared';

vi.mock('../src/composables/useCatalog', () => ({
  navigate: vi.fn(),
}));

const wrappers: VueWrapper[] = [];

const target: HomeView['shortcuts'][number]['target'] = {
  type: 'PRODUCT_LIST',
  id: null,
  filters: {},
};

const shortcuts: HomeView['shortcuts'] = [
  '视频课程',
  '作文书籍',
  '会员试卷',
  '我的学习',
  '会员中心',
  '订单物流',
].map((label, index) => ({
  id: String(index + 1),
  label,
  iconUrl: '',
  target,
}));

const course: ProductCardModel = {
  id: 'course-1',
  type: 'COURSE',
  title: '小学作文系统课',
  coverUrl: null,
  priceFen: 9900,
  originalPriceFen: 12900,
  status: 'ON_SALE',
  subtitle: '从审题到成文的完整训练',
};

afterEach(() => {
  wrappers.splice(0).forEach((wrapper) => wrapper.unmount());
  vi.clearAllMocks();
});

it('renders the six approved Chinese shortcuts in a compact six-entry grid', () => {
  const wrapper = mount(ShortcutGrid, { props: { shortcuts } });
  wrappers.push(wrapper);

  expect(wrapper.findAll('.shortcut-item')).toHaveLength(6);
  expect(wrapper.find('.shortcut-grid').classes()).toContain('shortcut-grid--six');
  expect(wrapper.text()).toContain('视频课程');
  expect(wrapper.text()).toContain('订单物流');
});

it('uses a file icon for member papers and a crown for the membership center', () => {
  const wrapper = mount(ShortcutGrid, { props: { shortcuts } });
  wrappers.push(wrapper);

  const icons = wrapper.findAll('.ls-icon').map((icon) => icon.attributes('src'));
  expect(icons[2]).toBe('/static/design/icons/files-blue.png');
  expect(icons[4]).toBe('/static/design/icons/crown-blue.png');
});

it('renders every published homepage banner and navigates with its own target', async () => {
  const banners: HomeView['banners'] = [
    { id: '1', title: '课程', imageUrl: '/banner-course.png', target },
    {
      id: '2',
      title: '图书',
      imageUrl: '/banner-books.png',
      target: { type: 'PRODUCT', id: 'book-2', filters: {} },
    },
    { id: '3', title: '试卷', imageUrl: '/banner-papers.png', target },
  ];
  const wrapper = mount(HomeCarousel, { props: { banners } });
  wrappers.push(wrapper);

  expect(wrapper.find('swiper').exists()).toBe(true);
  expect(wrapper.findAll('swiper-item')).toHaveLength(3);
  expect(wrapper.findAll('.banner-card__img').map((image) => image.attributes('src'))).toEqual([
    '/banner-course.png',
    '/banner-books.png',
    '/banner-papers.png',
  ]);
  await wrapper.findAll('.home-swiper__item')[1]!.trigger('click');
  expect(navigate).toHaveBeenCalledWith(banners[1]!.target);
});

it('falls back to the local banner when a published banner image fails', async () => {
  const banners: HomeView['banners'] = [
    { id: '1', title: '课程', imageUrl: '/unavailable.png', target },
  ];
  const wrapper = mount(HomeCarousel, { props: { banners } });
  wrappers.push(wrapper);

  const image = wrapper.get('.banner-card__img');
  await image.trigger('error');
  expect(wrapper.get('.banner-card__img').attributes('src')).toBe(
    '/static/design/banners/home-three-business.png',
  );
  await wrapper.get('.banner-card__img').trigger('error');
  expect(wrapper.find('.banner-card__placeholder').exists()).toBe(true);
});

it('uses a portrait cover class and keeps the product card hierarchy compact', () => {
  const wrapper = mount(ProductCard, { props: { product: course } });
  wrappers.push(wrapper);

  expect(wrapper.find('.product-card__cover-box').classes()).toContain(
    'product-card__cover-box--portrait',
  );
  expect(wrapper.find('.product-card__title').text()).toContain('小学作文系统课');
  expect(wrapper.find('.product-card__meta').exists()).toBe(false);
});

it('shows a fallback label when the product type is unknown at runtime', () => {
  const unknownTypeProduct = {
    ...course,
    type: 'UNKNOWN',
  } as unknown as ProductCardModel;
  const wrapper = mount(ProductCard, { props: { product: unknownTypeProduct } });
  wrappers.push(wrapper);

  expect(wrapper.get('.product-card__type').text()).toBe('商品');
});

it('keeps the supplied business section title and presents an empty module clearly', () => {
  const section: HomeView['sections'][number] = {
    id: 'papers',
    type: 'PRODUCT_GRID',
    title: '会员试卷下载',
    moreTarget: null,
    products: [],
    article: null,
  };
  const wrapper = mount(HomeSection, { props: { section } });
  wrappers.push(wrapper);

  expect(wrapper.find('.home-section').classes()).toContain('home-section--product-grid');
  expect(wrapper.text()).toContain('会员试卷下载');
  expect(wrapper.text()).toContain('该模块暂未发布内容');
});

it('renders paper grid sections from the server supplied paper array', () => {
  const section: HomeView['sections'][number] = {
    id: 'papers',
    type: 'PAPER_GRID',
    title: '会员试卷下载',
    moreTarget: { type: 'PAPER_LIST', id: null, filters: { paperType: 'PRACTICE' } },
    products: [],
    article: null,
    papers: [
      {
        id: 'paper-1',
        title: '七年级期末语文',
        stage: 'JUNIOR',
        schoolStage: 'JUNIOR',
        grade: '7',
        year: 2026,
        subject: 'CHINESE',
        paperType: 'FINAL_EXAM',
        summary: '公开摘要',
        coverUrl: null,
        attachmentFormats: ['PDF'],
        accessCondition: 'ACTIVE_MEMBER',
        resourceStatus: 'READY',
      },
    ],
  };
  const wrapper = mount(HomeSection, { props: { section } });
  wrappers.push(wrapper);

  expect(wrapper.find('.paper-grid').exists()).toBe(true);
  expect(wrapper.text()).toContain('七年级期末语文');
  expect(wrapper.text()).toContain('PDF');
  expect(wrapper.text()).toContain('会员可访问');
});

it.each([
  {
    sectionType: 'BOOK_GRID',
    target: { type: 'PRODUCT_LIST', id: null, filters: { type: 'BOOK' } },
  },
  {
    sectionType: 'COURSE_GRID',
    target: { type: 'PRODUCT_LIST', id: null, filters: { type: 'COURSE' } },
  },
  {
    sectionType: 'PAPER_GRID',
    target: { type: 'PAPER_LIST', id: null, filters: { paperType: 'PRACTICE' } },
  },
] as const)(
  'shows a working more link for $sectionType when the server target is empty',
  async ({ sectionType, target }) => {
    const section: HomeView['sections'][number] = {
      id: sectionType,
      type: sectionType,
      title: '内容专栏',
      moreTarget: null,
      products: [],
      article: null,
    };
    const wrapper = mount(HomeSection, { props: { section } });
    wrappers.push(wrapper);

    expect(wrapper.get('.more-btn').text()).toContain('查看更多');
    await wrapper.get('.more-btn').trigger('click');
    expect(navigate).toHaveBeenCalledWith(target);
  },
);
