package ai.gyeote.app

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import ai.gyeote.app.domain.*
import ai.gyeote.app.vehicle.WheelPosition
import ai.gyeote.app.vehicle.PleosSpeechProbe
import ai.gyeote.app.vehicle.Subscription
import ai.gyeote.app.vehicle.VehicleRecordRuntime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Native parked AAOS activity, also installable on a standard phone AVD for this prototype. */
class MainActivity : ComponentActivity() {
    private lateinit var controller: AppController
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private var scroll: ScrollView? = null
    private var page = "home"
    private var selectedWarning = WarningCode.TPMS
    private var consentDraft = Consent()
    private var arrivalModeDraft = ArrivalMode.ASK_EVERY_TIME
    private var draftLoaded = false
    private var parentName = "부모님"
    private var serverInput = "http://10.0.2.2:8787"
    private var sttInput = "응"
    private var renderPending = false
    private var forceFake = false
    private var publicTelltalesOnly = false
    private var testProfile: String? = null
    private var screenSubscription: Subscription? = null
    private var screenStarted = false
    private val recordConfig get() = VehicleRecordRuntime.Config(forceFake, publicTelltalesOnly, testProfile)
    private var speechProbe: PleosSpeechProbe? = null
    private var clearHistoryConfirm = false
    private var clearAllConfirm = false
    /** Record screen tab: warnings / pairs (인계 이력) / trips (운행 표, fleet mode only). */
    private var historyTab = "warnings"
    private var noteEditing = false
    private var noteDraft = ""
    private var showPairCode = false
    private var pairCountdown: TextView? = null
    private var renderedArrivalId: String? = null
    private val carWide get() = resources.configuration.screenWidthDp >= 900
    private val backgroundColor = Color.rgb(16, 37, 37)
    private val surface = Color.rgb(28, 55, 54)
    private val light = Color.rgb(243, 248, 240)
    private val mint = Color.rgb(169, 231, 203)
    private val muted = Color.rgb(185, 207, 200)
    private val dark = Color.rgb(19, 45, 40)
    private val peach = Color.rgb(255, 217, 181)
    private val danger = Color.rgb(255, 190, 178)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        // The debug build permits only the explicitly requested emulator evidence captures.
        if (BuildConfig.DEBUG) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        page = savedInstanceState?.getString("page") ?: "home"
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(backgroundColor) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(root)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (::controller.isInitialized && !controller.restricted) navigate("home")
            }
        })
        try {
            forceFake = BuildConfig.DEBUG && intent.getStringExtra("vehicleMode") == "fake"
            publicTelltalesOnly = BuildConfig.DEBUG && intent.getBooleanExtra("publicTelltalesOnly", false)
            testProfile = intent.getStringExtra("testProfile")?.takeIf { BuildConfig.DEBUG && it.matches(Regex("[A-Za-z0-9_-]{1,40}")) }
            controller = VehicleRecordRuntime.obtain(applicationContext, recordConfig).controller
            serverInput = controller.serverUrl
            applyDebugIntent(intent)
            render()
        } catch (_: Exception) {
            root.addView(label("설정을 안전하게 열지 못했어요.", 28, light, true))
            root.addView(label("기존 자료는 그대로 두었어요. 앱을 다시 열어 주세요.", 22, muted))
        }
    }
    override fun onStart() {
        super.onStart()
        if (::controller.isInitialized) {
            val session = VehicleRecordRuntime.obtain(applicationContext, recordConfig)
            controller = session.controller
            screenStarted = true
            screenSubscription = session.attachScreen(::scheduleRender)
            render()
        }
        handler.post(pairCountdownTick)
    }
    override fun onStop() {
        screenStarted = false
        screenSubscription?.cancel(); screenSubscription = null
        handler.removeCallbacksAndMessages(null); renderPending = false
        handler.removeCallbacks(pairCountdownTick); showPairCode = false
        speechProbe?.close(); speechProbe = null
        super.onStop()
    }
    private val pairCountdownTick = object : Runnable {
        override fun run() {
            if (::controller.isInitialized && page == "pairing" && !controller.restricted) {
                controller.pairCode?.let { code ->
                    if (pairCountdown != null) {
                        if (code.remainingSeconds(Instant.now()) == 0L) render()
                        else pairCountdown?.text = code.remainingText(Instant.now())
                    }
                }
            }
            handler.postDelayed(this, 1_000)
        }
    }
    override fun onDestroy() {
        screenStarted = false
        screenSubscription?.cancel(); screenSubscription = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("page", page); super.onSaveInstanceState(outState) }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        if (BuildConfig.DEBUG && ((intent.hasExtra("vehicleMode") && (intent.getStringExtra("vehicleMode") == "fake") != forceFake) ||
                (intent.hasExtra("publicTelltalesOnly") && intent.getBooleanExtra("publicTelltalesOnly", false) != publicTelltalesOnly))) {
            recreate(); return
        }
        if (BuildConfig.DEBUG && intent.hasExtra("testProfile") && intent.getStringExtra("testProfile") != testProfile) {
            recreate(); return
        }
        applyDebugIntent(intent); render()
    }
    private fun applyDebugIntent(intent: Intent) {
        if (!BuildConfig.DEBUG || !::controller.isInitialized) return
        intent.getStringExtra("serverUrl")?.let { serverInput = it }
        intent.getStringExtra("screen")?.takeIf { it in setOf("home", "consent", "warning", "history", "mechanic", "maintenance", "destinations", "messages", "console", "settings") }?.let {
            page = when {
                it == "maintenance" && !Features.standaloneMaintenance -> "history"
                it == "messages" && !Features.familyVoiceMessages -> "home"
                it == "consent" && !Features.familyConnection -> "settings"
                it == "destinations" && !Features.sharedRoutes -> "home"
                else -> it
            }; draftLoaded = false; scroll = null
        }
        intent.getStringExtra("warning")?.let { value ->
            WarningCode.entries.firstOrNull { it.name == value }?.let {
                selectedWarning = it
                if (controller.ports.simulated && it !in controller.fake.activeWarnings) controller.fake.toggleWarning(it)
            }
        }
        intent.getStringExtra("historyTab")?.takeIf { it in setOf("warnings", "pairs", "trips") }?.let { historyTab = it }
        // Debug-only evidence setup for app/tools/return_integration.py (fresh records each run).
        if (intent.getBooleanExtra("clearRecords", false)) controller.clearAllRecordsForTest()
        if (intent.hasExtra("restricted") && controller.ports.simulated) controller.fake.setRestriction(intent.getBooleanExtra("restricted", true))
        if (intent.getBooleanExtra("probeSpeech", false) && !controller.restricted) {
            speechProbe?.close(); speechProbe = PleosSpeechProbe(applicationContext).also { it.start() }
        }
    }
    private fun scheduleRender() {
        if (!screenStarted || !::controller.isInitialized || renderPending || isFinishing || isDestroyed) return
        renderPending = true
        handler.postDelayed({
            renderPending = false
            if (screenStarted && !isFinishing && !isDestroyed) {
                val prompt = controller.arrival.prompt
                if (Features.sharedRoutes && !controller.restricted && controller.privacy.current.routeArrivalAllowed(Instant.now()) &&
                    controller.arrival.mode == ArrivalMode.ASK_EVERY_TIME && prompt != null && renderedArrivalId == prompt.destinationId) {
                    // Keep the unanswered question and its controls stable.
                } else render()
            }
        }, 70)
    }
    private fun navigate(next: String) {
        if (controller.restricted) return
        if (next == "console" && !BuildConfig.DEBUG) return
        if (next == "messages" && !Features.familyVoiceMessages) return
        if ((next == "consent" || next == "pairing") && !Features.familyConnection) return
        if (next == "destinations" && !Features.sharedRoutes) return
        if (next == "maintenance" && !Features.standaloneMaintenance) { navigate("history"); return }
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken, 0)
        page = next; scroll = null; clearAllConfirm = false
        if (next == "consent") { consentDraft = controller.privacy.current; arrivalModeDraft = controller.arrival.mode; draftLoaded = true }
        render()
    }
    private fun render() {
        if (!::controller.isInitialized) return
        val scrollY = scroll?.scrollY ?: 0
        val focusTag = (currentFocus as? EditText)?.tag
        val selection = (currentFocus as? EditText)?.selectionStart ?: 0
        renderedArrivalId = null
        pairCountdown = null
        root.removeAllViews()
        if (controller.restricted) { driving(); return }
        val top = column(20).apply {
            val heading = LinearLayout(this@MainActivity).apply { gravity = Gravity.CENTER_VERTICAL }
            heading.addView(label(getString(R.string.app_name), 30, mint, true), LinearLayout.LayoutParams(0, -2, 1f))
            heading.addView(label(if (controller.ports.simulated) "정차 중 · 모의 차량" else "정차 중 · Pleos 차량", 18, muted), LinearLayout.LayoutParams(-2, -2))
            addView(heading)
            addView(label(if (Features.serverSync) controller.connection else "인계 방식 · ${controller.handoverSettings.mode.label}", 14, muted).apply { setPadding(0, dp(5), 0, 0) })
        }
        root.addView(top)
        val scroller = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        val content = column(20).apply { setPadding(dp(20), dp(2), dp(20), dp(24)) }
        scroller.addView(content)
        if (carWide) {
            val frame = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            frame.addView(navigation(), LinearLayout.LayoutParams(dp(150), -1))
            frame.addView(scroller, LinearLayout.LayoutParams(0, -1, 1f))
            root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))
        } else root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        scroll = scroller
        if (Features.sharedRoutes && controller.arrival.mode == ArrivalMode.ASK_EVERY_TIME && controller.arrival.prompt != null && controller.privacy.current.routeArrivalAllowed(Instant.now())) arrivalScreen(content)
        else if (controller.tourVisible) tourScreen(content)
        else if (controller.returnVisible) returnScreen(content)
        else if (controller.handoverVisible) handoverScreen(content)
        else when (page) {
            "settings" -> settingsScreen(content)
            "consent" -> consentScreen(content)
            "pairing" -> pairingScreen(content)
            "warning" -> warningScreen(content)
            "history" -> historyScreen(content)
            "mechanic" -> mechanicScreen(content)
            "maintenance" -> if (Features.standaloneMaintenance) maintenanceScreen(content) else historyScreen(content)
            "destinations" -> destinationsScreen(content)
            "messages" -> if (Features.familyVoiceMessages) messagesScreen(content) else homeScreen(content)
            "console" -> consoleScreen(content)
            else -> homeScreen(content)
        }
        if (!carWide) root.addView(navigation())
        scroller.post { scroller.scrollTo(0, scrollY) }
        if (focusTag != null) root.findViewWithTag<EditText>(focusTag)?.let {
            it.requestFocus(); it.setSelection(selection.coerceIn(0, it.text.length))
        }
    }
    private fun homeScreen(content: LinearLayout) {
        val (left, right) = pageColumns(content)
        // Neutral placeholder title; the tagline stays empty until the name is decided.
        left.addView(label("오늘", if (carWide) 44 else 36, light, true).apply { setPadding(0, dp(10), 0, dp(8)) })
        getString(R.string.app_tagline).takeIf { it.isNotBlank() }?.let { left.addView(label(it, 22, muted)) }
        gap(left, 24)
        val advice = controller.warningAdvice
        card(left, if (advice == null) mint else peach) {
            addView(label(if (advice == null) "차량 안내" else advice.urgency.label, 15, dark, true))
            addView(label(advice?.title ?: "켜진 경고등이 없어요", 27, dark, true).padTop(10))
            addView(label(advice?.meaning ?: "차량이 보내온 상태예요.\n필요한 순간, 쉬운 말로 알려드릴게요.", 22, dark).padTop(10))
            if (advice != null) addView(button("뜻과 할 일 보기", true) { selectedWarning = advice.code; navigate("warning") }.padTop(14))
        }
        gap(right, 12)
        if (!Features.sharedRoutes) card(right) {
            addView(label("차를 넘겨받을 때", 23, light, true))
            addView(label(controller.lastHandover?.let { "마지막 인수 기록\n" + HandoverText.snapshotSummary(it) }
                ?: "운전석 문을 열고 닫으면 정차 중에\n차가 자기 상태를 먼저 알려 드려요.", 22, muted).padTop(10))
            addView(button("인계 설정", false) { navigate("settings") }.padTop(14))
        }
        if (Features.sharedRoutes) card(right) {
            addView(label("마음이 닿는 소식", 23, light, true))
            val enabled = controller.privacy.current.routeArrivalAllowed(Instant.now())
            addView(label(if (enabled) "자녀가 보낸 길에 도착할 때만\n고른 가족에게 안부를 전해요." else "소식 전하기는 꺼져 있어요.\n알릴 사람과 내용을 직접 골라 주세요.", 24, muted).padTop(10))
            addView(button("소식 전하기 설정", false) { navigate("consent") }.padTop(14))
        }
        gap(right, 12)
        right.addView(button("기록 보기  ·  경고등 · 인계 이력", false) { navigate("history") })
        if (Features.sharedRoutes) right.addView(button("자녀가 보낸 길", false) { navigate("destinations") }.padTop(10))
        right.addView(label("기어 ${controller.gear.name} · 문 ${if (controller.door.open) "열림" else "닫힘"}", 20, muted).padTop(16))
        right.addView(label(controller.notice, 23, muted).padTop(14))
        gap(content, 24)
        val (recent, route) = pageColumns(content)
        card(recent) {
            minimumHeight = dp(if (carWide) 220 else 120)
            addView(label("최근 경고 기록", 26, light, true))
            val latest = WarningHistoryText.group(controller.history.records).firstOrNull()
            addView(label(latest?.let { WarningHistoryText.parent(it) } ?: "최근 30일 동안 기록된 경고가 없어요.", 30, muted).padTop(12))
        }
        if (!Features.sharedRoutes) card(route) {
            minimumHeight = dp(if (carWide) 220 else 120)
            addView(label("배터리와 충전구", 26, light, true))
            val energy = controller.energy
            addView(label(listOfNotNull(energy.socPercent?.let { "배터리 $it%" }, energy.rangeKm?.let { "약 ${formatKm(it)}km" })
                .joinToString(" · ").ifEmpty { "배터리 정보를 아직 받지 못했어요." } +
                "\n충전구 " + when (energy.chargePortOpen) { true -> "열림"; false -> "닫힘"; null -> "확인 못 함" }, 30, mint).padTop(12))
        }
        if (Features.sharedRoutes) card(route) {
            minimumHeight = dp(if (carWide) 220 else 120)
            addView(label("보낸 길 소식", 26, light, true))
            addView(label(routeStatus(), 30, mint).padTop(12))
            if (controller.arrival.queue.isNotEmpty()) addView(button("도착 소식 다시 보내기", false) { controller.retryArrivals() }.padTop(12))
        }
    }
    private fun consentScreen(content: LinearLayout) {
        if (!draftLoaded) { consentDraft = controller.privacy.current; arrivalModeDraft = controller.arrival.mode; draftLoaded = true }
        title(content, "내가 고른 소식만", "누구에게, 어떤 소식을 전할지\n언제든 직접 바꿀 수 있어요.")
        content.addView(button("소식 전하기 모두 끄기", true) {
            consentDraft = Consent(); controller.updateConsent(Consent(), forcePurge = true); render()
        })
        content.addView(label(if (controller.privacy.deletionPending) "이 기기에서는 전송을 막았어요. 서버 자료 삭제를 다시 시도하고 있어요." else "끄면 서버에 남은 공유 소식을 지워요. 경고 기록은 차 안에만 남아요.", 20, muted).padTop(10))
        gap(content, 18)
        val (choices, details) = pageColumns(content)
        card(choices) {
            addView(label("1  알릴 사람", 23, light, true))
            if (controller.children.isEmpty()) addView(label("가족을 연결하면 여기에서 고를 수 있어요.", 18, muted).padTop(10))
            controller.children.forEach { child ->
                addView(check(child.name, child.id in consentDraft.recipients) { checked ->
                    consentDraft = consentDraft.copy(recipients = if (checked) consentDraft.recipients + child.id else consentDraft.recipients - child.id)
                })
            }
            addView(button("연결한 가족 새로 불러오기", false) { controller.refresh() }.padTop(10))
        }
        gap(choices, 12)
        card(choices) {
            addView(label("2  전할 소식", 23, light, true))
            addView(check("자녀가 보낸 길에 도착하면 알리기", consentDraft.shareRouteArrival) {
                consentDraft = consentDraft.copy(shareRouteArrival = it)
            })
            if (Features.generalJourneySharing) {
            addView(check("출발했어요", consentDraft.shareDeparture) { consentDraft = consentDraft.copy(shareDeparture = it) })
            addView(check("잘 도착했어요", consentDraft.shareArrival) { consentDraft = consentDraft.copy(shareArrival = it) })
            }
            if (Features.warningSharing) addView(check("차량 경고 요약", consentDraft.shareAlerts) { consentDraft = consentDraft.copy(shareAlerts = it) })
            if (Features.locationSharing) {
            addView(check("현재 위치 공유 · 별도 선택", consentDraft.shareLocation) {
                consentDraft = consentDraft.copy(shareLocation = it, until = if (it && consentDraft.until == null) Instant.now().plusSeconds(3600) else consentDraft.until)
            })
            addView(label("기본은 좌표 없는 소식이에요. 이 모의 차량은 좌표를 수집하지 않아요.", 16, muted).padTop(6))
            }
            addView(label("도착 소식 보내는 방법", 23, light, true).padTop(16))
            addView(RadioGroup(this@MainActivity).apply {
                orientation = RadioGroup.VERTICAL
                ArrivalMode.entries.forEach { mode ->
                    addView(RadioButton(this@MainActivity).apply {
                        id = View.generateViewId()
                        text = if (mode == ArrivalMode.ASK_EVERY_TIME) "매번 물어보기 · 기본" else "자동으로 보내기"
                        textSize = if (carWide) 24f else 21f; setTextColor(light)
                        buttonTintList = android.content.res.ColorStateList.valueOf(mint)
                        minHeight = dp(76); isChecked = arrivalModeDraft == mode
                        setOnCheckedChangeListener { _, checked -> if (checked) arrivalModeDraft = mode }
                    })
                }
            })
            addView(label("매번 물어보기: 답하기 전까지 보내지 않아요. 답 없이 다시 출발하면 다음에 세울 때 한 번 더 여쭤봐요.\n자동으로 보내기: 수락한 길에 도착하면 정차 중 바로 전해요.", 21, muted).padTop(12))
        }
        gap(details, 12)
        card(details) {
            addView(label("3  알릴 기간", 23, light, true))
            addView(label(consentDraft.until?.let { "${DateTimeFormatter.ofPattern("M월 d일 HH:mm").withZone(ZoneId.systemDefault()).format(it)}까지" } ?: "직접 끌 때까지", 18, muted).padTop(8))
            addView(button("1시간 동안", false) { consentDraft = consentDraft.copy(until = Instant.now().plusSeconds(3600)); render() }.padTop(8))
            addView(button("오늘부터 24시간", false) { consentDraft = consentDraft.copy(until = Instant.now().plusSeconds(86400)); render() }.padTop(8))
            addView(button("직접 끌 때까지", false) { consentDraft = consentDraft.copy(until = null); render() }.padTop(8))
            addView(label("목적: 고른 가족에게 보낸 길의 도착 안부를 전해요. 목적지·도착 시각만 서버에 보관해요. 기간 종료 또는 철회 시 공유 자료를 삭제해요.", 20, muted).padTop(12))
            addView(button("이 내용에 동의하고 저장", true) {
                consentDraft = consentDraft.copy(
                    shareDeparture = consentDraft.shareDeparture && Features.generalJourneySharing,
                    shareArrival = consentDraft.shareArrival && Features.generalJourneySharing,
                    shareAlerts = consentDraft.shareAlerts && Features.warningSharing,
                    shareLocation = consentDraft.shareLocation && Features.locationSharing)
                controller.updateArrivalMode(arrivalModeDraft); controller.updateConsent(consentDraft); render()
            }.padTop(16))
            addView(label(if (consentDraft.recipients.isEmpty()) "받을 사람이 없으면 소식을 보내지 않아요." else "선택한 가족 ${consentDraft.recipients.size}명에게만 전해요.", 16, mint).padTop(8))
        }
        gap(details, 18)
        card(details) {
            addView(label("가족과 이어지기", 23, light, true))
            if (controller.session != null) {
                addView(button("가족 연결 번호 보기", false) {
                    showPairCode = true; controller.requestPairCode(); navigate("pairing")
                }.padTop(12))
                addView(button("가족 연결 다시 확인", false) { controller.refresh() }.padTop(12))
            } else {
                addView(input("부를 이름", "parentName", parentName) { parentName = it }.padTop(12))
                addView(button("가족 연결 시작", true) { controller.connect(parentName, serverInput) }.padTop(12))
            }
        }
    }
    private fun pairingScreen(content: LinearLayout) {
        title(content, "가족 연결 번호", "가족이 자녀 화면에서 번호를 입력하면 연결돼요.")
        card(content) {
            val code = controller.pairCode
            when {
                controller.pairCodeLoading -> addView(label("새 번호를 받고 있어요", 28, mint, true))
                code != null && code.remainingSeconds(Instant.now()) > 0 -> {
                    addView(label(if (showPairCode) "연결 번호  ${code.digits}" else "연결 번호  ••••••", 44, mint, true))
                    pairCountdown = label(code.remainingText(Instant.now()), 30, light, true).padTop(18)
                    addView(pairCountdown)
                    addView(label("10분 동안 한 번만 쓸 수 있어요. 번호는 가족에게만 전해 주세요.", 22, muted).padTop(18))
                    if (showPairCode) addView(button("번호 가리기", false) { showPairCode = false; render() }.padTop(20))
                    else addView(label("번호를 가렸어요. 다시 보려면 새 번호를 받아 주세요.", 20, muted).padTop(12))
                }
                controller.pairCodeError.isNotBlank() -> addView(label(controller.pairCodeError, 26, peach))
                else -> addView(label(if (code == null) "가족과 연결했거나 번호를 닫았어요." else "번호가 만료됐어요.", 28, light, true))
            }
            if (!controller.pairCodeLoading) addView(button("새 번호 받기", true) {
                showPairCode = true; controller.requestPairCode()
            }.padTop(24))
        }
        content.addView(button("설정으로 돌아가기", false) { showPairCode = false; navigate("consent") }.padTop(18))
    }
    private fun warningScreen(content: LinearLayout) {
        val advice = controller.adviceFor(selectedWarning)
        title(content, advice.title, "경고등이 전하는 뜻을\n차근차근 살펴볼게요.")
        card(content, if (advice.urgency == Urgency.STOP_SAFELY) danger else peach) {
            addView(label(advice.urgency.label, 21, dark, true))
            addView(label("무슨 뜻인가요", 17, dark, true).padTop(20))
            addView(label(advice.meaning, 27, dark, true).padTop(8))
            addView(label("지금 할 일", 17, dark, true).padTop(22))
            addView(label(advice.action, 24, dark).padTop(8))
        }
        gap(content, 16)
        if (selectedWarning == WarningCode.TPMS && Features.tireNumbers) {
            val readings = controller.tires
            val text = if (readings.pressures.isEmpty()) "공기압 수치를 아직 받지 못했어요." else
                readings.pressures.entries.joinToString("  ·  ") { (wheel, pressure) ->
                    "${wheel.label} ${String.format(Locale.KOREA, "%.1f", pressure)}${readings.unit?.let { " $it" } ?: ""}"
                }
            content.addView(label(text, 24, mint))
            if (readings.unit == null) content.addView(label("단위를 확인할 수 없어 수치만 보여 드려요.", 18, muted).padTop(8))
            gap(content, 16)
        }
        content.addView(button("안내 다시 듣기", true) { controller.ports.speaker.speak(advice.movingText) })
        content.addView(label("차종과 상황에 따라 뜻이 다를 수 있어요. 차량 설명서와 정비소 안내를 먼저 따라 주세요.", 18, muted).padTop(16))
        content.addView(button("오늘 상태로 돌아가기", false) { navigate("home") }.padTop(16))
    }
    private fun historyScreen(content: LinearLayout) {
        val fleet = controller.handoverSettings.mode == HandoverMode.FLEET
        if (historyTab == "trips" && !fleet) historyTab = "warnings"
        title(content, "기록", "차 상태만 이 차 안에 보관해요. 밖으로 보내지 않아요.")
        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOfNotNull("warnings" to "경고등", "pairs" to "인계 이력", ("trips" to "운행 표").takeIf { fleet }).forEach { (key, text) ->
            tabs.addView(Button(this).apply {
                this.text = text; textSize = if (carWide) 24f else 18f; isAllCaps = false; minHeight = dp(72)
                setTextColor(if (historyTab == key) dark else light)
                background = round(if (historyTab == key) mint else surface, 14)
                contentDescription = "$text 탭"
                setOnClickListener { historyTab = key; clearHistoryConfirm = false; scroll = null; render() }
            }, LinearLayout.LayoutParams(0, dp(76), 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
        }
        content.addView(tabs, LinearLayout.LayoutParams(-1, -2))
        gap(content, 18)
        when (historyTab) {
            "pairs" -> pairsTab(content)
            "trips" -> tripsTab(content)
            else -> warningsTab(content)
        }
    }
    private fun pairsTab(content: LinearLayout) {
        content.addView(label("인수·반납 한 쌍씩 · 최근 ${HandoverLedger.MAX_PAIRS}쌍까지 · 차 상태만", 20, muted))
        gap(content, 14)
        val pairs = controller.ledger.pairs.asReversed()
        if (pairs.isEmpty()) card(content) { addView(label("아직 인계 이력이 없어요.", 28, light)) }
        pairs.forEach { pair ->
            val (head, body) = ReturnText.pairSummary(pair)
            card(content) {
                addView(label(head, 26, light, true))
                addView(label(body, 22, mint).padTop(10))
            }
            gap(content, 12)
        }
    }
    private fun tripsTab(content: LinearLayout) {
        content.addView(label("법인·택시 모드에서 [돌려줬어요]를 누를 때마다 운행 1건을 적어요. 업무·개인 구분은 하지 않아요.", 20, muted))
        gap(content, 14)
        val months = ReturnText.months(controller.ledger.trips)
        if (months.isEmpty()) card(content) { addView(label("아직 운행 기록이 없어요.", 28, light)) }
        val weights = listOf(1.3f, 1.6f, 1.6f, 1.2f, 2.2f, 1.7f)
        fun row(cells: List<String>, header: Boolean = false) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false
                setPadding(dp(12), dp(14), dp(12), dp(14))
                background = round(if (header) dark else surface, 8)
            }
            cells.forEachIndexed { index, text ->
                line.addView(label(text, if (carWide) 22 else 15, if (header) mint else light, header).apply { setPadding(dp(6), 0, dp(6), 0) },
                    LinearLayout.LayoutParams(0, -2, weights[index]))
            }
            content.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        months.forEach { month ->
            content.addView(label(ReturnText.monthTitle(month), 26, light, true).padTop(10))
            gap(content, 10)
            row(listOf("날짜", "출발 km", "도착 km", "거리", "목적지", "인수 → 반납"), true)
            month.entries.forEach { row(ReturnText.tripCells(it)) }
        }
    }
    private fun warningsTab(content: LinearLayout) {
        content.addView(label("최근 30일 · 최대 200건 · ${HandoverText.RECORDING_SCOPE}", 20, muted))
        gap(content, 14)
        content.addView(button("정비사에게 보여주기", true) { navigate("mechanic") })
        gap(content, 18)
        val groups = WarningHistoryText.group(controller.history.records)
        if (groups.isEmpty()) card(content) { addView(label("아직 기록된 경고가 없어요.", 28, light)) }
        groups.forEach { group ->
            card(content) {
                addView(label(WarningHistoryText.parent(group), 28, light, true))
                addView(label("${group.first.reading.advice.urgency.label} · 짧은 반복은 함께 보여 드려요.", 21, mint).padTop(12))
            }
            gap(content, 14)
        }
        val due = if (controller.odometerKm.isFinite())
            MaintenanceRules.evaluate(controller.odometerKm.toLong(), controller.records).filter { it.isDue }.map { it.item.title }
            else emptyList()
        content.addView(label("참고: " + (if (due.isEmpty()) "정비 시기는 차량 설명서의 정비표를 먼저 따라 주세요." else
            "${due.joinToString()} 점검을 권해요. 차량 설명서의 정비표가 우선이에요."), 22, muted).padTop(10))
        content.addView(button("경고 기록 지우기", false) { clearHistoryConfirm = true; render() }.padTop(22))
        if (clearHistoryConfirm) {
            gap(content, 16)
            card(content, peach) {
                addView(label("이 차의 경고 기록을 모두 지울까요?", 28, dark, true))
                addView(button("네, 기록을 지울게요", true) { controller.clearWarningHistory(); clearHistoryConfirm = false; render() }.padTop(14))
                addView(button("기록을 남겨둘게요", false) { clearHistoryConfirm = false; render() }.padTop(12))
            }
        }
    }
    private fun mechanicScreen(content: LinearLayout) {
        title(content, "정비사에게 보여주기", "차량에서 관찰한 경고 기록이에요. 실제 고장 진단은 정비사에게 확인해 주세요.")
        content.addView(button("쉬운 말 기록으로 돌아가기", false) { navigate("history") })
        gap(content, 20)
        fun row(cells: List<String>, header: Boolean = false) {
            val weights = listOf(3.1f, 2.3f, 1.1f, 1.9f, 1.9f)
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false
                setPadding(dp(12), dp(16), dp(12), dp(16))
                background = round(if (header) dark else surface, 8)
            }
            cells.forEachIndexed { index, text ->
                line.addView(label(text, if (carWide) 23 else 16, if (header) mint else light, header).apply {
                    setPadding(dp(8), 0, dp(8), 0)
                }, LinearLayout.LayoutParams(0, -2, weights[index]))
            }
            content.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
        row(listOf("경고 · 색/위험도", "발생 → 해제", "지속", "주행거리", "주행 상태"), true)
        val date = DateTimeFormatter.ofPattern("MM.dd HH:mm:ss").withZone(ZoneId.systemDefault())
        controller.history.records.sortedByDescending { it.started.at }.forEach { record ->
            val reading = record.reading
            fun distance(moment: WarningMoment?) = moment?.odometerKm?.let { "${formatKm(it)} km" } ?: "미수신"
            fun state(moment: WarningMoment) = "기어 ${moment.gear.name} · ${if (moment.moving) "주행 중" else "정차 중"}"
            row(listOf(
                WarningHistoryText.mechanicDetails(reading),
                "${date.format(record.started.at)}\n→ ${record.ended?.let { date.format(it.at) } ?: "아직 켜짐"}",
                WarningHistoryText.duration(record.duration),
                "${distance(record.started)}\n→ ${record.ended?.let { distance(it) } ?: "—"}",
                "${state(record.started)}\n→ ${record.ended?.let { state(it) } ?: "—"}",
            ))
        }
        if (controller.history.records.isEmpty()) content.addView(label("기록된 경고가 없어요.", 28, light).padTop(18))
    }
    private fun routeStatus(): String {
        if (controller.arrivalNotice.isNotBlank()) return controller.arrivalNotice
        if (controller.arrival.queue.isNotEmpty()) return "아직 보내지 못한 도착 소식 ${controller.arrival.queue.size}건을 보관하고 있어요."
        val destination = controller.inbox.accepted()
        if (destination != null) return "${destination.name} · 안내 요청을 받았어요.\n${destination.note}"
        val arrived = controller.inbox.snapshot().lastOrNull { it.status == "arrived" }
        return arrived?.let { "${it.name}에 잘 도착했다고 전했어요." } ?: "아직 수락한 길이 없어요."
    }
    private fun arrivalScreen(content: LinearLayout) {
        val destination = controller.arrivedDestination ?: return
        renderedArrivalId = destination.id
        title(content, "잘 도착하셨어요", destination.name)
        card(content, mint) {
            addView(label("${controller.recipientLabel}께 도착 소식을 보낼까요?", if (carWide) 44 else 30, dark, true).apply {
                gravity = Gravity.CENTER
            })
            val reminder = if ((controller.arrival.prompt?.reasksRemaining ?: 0) > 0)
                "답 없이 다시 출발하면 다음에 세울 때 한 번 더 여쭤볼게요." else
                "이번에도 답 없이 출발하면 소식을 보내지 않고 마칠게요."
            addView(label("답하기 전까지 보내지 않아요.\n$reminder", 28, dark).padTop(24).apply { gravity = Gravity.CENTER })
            addView(button("보낼게요", true) { controller.confirmArrival() }.apply {
                setTextColor(mint); background = round(dark, 14)
                textSize = if (carWide) 32f else 26f
                minHeight = dp(104); minimumHeight = minHeight
            }.padTop(30))
            addView(button("이번엔 안 보낼게요", false) { controller.skipArrival() }.apply {
                setTextColor(dark); background = round(mint, 14).apply { setStroke(dp(2), dark) }
                textSize = 22f
            }.padTop(18))
        }
    }
    /** F3: parked only (render() shows the driving card otherwise). Vehicle state before -> now. */
    private fun returnScreen(content: LinearLayout) {
        val summary = controller.returnCard() ?: return
        content.addView(label("차를 돌려줄 때 · 정차 중", 18, mint, true))
        content.addView(label(summary.title, if (carWide) 40 else 30, light, true).padTop(10))
        content.addView(label("인수 때 → 지금", 22, muted).padTop(8))
        gap(content, 18)
        val (left, right) = pageColumns(content)
        val half = (summary.lines.size + 1) / 2
        summary.lines.forEachIndexed { index, line ->
            val column = if (index < half) left else right
            val alert = line.label == "새로 켜진 경고등" && line.text != "0개"
            card(column, if (alert) peach else surface) {
                addView(label(line.label, 17, if (alert) dark else mint, true))
                addView(label(line.text, if (carWide) 28 else 22, if (alert) dark else light, true).padTop(8))
            }
            gap(column, 12)
        }
        gap(content, 8)
        // Rental/car-share: no note between strangers (HandoverMode.noteOn).
        if (controller.handoverSettings.mode.noteOn) card(content) {
            addView(label("다음 사람에게 한마디 · 선택", 20, mint, true))
            val saved = controller.ledger.note
            if (noteEditing) {
                addView(input("글 한 줄 (최대 ${ReturnText.NOTE_MAX}자)", "returnNote", noteDraft) { noteDraft = it }.apply {
                    filters = arrayOf(android.text.InputFilter.LengthFilter(ReturnText.NOTE_MAX))
                    textSize = if (carWide) 26f else 20f
                }.padTop(12))
                val (save, cancel) = pageColumns(this)
                save.addView(button("한마디 저장", true) {
                    controller.saveReturnNote(noteDraft); noteEditing = false
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken, 0)
                    render()
                }.padTop(12))
                cancel.addView(button("취소", false) { noteEditing = false; render() }.padTop(12))
            } else {
                addView(label(saved?.let { "남긴 한마디: $it" } ?: "다음에 타는 사람의 인계 카드에 '이전 운전자 한마디'로 보여요.",
                    if (carWide) 24 else 20, light).padTop(10))
                addView(button(if (saved == null) "다음 사람에게 한마디" else "한마디 고치기", false) {
                    noteDraft = saved.orEmpty(); noteEditing = true; render()
                }.padTop(12))
            }
        }
        gap(content, 16)
        val (done, later) = pageColumns(content)
        done.addView(button("돌려줬어요", true) { noteEditing = false; controller.confirmReturn() }.apply {
            textSize = if (carWide) 30f else 24f; minHeight = dp(96); minimumHeight = minHeight
        })
        later.addView(button("나중에", false) { noteEditing = false; controller.laterReturn() }.apply {
            textSize = if (carWide) 30f else 24f; minHeight = dp(96); minimumHeight = minHeight
        }.padTop(if (carWide) 0 else 12))
        content.addView(label("[돌려줬어요]를 누르면 지금 차 상태만 인수 기록과 한 쌍으로 '인계 이력'에 남겨요." +
            if (controller.handoverSettings.mode == HandoverMode.FLEET) " 법인·택시 모드라 운행 표에도 1건을 적어요." else "", 18, muted).padTop(12))
    }
    private fun handoverScreen(content: LinearLayout) {
        val handover = controller.handoverCard()
        content.addView(label("차를 넘겨받는 순간 · 정차 중", 18, mint, true))
        content.addView(label(handover.greeting, if (carWide) 40 else 30, light, true).padTop(10))
        gap(content, 18)
        val (left, right) = pageColumns(content)
        val half = (handover.lines.size + 1) / 2
        handover.lines.forEachIndexed { index, line ->
            val column = if (index < half) left else right
            val alert = line.label == "지금 켜진 경고등" && controller.warnings.isNotEmpty()
            card(column, if (alert) peach else surface) {
                addView(label(line.label, 17, if (alert) dark else mint, true))
                addView(label(line.text, if (carWide) 26 else 22, if (alert) dark else light, true).padTop(8))
            }
            gap(column, 12)
        }
        gap(content, 8)
        val (accept, tour) = pageColumns(content)
        accept.addView(button("인수했어요", true) { controller.acceptHandover() }.apply {
            textSize = if (carWide) 30f else 24f; minHeight = dp(96); minimumHeight = minHeight
        })
        if (controller.tourOffered) tour.addView(button("처음 타요", false) { controller.requestTour() }.apply {
            textSize = if (carWide) 30f else 24f; minHeight = dp(96); minimumHeight = minHeight
        }.padTop(if (carWide) 0 else 12))
        content.addView(button("다시 듣기", false) { controller.replayHandover() }.padTop(12))
        content.addView(label("[인수했어요]를 누르면 지금 차 상태(시각·배터리·누적 거리·켜진 경고등·문·충전구)만 이 차 안에 남겨요.", 18, muted).padTop(12))
    }
    /** F2: one mission per screen, large type, parked only (render() shows the driving card otherwise). */
    private fun tourScreen(content: LinearLayout) {
        val machine = controller.tour ?: return
        val total = machine.missions.size
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(label("처음 3분 투어 · 정차 중", 20, mint, true), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(label("통과 ${machine.passed} · 남음 ${machine.remaining}", 24, light, true).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(-2, -2))
        content.addView(head)
        gap(content, 18)
        val passedMission = controller.tourPassedMission
        when {
            controller.tourCelebrating && passedMission != null -> card(content, mint) {
                gravity = Gravity.CENTER_HORIZONTAL
                val elapsed = (SystemClock.elapsedRealtime() - controller.tourPassedAt).coerceIn(0, 500)
                addView(CheckView(this@MainActivity).also { it.circle = dark; it.stroke = mint; it.animateFrom(elapsed / 500f, 500 - elapsed) },
                    LinearLayout.LayoutParams(dp(if (carWide) 260 else 180), dp(if (carWide) 260 else 180)).apply { gravity = Gravity.CENTER_HORIZONTAL })
                addView(label("좋아요. 통과했어요.", if (carWide) 52 else 34, dark, true).apply { gravity = Gravity.CENTER }.padTop(18))
                addView(label("미션 ${machine.missions.indexOf(passedMission) + 1} · " + passedMission.title.replace("\n", " "), 24, dark).apply { gravity = Gravity.CENTER }.padTop(10))
            }
            machine.finished -> {
                card(content, mint) {
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(label(TourPlan.DONE.replace(". ", ".\n"), if (carWide) 60 else 38, dark, true).apply { gravity = Gravity.CENTER })
                    addView(label("통과 ${machine.passed} · 남음 ${machine.remaining}", if (carWide) 30 else 24, dark, true).apply { gravity = Gravity.CENTER }.padTop(20))
                    if (machine.end == TourEnd.SKIPPED && machine.remaining > 0)
                        addView(label("남은 미션은 건너뛰었어요. 설정에서 언제든 다시 할 수 있어요.", 22, dark).apply { gravity = Gravity.CENTER }.padTop(10))
                }
                content.addView(button("시작하기", true) { controller.finishTour(); page = "home"; scroll = null; render() }.apply {
                    textSize = if (carWide) 34f else 26f; minHeight = dp(110); minimumHeight = minHeight
                }.padTop(24))
            }
            else -> {
                val mission = machine.current ?: return
                card(content) {
                    addView(label("미션 ${machine.passed + 1} / $total", 22, mint, true))
                    addView(label(mission.title, if (carWide) 60 else 38, light, true).padTop(14))
                    addView(label(mission.hint, if (carWide) 26 else 22, muted).padTop(14))
                    if (mission == TourMission.STEERING) steeringGauge(this, machine)
                }
                content.addView(button("건너뛰기", false) { controller.skipTour() }.apply {
                    textSize = if (carWide) 28f else 22f; minHeight = dp(92); minimumHeight = minHeight
                }.padTop(24))
                content.addView(label("기어를 D로 바꾸면 투어는 바로 끝나요. 결과는 저장하지 않아요.", 18, muted).padTop(12))
            }
        }
    }
    private fun steeringGauge(parent: LinearLayout, machine: TourMachine) {
        val angle = controller.steeringAngle
        val side = when { angle == null -> ""; angle < 0 -> " · 왼쪽"; angle > 0 -> " · 오른쪽"; else -> " · 가운데" }
        parent.addView(label(angle?.let { String.format(Locale.KOREA, "%.0f°", it) + side } ?: "핸들 각도를 아직 받지 못했어요",
            if (carWide) 80 else 48, light, true).apply { gravity = Gravity.CENTER }.padTop(24))
        parent.addView(GaugeView(this).apply {
            this.angle = angle; left = machine.leftReached; right = machine.rightReached; limit = TourPlan.STEERING_LIMIT_DEGREES
            reached = mint; idle = muted; marker = peach; track = dark
        },
            LinearLayout.LayoutParams(-1, dp(if (carWide) 110 else 80)).apply { topMargin = dp(18) })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(label("왼쪽 끝 ${if (machine.leftReached) "통과" else "남음"}", 28, if (machine.leftReached) mint else muted, true),
            LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(label("오른쪽 끝 ${if (machine.rightReached) "통과" else "남음"}", 28, if (machine.rightReached) mint else muted, true).apply { gravity = Gravity.END },
            LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
    }
    private fun settingsScreen(content: LinearLayout) {
        title(content, "인계 설정", "운전자가 바뀔 때 차가 자기 상태를\n먼저 알려 드려요.")
        val current = controller.handoverSettings
        val (left, right) = pageColumns(content)
        card(left) {
            addView(label("쓰는 방식", 23, light, true))
            addView(RadioGroup(this@MainActivity).apply {
                orientation = RadioGroup.VERTICAL
                HandoverMode.settingsOrder.forEach { mode ->
                    val choice = if (mode == HandoverMode.RENTAL) "${mode.label} · 기본" else mode.label
                    addView(RadioButton(this@MainActivity).apply {
                        id = View.generateViewId()
                        text = choice
                        textSize = if (carWide) 24f else 21f; setTextColor(light)
                        buttonTintList = android.content.res.ColorStateList.valueOf(mint)
                        minHeight = dp(72); isChecked = current.mode == mode
                        setOnCheckedChangeListener { _, checked -> if (checked && controller.handoverSettings.mode != mode) controller.updateHandoverSettings(controller.handoverSettings.withMode(mode)) }
                    })
                }
            })
            addView(label("끄기를 고르면 인계 카드와 차 상태 기록을 멈춰요.", 18, muted).padTop(8))
        }
        card(right) {
            addView(label("말하는 길이", 23, light, true))
            addView(RadioGroup(this@MainActivity).apply {
                orientation = RadioGroup.VERTICAL
                HandoverLength.entries.forEach { length ->
                    addView(RadioButton(this@MainActivity).apply {
                        id = View.generateViewId()
                        text = length.label
                        textSize = if (carWide) 24f else 21f; setTextColor(light)
                        buttonTintList = android.content.res.ColorStateList.valueOf(mint)
                        minHeight = dp(72); isChecked = current.length == length
                        setOnCheckedChangeListener { _, checked -> if (checked && controller.handoverSettings.length != length) controller.updateHandoverSettings(controller.handoverSettings.copy(length = length)) }
                    })
                }
            })
            addView(label("화면에는 늘 모든 줄을 보여 드려요.", 18, muted).padTop(8))
        }
        gap(content, 18)
        card(content) {
            addView(label("마지막 인수 기록", 23, light, true))
            addView(label(controller.lastHandover?.let { HandoverText.snapshotSummary(it) } ?: "아직 인수 기록이 없어요.", 22, muted).padTop(10))
            addView(label("차 상태만 이 차 안에 암호화해 저장해요.", 18, muted).padTop(8))
        }
        if (controller.tourMissions.isNotEmpty()) {
            gap(content, 18)
            card(content) {
                addView(label("처음 3분 투어", 23, light, true))
                addView(label("핸들·브레이크·방향지시등을 직접 움직여 보는 미션 ${controller.tourMissions.size}개예요. 정차 중(P)에만 할 수 있어요.", 22, muted).padTop(10))
                addView(button("투어 다시 하기", true) { controller.restartTour() }.padTop(14))
            }
        }
        gap(content, 18)
        card(content) {
            addView(label("기록 모두 지우기", 23, light, true))
            addView(label("경고등 기록·인계 이력·운행 표·마지막 인수 기록·남긴 한마디를 이 차에서 지워요. 설정은 그대로 둬요.", 20, muted).padTop(10))
            if (!clearAllConfirm) addView(button("모두 지우기", false) { clearAllConfirm = true; render() }.padTop(14))
            else {
                addView(label("정말 모두 지울까요? 되돌릴 수 없어요.", 24, peach, true).padTop(14))
                addView(button("네, 모두 지울게요", true) { controller.clearAllRecords(); clearAllConfirm = false; render() }.padTop(12))
                addView(button("남겨둘게요", false) { clearAllConfirm = false; render() }.padTop(10))
            }
        }
        if (Features.familyConnection) content.addView(button("소식 전하기 설정", false) { navigate("consent") }.padTop(18))
    }
    private fun maintenanceScreen(content: LinearLayout) {
        title(content, "차를 돌보는 시간", "현재 ${formatKm(controller.odometerKm)} km\n마지막 정비 기록부터 살펴봐요.")
        content.addView(label("아래 주기는 시연용 예시예요. 차종·운행 조건에 맞는 제조사 정비표가 우선이에요.", 18, peach))
        if (!controller.odometerKm.isFinite()) { content.addView(label("주행거리를 아직 받지 못했어요.", 24, muted).padTop(20)); return }
        MaintenanceRules.evaluate(controller.odometerKm.toLong(), controller.records).forEach { due ->
            gap(content, 14)
            card(content, if (due.isDue) peach else surface) {
                val color = if (due.isDue) dark else light
                addView(label(due.item.title, 25, color, true))
                addView(label(when {
                    due.lastKm == null -> "마지막 정비 기록이 없어요."
                    due.isDue -> "점검 시기예요. 정비소 점검을 권해요."
                    else -> "예시 기준까지 ${due.remainingKm?.coerceAtLeast(0)} km 남았어요."
                }, 20, color).padTop(10))
                addView(label("예시 간격 ${due.item.intervalKm} km" + (due.lastKm?.let { " · 마지막 $it km" } ?: ""), 16, color).padTop(8))
                addView(button("지금 거리로 정비 완료 기록", true) { controller.record(due.item) }.padTop(14))
            }
        }
    }
    private fun destinationsScreen(content: LinearLayout) {
        title(content, "함께 가는 길", "가족이 보내온 길도\n출발은 내가 고를 때.")
        if (controller.inbox.all().isEmpty()) card(content) {
            addView(label("아직 기다리는 길이 없어요", 25, light, true))
            addView(label("가족이 목적지를 보내면 정차 중에 여쭤볼게요.", 20, muted).padTop(10))
        }
        controller.inbox.all().forEach { destination ->
            gap(content, 12)
            card(content, mint) {
                addView(label(if (destination.status == "deferred") "나중에 고르기로 했어요" else "가족이 길을 보내왔어요", 16, dark))
                addView(label(destination.name, 28, dark, true).padTop(10))
                addView(label(destination.address, 18, dark).padTop(8))
                if (destination.note.isNotBlank()) addView(label(destination.note, 20, dark).padTop(10))
                addView(button("좋아요 · 안내 요청", true) { controller.respond(destination, DestinationIntent.ACCEPT) }.padTop(18))
                addView(button("나중에 할게요", true) { controller.respond(destination, DestinationIntent.DEFER) }.padTop(8))
                addView(button("이번에는 받지 않기", true) { controller.respond(destination, DestinationIntent.DECLINE) }.padTop(8))
                if (BuildConfig.DEBUG) addView(button("음성으로 답하기 · 모의 입력", true) { controller.listen(destination); navigate("console") }.padTop(8))
            }
        }
        gap(content, 16)
        content.addView(label(controller.notice, 19, muted))
        content.addView(label(controller.ports.navigator.status, 19, mint).padTop(12))
        if (Features.familyVoiceMessages) content.addView(button("가족이 남긴 목소리", false) { navigate("messages") }.padTop(16))
        if (controller.arrivalNotice.isNotBlank() || controller.inbox.accepted() != null) content.addView(label(routeStatus(), 26, mint).padTop(18))
    }
    private fun messagesScreen(content: LinearLayout) {
        title(content, "반가운 목소리", "바쁘지 않은 지금,\n가족의 말을 들어볼까요?")
        if (controller.messages.isEmpty()) card(content) {
            addView(label("기다리는 목소리가 없어요", 24, light, true))
            addView(label("가족이 남긴 메시지는 정차 중에 재생할 수 있어요.", 20, muted).padTop(10))
        }
        controller.messages.forEach { message ->
            gap(content, 12)
            card(content) {
                val sender = controller.children.firstOrNull { it.id == message.fromChildId }?.name ?: "가족"
                addView(label("$sender 님이 남긴 말", 24, light, true))
                addView(label("${message.durationSec}초 · 이 기기에 파일로 남기지 않아요", 17, muted).padTop(8))
                addView(button("목소리 듣기", true) { controller.play(message) }.padTop(12))
            }
        }
        gap(content, 16)
        content.addView(button("예시 메시지 듣기 · 합성 음성", false) { controller.playDemoMessage() })
        content.addView(label("${controller.notice}\n${controller.ports.speaker.status}", 18, muted).padTop(16))
    }
    private fun consoleScreen(content: LinearLayout) {
        if (!BuildConfig.DEBUG) return
        title(content, "모의 콘솔", "실제 차량 없이 살펴보는\n시연용 입력이에요.")
        if (BuildConfig.DEBUG) {
            card(content) {
                addView(label("개발 연결 설정", 23, light, true))
                addView(input("서버 주소", "serverUrl", serverInput) { serverInput = it }.padTop(12))
                addView(button("서버 연결 적용", true) { controller.connect(parentName, serverInput) }.padTop(12))
            }
            gap(content, 18)
        }
        if (!controller.ports.simulated) {
            content.addView(label("현재 Pleos 차량 신호를 받고 있어요. 모의 입력은 아래에서 따로 선택할 수 있어요.", 24, mint))
            content.addView(button("가짜 차량 입력으로 전환", true) {
                startActivity(Intent(this, MainActivity::class.java).putExtra("vehicleMode", "fake").putExtra("screen", "console"))
            }.padTop(16))
            return
        }
        card(content) {
            addView(label("차량 상태", 23, light, true))
            addView(label("${formatKm(controller.odometerKm)} km · 문 ${if (controller.fake.doorState.open) "열림" else "닫힘·잠금"}\n브레이크 ${if (controller.fake.brakePressed) "밟음" else "놓음"}", 20, muted).padTop(10))
            addView(button("문 닫고 잠그기 / 문 열기", false) { controller.fake.setDoor(!controller.fake.doorState.open); scheduleRender() }.padTop(12))
            addView(button("브레이크 밟기 / 놓기", false) { controller.fake.setBrake(!controller.fake.brakePressed); scheduleRender() }.padTop(8))
            addView(button("기어 P / D 바꾸기", false) {
                controller.fake.setGear(if (controller.fake.gearSelection == GearSelection.PARK) GearSelection.DRIVE else GearSelection.PARK)
                scheduleRender()
            }.padTop(8))
            addView(button("주행거리 +1 km", false) { controller.fake.setOdometer(controller.fake.odometerKm + 1); scheduleRender() }.padTop(8))
            addView(button("주행거리 12,000 km로 맞추기", false) { controller.fake.setOdometer(12_000.0); scheduleRender() }.padTop(8))
            addView(button("내비 도착 콜백", false) { controller.fake.arrive(); scheduleRender() }.padTop(8))
            addView(label("출발: 문 닫힘·잠금 → 브레이크 밟음 → 2초 뒤 브레이크 놓기 → 거리 증가. 도착: 내비 콜백 또는 거리 20초 정지 + 문 열림 2초.", 16, muted).padTop(12))
        }
        gap(content, 12)
        card(content) {
            addView(label("경고등 켜기 / 끄기", 23, light, true))
            listOf(WarningCode.TPMS, WarningCode.ENGINE, WarningCode.ABS, WarningCode.ENGINE_OIL_PRESSURE, WarningCode.MASTER_WARNING).forEach { code ->
                val advice = WarningDictionary.lookup(code)
                addView(button("${if (code in controller.warnings) "●" else "○"} ${advice.title}", false) {
                    controller.fake.toggleWarning(code); selectedWarning = code; scheduleRender()
                }.padTop(8))
            }
            addView(button("타이어 저압 폴링 값 바꾸기", false) { controller.fake.tireLow = controller.fake.tireLow != true; scheduleRender() }.padTop(8))
            addView(button("선택한 경고 뜻 보기", true) { navigate("warning") }.padTop(8))
        }
        gap(content, 12)
        card(content) {
            addView(label("인계 시연 입력", 23, light, true))
            val energy = controller.fake.energyState
            addView(label("배터리 ${energy.socPercent ?: "—"}% · 충전구 ${if (energy.chargePortOpen == true) "열림" else "닫힘"} · 트렁크 ${if (controller.fake.doorState.trunkOpen == true) "열림" else "닫힘"}", 20, muted).padTop(10))
            addView(button("배터리 54% · 누적 12,345 km", false) {
                controller.fake.setBattery(54, 210.0); controller.fake.setOdometer(12_345.0); scheduleRender()
            }.padTop(8))
            addView(button("충전구 열기 / 닫기", false) { controller.fake.setChargePort(controller.fake.energyState.chargePortOpen != true); scheduleRender() }.padTop(8))
            addView(button("트렁크 열기 / 닫기", false) { controller.fake.setTrunk(controller.fake.doorState.trunkOpen != true); scheduleRender() }.padTop(8))
        }
        if (Features.sharedRoutes) gap(content, 12)
        if (Features.sharedRoutes) card(content) {
            addView(label("가족이 보내온 길 · 음성", 23, light, true))
            addView(button("모의 목적지 받기", false) { controller.demoDestination() }.padTop(12))
            addView(input("음성에서 바뀐 문장", "stt", sttInput) { sttInput = it }.padTop(10))
            addView(button("이 문장을 들려주기", true) { controller.injectText(sttInput) }.padTop(8))
            addView(label("들은 말: ${controller.voiceText.ifBlank { "아직 없어요" }}\n${controller.notice}", 18, muted).padTop(10))
            addView(button("받은 목적지 보기", false) { navigate("destinations") }.padTop(8))
        }
        gap(content, 12)
        content.addView(button("시연 정비 기록 · 엔진오일 2,000 km", false) { controller.record(MaintenanceItem.ENGINE_OIL, 2_000) })
        controller.records[MaintenanceItem.ENGINE_OIL]?.let {
            content.addView(label("저장한 엔진오일 ${it} km", 18, muted).padTop(8))
        }
        content.addView(button("주행 중 화면으로 전환", true) { controller.fake.setRestriction(true) }.padTop(12))
        content.addView(label("주행 화면에서는 모든 입력이 숨겨져요. 시연 복귀는 adb의 restricted=false 입력으로 할 수 있어요.", 16, muted).padTop(12))
    }
    private fun driving() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken, 0)
        val advice = controller.warningAdvice
        val container = column(28).apply { gravity = Gravity.CENTER; setPadding(dp(28), dp(40), dp(28), dp(40)) }
        val card = column(28).apply {
            gravity = Gravity.CENTER
            background = round(if (advice?.urgency == Urgency.STOP_SAFELY) danger else mint)
            minimumHeight = dp(260)
        }
        val text = advice?.movingText ?: "운전에 집중해 주세요. 안내는 정차 후 보여 드릴게요."
        card.addView(label(text, if (carWide) 52 else 32, dark, true).apply { gravity = Gravity.CENTER; setLineSpacing(dp(6).toFloat(), 1.1f) })
        if (Features.sharedRoutes) controller.inbox.accepted()?.note?.takeIf { it.isNotBlank() }?.let {
            card.addView(label("자녀의 한마디\n$it", if (carWide) 36 else 24, dark).padTop(28).apply { gravity = Gravity.CENTER })
        }
        container.addView(card, LinearLayout.LayoutParams(-1, -2))
        root.addView(container, LinearLayout.LayoutParams(-1, -1))
        window.decorView.clearFocus()
    }
    private fun navigation(): View = LinearLayout(this).apply {
        orientation = if (carWide) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        setPadding(dp(8), dp(6), dp(8), dp(8))
        setBackgroundColor(backgroundColor)
        listOfNotNull("home" to "오늘", "history" to "기록", ("destinations" to "함께").takeIf { Features.sharedRoutes }, "settings" to "설정").let { tabs ->
            val visible = if (BuildConfig.DEBUG) tabs + ("console" to "모의") else tabs
            visible.forEach { (target, text) ->
                addView(Button(this@MainActivity).apply {
                    this.text = text; textSize = if (carWide) 24f else 17f; isAllCaps = false; minHeight = dp(72)
                    setTextColor(if (page == target) dark else light)
                    background = round(if (page == target) mint else surface, 14)
                    setOnClickListener { navigate(target) }
                    contentDescription = "$text 화면"
                }, (if (carWide) LinearLayout.LayoutParams(-1, dp(80)) else LinearLayout.LayoutParams(0, dp(56), 1f)).apply {
                    setMargins(dp(4), if (carWide) dp(8) else 0, dp(4), 0)
                })
            }
        }
    }
    private fun title(parent: LinearLayout, text: String, subtitle: String) {
        parent.addView(label(text, 32, light, true))
        parent.addView(label(subtitle, 20, muted).padTop(12)); gap(parent, 22)
    }
    private fun column(padding: Int = 0) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }
    private fun pageColumns(parent: LinearLayout): Pair<LinearLayout, LinearLayout> {
        if (!carWide) return parent to parent
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        val left = column(); val right = column()
        row.addView(left, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(24) })
        row.addView(right, LinearLayout.LayoutParams(0, -2, 1f))
        parent.addView(row, LinearLayout.LayoutParams(-1, -2))
        return left to right
    }
    private fun label(text: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size.toFloat() * if (carWide) 1.1f else 1f; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = false; setLineSpacing(dp(3).toFloat(), 1.1f)
        layoutParams = LinearLayout.LayoutParams(-1, -2)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    private fun button(text: String, primary: Boolean, action: () -> Unit) = Button(this).apply {
        this.text = text; textSize = if (carWide) 24f else 20f; isAllCaps = false
        minHeight = dp(if (carWide) 76 else 60); minimumHeight = minHeight
        setPadding(dp(14), dp(12), dp(14), dp(12))
        setTextColor(if (primary) dark else light)
        background = round(if (primary) mint else Color.rgb(46, 77, 72), 14)
        layoutParams = LinearLayout.LayoutParams(-1, -2)
        setOnClickListener { action() }
    }
    private fun check(text: String, checked: Boolean, onChange: (Boolean) -> Unit) = CheckBox(this).apply {
        this.text = text; textSize = 21f; setTextColor(light); isChecked = checked
        buttonTintList = android.content.res.ColorStateList.valueOf(mint)
        minHeight = dp(60); setPadding(0, dp(10), 0, dp(10))
        setOnCheckedChangeListener { _, value -> onChange(value) }
    }
    private fun input(hint: String, key: String, value: String, update: (String) -> Unit) = EditText(this).apply {
        this.hint = hint; tag = key; setText(value); textSize = 20f; setTextColor(light); setHintTextColor(muted)
        minHeight = dp(60); setSingleLine(true); setSelectAllOnFocus(false)
        setPadding(dp(12), dp(12), dp(12), dp(12)); background = round(backgroundColor, 10)
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { update(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: Editable?) = Unit
        })
    }
    private fun card(parent: LinearLayout, color: Int = surface, build: LinearLayout.() -> Unit) {
        parent.addView(column(22).apply { background = round(color); build() }, LinearLayout.LayoutParams(-1, -2))
    }
    private fun round(color: Int, radius: Int = 24) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    private fun gap(parent: LinearLayout, size: Int) { parent.addView(Space(this), LinearLayout.LayoutParams(1, dp(size))) }
    private fun <T : View> T.padTop(amount: Int): T {
        val params = (layoutParams as? LinearLayout.LayoutParams) ?: LinearLayout.LayoutParams(-1, -2)
        params.topMargin = dp(amount); layoutParams = params; return this
    }
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun formatKm(value: Double): String = if (value.isFinite()) String.format(Locale.KOREA, "%,.0f", value) else "—"
}

