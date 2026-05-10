# PRD：前端视觉去 JetLinks 化与交互零回归改造

## 1. 背景与目标

当前前端基于 Vue/Vite、Ant Design Vue 和 `@jetlinks-web/*` 组件体系开发，页面结构、默认主题、登录页、首页、菜单、图标和部分文案仍保留 JetLinks 原始产品特征。

本次目标是：只修改前端视觉风格，不改变原有交互方式和业务流程，使普通用户从页面观感上看不出这是 JetLinks 二次开发。

成功标准：

- 登录页、主框架、首页、高频业务页不再呈现 JetLinks 默认蓝白后台风格。
- 用户可见区域不出现 JetLinks 名称、原始 logo、原始 favicon、JetLinks 官方协议等明显来源信息。
- 所有原有交互保持一致，包括菜单跳转、表格操作、表单校验、弹窗、抽屉、分页、Tab、用户菜单、语言切换、通知入口。
- 不修改接口、权限、路由结构、菜单数据结构、业务逻辑、组件事件。

非目标：

- 不移除 `@jetlinks-web/*` 依赖。
- 不做源码级、包名级、构建产物级技术去识别。
- 不重写组件库。
- 不重新设计业务流程和交互逻辑。

## 2. 当前状态

已确认的前端结构：

- 前端目录：`front`
- 主入口：`front/src/main.ts`
- 根组件：`front/src/App.vue`
- 主题配置：`front/configs/theme/index.ts`
- 全局样式入口：`front/src/style/global.less`、`front/src/style/layout.less`、`front/src/style.css`
- 主布局：`front/src/layout/BasicLayoutPage.vue`
- 系统配置 Store：`front/src/store/system.ts`
- 登录页：`front/src/views/login/index.vue`、`front/src/views/login/right.vue`
- OAuth 页：`front/src/views/oauth/index.vue`
- 初始化页：`front/src/views/init-home`
- 高频模块：`front/src/modules/device-manager-ui`、`front/src/modules/rule-engine-manager-ui`、`front/src/modules/notify-manager-ui`、`front/src/modules/authentication-manager-ui`

风险事实：

- `front/src` 下约 710 个 Vue 文件，模块页面约 595 个。
- 大量页面有局部 `<style>`，不能只改一个主题文件就完成彻底换风格。
- `#1677FF`、`#1890ff`、`@primary-color` 等默认蓝色风格引用较多。
- 构建配置已关闭 sourcemap，普通用户主要从视觉和文案识别来源。

## 3. 需求范围与修改方案

### 3.1 品牌露出清理

修改位置：

- `front/public/favicon.ico`
- `front/public/logo.png`
- `front/public/images/login/logo.png`
- `front/public/images/login/login.png`
- `front/src/assets/bindPage/jetlinksLogo.png`
- `front/src/store/system.ts`
- `front/src/views/oauth/index.vue`
- `front/src/locales/lang/zh.json`
- `front/src/locales/lang/en.json`
- 各模块 locales 中用户可见的 JetLinks、物联网平台、官方协议描述。

怎么修改：

- 替换 favicon、系统 logo、登录页 logo、登录背景、绑定页 logo。
- 将默认系统名从 JetLinks 或物联网平台类描述改为新品牌名或中性平台名。
- 将 OAuth 默认标题中的 `Jetlinks` fallback 改为新品牌名。
- 将初始化页、SDK 示例页、官方协议说明中的 JetLinks 露出文案改为中性描述，例如“平台协议”“默认协议”“Java SDK”。
- 保留后台可配置的 `front.logo`、`front.ico`、`front.title` 逻辑，不破坏现有系统配置能力。

预期效果：

- 首次打开、登录、浏览器标签、OAuth、初始化、绑定账号等关键入口不再出现 JetLinks 品牌痕迹。
- 即使后台没有配置自定义品牌，也会显示新默认品牌。

测试与验证：

- 使用 `rg -n "JetLinks|Jetlinks|jetlinks|Jetlinks官方|JetLinks官方|官方协议"` 检查用户可见源码。
- 浏览器验证 favicon、document title、登录页 logo、登录背景、主框架 logo。
- 验证系统设置中上传 logo、favicon、背景后仍能覆盖默认值。
- 验收标准：用户可见页面不得出现 JetLinks 字样、原 JetLinks logo、原 favicon、原登录背景。

