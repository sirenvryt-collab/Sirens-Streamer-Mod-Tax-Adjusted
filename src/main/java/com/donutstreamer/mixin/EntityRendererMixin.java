package com.donutstreamer.mixin;

import com.donutstreamer.DonutStreamerClient;
import com.donutstreamer.Session;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Own nametag in F5: some servers put the rank only in the tab-list name rather than in a scoreboard team, so the
 * default tag for your own player shows just the bare name. When your tab-list name carries more than the default
 * tag does (a rank prefix or suffix), that fuller name is used for your own tag. Other players are never touched.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
    @Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
    private void ds$ownRank(Entity entity, CallbackInfoReturnable<Text> cir) {
        Session s = DonutStreamerClient.session;
        if (s == null || !s.selfNametag || !s.selfNametagRank) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || entity != mc.player) return;
        ClientPlayNetworkHandler handler = mc.getNetworkHandler();
        if (handler == null) return;
        PlayerListEntry entry = handler.getPlayerListEntry(mc.player.getUuid());
        if (entry == null) return;
        Text tab = entry.getDisplayName();
        Text current = cir.getReturnValue();
        if (tab == null || current == null) return;
        if (tab.getString().length() > current.getString().length()) cir.setReturnValue(tab);
    }
}
