package ai.gyeote.app.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import ai.gyeote.app.domain.Consent
import ai.gyeote.app.domain.MaintenanceItem
import org.json.JSONObject
import org.json.JSONArray
import ai.gyeote.app.domain.Destination
import ai.gyeote.app.domain.WarningRecord
import ai.gyeote.app.domain.RouteArrivalFlow
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** App-private AES-GCM state, with the key kept by Android Keystore; excluded from backup. */
class SecureStore(context: Context, testProfile: String? = null) {
    private val file = AtomicFile(File(context.filesDir, testProfile?.let {
        require(it.matches(Regex("[A-Za-z0-9_-]{1,40}")))
        "state-test-$it.enc"
    } ?: "state.enc"))
    private val key: SecretKey
    private var state: JSONObject

    init {
        val alias = "${context.packageName}.state.v1"
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        key = (keyStore.getKey(alias, null) as? SecretKey) ?: KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore",
        ).apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
        state = if (file.baseFile.exists()) {
            val bytes = file.openRead().use { it.readBytes() }
            require(bytes.size > 29 && bytes[0] == 1.toByte()) { "저장된 설정을 열 수 없어요." }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            }
            JSONObject(cipher.doFinal(bytes.copyOfRange(13, bytes.size)).toString(Charsets.UTF_8))
        } else JSONObject()
    }

    @Synchronized private fun change(block: (JSONObject) -> Unit) {
        val next = JSONObject(state.toString()).also(block)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key) }
        val payload = byteArrayOf(1) + cipher.iv + cipher.doFinal(next.toString().toByteArray(Charsets.UTF_8))
        val stream = file.startWrite()
        try { stream.write(payload); file.finishWrite(stream); state = next }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    @Synchronized fun consent(): Consent = state.optJSONObject("consent")?.let(ApiJson::consent) ?: Consent()
    @Synchronized fun purgePending(): Boolean = state.optBoolean("purgePending", false)
    fun saveConsent(consent: Consent, purgePending: Boolean) = change {
        it.put("consent", ApiJson.consent(consent)); it.put("purgePending", purgePending)
    }
    @Synchronized fun session(): FamilySession? = state.optJSONObject("session")?.let {
        FamilySession(it.getString("familyId"), it.getString("pairCode"), it.getString("parentToken"))
    }
    fun saveSession(session: FamilySession) = change {
        it.put("session", JSONObject().put("familyId", session.familyId).put("pairCode", session.pairCode).put("parentToken", session.parentToken))
    }
    @Synchronized fun serverUrl(): String = state.optString("serverUrl", "http://10.0.2.2:8787")
    fun saveServerUrl(value: String) = change { it.put("serverUrl", value) }
    @Synchronized fun warningHistory(): List<WarningRecord> = EdgeJson.history(state.optJSONArray("warningHistory") ?: JSONArray())
    fun saveWarningHistory(records: List<WarningRecord>) = change { it.put("warningHistory", EdgeJson.history(records)) }
    @Synchronized fun arrival(): RouteArrivalFlow = EdgeJson.arrival(state.optJSONObject("routeArrival"))
    fun saveArrival(flow: RouteArrivalFlow) = change { it.put("routeArrival", EdgeJson.arrival(flow)) }
    @Synchronized fun maintenance(): Map<MaintenanceItem, Long> {
        val values = state.optJSONObject("maintenance") ?: return emptyMap()
        return MaintenanceItem.entries.mapNotNull { item ->
            if (values.has(item.name)) item to values.getLong(item.name) else null
        }.toMap()
    }
    fun saveMaintenance(records: Map<MaintenanceItem, Long>) = change { root ->
        root.put("maintenance", JSONObject().also { json -> records.forEach { (item, km) -> json.put(item.name, km) } })
    }
    @Synchronized fun responses(): Map<String, String> {
        val values = state.optJSONObject("responses") ?: return emptyMap()
        return values.keys().asSequence().associateWith { values.getString(it) }
    }
    fun saveResponse(id: String, status: String?) = change { root ->
        val values = root.optJSONObject("responses") ?: JSONObject()
        if (status == null) values.remove(id) else values.put(id, status)
        root.put("responses", values)
    }
    @Synchronized fun destinations(): List<Destination> {
        val values = state.optJSONArray("destinations") ?: return emptyList()
        return (0 until values.length()).map { ApiJson.destination(values.getJSONObject(it)) }
    }
    fun saveDestinations(destinations: List<Destination>) = change { root ->
        root.put("destinations", JSONArray().apply {
            destinations.takeLast(100).forEach { destination -> put(JSONObject().apply {
                put("id", destination.id); put("fromChildId", destination.fromChildId)
                put("name", destination.name); put("address", destination.address)
                put("lat", destination.lat); put("lng", destination.lng)
                put("note", destination.note); put("status", destination.status)
            }) }
        })
    }
}
