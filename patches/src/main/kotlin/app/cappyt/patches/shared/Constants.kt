package app.cappyt.patches.shared

import app.morphe.patcher.patch.ApkFileType
import app.morphe.patcher.patch.AppTarget
import app.morphe.patcher.patch.Compatibility

object Constants {
    val COMPATIBILITY_MUSESCORE = Compatibility(
        name = "MuseScore: Music Chords & Tabs",
        packageName = "com.musescore.playerlite",
        apkFileType = ApkFileType.APKM,
        appIconColor = 0x240C44,
        targets = listOf(
            // Release used to develop the Hermes bytecode patch tables.
            AppTarget(
                version = "2.14.48"
            )
        )
    )
}
