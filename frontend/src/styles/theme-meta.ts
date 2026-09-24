// 主题元数据 —— 由 frontend/scripts/extract-preview.mjs 从 docs/02-design/UI-PREVIEW.html 的
// `var THEMES` 数组生成（含中文名 / 气质标签 / 五色色卡 / 设计说明）。
// 请勿手改：要改主题名或色卡请改预览稿，再跑 pnpm run sync-preview。

/** 一套主题的元数据。 */
export interface ThemeMeta {
  /** 主题 id（与 index.html 的 data-theme、localStorage 里存的值一致）。 */
  id: string
  /** 中文名（三字，顶栏按钮与色卡上都显示它）。 */
  name: string
  /** 一句话气质标签（色卡副标题）。 */
  tag: string
  /** 五色色卡：侧栏 / 主色 / 点缀 / 页面底 / 卡片底。 */
  sw: string[]
  /** 设计说明（长句）。 */
  desc: string
}

/** 六套主题（数组顺序即选择器里的排列顺序）。 */
export const THEME_META: ThemeMeta[] = [
  { id: 'hebtu', name: '师大蓝', tag: '默认 · 亮堂', sw: ['#303064', '#1E56D6', '#C79A2E', '#EDF3FB', '#FFFFFF'], desc: '校徽藏青 #303064 作品牌色，交互主色取亮蓝；浅色侧栏 + 蓝调页面底，白天办公最耐看。' },
  { id: 'gingko', name: '银杏暖', tag: '暖色 · 亮调', sw: ['#FFE9B8', '#C2740E', '#E0A32E', '#FAF3E7', '#FFFDF9'], desc: '取校园银杏的暖黄，米白纸感底 + 琥珀主色；暖而不糊，长文阅读不刺眼。' },
  { id: 'celadon', name: '青瓷绿', tag: '青绿 · 清爽', sw: ['#0E8074', '#0E8074', '#B98A2E', '#EDF7F4', '#FFFFFF'], desc: '雨过天青的亮青绿，冷调里最清爽的一套；适合科研与制度类内容长时间浏览。' },
  { id: 'ink', name: '墨玉青', tag: '青色 · 沉稳', sw: ['#11201C', '#1F6F63', '#B58A2B', '#EEF4F2', '#FFFFFF'], desc: '深墨侧栏 + 青玉主色 + 鎏金点缀；沉稳但底色已调亮，不再是纯白刺眼。' },
  { id: 'jiang', name: '师大绛', tag: '校园红 · 庄重', sw: ['#2A1215', '#9E2B25', '#A9772A', '#FAF6F3', '#FFFFFF'], desc: '绛红主色取意校园红墙与印章；适合党政公文、通知公告等场景。' },
  { id: 'night', name: '墨夜黑', tag: '深色 · 夜间', sw: ['#0A1013', '#4FA8A0', '#D3AE6A', '#0E1417', '#161D21'], desc: '深色主题，夜间值班或投影演示；状态色按深色单独调校。' },
]
