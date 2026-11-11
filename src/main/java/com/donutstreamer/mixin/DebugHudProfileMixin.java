package com.donutstreamer.mixin;

import com.donutstreamer.StreamMode;
import net.minecraft.client.gui.hud.debug.DebugHudProfile;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Hides the location lines of the F3 screen while stream mode is on (display only). */
@Mixin(DebugHudProfile.class)
public abstract class DebugHudProfileMixin {
    private static Collection<Identifier> ds$lastSource = null;
    private static int ds$lastSize = -1;
    private static List<Identifier> ds$lastFiltered = null;

    @Inject(method = "isEntryVisible", at = @At("RETURN"), cancellable = true)
    private void ds$hideLocationEntry(Identifier id, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue() && StreamMode.hidingCoords() && StreamMode.isLocationEntry(id)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "getVisibleEntries", at = @At("RETURN"), cancellable = true)
    private void ds$filterVisibleEntries(CallbackInfoReturnable<Collection<Identifier>> cir) {
        if (!StreamMode.hidingCoords()) return;
        Collection<Identifier> src = cir.getReturnValue();
        if (src == null) return;
        if (src == ds$lastSource && src.size() == ds$lastSize && ds$lastFiltered != null) {
            cir.setReturnValue(ds$lastFiltered);   // reuse until the list changes: no per-frame allocation
            return;
        }
        List<Identifier> out = new ArrayList<>(src.size());
        for (Identifier id : src) {
            if (!StreamMode.isLocationEntry(id)) out.add(id);
        }
        ds$lastSource = src;
        ds$lastSize = src.size();
        ds$lastFiltered = out;
        cir.setReturnValue(out);
    }
}
