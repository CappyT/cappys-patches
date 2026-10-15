package app.cappyt.patches.musescore.playdetect

import app.cappyt.patches.shared.Constants.COMPATIBILITY_MUSESCORE
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch

/**
 * Disables pairipcore's Google Play checks.
 *
 * MuseScore ships Google's pairipcore SDK (com.pairip.*). Its Application wrapper
 * (com.pairip.application.Application, registered in the manifest) runs on every start:
 *
 *   1. SignatureCheck.verifyIntegrity  - throws SignatureTamperedException when the APK was
 *      re-signed (i.e. every patched install).
 *   2. LicenseClient.checkLicense      - binds to the Play licensing service, runs a local
 *      installer check (getInstallSourceInfo().getInstallingPackageName() must be
 *      "com.android.vending") and on failure shows the "Check that Google Play is enabled on
 *      your device..." dialog (LicenseActivity) and schedules an app shutdown after 30 seconds.
 *
 * Both entry points are static methods, so no-oping them kills the dialog, the paywall activity
 * and the scheduled shutdown. VMRunner.setContext in the same wrapper is left alone: the
 * pairip VM self-heals its context (VMRunner.invoke re-creates it when null), and the VM
 * protection may host app logic that must keep running.
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
        val checkLicense = Fingerprint(
            definingClass = "Lcom/pairip/licensecheck/LicenseClient;",
            name = "checkLicense",
            returnType = "V",
            parameters = listOf("Landroid/content/Context;"),
        )
        checkLicense.method.addInstructions(0, "return-void")

        val verifyIntegrity = Fingerprint(
            definingClass = "Lcom/pairip/SignatureCheck;",
            name = "verifyIntegrity",
            returnType = "V",
            parameters = listOf("Landroid/content/Context;"),
        )
        verifyIntegrity.method.addInstructions(0, "return-void")
    }
}
