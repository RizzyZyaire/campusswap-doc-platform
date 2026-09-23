import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import tseslint from 'typescript-eslint'

// 前端 lint 口径（对应 MASTER-PLAN T5.8 与老师红线 R6）：
//   · 禁 any（用 unknown + 类型守卫）
//   · 禁内联样式（style="..." / :style="{...}"）——样式一律走 Tailwind 原子类与 main.css 的 @layer components
export default tseslint.config(
  { ignores: ['dist/**', 'node_modules/**', 'scripts/**', 'src/assets/**'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs['flat/essential'],
  {
    files: ['**/*.vue'],
    languageOptions: {
      parserOptions: { parser: tseslint.parser }
    }
  },
  {
    rules: {
      '@typescript-eslint/no-explicit-any': 'error',
      'vue/no-restricted-static-attribute': [
        'error',
        { key: 'style', message: '禁内联样式：请用 Tailwind 原子类或 main.css 的 @layer components（红线 R6）' }
      ],
      'vue/no-restricted-v-bind': [
        'error',
        { argument: 'style', message: '禁内联样式：请用 Tailwind 原子类或 main.css 的 @layer components（红线 R6）' }
      ],
      'vue/multi-word-component-names': 'off',
      'no-console': ['warn', { allow: ['warn', 'error'] }]
    }
  }
)
