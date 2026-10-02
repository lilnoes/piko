/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.watchHistory

import app.crimera.patches.instagram.entity.decoder.MEDIA_CLASS_NAME
import app.crimera.patches.instagram.entity.decoder.decoderEntity
import app.crimera.patches.instagram.entity.mediadata.mediaDataEntity
import app.crimera.patches.instagram.entity.originalSoundDataIntf.originalSoundDataIntfEntity
import app.crimera.patches.instagram.entity.trackDataIntf.trackDataIntfEntity
import app.crimera.patches.instagram.entity.userdata.userDataEntity
import app.crimera.patches.instagram.misc.actionBar.mainFeedActionBarButton.mainFeedActionBarButtonPatch
import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.crimera.patches.instagram.utils.addFlags
import app.crimera.patches.instagram.utils.enableSettings
import app.crimera.utils.changeString
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.getReference
import app.morphe.util.registersUsed
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val HOOK_CLASS = "$PATCHES_DESCRIPTOR/watchHistory/WatchHistoryHook;"

/**
 * Anchors are matched by signature shape built from class names Instagram does not obfuscate,
 * rather than by log strings. Verified against 439.0.0.37.89.
 */
private const val MEDIA_FRAME_LAYOUT = "Lcom/instagram/ui/widget/framelayout/MediaFrameLayout;"
private const val AUTOPLAY_PLAYBACK_STATE = "Lcom/instagram/autoplay/models/AutoplayPlaybackState;"
private const val AUTOPLAY_PLAYBACK_HISTORY = "Lcom/instagram/autoplay/models/AutoplayPlaybackHistory;"
private const val AUTOPLAY_SCREEN_ITEM = "Lcom/instagram/autoplay/models/AutoplayScreenItemWithoutMetadata;"
private const val REEL_VIEW_GROUP = "Lcom/instagram/reels/viewer/common/ReelViewGroup;"
private const val REEL_ITEM = "Lcom/instagram/model/reels/ReelItem;"

private fun Method.paramTypes(): List<String> = parameterTypes.map { it.toString() }

/**
 * Instagram's own playback recorder: appends a timed segment whenever a media changes
 * playback state, so this fires the moment a video actually starts playing.
 */
private object AutoplayPlaybackStateFingerprint : Fingerprint(
    returnType = "V",
    custom = { method, _ ->
        val params = method.parameterTypes.map { it.toString() }
        params.size == 2 &&
            params[0] == AUTOPLAY_PLAYBACK_STATE &&
            params[1] == MEDIA_CLASS_NAME
    },
)

/** Created the first time a media gets a playback history entry. */
private object AutoplayPlaybackHistoryInitFingerprint : Fingerprint(
    definingClass = AUTOPLAY_PLAYBACK_HISTORY,
    name = "<init>",
    custom = { method, _ ->
        val params = method.parameterTypes.map { it.toString() }
        params.size == 4 && params[0] == MEDIA_CLASS_NAME
    },
)

/** Resolves the on-screen item for a media. */
private object AutoplayOnScreenItemFingerprint : Fingerprint(
    returnType = AUTOPLAY_SCREEN_ITEM,
    custom = { method, _ ->
        val params = method.parameterTypes.map { it.toString() }
        params.size == 1 && params[0] == MEDIA_CLASS_NAME
    },
)

/**
 * Per-post touch handler bundle, built when a feed post is bound to its view. It also
 * constructs the gesture listener that other Instagram mods hook, but here the media is a
 * plain parameter instead of a field read, and the constructor has free registers.
 */
private object FeedMediaTouchHandlerInitFingerprint : Fingerprint(
    returnType = "V",
    name = "<init>",
    custom = { method, _ ->
        val params = method.parameterTypes.map { it.toString() }
        params.size >= 4 &&
            params[0] == "Landroid/content/Context;" &&
            params[1] == MEDIA_CLASS_NAME &&
            params.contains(MEDIA_FRAME_LAYOUT)
    },
)

