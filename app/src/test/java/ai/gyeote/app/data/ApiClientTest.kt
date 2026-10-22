package ai.gyeote.app.data

import ai.gyeote.app.domain.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class ApiClientTest {
    private val server = MockWebServer()
    private lateinit var api: ApiClient
    private val session = FamilySession("family-id", "123456", "dummy-test-token")
    @Before fun start() {
        server.start(InetAddress.getByName("127.0.0.1"), 0)
        api = ApiClient(server.url("/").newBuilder().host("127.0.0.1").build().toString(), session, allowLocalHttp = true)
    }
    @After fun close() { server.shutdown() }
    private fun json(value: String, status: Int = 200) = server.enqueue(MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json; charset=utf-8").setBody(value))
    private val destination = """{"id":"dest-id","fromChildId":"child-id","name":"병원","address":"서울","lat":37.0,"lng":127.0,"note":"천천히 오세요","status":"pending"}"""

    @Test fun createFamilyUsesUtf8AndNoAuthorizationHeader() {
        json("""{"familyId":"family-id","pairCode":"123456","parentToken":"dummy-test-token"}""", 201)
        assertEquals("family-id", api.createFamily("부모님").familyId)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method); assertEquals("/api/families", request.path)
        assertNull(request.getHeader("Authorization"))
        assertEquals("부모님", JSONObject(request.body.readUtf8()).getString("parentName"))
    }
    @Test fun pairCodeViewingIssuesFreshAuthenticatedNumberEachTime() {
        for ((digits, expiry) in listOf("001234" to "2026-10-22T01:10:00Z", "009876" to "2026-10-22T01:11:00Z")) {
            json("""{"pairCode":"$digits","expiresAt":"$expiry"}""", 201)
            val code = api.issuePairCode()
            assertEquals(digits, code.digits)
            assertEquals(Instant.parse(expiry), code.expiresAt)
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("POST", request.method)
            assertEquals("/api/families/family-id/pair-code", request.path)
            assertEquals("Bearer dummy-test-token", request.getHeader("Authorization"))
        }
    }
    @Test fun pairCodeFailureDoesNotFallBackToPersistedSessionNumber() {
        json("""{"error":"unauthorized"}""", 401)
        val error = runCatching { api.issuePairCode() }.exceptionOrNull() as ApiException
        assertEquals(401, error.status)
        assertEquals("unauthorized", error.code)
        json("""{"pairCode":"001234","expiresAt":"invalid-time"}""", 201)
        assertTrue(runCatching { api.issuePairCode() }.isFailure)
    }
    @Test fun consentReadAndWriteMatchCamelCaseAndExplicitRecipients() {
        val consent = Consent(shareDeparture = true, recipients = setOf("child-id"), until = Instant.parse("2026-10-23T00:00:00Z"))
        json(ApiJson.consent(consent).toString()); assertEquals(consent, api.getConsent())
        assertEquals("/api/families/family-id/consent", server.takeRequest().path)
        json(ApiJson.consent(consent).toString()); api.putConsent(consent)
        val request = server.takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("Bearer dummy-test-token", request.getHeader("Authorization"))
        assertEquals(consent, ApiJson.consent(JSONObject(request.body.readUtf8())))
    }
    @Test fun eventHasUtcTimeAndNoLocationWithoutPermission() {
        val now = Instant.parse("2026-10-22T01:02:03Z")
        val event = Consent(shareArrival = true, recipients = setOf("child-id")).filter(
            SharedEvent(SharedEventType.ARRIVED, now, "도착했어요", Coordinates(37.0, 127.0)), now)!!
        json("{}", 201); api.postEvent(event)
        val request = server.takeRequest()
        assertEquals("POST", request.method); assertEquals("/api/families/family-id/events", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("arrived", body.getString("type")); assertEquals(now.toString(), body.getString("at"))
        assertFalse(body.has("location"))
    }
    @Test fun revocationUsesAuthenticatedDeleteAndAcceptsEmpty204() {
        server.enqueue(MockResponse().setResponseCode(204)); api.deleteSharedData()
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api/families/family-id/shared-data", request.path)
        assertNotNull(request.getHeader("Authorization"))
    }
    @Test fun destinationPollingAndPatchRespectContract() {
        json("[$destination]"); assertEquals("병원", api.pendingDestinations().single().name)
        assertEquals("/api/families/family-id/destinations?status=pending", server.takeRequest().path)
        json(destination.replace("pending", "accepted")); assertEquals("accepted", api.respondDestination("dest-id", "accepted").status)
        val request = server.takeRequest()
        assertEquals("PATCH", request.method); assertEquals("/api/destinations/dest-id", request.path)
        assertEquals("accepted", JSONObject(request.body.readUtf8()).getString("status"))
    }
    @Test fun messageListAudioAndPlayedAcknowledgementUseSeparateEndpoints() {
        json("""[{"id":"message-id","fromChildId":"child-id","mime":"audio/wav","durationSec":2}]""")
        assertEquals(2, api.messages().single().durationSec)
        assertEquals("/api/families/family-id/messages?unplayed=1", server.takeRequest().path)
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/wav").setBody("wave-bytes"))
        assertEquals("wave-bytes", api.audio("message-id").toString(Charsets.UTF_8))
        assertEquals("/api/messages/message-id/audio", server.takeRequest().path)
        json("{}"); api.markPlayed("message-id")
        val request = server.takeRequest()
        assertEquals("PATCH", request.method); assertEquals("/api/messages/message-id", request.path)
        assertTrue(JSONObject(request.body.readUtf8()).getBoolean("played"))
    }
    @Test fun childListContainsDisplayNamesOnly() {
        json("""[{"id":"child-id","name":"가족"}]""")
        assertEquals(listOf(Child("child-id", "가족")), api.children())
        assertEquals("/api/families/family-id/children", server.takeRequest().path)
    }
    @Test fun consentDenialIsAnErrorRatherThanFalseSuccess() {
        json("""{"error":"consent_required","message":"동의가 필요해요"}""", 403)
        val error = runCatching { api.postEvent(SharedEvent(SharedEventType.DEPARTED, Instant.now(), "출발했어요")) }.exceptionOrNull()
        assertTrue(error is ApiException)
        assertEquals("consent_required", (error as ApiException).code)
        assertEquals(403, error.status)
    }
    @Test fun localTransportNeverFollowsRedirectWithParentAuthorization() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.invalid/"))
        assertTrue(runCatching { api.children() }.exceptionOrNull() is ApiException)
        assertEquals(1, server.requestCount)
    }
    @Test(expected = IllegalArgumentException::class) fun remoteCleartextIsRejectedEvenInDebug() {
        ApiClient("http://example.com:8787", session, allowLocalHttp = true)
    }
    @Test(expected = IllegalArgumentException::class) fun releaseDisallowsEvenLocalCleartext() {
        ApiClient("http://10.0.2.2:8787", session)
    }
    @Test(expected = IllegalArgumentException::class) fun credentialsInUrlAreRejected() {
        ApiClient("https://user:password@example.com", session)
    }
    @Test fun sessionTextDoesNotExposeAuthentication() {
        assertFalse(session.toString().contains("dummy-test-token"))
    }
    @Test fun routeConsentRoundTripsAndWireDefaultsFalseForOldServer() {
        val consent = Consent(recipients = setOf("child-id"), shareRouteArrival = true)
        json(ApiJson.consent(consent).toString()); api.putConsent(consent)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertTrue(body.getBoolean("shareRouteArrival")); assertFalse(body.getBoolean("shareArrival"))
        assertEquals(consent, ApiJson.consent(body))
    }
    @Test fun routeArrivalPostContainsDestinationAndNoCoordinates() {
        json("{}", 201)
        api.postEvent(SharedEvent(SharedEventType.ARRIVED, Instant.parse("2026-10-22T00:00:00Z"), "병원에 잘 도착했어요", destinationId = "dest-id"))
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("dest-id", body.getString("destinationId")); assertEquals("arrived", body.getString("type"))
        assertFalse(body.has("location"))
    }
    @Test fun conflictIsAnExplicitErrorAndRetainedInDurableQueue() {
        json("""{"error":"invalid_state"}""", 409)
        val event = SharedEvent(SharedEventType.ARRIVED, Instant.now(), "도착", destinationId = "dest-id")
        val flow = RouteArrivalFlow(listOf(QueuedArrival(event)))
        val error = runCatching { api.postEvent(flow.queue.single().event) }.exceptionOrNull() as ApiException
        assertEquals(409, error.status); assertEquals("invalid_state", error.code)
        flow.failed("dest-id", ArrivalFailure.CONFLICT)
        assertEquals(1, EdgeJson.arrival(EdgeJson.arrival(flow)).queue.size)
    }
}
