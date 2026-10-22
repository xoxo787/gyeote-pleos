package ai.gyeote.app.domain

enum class DestinationIntent { ACCEPT, DEFER, DECLINE, ASK_AGAIN }

object KoreanIntentParser {
    private val accepts = setOf("응", "네", "예", "좋아", "좋아요", "가자", "그래", "그래요", "출발", "네 좋아요", "안내 시작해줘")
    private val defers = setOf("나중에", "다음에", "이따", "이따가", "나중에요", "다음에요", "다음에 할게요", "나중에 할게요")
    private val declines = setOf("아니", "아니요", "싫어", "싫어요", "취소", "취소해줘")
    fun parse(text: String): DestinationIntent {
        val normalized = text.trim().replace(Regex("[.,!?。！？]"), "").replace(Regex("\\s+"), " ")
        return when (normalized) {
            in accepts -> DestinationIntent.ACCEPT
            in defers -> DestinationIntent.DEFER
            in declines -> DestinationIntent.DECLINE
            else -> DestinationIntent.ASK_AGAIN
        }
    }
}

data class Destination(
    val id: String, val fromChildId: String, val name: String, val address: String,
    val lat: Double, val lng: Double, val note: String, val status: String = "pending",
)

class DestinationInbox {
    private val items = linkedMapOf<String, Destination>()
    private val prompted = mutableSetOf<String>()
    var restricted: Boolean = false
    fun snapshot(): List<Destination> = items.values.toList()
    fun restore(destinations: List<Destination>) {
        destinations.filter { it.status in setOf("pending", "deferred", "accepted", "declined", "arrived") }.forEach { items[it.id] = it }
    }
    fun receive(destination: Destination) {
        if (destination.status == "pending" && items[destination.id]?.status !in setOf("accepted", "declined", "deferred")) {
            items[destination.id] = destination
        }
    }
    fun all(): List<Destination> = items.values.filter { it.status in setOf("pending", "deferred") }
    fun accepted(): Destination? = items.values.lastOrNull { it.status == "accepted" }
    fun find(id: String): Destination? = items[id]
    fun arrived(id: String) { items[id]?.let { items[id] = it.copy(status = "arrived") } }
    fun nextPrompt(): Destination? {
        if (restricted) return null
        return items.values.firstOrNull { it.status == "pending" && it.id !in prompted }?.also { prompted += it.id }
    }
    fun respond(id: String, intent: DestinationIntent): Destination? {
        if (restricted || intent == DestinationIntent.ASK_AGAIN) return null
        val existing = items[id]?.takeIf { it.status in setOf("pending", "deferred") } ?: return null
        val status = when (intent) {
            DestinationIntent.ACCEPT -> "accepted"
            DestinationIntent.DEFER -> "deferred"
            DestinationIntent.DECLINE -> "declined"
            DestinationIntent.ASK_AGAIN -> return null
        }
        return existing.copy(status = status).also { items[id] = it }
    }
}
