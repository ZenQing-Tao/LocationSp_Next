## 功能
- 新增 OSM（MapLibre）矢量底图，默认使用 OpenFreeMap，**无需任何 Key 即可出图**；卫星图使用 Esri World Imagery
- 新增「自定义瓦片地址」设置项，支持栅格模板（含 {z}/{x}/{y}）或 .json 样式地址，留空则用内置 OpenFreeMap
- 新增 Nominatim 地点搜索，无需 Key（已固定 User-Agent 并限流 1 次/秒）
- 全新应用图标（自适应图标 + 深色主题单色图标）

## 修复
- 修正 MapLibre 适配器：`MapView` 导入、`Style.Builder`、`addSource/addLayer` 单参数、`Legend`/图层属性改用 `PropertyFactory`、`setStyle` 异步重建标注
- 修正 GPS 缩放参数类型（Float→Double）与 Marker anchor 兼容问题
- 补充 R8 keep 规则（MapLibre / OkHttp / Koin），修复 release 包混淆后崩溃

## 变更
- **移除构建产物中内置的第三方地图 Key**（高德/百度）与百度个性化样式 ID，改为 `local.yml` / 环境变量 / App 内设置注入
- 高德 / 百度 / Google 引擎调整为可选，需使用者自备 Key（Google 按次计费，请自行控制额度）
- 应用名称改为 **LocationSp_Next**，版本号 3.1.1（versionCode 31001）
- 移除 App 内指向上游 Telegram 群的入口
- 正式版改用固定发布签名（此前为每次随机的 debug 签名）

## 文档
- README 重写：非官方分支声明、图标与标语、数据与隐私说明、免责声明、中英文互跳
- 新增 `NOTICE`（GPLv3 §5(a) 修改记录）、`THIRD_PARTY_LICENSES.md`
- LICENSE 补全 GPLv3 全文
- CI：发布产物命名统一为 `LocationSp_Next-*`，Release 说明自动附带源码链接，构建日志留存与失败诊断

## 安装包说明
- `LocationSp_Next-scoped-*`：非全局方案，在 LSPosed 作用域中勾选需要模拟的应用
- `LocationSp_Next-global-*`：全局方案，作用域勾选系统框架、电话、蓝牙，对全设备生效
- 两个方案二选一安装；绝大多数设备选择 `arm64-v8a`
- **本项目此前为 debug 签名，首次安装需先卸载旧版**；从本版起签名固定，后续可直接覆盖升级

## 源码与许可
- 本版对应完整源码：https://github.com/ZenQing-Tao/LocationSp_Next/tree/v3.1.1
- 本项目为 LocationSpoofer 的非官方延续分支，依 **GNU GPLv3** 分发；原作品版权 © 2026 SuseOAA，修改部分 © 2026 ZenQing-Tao
- 仅供学习研究与自有设备/授权环境测试，**严禁用于违法违规用途**