### 3.2 全局主题重设

修改位置：

- `front/configs/theme/index.ts`
- `front/src/style/variable.less`
- `front/src/style/global.less`
- `front/src/style/layout.less`
- `front/src/style.css`

怎么修改：

- 将 Ant Design token 从默认蓝色改为新主色体系。
- 调整 `colorPrimary`、`colorLink`、`borderRadius`、边框色、背景色、文本灰阶。
- 统一按钮、表格、输入框、Tab、分页、Modal、Drawer、Dropdown、Notification 的视觉。
- 将页面背景从默认后台灰白感改为新品牌的中性工作台风格。
- 保持组件尺寸和 DOM 结构稳定，避免影响点击区域和布局计算。
- 避免大范围滥用 `!important`，只对组件库无法通过 token 覆盖的位置做局部覆盖。

预期效果：

- 整体不再像 JetLinks 默认 Ant Design 蓝白风格。
- 页面依旧是后台管理系统，保持清晰、稳定、可扫描。
- hover、active、disabled、focus、error 状态清晰，不降低可用性。

测试与验证：

- 执行 `cd front && pnpm build`。
- 抽查按钮、输入框、Select、DatePicker、Table、Pagination、Tabs、Modal、Drawer、Dropdown、Tooltip、Notification。
- 验证 disabled 不可点击、focus 可见、error 颜色明显、hover 不改变布局尺寸。
- 验收标准：构建通过，无新增控制台错误，无文字溢出，无组件错位，无状态不可辨识。

### 3.3 主布局风格调整

修改位置：

- `front/src/layout/BasicLayoutPage.vue`
- `front/src/style/layout.less`
- `front/src/layout/components/*`

怎么修改：

- 覆盖 `j-pro-layout` 的顶部栏、侧边栏、菜单、面包屑、内容区背景。
- 调整菜单选中态、展开态、hover 态，使其区别于 JetLinks 默认风格。
- 调整顶部右侧用户、语言、通知、资源入口的间距和颜色，但不改组件行为。
- 保持 `layout.layout`、`splitMenus`、`menuData`、`selectedKeys`、`openKeys` 的逻辑不变。
- 保持侧边栏折叠宽度、顶部栏高度的功能逻辑不变；若视觉需要调整高度，必须逐页验证。

预期效果：

- 用户进入系统后的第一视觉明显换新。
- 菜单层级和导航方式完全不变。
- 顶部栏和侧边栏形成统一新品牌框架。

测试与验证：

- 验证菜单跳转、菜单展开或收起、选中态、高亮态。
- 验证侧边栏折叠、混合布局、顶部布局、面包屑返回。
- 验证用户菜单、退出登录、语言切换、通知入口。
- 验收标准：所有导航行为与修改前一致；菜单不遮挡内容；窄屏不出现顶部操作重叠。

### 3.4 登录页改造

修改位置：

- `front/src/views/login/index.vue`
- `front/src/views/login/right.vue`
- `front/public/images/login/*`

怎么修改：

- 替换登录背景图、logo、右侧登录面板视觉。
- 调整登录页色彩、卡片、输入框、按钮、第三方登录入口样式。
- 不改变登录表单字段、验证码逻辑、第三方登录配置、加密逻辑、token 写入逻辑。
- 保持 `systemInfo.front.background`、`systemInfo.front.logo`、`layout.title` 的可配置优先级。

预期效果：

- 登录页第一眼不再像 JetLinks 原登录页。
- 新视觉与主系统框架一致。
- 登录操作路径完全不变。

测试与验证：

- 验证账号密码登录。
- 验证验证码展示与刷新。
- 验证登录失败提示。
- 验证第三方登录入口展示。
- 验证后台配置 logo/background 后可覆盖默认图。
- 验收标准：登录成功跳转正常；失败提示正常；输入框、按钮、验证码、第三方入口均可点击且无错位。

### 3.5 首页与初始化页改造

修改位置：

- `front/src/views/init-home`
- `front/src/modules/device-manager-ui/views/home`
- `front/public/images/init-home`
- `front/src/modules/device-manager-ui/assets/home`

怎么修改：

- 替换首页背景、引导图、卡片样式、统计卡片、入口卡片视觉。
- 初始化页替换默认 logo、背景、上传区域、步骤卡片和提示文案样式。
- 不改变首页接口调用、模块切换、引导跳转、初始化保存逻辑。
- 不改变初始化数据流程，只替换可见文案和视觉资产。

