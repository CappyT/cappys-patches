# 🎸 Cappyt Patches

Unofficial [Morphe](https://github.com/MorpheApp) patch bundle for the **MuseScore Android app**
(`com.musescore.playerlite`).

<!-- 🩹 Patches list -->

<!-- PATCHES_START EXPANDED -->
> **[v1.0.0](https://github.com/CappyT/cappys-patches/releases/tag/v1.0.0)**&nbsp;&nbsp;•&nbsp;&nbsp;`main`&nbsp;&nbsp;•&nbsp;&nbsp;1 patches total
<details open>
<summary>📦 MuseScore: Music Chords & Tabs&nbsp;&nbsp;•&nbsp;&nbsp;1 patch</summary>
<br>

**🎯 Supported versions:**

| 2.14.48 |
| :---: |

| 💊&nbsp;Patch | 📜&nbsp;Description | ⚙️&nbsp;Options |
|----------|----------------|-----------|
| [Unlock Pro](#unlock-pro) | Unlocks MuseScore Pro / Off-Scores entitlements locally by patching the React Native Hermes bundle (paywalls, offline scores gating, feature flags). |  |

</details>

<!-- PATCHES_END -->

## 💊 Patches

| Patch | Description |
|---|---|
| **Unlock Pro** | Unlocks MuseScore Pro / Off-Scores entitlements locally by patching the React Native Hermes bundle (`assets/index.android.bundle`): the access-rights sync always grants Pro flags, so paywalls, offline-scores gating, transposition/parts gates and feature flags open up. Byte-verified in-place rewrites + Hermes SHA-1 trailer recomputed. |

## 🎯 Compatibility

| App | Package | Tested version |
|---|---|---|
| MuseScore: Music Chords & Tabs | `com.musescore.playerlite` | **2.14.48** (versionCode 2701674, APKMirror universal `.apkm`) |

The Unlock Pro patch carries a full SHA-256 integrity check of the bundle and **fails loudly** on
any other build — the Hermes bytecode offsets are version-specific.

## 🔧 How it works (for patch authors)

MuseScore's Android app is React Native with the JS logic compiled to Hermes bytecode (v98).
The release build accidentally ships the full Metro sourcemap (`assets/index.android.bundle.packager.map`,
with `sourcesContent`), so the entire TypeScript source of the app is recoverable:

1. The entitlement store (`marketingStoreChunk`) holds `isPro`, `isOffScores`, `isPremiumRights`, …
2. `saveAllAccessRightsToStoreThunk` (`billingThunks.ts`) is the single choke point that maps the
   `/purchase/service/access` response to those flags:
   - `rights` non-empty → each `isAccess<Plan>Unexpired = !!dateAccess && now < dateAccess`
   - `rights` empty → a literal `{ isPro: false, ... }` dispatch
3. The patch rewrites the compiled Hermes function `saveAllAccessRightsToStoreThunk` (generator fn):
   - the `if (rights?.length)` test becomes an unconditional `LoadConstTrue` (else branch is dead),
   - each `isAccess*Unexpired` computation is replaced by straight-line `LoadConstTrue`,
   - a second all-false rights-reset literal has its SerializedLiteralParser tag bytes flipped
     to `True` (same total length).
4. The Hermes SHA-1 trailer (last 20 bytes) is recomputed after patching.

Deliberately **not** patched: `isTrialPro` (must stay `false`, otherwise `exportToPDF` routes to a
server-side trial-restriction check) and `isAccessPremiumUnexpired` (`isPremiumRights=true` reroutes
premium-score taps to the buy modal).

**Server-side gates stay server-side:** downloads of licensed *official* scores and authenticated
API resources are still validated by musescore.com. This patch unlocks the client-side UX.

## 📦 Using these patches

Click here to add these patches to Morphe: https://morphe.software/add-source?github=CappyT/cappys-patches

Or manually add `https://github.com/CappyT/cappys-patches` in Morphe Manager → Sources.

1. Get the original `.apkm` from APKMirror (universal build, 2.14.48).
2. Open Morphe Manager → Patches → select the source → pick **Unlock Pro**.
3. Select the downloaded `.apkm` → Patch → install the output.

## 🛠️ Building locally

```bash
export GITHUB_ACTOR=$(gh api user -q .login)
export GITHUB_TOKEN=$(gh auth token)
./gradlew buildAndroid
```

The built patch bundle lands in `patches/build/libs/patches-*.mpp` — load it in
[Morphe Desktop](https://github.com/MorpheApp/morphe-desktop) or sideload it with Morphe Manager.

### Regenerating the Hermes patch tables

`patches/src/main/kotlin/app/cappyt/patches/musescore/unlockpro/PatchTables.kt` is generated from
`patch_entries.json`. To retarget a new app version: decompile the new bundle (jadx + hermes-dec),
locate `saveAllAccessRightsToStoreThunk`, regenerate offsets, and rerun the generator.

## 📜 License

Cappyt Patches are licensed under the [GNU General Public License v3.0](LICENSE).
