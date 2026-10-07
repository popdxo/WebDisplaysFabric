package net.montoyo.wd.mixin;

import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.awt.Rectangle;
import java.nio.ByteBuffer;

/**
 * Targets MCEFBrowser.onPaint to reset GL pixel store state immediately after
 * MCEF finishes all texture updates.
 *
 * The actual pixel store corruption happens in MCEFBrowser.onPaint() (not MCEFRenderer),
 * where it sets GL_UNPACK_ROW_LENGTH, GL_UNPACK_SKIP_PIXELS, GL_UNPACK_SKIP_ROWS
 * for partial dirty-rect updates but NEVER resets them afterward. When Minecraft
 * subsequently loads its own textures, these non-zero values corrupt the data,
 * causing visible noise/artifacts on distant blocks and grass.
 */
@Pseudo
@Mixin(targets = "com.cinemamod.mcef.MCEFBrowser", remap = false)
public class PixelStoreResetMixin {

    /**
     * Inject at RETURN of MCEFBrowser.onPaint to reset pixel store state.
     * Method descriptor: void onPaint(CefBrowser, boolean, Rectangle[], ByteBuffer, int, int)
     */
    @Inject(method = "onPaint", at = @At("RETURN"))
    private void webdisplays$afterOnPaint(CallbackInfo ci) {
        GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
    }
}