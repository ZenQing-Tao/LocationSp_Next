# LocationSp_Next

[简体中文](./README.md) | **English**

> **Unofficial fork.**
> This project is derived from **LocationSpoofer**, whose repository is maintained by GitHub user [@HuangZhuoRui](https://github.com/HuangZhuoRui);
> the copyright holder named in the upstream `LICENSE` is **SuseOAA** (kept verbatim here; no claim is made about identity).
> The upstream repository <https://github.com/HuangZhuoRui/LocationSpoofer> was **archived on 2026-10-18** and its source code removed.
> This branch has been maintained by **ZenQing-Tao** since **2026-10-22**, based on upstream commit `dcbe620`.
> **This project is not affiliated with, endorsed by, or released on behalf of the upstream author/maintainer.**

Android system-level location and wireless-environment simulation framework based on KernelSU / APatch / Magisk + LSPosed (libxposed API 102).

> ⚠️ **Read before use**: for study, research and testing only — on your own devices, your own apps, or environments you are explicitly authorized to test. **Any illegal use is strictly prohibited.** See the disclaimer at the end.

<p align="center">
  <img src="docs/images/app_icon.png" width="128" alt="LocationSp_Next app icon">
</p>
<p align="center"><sub>Icon inspired by the 「缩地青符」 (Shrink-Earth Talisman).</sub></p>
<p align="center"><strong>Stand still, travel a thousand miles.</strong></p>


---

## Provenance & License

| Item | Value |
| --- | --- |
| Original project | LocationSpoofer |
| Upstream maintainer | GitHub [@HuangZhuoRui](https://github.com/HuangZhuoRui) |
| Copyright holder (upstream LICENSE) | SuseOAA |
| Upstream repo | <https://github.com/HuangZhuoRui/LocationSpoofer> (archived 2026-10-18) |
| Upstream last commit | `dcbe620` |
| License | GNU General Public License v3.0 — see [`LICENSE`](./LICENSE) |
| Original copyright | Copyright © 2026 SuseOAA |
| Maintainer of this fork | ZenQing-Tao (since 2026-10-22) |
| Modification record | [`NOTICE`](./NOTICE) (per GPLv3 §5(a)) |

---

## Differences from upstream

| Item | Upstream | This fork |
| --- | --- | --- |
| Map keys | Third-party keys baked in | **No keys bundled**; injected via `local.yml` / env vars / in-app settings |
| Default basemap | AMap | **OSM vector tiles (MapLibre + OpenFreeMap)** — no key, no quota |
| Default search | AMap POI | **Nominatim** — no key required |
| AMap / Baidu / Google | Enabled out of the box | Optional; bring your own key |
| App name | LocationSpoofer | **LocationSp_Next** |

---

## Install

| Variant | Scope |
| --- | --- |
| `LocationSp_Next-scoped-<abi>.apk` | Applies only to apps selected in the LSPosed scope |
| `LocationSp_Next-global-<abi>.apk` | Hooks system services; applies device-wide |

Enable the module in LSPosed and restart the target app (some devices require a reboot).

> Debug builds may use a different signing key each time, so they **cannot be installed over an existing build** — uninstall first. Uninstalling clears local data.

---

## Map & third-party configuration

No map keys are bundled. Configure via `local.yml` (git-ignored) or environment variables — see [`local.yml.example`](./local.yml.example).

| Variable | Purpose | Notes |
| --- | --- | --- |
| `AMAP_API_KEY` | AMap Android SDK (map / location / search) | Choose the **Android** platform; bind your package name + signing SHA1. Search APIs have a daily quota — do not share keys |
| `BAIDU_MAP_API_KEY` | Baidu Map Android SDK | Create an app of type **Android SDK**, bind package + SHA1 |
| `GOOGLE_MAPS_API_KEY` | Google Maps / Places | **Billed per request**; restrict by package + SHA1 + API allowlist |

Without any key the app still works: OSM basemap + Nominatim search.

### Map attribution

- Base map data © [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors, vector tiles and styles by [OpenFreeMap](https://openfreemap.org/) (no key, no quota);
- Satellite imagery © [Esri](https://www.esri.com/) World Imagery;
- Follow each provider's attribution and terms when using AMap / Baidu / Google.

Attribution must not be removed or hidden.

---

## Build

JDK 17, Android SDK (compileSdk 37).

```bash
cp local.yml.example local.yml          # optional: your own keys
./gradlew :app:assembleScopedDebug
./gradlew :app:assembleGlobalDebug
```

---

## Data & Privacy

- While running, the app reads environment data **locally**: Wi-Fi scan results (SSID / BSSID / RSSI), cellular cells (including 5G NR), BLE beacons, sensors and location, so that the simulated environment stays self-consistent.
- This data is stored **only on your device** (app-private database) and is **never uploaded**; the project has no backend server.
- Network requests happen only when you use the corresponding feature:
  - map tiles: OpenFreeMap (default) / Esri (satellite) / a tile source you configure;
  - place search: Nominatim (fixed User-Agent, throttled to 1 req/s);
  - cloud environment data: WiGLE / OpenCellID (**only if you supply your own token**);
  - update check: GitHub API.
- Uninstalling the app removes all local data; you can also export or clear it from the app's data import/export screen.
- This project does not implement any environment-data upload or crowdsourcing. If you add such features in a fork, you are collecting other people's data and must comply with applicable privacy laws yourself.

## Disclaimer & Acceptable Use

1. **Permitted use only.** This project is provided for study, research and communication, and for testing on your own devices/apps or environments you are explicitly authorized to test.
2. **Prohibited uses** include, but are not limited to: faking attendance or check-ins; forging movement or travel records for rewards; fraud or deception; unauthorized interference with other devices, apps or systems; circumventing third-party security mechanisms; any use violating applicable law or terms of service.
3. **Simulated data is not real.** All location, motion, satellite, Wi-Fi, cell and sensor values are simulated and must not be used as proof of presence, athletic performance, attendance or any other fact.
4. **Use at your own risk.** Using this project may violate third-party terms of service and result in account restrictions. Users bear all risks and legal responsibility. The authors accept no liability for any damages.
5. **No circumvention guarantees.** No guarantee is made that this project can bypass any anti-fraud, detection or security mechanism; no support is provided for such purposes.
6. **If such tools are prohibited where you live, stop using and delete this project and its derivatives immediately.**
7. **No warranty**, as set out in the GNU GPLv3.

---

## Credits

- The app icon is an original work of this project, inspired by the 「缩地青符」 (Shrink-Earth Talisman).

- Thanks to the upstream author (named in the upstream LICENSE as **SuseOAA**), the upstream maintainer [@HuangZhuoRui](https://github.com/HuangZhuoRui), and all upstream contributors.
- Original work © 2026 SuseOAA; modifications © 2026 ZenQing-Tao.
- Distributed under [GNU GPLv3](./LICENSE); see [`NOTICE`](./NOTICE) for the modification record.

**When distributing binaries, provide the corresponding complete source code and retain `LICENSE`, `NOTICE`, and the attribution and disclaimer above.**
