package ai.gyeote.app.domain

enum class WarningCode {
    TPMS, ENGINE, ABS, ESC, AIRBAG, SEAT_BELT, LOW_FUEL, LOW_EV_BATTERY,
    ENGINE_OIL_PRESSURE, ENGINE_TEMPERATURE, PARKING_BRAKE, DOOR_OPEN,
    MASTER_WARNING, LOW_VOLT_BATTERY, TRUNK_OPEN, FCA, SAFETY_ASSIST, UNKNOWN,
}

enum class Urgency(val label: String) {
    STOP_SAFELY("지금 안전한 곳에 세우기"), CHECK_SOON("곧 점검"), INFORMATION("참고"),
}

data class WarningAdvice(
    val code: WarningCode,
    val title: String,
    val urgency: Urgency,
    val meaning: String,
    val action: String,
    val movingText: String,
)

object WarningDictionary {
    private fun advice(
        code: WarningCode, title: String, urgency: Urgency,
        meaning: String, action: String, movingText: String = "$meaning $action",
    ) = WarningAdvice(code, title, urgency, meaning, action, movingText)

    private val entries = listOf(
        advice(WarningCode.TPMS, "타이어 바람", Urgency.CHECK_SOON,
            "타이어 공기압이 낮을 수 있어요.", "안전한 곳에 세운 뒤 확인하고 점검을 권해요."),
        advice(WarningCode.ENGINE, "엔진 점검", Urgency.CHECK_SOON,
            "엔진이나 배출가스 장치에 이상이 있을 수 있어요.", "무리한 운전을 피하고 정비소 점검을 권해요."),
        advice(WarningCode.ABS, "제동 보조 장치", Urgency.CHECK_SOON,
            "급제동 때 바퀴 잠김을 줄이는 기능에 이상이 있을 수 있어요.", "차간 거리를 넉넉히 두고 정비소 점검을 권해요."),
        advice(WarningCode.ESC, "차체 자세 보조", Urgency.CHECK_SOON,
            "미끄러짐을 줄이는 장치가 작동 중이거나 점검이 필요할 수 있어요.", "천천히 운전하고 계속 켜져 있으면 점검을 권해요."),
        advice(WarningCode.AIRBAG, "에어백 점검", Urgency.CHECK_SOON,
            "에어백 장치에 이상이 있을 수 있어요.", "가까운 정비소에서 점검을 권해요."),
        advice(WarningCode.SEAT_BELT, "안전띠", Urgency.STOP_SAFELY,
            "안전띠가 채워지지 않았을 수 있어요.", "안전한 곳에 세우고 탑승자의 안전띠를 살펴 주세요."),
        advice(WarningCode.LOW_FUEL, "연료 보충", Urgency.INFORMATION,
            "남은 연료가 적을 수 있어요.", "가까운 주유소에서 보충을 권해요."),
        advice(WarningCode.LOW_EV_BATTERY, "배터리 충전", Urgency.INFORMATION,
            "주행용 배터리 잔량이 적을 수 있어요.", "가까운 충전소에서 충전을 권해요."),
        advice(WarningCode.ENGINE_OIL_PRESSURE, "엔진오일 압력", Urgency.STOP_SAFELY,
            "엔진오일 압력이 낮을 수 있어요.", "지금 안전한 곳에 세우고 시동을 끈 뒤 정비소에 문의해 주세요."),
        advice(WarningCode.ENGINE_TEMPERATURE, "엔진 온도", Urgency.STOP_SAFELY,
            "엔진 온도가 높을 수 있어요.", "안전한 곳에 세우고 뜨거운 냉각수 뚜껑은 열지 말고 정비소에 문의해 주세요."),
        advice(WarningCode.PARKING_BRAKE, "주차 브레이크", Urgency.STOP_SAFELY,
            "주차 브레이크가 걸려 있거나 제동 장치 점검이 필요할 수 있어요.", "안전한 곳에 세워 확인하고 계속 켜져 있으면 점검을 권해요."),
        advice(WarningCode.DOOR_OPEN, "문 닫힘", Urgency.STOP_SAFELY,
            "차 문이 덜 닫혔을 수 있어요.", "안전한 곳에 세운 뒤 문을 다시 닫아 주세요."),
        advice(WarningCode.MASTER_WARNING, "차량 종합 안내", Urgency.STOP_SAFELY,
            "차량에서 함께 살펴볼 안내가 있을 수 있어요.", "안전한 곳에 세운 뒤 차량 안내를 살피고 정비소에 문의해 주세요."),
        advice(WarningCode.LOW_VOLT_BATTERY, "보조 배터리", Urgency.CHECK_SOON,
            "보조 배터리나 충전 장치에 이상이 있을 수 있어요.", "안전한 곳에서 정비소 점검을 권해요."),
        advice(WarningCode.TRUNK_OPEN, "트렁크 닫힘", Urgency.STOP_SAFELY,
            "트렁크가 덜 닫혔을 수 있어요.", "안전한 곳에 세운 뒤 닫힘을 살펴 주세요."),
        advice(WarningCode.FCA, "전방 충돌방지 보조", Urgency.INFORMATION,
            "앞차나 장애물이 가까워 차가 스스로 속도를 줄였을 수 있어요.", "주변을 살피고 안전거리를 유지해 주세요."),
        advice(WarningCode.SAFETY_ASSIST, "차량 안전 기능", Urgency.INFORMATION,
            "차의 안전 기능이 작동했을 수 있어요.", "차량 설명서를 확인해 주세요."),
        advice(WarningCode.UNKNOWN, "차량 안내", Urgency.CHECK_SOON,
            "차량에서 점검이 필요한 안내가 있을 수 있어요.", "정차 후 차량 설명서를 살피고 정비소 점검을 권해요."),
    ).associateBy { it.code }

    fun lookup(code: WarningCode, red: Boolean = false): WarningAdvice {
        val entry = entries.getValue(code)
        return if (red && entry.urgency != Urgency.STOP_SAFELY) entry.copy(
            urgency = Urgency.STOP_SAFELY,
            action = "지금 안전한 곳에 세운 뒤 정비소에 문의해 주세요.",
            movingText = "${entry.meaning} 지금 안전한 곳에 세운 뒤 정비소에 문의해 주세요.",
        ) else entry
    }

    fun mostUrgent(codes: Set<WarningCode>): WarningAdvice? = codes.map { lookup(it) }.minByOrNull { it.urgency.ordinal }
}
