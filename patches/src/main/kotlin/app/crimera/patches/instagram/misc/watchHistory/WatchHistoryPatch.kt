/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.watchHistory

import app.crimera.patches.instagram.entity.decoder.MEDIA_CLASS_NAME
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
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val HOOK_CLASS = "$PATCHES_DESCRIPTOR/watchHistory/WatchHistoryHook;"

/** Runtime hook class injected into feed and Reels viewers. */
// Dummy: trigger a pre-release after restoring tag ancestry.

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

private fun CharSequence.registerWidth(): Int = if (this == "J" || this == "D") 2 else 1

private fun Method.mediaParamIndex(): Int = parameters.indexOfFirst { it.type == MEDIA_CLASS_NAME }

/**
 * Smali `p` register of the Media parameter, accounting for the receiver and wide (`J`/`D`) params.
 */
private fun Method.mediaParamRegister(): Int? {
    val paramIndex = mediaParamIndex()
    if (paramIndex < 0) return null
    var register = if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1
    for (i in 0 until paramIndex) {
        register += parameters[i].type.registerWidth()
    }
    return register
}

private fun mediaHookSmali(method: Method, hookName: String = "onMediaViewed"): String? {
    val pIndex = method.mediaParamRegister() ?: return null
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
            fun MutableMethod.injectHook(hookName: String = "onMediaViewed"): Boolean {
                if (name == "<clinit>") return false
                if (implementation == null) return false
                val smali = mediaHookSmali(this, hookName) ?: return false
                val index =
                    if (name == "<init>") {
                        instructions.indexOfFirst { it.opcode == Opcode.INVOKE_DIRECT }
                            .let { if (it < 0) 0 else it + 1 }
                    } else {
                        0
                    }
                addInstructions(index, smali)
                return true
            }

            var injected = 0
            val matched = mutableListOf<String>()

            fun record(anchor: String, count: Int) {
                if (count <= 0) return
                injected += count
                matched += "$anchor=$count"
            }

            fun injectClassMethods(anchor: String, classType: String, hookName: String) {
                var count = 0
                mutableClassDefBy { it.type == classType }.methods.forEach { method ->
                    runCatching {
                        if (method.injectHook(hookName)) count++
                    }
                }
                record(anchor, count)
            }

            // Each hook is independently runCatching-wrapped: a drifted anchor should skip only
            // that capture point, never abort the patch — unless every capture point misses.

            // Feed + Reels analytics/session attach — first parameter is the Media object.
            runCatching {
                if (AslSessionRelatedFingerprint.method.injectHook()) {
                    record("aslSession", 1)
                }
            }

            // Reels viewer: ClipsItemState is created/updated for the current clip.
            runCatching {
                injectClassMethods(
                    "clipsItemState",
                    ClipsItemStateToStringFingerprint.classDef.type,
                    "onMediaViewedReel",
                )
            }

            // Reels item more-options controller is built when a Reel is on screen.
            runCatching {
                if (ClipsOrganicMediaItemViewMoreOptionsFingerprint.method.injectHook("onMediaViewedReel")) {
                    record("clipsOrganicMoreOptions", 1)
                }
            }

            // Feed overflow helper/creator holds the bound post Media.
            runCatching {
                injectClassMethods(
                    "overflowMenuCreator",
                    MediaOptionsOverflowMenuCreatorFingerprint.classDef.type,
                    "onMediaViewedPost",
                )
            }

            runCatching {
                injectClassMethods(
                    "overflowHelper",
                    MediaOptionsOverflowHelperFingerprint.classDef.type,
                    "onMediaViewedPost",
                )
            }

            // Main-feed litho media binder, if the string still exists on this build.
            runCatching {
                val method = MainFeedMediaBinderGroupFingerprint.matchOrNull()?.method ?: return@runCatching
                if (method.implementation == null) return@runCatching
                if (method.mediaParamIndex() >= 0) {
                    if (method.injectHook("onMediaViewedPost")) record("mainFeedBinder", 1)
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
                    record("mainFeedBinder", 1)
                }
            }

            if (injected == 0) {
                throw PatchException("Watch history: no capture hooks injected")
            }
            println("Watch history: injected $injected hooks (${matched.joinToString()})")

            enableSettings("watchHistory")
            addFlags("mainFeedActionBarFlags")
        }
    }
