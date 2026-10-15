package app.cappyt.patches.musescore.unlockpro

import app.cappyt.patches.shared.Constants.COMPATIBILITY_MUSESCORE
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import java.security.MessageDigest

private const val BUNDLE_PATH = "assets/index.android.bundle"
private const val SHA1_TRAILER_SIZE = 20

/** Lowercase two-digit hex for error messages. */
private fun hex(b: Byte): String = "%02x".format(b)

/**
 * Unlocks MuseScore Pro entitlements inside the React Native Hermes bundle
 * (assets/index.android.bundle) of com.musescore.playerlite 2.14.48.
 *
 * How the app gates Pro features (recovered from the Metro sourcemap shipped inside the APK):
 *
 *   billingThunks.saveAllAccessRightsToStoreThunk  <- /purchase/service/access response
 *       rights?.length
 *         ? flags from isAccess<Plan>Unexpired     (7 regions force these to true)
 *         : setPartialFieldsCustom({ isPro: false, ... })   (else literal: all false)
 *
 * Patches applied (all in-place rewrites, expected bytes verified first):
 *
 *   rightsGate     The `if (rights?.length)` branch test becomes LoadConstTrue: the
 *                  non-empty branch always runs and the all-false else literal is dead.
 *   thunkFlag0..6  Each `isAccess<Plan>Unexpired` computation is replaced with
 *                  straight-line LoadConstTrue of its result register.
 *
 * Deliberately not patched: the initialState literal (its entitlement booleans share one
 * 19-item False group with isTrialPro, which must stay false: exportToPDF then routes to a
 * server-side trial-restriction check, and isAutoScrollSettingsModalVisible would popup a
 * modal on launch) and isAccessPremiumUnexpired (isPremiumRights=true reroutes premium-score
 * taps to the buy-score modal).
 */
@Suppress("unused")
val unlockMuseScoreProPatch = resourcePatch(
    name = "Unlock Pro",
    description = "Unlocks MuseScore Pro / Off-Scores entitlements by patching the React Native " +
            "Hermes bundle (paywalls, offline scores gating, feature flags). Also hides the " +
            "device-compatibility attestation popup shown when opening scores.",
    default = true
) {
    compatibleWith(COMPATIBILITY_MUSESCORE)

    execute {
        val file = get(BUNDLE_PATH)
        val data = file.readBytes()

        if (data.size < 64) {
            throw PatchException("$BUNDLE_PATH is too small to be a Hermes bundle")
        }

        // Optional global integrity check: the tables were generated against this exact bundle.
        // A warning only (not fatal): other patches (e.g. Disable tracking) may have already
        // rewritten the bundle when this patch runs; the per-region byte verification below is
        // the authoritative guard.
        val actualSha256 = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        if (!actualSha256.equals(MUSESCORE_BUNDLE_SHA256, ignoreCase = true)) {
            println(
                "WARNING: index.android.bundle SHA-256 mismatch (got $actualSha256). This patch " +
                        "bundle targets MuseScore 2.14.48 (versionCode 2701674); the per-region " +
                        "verification decides whether the tables still apply."
            )
        }

        try {
            applyHermesRegions(data)

            // Recompute the Hermes SHA-1 trailer (last 20 bytes = SHA-1 of everything before).
            val sha1 = MessageDigest.getInstance("SHA-1")
                .digest(data.copyOfRange(0, data.size - SHA1_TRAILER_SIZE))
            sha1.copyInto(data, data.size - SHA1_TRAILER_SIZE)

            file.writeBytes(data)
        } catch (e: PatchException) {
            // The tables are version-specific: on any other build the expected bytes no longer
            // match. Skip the Hermes unlock instead of failing the whole patching session - the
            // version-independent patches (pairip / manifest flags) still apply.
            println(
                "WARNING: Unlock Pro skipped - ${e.message}. The Hermes patch tables target " +
                        "MuseScore 2.14.48 (versionCode 2701674); regenerate them for this build."
            )
        }
    }
}

/**
 * Verifies and applies every region all-or-nothing: if any expected byte mismatches, a
 * [PatchException] is thrown BEFORE anything is written, so a version-drifted bundle is
 * never partially rewritten.
 */
private fun applyHermesRegions(data: ByteArray) {
    for (entry in HERMES_PATCHES) {
        val offset = entry.offset.toInt()
        val end = offset + entry.expected.size

        if (end > data.size) {
            throw PatchException("${entry.name}: range end $end exceeds bundle size")
        }
        for (i in entry.expected.indices) {
            if (data[offset + i] != entry.expected[i]) {
                throw PatchException(
                    "${entry.name}: byte mismatch at 0x" + hex(data[offset + i]) +
                            " (expected 0x" + hex(entry.expected[i]) + ")"
                )
            }
        }
        entry.replace.copyInto(data, offset)
    }
}

