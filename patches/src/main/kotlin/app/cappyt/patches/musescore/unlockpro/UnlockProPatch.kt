package app.cappyt.patches.musescore.unlockpro

import app.cappyt.patches.shared.Constants.COMPATIBILITY_MUSESCORE
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import java.security.MessageDigest

/**
 * Unlocks MuseScore Pro entitlements inside the React Native Hermes bundle
 * (assets/index.android.bundle) of com.musescore.playerlite 2.14.48.
 *
 * How the app gates Pro features (recovered from the Metro sourcemap shipped inside the APK):
 *
 *   billingThunks.saveAllAccessRightsToStoreThunk  <- /purchase/service/access response
 *       rights?.length
 *         ? flags = isAccess<Plan>Unexpired        (7 regions force these to true)
 *         : setPartialFieldsCustom({ isPro: false, ... })   (else literal: all false)
 *
 * Patches applied (all same-length, byte-verified in-place rewrites):
 *
 *   1. rightsGate       The `if (rights?.length)` branch test is replaced with
 *                       LoadConstTrue; the non-empty branch always runs, so the
 *                       all-false else literal is never dispatched.
 *   2. thunkFlag0..6    Each `isAccess<Plan>Unexpired = !!dateAccess && now < dateAccess`
 *                       computation is replaced with straight-line LoadConstTrue, so every
 *                       plan (pro, offScores, courses/courses, learningLite, learnLite sub,
 *                       ebook, autoGrade) reports unexpired. isAccessPremiumUnexpired is
 *                       deliberately left alone: isPremiumRights=true reroutes premium-score
 *                       taps to the buy-score modal.
 *   3. elseBranch       A second all-false setPartialFieldsCustom literal (rights=null reset
 *                       path in the same module) has its entitlement booleans flipped to true
 *                       by rewriting the SerializedLiteralParser tag bytes (same total length:
 *                       the 9-boolean False group becomes one True group). isTrial is flipped
 *                       too because the encoding requires a single run; its consumers are
 *                       cosmetic, and the PDF-export gate reads isTrialPro which stays false.
 *
 * Not patched on purpose:
 *   - initialState literal (all entitlement booleans live in one 19-item False group with
 *     isTrialPro, which must stay false: exportToPDF routes to a server-side trial-restriction
 *     check when it is true, and isAutoScrollSettingsModalVisible would popup a modal on launch).
 *   - setResetMarketingStore (logout resets flags; the next rights sync re-grants them).
 *
 * After patching, the Hermes SHA-1 trailer (last 20 bytes of the bundle) is recomputed.
 *
 * Server-side gates remain server-side: individual downloads of licensed "official" scores and
 * authenticated API resources are still validated by musescore.com.
 */
@Suppress("unused")
val unlockMuseScoreProPatch = resourcePatch(
    name = "Unlock Pro",
    description = "Unlocks MuseScore Pro / Off-Scores entitlements locally by patching the " +
            "React Native Hermes bundle (paywalls, offline scores gating, feature flags).",
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
        val actualSha256 = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") {
            "%02x".format(it)
        }
        if (!actualSha256.equals(MUSESCORE_BUNDLE_SHA256, ignoreCase = true)) {
            throw PatchException(
                "index.android.bundle SHA-256 mismatch (got $actualSha256). " +
                        "This patch bundle targets MuseScore 2.14.48 (versionCode 2701674); " +
                        "regenerate the patch tables for other versions."
            )
        }

        for (entry in HERMES_PATCHES) {
            val offset = entry.offset.toInt()
            val end = offset + entry.expected.size

            if (end > data.size) {
                throw PatchException("${entry.name}: range end $end exceeds bundle size")
            }
            for (i in entry.expected.indices) {
                if (data[offset + i] != entry.expected[i]) {
                    throw PatchException(
                        "${entry.name}: byte mismatch at 0x${"%x".format(offset + i)} " +
                                "(expected 0x${"%02x".format(entry.expected[i])}, " +
                                "got 0x${"%02x".format(data[offset + i])})"
                    )
                }
            }
            entry.replace.copyInto(data, offset)
        }

        // Recompute the Hermes SHA-1 trailer (last 20 bytes = SHA-1 of everything before them).
        val sha1 = MessageDigest.getInstance("SHA-1")
            .digest(data.copyOfRange(0, data.size - SHA1_TRAILER_SIZE))
        sha1.copyInto(data, data.size - SHA1_TRAILER_SIZE)

        file.writeBytes(data)
    }

    finalize {
        // no-op: the bundle file is fully written in execute
    }
}

private const val BUNDLE_PATH = "assets/index.android.bundle"
private const val SHA1_TRAILER_SIZE = 20