/** Binds a media into its frame layout. */
private object MediaFrameBindFingerprint : Fingerprint(
    returnType = "V",
    custom = { method, _ ->
        val params = method.parameterTypes.map { it.toString() }
        params.size == 4 &&
            params[0] == MEDIA_CLASS_NAME &&
            params[3] == MEDIA_FRAME_LAYOUT &&
            method.implementation != null
    },
)

/** Reel and story viewer binder: carries the media alongside the reel view group. */
private object ReelViewerBinderFingerprint : Fingerprint(
    returnType = "V",
    custom = { method, _ ->
        val params = method.parameterTypes.map { it.toString() }
        params.contains(MEDIA_CLASS_NAME) &&
            (params.contains(REEL_VIEW_GROUP) || params.contains(REEL_ITEM)) &&
            params.contains(MEDIA_FRAME_LAYOUT)
    },
)

private object ClipsItemStateToStringFingerprint : Fingerprint(
    name = "toString",
    strings = listOf("ClipsItemState(lastUserPausedPositionMs="),
)

/**
 * Instagram's own impression tracker, the only subsystem found on 439 that distinguishes a
 * media the user looked at from one the feed merely prepared: it reports a media to its logger
 * only after the view outlived a dwell threshold, which prefetch can never satisfy.
 */
private const val IMPRESSION_TRACKER_STRING = "Viewable info missing for media with key %s"

private object ImpressionTrackerFingerprint : Fingerprint(
    strings = listOf(IMPRESSION_TRACKER_STRING),
)

/** Milliseconds a media must stay on screen before the tracker reports time spent on it. */
private const val DWELL_THRESHOLD_MS = 500L

/**
 * Declaration of the per-view impression listener, whose callbacks fire as a media enters,
 * becomes partly visible and leaves the screen. Matched on the percent-visible callback, the
 * only one carrying a `View` and a `double` next to a media.
 */
/**
 * Shapes of Instagram's clips watch-state callbacks, carrying the item whose single `Media` field
 * is the reel being played. Matched by shape because the method names are obfuscated, and swept
 * across every implementation because the interface has dozens and the live one is not knowable
 * statically.
 *
 * `(item, positionMs, ?, ?, ?)` is the progress callback and `(item, positionMs, loops)` the loop
 * callback. Instagram's own implementation of the former treats 3 seconds of playback as a watch.
 */
private val CLIPS_PROGRESS_TAIL = listOf("I", "I", "I", "Z")
private val CLIPS_LOOP_TAIL = listOf("I", "I")

/**
 * The declaring type of the item parameter, if this method has the given callback shape. Tested
 * against every method in the app, so the parameter list is only materialised once the cheap
 * arity and return type checks pass.
 */
private fun Method.clipsItemType(tail: List<String>): String? {
    if (parameterTypes.size != tail.size + 1) return null
    if (returnType != "V") return null
    val params = paramTypes()
    if (!params[0].startsWith("L")) return null
    if (tail.indices.any { params[it + 1] != tail[it] }) return null
    return params[0]
}

private object ImpressionListenerFingerprint : Fingerprint(
    returnType = "V",
    custom = { method, _ ->
        val params = method.parameterTypes.map { it.toString() }
        params.size == 3 &&
            params[0] == "Landroid/view/View;" &&
            params[1] == MEDIA_CLASS_NAME &&
            params[2] == "D" &&
            // The interface declaration, not one of the implementations.
            method.implementation == null
    },
)

/** Placeholder in the extension, rewritten with the list of anchors that injected. */
private object InjectionReportExtensionFingerprint : Fingerprint(
    definingClass = HOOK_CLASS,
    name = "injectionReport",
)

/** Placeholder in the extension, rewritten with the method behind each probe index. */
private object ProbeReportExtensionFingerprint : Fingerprint(
    definingClass = HOOK_CLASS,
    name = "probeReport",
)

/** Must match WatchHistoryHook.PROBE_COUNT. */
private const val PROBE_COUNT = 24

private fun Method.itemParamIndex(types: Set<String>): Int = paramTypes().indexOfFirst { it in types }

