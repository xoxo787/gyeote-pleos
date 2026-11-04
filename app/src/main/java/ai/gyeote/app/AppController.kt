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

/** Process-wide vehicle state; the recording service and a visible screen have separate lifetimes. */
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
    var energy = EnergyState()
        private set
    var turnSignal = TurnSignal.UNKNOWN
        private set
    /** Console sign convention (SDK sign corrected in the port). */
    var steeringAngle: Float? = null
        private set
    var seats = SeatOccupancy()
        private set
    /** Any side window open; null when the vehicle does not report windows. */
    var windowOpen: Boolean? = null
        private set
    var handoverSettings = store.handoverSettings()
        private set
    var lastHandover: HandoverSnapshot? = store.handoverSnapshot()
        private set
    /** F3 records: last 50 takeover/return pairs, fleet trip lines, the note for the next driver. */
    val ledger = HandoverLedger(store.handoverPairs(), store.trips(), store.handoverNote())
    /** Navigation destination name reported since the current takeover (fleet trip line only). */
    private var tripDestination: String? = null
    private var returnDueSince: Long? = null
    /** F2 tour in progress (or its finish card). Session only: nothing about it is stored. */
    var tour: TourMachine? = null
        private set
    /** The tour already ran in this boarding; cleared when the gear leaves P. Session flag only. */
    var tourDoneThisBoarding = false
        private set
    /** elapsedRealtime of the last passed mission, for the 0.5 s check animation. */
    var tourPassedAt = Long.MIN_VALUE
        private set
    var tourPassedMission: TourMission? = null
        private set
    private var handoverDueSince: Long? = null
    private var handoverSpoken = false
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
    private var recording = false
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
        // A stored takeover without its return stays open across restarts (a drive in D is still needed).
        journey.takeoverRestored(ledger.isOpen(lastHandover))
        inbox.restore(store.destinations())
        history.retain(Instant.now())
        subscriptions += ports.telltales.observeTelltaleDetails { readings ->
            telltaleReadings = readings
            val moment = WarningMoment(Instant.now(), odometerKm.takeIf { it.isFinite() && it >= 0 }, gear,
                gear in setOf(GearSelection.DRIVE, GearSelection.REVERSE) || journey.phase == JourneyPhase.TRAVELLING)
            val historyChanged = recording && handoverSettings.mode.cardOn && history.sample(readings, moment)
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
            val updated = brake != it; brake = it; sample()
            val pressed = it
            tourInput("brake") { machine -> machine.brake(pressed) }
            if (updated) changed()
        }
        subscriptions += ports.gear.observeGear {
            val updated = gear != it; gear = it
            if (it != GearSelection.PARK) { tourDoneThisBoarding = false; endTour("gear=$it") }
            sample(); if (updated) changed()
        }
        subscriptions += ports.tire.observeTires {
            val updated = tires != it; tires = it; tireLow = it.lowPressure == true; updateWarnings(telltaleCodes)
            if (updated && Features.tireNumbers) changed()
        }
        subscriptions += ports.energy.observeEnergy {
            val updated = energy != it; energy = it; if (updated) changed()
        }
        subscriptions += ports.turnSignal.observeTurnSignal {
            val updated = turnSignal != it; turnSignal = it
            val on = signalOn(it)
            tourInput("turnSignal") { machine -> machine.turnSignal(on) }
            if (updated) changed()
        }
        subscriptions += ports.steering.observeSteeringAngle {
            val updated = steeringAngle != it; steeringAngle = it
            val angle = it
            tourInput("steering") { machine -> machine.steering(angle) }
            // Only the tour shows the live angle; elsewhere a steering change needs no redraw.
            if (updated && tourVisible) changed()
        }
        subscriptions += ports.seats.observeSeats { seats = it }
        subscriptions += ports.windows.observeWindows {
            val updated = windowOpen != it; windowOpen = it; if (updated && returnVisible) changed()
        }
        subscriptions += ports.navigator.onDestinationName { tripDestination = it }
        subscriptions += ports.driving.observeRestrictions {
            if (!restricted && it) { arrival.depart(); store.saveArrival(arrival) }
            restricted = it
            inbox.restricted = it
            if (it) { ports.listener.cancel(); ports.speaker.stop(); player.stop(); endTour("restricted") }
            else promptDestination()
            checkHandover(); checkReturn()
            if (active) schedulePoll(0)
            changed()
        }
        subscriptions += ports.navigator.onDestinationArrived {
            journey.destinationArrived(SystemClock.elapsedRealtime())?.let(::journeyEvent)
            if (Features.sharedRoutes) offerArrival()
        }
    }

    fun start() {
        if (active || destroyed) return
        active = true
        handler.removeCallbacks(tick)
        handler.post(tick)
        schedulePoll(0)
        updateWarnings(telltaleCodes)
    }
    fun stop() {
        active = false
        pairCode = null
        polling?.cancel(false)
        if (!recording) handler.removeCallbacks(tick)
        store.saveArrival(arrival)
        ports.listener.cancel(); ports.speaker.stop(); player.stop()
    }
    fun close() {
        if (destroyed) return
        stopRecording(); stop(); destroyed = true
        handler.removeCallbacksAndMessages(null)
        subscriptions.forEach(Subscription::cancel)
        ports.close(); ports.speaker.close(); io.shutdownNow()
    }
    internal fun startRecording() {
        if (recording || destroyed || !handoverSettings.mode.cardOn) return
        recording = true
        handler.removeCallbacks(tick)
        handler.post(tick)
        vehicleLog("recording started")
    }
    internal fun stopRecording() {
        if (!recording) return
        recording = false
        if (!active) handler.removeCallbacks(tick)
        vehicleLog("recording stopped")
    }
    private val tick = object : Runnable {
        override fun run() {
            if ((!active && !recording) || destroyed) return
            sample()
            val now = SystemClock.elapsedRealtime()
            val instant = Instant.now()
            val before = arrival.prompt
            val queueSize = arrival.queue.size
            if (Features.sharedRoutes) arrival.advance(!restricted, privacy.current, instant, arrivedDestination)
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
            speakHandoverWhenSettled(now)
            handler.postDelayed(this, 1_000)
        }
    }
    private fun sample() {
        if ((!active && !recording) || destroyed) return
        journey.sample(VehicleSample(SystemClock.elapsedRealtime(), odometerKm, door.open, door.locked, brake, gear, door.driverOpen))?.let(::journeyEvent)
        checkHandover(); checkReturn()
    }

    /** F1 is offered only while parked (gear P) and unrestricted; never while driving. */
    val handoverVisible: Boolean get() = Features.handover && handoverSettings.mode.cardOn &&
        journey.handover == HandoverState.DUE && !restricted && gear == GearSelection.PARK
    fun handoverInput() = HandoverInput(
        socPercent = energy.socPercent, rangeKm = energy.rangeKm,
        activeWarnings = warnings.map(::adviceFor), history = history.records,
        odometerKm = odometerKm.takeIf { it.isFinite() && it >= 0 },
        doorOpen = door.open, trunkOpen = door.trunkOpen, chargePortOpen = energy.chargePortOpen,
        previousNote = handoverSettings.mode.visibleNote(ledger.note),
        windowOpen = windowOpen,
    )
    fun handoverCard(): HandoverCard = HandoverText.build(handoverInput(), handoverSettings.length, Instant.now())
    private fun checkHandover() {
        val visible = handoverVisible
        if (visible && handoverDueSince == null) {
            handoverDueSince = SystemClock.elapsedRealtime(); handoverSpoken = false
            vehicleLog("handoverCard shown mode=${handoverSettings.mode} gear=$gear")
            changed()
        } else if (!visible && handoverDueSince != null) {
            if (handoverSpoken) ports.speaker.stop()
            handoverDueSince = null
            vehicleLog("handoverCard hidden gear=$gear restricted=$restricted")
            changed()
        }
    }
    /** Values arrive shortly after the gear on a real vehicle; speak once they have had ~2 s to settle. */
    private fun speakHandoverWhenSettled(now: Long) {
        val since = handoverDueSince ?: return
        if (!active || handoverSpoken || !handoverVisible || now - since < 2_000) return
        handoverSpoken = true
        val card = handoverCard()
        vehicleLog("handoverSpeech length=${handoverSettings.length} chars=${card.speech.length}")
        ports.speaker.speak(card.speech)
    }
    fun replayHandover() { if (handoverVisible) ports.speaker.speak(handoverCard().speech) }
    fun acceptHandover() {
        if (!handoverVisible) return
        saveHandoverSnapshot("accept")
        notice = "인수 기록을 남겼어요. 차 상태만 저장했어요."
        changed()
    }
    /** Both card buttons take the car over, so both store the same vehicle-only snapshot. */
    private fun saveHandoverSnapshot(via: String) {
        val snapshot = HandoverSnapshot.capture(Instant.now(), handoverInput())
        store.saveHandoverSnapshot(snapshot)
        lastHandover = store.handoverSnapshot()
        vehicleLog("handoverSnapshot saved soc=${snapshot.socPercent} odometerKm=${snapshot.odometerKm} warnings=${snapshot.activeWarnings.joinToString("+")} " +
            "door=${snapshot.doorOpen} trunk=${snapshot.trunkOpen} chargePort=${snapshot.chargePortOpen} restored=${lastHandover == snapshot} via=$via")
        ports.speaker.stop()
        journey.handoverHandled(); handoverDueSince = null
        // A new takeover: the previous note has been shown to this driver; the return card waits for a drive.
        if (ledger.note != null) { ledger.takenOver(); store.saveHandoverNote(null) }
        journey.takeoverStarted(); tripDestination = null
    }

    /** F3: open takeover, driven in D since, now P + driver door open. Never while driving or restricted. */
    val returnVisible: Boolean get() = Features.handover && handoverSettings.mode.cardOn && lastHandover != null &&
        ledger.isOpen(lastHandover) && journey.returnState == ReturnState.DUE && !restricted && gear == GearSelection.PARK
    fun returnComparison(): ReturnComparison? = lastHandover?.let {
        ReturnText.compare(it, HandoverSnapshot.capture(Instant.now(), handoverInput()))
    }
    fun returnCard(): ReturnCard? = returnComparison()?.let(ReturnText::build)
    private fun checkReturn() {
        val visible = returnVisible
        if (visible && returnDueSince == null) {
            returnDueSince = SystemClock.elapsedRealtime()
            val c = returnComparison()
            vehicleLog("returnCard shown mode=${handoverSettings.mode} gear=$gear soc=${c?.socBefore}->${c?.socNow} " +
                "distanceKm=${c?.distanceKm} newWarnings=${c?.newWarnings?.size} names=${c?.newWarnings?.joinToString("+")} window=${c?.windowOpen}")
            changed()
        } else if (!visible && returnDueSince != null) {
            returnDueSince = null
            vehicleLog("returnCard hidden gear=$gear restricted=$restricted")
            changed()
        }
    }
    /** [다음 사람에게 한마디]: one optional line, stored now and shown on the next handover card. */
    fun saveReturnNote(text: String) {
        if (!returnVisible || !handoverSettings.mode.noteOn) return
        val saved = ledger.leaveNote(text)
        store.saveHandoverNote(saved)
        // Debug evidence names the length only, never the text.
        vehicleLog("returnNote saved chars=${saved?.length ?: 0}")
        changed()
    }
    /** [돌려줬어요]: the return snapshot (vehicle state only) is stored as a pair with the takeover. */
    fun confirmReturn() {
        if (!returnVisible) return
        val takeover = lastHandover ?: return
        val returned = HandoverSnapshot.capture(Instant.now(), handoverInput())
        val trip = ledger.returned(takeover, returned, handoverSettings.mode, tripDestination)
        store.saveHandoverPairs(ledger.pairs)
        if (trip != null) store.saveTrips(ledger.trips)
        val restored = store.handoverPairs().lastOrNull() == ledger.pairs.lastOrNull()
        vehicleLog("returnPair saved soc=${takeover.socPercent}->${returned.socPercent} odometerKm=${takeover.odometerKm}->${returned.odometerKm} " +
            "newWarnings=${ReturnText.compare(takeover, returned).newWarnings.size} pairs=${ledger.pairs.size} restored=$restored")
        if (trip != null) vehicleLog("tripLog added startKm=${trip.startKm} endKm=${trip.endKm} distanceKm=${trip.distanceKm} " +
            "destination=${trip.destination != null} trips=${ledger.trips.size}")
        journey.returnHandled(closed = true); returnDueSince = null; tripDestination = null
        notice = "돌려주기 기록을 남겼어요. 차 상태만 저장했어요."
        changed()
    }
    /** [나중에]: hides the card for this stop without a record. */
    fun laterReturn() {
        if (!returnVisible) return
        journey.returnHandled(closed = false); returnDueSince = null
        vehicleLog("returnCard later")
        changed()
    }
    /** Settings "모두 지우기": warning history, takeover/return pairs, trip lines, last snapshot and note. */
    fun clearAllRecords() {
        if (restricted) return
        history.clear(); ledger.clear(); lastHandover = null
        store.clearRecords()
        journey.takeoverCleared(); returnDueSince = null; tripDestination = null
        vehicleLog("records cleared warnings=${store.warningHistory().size} pairs=${store.handoverPairs().size} trips=${store.trips().size} " +
            "snapshot=${store.handoverSnapshot() != null} note=${store.handoverNote() != null}")
        notice = "이 차에 남긴 기록을 모두 지웠어요."
        changed()
    }
    /** Debug-only evidence setup for the emulator script: same as the settings button, without the parked check. */
    internal fun clearAllRecordsForTest() {
        if (!BuildConfig.DEBUG) return
        history.clear(); ledger.clear(); lastHandover = null
        store.clearRecords(); journey.takeoverCleared(); returnDueSince = null; tripDestination = null
        vehicleLog("records cleared via=debugIntent pairs=${store.handoverPairs().size}")
        changed()
    }

    /** Missions for the current mode; empty when the mode is OFF. */
    val tourMissions: List<TourMission> get() = if (Features.tour) TourPlan.missions(handoverSettings.mode) else emptyList()
    /** The handover card offers [처음 타요] only once per boarding. */
    val tourOffered: Boolean get() = tourMissions.isNotEmpty() && !tourDoneThisBoarding
    /** Parked (P) and unrestricted only. Any other gear or a restriction removes the tour at once. */
    val tourVisible: Boolean get() = tour != null && !restricted && gear == GearSelection.PARK
    val tourCelebrating: Boolean get() = tourPassedMission != null && SystemClock.elapsedRealtime() - tourPassedAt < TOUR_CHECK_MILLIS
    private fun signalOn(value: TurnSignal) = value == TurnSignal.LEFT || value == TurnSignal.RIGHT

    /** [처음 타요]: take the car over (same snapshot as [인수했어요]) and start the tour. */
    fun requestTour() {
        if (!handoverVisible || !tourOffered) return
        saveHandoverSnapshot("firstRide")
        startTour("handover")
    }
    /** Settings [투어 다시 하기]: parked only, hidden when the mode is OFF. */
    fun restartTour() {
        if (restricted || gear != GearSelection.PARK || tourMissions.isEmpty() || handoverVisible) return
        startTour("settings")
    }
    private fun startTour(from: String) {
        val machine = TourMachine(tourMissions, brake, signalOn(turnSignal))
        machine.steering(steeringAngle)
        tour = machine; tourDoneThisBoarding = true; tourPassedMission = null; tourPassedAt = Long.MIN_VALUE
        vehicleLog("tour started from=$from mode=${handoverSettings.mode} missions=${machine.missions.joinToString("+")} current=${machine.current}")
        changed()
    }
    private fun tourInput(signal: String, feed: (TourMachine) -> Boolean) {
        if (!active) return
        val machine = tour ?: return
        if (!tourVisible) return
        val before = machine.current ?: return
        if (!feed(machine)) return
        tourPassedMission = before; tourPassedAt = SystemClock.elapsedRealtime()
        vehicleLog("tourMission passed=$before by=$signal next=${machine.current} passed=${machine.passed} remaining=${machine.remaining}")
        if (machine.end == TourEnd.COMPLETED) vehicleLog("tour completed passed=${machine.passed}")
        // Debug evidence names the kind of utterance only, never the text.
        vehicleLog("tourSpeech kind=${if (machine.end == TourEnd.COMPLETED) "PASS+DONE" else "PASS"}")
        ports.speaker.speak(if (machine.end == TourEnd.COMPLETED) "${TourPlan.PASS_SPEECH} ${TourPlan.DONE}" else TourPlan.PASS_SPEECH)
        // Redraw once the check animation is over so the next mission (or the finish card) appears.
        handler.postDelayed({ if (!destroyed) changed() }, TOUR_CHECK_MILLIS + 20)
        changed()
    }
    fun skipTour() {
        val machine = tour ?: return
        if (!tourVisible || !machine.skip()) return
        tourPassedMission = null
        ports.speaker.stop()
        vehicleLog("tour skipped passed=${machine.passed} remaining=${machine.remaining}")
        changed()
    }
    /** [시작하기] on the finish card. */
    fun finishTour() {
        if (tour?.finished != true) return
        tour = null; tourPassedMission = null
        notice = TourPlan.DONE
        vehicleLog("tour closed")
        changed()
    }
    private fun endTour(reason: String) {
        val machine = tour ?: return
        machine.gear(GearSelection.UNKNOWN)
        tour = null; tourPassedMission = null
        ports.speaker.stop()
        vehicleLog("tour ended reason=$reason passed=${machine.passed} remaining=${machine.remaining}")
    }
    fun updateHandoverSettings(next: HandoverSettings) {
        if (restricted) return
        handoverSettings = next; store.saveHandoverSettings(next)
        vehicleLog("handoverSettings mode=${next.mode} length=${next.length}")
        checkHandover(); changed()
    }
    private companion object { const val TOUR_CHECK_MILLIS = 500L }
    private fun journeyEvent(event: JourneyEvent) {
        vehicleLog("journeyEvent=${event.name}")
        notice = if (event == JourneyEvent.DEPARTED) "출발했어요. 안전 운전하세요." else "정차했어요."
        if (event == JourneyEvent.DEPARTED) { arrival.depart(); store.saveArrival(arrival) }
        if (event == JourneyEvent.DEPARTED && Features.sharedRoutes) inbox.accepted()?.note?.takeIf { it.isNotBlank() }?.let {
            notice += "\n자녀의 한마디: $it"
            if (active) ports.speaker.speak(it)
        }
        if (event == JourneyEvent.ARRIVED && Features.sharedRoutes) offerArrival()
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
            if (active) ports.speaker.speak(advice.movingText)
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
        if (!Features.serverSync) return
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
        if (!active || !Features.sharedRoutes) return
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
