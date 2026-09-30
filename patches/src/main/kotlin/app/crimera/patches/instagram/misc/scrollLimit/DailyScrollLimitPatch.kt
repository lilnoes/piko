/*
 * Copyright (C) 2026 piko <https://github.com/crimera/piko>
 *
 * See the included NOTICE file for GPLv3 §7(b) terms that apply to this code.
 */

package app.crimera.patches.instagram.misc.scrollLimit

import app.crimera.patches.instagram.misc.reels.disableReelsScrollingPatch
import app.crimera.patches.instagram.misc.settings.IgFragmentActivityOnCreate
import app.crimera.patches.instagram.misc.settings.settingsPatch
import app.crimera.patches.instagram.utils.Constants.COMPATIBILITY_INSTAGRAM
import app.crimera.patches.instagram.utils.Constants.PATCHES_DESCRIPTOR
import app.crimera.patches.instagram.utils.enableSettings
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.indexOfFirstInstruction
import com.android.tools.smali.dexlib2.Opcode

private const val SCROLL_LIMIT_CLASS = "$PATCHES_DESCRIPTOR/scrollLimit/ScrollLimit;"

@Suppress("unused")
val dailyScrollLimitPatch =
    bytecodePatch(
        name = "Daily scroll limit",
        description = "Sets a daily limit on minutes spent in the app. Once it is used up, Disable Reels scrolling is switched on and held on until midnight.",
        default = false,
    ) {
        // Switching the preference on only has an effect if the Reels patch is applied.
        dependsOn(settingsPatch, disableReelsScrollingPatch)

        compatibleWith(COMPATIBILITY_INSTAGRAM)

        execute {
            // Same activity hook settingsPatch uses for Utils.setActivity; the extension then
            // registers process-wide lifecycle callbacks from the first activity it sees.
            IgFragmentActivityOnCreate.method.apply {
                addInstruction(
                    indexOfFirstInstruction(Opcode.RETURN_VOID),
                    "invoke-static {p0}, $SCROLL_LIMIT_CLASS->install(Landroid/app/Activity;)V",
                )
            }

            enableSettings("dailyScrollLimit")
        }
    }
