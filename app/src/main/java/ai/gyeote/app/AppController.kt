package ai.gyeote.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import ai.gyeote.app.data.*
import ai.gyeote.app.domain.*
import ai.gyeote.app.vehicle.*
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Main-thread presentation state plus one serial network worker. No background service claim. */
class AppController(context: Context, forceFake: Boolean = false, publicTelltalesOnly: Boolean = false,
    testProfile: String? = null, private val changed: () -> Unit) {
    private val store = SecureStore(context, testProfile.takeIf { BuildConfig.DEBUG })
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadScheduledExecutor()
    private val subscriptions = mutableListOf<Subscription>()
    private val journey = JourneyMachine()
    private val player = MessagePlayer(context)
    val fake = FakeVehicle(initiallyRestricted = !forceFake)
    private val speaker = AndroidSpeaker(context)
    val ports = if (forceFake) fake.adapters(speaker) else
        PleosAdapters.create(context, speaker, fake, publicTelltalesOnly) ?: fake.adapters(speaker)
    val inbox = DestinationInbox()
    val privacy = ConsentCoordinator(store.consent(), store.purgePending(), object : ConsentPersistence {
        override fun save(consent: Consent, purgePending: Boolean) = store.saveConsent(consent, purgePending)
    })
    var records: Map<MaintenanceItem, Long> = store.maintenance()
        private set
    @Volatile var serverUrl: String = store.serverUrl()
        private set
    @Volatile var session: FamilySession? = store.session()
        private set
    var children: List<Child> = emptyList()
        private set
    var pairCode: PairingCode? = null
        private set
    var pairCodeLoading = false
        private set
    var pairCodeError = ""
        private set
    private var pairCodeChildren = emptySet<String>()
    var messages: List<FamilyMessage> = emptyList()
        private set
    var warnings: Set<WarningCode> = emptySet()
        private set
    var odometerKm = if (ports.simulated) fake.odometerKm else Double.NaN
        private set
    var door = if (ports.simulated) fake.doorState else DoorState(true, false)
        private set
    private var brake = fake.brakePressed
    var gear = GearSelection.UNKNOWN
        private set
    var tires = TireState()
        private set
    @Volatile var restricted = true
        private set
    var connection = if (session == null) "가족 연결 전" else "서버 연결 준비 중"
        private set
    var notice = "오늘도 편안하게 다녀오세요."
        private set
    var voiceText = ""
        private set
    private var failures = 0
    @Volatile private var active = false
    @Volatile private var destroyed = false
    @Volatile private var connecting = false
    private var polling: ScheduledFuture<*>? = null
    private var lastTirePoll = Long.MIN_VALUE
    private var tireLow = false
    private var telltaleCodes = emptySet<WarningCode>()
    private var telltaleReadings = emptyList<TelltaleReading>()
    private var reportedWarnings = emptySet<WarningCode>()
    private var presentedWarningAdvice = emptyList<WarningAdvice>()
    val history = WarningHistory(store.warningHistory())
    val arrival = store.arrival()
    var arrivalNotice = ""
        private set
    private var lastRetentionDay: Long = Long.MIN_VALUE

    val recipientLabel: String get() {
        val names = children.filter { it.id in privacy.current.recipients }.map { it.name }
        if (names.size != 1) return "가족"
        return when {
            names[0].contains("딸") -> "따님"
            names[0].contains("아들") -> "아드님"
            else -> "${names[0]} 님"
        }
    }
    val arrivedDestination get() = (arrival.prompt ?: arrival.deferredPrompt)?.let { inbox.find(it.destinationId) }
    fun adviceFor(code: WarningCode): WarningAdvice = telltaleReadings.firstOrNull { it.code == code }?.advice ?: WarningDictionary.lookup(code)
    val warningAdvice: WarningAdvice? get() = warnings.map(::adviceFor).minByOrNull { it.urgency.ordinal }

    private fun ui(notify: Boolean = true, action: () -> Unit) { handler.post { if (!destroyed) { action(); if (notify) changed() } } }
    private fun client() = ApiClient(serverUrl, session, allowLocalHttp = BuildConfig.DEBUG,
        onEventRequest = { vehicleLog("eventHttpAttempt=${it.wire}") })
    private fun transportReason(error: Exception): String {
        // Log only fixed transport vocabulary/numbers, never server text or authentication.
        val allowed = setOf("expected", "bytes", "but", "received", "unexpected", "end", "of", "stream", "status", "line", "header", "too", "many")
        return error.message.orEmpty().lowercase().split(Regex("\\s+")).filter {
            it in allowed || it.matches(Regex("[0-9]+"))
        }.take(10).joinToString(" ")
    }

    init {
        inbox.restore(store.destinations())
        history.retain(Instant.now())
        subscriptions += ports.telltales.observeTelltaleDetails { readings ->
            telltaleReadings = readings
            val moment = WarningMoment(Instant.now(), odometerKm.takeIf { it.isFinite() && it >= 0 }, gear,
                gear in setOf(GearSelection.DRIVE, GearSelection.REVERSE) || journey.phase == JourneyPhase.TRAVELLING)
            val historyChanged = history.sample(readings, moment)
            if (historyChanged) {
                store.saveWarningHistory(history.records)
                vehicleLog("warningHistory records=${history.records.size} open=${history.records.count { it.ended == null }}")
            }
            telltaleCodes = readings.map { it.code }.toSet(); updateWarnings(telltaleCodes)
            if (historyChanged) changed()
        }
        subscriptions += ports.odometer.observeOdometer {
            val updated = odometerKm != it; odometerKm = it; sample(); if (updated) changed()
        }
        subscriptions += ports.door.observeDoor {
            val updated = door != it; door = it; sample(); if (updated) changed()
        }
        subscriptions += ports.brake.observeBrake {
            val updated = brake != it; brake = it; sample(); if (updated) changed()
        }
        subscriptions += ports.gear.observeGear {
            val updated = gear != it; gear = it; sample(); if (updated) changed()
        }
        subscriptions += ports.tire.observeTires {
            val updated = tires != it; tires = it; tireLow = it.lowPressure == true; updateWarnings(telltaleCodes)
            if (updated && Features.tireNumbers) changed()
        }
        subscriptions += ports.driving.observeRestrictions {
            if (!restricted && it) { arrival.depart(); store.saveArrival(arrival) }
            restricted = it
            inbox.restricted = it
            if (it) { ports.listener.cancel(); ports.speaker.stop(); player.stop() }
            else promptDestination()
            if (active) schedulePoll(0)
            changed()
        }
        subscriptions += ports.navigator.onDestinationArrived {
            journey.destinationArrived(SystemClock.elapsedRealtime())?.let(::journeyEvent)
            offerArrival()
        }
    }

    fun start() {
        active = true
        handler.post(tick)
        schedulePoll(0)
    }
    fun stop() {
        active = false
        pairCode = null
        polling?.cancel(false)
        handler.removeCallbacks(tick)
        store.saveArrival(arrival)
        ports.listener.cancel(); ports.speaker.stop(); player.stop()
    }
    fun close() {
        stop(); destroyed = true
        subscriptions.forEach(Subscription::cancel)
        ports.close(); ports.speaker.close(); io.shutdownNow()
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!active || destroyed) return
            sample()
            val now = SystemClock.elapsedRealtime()
            val instant = Instant.now()
            val before = arrival.prompt
            val queueSize = arrival.queue.size
            arrival.advance(!restricted, privacy.current, instant, arrivedDestination)
            if (before != arrival.prompt || queueSize != arrival.queue.size) {
                store.saveArrival(arrival); changed()
                if (queueSize != arrival.queue.size) {
                    vehicleLog("arrivalAutoQueued")
                    retryArrivals()
                }
            }
            val day = instant.epochSecond / 86400
            if (day != lastRetentionDay) {
                lastRetentionDay = day
                if (history.retain(instant)) { store.saveWarningHistory(history.records); changed() }
            }
            if (lastTirePoll == Long.MIN_VALUE || now - lastTirePoll >= PollingPolicy.delayMillis(restricted)) {
                lastTirePoll = now
                tireLow = ports.tire.lowPressure() == true
                updateWarnings(telltaleCodes)
            }
            val expiry = privacy.current.until
            if (expiry != null && !Instant.now().isBefore(expiry) && privacy.current != Consent()) {
                updateConsent(Consent(), forcePurge = true)
                notice = "정해 둔 시간이 지나 소식 전하기를 껐어요."
                changed()
            }
            handler.postDelayed(this, 1_000)
        }
    }
    private fun sample() {
        journey.sample(VehicleSample(SystemClock.elapsedRealtime(), odometerKm, door.open, door.locked, brake, gear))?.let(::journeyEvent)
    }
    private fun journeyEvent(event: JourneyEvent) {
        vehicleLog("journeyEvent=${event.name}")
        notice = if (event == JourneyEvent.DEPARTED) "출발했어요. 편안하게 다녀오세요." else "잘 다녀오셨어요. 오늘도 반가워요."
        if (event == JourneyEvent.DEPARTED) { arrival.depart(); store.saveArrival(arrival) }
        if (event == JourneyEvent.DEPARTED) inbox.accepted()?.note?.takeIf { it.isNotBlank() }?.let {
            notice += "\n자녀의 한마디: $it"
            ports.speaker.speak(it)
        }
        if (event == JourneyEvent.ARRIVED) offerArrival()
        val type = if (event == JourneyEvent.DEPARTED) SharedEventType.DEPARTED else SharedEventType.ARRIVED
        if (Features.generalJourneySharing) share(SharedEvent(type, Instant.now(), if (event == JourneyEvent.DEPARTED) "출발했어요" else "도착했어요"))
        changed()
    }
    private fun updateWarnings(codes: Set<WarningCode>) {
        val next = if (tireLow) codes + WarningCode.TPMS else codes
        val adviceNow = next.map(::adviceFor)
        val needsRender = warnings != next || presentedWarningAdvice != adviceNow
        warnings = next
        val newlyActive = warnings - reportedWarnings
        newlyActive.map(::adviceFor).minByOrNull { it.urgency.ordinal }?.let { advice ->
            ports.speaker.speak(advice.movingText)
            if (Features.warningSharing) share(SharedEvent(SharedEventType.ALERT, Instant.now(), advice.movingText))
        }
        reportedWarnings = warnings
        presentedWarningAdvice = adviceNow
        if (needsRender) changed()
    }
    private fun share(event: SharedEvent) {
        if (!active || session == null || privacy.current.filter(event, Instant.now()) == null) return
        io.execute {
            try { privacy.send(client().sharingRemote(), event, Instant.now()); ui { connection = "가족과 연결됐어요" } }
            catch (_: Exception) { offline() }
        }
    }
    fun updateConsent(consent: Consent, forcePurge: Boolean = false) {
        privacy.update(consent, forcePurge)
        if (!consent.routeArrivalAllowed(Instant.now())) { arrival.cancelPrompt(); store.saveArrival(arrival) }
        notice = if (consent == Consent()) "소식 전하기를 껐어요." else "고르신 소식만 전할게요."
        changed()
        if (session != null) io.execute {
            try { privacy.flush(client().sharingRemote()); ui { connection = "설정을 안전하게 반영했어요" } }
            catch (_: Exception) { offline() }
        }
    }
    fun connect(parentName: String, baseUrl: String) {
        if (restricted || connecting) return
        val clean = baseUrl.trim().trimEnd('/')
        try { ApiClient(clean, allowLocalHttp = BuildConfig.DEBUG) }
        catch (_: Exception) { notice = "서버 주소를 다시 살펴 주세요. HTTPS 또는 개발용 기본 주소를 써 주세요."; changed(); return }
        if (session != null && clean != serverUrl) {
            notice = "연결한 가족이 있어 서버 주소를 바꿀 수 없어요."; changed(); return
        }
        connecting = true
        serverUrl = clean; store.saveServerUrl(clean)
        connection = "가족 연결을 준비하고 있어요"; changed()
        io.execute {
            try {
                if (session == null) {
                    val created = ApiClient(clean, allowLocalHttp = BuildConfig.DEBUG).createFamily(parentName.ifBlank { "부모님" })
                    store.saveSession(created); session = created
                }
                privacy.flush(client().sharingRemote())
                ui { connection = "가족과 연결됐어요"; notice = "연결 번호를 가족에게 전해 주세요." }
                pollOnce()
            } catch (_: Exception) { offline() }
            finally { ui { connecting = false } }
        }
    }
    private fun schedulePoll(delay: Long) {
        polling?.cancel(false)
        if (active && !destroyed) polling = io.schedule({
            pollOnce()
            if (active && !destroyed) schedulePoll(PollingPolicy.delayMillis(restricted, failures))
        }, delay, TimeUnit.MILLISECONDS)
    }
    fun refresh() { if (active) schedulePoll(0) }
    fun requestPairCode() {
        if (!active || restricted || session == null || pairCodeLoading) return
        pairCode = null; pairCodeError = ""; pairCodeLoading = true
        pairCodeChildren = children.map { it.id }.toSet()
        changed()
        io.execute {
            try {
                val issued = client().issuePairCode()
                ui { if (active) pairCode = issued }
            } catch (_: Exception) {
                ui { pairCodeError = "새 번호를 받지 못했어요. 연결을 살펴보고 다시 눌러 주세요." }
            } finally { ui { pairCodeLoading = false } }
        }
    }
    private fun pollOnce() {
        if (session == null) return
        try {
            val api = client()
            privacy.flush(api.sharingRemote())
            store.responses().forEach { (id, status) ->
                try { api.respondDestination(id, status); store.saveResponse(id, null) }
                catch (error: ApiException) { if (error.status == 404) store.saveResponse(id, null) else throw error }
            }
            val people = api.children()
            val destinations = api.pendingDestinations()
            val audio = if (Features.familyVoiceMessages) api.messages() else emptyList()
            deliverArrivals(api, manual = false)
            failures = 0
            ui(notify = false) {
                val oldPeople = children
                val oldDestinations = inbox.snapshot()
                val oldMessages = messages
                val oldConnection = connection
                val oldNotice = notice
                children = people
                if (pairCode != null && people.any { it.id !in pairCodeChildren }) pairCode = null
                destinations.forEach(inbox::receive)
                if (oldDestinations != inbox.snapshot()) store.saveDestinations(inbox.snapshot())
                messages = audio
                connection = "가족과 연결됐어요"
                promptDestination()
                if (oldPeople != people || oldDestinations != inbox.snapshot() || oldMessages != audio ||
                    oldConnection != connection || oldNotice != notice) changed()
            }
        } catch (error: Exception) {
            vehicleLog("familyPoll failure=${error.javaClass.simpleName} httpStatus=${(error as? ApiException)?.status} reason=${transportReason(error)}")
            failures++; offline()
        }
    }
    private fun offline() = ui {
        connection = if (privacy.deletionPending) "오프라인 · 소식 차단됨, 삭제 재시도 중" else "오프라인 · 연결을 다시 시도해요"
    }
    private fun promptDestination() {
        inbox.nextPrompt()?.let { destination ->
            val name = children.firstOrNull { it.id == destination.fromChildId }?.name ?: "가족"
            notice = "$name 님이 ${destination.name} 가는 길을 보내왔어요. 안내를 시작할까요?"
            ports.speaker.speak(notice + destination.note.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty())
        }
    }
    fun respond(destination: Destination, intent: DestinationIntent) {
        if (restricted) return
        if (inbox.all().none { it.id == destination.id }) return
        if (intent == DestinationIntent.ASK_AGAIN) {
            notice = "안내를 시작할까요? 응, 나중에, 아니 중에서 말씀해 주세요."
            ports.speaker.speak(notice); changed(); return
        }
        if (intent == DestinationIntent.ACCEPT && !ports.navigator.requestRoute(destination)) {
            notice = "안내 요청을 마치지 못했어요. 정차 후 다시 골라 주세요."; changed(); return
        }
        val answered = inbox.respond(destination.id, intent) ?: return
        store.saveDestinations(inbox.snapshot())
        notice = when (intent) {
            DestinationIntent.ACCEPT -> if (ports.simulated) "${destination.name} 모의 길안내를 요청했어요." else
                "${destination.name} 길안내를 요청했어요. 안내 시작은 내비 화면에서 살펴 주세요."
            DestinationIntent.DEFER -> "괜찮아요. 나중에 다시 고르실 수 있어요."
            DestinationIntent.DECLINE -> "마음 편히, 이번 안내는 받지 않을게요."
            else -> ""
        }
        if (!destination.id.startsWith("demo-") && session != null) {
            store.saveResponse(destination.id, answered.status)
            refresh()
        }
        ports.listener.cancel(); changed()
        if (intent == DestinationIntent.ACCEPT && destination.note.isNotBlank()) ports.speaker.speak(destination.note)
    }
    private fun offerArrival() {
        val queued = arrival.queue.size
        arrival.arrive(inbox.accepted(), privacy.current, Instant.now())
        arrival.advance(!restricted, privacy.current, Instant.now(), arrivedDestination)
        vehicleLog("arrivalOffer accepted=${inbox.accepted() != null} allowed=${privacy.current.routeArrivalAllowed(Instant.now())} prompt=${arrival.prompt != null} deferred=${arrival.deferredPrompt != null} handled=${arrival.handled.size}")
        store.saveArrival(arrival)
        if (arrival.queue.size > queued) retryArrivals()
        changed()
    }
    fun updateArrivalMode(mode: ArrivalMode) {
        arrival.changeMode(mode); store.saveArrival(arrival); changed()
    }
    fun confirmArrival() {
        if (restricted || !active) return
        arrival.confirm(privacy.current, Instant.now(), arrivedDestination)
        store.saveArrival(arrival); changed(); retryArrivals()
    }
    fun skipArrival() {
        if (restricted) return
        arrival.skip(); store.saveArrival(arrival)
        arrivalNotice = "이번에는 도착 소식을 보내지 않았어요."
        changed()
    }
    fun retryArrivals() {
        if (!active || restricted || session == null || !privacy.current.routeArrivalAllowed(Instant.now())) return
        io.execute { deliverArrivals(client(), manual = true) }
    }
    private fun deliverArrivals(api: ApiClient, manual: Boolean) {
        for (item in arrival.ready(manual)) {
            if (!active || restricted || !privacy.current.routeArrivalAllowed(Instant.now())) return
            val id = item.event.destinationId ?: continue
            try {
                if (!privacy.send(api.sharingRemote(), item.event, Instant.now())) return
                arrival.sent(id); store.saveArrival(arrival)
                ui {
                    inbox.arrived(id); store.saveDestinations(inbox.snapshot())
                    arrivalNotice = "${recipientLabel}께 잘 도착했다고 전했어요"
                    notice = arrivalNotice
                }
            } catch (error: Exception) {
                vehicleLog("arrivalDelivery failure=${error.javaClass.simpleName} reason=${transportReason(error)}")
                val failure = when ((error as? ApiException)?.status) {
                    409 -> ArrivalFailure.CONFLICT
                    403 -> ArrivalFailure.CONSENT
                    null -> ArrivalFailure.NETWORK
                    else -> ArrivalFailure.OTHER
                }
                arrival.failed(id, failure); store.saveArrival(arrival)
                ui {
                    arrivalNotice = when (failure) {
                        ArrivalFailure.CONFLICT -> "목적지 상태가 달라 보내지 못했어요 (409). 소식을 보관했어요. 다시 확인해 주세요."
                        ArrivalFailure.CONSENT -> "서버에서 동의를 확인하지 못했어요 (403). 소식을 보관했어요. 설정을 살펴 주세요."
                        ArrivalFailure.NETWORK -> "연결이 끊겨 아직 전하지 못했어요. 소식을 보관하고 정차 중 다시 시도해요."
                        ArrivalFailure.OTHER -> "서버 응답으로 보내지 못했어요. 소식을 보관했어요. 다시 시도해 주세요."
                    }
                }
            }
        }
    }
    fun clearWarningHistory() {
        if (restricted) return
        history.clear(); store.saveWarningHistory(history.records)
        notice = "이 차의 경고 기록을 지웠어요."; changed()
    }
    fun listen(destination: Destination) {
        if (restricted) return
        notice = "모의 콘솔에서 들려줄 문장을 입력해 주세요."
        ports.listener.requestText { text -> voiceText = text; respond(destination, KoreanIntentParser.parse(text)) }
        changed()
    }
    fun demoDestination() {
        inbox.receive(Destination("demo-${SystemClock.elapsedRealtime()}", "demo-family", "서울 중앙병원",
            "서울특별시 중구 세종대로", 37.5665, 126.9780, "천천히 오세요. 도착하면 만나요."))
        store.saveDestinations(inbox.snapshot())
        promptDestination(); changed()
    }
    fun injectText(text: String) {
        voiceText = text
        if (!restricted) {
            val destination = inbox.all().firstOrNull()
            if (destination != null) {
                ports.listener.requestText { respond(destination, KoreanIntentParser.parse(it)) }
                fake.injectText(text)
            } else notice = "먼저 모의 목적지를 받아 주세요."
        }
        changed()
    }
    fun record(item: MaintenanceItem, lastKm: Long = odometerKm.toLong()) {
        if (restricted || !odometerKm.isFinite()) return
        records = MaintenanceRules.record(item, lastKm, odometerKm.toLong(), records)
        store.saveMaintenance(records); notice = "${item.title} 정비 거리를 기록했어요."; changed()
    }
    fun play(message: FamilyMessage) {
        if (restricted || message.durationSec !in 1..30 || message.mime !in setOf("audio/wav", "audio/mp4")) return
        notice = "가족이 남긴 말을 준비하고 있어요."; changed()
        io.execute {
            try {
                val bytes = client().audio(message.id)
                ui {
                    if (restricted || !active) bytes.fill(0) else {
                        ports.speaker.stop()
                        runCatching { player.play(bytes, complete = {
                            notice = "마음이 닿았어요. 편안한 하루 보내세요."; changed()
                            io.execute { try { client().markPlayed(message.id); refresh() } catch (_: Exception) { offline() } }
                        }, failed = { notice = "메시지를 재생하지 못했어요. 다시 골라 주세요."; changed() }) }
                            .onFailure { player.stop(); notice = "메시지를 재생하지 못했어요." }
                    }
                }
            } catch (_: Exception) { offline() }
        }
    }
    fun playDemoMessage() { if (!restricted) ports.speaker.speak("아버지, 오늘도 천천히 다녀오세요. 도착하면 함께 차 한 잔 해요.") }
}
