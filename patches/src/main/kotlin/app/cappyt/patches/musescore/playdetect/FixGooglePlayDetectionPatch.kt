package app.cappyt.patches.musescore.playdetect

import app.cappyt.patches.shared.Constants.COMPATIBILITY_MUSESCORE
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

/**
 * Disables pairipcore's Google Play checks.
 *
 * MuseScore ships Google's pairipcore SDK (com.pairip.*). On every start:
 *
 *   - com.pairip.application.Application (manifest Application) runs
 *     SignatureCheck.verifyIntegrity (throws on re-signed APKs) and
 *     LicenseClient.checkLicense; MainApplication.onCreate additionally calls
 *     com.pairip.StartupLauncher.launch (the native VM startup program).
 *   - The license state machine binds to the Play licensing service and, for any install
 *     not coming from com.android.vending, reacts with an error dialog or the Play Store
 *     paywall interstitial ("Download this app on Google Play" - rendered by the Play Store
 *     itself, which is why the string exists nowhere in the APK) and schedules an app
 *     shutdown after 30 seconds.
 *
 * What is no-oped, deliberately split in two groups:
 *
 *   1. Entry no-ops proven safe on this app: checkLicense and verifyIntegrity. The VM
 *      startup program (StartupLauncher) is NOT touched: the pairip VM runtime depends on
 *      it having run, and no-oping it crashes VM-protected methods at app start.
 *   2. Sink no-ops: every user-visible or destructive sink of the license flow (paywall
 *      activity, error dialog, app shutdown). Even if the state machine still runs and
 *      reports NOT_LICENSED, nothing is displayed and nothing exits. The blank
 *      LicenseActivity still calls through to super so the framework stays happy.
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
        val voidNoOps = listOf(
            // entries proven safe to disable on this app
            Triple(
                "Lcom/pairip/SignatureCheck;",
                "verifyIntegrity",
                listOf("Landroid/content/Context;"),
            ),
            Triple(
                "Lcom/pairip/licensecheck/LicenseClient;",
                "checkLicense",
                listOf("Landroid/content/Context;"),
            ),
            // sinks: paywall / dialogs / shutdown - reachable only inside the license flow,
            // no caller depends on their effects
            Triple(
                "Lcom/pairip/licensecheck/LicenseClient;",
                "startPaywallActivity",
                listOf("Landroid/app/PendingIntent;"),
            ),
            Triple(
                "Lcom/pairip/licensecheck/LicenseClient;",
                "handleError",
                listOf("Lcom/pairip/licensecheck/LicenseCheckException;"),
            ),
            Triple(
                "Lcom/pairip/licensecheck/LicenseClient;",
                "scheduleAppShutdown",
                listOf<String>(),
            ),
            Triple(
                "Lcom/pairip/licensecheck/LicenseActivity;",
                "showPaywallAndCloseApp",
                listOf<String>(),
            ),
            Triple(
                "Lcom/pairip/licensecheck/LicenseActivity;",
                "showErrorDialog",
                listOf<String>(),
            ),
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

        // LicenseActivity: blank it if anything still launches it. It must still call through
        // to super, otherwise the framework crashes the app.
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
