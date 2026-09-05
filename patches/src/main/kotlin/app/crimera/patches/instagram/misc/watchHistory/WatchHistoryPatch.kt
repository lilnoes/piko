/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.watchHistory

import app.crimera.patches.instagram.entity.decoder.MEDIA_CLASS_NAME
import app.crimera.patches.instagram.entity.decoder.MEDIAEXT_CLASS_NAME
import app.crimera.patches.instagram.entity.decoder.decoderEntity
import app.crimera.patches.instagram.entity.mediadata.AslSessionRelatedFingerprint
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
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method

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

private object ClipsOrganicMediaItemViewMoreOptionsFingerprint : Fingerprint(
    strings = listOf("ClipsOrganicMediaItemViewMoreOptionsController", "reels"),
)

private object MediaOptionsOverflowMenuCreatorFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("MediaOptionsOverflowMenuCreator"),
)

/** Placeholder in the extension, rewritten with the list of anchors that injected. */
private object InjectionReportExtensionFingerprint : Fingerprint(
    definingClass = HOOK_CLASS,
    name = "injectionReport",
)

private fun Method.mediaParamIndex(): Int = paramTypes().indexOfFirst { it == MEDIA_CLASS_NAME }

private fun CharSequence.registerWidth(): Int = if (this == "J" || this == "D") 2 else 1

/** Smali `p` register of the media parameter, accounting for the receiver and wide params. */
private fun Method.mediaParamRegister(): Int? {
    val paramIndex = mediaParamIndex()
    if (paramIndex < 0) return null
    var register = if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1
    for (i in 0 until paramIndex) {
        register += paramTypes()[i].registerWidth()
    }
    return register
}

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

            fun MutableMethod.injectHook(hookName: String): Boolean {
                if (name == "<clinit>") return false
                if (implementation == null) return false
                val register = mediaParamRegister() ?: return false
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

            // Reel and story viewer.
            anchor("reelViewer") {
                sweepClass(ReelViewerBinderFingerprint.classDef.type, "onReelBind")
            }

            anchor("clipsOptions") {
                if (ClipsOrganicMediaItemViewMoreOptionsFingerprint.method.injectHook("onReelBind")) 1 else 0
            }

            anchor("overflowCreator") {
                sweepClass(MediaOptionsOverflowMenuCreatorFingerprint.classDef.type, "onFeedBind")
            }

            anchor("aslSession") {
                if (AslSessionRelatedFingerprint.method.injectHook("onAslSession")) 1 else 0
            }

            // Coverage net: the media helper every surface calls through.
            anchor("mediaExt") {
                sweepClass(MEDIAEXT_CLASS_NAME, "onMediaExt")
            }

            if (injected == 0) {
                throw PatchException("Watch history: no capture hooks injected (${report.joinToString()})")
            }

            // Surface the result in the app; patch-time stdout is invisible in the manager.
            val summary = "$injected hooks: ${report.joinToString()}".replace(Regex("[^A-Za-z0-9=,:. -]"), "_")
            runCatching {
                InjectionReportExtensionFingerprint.changeString("injection-report", summary)
            }
            println("Watch history: $summary")

            enableSettings("watchHistory")
            addFlags("mainFeedActionBarFlags")
        }
    }
