export const typographyLevels = [
  {key:"caption",label:"辅助说明",usage:"提示、注释、次要信息",value:11,min:10,max:18},
  {key:"compact",label:"紧凑面板",usage:"紧凑按钮、列设置",value:12,min:11,max:20},
  {key:"list",label:"列表与导航",usage:"表格内容、表头、侧边导航",value:13,min:12,max:22},
  {key:"body",label:"正文与表单",usage:"正文、表单、常规按钮",value:14,min:12,max:24},
  {key:"section",label:"分组标题",usage:"卡片、弹窗、侧栏标题",value:16,min:14,max:28},
  {key:"page",label:"页面标题",usage:"业务页面主标题",value:22,min:18,max:36},
  {key:"display",label:"重点数值",usage:"控制台指标、大号展示",value:28,min:22,max:48},
] as const;
export type TypographyScale = Record<typeof typographyLevels[number]["key"],number>;
export type UiSettings = {systemName:string;description:string;typography:TypographyScale};
export const defaultTypography = Object.fromEntries(typographyLevels.map(level=>[level.key,level.value])) as TypographyScale;
export const defaultUiSettings:UiSettings = {systemName:"SyncSrv",description:"备份、归档与恢复",typography:defaultTypography};
export function validTypography(scale:TypographyScale) {
  return typographyLevels.every((level,i)=>Number.isInteger(scale[level.key])&&scale[level.key]>=level.min&&scale[level.key]<=level.max
    &&(!i||scale[level.key]>=scale[typographyLevels[i-1].key]));
}
