package ai.gyeote.app

/**
 * Preserve the earlier integrations behind explicit product switches.
 * The family features (connection number, sent routes, arrival news, voice messages, server sync)
 * stay in the code and their domain/data tests, but are off in the product UI since the 10-26 pivot.
 */
object Features {
    const val generalJourneySharing = false
    const val warningSharing = false
    const val locationSharing = false
    const val familyVoiceMessages = false
    const val tireNumbers = false
    const val standaloneMaintenance = false
    /** Connection number, recipients and consent screens. */
    const val familyConnection = false
    /** Routes sent from the phone web page and the arrival news that follows them. */
    const val sharedRoutes = false
    /** Polling and event delivery to the family server. */
    const val serverSync = false
    /** F1: the car hands its own state over to the next driver. */
    const val handover = true
    /** F2: the "first 3 minutes" tour with real controls, parked only. */
    const val tour = true
}