/** Steering gauge: -540..+540 degrees, both ±limit zones, a marker at the current calibrated angle. */
private class GaugeView(context: Context) : View(context) {
    var angle: Float? = null
    var left = false
    var right = false
    var limit = 400f
    var reached = Color.GREEN
    var idle = Color.GRAY
    var marker = Color.WHITE
    var track = Color.BLACK
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val span = 540f
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = h * 0.18f
        fun x(deg: Float) = w / 2 + (deg.coerceIn(-span, span) / span) * (w / 2 - r)
        val top = h * 0.32f; val bottom = h * 0.68f
        paint.color = track; rect.set(0f, top, w, bottom); canvas.drawRoundRect(rect, r, r, paint)
        paint.color = if (left) reached else idle; rect.set(0f, top, x(-limit), bottom); canvas.drawRoundRect(rect, r, r, paint)
        paint.color = if (right) reached else idle; rect.set(x(limit), top, w, bottom); canvas.drawRoundRect(rect, r, r, paint)
        paint.color = idle; canvas.drawRect(w / 2 - 2f, top - h * 0.1f, w / 2 + 2f, bottom + h * 0.1f, paint)
        angle?.let { paint.color = marker; canvas.drawCircle(x(it), h / 2, h * 0.42f, paint) }
    }
}

