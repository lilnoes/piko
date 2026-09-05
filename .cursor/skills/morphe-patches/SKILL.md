---
name: morphe-patches
description: Develop, build, and release Morphe/Piko patch bundles (.mpp). Use when adding Instagram or Twitter patches, building patches, fixing Morphe Manager showing 0 patches, configuring GitHub Packages, working on the `dev` branch, or following the Morphe patches template.
---

# Morphe / Piko patches

Follow the [Morphe patches template](https://github.com/MorpheApp/morphe-patches-template) unless this repo documents a stricter rule.

Do not hardcode machine paths (`JAVA_HOME`, SDK roots, usernames). Detect them.

## Layout

| Path | Role |
|------|------|
| `patches/` | Kotlin bytecode/resource patches. This is what Morphe lists. |
| `extensions/` | Java runtime injected into the app (hooks, UI, SQLite). |
| `patches/build/libs/patches-*.mpp` | Ship artifact (a JAR). |
| `patches-list.json` | Patch catalog. **Generated.** |
| `patches-bundle.json` | Morphe Manager source pointer (version + download URL). **Generated.** |
| `README.md` between `PATCHES_START` / `PATCHES_END` | **Generated.** |

Do not hand-edit generated files. Semantic-release updates them. English source strings live in `patches/src/main/resources/addresources/values/*/strings.xml`; do not add locale files by hand if Crowdin owns translations.

## Git and remotes

- **`origin`**: this fork. **`upstream`**: the original project (`crimera/piko` or similar). Never push to `upstream`.
- All feature work goes on **`dev`**. Merge `dev` → `main` (no squash) only for a stable release.
- Do not force-push `main`.
- A public GitHub repo is cloneable and accepts PRs from forks. Only collaborators can push or merge.

### Commit types (semantic-release)

| Prefix | Effect on `dev` | Effect on `main` |
|--------|-----------------|------------------|
| `feat:` | pre-release `.mpp` (minor) | stable minor |
| `fix:` / `bump:` / `perf:` | pre-release `.mpp` (patch) | stable patch |
| `chore:` | no new bundle (CI still compiles) | no new bundle |

Examples: `feat(Instagram): Add watch history`, `fix: Align fork releases with the template`.

## Build an `.mpp`

**Always** `./gradlew buildAndroid` (or `:patches:buildAndroid`). That merges `classes.dex` and `extensions/*.mpe` into the bundle.

Do **not** ship the output of `:patches:jar` or `generatePatchesList` alone. Those can omit `classes.dex`. Morphe Manager on Android loads patches from `classes.dex`; a class-only JAR shows **0 patches**.

Verify before upload:

```sh
unzip -l patches/build/libs/patches-*.mpp | grep -E '^(classes.dex|extensions/.*\.mpe$)'
```

A good bundle has `classes.dex` plus `extensions/*.mpe`. Compare against an official GitHub release `.mpp` if unsure.

### Environment (portable)

Need:

1. **JDK** — CI/template often use Temurin **21**; some repos use **17**. Prefer `JAVA_HOME` from `java_home` (macOS), SDKMAN, or the JDK the repo’s CI uses. Do not assume Homebrew layout.
2. **Android SDK** — `ANDROID_HOME` or `sdk.dir` in gitignored `local.properties`. Need the `compileSdk` platform (this repo: **36**) and matching build-tools. Android Studio’s default SDK is `$HOME/Library/Android/sdk` on macOS, `$HOME/Android/Sdk` on Linux; confirm rather than assume.
3. **GitHub Packages auth** — Morphe Gradle plugins live at `https://maven.pkg.github.com/MorpheApp/registry`. That registry requires a logged-in user **even when the package is public**. `repo` on a `gh` token is not enough; the token needs **`read:packages`**.

```sh
# token scopes: include read:packages
export GITHUB_ACTOR="$(gh api user --jq .login)"
export GITHUB_TOKEN="$(gh auth token)"
# or ~/.gradle/gradle.properties: gpr.user=...  gpr.key=...
```

`gh auth refresh -h github.com -s read:packages` if Gradle cannot resolve `app.morphe.patches`.

If `settings.gradle.kts` reads empty `GITHUB_ACTOR`/`GITHUB_TOKEN`, the Morphe settings plugin can throw `IllegalArgumentException` with no message.

Both the token **and** the SDK are needed for a full `buildAndroid`; the token alone gets you as far as `:patches:compileKotlin`, then R8 on the extensions fails on the missing SDK:

```sh
export ANDROID_HOME="$HOME/Library/Android/sdk"   # confirm, do not assume
export GITHUB_ACTOR="$(gh api user --jq .login)"
export GITHUB_TOKEN="$(gh auth token)"
./gradlew buildAndroid --console=plain
```

| Failure | Cause |
|---------|-------|
| `Failed to apply plugin 'app.morphe.patches'` → `IllegalArgumentException` (no message) | `GITHUB_ACTOR`/`GITHUB_TOKEN` unset or missing `read:packages` |
| `Could not determine the dependencies of task ':extensions:*:minifyReleaseWithR8'` → SDK location not found | `ANDROID_HOME` unset and no `sdk.dir` in `local.properties` |

`:patches:compileKotlin` from cold is a few minutes. Compile that alone while iterating on Kotlin; run `buildAndroid` only when you need a bundle.

## Releases

Use **`release.yml`** (semantic-release). Do not hand-create GitHub releases or clobber `.mpp` assets unless recovering a broken publish.

Template `release.yml`: `on: push` with **no branch filter**. `.releaserc` publishes only from **`main`** (stable) and **`dev`** (prerelease, e.g. `v3.10.1-dev.1`). Other branches compile via the “Verify project compiles” step.

`generatePatchesList` must `dependsOn("buildAndroid")` so the uploaded `.mpp` includes dex/extensions.

If this fork cannot sign with GPG, set `.releaserc` `signatureUrlTemplate` to `""`. A `patches-bundle.json` `signature_download_url` that 404s can make Morphe Manager show **0 patches**.

`patches-bundle.json` `download_url` must point at **this repo’s** `releases/download/...`, not upstream.

Morphe Manager: add `https://morphe.software/add-source?github=OWNER/REPO`. Enable **pre-release** to consume `dev` bundles.

### “tag already exists” (`vX.Y.Z-dev.N`)

Two Release runs overlapped, or the tagged `chore: Release v… [skip ci]` commit is **not an ancestor** of `dev`. Semantic-release then tries the same tag again.

Fix: merge the tagged commit into `dev` so `git merge-base --is-ancestor <tag> origin/dev` is true. Next `feat:`/`fix:` becomes `dev.N+1`. Do not delete a good GitHub pre-release unless recreating it on purpose.

Do not push `dev` while a Release job for the same version is still running.

## Workflows to keep

This fork only needs `release.yml` (build and publish `.mpp`).

Crowdin and extra PR-build workflows are upstream-specific; they need secrets this fork usually does not have. Leave them off unless Crowdin is configured.

## Adding an Instagram patch

Mirror an existing feature (e.g. save deleted messages):

1. Kotlin `bytecodePatch` + optional `resourcePatch` (manifest activity, etc.). `compatibleWith` the supported Instagram version. Wrap each fingerprint in its own `runCatching` so one drift does not abort the patch — but see [Do not let `runCatching` hide a dead patch](#do-not-let-runcatching-hide-a-dead-patch).
2. Constructor hooks: inject **after** the first `INVOKE_DIRECT` (`super()`), not at index 0. In a constructor the first `invoke-direct` is always the `super()`/`this()` call, because instance methods cannot be called before it.
3. Java extension: hook class + Activity/DB as needed. Capture on a background thread. Dedupe by media id.
4. Wire settings: `Settings`, `SettingsStatus`, `Pref`, `ScreenBuilder`, `enableSettings("flagName")`.
5. Home toolbar: existing `ActionBarPatch` / `mainFeedActionBarFlags` if the feature needs a home icon.
6. English strings only in `values/instagram/strings.xml`.
7. Do not edit the README patches table.

Public patch `name` is what Morphe lists. Nameless `resourcePatch` dependencies stay hidden.

## Fingerprints

Full constructor surface, in rough order of how often this repo uses it:

| Param | Notes |
|-------|-------|
| `definingClass` | Partial match — `"SelectHighlightsCoverFragment;"` and `"/fasterxml/jackson/core/"` both work |
| `name` | Exact method name |
| `strings` | Literal strings in the method body |
| `returnType` | Partial match, e.g. `"ProfilePicUrlInfo"` without the `L…;` |
| `parameters` | Partial match per type, e.g. `"Ljava/io/InputStream"` |
| `filters` | `string(...)`, `literal(...)`, `methodCall(...)` |
| `custom` | `{ methodDef, classDef -> Boolean }`, evaluated **at match time** |
| `accessFlags` | `listOf(AccessFlags.PUBLIC, AccessFlags.STATIC)` |
| `classFingerprint` | Scope this fingerprint to a class another one found |

Two properties matter more than they look:

- **A fingerprint resolves to exactly one method.** If the shape you describe matches three implementations, you get one and silently lose the others. Either constrain harder, or match one and sweep its class with `mutableClassDefBy { it.type == X }.methods.forEach { ... }`.
- **`custom` runs at match time; the constructor arguments are evaluated at object-init time.** Class names resolved by `decoderEntity` (`MEDIA_CLASS_NAME`, `MEDIAEXT_CLASS_NAME`) are `var`s filled in during `execute`. Using them in `parameters = listOf(...)` only works because Kotlin `object` init is lazy and the patch depends on `decoderEntity`. Using them inside `custom` is unconditionally safe. Prefer `custom` for anything built from a resolved class name.

### Prefer signature shape over log strings

String anchors read well and drift badly. Shape anchors built from the class names Instagram does **not** obfuscate (`Lcom/instagram/feed/media/Media;`, `MediaFrameLayout`, `AutoplayPlaybackState`, `ReelViewGroup`, framework types) survive version bumps and, more importantly, guarantee the matched method actually receives the object you want to hook.

An unconstrained string fingerprint frequently lands on a synthetic lambda class that merely *mentions* the string. Real example: `strings = listOf("MediaOptionsOverflowHelper")` alone resolved to a helper class where **0 of 27** methods take a `Media` parameter, while the same string constrained with `parameters` and `returnType` (as the download patch does) resolved to the intended class.

### Do not let `runCatching` hide a dead patch

`runCatching` per fingerprint is right, but a patch that matches everything and injects nothing must not look like success. Count injections, attribute them per anchor, and fail loudly on zero:

```kotlin
fun anchor(name: String, block: () -> Int) {
    val count = runCatching(block).getOrElse { report += "$name=miss"; return }
    injected += count
    report += "$name=$count"          // "anchor=0" is a reported outcome, not a silent skip
}
if (injected == 0) throw PatchException("no hooks injected (${report.joinToString()})")
```

A helper that returns `false` for both "no matching parameter" and "injection failed" makes a dead anchor indistinguishable from a live one. That is how a feature ships doing nothing.

## Injecting smali safely

**Register width.** Parameter register `pN` is not parameter index `N`. Start at 1 for non-static methods (`p0` is `this`), 0 for static, then add **2** for each preceding `J` or `D` and 1 for everything else.

```kotlin
private fun Method.paramRegister(index: Int): Int {
    var register = if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1
    for (i in 0 until index) register += if (parameterTypes[i].let { it == "J" || it == "D" }) 2 else 1
    return register
}
```

**Register range.** `invoke-static {pN}` assembles to format 35c, which addresses only **v0–v15**. In a method with many parameters `pN` routinely maps above v15 and the instruction cannot encode. Instagram's Reels binders take 20+ parameters. Use the range form, which costs the same three code units and has no downside:

```kotlin
"invoke-static/range {p$register .. p$register}, $HOOK_CLASS->$hookName(Ljava/lang/Object;)V"
```

`p` registers are valid inside range syntax; `HookFlagsPatch` and `FriendshipStatusIndicatorPatch` already do this.

**Skip what cannot be injected.** Guard on `implementation != null` (abstract and native methods) and skip `<clinit>`.

## Verifying anchors against the real APK

Do not guess and ship. Unpack the supported build and confirm every anchor resolves to a method that takes the object you intend to pass.

`docs/mappings/*.json` are **Mobile Config parameter dumps, not DEX maps.** They cannot confirm a fingerprint.

```sh
unzip -o app.apkm -d /tmp/ig            # APKM is a zip of base + split APKs
unzip -o /tmp/ig/base.apk 'classes*.dex' -d /tmp/ig/dex
for f in /tmp/ig/dex/*.dex; do "$ANDROID_HOME"/build-tools/*/dexdump -d "$f" > "/tmp/ig/dump/$(basename "$f" .dex).txt"; done
jadx -d /tmp/ig/jadx --single-class LX.C01Sb /tmp/ig/base.apk   # readable Java for one class
```

Build a JSON index of every method whose signature contains the type you care about, then emulate each fingerprint against it. What the index must report per candidate: defining class, name, full signature, access flags, the parameter index of your type, **and the computed `p` register**. That last column is what catches the v15 problem before patch time rather than after.

`dexdump` output is indentation-sensitive and its class/method headers change shape between entries; expect to iterate on the parser regexes. Cache the index to JSON — a full 20-dex pass is slow enough that you will want to re-run only the matcher.

Use `dexdump` to enumerate and `jadx` to understand. Signature search tells you a hook exists; only decompiled Java tells you whether it means "user watched this" or "analytics callback that is routinely invoked with null".

## Debugging on device

There is no `adb` on a user's unrooted phone, so `Logger.printDebug` is invisible. Anything you need to see must reach the UI.

**Carry patch-time facts into the app.** Put a sentinel in the extension and rewrite it during `execute`:

```java
public static String injectionReport() { return "injection-report"; }   // extension
```
```kotlin
InjectionReportExtensionFingerprint.changeString("injection-report", summary)   // patch
```

`changeString(sentinel, value)` in `utils/Utils.kt` matches by value rather than by slot index, so it does not break when string order shifts. This is safe because `extensions/proguard-rules.pro` sets `-dontobfuscate -dontoptimize` — constant strings are not inlined away and method names survive. Sanitize the value: it is spliced into smali, so strip quotes, backslashes, and newlines.

**Tag each injection site.** You cannot pass a tag as an argument, because injecting a `const-string` needs a free register in a method you do not control. Instead give each anchor its own thin static entry point that forwards to a shared implementation. Then per-site call counts tell you which anchors actually fire on a real device.

**Persist a ring buffer.** A few hundred lines in `filesDir`, reloaded on start, with a screen that shows it plus Copy and Clear. Instagram gets killed between "user watches videos" and "user checks the result", so an in-memory-only log is usually empty by the time anyone reads it.

**Keep the hot path cheap.** Hooks land inside binder and playback paths called at scroll rate. Do reference comparison and integer counting before anything else; gate on object identity before touching preferences, reflection, or the log. Note that `Logger.printDebug(() -> ...)` allocates a lambda on every call regardless of whether logging is enabled.

**Watch for hook re-entry.** If the extension uses reflection into a class you also swept (e.g. `MediaExtKt`), processing a captured object re-enters your own hook. An identity gate on recently-seen objects breaks the cycle; verify it does before shipping.

## Common Manager failures

| Symptom | Likely cause |
|---------|----------------|
| 0 patches after adding the source | Missing `classes.dex` in `.mpp`, or `signature_download_url` 404 |
| Still the old patch count | Manager is downloading upstream `patches-bundle.json` / an old release |
| Bundle version N/A / download error | `download_url` points at the wrong owner or a missing asset |
| Pre-release not listed | Pre-release toggle off for that source |

CLI can still patch from a class-only JAR; **Manager cannot**. Always ship `buildAndroid` output.

## Patch applies but the feature does nothing

| Symptom | Likely cause |
|---------|--------------|
| Hook never fires | Anchor resolved to a class whose methods do not take your object. Verify against the dex, not the string |
| Hook fires a handful of times, always with `null` | You hooked an analytics or callback site. Decompile it and read what it means |
| Hook fires but the object is wrong | Parameter index used as the register number, or a preceding `J`/`D` not counted as two |
| `PatchException` / assembler error on a many-parameter method | Needs `invoke-static/range`; plain `invoke-static` cannot address above v15 |
| Only one of several equivalent sites is hooked | A fingerprint returns one match. Sweep the class, or constrain and add a second anchor |
| Everything "succeeds" and the feature is empty | No injection accounting. Count per anchor and throw on zero |

Verify the built extension actually contains your changes before blaming the patch — Gradle reporting `compileReleaseJavaWithJavac UP-TO-DATE` after an edit is worth a second look:

```sh
unzip -p patches/build/libs/patches-*.mpp extensions/instagram.mpe > /tmp/ext.dex
strings /tmp/ext.dex | grep -E 'YourNewClass|yourNewMethod|your-sentinel'
```

`.mpe` is a bare DEX file, not a zip — `strings` works, `unzip` does not.
