package ai.gyeote.app.vehicle

import android.content.Context
import android.os.Handler
import android.os.Looper
import ai.gyeote.app.domain.Destination
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.constants.NaviErrorCode

internal class PleosNavigator(context: Context, private val restricted: () -> Boolean) : Navigator {
    private val main = Handler(Looper.getMainLooper())
    private val navi = NaviHelper(context)
    private val arrivals = linkedSetOf<() -> Unit>()
    private var ready = false
    private var closed = false
    override var status = "길안내 연결 준비 중"
        private set
    private fun post(action: () -> Unit) { main.post { if (!closed) action() } }
    private val events = object : NaviHelperEventListener {
        override fun onDestinationArrived(info: DestinationArrivedInfo) = post {
            status = "목적지 도착 신호를 받았어요"; arrivals.toList().forEach { it() }
        }
        override fun onRoutingCompleted() = post { status = "경로 계산 완료 신호를 받았어요"; vehicleLog("navigation=routingCompleted") }
        override fun onError(errorCode: NaviErrorCode) = post {
            status = "길안내 요청 후 오류가 발생했어요. 정차 후 다시 골라 주세요."
            vehicleLog("navigationError=${errorCode.name}")
        }
    }
    init {
        runCatching {
            navi.initialize(onConnected = { post { ready = true; navi.addListener(events); status = "길안내 요청 준비됨"; vehicleLog("navigation=connected") } },
                onFailed = { post { status = "길안내 서비스 연결을 마치지 못했어요"; vehicleLog("navigation=unavailable") } })
        }.onFailure { vehicleLog("navigation unavailable: ${it.javaClass.simpleName}") }
    }
    override fun requestRoute(destination: Destination): Boolean {
        if (!ready || restricted()) return false
        return try {
            navi.requestRoute(RouteInfo(latitude = destination.lat, longitude = destination.lng,
                poiName = destination.name, poiId = "", poiSubId = "", address = destination.address))
            status = "길안내를 요청했어요. 안내 시작은 내비 화면에서 살펴 주세요."
            vehicleLog("navigation=requestSubmitted"); true
        } catch (_: Exception) { status = "길안내 요청을 마치지 못했어요"; false }
    }
    override fun onDestinationArrived(callback: () -> Unit): Subscription {
        arrivals += callback
        return Subscription { arrivals -= callback }
    }
    fun close() {
        closed = true; main.removeCallbacksAndMessages(null)
        runCatching { navi.removeListener(events) }; runCatching { navi.release() }
    }
}
