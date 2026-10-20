import js from '@eslint/js';
import ts from 'typescript-eslint';
import vue from 'eslint-plugin-vue';
export default ts.config(
  { ignores: ['**/dist/**', '**/node_modules/**', 'assets/**'] },
  js.configs.recommended, ...ts.configs.recommended, ...vue.configs['flat/essential'],
  { files: ['**/*.vue'], languageOptions: { parserOptions: { parser: ts.parser } } },
  { files: ['miniprogram/src/**/*.{ts,vue}'], languageOptions: { globals: { uni: 'readonly', getCurrentPages: 'readonly' } } },
  { rules: { 'vue/multi-word-component-names': 'off' } },
);
