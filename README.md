# LocationSp_Next

> **非官方延续分支（unofficial fork）**
> 本项目源自 **LocationSpoofer**，上游仓库由 GitHub 用户 [@HuangZhuoRui](https://github.com/HuangZhuoRui) 维护；
> 上游 `LICENSE` 中的版权署名为 **SuseOAA**（本仓库原样保留该署名，未对其身份作任何认定）。
> 上游仓库 <https://github.com/HuangZhuoRui/LocationSpoofer> 已于 **2026-10-18 归档**，源码已从该仓库移除，仅保留说明页。
> 本仓库自 **2026-10-22** 起由 **ZenQing-Tao** 接手维护，代码基于上游最后一个提交 `dcbe620`。
> **本项目与上游作者/维护者无隶属关系，不代表其发布，也未获得其背书。**

基于 KernelSU / APatch / Magisk + LSPosed（libxposed API 102）的 Android 系统级虚拟定位与无线环境模拟框架。

> ⚠️ **使用前必读**：本项目仅供**学习、研究、交流**，以及在**自有设备、自有应用或已获得明确书面授权的环境**中做定位测试。**严禁用于任何违法用途**，详见文末「免责声明与使用限制」。

<p align="center">
  <img src="docs/images/app_icon.png" width="128" alt="LocationSp_Next 应用图标">
</p>
<p align="center"><sub>图标灵感来源于「缩地青符」</sub></p>
<p align="center"><strong>坐地不动，日行千里</strong></p>


---

## 来源与许可

| 项目 | 说明 |
| --- | --- |
| 原始项目 | LocationSpoofer |
| 上游仓库维护者 | GitHub [@HuangZhuoRui](https://github.com/HuangZhuoRui) |
| 版权署名（上游 LICENSE） | SuseOAA |
| 上游仓库 | <https://github.com/HuangZhuoRui/LocationSpoofer>（2026-10-18 归档） |
| 上游最后提交 | `dcbe620` |
| 许可证 | GNU General Public License v3.0（见 [`LICENSE`](./LICENSE)） |
| 原始版权 | Copyright © 2026 SuseOAA |
| 本分支维护者 | ZenQing-Tao（自 2026-10-22） |
| 修改记录 | 见 [`NOTICE`](./NOTICE)（依 GPLv3 §5(a) 标注） |

---

## 与上游的主要差异

| 项目 | 上游（归档前） | 本分支 |
| --- | --- | --- |
| 地图 Key | 构建产物内置第三方 Key | **不内置任何 Key**，由 `local.yml` / 环境变量 / App 内设置注入 |
| 默认底图 | 高德 | **OSM 矢量瓦片（MapLibre + OpenFreeMap）**，无需 Key、无调用配额 |
| 默认搜索 | 高德 POI | **Nominatim**，无需 Key |
| 高德 / 百度 / Google | 内置即可用 | 保留为**可选引擎**，需使用者自备 Key |
| 应用名 | LocationSpoofer | **LocationSp_Next** |

---

## 功能

- **空间定位与物理仿真**：多引擎切换（OSM / 高德 / 百度 / Google）；WGS-84 / GCJ-02 / BD-09 自适应；Ornstein-Uhlenbeck 位置抖动、步态横向摇摆、路网拟合与红绿灯停候、悬浮窗阻尼摇杆。
- **无线电与传感器模拟**：Wi-Fi 扫描与连接态、2G–5G NR 小区、BLE 信标、GNSS 卫星矩阵与 NMEA 语句、计步与步频联动。
- **路线模拟**：多点路径规划、速度无级调节、实际路网拟合。
- **坐标系适配**：配置层统一以 GCJ-02 存储，按引擎转换为对应坐标系，可在设置中为目标应用强制指定。

> 本项目不承诺、也不提供任何「绕过第三方风控 / 反检测 / 通过某平台校验」的能力保证。相关代码仅为研究 Android 系统接口与定位链路而存在。

---

## 环境要求

- Android 8.0+（minSdk 26），建议 Android 12 及以上；
- Root：KernelSU / APatch / Magisk 任一；
- LSPosed（libxposed API 102+）；
- 首次使用需在 LSPosed 中启用本模块并勾选作用域。

---

## 安装

从 [Releases](../../releases) 下载对应变体：

| 变体 | 说明 |
| --- | --- |
| `LocationSp_Next-scoped-<abi>.apk` | 仅在 LSPosed 作用域中勾选的 App 内生效 |
| `LocationSp_Next-global-<abi>.apk` | Hook 系统服务，对全设备生效 |

安装后在 LSPosed 中启用模块，重启目标应用（部分系统需重启设备）。

> 开发构建（debug 包）签名每次可能不同，**无法覆盖安装**，升级前请先卸载旧版本。卸载会清除本地数据（已保存地点、路线、root 方案配置等）。

---

## 地图与第三方服务配置

本仓库**不内置任何第三方地图 Key**。Key 通过项目根目录的 `local.yml`（已被 `.gitignore` 忽略）或同名环境变量注入，模板见 [`local.yml.example`](./local.yml.example)。

| 变量 | 用途 | 说明 |
| --- | --- | --- |
| `AMAP_API_KEY` | 高德 Android SDK（地图 / 定位 / 搜索） | 平台选 **Android**，填写你自己的包名与签名 SHA1。SDK 免费，但**搜索类接口有每日配额**，多人共用同一个 Key 极易被打满 |
| `BAIDU_MAP_API_KEY` | 百度地图 Android SDK | 创建应用选 **Android SDK**，填写包名与签名 SHA1 |
| `GOOGLE_MAPS_API_KEY` | Google Maps / Places | **按次计费**，务必配置包名 + SHA1 + API 白名单，并在自己账号下控制用量 |

- 不配置任何 Key 也能正常使用：默认走 OSM 底图 + Nominatim 搜索；
- 也可在 App 内「设置 → 地图配置」临时填入自己的 Key；
- **请勿使用他人的 Key**：Key 绑定包名与签名，转借既无法生效，也违反各平台开发者协议。

### 地图署名

- 底图数据 © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors，矢量瓦片与样式由 [OpenFreeMap](https://openfreemap.org/) 提供（免 Key、无配额）；
- 卫星影像 © [Esri](https://www.esri.com/) World Imagery；
- 使用高德 / 百度 / Google 引擎时，请遵守对应平台的署名与使用条款。

上述署名不得移除或隐藏。

---

## 构建

环境：JDK 17、Android SDK（compileSdk 37）。

```bash
# 可选：配置自己的地图 Key
cp local.yml.example local.yml

./gradlew :app:assembleScopedDebug    # 非全局方案
./gradlew :app:assembleGlobalDebug    # 全局方案
```

CI：`.github/workflows/build-debug-apk.yml`（手动触发，产出可直接安装的 debug 包）；`release.yml`（需配置 `KEYSTORE_*` 密钥做正式签名）。

---

## 常见问题

**地图一片空白？**
默认底图走 OpenFreeMap 矢量瓦片；若长期空白，请确认网络能访问 `tiles.openfreemap.org`，或到设置里切换到高德引擎（需你自己的 Key，且已绑定当前包名与签名）。

**搜索提示「额度耗尽」？**
高德搜索接口按 Key 计配额。请使用自己申请的 Key，避免与他人共用。

**升级时提示签名冲突 / 无法安装？**
不同构建（尤其 debug 包）签名不同，需先卸载再安装。

**模拟定位不生效？**
确认 LSPosed 已启用模块、作用域已勾选目标 App，并重启目标 App；root 方案可在设置中手动指定。

---

## 数据与隐私

- App 在运行时会**在本机**读取环境信息：Wi-Fi 扫描结果（SSID / BSSID / RSSI）、蜂窝小区（含 5G NR）、BLE 信标、传感器与位置数据，用于让模拟的环境指纹保持自洽。
- 这些数据**仅保存在本机**数据库（`/data/data/com.vincenthzr.locationspoofer/databases/`），**不会上传到任何服务器**；本项目没有任何自建后端。
- 只有在使用对应功能时才会发起网络请求：
  - 地图瓦片：OpenFreeMap（默认）/ Esri（卫星）/ 你自行配置的瓦片源；
  - 地点搜索：Nominatim（固定 User-Agent，限流 1 次/秒）；
  - 云端环境数据：WiGLE / OpenCellID（**仅当你自行填入 Token**）；
  - 检查更新：GitHub API。
- 卸载 App 即删除全部本地数据；也可在「导入与导出数据」中自行导出或清除。
- 本项目不提供“环境数据上传 / 众包共享”功能。若你在此基础上二次开发此类功能，属于收集他人信息，需自行遵守《个人信息保护法》等适用法规。

## 免责声明与使用限制

**请在使用前完整阅读本节。使用本项目即表示你已知悉并接受以下全部内容。**

1. **仅限合法用途。** 本项目仅供**学习、研究、交流**，以及在**自有设备、自有应用或已获得明确书面授权的环境**中进行定位与坐标转换测试、路线回放、应用兼容性验证、无线环境与传感器接口测试、Android 系统接口研究。

2. **严禁用于违法或侵害他人权益的用途**，包括但不限于：
   - 虚假考勤、代打卡、伪造运动或出行记录以骗取考核、奖励、补贴或其他利益；
   - 伪造位置以实施欺诈、诈骗、虚假诉讼或规避监管；
   - 未经授权干预、干扰他人设备、应用或系统，或规避第三方服务的安全机制；
   - 任何违反所在地法律法规或第三方服务协议的行为。

3. **模拟数据不具真实性。** 本项目产生的位置、轨迹、卫星、Wi-Fi、基站、蓝牙与传感器数据均为模拟值，**不代表设备真实位置或真实运动状态**，不得作为实际到场、运动成绩、考勤或其他真实性证明。

4. **风险自担。** 使用本项目可能违反第三方应用的服务条款，并可能导致账号被限制、封禁或其他后果。**使用者应自行评估并承担全部风险与法律责任**，作者与贡献者不对任何直接或间接损失负责。

5. **不提供规避能力的承诺。** 本项目不承诺、也不保证能够绕过任何第三方平台的风控、检测、反作弊或安全机制，不提供此类用途的技术支持或咨询。

6. **如所在地法律禁止此类工具的使用或分发，请立即停止使用并删除本项目及其衍生文件。**

7. **无担保。** 本项目按 GNU GPLv3 提供，不附带任何明示或默示担保，包括但不限于适销性、特定用途适用性与不侵权担保。

---

## 致谢与许可

- 感谢上游项目的作者（上游 `LICENSE` 署名为 **SuseOAA**）、仓库维护者 [@HuangZhuoRui](https://github.com/HuangZhuoRui) 及所有上游贡献者的工作；
- 原始作品版权：**Copyright © 2026 SuseOAA**；
- 本分支修改部分版权：**Copyright © 2026 ZenQing-Tao**；
- 依 [GNU General Public License v3.0](./LICENSE) 分发；修改记录见 [`NOTICE`](./NOTICE)；
- 第三方组件（高德 / 百度 / Google Maps / MapLibre / Room / Jetpack Compose 等）版权归各自权利人所有，遵循各自许可证。
- 应用图标由本项目自制，灵感来源于「缩地青符」。

**分发本项目二进制时，请同时提供对应版本的完整源码，并保留 `LICENSE`、`NOTICE` 与本文件中的署名与免责声明。**

---

## 反馈

请通过本仓库的 [Issues](../../issues) 提交问题；安全相关问题请按 [`SECURITY.md`](./SECURITY.md) 私下报告。

> 本仓库与上游作者及任何第三方社群无隶属关系，不代其提供支持。
