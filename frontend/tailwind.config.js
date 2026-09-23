/** @type {import('tailwindcss').Config} */
// 令牌全部来自 src/styles/theme.css（由 scripts/extract-assets.mjs 从 UI-PREVIEW.html 提取，
// 与用户逐张确认过的预览稿逐字一致）。Tailwind 只做「令牌 → 原子类」的映射，不另造颜色。
export default {
  content: ['./index.html', './src/**/*.{vue,ts}'],
  theme: {
    extend: {
      colors: {
        canvas: 'var(--bg)',
        surface: {
          DEFAULT: 'var(--surface)',
          2: 'var(--surface-2)',
          3: 'var(--surface-3)'
        },
        line: {
          DEFAULT: 'var(--border)',
          strong: 'var(--border-strong)'
        },
        ink: {
          DEFAULT: 'var(--text)',
          2: 'var(--text-2)',
          3: 'var(--text-3)'
        },
        primary: {
          DEFAULT: 'var(--primary)',
          hover: 'var(--primary-hover)',
          soft: 'var(--primary-soft)',
          on: 'var(--on-primary)',
          strong: 'var(--primary-soft-text)'
        },
        accent: {
          DEFAULT: 'var(--accent)',
          soft: 'var(--accent-soft)'
        },
        brandink: 'var(--brand-ink)',
        sidebar: {
          DEFAULT: 'var(--sidebar-bg)',
          text: 'var(--sidebar-text)',
          muted: 'var(--sidebar-muted)',
          hover: 'var(--sidebar-hover)',
          active: 'var(--sidebar-active)',
          activeText: 'var(--sidebar-active-text)',
          title: 'var(--sidebar-title)',
          line: 'var(--sidebar-border)',
          chip: 'var(--sidebar-chip)'
        },
        ok: {
          DEFAULT: 'var(--success)',
          soft: 'var(--success-soft)',
          text: 'var(--success-text)'
        },
        warn: {
          DEFAULT: 'var(--warning)',
          soft: 'var(--warning-soft)',
          text: 'var(--warning-text)'
        },
        danger: {
          DEFAULT: 'var(--danger)',
          soft: 'var(--danger-soft)',
          text: 'var(--danger-text)'
        },
        info: {
          DEFAULT: 'var(--info)',
          soft: 'var(--info-soft)',
          text: 'var(--info-text)'
        }
      },
      borderRadius: {
        sm: 'var(--radius-sm)',
        DEFAULT: 'var(--radius)',
        lg: 'var(--radius-lg)',
        pill: 'var(--radius-pill)'
      },
      boxShadow: {
        sm: 'var(--shadow-sm)',
        DEFAULT: 'var(--shadow)',
        lg: 'var(--shadow-lg)',
        ring: 'var(--ring)'
      },
      fontFamily: {
        sans: 'var(--font-sans)',
        mono: 'var(--font-mono)'
      },
      width: { sidebar: 'var(--sidebar-w)' },
      height: { topbar: 'var(--topbar-h)' },
      maxWidth: { content: 'var(--content-max)' },
      transitionDuration: { fast: '150ms', DEFAULT: '220ms' }
    }
  },
  plugins: []
}