/** The 0.5 s pass check: a circle with a check stroke drawn from the given start to complete. */
private class CheckView(context: Context) : View(context) {
    var circle = Color.BLACK
    var stroke = Color.WHITE
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val part = Path()
    private val measure = PathMeasure()
    private var progress = 0f
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    fun animateFrom(start: Float, remainingMillis: Long) {
        progress = start.coerceIn(0f, 1f)
        if (remainingMillis <= 0) { progress = 1f; return }
        ValueAnimator.ofFloat(progress, 1f).apply {
            duration = remainingMillis
            addUpdateListener { progress = it.animatedValue as Float; invalidate() }
            start()
        }
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val size = minOf(w, h).toFloat(); val cx = w / 2f; val cy = h / 2f
        path.reset()
        path.moveTo(cx - size * 0.22f, cy + size * 0.01f); path.lineTo(cx - size * 0.06f, cy + size * 0.17f); path.lineTo(cx + size * 0.24f, cy - size * 0.15f)
        measure.setPath(path, false)
    }
    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        val scale = 0.6f + 0.4f * minOf(1f, progress * 2f)
        paint.style = Paint.Style.FILL; paint.color = circle
        canvas.drawCircle(width / 2f, height / 2f, size / 2 * scale, paint)
        part.reset()
        measure.getSegment(0f, measure.length * ((progress - 0.3f) / 0.7f).coerceIn(0f, 1f), part, true)
        paint.style = Paint.Style.STROKE; paint.color = stroke; paint.strokeWidth = size * 0.09f
        paint.strokeCap = Paint.Cap.ROUND; paint.strokeJoin = Paint.Join.ROUND
        canvas.drawPath(part, paint)
    }
}
