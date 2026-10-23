# 第三方组件与许可

本项目自身依 **GNU GPLv3** 分发（见 [`LICENSE`](./LICENSE)）。
下列第三方组件的版权归各自权利人所有，遵循各自的许可证。分发本项目时必须同时保留本文件与各组件自带的版权声明。

## 地图与地理数据

| 组件 | 用途 | 许可 / 条款 | 备注 |
| --- | --- | --- | --- |
| [MapLibre Native Android](https://github.com/maplibre/maplibre-native) | 矢量/栅格地图渲染 | BSD-2-Clause | 保留其版权声明 |
| [OpenStreetMap](https://www.openstreetmap.org/copyright) | 底图数据 | ODbL 1.0 | 必须署名为 “© OpenStreetMap contributors” |
| [OpenFreeMap](https://openfreemap.org/) | 矢量瓦片与样式服务 | 服务使用条款 | 免费、免 Key；保留署名 |
| [Esri World Imagery](https://www.esri.com/) | 卫星影像瓦片 | Esri 使用条款 | 仅用于非商业场景并保留 Esri 署名；商用请自行申请授权 |
| [Photon](https://photon.komoot.io/) | 地点搜索（默认） | 公共实例使用政策 / ODbL 数据 | komoot 提供的公开实例，按合理使用，不保证 SLA |
| [Nominatim](https://nominatim.org/) | 地点搜索（兜底） | ODbL / 使用政策 | OSM 基金会公益服务，代码中已固定 User-Agent 并限流 1 次/秒 |
| AMap SDK（高德） | 可选地图引擎 | 高德开发者协议 | 仅在用户自备 Key 时启用 |
| Baidu Map SDK（百度） | 可选地图引擎 | 百度开发者协议 | 仅在用户自备 Key 时启用 |
| Google Maps / Places SDK | 可选地图引擎 | Google APIs 服务条款 | 仅在用户自备 Key 时启用，**按次计费** |

## 搜索服务的公平使用（重要）

内置的 Photon、Nominatim 与 OpenFreeMap 均为社区或公益性资源，并非为大规模商业调用而设。本项目通过以下方式限制对它们的占用：

| 措施 | 说明 |
| --- | --- |
| 请求节流 | 搜索请求之间至少间隔 1.1 秒（约 1 次/秒） |
| 每日限额 | 内置搜索服务每个设备每天最多 8 次（缓存命中不计数） |
| 本地缓存 | 重复关键词直接走本地缓存，不发请求 |
| 可退出 | 可在设置填写自建 / 自有搜索服务地址，完全不占用公共资源 |

对于重度使用（批量搜索、多设备集中使用、将本项目作为商业产品分发），**请自行部署 Photon / Nominatim，或接入商业地理编码服务**，不要依赖这些免费公共实例。本项目对其可用性、响应速度与长期存续不作任何承诺。

## 应用框架与库

| 组件 | 许可 |
| --- | --- |
| AndroidX / Jetpack Compose / Room / Lifecycle | Apache-2.0 |
| Kotlin / kotlinx.coroutines / kotlinx.serialization | Apache-2.0 |
| Koin | Apache-2.0 |
| OkHttp / Okio | Apache-2.0 |
| [libxposed API](https://github.com/libxposed/api) | Apache-2.0 |
| miuix-kmp | Apache-2.0 |

## 免责

第三方服务（地图瓦片、地点搜索、云端环境数据）可能随时变更其条款、配额或可用性。本项目对其可用性不作担保。
若你将本项目用于商业用途，请自行核实上述各服务的商用授权要求（尤其是 Esri 与 Google Maps）。
