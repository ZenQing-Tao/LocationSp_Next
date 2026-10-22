package com.vincenthzr.locationspoofer.data.model

enum class MapEngine {
    /**
     * AUTO =「有高德 Key 就用高德，否则回退 OSM」。
     * 见 AppMapView.activeEngine 的解析逻辑。
     */
    AUTO,

    /** OpenStreetMap（MapLibre 栅格瓦片）：无需 Key / 账号 / 计费，默认引擎。 */
    OSM,

    /** 高德：需要用户自备 Android Key。 */
    AMAP,

    /** 百度：需要用户自备 AK。 */
    BAIDU,

    /** Google Maps：按次计费，且需要用户自备 Key。 */
    GOOGLE
}