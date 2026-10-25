/**
 * 图标：自绘的一组线性图标，1.5px 描边，16px 网格。
 *
 * 为什么不用图标库：Element Plus 的图标是另一套视觉（更圆、更满），
 * 跟这套 1px 边、无阴影的界面不合。自绘十个够用，也省一个依赖。
 */
export type IconName =
  | 'market'
  | 'box'
  | 'users'
  | 'review'
  | 'audit'
  | 'menu'
  | 'close'
  | 'check'
  | 'file'
  | 'plus'
  | 'upload'
  | 'chevron-right'
  | 'chevron-down'

/** 每条 path 的 d，渲染成 <path>。 */
export const PATHS: Record<IconName, string[]> = {
  market: ['M2.5 4.5h4v4h-4zM9.5 4.5h4v4h-4zM2.5 11.5h4v4h-4zM9.5 11.5h4v4h-4z'],
  box: ['M8 1.8l6 2.7v6.9l-6 2.8-6-2.8V4.5zM2 4.5l6 2.7 6-2.7M8 7.2v7'],
  users: [
    'M6 7.5a2.25 2.25 0 100-4.5 2.25 2.25 0 000 4.5zM2 13.5c0-2.2 1.8-3.5 4-3.5s4 1.3 4 3.5',
    'M11 3.4a2.25 2.25 0 010 4.4M11.6 10.2c1.5.35 2.4 1.5 2.4 3.3',
  ],
  review: [
    'M8 2.2h4.2L14.5 4.5v9.3h-13V2.2z',
    'M8 2.2v2.3h4.2M5.5 8.5l1.8 1.8 3.4-3.6',
  ],
  audit: [
    'M3 2.6h10v10.8H3z',
    'M5.2 5.2h5.6M5.2 8h5.6M5.2 10.8h3.2',
  ],
  menu: ['M2.5 4.5h11M2.5 8h11M2.5 11.5h11'],
  close: ['M3.5 3.5l9 9M12.5 3.5l-9 9'],
  check: ['M3 8.5l3.2 3.2L13 4.8'],
  file: ['M9 1.8H4.2v12.4h7.6V4.5zM9 1.8v2.7h2.8'],
  plus: ['M8 3.5v9M3.5 8h9'],
  upload: ['M8 11V3.2M4.8 6.4L8 3.2l3.2 3.2M3 12.2h10'],
  'chevron-right': ['M6.5 3.5L11 8l-4.5 4.5'],
  'chevron-down': ['M3.5 6.5L8 11l4.5-4.5'],
}
