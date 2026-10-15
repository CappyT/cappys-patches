package app.cappyt.patches.musescore.playdetect

import app.cappyt.patches.shared.Constants.COMPATIBILITY_MUSESCORE
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

/**
 * Disables pairipcore's Google Play checks: every Java entry into the license state machine
 * and every UI/shutdown sink, leaving the pairip VM runtime untouched.
 *
 * MuseScore ships Google's pairipcore SDK (com.pairip.*):
 *
 *   - a manifest Application wrapper that runs SignatureCheck.verifyIntegrity (throws on
 *     re-signed APKs) and LicenseClient.checkLicense;
 *   - a native VM (libpairipcore.so + per-method program assets in assets/) whose startup
 *     program participates in app startup (MainApplication).

 * The license state machine binds to the Play licensing service; for any install that does
 * not come from com.android.vending, the Play Store answers responseCode 2 (NOT_LICENSED)
 * with a paywall PendingIntent. Sending it displays the Play Store's own UI ("install this
 * app from the Play Store"), rendered by the Play Store app in the device language - which
 * is why the string exists nowhere in MuseScore's APK. Failure paths show a hardcoded
 * error dialog and schedule an app shutdown after 30 seconds.
 *
 * Killed here:
 *
 *   - NOT touched: com.pairip.StartupLauncher.launch. No-oping it crashes the app at start:
 *     the VM startup program is required by the pairip runtime (v1.1.1 regression, verified).
 *   - Entries into the state machine: with checkLicense, stopTrial, handleTrialEnd,
 *     initializeLicenseCheck and initiateFreshLicensingServiceConnection returning void,
 *     nothing ever binds to the Play licensing service - no response, no paywall PendingIntent.
 *   - The async response callback verifyLicense: drops the license service response (and the
 *     paywall PendingIntent it carries) even if a stale binder callback still fires.
 *   - Sinks: startPaywallActivity, handleError, scheduleAppShutdown, LicenseActivity
 *     showPaywallAndCloseApp / showErrorDialog.
 *   - LicenseActivity.onStart: calls through to super (framework requirement) and finishes
 *     itself immediately, so nothing can display and no blank activity is left behind.
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
            // signature check called from the manifest Application wrapper
            Triple(
                "Lcom/pairip/SignatureCheck;",
                "verifyIntegrity",
                listOf("Landroid/content/Context;"),
            ),
            // entries into the license state machine (none on the app startup path)
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
            // sinks
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

        // LicenseActivity.onStart: never process the paywall/dialog extras; close immediately.
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
                    invoke-virtual {v0}, Landroid/app/Activity;->finish()V
                    return-void
                """,
            )
        }

        // Play Store in-app review flow ("Install this app from the Play Store" banner on
        // sideloaded installs - rendered by the Play Store itself, which is why the string
        // exists nowhere in the APK). The RNRate module hands the flow to the Play Store via
        // Play Core Review and the app auto-triggers it from useRateScoreFlow on the third
        // score opened in a session, plus from every "rate the app" button. Answer the JS
        // callback with true (review "shown"): the success path only runs the cooldown/hide
        // callbacks, while the failure path would open the Play Store listing page.
        val rate = Fingerprint(
            definingClass = "Lcom/reactnativerate/RNRateModule;",
            name = "rate",
            returnType = "V",
            parameters = listOf(
                "Lcom/facebook/react/bridge/ReadableMap;",
                "Lcom/facebook/react/bridge/Callback;",
            ),
        )
        val rateMethod = rate.methodOrNull
        if (rateMethod == null) {
            println("WARNING: RNRateModule.rate not found; skipping")
        } else {
            rateMethod.addInstructions(
                0,
                """
                    const/4 v0, 0x1
                    new-array v0, v0, [Ljava/lang/Object;
                    const/4 v1, 0x0
                    sget-object v2, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;
                    aput-object v2, v0, v1
                    invoke-interface {p2, v0}, Lcom/facebook/react/bridge/Callback;->invoke([Ljava/lang/Object;)V
                    return-void
                """,
            )
        }
    }
}
