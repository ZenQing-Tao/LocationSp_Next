package com.vincenthzr.locationspoofer.data.motion

import com.vincenthzr.locationspoofer.data.model.RoutePoint
import com.vincenthzr.locationspoofer.utils.CoordinateUtils.LatLng
import com.vincenthzr.locationspoofer.utils.GeoMath
import com.vincenthzr.locationspoofer.utils.PaceProfile
import java.util.Collections
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionControllerTest {

    private class FakeSink : MotionController.MotionSink {
        data class Manual(val lat: Double, val lng: Double, val speed: Double)
        data class Route(val offset: Double, val start: Long, val preset: MotionController.SpeedPreset)

        val manual: MutableList<Manual> = Collections.synchronizedList(ArrayList())
        val routes: MutableList<Route> = Collections.synchronizedList(ArrayList())
        var saved: Pair<String, List<RoutePoint>>? = null

        override suspend fun writeManual(lat: Double, lng: Double, bearing: Float, speedMs: Double, timestamp: Long) {
            manual += Manual(lat, lng, speedMs)
        }

        override suspend fun writeRoute(
            lat: Double, lng: Double, bearing: Float, preset: MotionController.SpeedPreset,
            points: List<RoutePoint>, startTimestamp: Long, distanceOffset: Double, stopAtDestination: Boolean
        ) {
            routes += Route(distanceOffset, startTimestamp, preset)
        }

        override suspend fun saveRoute(name: String, points: List<RoutePoint>) {
            saved = name to points
        }
    }

    /** 配置写入在后台协程里异步执行，等它落到 FakeSink */
    private fun awaitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 2000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertTrue("condition not met in time", condition())
    }

    private var now = 1_000_000L
    private val sink = FakeSink()
    private val walking = MotionController.SpeedPreset("WALKING", 2.0)
    private val controller = MotionController(sink, realismParams = { 0 to 0 }, clock = { now }, autoTick = false)

    private val start = LatLng(30.0, 120.0)
    private val end = GeoMath.destination(start, 0.0, 1000.0) // 正北 1000 米

    /**
     * 3.2.0 起路线距离由 [PaceProfile] 权威计算：关闭所有起伏时是严格匀速，
     * 即“距离 = 速度 × 经过时间”。这条不变量是下面所有距离断言的基础。
     */
    private fun expectedDistance(speedMs: Double, elapsedMs: Long): Double =
        speedMs * (elapsedMs / 1000.0)

    private fun here() = LatLng(controller.state.value.lat, controller.state.value.lng)

    private fun startRoute() {
        controller.onRouteStarted(
            listOf(RoutePoint(start.lat, start.lng), RoutePoint(end.lat, end.lng)),
            walking, stopAtDestination = false, startTimestamp = now
        )
        controller.tick(now)
    }

    private fun advance(ms: Long) {
        now += ms
        controller.tick(now)
    }

    @Test
    fun `route playback advances along the path`() {
        startRoute()
        advance(10_000)
        assertEquals(expectedDistance(2.0, 10_000), GeoMath.distance(start, here()), 0.1)
        assertEquals(MotionController.Mode.ROUTE, controller.state.value.mode)
    }

    @Test
    fun `joystick is ignored while the route is playing`() {
        startRoute()
        controller.joystick(90.0, 1f)
        advance(5_000)
        assertEquals(expectedDistance(2.0, 5_000), GeoMath.distance(start, here()), 0.1) // 仍沿路线正北行进，没有被摇杆带偏
    }

    @Test
    fun `pause hands control to the joystick and freezes the route`() {
        startRoute()
        advance(10_000)
        controller.pause()
        val paused = here()
        assertTrue(controller.state.value.paused)
        awaitUntil { sink.manual.isNotEmpty() }
        assertEquals(0.0, sink.manual.last().speed, 0.0)

        advance(5_000) // 没推摇杆，位置不动
        assertEquals(0.0, GeoMath.distance(paused, here()), 1e-6)

        controller.joystick(90.0, 1f) // 推满向东
        advance(1_000)
        assertEquals(2.0, GeoMath.distance(paused, here()), 0.05)
        controller.joystick(0.0, 0f)
        awaitUntil { sink.manual.last().speed == 0.0 }
    }

    @Test
    fun `resume walks back to the pause point and continues from the paused distance`() {
        startRoute()
        advance(10_000) // 路线上走了 20 米
        controller.pause()
        val pausePoint = here()
        controller.joystick(90.0, 1f)
        repeat(5) { advance(1_000) } // 向东走开 10 米
        controller.joystick(0.0, 0f)
        assertEquals(10.0, GeoMath.distance(pausePoint, here()), 0.1)

        controller.resume()
        assertTrue(controller.state.value.returning)
        // 以 2 m/s 走回 10 米，全程逐步靠近、没有瞬移
        var last = GeoMath.distance(pausePoint, here())
        repeat(20) {
            if (!controller.state.value.returning) return@repeat
            advance(500)
            val d = GeoMath.distance(pausePoint, here())
            assertTrue(d <= last + 1e-6)
            assertTrue(last - d <= 1.0 + 1e-6)
            last = d
        }
        assertFalse(controller.state.value.returning)
        assertEquals(MotionController.Mode.ROUTE, controller.state.value.mode)
        // 3.2.0 起开局也会写一次 route（offset=0），因此要等“恢复后那一次”落盘再断言
        awaitUntil { sink.routes.any { it.offset > 19.9 } }
        assertEquals(20.0, sink.routes.last().offset, 0.01)

        advance(5_000) // 接着原路线走：20 + 10 = 30 米
        assertEquals(30.0, GeoMath.distance(start, here()), 0.1)
    }

    @Test
    fun `changing speed during a route does not jump the position`() {
        startRoute()
        advance(10_000)
        val before = here()
        controller.setSpeedPreset(MotionController.SpeedPreset("RUNNING", 4.0))
        controller.tick(now)
        assertEquals(0.0, GeoMath.distance(before, here()), 0.01)
        advance(5_000)
        assertEquals(expectedDistance(4.0, 5_000) + 20.0, GeoMath.distance(start, here()), 0.1) // 20 米 + 4 m/s × 5 秒
    }

    @Test
    fun `recording samples the joystick path and saves it as a route`() = runBlocking {
        controller.onStaticStarted(start.lat, start.lng)
        controller.setSpeedPreset(walking)
        controller.tick(now)
        controller.startRecording()
        controller.joystick(90.0, 1f) // 向东 30 米
        repeat(15) { advance(1_000) }
        controller.joystick(0.0, 1f) // 转向正北 10 米
        repeat(5) { advance(1_000) }
        controller.joystick(0.0, 0f)
        controller.stopRecording()

        val state = controller.state.value
        assertTrue(state.hasPendingRecording)
        assertEquals(40.0, state.recordedDistanceM, 1.0)

        assertTrue(controller.saveRecording("测试路线"))
        val (name, points) = sink.saved!!
        assertEquals("测试路线", name)
        assertEquals(start.lat, points.first().lat, 1e-9)
        // 直线段约每 5 米一个点，不会每 100ms 记一次
        assertTrue(points.size in 7..12)
        assertFalse(controller.state.value.hasPendingRecording)
    }

    @Test
    fun `manual route starts paused and Start walks to the route before following it`() {
        controller.onRouteStarted(
            listOf(RoutePoint(start.lat, start.lng), RoutePoint(end.lat, end.lng)),
            walking, stopAtDestination = false, startTimestamp = now, startPaused = true
        )
        controller.tick(now)
        assertTrue(controller.state.value.paused)
        assertTrue(controller.state.value.hasRoute)
        assertEquals(MotionController.Mode.MANUAL, controller.state.value.mode)

        controller.joystick(90.0, 1f) // 先用摇杆向东走开 6 米
        repeat(3) { advance(1_000) }
        controller.joystick(0.0, 0f)

        controller.resume() // "开始"：先走回路线起点
        assertTrue(controller.state.value.returning)
        repeat(10) { if (controller.state.value.returning) advance(1_000) }
        assertEquals(MotionController.Mode.ROUTE, controller.state.value.mode)
        awaitUntil { sink.routes.isNotEmpty() }
        assertEquals(0.0, sink.routes.last().offset, 1e-9)

        advance(5_000) // 然后沿路线正北前进 10 米
        assertEquals(expectedDistance(2.0, 5_000), GeoMath.distance(start, here()), 0.1)
    }

    /**
     * 3.2.0：路线模式下速度读数必须等于设定速度（不再叠加随机噪声），
     * 这是“设 6'00" 实测 4'30"”这个 bug 的直接回归。
     */
    @Test
    fun `route speed equals the configured speed without noise`() {
        startRoute()
        advance(10_000)
        assertEquals(2.0, controller.state.value.speedMs, 1e-6)
        advance(10_000)
        assertEquals(2.0, controller.state.value.speedMs, 1e-6)
    }

    /** 3.2.0：配速浮动开启时，各公里配速不完全相同（曲线没有退化成单一速度） */
    @Test
    fun `pace variation gives different paces to different kilometers`() {
        val profile = PaceProfile.fromConfig(
            baseSpeedMps = 3.0,
            paceVariationSec = 10.0,
            fatigueLevel = 0,
            firstKmBiasSec = 0.0,
            routeLengthM = 10_000.0,
            seed = now
        )
        val paces = (0..4).map { profile.paceSecPerKm(it) }
        assertTrue("paces=$paces", paces.max() - paces.min() > 1e-6)
    }

    /** 3.2.0：开启力竭后，后段配速必须比前段慢（若 kmIndexAt 失效则两者相等） */
    @Test
    fun `fatigue makes later kilometers slower`() {
        val profile = PaceProfile.fromConfig(
            baseSpeedMps = 3.0,
            paceVariationSec = 0.0,
            fatigueLevel = 2,
            firstKmBiasSec = 0.0,
            routeLengthM = 10_000.0,
            seed = now
        )
        // 力竭从 40%（第 4 公里）之后开始，第 6 公里必须已经变慢且继续变慢
        assertTrue(profile.paceSecPerKm(6) > profile.paceSecPerKm(0))
        assertTrue(profile.paceSecPerKm(9) > profile.paceSecPerKm(6))
    }

    /** 3.2.0：暂停后继续，累计距离必须连续（distanceAfter 的偏移衔接） */
    @Test
    fun `resume keeps the accumulated distance continuous`() {
        startRoute()
        advance(10_000) // 走了 20 米
        controller.pause()
        controller.resume()
        repeat(20) { if (controller.state.value.returning) advance(500) }
        // 继续后 5 秒应再走 10 米，累计 30 米
        advance(5_000)
        assertEquals(30.0, GeoMath.distance(start, here()), 0.1)
    }

    @Test
    fun `stopping clears the session`() {
        startRoute()
        controller.onStopped()
        assertFalse(controller.state.value.active)
        controller.joystick(90.0, 1f)
        advance(1_000)
        assertFalse(controller.state.value.active)
    }
}