private fun Method.mediaParamIndex(): Int = itemParamIndex(setOf(MEDIA_CLASS_NAME))

private fun CharSequence.registerWidth(): Int = if (this == "J" || this == "D") 2 else 1

/** Smali `p` register of a parameter, accounting for the receiver and wide params. */
private fun Method.paramRegister(index: Int): Int {
    var register = if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1
    for (i in 0 until index) {
        register += paramTypes()[i].registerWidth()
    }
    return register
}

private fun Method.itemParamRegister(types: Set<String>): Int? =
    itemParamIndex(types).takeIf { it >= 0 }?.let { paramRegister(it) }

@Suppress("unused")
val watchHistoryPatch =
    bytecodePatch(
        name = "Watch history",
        description = "Records posts and Reels as you view them and adds a searchable history button on the home feed.",
        default = true,
    ) {
        dependsOn(
            settingsPatch,
            decoderEntity,
            mediaDataEntity,
            userDataEntity,
            originalSoundDataIntfEntity,
            trackDataIntfEntity,
            mainFeedActionBarButtonPatch,
            watchHistoryResourcePatch,
        )
        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            val report = mutableListOf<String>()
            var injected = 0

            /**
             * The impression callbacks take the interface `Media` implements rather than `Media`
             * itself, so the accepted parameter types are the transitive interfaces of `Media`.
             */
            val mediaItemTypes =
                buildSet {
                    add(MEDIA_CLASS_NAME)
                    val pending = ArrayDeque(listOf(MEDIA_CLASS_NAME))
                    while (pending.isNotEmpty()) {
                        val classDef = classDefByOrNull(pending.removeFirst()) ?: continue
                        classDef.interfaces.forEach { if (add(it)) pending += it }
                    }
                }

            fun MutableMethod.injectHook(
                hookName: String,
                types: Set<String> = setOf(MEDIA_CLASS_NAME),
            ): Boolean {
                if (name == "<clinit>") return false
                if (implementation == null) return false
                val register = itemParamRegister(types) ?: return false
                val index =
                    if (name == "<init>") {
                        // Never before the super() call.
                        instructions.indexOfFirst { it.opcode == Opcode.INVOKE_DIRECT }
                            .let { if (it < 0) 0 else it + 1 }
                    } else {
                        0
                    }
                // The Reels binders take 20+ parameters, so the media register is routinely
                // above v15 and the non-range invoke would not encode.
                addInstructions(
                    index,
                    "invoke-static/range {p$register .. p$register}, $HOOK_CLASS->$hookName(Ljava/lang/Object;)V",
                )
                return true
            }

            fun MutableMethod.injectAfterMediaIget(hookName: String): Boolean {
                val iget =
                    instructions.firstOrNull {
                        it.opcode == Opcode.IGET_OBJECT &&
                            it.getReference<FieldReference>()?.type == MEDIA_CLASS_NAME
                    } ?: return false
                val register = iget.registersUsed[0]
                addInstructions(
                    iget.location.index + 1,
                    "invoke-static/range {v$register .. v$register}, $HOOK_CLASS->$hookName(Ljava/lang/Object;)V",
                )
                return true
            }

            /**
             * Each anchor is isolated: a drifted one must skip only its own capture point.
             * An anchor that matches but injects nothing is recorded as `anchor=0` rather than
             * passing silently, which is how the previous revision shipped a dead feature.
             */
            fun anchor(name: String, block: () -> Int) {
                val count =
                    runCatching(block).getOrElse {
                        report += "$name=miss"
                        return
                    }
                injected += count
                report += "$name=$count"
            }

            fun sweepClass(classType: String, hookName: String): Int {
                var count = 0
                mutableClassDefBy { it.type == classType }.methods.forEach { method ->
                    runCatching { if (method.injectHook(hookName)) count++ }
                }
                return count
            }

            // Video actually started playing.
            anchor("autoplayState") {
                if (AutoplayPlaybackStateFingerprint.method.injectHook("onAutoplayState")) 1 else 0
            }

            anchor("autoplayHistory") {
                if (AutoplayPlaybackHistoryInitFingerprint.method.injectHook("onAutoplayHistory")) 1 else 0
            }

            anchor("autoplayScreen") {
                if (AutoplayOnScreenItemFingerprint.method.injectHook("onScreenItem")) 1 else 0
            }

            // Feed post bound to its view.
            anchor("feedBind") {
                sweepClass(FeedMediaTouchHandlerInitFingerprint.classDef.type, "onFeedBind")
            }

            anchor("frameBind") {
                if (MediaFrameBindFingerprint.method.injectHook("onFrameBind")) 1 else 0
            }

            // Reels viewer: ClipsItemState has no Media parameter. The factory that builds
            // it from a ClipsItem immediately reads Media off a field — same pattern InstaPro
            // uses on the feed gesture listener. The previous fingerprint required PUBLIC+STATIC
            // without FINAL and used getReference during match, which missed on 439 (clipsState=miss).
            anchor("clipsState") {
                var count = 0
                mutableClassDefBy { it.type == ClipsItemStateToStringFingerprint.classDef.type }
                    .methods
                    .forEach { method ->
                        if (method.name == "<clinit>" || method.name == "toString") return@forEach
                        runCatching { if (method.injectAfterMediaIget("onClipsState")) count++ }
                    }
                count
            }

            // Story / in-feed reel viewer binders.
            anchor("reelViewer") {
                sweepClass(ReelViewerBinderFingerprint.classDef.type, "onReelBind")
            }

            /**
             * Both dwell-gated entry points of the impression tracker take (item, int) and differ
             * only in body: one compares the elapsed time against the dwell threshold, the other
             * carries the tracker's own warning string.
             */
            val trackerMethods =
                runCatching {
                    val trackerType = ImpressionTrackerFingerprint.classDef.type
                    mutableClassDefBy { it.type == trackerType }.methods.filter {
                        it.implementation != null && it.itemParamIndex(mediaItemTypes) >= 0
                    }
                }.getOrDefault(emptyList())

            val dwellShaped =
                trackerMethods.filter {
                    val params = it.paramTypes()
                    params.size == 2 && params[1] == "I" && params[0] != MEDIA_CLASS_NAME
                }

            anchor("imprDwell") {
                val method =
                    dwellShaped.firstOrNull { method ->
                        method.instructions.any {
                            (it as? WideLiteralInstruction)?.wideLiteral == DWELL_THRESHOLD_MS
                        }
                    } ?: return@anchor 0
                if (method.injectHook("onImpressionDwell", mediaItemTypes)) 1 else 0
            }

            anchor("imprEnd") {
                val method =
                    dwellShaped.firstOrNull { method ->
                        method.instructions.any {
                            it.getReference<StringReference>()?.string == IMPRESSION_TRACKER_STRING
                        }
                    } ?: return@anchor 0
                if (method.injectHook("onImpressionEnd", mediaItemTypes)) 1 else 0
            }

            /**
             * Probe groups whose live implementation is not knowable statically. A fingerprint
             * resolves to exactly one method, which is how the previous revision instrumented one
             * of two autoplay recorders and concluded the subsystem was dead, so each group is
             * swept across every implementation instead.
             */
            val probeGroups = mutableListOf<Pair<String, (Method) -> Boolean>>()

            // Keyed by method name, matched by the declaration on the listener interface.
            runCatching {
                ImpressionListenerFingerprint.classDef.methods
                    .filter { it.itemParamIndex(mediaItemTypes) >= 0 }
                    .sortedBy { it.name }
                    .forEach { declared ->
                        val params = declared.paramTypes()
                        probeGroups +=
                            "lst.${declared.name}" to { method: Method -> method.paramTypes() == params }
                    }
            }
            val namedGroups = probeGroups.indices.groupBy { probeGroups[it].first.substringAfter('.') }

            // Keyed by signature alone, because the name carries no meaning here.
            val autoplayGroup = probeGroups.size
            probeGroups +=
                "autoplayAll" to
                    { method: Method ->
                        // Checked against every method in the app, so the cheap test comes first
                        // and the parameter list is never materialised.
                        method.parameterTypes.size == 2 &&
                            method.parameterTypes[0].toString() == AUTOPLAY_PLAYBACK_STATE &&
                            method.parameterTypes[1].toString() == MEDIA_CLASS_NAME
                    }

            // One pass over the dex rather than one scan per group. Whether a match is abstract is
            // left to the injection step, since reading every implementation here would mean
            // parsing the code of every method in the app.
            val groupHits = List(probeGroups.size) { mutableListOf<Pair<ClassDef, Method>>() }
            val mediaFieldTypes = mutableSetOf<String>()
            val clipsProgress = mutableMapOf<String, MutableList<Pair<ClassDef, Method>>>()
            val clipsLoop = mutableMapOf<String, MutableList<Pair<ClassDef, Method>>>()
            classDefForEach { classDef ->
                if (classDef.fields.any { it.type == MEDIA_CLASS_NAME }) {
                    mediaFieldTypes += classDef.type
                }
                classDef.methods.forEach { method ->
                    namedGroups[method.name]?.forEach { group ->
                        if (probeGroups[group].second(method)) groupHits[group] += classDef to method
                    }
                    if (probeGroups[autoplayGroup].second(method)) {
                        groupHits[autoplayGroup] += classDef to method
                    }
                    method.clipsItemType(CLIPS_PROGRESS_TAIL)?.let {
                        clipsProgress.getOrPut(it) { mutableListOf() } += classDef to method
                    }
                    method.clipsItemType(CLIPS_LOOP_TAIL)?.let {
                        clipsLoop.getOrPut(it) { mutableListOf() } += classDef to method
                    }
                }
            }

            /**
             * The clips item is the only type carrying a media that appears as the subject of the
             * progress callback, and the implementation that wins a tie is the one with the most
             * implementations, since the interface is implemented across the whole viewer.
             */
            val clipsItemType =
                clipsProgress.keys
                    .filter { it in mediaFieldTypes }
                    .maxByOrNull { clipsProgress.getValue(it).size }

            /**
             * Both callbacks pass consecutive parameters, so a range invoke covers them without
             * needing a free register in methods this patch does not control.
             */
            fun sweepClipsCallback(
                hits: List<Pair<ClassDef, Method>>,
                hook: String,
                argCount: Int,
            ): Int {
                var count = 0
                for ((classDef, method) in hits) {
                    runCatching {
                        val mutable =
                            mutableClassDefBy(classDef).methods.first {
                                it.name == method.name && it.paramTypes() == method.paramTypes()
                            }
                        if (mutable.implementation == null) return@runCatching
                        val first = mutable.paramRegister(0)
                        val last = mutable.paramRegister(argCount - 1)
                        val signature = "Ljava/lang/Object;" + "I".repeat(argCount - 1)
                        mutable.addInstructions(
                            0,
                            "invoke-static/range {p$first .. p$last}, " +
                                "$HOOK_CLASS->$hook($signature)V",
                        )
                        count++
                    }
                }
                return count
            }

            // Reel actually played: Instagram's own watch-state listener, which its own
            // implementation reads as a watch at three seconds of playback.
            anchor("clipsProgress") {
                val type = clipsItemType ?: return@anchor 0
                sweepClipsCallback(clipsProgress.getValue(type), "onClipsProgress", 2)
            }

            anchor("clipsLoop") {
                val type = clipsItemType ?: return@anchor 0
                sweepClipsCallback(clipsLoop[type].orEmpty(), "onClipsLoop", 3)
            }

            /**
             * Feed posts get no help from the impression tracker, which is behind a server flag
             * and was not running on the test device. The listener's own impression start and
             * visibility updates bracket the time a post spends on screen, which is enough to
             * measure the dwell in the extension. Both are identified by shape: the four-argument
             * form is unique to the impression start, and only the visibility update carries a
             * `View` and a `double`.
             */
            val listenerMethods =
                runCatching { ImpressionListenerFingerprint.classDef.methods.toList() }
                    .getOrDefault(emptyList())

            fun listenerName(params: List<String>): String? =
                listenerMethods.firstOrNull { it.paramTypes() == params }?.name

            /** Every implementation of one listener callback, found by the pass above. */
            fun sweepListener(name: String?, hook: String, paramIndex: Int): Int {
                if (name == null) return 0
                var count = 0
                for (group in namedGroups[name].orEmpty()) {
                    for ((classDef, method) in groupHits[group]) {
                        runCatching {
                            val mutable =
                                mutableClassDefBy(classDef).methods.first {
                                    it.name == name && it.paramTypes() == method.paramTypes()
                                }
                            if (mutable.implementation == null) return@runCatching
                            val register = mutable.paramRegister(paramIndex)
                            mutable.addInstructions(
                                0,
                                "invoke-static/range {p$register .. p$register}, " +
                                    "$HOOK_CLASS->$hook(Ljava/lang/Object;)V",
                            )
                            count++
                        }
                    }
                }
                return count
            }

            anchor("feedEnter") {
                val name = listenerName(listOf(MEDIA_CLASS_NAME, "I", "I", "I"))
                sweepListener(name, "onFeedEnter", 0)
            }

            anchor("feedVisible") {
                val name = listenerName(listOf("Landroid/view/View;", MEDIA_CLASS_NAME, "D"))
                sweepListener(name, "onFeedVisible", 1)
            }

            if (injected == 0) {
                throw PatchException("Watch history: no capture hooks injected (${report.joinToString()})")
            }

            // The anchors that were supposed to mean "this played" reported zero calls on 439,
            // leaving only prefetch-time binders, so the remaining candidates are instrumented
            // instead of guessed at. Probes are diagnostics: they never write history, and they
            // are excluded from `injected` so they cannot mask a failed anchor.
            val probeTargets = mutableListOf<String>()

            fun probeMethod(method: MutableMethod, label: String, index: Int): Boolean =
                runCatching { method.injectHook("onProbe$index", mediaItemTypes) }
                    .getOrDefault(false)
                    .also { if (it) probeTargets += "probe$index=$label" }

            // Every item-carrying entry point of the tracker, so the log distinguishes impression
            // start from impression end, dwell from percent-visible, and whole media from
            // carousel child.
            for (method in trackerMethods) {
                if (probeTargets.size >= PROBE_COUNT) break
                probeMethod(method, "trk.${method.name}", probeTargets.size)
            }

            probeGroups.forEachIndexed { group, (label, _) ->
                val hits = groupHits[group]
                if (hits.isEmpty() || probeTargets.size >= PROBE_COUNT) return@forEachIndexed
                val index = probeTargets.size
                var count = 0
                for ((classDef, method) in hits) {
                    val mutable =
                        runCatching {
                            mutableClassDefBy(classDef).methods.first {
                                it.name == method.name && it.paramTypes() == method.paramTypes()
                            }
                        }.getOrNull() ?: continue
                    if (runCatching { mutable.injectHook("onProbe$index", mediaItemTypes) }
                            .getOrDefault(false)
                    ) {
                        count++
                    }
                }
                if (count > 0) probeTargets += "probe$index=$label x$count"
            }
            report += "probes=${probeTargets.size}"

            // Surface the result in the app; patch-time stdout is invisible in the manager.
            val summary = "$injected hooks: ${report.joinToString()}".replace(Regex("[^A-Za-z0-9=,:. -]"), "_")
            runCatching {
                InjectionReportExtensionFingerprint.changeString("injection-report", summary)
            }
            val probeSummary =
                (if (probeTargets.isEmpty()) "no probes" else probeTargets.joinToString(", "))
                    .replace(Regex("[^A-Za-z0-9=,:. -]"), "_")
            runCatching {
                ProbeReportExtensionFingerprint.changeString("probe-report", probeSummary)
            }
            println("Watch history: $summary")
            println("Watch history probes: $probeSummary")

            enableSettings("watchHistory")
            addFlags("mainFeedActionBarFlags")
        }
    }
