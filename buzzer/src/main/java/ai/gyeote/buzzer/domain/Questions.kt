package ai.gyeote.buzzer.domain

import java.util.Locale
import kotlin.math.roundToInt

/** One true/false statement. [why] is shown and read after the answer. */
data class Question(val text: String, val truth: Boolean, val why: String, val fromCar: Boolean = false)

/** What the car reports right now. Null means the signal has not arrived or is not supported. */
data class CarFacts(
    val socPercent: Int? = null,
    val charging: Boolean? = null,
    val rangeKm: Double? = null,
    val odometerKm: Double? = null,
    val chargePortOpen: Boolean? = null,
)

/**
 * Hands out the questions of a session. Bank questions go in order from [cursor] (kept across sessions so
 * they do not repeat); every [carEvery]-th question is made from live car data when the car has any.
 */
class QuestionSource(private val bank: List<Question>, cursor: Int = 0, private val carEvery: Int = 3) {
    init { require(bank.isNotEmpty()) }
    var cursor = cursor
        private set
    private var asked = 0
    private var carAsked = 0

    fun next(facts: CarFacts): Question {
        asked++
        if (carEvery > 0 && asked % carEvery == 0) {
            CarQuestions.make(facts, carAsked)?.let { carAsked++; return it }
        }
        return bank[Math.floorMod(cursor++, bank.size)]
    }
}

/** Questions whose answer is the car's own state at this moment. */
object CarQuestions {
    const val EARTH_LAP_KM = 40_000
    const val RANGE_MARK_KM = 200

    fun make(facts: CarFacts, n: Int): Question? {
        val makers = buildList<() -> Question> {
            facts.socPercent?.let { soc ->
                add { Question("지금 이 차의 배터리는 절반보다 많이 남아 있다.", soc > 50, "지금 배터리는 ${soc}%예요.", fromCar = true) }
            }
            facts.rangeKm?.let { range ->
                val km = range.roundToInt()
                add { Question("지금 남은 전기로 ${RANGE_MARK_KM}km를 갈 수 있다.", km >= RANGE_MARK_KM, "지금 갈 수 있는 거리는 약 ${km}km예요.", fromCar = true) }
            }
            facts.odometerKm?.let { odometer ->
                val km = odometer.roundToInt()
                add {
                    Question("이 차는 지금까지 지구 한 바퀴보다 많이 달렸다.", km > EARTH_LAP_KM,
                        "지구 한 바퀴는 약 4만 km이고, 이 차는 지금까지 ${String.format(Locale.KOREA, "%,d", km)}km를 달렸어요.", fromCar = true)
                }
            }
            facts.chargePortOpen?.let { open ->
                add { Question("지금 이 차의 충전구는 열려 있다.", open, if (open) "충전구가 열려 있어요." else "충전구는 닫혀 있어요.", fromCar = true) }
            }
        }
        if (makers.isEmpty()) return null
        return makers[Math.floorMod(n, makers.size)]()
    }
}

/** Built-in statements. No real organisation, company or person is named. */
object QuestionBank {
    private fun o(text: String, why: String) = Question(text, true, why)
    private fun x(text: String, why: String) = Question(text, false, why)

