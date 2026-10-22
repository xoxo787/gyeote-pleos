package ai.gyeote.app.data

import ai.gyeote.app.domain.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.concurrent.TimeUnit

class FamilySession(val familyId: String, val pairCode: String, val parentToken: String) {
    override fun toString(): String = "FamilySession([private])"
}
data class Child(val id: String, val name: String)
data class FamilyMessage(val id: String, val fromChildId: String, val mime: String, val durationSec: Int)
class ApiException(val status: Int, val code: String) : IOException("서버 응답을 다시 살펴 주세요 ($status).")

object ApiJson {
    fun consent(value: Consent): JSONObject = JSONObject().apply {
        put("shareDeparture", value.shareDeparture); put("shareArrival", value.shareArrival)
        put("shareAlerts", value.shareAlerts); put("shareLocation", value.shareLocation)
        put("shareRouteArrival", value.shareRouteArrival)
        put("recipients", JSONArray(value.recipients.toList()))
        put("until", value.until?.toString() ?: JSONObject.NULL)
    }
    fun consent(json: JSONObject): Consent {
        val recipients = json.optJSONArray("recipients") ?: JSONArray()
        return Consent(json.optBoolean("shareDeparture"), json.optBoolean("shareArrival"),
            json.optBoolean("shareAlerts"), json.optBoolean("shareLocation"),
            (0 until recipients.length()).map { recipients.getString(it) }.toSet(),
            json.optString("until").takeIf { it.isNotBlank() && it != "null" }?.let(Instant::parse),
            shareRouteArrival = json.optBoolean("shareRouteArrival", false))
    }
    fun destination(json: JSONObject) = Destination(
        json.getString("id"), json.getString("fromChildId"), json.getString("name"), json.getString("address"),
        json.getDouble("lat"), json.getDouble("lng"), json.optString("note"), json.getString("status"),
    )
    fun event(event: SharedEvent) = JSONObject().apply {
        put("type", event.type.wire); put("at", event.at.toString()); put("summary", event.summary)
        event.destinationId?.let { put("destinationId", it) }
        event.location?.let { put("location", JSONObject().put("lat", it.lat).put("lng", it.lng)) }
    }
}

class ApiClient(
    baseUrl: String,
    private val session: FamilySession? = null,
    allowLocalHttp: Boolean = false,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS).followRedirects(false).build(),
    private val onEventRequest: ((SharedEventType) -> Unit)? = null,
) {
    private val base: HttpUrl = baseUrl.trim().trimEnd('/').toHttpUrl().also { url ->
        require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null)
        require(url.encodedPath == "/") { "서버의 기본 주소만 입력해 주세요." }
        require(url.scheme == "https" || (allowLocalHttp && url.host in setOf("10.0.2.2", "127.0.0.1", "localhost"))) {
            "HTTPS 주소를 입력해 주세요. 개발용 주소는 이 기기 안의 서버만 허용해요."
        }
    }

    private fun url(vararg parts: String, query: Pair<String, String>? = null): HttpUrl = base.newBuilder().apply {
        parts.forEach(::addPathSegment)
        query?.let { addQueryParameter(it.first, it.second) }
    }.build()
    private fun familyUrl(tail: String, query: Pair<String, String>? = null) =
        url("api", "families", requireNotNull(session).familyId, tail, query = query)

    private fun request(method: String, url: HttpUrl, body: JSONObject? = null, auth: Boolean = true): ByteArray {
        val data = body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(url).apply {
            if (auth) header("Authorization", "Bearer ${requireNotNull(session).parentToken}")
            header("Accept", "application/json")
            method(method, if (method in setOf("POST", "PUT", "PATCH")) data ?: ByteArray(0).toRequestBody() else null)
        }.build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val code = runCatching { JSONObject(response.body?.string().orEmpty()).optString("error") }.getOrDefault("unknown")
                throw ApiException(response.code, code)
            }
            val responseBody = response.body ?: return@use ByteArray(0)
            if (responseBody.contentLength() > 2 * 1024 * 1024) throw IOException("응답이 너무 커요.")
            responseBody.byteStream().use { stream ->
                val result = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    if (result.size() + count > 2 * 1024 * 1024) throw IOException("응답이 너무 커요.")
                    result.write(buffer, 0, count)
                }
                result.toByteArray()
            }
        }
    }
    private fun json(bytes: ByteArray) = JSONObject(bytes.toString(Charsets.UTF_8))
    private fun array(bytes: ByteArray) = JSONArray(bytes.toString(Charsets.UTF_8))
    fun createFamily(parentName: String): FamilySession {
        val json = json(request("POST", url("api", "families"), JSONObject().put("parentName", parentName), auth = false))
        return FamilySession(json.getString("familyId"), json.getString("pairCode"), json.getString("parentToken"))
    }
    fun issuePairCode(): PairingCode {
        val json = json(request("POST", familyUrl("pair-code")))
        return PairingCode(json.getString("pairCode"), Instant.parse(json.getString("expiresAt")))
    }
    fun getConsent(): Consent = ApiJson.consent(json(request("GET", familyUrl("consent"))))
    fun putConsent(consent: Consent) { request("PUT", familyUrl("consent"), ApiJson.consent(consent)) }
    fun deleteSharedData() { request("DELETE", familyUrl("shared-data")) }
    fun postEvent(event: SharedEvent) {
        onEventRequest?.invoke(event.type)
        request("POST", familyUrl("events"), ApiJson.event(event))
    }
    fun children(): List<Child> {
        val items = array(request("GET", familyUrl("children")))
        return (0 until items.length()).map { items.getJSONObject(it).let { child -> Child(child.getString("id"), child.getString("name")) } }
    }
    fun pendingDestinations(): List<Destination> {
        val items = array(request("GET", familyUrl("destinations", "status" to "pending")))
        return (0 until items.length()).map { ApiJson.destination(items.getJSONObject(it)) }
    }
    fun respondDestination(id: String, status: String): Destination {
        require(status in setOf("accepted", "deferred", "declined"))
        return ApiJson.destination(json(request("PATCH", url("api", "destinations", id), JSONObject().put("status", status))))
    }
    fun messages(): List<FamilyMessage> {
        val items = array(request("GET", familyUrl("messages", "unplayed" to "1")))
        return (0 until items.length()).map { items.getJSONObject(it).let { message ->
            FamilyMessage(message.getString("id"), message.getString("fromChildId"), message.getString("mime"), message.getInt("durationSec"))
        } }
    }
    fun audio(id: String): ByteArray = request("GET", url("api", "messages", id, "audio"))
    fun markPlayed(id: String) { request("PATCH", url("api", "messages", id), JSONObject().put("played", true)) }
    fun sharingRemote() = object : SharingRemote {
        override fun putConsent(consent: Consent) = this@ApiClient.putConsent(consent)
        override fun deleteSharedData() = this@ApiClient.deleteSharedData()
        override fun postEvent(event: SharedEvent) = this@ApiClient.postEvent(event)
    }
}
