package ai.gyeote.buzzer

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import ai.gyeote.buzzer.domain.CarFacts
import ai.gyeote.buzzer.domain.FinishReason
import ai.gyeote.buzzer.domain.GameConfig
import ai.gyeote.buzzer.domain.GameMachine
import ai.gyeote.buzzer.domain.Lines
import ai.gyeote.buzzer.domain.Phase
import ai.gyeote.buzzer.domain.Progress
import ai.gyeote.buzzer.domain.ProgressKeeper
import ai.gyeote.buzzer.domain.QuestionBank
import ai.gyeote.buzzer.domain.QuestionSource
import ai.gyeote.buzzer.domain.RoundResult
import ai.gyeote.buzzer.domain.Seat
import ai.gyeote.buzzer.vehicle.Car
import ai.gyeote.buzzer.vehicle.CarListener
import ai.gyeote.buzzer.vehicle.PleosCar
import ai.gyeote.buzzer.vehicle.Voice
import kotlin.random.Random

/**
 * The only screen. It forwards car signals and the clock into [GameMachine] and draws whatever phase comes out.
 * No game rule lives here: see domain/Game.kt.
 */
class MainActivity : ComponentActivity(), CarListener {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var root: LinearLayout
    private lateinit var questions: QuestionSource
    private lateinit var machine: GameMachine
    private lateinit var progress: StoredProgress
    private lateinit var keeper: ProgressKeeper
    private var config = GameConfig()
    private var car: Car? = null
    private var voice: Voice? = null
    private var carMissing = false
    private var facts = CarFacts()
    private val positions = mutableMapOf<Seat, Int>()
    private var drawn: Any? = null
    private var bar: BarView? = null
    private var unit = 14f
    private var wasLocked = true

    private val night = Color.rgb(20, 22, 43)
    private val panel = Color.rgb(32, 35, 66)
    private val cream = Color.rgb(244, 241, 222)
    private val dim = Color.rgb(138, 143, 181)
    private val gold = Color.rgb(255, 209, 102)
    private val glass = Color.rgb(123, 223, 242)
    private val coral = Color.rgb(239, 118, 122)

    private val ticker = object : Runnable {
        override fun run() {
            machine.tick(now())
            settle()
            updateBar()
            handler.postDelayed(this, 100)
        }
    }