预期效果：

- 首页和初始化页不再保留 JetLinks 原始引导风格。
- 入口卡片、统计卡片、引导图与新主题一致。
- 新用户首次进入时感知为独立产品。

测试与验证：

- 验证首页三个视图或模块入口切换。
- 验证首页统计卡片、快捷入口点击。
- 验证初始化页基础信息配置、logo 上传、favicon 上传、背景上传。
- 验证初始化保存、下一步、返回等操作。
- 验收标准：入口点击路径不变；上传与保存功能正常；页面无图像拉伸、文字遮挡、布局跳动。

### 3.6 高频业务页面视觉统一

修改位置：

- `front/src/modules/device-manager-ui`
- `front/src/modules/rule-engine-manager-ui`
- `front/src/modules/notify-manager-ui`
- `front/src/modules/authentication-manager-ui`
- `front/src/components`

怎么修改：

- 优先覆盖高频页面：设备列表、设备详情、产品、接入配置、规则场景、告警、通知模板、通知配置、用户、角色、部门、系统应用。
- 对列表页统一搜索区域、表格、操作列、分页、批量操作视觉。
- 对详情页统一标题区、Tab、状态标签、信息卡片视觉。
- 对配置页统一步骤、表单、选择卡片、空状态、提示区视觉。
- 局部样式只改视觉 class，不改模板结构、事件绑定、字段、接口调用。

预期效果：

- 高频业务页面与全局主题一致。
- 页面不再出现大量 JetLinks 默认蓝色卡片、渐变、引导图风格。
- 用户常用路径看起来像完整换肤后的独立产品。

测试与验证：

- 设备模块：查询、重置、分页、详情 Tab、编辑弹窗、删除确认、导入入口。
- 产品模块：新建、编辑、详情、物模型相关入口。
- 规则模块：场景列表、规则配置、触发方式、动作配置、保存校验。
- 通知模块：模板列表、模板详情、配置详情、调试弹窗。
- 系统模块：用户、角色、部门、应用管理的查询、编辑、权限选择。
- 验收标准：所有抽查页面原操作可完成；无弹窗遮挡；无表格操作列不可点；无表单字段被压缩到不可读。

### 3.7 图片、插画、图标与空状态替换

修改位置：

- `front/public/images`
- `front/public/icons`
- `front/src/assets`
- 各模块 `assets` 目录。

怎么修改：

- 替换明显带 JetLinks 原风格的登录图、首页图、引导图、空状态图、默认设备图、默认产品图。
- 保留业务含义明确的协议图标、通知渠道图标、设备类型图标，除非其风格与新主题明显冲突。
- 图片尺寸尽量保持原宽高或同等比例，避免影响布局。
- 新图标统一描边、填充、圆角和色彩风格。

预期效果：

- 关键视觉资产不再像 JetLinks 原产品。
- 业务图标仍可识别，不影响用户判断。
- 页面视觉更统一。

测试与验证：

- 检查登录、首页、设备、产品、规则、通知、系统管理中实际展示的图片。
- 验证图片加载成功，无 404。
- 验证图片替换后不导致布局高度变化过大。
- 验收标准：用户可见关键图片全部替换或统一；无破图；无布局挤压。

### 3.8 样式风险控制

修改位置：

- 所有新增或修改的 less/css/vue style。

怎么修改：

- 优先通过 Ant Design token 和局部 class 改样式。
- 避免修改组件事件、模板结构、数据结构。
- 避免全局选择器误伤，例如直接覆盖所有 `div`、所有 `span`、所有 `.ant-*` 深层结构。
- 必须覆盖组件库样式时，限制在主布局或特定页面容器下。
- 控制 `z-index`、`overflow`、`position` 修改，特别是 Modal、Drawer、Dropdown、Tooltip、Select 下拉层。

预期效果：

- 风格变化明显，但交互稳定。
- 局部页面样式不污染其他模块。
- 弹层、下拉、抽屉、表格滚动保持正常。

测试与验证：

- 搜索新增 `!important`、`position: fixed`、`z-index`、`overflow: hidden`。
- 验证 Modal、Drawer、Dropdown、Select、Tooltip、Popover。
- 验证表格横向滚动、固定列、操作列。
- 验收标准：不出现下拉被遮挡、弹窗层级异常、抽屉无法滚动、页面无法点击等问题。

