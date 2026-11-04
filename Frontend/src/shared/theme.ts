import { theme, type ThemeConfig } from 'antd'
import { defaultTypography as DEFAULT_TYPOGRAPHY } from "../modules/settings/settingsModel";

export const lePalette = {
    primary: '#2F6B9A',
    success: '#42d392',
    warning: '#f5b942',
    error: '#ff667a',
    info: '#55c2ff',
    canvas: '#080c18',
    container: '#101729',
    elevated: '#151e33',
    border: '#27334f',
    text: '#eef2ff',
    secondaryText: '#99a7c2',
}
const palette = lePalette

/**
 * 按 LabelEditor 的主题和默认字体层级同步（2026-10-21）。
 * 颜色、圆角、阴影和 Ant Design 组件外观优先在此维护，不分散到普通 CSS。
 */
const baseTheme: ThemeConfig = {
    algorithm: theme.darkAlgorithm,
    cssVar: { key: 'syncsrv', prefix: 'ss' },
    hashed: true,
    token: {
        colorPrimary: palette.primary,
        colorSuccess: palette.success,
        colorWarning: palette.warning,
        colorError: palette.error,
        colorInfo: palette.info,
        colorBgBase: palette.canvas,
        colorBgLayout: palette.canvas,
        colorBgContainer: palette.container,
        colorBgElevated: palette.elevated,
        colorBgMask: 'rgba(2, 6, 18, 0.72)',
        colorBorder: palette.border,
        colorBorderSecondary: '#202b44',
        colorText: palette.text,
        colorTextSecondary: palette.secondaryText,
        colorTextTertiary: '#70809f',
        // One physical pixel on Retina displays; regular displays snap to a thin line.
        lineWidth: 0.5,
        borderRadius: 10,
        borderRadiusLG: 14,
        borderRadiusSM: 7,
        controlHeight: 36,
        fontSize: 14,
        fontFamily: 'Inter, PingFang SC, Microsoft YaHei, system-ui, sans-serif',
        boxShadowSecondary: '0 18px 48px rgba(0, 0, 0, 0.38)',
        motionDurationFast: '0.12s',
        motionDurationMid: '0.18s',
    },
    components: {
        Layout: {
            bodyBg: palette.canvas,
            headerBg: palette.container,
            siderBg: '#0c1221',
            triggerBg: '#151e33',
            triggerColor: palette.secondaryText,
        },
        Menu: {
            darkItemBg: '#0c1221',
            darkSubMenuItemBg: '#0c1221',
            darkItemColor: palette.secondaryText,
            darkItemHoverBg: '#17223a',
            darkItemSelectedBg: '#1E3A56',
            darkItemSelectedColor: '#ffffff',
            itemBorderRadius: 9,
            itemMarginInline: 10,
        },
        Card: {
            colorBgContainer: palette.container,
            colorBorderSecondary: palette.border,
            headerBg: 'transparent',
            paddingLG: 20,
        },
        Table: {
            fontSize: 13,
            cellFontSize: 13,
            cellFontSizeMD: 13,
            cellFontSizeSM: 13,
            fontWeightStrong: 500,
            lineHeight: 1.55,
            cellPaddingBlock: 10,
            cellPaddingInline: 16,
            cellPaddingBlockMD: 10,
            cellPaddingInlineMD: 14,
            cellPaddingBlockSM: 8,
            cellPaddingInlineSM: 12,
            headerBorderRadius: 10,
            headerBg: '#121b2c',
            headerColor: '#aab8cc',
            headerSplitColor: 'transparent',
            headerSortActiveBg: '#162136',
            headerSortHoverBg: '#19263b',
            bodySortBg: '#101a2b',
            rowHoverBg: '#152136',
            rowSelectedBg: '#182d43',
            rowSelectedHoverBg: '#1b334c',
            colorText: '#c7d2e4',
            borderColor: '#ffffff0b',
            footerBg: '#101729',
        },
        Modal: {
            contentBg: palette.container,
            headerBg: palette.container,
            footerBg: palette.container,
            titleColor: palette.text,
        },
        Drawer: {
            colorBgElevated: palette.container,
        },
        Button: {
            primaryShadow: '0 8px 20px rgba(47, 107, 154, 0.24)',
            defaultBg: '#151e33',
            defaultBorderColor: palette.border,
            defaultHoverBg: '#1a2540',
        },
        Input: {
            colorBgContainer: '#0d1424',
            activeBg: '#0d1424',
            hoverBg: '#0d1424',
        },
        InputNumber: {
            colorBgContainer: '#0d1424',
            activeBg: '#0d1424',
        },
        Select: {
            colorBgContainer: '#0d1424',
            optionSelectedBg: '#1E3A56',
            optionActiveBg: '#17223a',
        },
        Form: {
            labelColor: '#c9d3e8',
            labelRequiredMarkColor: palette.error,
        },
        Tabs: {
            itemColor: palette.secondaryText,
            itemSelectedColor: '#ffffff',
            itemHoverColor: '#ffffff',
            inkBarColor: palette.primary,
        },
        Descriptions: {
            labelBg: '#151e33',
            contentColor: palette.text,
        },
        Tag: {
            defaultBg: '#17223a',
            defaultColor: '#c9d3e8',
        },
        Tooltip: {
            colorBgSpotlight: '#242f49',
        },
        Popover: {
            colorBgElevated: palette.elevated,
        },
        Dropdown: {
            colorBgElevated: palette.elevated,
        },
        Alert: {
            withDescriptionPadding: '14px 16px',
        },
        Spin: {
            colorPrimary: palette.primary,
        },
        Progress: {
            defaultColor: palette.primary,
            remainingColor: '#202b44',
        },
        Segmented: {
            itemSelectedBg: '#1E3A56',
            trackBg: '#0d1424',
        },
    },
}

export function createSystemTheme(scale = DEFAULT_TYPOGRAPHY): ThemeConfig {
    const components = baseTheme.components!;
    return {
        ...baseTheme,
        token: { ...baseTheme.token, fontSize: scale.body, fontSizeSM: scale.compact, fontSizeLG: scale.section,
            fontSizeXL: scale.page, fontSizeHeading1: scale.display, fontSizeHeading2: scale.page,
            fontSizeHeading3: scale.page, fontSizeHeading4: scale.section, fontSizeHeading5: scale.body,
            controlHeight: Math.max(36, Math.ceil(scale.body * 1.6) + 12),
            controlHeightSM: Math.max(24, Math.ceil(scale.compact * 1.6) + 4),
            controlHeightLG: Math.max(40, Math.ceil(scale.section * 1.6) + 12) },
        components: { ...components,
            Table: { ...components.Table, fontSize: scale.list, cellFontSize: scale.list, cellFontSizeMD: scale.list, cellFontSizeSM: scale.list },
            Card: { ...components.Card, headerFontSize: scale.section, headerFontSizeSM: scale.body },
            Form: { ...components.Form, labelFontSize: scale.body },
            Button: { ...components.Button, contentFontSize: scale.body, contentFontSizeSM: scale.compact },
            Input: { ...components.Input, inputFontSize: scale.body, inputFontSizeSM: scale.compact },
            InputNumber: { ...components.InputNumber, inputFontSize: scale.body, inputFontSizeSM: scale.compact },
            Select: { ...components.Select, optionFontSize: scale.body },
            Modal: { ...components.Modal, titleFontSize: scale.section },
            Statistic: { titleFontSize: scale.body, contentFontSize: scale.display },
            Tag: { ...components.Tag, fontSize: scale.compact },
            Menu: { ...components.Menu, fontSize: scale.list },
        },
    }
}

export const darkTheme = createSystemTheme();
