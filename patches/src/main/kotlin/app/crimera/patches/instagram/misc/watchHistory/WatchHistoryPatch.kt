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
import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.getReference
import app.morphe.util.registersUsed
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val HOOK_CLASS = "$PATCHES_DESCRIPTOR/watchHistory/WatchHistoryHook;"

private object AslSessionMediaFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("asl_session_id", "is_video", "is_carousel"),
)

private object ClipsItemStateToStringFingerprint : Fingerprint(
    name = "toString",
    strings = listOf("ClipsItemState(lastUserPausedPositionMs="),
)

private object ClipsOrganicMediaItemViewMoreOptionsFingerprint : Fingerprint(
    strings = listOf("ClipsOrganicMediaItemViewMoreOptionsController", "reels"),
)

private object MediaOptionsOverflowMenuCreatorFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("MediaOptionsOverflowMenuCreator"),
)

private object MediaOptionsOverflowHelperFingerprint : Fingerprint(
    strings = listOf("MediaOptionsOverflowHelper"),
)

private object MainFeedMediaBinderGroupFingerprint : Fingerprint(
    strings = listOf("MainFeedMediaBinderGroup"),
)

private fun Method.mediaParamIndex(): Int = parameters.indexOfFirst { it.type == MEDIA_CLASS_NAME }

private fun mediaHookSmali(method: Method, hookName: String = "onMediaViewed"): String? {
    val paramIndex = method.mediaParamIndex()
    if (paramIndex < 0) return null
    val pIndex = if (AccessFlags.STATIC.isSet(method.accessFlags)) paramIndex else paramIndex + 1
    return "invoke-static {p$pIndex}, $HOOK_CLASS->$hookName(Ljava/lang/Object;)V"
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
            fun MutableMethod.injectHook(hookName: String = "onMediaViewed") {
                if (name == "<clinit>") return
                val smali = mediaHookSmali(this, hookName) ?: return
                val index =
                    if (name == "<init>") {
                        instructions.indexOfFirst { it.opcode == Opcode.INVOKE_DIRECT }
                            .let { if (it < 0) 0 else it + 1 }
                    } else {
                        0
                    }
                addInstructions(index, smali)
            }

            // Each hook is independently runCatching-wrapped: a drifted anchor should skip only
            // that capture point, never abort the patch.

            // Feed + Reels analytics/session attach — first parameter is the Media object.
            runCatching {
                AslSessionMediaFingerprint.method.injectHook()
            }

            // Reels viewer: ClipsItemState is created/updated for the current clip.
            runCatching {
                mutableClassDefBy { it.type == ClipsItemStateToStringFingerprint.classDef.type }
                    .methods
                    .forEach { it.injectHook("onMediaViewedReel") }
            }

            // Reels item more-options controller is built when a Reel is on screen.
            runCatching {
                ClipsOrganicMediaItemViewMoreOptionsFingerprint.method.injectHook("onMediaViewedReel")
            }

            // Feed overflow helper/creator holds the bound post Media.
            runCatching {
                mutableClassDefBy { it.type == MediaOptionsOverflowMenuCreatorFingerprint.classDef.type }
                    .methods
                    .forEach { it.injectHook("onMediaViewedPost") }
            }

            runCatching {
                mutableClassDefBy { it.type == MediaOptionsOverflowHelperFingerprint.classDef.type }
                    .methods
                    .forEach { it.injectHook("onMediaViewedPost") }
            }

            // Main-feed litho media binder, if the string still exists on this build.
            runCatching {
                val method = MainFeedMediaBinderGroupFingerprint.matchOrNull()?.method ?: return@runCatching
                if (method.mediaParamIndex() >= 0) {
                    method.injectHook("onMediaViewedPost")
                } else {
                    val iget =
                        method.instructions.first {
                            it.opcode == Opcode.IGET_OBJECT &&
                                it.getReference<FieldReference>()?.type == MEDIA_CLASS_NAME
                        }
                    val mediaRegister = iget.registersUsed[0]
                    method.addInstructions(
                        iget.location.index + 1,
                        "invoke-static {v$mediaRegister}, $HOOK_CLASS->onMediaViewedPost(Ljava/lang/Object;)V",
                    )
                }
            }

            enableSettings("watchHistory")
            addFlags("mainFeedActionBarFlags")
        }
    }
