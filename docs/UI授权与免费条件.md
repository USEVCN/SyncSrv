# UI 授权与免费条件

核实日期：2026-11-04。以 SyncSrv 当前锁定版本、安装包内许可证、上游许可证及作者公开答复交叉核实。

| 内容 | 当前版本/来源 | 是否免费商用 | 必须遵守的条件 |
| --- | --- | --- | --- |
| React、React DOM | 19.2.7，MIT | 是 | 在源码与分发物中保留版权、完整许可和免责声明；不要求项目采用 MIT，不收运行或按席位许可费。 |
| Ant Design | 6.6.4，MIT | 是 | 保留其版权及 MIT 全文；不把其商标和独立付费服务包含在授权承诺中。 |
| Ant Design Icons | 6.3.2；icons-svg 4.6.0，MIT | 是 | 保留图标包版权及 MIT 全文，不删除上游署名。 |
| React Router DOM | 7.18.4，MIT | 是 | 保留其版权及 MIT 全文。 |
| npm 运行时依赖合计（含以上各项） | 75 个安装实例、74 个唯一名称/版本，全部声明 MIT，完整文本已收集 | 是 | 分发时保留每项实际版权和许可文本；升级或新增依赖后重新审查。 |
| React Bits Aurora / Border Glow | 免费版改编，MIT + Commons Clause | 是，用于应用/网站 | 保留 David Haz 版权和原许可；作为 SyncSrv 应用 UI 分发；不能改标为 Apache-2.0，也不能抽取、改包为独立组件库、组件合集或竞争性模板产品。 |
| React Bits Pro | 当前源码未发现此来源；本地品牌粒子渲染器标明为独立实现 | 不纳入当前免费授权 | 不得把免费版许可套用到 Pro；没有单独权利证明时不得引入 Pro 代码。 |
| 自有布局、主题及本地渲染代码 | SyncSrv 自有代码 | 随项目 Apache-2.0 提供 | 保留 Apache-2.0/NOTICE；品牌商标权不因代码许可而自动授予。 |

当前没有发现需要购买才能使用的 UI 依赖，因此无需为费用删除已有组件或购买 Pro。React Bits 不是 React 本体，也不是普通无附加限制的 MIT 库；其应用内使用与组件产品再分发需要分别判断。

## React Bits 的公开源码问题

作者在 #1111 对“Apache-2.0 项目、改编组件单独保留原许可、组件源码放在公开仓库”的问题给出肯定答复，条件是组件用于网站而非独立组件库。#1101 也确认允许商业网站、Web 应用和 Vite 生产打包。

因此采用的方案是：保留现有免费应用特效，在 `THIRD_PARTY_NOTICES.md` 明确列出 3 个例外文件，在文件中标记原许可，并随源代码及网页分发完整许可。项目根目录的 Apache-2.0 不覆盖这些文件。当前条款未要求购买、付款或强制在应用画面显示水印。

MIT + Commons Clause 不是标准 MIT，也不能将其作为无用途限制的开源许可。SyncSrv 当前是“自有代码 Apache-2.0，列明第三方例外”，不能宣称仓库所有文件均为 Apache-2.0。若以后要求每个文件都采用宽松开源许可，应将这 3 个特效文件替换为独立编写的 CSS/WebGL 效果，或经核实的 MIT 替代实现。

随现有改编代码保留的许可原文与上游提交 `ecdd797468fc87f7b463157627d1174b0c36fdf5` 完全一致；本次同时检查了当前上游提交 `d965399df489c7b8da3874e07d716c0d98ab39fd`。原文哈希和固定提交链接已记录，未用上游后续变动的条款悄悄覆盖原许可。

## 已补齐的分发条件

- `LICENSE`：SyncSrv 自有代码的 Apache-2.0。
- `NOTICE`、`THIRD_PARTY_NOTICES.md`：项目和第三方授权边界。
- `licenses/ui-inventory.json`：每个运行时依赖的版本、许可、免费条件、证据与许可文本哈希。
- `licenses/ui-reviewed.json`：本次审查通过的名称、版本、原文哈希和 React Bits 固定提交证据；不由普通构建自动更新。
- `Frontend/public/licenses/ui-third-party.txt`：完整 UI 许可合集，构建后可通过 `/licenses/ui-third-party.txt` 访问。
- `Frontend/public/licenses/react-bits.txt`：原 React Bits 许可，保留不改。
- `licenses/upstream/`：补全 npm 包遗漏的 Ant Design SVG 图标许可，使用该包 registry gitHead 指向的上游原文；is-mobile 的完整 MIT 条款取自其随包 README。
- `npm run check:licenses`：核对已收集文本；`npm run build` 自动重新生成。新增名称/版本、许可原文变化、未知许可、缺失原文或安装版本与锁文件不符会拒绝构建，等待审查；即使新包声称 MIT，也不会自动加入已审查名单。
- 后端 JAR 的 `META-INF` 许可目录、Linux 中心安装包的根目录及 `licenses/` 同步保留相关完整文本；打包时拒绝遗漏或过期的 JAR UI 许可。

开发工具（如 Vite、Vitest）的依赖不计入上述 74 项运行时 UI 结论；后端、Agent、JRE 也属于各自的许可审查范围，不能把这份 UI 结果当成整个发行包的授权结论。

## 不免费或无法证实授权时的替代规则

不自动购买、不使用盗版或来源不明源码，不用删除署名来“解决”许可。新增 Pro/付费组件若无覆盖开源分发的授权，优先使用已经核实免费的 Ant Design、其图标库、React Bits 免费应用组件或独立实现。新增免费组件同样必须先补齐来源、许可与分发条件；不是仅检查价格为零。

## 一手证据

- React 19.2.7：https://github.com/react/react/blob/v19.2.7/LICENSE
- Ant Design：https://github.com/ant-design/ant-design/blob/master/LICENSE
- Ant Design Icons：https://github.com/ant-design/ant-design-icons/blob/master/LICENSE
- React Router：https://github.com/remix-run/react-router/blob/react-router%407.18.4/LICENSE.md
- React Bits：https://github.com/DavidHDev/react-bits/blob/main/LICENSE.md
- 公开仓库答复：https://github.com/DavidHDev/react-bits/discussions/1111
- 商业打包答复：https://github.com/DavidHDev/react-bits/discussions/1101

价格和许可证版本需随依赖更新重新核实；以上结论限定于当前已检查的代码和版本，不替代新增来源的授权证明。
