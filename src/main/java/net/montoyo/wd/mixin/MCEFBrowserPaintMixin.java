package net.montoyo.wd.mixin;

import net.montoyo.wd.client.mcef.HybridFrameCapture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.Rectangle;
import java.nio.ByteBuffer;

@Pseudo
@Mixin(targets = "com.cinemamod.mcef.MCEFBrowser", remap = false)
public abstract class MCEFBrowserPaintMixin {
    @Inject(method = "onPaint", at = @At("HEAD"), remap = false)
    private void webdisplays$captureHybridFrame(@Coerce Object browser, boolean popup, Rectangle[] dirtyRects,
                                                 ByteBuffer buffer, int width, int height, CallbackInfo ci) {
        HybridFrameCapture.onPaint(this, popup, dirtyRects, buffer, width, height);
    }
}
