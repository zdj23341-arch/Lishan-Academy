import { defineConfig } from 'vitest/config';
import vue from '@vitejs/plugin-vue';
export default defineConfig({
  plugins: [
    vue({
      template: {
        compilerOptions: {
          isCustomElement: (tag) =>
            [
              'view',
              'cover-view',
              'text',
              'image',
              'scroll-view',
              'swiper',
              'swiper-item',
              'picker',
              'switch',
            ].includes(tag),
        },
      },
    }),
  ],
  test: {
    include: [
      'shared/tests/**/*.test.ts',
      'admin/tests/**/*.test.ts',
      'miniprogram/tests/**/*.test.ts',
    ],
  },
});