    private fun now() = SystemClock.elapsedRealtime()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        measureUnit()
        root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(night) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            insets
        }
        setContentView(root)
        progress = StoredProgress(this)
        if (BuildConfig.DEBUG) applyDebugExtras()
        questions = QuestionSource(QuestionBank.all, cursor = progress.cursor)
        val fixedDelay = if (BuildConfig.DEBUG) intent.getLongExtra("reflexDelayMs", -1) else -1
        machine = GameMachine(questions, config) { if (fixedDelay >= 0) fixedDelay else Random.nextLong(2_000, 5_000) }
        keeper = ProgressKeeper(progress)
        render()
    }

    /** Debug builds only: shorter timings and a clean progress file for the emulator integration script. */
    private fun applyDebugExtras() {
        fun ms(name: String, default: Long) = intent.getLongExtra(name, default).coerceIn(500, 120_000)
        config = config.copy(askMs = ms("askMs", config.askMs), revealMs = ms("revealMs", config.revealMs),
            reflexWindowMs = ms("reflexWindowMs", config.reflexWindowMs), fallbackMs = ms("fallbackMs", config.fallbackMs))
        if (intent.getBooleanExtra("resetProgress", false)) { progress.lifetimeStars = 0; progress.cursor = 0 }
    }

    /** Day/night or window-size changes redraw the same game instead of recreating the screen. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        measureUnit()
        render()
    }

    private fun measureUnit() {
        val metrics = resources.displayMetrics
        unit = minOf(metrics.heightPixels.toFloat(), metrics.widthPixels * 0.5625f) / 100f
    }

    override fun onStart() {
        super.onStart()
        voice = Voice(this)
        car = PleosCar.open(this, this)
        carMissing = car == null
        if (carMissing) render()
        handler.post(ticker)
    }

    override fun onStop() {
        handler.removeCallbacks(ticker)
        car?.close(); car = null
        voice?.close(); voice = null
        // Without the car the gear is unknown again, so the game locks until the screen comes back and P is reported anew.
        machine.screenLeft(now())
        machine.drainSpeech()
        keeper.observe(machine.phase, machine.stars, questions.cursor)
        super.onStop()
    }

    // --- car signals ---

    override fun onGear(parked: Boolean) { machine.gear(parked, now()); settle() }
    override fun onWindow(seat: Seat, position: Int) { positions[seat] = position; machine.window(seat, position, now()); settle(force = true) }
    override fun onFacts(facts: CarFacts) { this.facts = facts; machine.car(facts, now()); settle(force = machine.phase == Phase.Lobby || machine.phase is Phase.Between) }

    /** After every input: speak what the machine queued, save progress when a session ends, redraw if something changed. */
    private fun settle(force: Boolean = false) {
        val lines = machine.drainSpeech()
        // The moment the game locks, whatever was still being read stops, whether or not a closing line follows.
        val locked = machine.phase is Phase.Locked
        if (locked && !wasLocked) voice?.hush()
        wasLocked = locked
        voice?.say(lines)
        keeper.observe(machine.phase, machine.stars, questions.cursor)
        val state = machine.phase to machine.joined
        if (state != drawn) {
            if (BuildConfig.DEBUG) Log.i("BuzzerGame", describe())
            render()
        } else if (force) render()
    }

    private fun describe(): String = when (val phase = machine.phase) {
        is Phase.Locked -> "phase=Locked closedByDriving=${phase.closedByDriving} leftScreen=${phase.leftScreen} lifetime=${progress.lifetimeStars}"
        Phase.Lobby -> "phase=Lobby joined=${machine.joined.map { it.name }} charging=${facts.charging} soc=${facts.socPercent}"
        is Phase.Between -> "phase=Between raise=${phase.raise.map { it.name }} nextAtSoc=${phase.nextAtSoc}"
        is Phase.Asking -> "phase=Asking round=${phase.round} fromCar=${phase.question.fromCar} truth=${phase.question.truth} answeredO=${phase.answeredO.map { it.name }} final=${machine.finalRound}"
        is Phase.Steady -> "phase=Steady round=${phase.round} tooEarly=${phase.tooEarly.map { it.name }} final=${machine.finalRound}"
        is Phase.Go -> "phase=Go round=${phase.round} tooEarly=${phase.tooEarly.map { it.name }}"
        is Phase.Reveal -> when (val result = phase.result) {
            is RoundResult.Ox -> "phase=Reveal round=${phase.round} kind=OX correct=${result.correct.map { it.name }} wrong=${result.wrong.map { it.name }} stars=${starsText()}"
            is RoundResult.Reflex -> "phase=Reveal round=${phase.round} kind=REFLEX winner=${result.winner?.name} tooEarly=${result.tooEarly.map { it.name }} stars=${starsText()}"
        }
        is Phase.Finished -> "phase=Finished reason=${phase.reason} open=${phase.openWindows.map { it.name }} stars=${starsText()} lifetime=${progress.lifetimeStars}"
    }
    private fun starsText() = machine.stars.entries.joinToString(",") { "${it.key.name}:${it.value}" }

    // --- drawing ---

    private class Copy(val kicker: String, val headline: String, val sub: String, val accent: Int, val button: String? = null, val action: (() -> Unit)? = null)

    private fun copy(): Copy {
        val soc = facts.socPercent
        return when (val phase = machine.phase) {
            is Phase.Locked -> when {
                carMissing -> Copy("", "차에서만 열려요", "이 기기에서는 차량 신호를 찾지 못했어요.", dim)
                phase.closedByDriving -> Copy("출발", "출발하니까 닫았어요", "다음에 차를 세우면 이어서 해요. 지금까지 모은 별 ${progress.lifetimeStars}개", coral)
                phase.leftScreen -> Copy("", "하던 판은 마쳤어요", "화면을 떠나 있는 동안 판을 닫아 뒀어요. 지금까지 모은 별 ${progress.lifetimeStars}개", dim)
                else -> Copy("", "차를 세우면 열려요", "기어를 P에 두면 놀이가 열려요.", dim)
            }
            Phase.Lobby -> Copy(
                if (facts.charging == true && soc != null) "충전 중 ${soc}% · 배터리가 1% 오를 때마다 새 문제가 와요" else "오늘은 ${config.parkedRounds}판 · 충전 중에 열면 배터리가 시계가 돼요",
                "함께할 자리는\n창문을 한 칸 내려 주세요",
                "창문은 한 칸이면 돼요 · 올릴 때는 천천히 · 기어를 옮기면 바로 닫혀요\n지금까지 모은 별 ${progress.lifetimeStars}개",
                glass, if (machine.joined.isEmpty()) null else "시작") { machine.start(now()); settle() }
            is Phase.Between -> when {
                phase.raise.isNotEmpty() -> Copy("잠깐", "창문을 올려 주세요", "천천히 하셔도 돼요 · ${phase.raise.joinToString(", ") { it.label }}", glass)
                else -> Copy("충전 중 ${soc ?: "?"}%", "다음 문제는 ${phase.nextAtSoc}%에 와요", "충전되는 동안 잠깐 쉬어요.", gold)
            }
            is Phase.Asking -> Copy(
                (if (machine.finalRound) "마지막 판" else "${phase.round + 1}번째 판") + if (phase.question.fromCar) " · 차가 낸 문제" else "",
                phase.question.text, "맞으면 창문을 한 칸 내리고, 아니면 그대로 두세요.", cream)
            is Phase.Steady -> Copy("${phase.round + 1}번째 판 · 번개 버저", "준비…", "‘지금!’이 뜨면 창문을 한 칸 내리세요.", cream)
            is Phase.Go -> Copy("${phase.round + 1}번째 판 · 번개 버저", "지금!", "가장 먼저 내린 자리가 별을 받아요.", gold)
            is Phase.Reveal -> when (val result = phase.result) {
                is RoundResult.Ox -> Copy(result.question.text, if (result.question.truth) "O · 맞는 말이에요" else "X · 틀린 말이에요", result.question.why, gold)
                is RoundResult.Reflex -> Copy("번개 버저", result.winner?.let { Lines.fastest(it) } ?: Lines.NO_WINNER,
                    if (result.tooEarly.isEmpty()) "" else "너무 빨랐던 자리: ${result.tooEarly.joinToString(", ") { it.label }}", gold)
            }
            is Phase.Finished -> Copy(
                when (phase.reason) { FinishReason.ROUNDS_DONE -> "끝"; FinishReason.CHARGED -> "충전 완료 ${soc ?: ""}%"; FinishReason.UNPLUGGED -> "충전 끝" },
                when (phase.reason) { FinishReason.ROUNDS_DONE -> "오늘은 여기까지예요"; FinishReason.CHARGED -> "충전도 놀이도 끝났어요"; FinishReason.UNPLUGGED -> "다음 충전 때 이어서 해요" },
                "오늘 모은 별 ${machine.stars.values.sum()}개 · 지금까지 ${progress.lifetimeStars}개" +
                    if (phase.openWindows.isEmpty()) "" else "\n열려 있는 창문을 올려 주세요: ${phase.openWindows.joinToString(", ") { it.label }}",
                gold, "한 판 더") { machine.again(now()); settle() }
        }
    }

    private fun render() {
        drawn = machine.phase to machine.joined
        val text = copy()
        if (machine.phase is Phase.Locked || machine.phase is Phase.Finished) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        root.removeAllViews()
        bar = null
        val pad = (unit * 4).toInt()
        root.setPadding(pad, pad, pad, pad)

        // Left: the four seats laid out as they sit in the car.
        val seats = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        seats.addView(label("앞", 2.2f, dim).apply { gravity = Gravity.CENTER })
        listOf(listOf(Seat.DRIVER, Seat.PASSENGER), listOf(Seat.REAR_LEFT, Seat.REAR_RIGHT)).forEach { row ->
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEach { line.addView(tile(it), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f).apply { setMargins(gap(), gap(), gap(), gap()) }) }
            seats.addView(line, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        root.addView(seats, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 4f))

        // Right: what is happening now.
        val stage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(pad, 0, 0, 0) }
        stage.addView(label(getString(R.string.app_name), 2.4f, dim, bold = true))
        stage.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        if (text.kicker.isNotEmpty()) stage.addView(label(text.kicker, 2.8f, text.accent, bold = true))
        val long = text.headline.length > 22
        stage.addView(label(text.headline, if (long) 5.2f else 7f, if (machine.phase is Phase.Go) gold else cream, bold = true).apply {
            contentDescription = "headline"
            setPadding(0, (unit * 1.5f).toInt(), 0, (unit * 1.5f).toInt())
        })
        if (text.sub.isNotEmpty()) stage.addView(label(text.sub, 3f, dim))
        if (machine.phase is Phase.Asking || machine.phase is Phase.Go) {
            bar = BarView(this, glass, panel)
            stage.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (unit * 1.4f).toInt()).apply { topMargin = (unit * 3).toInt() })
            updateBar()
        }
        stage.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        if (text.button != null && text.action != null) {
            stage.addView(label(text.button, 3.6f, night, bold = true).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { setColor(gold); cornerRadius = unit * 2.4f }
                setPadding((unit * 6).toInt(), (unit * 2.4f).toInt(), (unit * 6).toInt(), (unit * 2.4f).toInt())
                isClickable = true
                setOnClickListener { text.action.invoke() }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        root.addView(stage, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 6f))
    }

    private fun gap() = (unit * 1.2f).toInt()

    private fun updateBar() {
        val view = bar ?: return
        view.fraction = when (val phase = machine.phase) {
            is Phase.Asking -> (phase.endsAt - now()).toFloat() / config.askMs
            is Phase.Go -> (phase.endsAt - now()).toFloat() / config.reflexWindowMs
            else -> 0f
        }.coerceIn(0f, 1f)
        view.invalidate()
    }

    private class TileLook(val status: String, val fill: Int, val stroke: Int, val text: Int)

    private fun look(seat: Seat): TileLook {
        val playing = seat in machine.joined
        val down = (positions[seat] ?: 0) > 0
        val idle = TileLook(if (playing) "준비됐어요" else "쉬는 자리", panel, if (playing) dim else panel, if (playing) cream else dim)
        return when (val phase = machine.phase) {
            is Phase.Locked -> TileLook("", panel, panel, dim)
            Phase.Lobby -> when {
                playing && down -> TileLook("참가! 이제 올려 주세요", panel, glass, cream)
                playing -> TileLook("참가!", panel, glass, cream)
                down -> TileLook("올렸다가 내리면 참가", panel, panel, dim)
                else -> TileLook("창문을 내리면 참가", panel, panel, dim)
            }
            is Phase.Between -> if (seat in phase.raise) TileLook("창문을 올려 주세요", panel, glass, cream) else idle
            is Phase.Asking -> when {
                !playing -> idle
                seat in phase.answeredO -> TileLook("O를 골랐어요", glass, glass, night)
                else -> TileLook("그대로 두면 X", panel, dim, cream)
            }
            is Phase.Steady -> if (seat in phase.tooEarly) TileLook("너무 빨랐어요", panel, coral, coral) else idle
            is Phase.Go -> when {
                !playing -> idle
                seat in phase.tooEarly -> TileLook("이번 판은 쉬어요", panel, coral, coral)
                else -> TileLook("지금!", panel, gold, gold)
            }
            is Phase.Reveal -> when (val result = phase.result) {
                is RoundResult.Ox -> when (seat) {
                    in result.correct -> TileLook("★ 맞혔어요", gold, gold, night)
                    in result.wrong -> TileLook("아쉬워요", panel, dim, dim)
                    else -> idle
                }
                is RoundResult.Reflex -> when {
                    seat == result.winner -> TileLook("★ 가장 빨랐어요", gold, gold, night)
                    seat in result.tooEarly -> TileLook("너무 빨랐어요", panel, coral, coral)
                    else -> idle
                }
            }
            is Phase.Finished -> when {
                seat in phase.openWindows -> TileLook("창문이 열려 있어요", panel, glass, cream)
                playing -> TileLook("수고했어요", panel, dim, cream)
                else -> idle
            }
        }
    }

    private fun tile(seat: Seat): View {
        val look = look(seat)
        val stars = machine.stars[seat]
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            contentDescription = "seat-${seat.name}"
            background = GradientDrawable().apply { setColor(look.fill); cornerRadius = unit * 3; setStroke((unit * 0.5f).toInt(), look.stroke) }
            setPadding((unit * 2).toInt(), (unit * 2).toInt(), (unit * 2).toInt(), (unit * 2).toInt())
            addView(label(seat.label, 3.8f, look.text, bold = true).apply { gravity = Gravity.CENTER })
            addView(GlassView(context, positions[seat] ?: 0, if (look.fill == panel) glass else night, look.text),
                LinearLayout.LayoutParams((unit * 17).toInt(), (unit * 12).toInt()).apply { topMargin = (unit * 2).toInt(); bottomMargin = (unit * 2).toInt() })
            addView(label(look.status, 2.9f, look.text).apply { gravity = Gravity.CENTER })
            if (stars != null) addView(label("★ $stars", 3.8f, if (look.fill == panel) gold else night, bold = true).apply { gravity = Gravity.CENTER })
        }
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_PX, unit * size)
        setTextColor(color)
        setLineSpacing(0f, 1.2f)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    /** A small picture of one car window: the glass sits lower the further the window is opened (0..10). */
    private class GlassView(context: Context, private val position: Int, private val glassColor: Int, private val frameColor: Int) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val box = RectF()
        override fun onDraw(canvas: Canvas) {
            val stroke = height * 0.06f
            // One notch is only a tenth of the travel, so any opening is drawn at least a third down to be visible.
            val open = if (position <= 0) 0f else 0.33f + 0.67f * position.coerceIn(0, 10) / 10f
            paint.style = Paint.Style.FILL
            paint.color = glassColor
            paint.alpha = 190
            box.set(stroke, stroke + (height - 2 * stroke) * open, width - stroke, height - stroke)
            canvas.drawRoundRect(box, stroke, stroke, paint)
            paint.alpha = 255
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = stroke
            paint.color = frameColor
            box.set(stroke / 2, stroke / 2, width - stroke / 2, height - stroke / 2)
            canvas.drawRoundRect(box, stroke * 2, stroke * 2, paint)
        }
    }

    /** Time left in the current round. */
    private class BarView(context: Context, private val color: Int, private val track: Int) : View(context) {
        var fraction = 1f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val box = RectF()
        override fun onDraw(canvas: Canvas) {
            val radius = height / 2f
            paint.color = track
            box.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(box, radius, radius, paint)
            paint.color = color
            box.set(0f, 0f, width * fraction, height.toFloat())
            canvas.drawRoundRect(box, radius, radius, paint)
        }
    }

    /** Lifetime stars and the question cursor, in private app storage. Nothing about people is stored. */
    private class StoredProgress(context: Context) : Progress {
        private val store = context.getSharedPreferences("progress", Context.MODE_PRIVATE)
        override var lifetimeStars: Int
            get() = store.getInt("lifetimeStars", 0)
            set(value) { store.edit().putInt("lifetimeStars", value).apply() }
        override var cursor: Int
            get() = store.getInt("cursor", 0)
            set(value) { store.edit().putInt("cursor", value).apply() }
    }
}
