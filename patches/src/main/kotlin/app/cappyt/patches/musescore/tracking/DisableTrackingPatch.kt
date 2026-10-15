package app.cappyt.patches.musescore.tracking

import java.security.MessageDigest

import app.cappyt.patches.musescore.unlockpro.HERMES_STRING_PATCHES
import app.cappyt.patches.shared.Constants.COMPATIBILITY_MUSESCORE
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import org.w3c.dom.Element

private const val BUNDLE_PATH = "assets/index.android.bundle"
private const val SHA1_TRAILER_SIZE = 20

// Disables Firebase telemetry collection at the SDK level (manifest flags).
private val FIREBASE_OFF = listOf(
    "firebase_analytics_collection_enabled" to "false",
    "firebase_analytics_collection_deactivated" to "true",
    "firebase_crashlytics_collection_enabled" to "false",
    "google_analytics_adid_collection_enabled" to "false",
    "google_analytics_ssaid_collection_enabled" to "false",
    "google_analytics_automatic_screen_reporting_enabled" to "false",
    "google_analytics_default_allow_analytics_storage" to "false",
    "google_analytics_default_allow_ad_storage" to "false",
    "firebase_performance_logcat_enabled" to "false",
)

// Sentry defaults (the JS-side DSN kill in HERMES_STRING_PATCHES disables reporting itself).
private val SENTRY_OFF = listOf(
    "io.sentry.send-default-pii" to "false",
    "io.sentry.auto-init" to "false",
)

/**
 * Kills third-party tracking and crash reporting:
 *
 * - Sentry: the React app initializes Sentry from JS with a hardcoded DSN stored in the Hermes
 *   bundle string table. The DSN (Android project DSN) is rewritten to an invalid scheme, which
 *   makes the SDK disable itself instead of reporting.
 * - AppsFlyer: the React app calls initSdk with a hardcoded devKey from the same string table.
 *   The key is corrupted, so initialization fails and no attribution/install/uninstall events
 *   are collected.
 * - Firebase Analytics / Crashlytics / ADID: disabled via manifest meta-data flags.
 *
 * Same-length in-place rewrites, exactly-one-occurrence verification; the Hermes SHA-1 trailer
 * (last 20 bytes) is recomputed afterwards.
 */
@Suppress("unused")
val disableTrackingPatch = resourcePatch(
    name = "Disable tracking",
    description = "Disables Sentry crash reporting, AppsFlyer attribution and Firebase Analytics " +
            "collection.",
    default = true
) {
    compatibleWith(COMPATIBILITY_MUSESCORE)

    execute {
        val bundle = get(BUNDLE_PATH)
        val data = bundle.readBytes()

        var stringsApplied = false
        try {
            for (entry in HERMES_STRING_PATCHES) {
                val positions = indexOfAll(data, entry.find)
                if (positions.size != 1) {
                    throw PatchException(
                        "${entry.name}: expected exactly 1 occurrence of the target string, " +
                                "found ${positions.size}"
                    )
                }
                entry.replace.copyInto(data, positions[0])
            }
            stringsApplied = true
        } catch (e: PatchException) {
            // The string kills are verified against the 2.14.48 bundle; on any other build the
            // strings may differ. Skip them instead of failing the session (the manifest flags
            // below are version-independent and still apply).
            println(
                "WARNING: Disable tracking skipped its Hermes string kills - ${e.message}. " +
                        "Regenerate the tables for this build."
            )
        }

        if (stringsApplied) {
            // Recompute the Hermes SHA-1 trailer (last 20 bytes = SHA-1 of everything before).
            val sha1 = MessageDigest.getInstance("SHA-1")
                .digest(data.copyOfRange(0, data.size - SHA1_TRAILER_SIZE))
            sha1.copyInto(data, data.size - SHA1_TRAILER_SIZE)
            bundle.writeBytes(data)
        }

        // Firebase / Sentry collection flags in the merged manifest.
        document("AndroidManifest.xml").use { document ->
            val application = document.getElementsByTagName("application").item(0) as Element
            val existing = mutableMapOf<String, Element>()
            val metaNodes = application.getElementsByTagName("meta-data")
            for (i in 0 until metaNodes.length) {
                val node = metaNodes.item(i) as Element
                existing[node.getAttribute("android:name")] = node
            }

            (FIREBASE_OFF + SENTRY_OFF).forEach { (name, value) ->
                val node = existing[name]
                if (node != null) {
                    node.setAttribute("android:value", value)
                } else {
                    val meta = document.createElement("meta-data")
                    meta.setAttribute("android:name", name)
                    meta.setAttribute("android:value", value)
                    application.appendChild(meta)
                }
            }
        }
    }
}

private fun indexOfAll(haystack: ByteArray, needle: ByteArray): List<Int> {
    val positions = mutableListOf<Int>()
    if (needle.isEmpty() || haystack.size < needle.size) return positions
    var i = 0
    while (i <= haystack.size - needle.size) {
        var match = true
        for (j in needle.indices) {
            if (haystack[i + j] != needle[j]) {
                match = false
                break
            }
        }
        if (match) {
            positions += i
            i += needle.size
        } else {
            i++
        }
    }
    return positions
}
