/// <reference types="vite/client" />

/**
 * 环境变量类型（只登记用到的，避免 any 与隐式 string）。
 * 见 .env.development / .env.production。
 */
interface ImportMetaEnv {
  /** 接口基址；默认 '/api'（dev 走 Vite 代理到 10087）。 */
  readonly VITE_API_BASE?: string
  /** 应用标题（index.html 与登录页用）。 */
  readonly VITE_APP_TITLE?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