    val all: List<Question> = listOf(
        o("전기차는 달리는 동안 배기가스를 내보내지 않는다.", "전기 모터로 달려서 배기구가 없어요."),
        x("방향지시등은 차로를 바꾼 다음에 켠다.", "바꾸기 전에 먼저 켜서 알려야 해요."),
        o("문어의 심장은 세 개다.", "아가미로 보내는 심장 둘, 온몸으로 보내는 심장 하나가 있어요."),
        x("달은 스스로 빛을 낸다.", "달은 햇빛을 받아 반사할 뿐이에요."),
        o("전기차는 속도를 줄일 때 전기를 조금 되찾는다.", "바퀴가 도는 힘으로 발전해서 배터리에 다시 담아요."),
        x("뒷좌석에서는 안전벨트를 매지 않아도 된다.", "모든 자리에서 매야 해요."),
        o("빛은 소리보다 빠르다.", "그래서 번개가 먼저 보이고 천둥이 나중에 들려요."),
        x("물은 섭씨 100도에서 언다.", "0도에서 얼고 100도에서 끓어요."),
        o("비 오는 날에는 차가 멈추는 데 더 긴 거리가 필요하다.", "젖은 길은 미끄러워서 그래요."),
        x("펭귄은 하늘을 나는 새다.", "날지 못하지만 헤엄을 아주 잘 쳐요."),
        o("거미의 다리는 여덟 개다.", "곤충은 여섯 개, 거미는 여덟 개예요."),
        x("타이어 공기압은 계절이 바뀌어도 그대로다.", "추워지면 공기압이 내려가요."),
        o("태양은 별이다.", "지구에서 가장 가까운 별이에요."),
        x("지구는 태양 둘레를 하루에 한 바퀴 돈다.", "한 바퀴 도는 데 일 년이 걸려요."),
        o("추운 날에는 전기차가 갈 수 있는 거리가 줄어들 수 있다.", "배터리가 추위에 약하고 난방에도 전기를 써요."),
        x("고래는 물고기다.", "새끼에게 젖을 먹이는 포유류예요."),
        o("우리 몸에서 가장 넓은 기관은 피부다.", "온몸을 덮고 있어요."),
        x("횡단보도에서는 차가 사람보다 먼저 간다.", "사람이 먼저예요."),
        o("빠른 충전기에서도 배터리가 거의 찼을 때는 충전이 느려진다.", "배터리를 아끼려고 속도를 줄여요."),
        x("낙타의 혹에는 물이 들어 있다.", "물이 아니라 지방이 들어 있어요."),
        o("얼음은 물에 뜬다.", "얼면 물보다 가벼워져요."),
        x("전기차는 주유소에서 휘발유를 넣는다.", "충전기에서 전기를 넣어요."),
        o("벌은 춤으로 꽃이 있는 곳을 알린다.", "엉덩이를 흔드는 춤으로 방향과 거리를 알려요."),
        x("신호등의 노란불은 빨리 지나가라는 뜻이다.", "멈출 준비를 하라는 뜻이에요."),
        o("차 문을 열기 전에는 뒤에서 오는 자전거를 살펴야 한다.", "문에 부딪히는 사고를 막을 수 있어요."),
        x("어른이 되면 뼈의 개수가 아기 때보다 늘어난다.", "자라면서 뼈가 서로 붙어 개수가 줄어요."),
        o("북극곰의 털 아래 피부는 검은색이다.", "햇볕의 열을 잘 받으려고 그래요."),
        x("차의 누적 주행거리는 달릴수록 줄어든다.", "달린 만큼 계속 늘어나요."),
        o("지구에서 가장 큰 동물은 대왕고래다.", "길이가 30미터에 이르기도 해요."),
        x("한 시간은 100분이다.", "한 시간은 60분이에요."),
        o("주차할 때는 기어를 P에 둔다.", "P는 차가 움직이지 않게 잡아 줘요."),
        x("와이퍼는 뒷좌석 창문의 빗물을 닦는다.", "앞 유리와 뒤 유리의 빗물을 닦아요."),
        o("어두울 때는 전조등을 켜야 한다.", "내가 보는 데도, 남이 나를 보는 데도 필요해요."),
        x("차에서 내릴 때는 차가 멈추기 전에 문을 연다.", "차가 완전히 멈춘 다음에 열어야 해요."),
        o("무지개는 햇빛이 물방울을 지나며 나뉘어 생긴다.", "그래서 비가 갠 뒤에 자주 보여요."),
        o("사이드미러는 옆과 뒤를 보는 거울이다.", "차로를 바꿀 때 꼭 봐요."),
    )
}
