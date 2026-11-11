package com.donutstreamer.mixin;

import com.donutstreamer.DonutStreamerClient;
import com.donutstreamer.Session;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Lets the game draw your own nametag in third person (F5), through the exact same path other players' tags use,
 * so team prefixes and colours (ranks, Media, etc.) show up the way other people see them. First person is untouched.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {
    @Inject(method = "hasLabel(Lnet/minecraft/entity/LivingEntity;D)Z", at = @At("HEAD"), cancellable = true)
    private void ds$showOwnNametag(LivingEntity entity, double squaredDistance, CallbackInfoReturnable<Boolean> cir) {
        Session s = DonutStreamerClient.session;
        if (s == null || !s.selfNametag) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (entity != mc.player || mc.getCameraEntity() != entity) return;
        if (mc.options.getPerspective() == Perspective.FIRST_PERSON) return;
        if (!MinecraftClient.isHudEnabled() || entity.hasPassengers()) return;
        cir.setReturnValue(true);
    }
}
