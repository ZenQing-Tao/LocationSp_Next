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
| [Nominatim](https://nominatim.org/) | 地点搜索 | ODbL / 使用政策 | 代码中已固定 User-Agent 并限流 1 次/秒 |
| AMap SDK（高德） | 可选地图引擎 | 高德开发者协议 | 仅在用户自备 Key 时启用 |
| Baidu Map SDK（百度） | 可选地图引擎 | 百度开发者协议 | 仅在用户自备 Key 时启用 |
| Google Maps / Places SDK | 可选地图引擎 | Google APIs 服务条款 | 仅在用户自备 Key 时启用，**按次计费** |

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
