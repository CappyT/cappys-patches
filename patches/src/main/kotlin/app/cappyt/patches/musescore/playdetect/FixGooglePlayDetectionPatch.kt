package app.cappyt.patches.musescore.playdetect

import app.cappyt.patches.shared.Constants.COMPATIBILITY_MUSESCORE
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.PatchException

/**
 * Disables pairipcore's Google Play checks, exhaustively.
 *
 * MuseScore ships Google's pairipcore SDK (com.pairip.*). On every start:
 *
 *   - com.pairip.application.Application (manifest Application) runs
 *     SignatureCheck.verifyIntegrity (throws on re-signed APKs) and
 *     LicenseClient.checkLicense, and MainApplication.onCreate calls
 *     com.pairip.StartupLauncher.launch (the native VM startup program).
 *   - LicenseClient.checkLicense / stopTrial are the two public entries into the license
 *     state machine; both end in initializeLicenseCheck -> bind to the Play licensing
 *     service -> onServiceConnected -> checkLicenseInternal -> processResponse.
 *   - On failure (or responseCode 2, NOT_LICENSED - the case for any install not coming
 *     from com.android.vending) the flow shows the "Check that Google Play is enabled on
 *     your device..." error dialog or launches the Play Store paywall interstitial
 *     ("Download this app on Google Play", rendered by the Play Store itself in the device
 *     language) via LicenseActivity, and schedules an app shutdown after 30 seconds.
 *
 * Every entry point into the state machine plus every UI/shutdown sink is no-oped, so no
 * path (including reflective calls from the pairip VM) can reach the paywall anymore.
 * The per-method VM protection is untouched: protected app methods invoke VMRunner
 * directly with their own program names and keep working.
 */
@Suppress("unused")
val fixGooglePlayDetectionPatch = bytecodePatch(
    name = "Fix Google Play detection",
    description = "Disables pairipcore's license and signature checks that complain the app was " +
            "not installed from Google Play and shut it down.",
    default = true
) {
    compatibleWith(COMPATIBILITY_MUSESCORE)

    execute {
        // void no-ops: entries into the license state machine, its sinks, and the startup VM.
        val voidNoOps = listOf(
            // startup VM program (integrity check); per-method VM programs are unaffected
            Triple("Lcom/pairip/StartupLauncher;", "launch", listOf<String>()),
            // signature check called from the manifest Application wrapper
            Triple(
                "Lcom/pairip/SignatureCheck;",
                "verifyIntegrity",
                listOf("Landroid/content/Context;"),
            ),
            // public entries into the license state machine
            Triple(
                "Lcom/pairip/licensecheck/LicenseClient;",
                "checkLicense",
                listOf("Landroid/content/Context;"),
            ),
            Triple(
                "Lcom/pairip/licensecheck/LicenseClient;",
                "stopTrial",
                listOf("Landroid/content/Context;"),
            ),
            Triple("Lcom/pairip/licensecheck/LicenseClient;", "handleTrialEnd", listOf<String>()),
            Triple(
                "Lcom/pairip/licensecheck/LicenseClient;",
                "initializeLicenseCheck",
                listOf<String>(),
            ),
            Triple(
                "Lcom/pairip/licensecheck/LicenseClient;",
                "initiateFreshLicensingServiceConnection",
                listOf("Z"),
            ),
            // async callback from the Play licensing service: no processing, no paywall
            Triple(
                "Lcom/pairip/licensecheck/LicenseClient;",
                "verifyLicense",
                listOf("I", "Landroid/os/Bundle;"),
            ),
            // sinks
            Triple("Lcom/pairip/licensecheck/LicenseClient;", "scheduleAppShutdown", listOf<String>()),
            Triple(
                "Lcom/pairip/licensecheck/LicenseClient;",
                "startPaywallActivity",
                listOf("Landroid/app/PendingIntent;"),
            ),
            Triple(
                "Lcom/pairip/licensecheck/LicenseActivity;",
                "showPaywallAndCloseApp",
                listOf<String>(),
            ),
            Triple("Lcom/pairip/licensecheck/LicenseActivity;", "showErrorDialog", listOf<String>()),
        )

        for ((definingClass, name, parameters) in voidNoOps) {
            val fingerprint = Fingerprint(
                definingClass = definingClass,
                name = name,
                returnType = "V",
                parameters = parameters,
            )
            val method = fingerprint.methodOrNull
            if (method == null) {
                println("WARNING: pairip method $definingClass->$name not found; skipping")
                continue
            }
            method.addInstructions(0, "return-void")
        }

        // LicenseActivity.onStart: blank the activity instead of letting it show any dialog.
        // It must still call through to super, otherwise the framework crashes the app.
        val onStart = Fingerprint(
            definingClass = "Lcom/pairip/licensecheck/LicenseActivity;",
            name = "onStart",
            returnType = "V",
        )
        val onStartMethod = onStart.methodOrNull
        if (onStartMethod == null) {
            println("WARNING: pairip LicenseActivity.onStart not found; skipping")
        } else {
            onStartMethod.addInstructions(
                0,
                """
                    invoke-super {v0}, Landroid/app/Activity;->onStart()V
                    return-void
                """,
            )
        }
    }
}