## 4. 整体验收测试

### 4.1 构建验收

必须执行：

```bash
cd front
pnpm build
```

验收标准：

- 构建成功。
- 无新增 TypeScript、Vue、Less 编译错误。
- 无新增资源引用错误。
- 构建后页面可正常访问。

### 4.2 品牌验收

必须执行关键词检查：

```bash
rg -n "JetLinks|Jetlinks|jetlinks|JetLinks官方|Jetlinks官方|官方协议" front/src front/public
```

验收标准：

- 用户可见页面文案不得出现 JetLinks 品牌。
- 允许保留 import 包名、依赖名、内部技术引用。
- 默认 logo、favicon、登录背景、绑定页 logo 必须替换。

### 4.3 视觉验收页面

必须截图对比以下页面：

- 登录页
- 重新登录弹窗
- OAuth 登录页
- 初始化页
- 首页
- 设备列表
- 设备详情
- 产品详情
- 规则场景配置
- 告警配置
- 通知模板
- 通知配置
- 用户管理
- 角色权限
- 应用管理

验收标准：

- 登录页、首页、主布局、高频页面风格统一。
- 不再呈现 JetLinks 默认蓝白后台观感。
- 页面无明显错位、遮挡、溢出、破图。
- 字体大小、按钮尺寸、表格密度适合后台系统使用。

### 4.4 交互回归验收

必须覆盖：

- 登录成功、登录失败、退出登录。
- 菜单跳转、菜单展开、侧边栏折叠、面包屑跳转。
- 列表查询、重置、分页、表格操作。
- 新增、编辑、删除确认。
- 表单必填校验、错误提示、取消、保存。
- Modal、Drawer、Dropdown、Select、Tooltip、Popover。
- Tab 切换、详情页返回。
- 通知入口、用户菜单、语言切换。
- 初始化页上传与保存。

验收标准：

- 所有原有交互路径可完成。
- 无新增控制台 error。
- 无按钮不可点击。
- 无弹窗、下拉、抽屉被遮挡。
- 无表格操作列因样式变化不可用。
- 无表单字段因宽度变化无法输入或无法识别。

### 4.5 响应式验收

至少验证以下 viewport：

- 1366x768
- 1920x1080
- 390x844

验收标准：

- 顶部栏操作不重叠。
- 侧边栏和内容区不互相遮挡。
- 表格和表单在窄屏下仍可滚动或正常折行。
- 弹窗在窄屏下可操作关闭和保存。
- 登录页在窄屏下不出现核心按钮不可见。

## 5. 任务排期与工作量

推荐按三阶段执行。

第一阶段：低风险换皮，预计 5-8 人日。

- 品牌露出清理。
- 全局主题 token。
- 主布局样式。
- 登录页和首页重点改造。
- 基础构建与核心路径验收。

第二阶段：高频页面统一，预计 4-6 人日。

- 设备、规则、通知、系统管理页面视觉统一。
- 替换关键图片、图标、空状态。
- 高频交互回归。

第三阶段：收敛与验收，预计 1-2 人日。

- 全量品牌关键词检查。
- 多分辨率截图验收。
- 弹层、表格、表单专项回归。
- 修复样式污染和边界问题。

总预估：10-15 人日。

## 6. 最终验收标准

项目完成后必须同时满足：

- 视觉验收：普通用户从登录页、首页、主框架、高频业务页看不出 JetLinks 二次开发来源。
- 品牌验收：用户可见区域无 JetLinks 品牌、原 logo、原 favicon、原登录背景、原官方协议标识。
- 交互验收：原有登录、菜单、列表、详情、表单、弹窗、抽屉、下拉、分页、Tab、通知、用户菜单交互全部保持。
- 构建验收：`pnpm build` 通过，无新增运行时报错。
- 风险验收：无文字遮挡、按钮不可点、弹层被遮挡、表格操作列错位、页面滚动异常。

## 7. 默认假设

- 新品牌名称、logo、favicon、主色、登录背景如暂未提供，先使用中性占位品牌资源，但保留替换入口。
- 本次只面向普通用户视觉识别，不处理源码依赖中的 JetLinks 包名。
- 低频深层页面以全局主题覆盖为主，不逐页重设计。
- 所有改动必须可回滚，且每个阶段完成后独立可构建、可验收。
