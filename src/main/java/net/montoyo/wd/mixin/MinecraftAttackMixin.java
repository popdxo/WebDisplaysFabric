package net.montoyo.wd.mixin;

import net.minecraft.client.Minecraft;
import net.montoyo.wd.client.ScreenCursorTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Never break (or start breaking) a block that sits behind or under a display surface, whatever the player holds.
 * Fabric's AttackBlockCallback only covers the first hit; holding the button runs continueAttack, which kept
 * mining through the screen.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftAttackMixin {
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void webdisplays$noAttackThroughDisplay(CallbackInfoReturnable<Boolean> cir) {
        if (ScreenCursorTracker.displayBlocksAttack((Minecraft) (Object) this)) cir.setReturnValue(false);
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void webdisplays$noMiningThroughDisplay(boolean leftClick, CallbackInfo ci) {
        Minecraft minecraft = (Minecraft) (Object) this;
        if (ScreenCursorTracker.displayBlocksAttack(minecraft)) {
            if (minecraft.gameMode != null) minecraft.gameMode.stopDestroyBlock();
            ci.cancel();
        }
    }
}
