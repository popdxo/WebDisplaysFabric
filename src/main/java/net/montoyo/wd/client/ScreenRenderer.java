package net.montoyo.wd.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import net.minecraft.world.level.block.state.BlockState;
import net.montoyo.wd.client.mcef.MCEFHelper;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.entity.ScreenData;
import net.montoyo.wd.utilities.data.BlockSide;
import net.montoyo.wd.utilities.data.Rotation;
import org.joml.Matrix4f;

public class ScreenRenderer implements BlockEntityRenderer<ScreenBlockEntity> {

    public ScreenRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(ScreenBlockEntity blockEntity, float partialTick, PoseStack poseStack,
                        MultiBufferSource bufferSource, int packedLight, int packedOverlay) {
        if (!MCEFHelper.isMCEFAvailable()) return;
        if (!ClientInit.isMCEFRenderingEnabled()) return;

        for (int i = 0; i < blockEntity.screenCount(); i++) {
            ScreenData screen = blockEntity.getScreen(i);
            if (screen == null) continue;

            int texId = screen.browser == null ? 0 : MCEFHelper.getBrowserTextureId(screen.browser);
            if (texId <= 0) continue;

            if (isBlockedByOpaque(blockEntity, screen)) continue;

            renderScreen(blockEntity, screen, texId, poseStack);
        }
    }

    private boolean isBlockedByOpaque(ScreenBlockEntity blockEntity, ScreenData screen) {
        BlockPos bp = blockEntity.getBlockPos();
        Direction dir = screen.side.direction;
        BlockPos adjacent = bp.relative(dir);
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return false;
        BlockState state = mc.level.getBlockState(adjacent);
        return state.isSolid();
    }

    private void renderScreen(ScreenBlockEntity blockEntity, ScreenData screen, int texId, PoseStack poseStack) {
        BlockSide side = screen.side;
        Rotation rot = screen.rotation;

        float w = screen.size.x;
        float h = screen.size.y;

        // "Cover" mode: zoom the browser content to fill the entire screen surface
        float screenAspect = w / h;
        float browserAspect = (float) screen.resolution.x / (float) screen.resolution.y;

        float uvU0 = 0f, uvV0 = 0f, uvU1 = 1f, uvV1 = 1f;

        if (screenAspect > browserAspect) {
            float ratio = screenAspect / browserAspect;
            float offset = (1.0f - 1.0f / ratio) / 2.0f;
            uvU0 = offset;
            uvU1 = 1.0f - offset;
        } else if (screenAspect < browserAspect) {
            float ratio = browserAspect / screenAspect;
            float offset = (1.0f - 1.0f / ratio) / 2.0f;
            uvV0 = offset;
            uvV1 = 1.0f - offset;
        }

        float u0 = uvU0, v0 = uvV0, u1 = uvU1, v1 = uvV1;
        switch (rot) {
            case ROT_90 -> { u0 = uvU1; v0 = uvV0; u1 = uvU0; v1 = uvV1; }
            case ROT_180 -> { u0 = uvU1; v0 = uvV1; u1 = uvU0; v1 = uvV0; }
            case ROT_270 -> { u0 = uvU0; v0 = uvV1; u1 = uvU1; v1 = uvV0; }
        }

        float eps = 0.001f;

        poseStack.pushPose();

        switch (side) {
            case BOTTOM -> poseStack.translate(0, -eps, 0);
            case TOP -> poseStack.translate(0, 1.0 + eps, 0);
            case NORTH -> poseStack.translate(0, 0, -eps);
            case SOUTH -> poseStack.translate(0, 0, 1.0 + eps);
            case WEST -> poseStack.translate(-eps, 0, 0);
            case EAST -> poseStack.translate(1.0 + eps, 0, 0);
        }

        Matrix4f matrix = poseStack.last().pose();

        // Match Forge exactly: use POSITION_TEX_COLOR format with explicit white color per vertex
        RenderSystem.enableDepthTest();
        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, texId);
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);

        BufferBuilder builder = Tesselator.getInstance().getBuilder();
        builder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);

        switch (side) {
            case NORTH -> {
                builder.vertex(matrix, 0, 0, 0).uv(u1, v1).color(255, 255, 255, 255).endVertex();
                builder.vertex(matrix, 0, h, 0).uv(u1, v0).color(255, 255, 255, 255).endVertex();
                builder.vertex(matrix, w, h, 0).uv(u0, v0).color(255, 255, 255, 255).endVertex();
                builder.vertex(matrix, w, 0, 0).uv(u0, v1).color(255, 255, 255, 255).endVertex();
            }
            case SOUTH -> {
                builder.vertex(matrix, w, 0, 0).uv(u1, v1).color(255, 255, 255, 255).endVertex();
                builder.vertex(matrix, w, h, 0).uv(u1, v0).color(255, 255, 255, 255).endVertex();
                builder.vertex(matrix, 0, h, 0).uv(u0, v0).color(255, 255, 255, 255).endVertex();
                builder.vertex(matrix, 0, 0, 0).uv(u0, v1).color(255, 255, 255, 255).endVertex();
            }
            case WEST -> {
                builder.vertex(matrix, 0, 0, 0).uv(u0, v1).color(255, 255, 255, 255).endVertex();
                builder.vertex(matrix, 0, 0, w).uv(u1, v1).color(255, 255, 255, 255).endVertex();
                builder.vertex(matrix, 0, h, w).uv(u1, v0).color(255, 255, 255, 255).endVertex();
                builder.vertex(matrix, 0, h, 0).uv(u0, v0).color(255, 255, 255, 255).endVertex();
            }
            case EAST -> {
                builder.vertex(matrix, 0, 0, w).uv(u0, v1).color(255, 255, 255, 255).endVertex();
                builder.vertex(matrix, 0, 0, 0).uv(u1, v1).color(255, 255, 255, 255).endVertex();
                builder.vertex(matrix, 0, h, 0).uv(u1, v0).color(255, 255, 255, 255).endVertex();
                builder.vertex(matrix, 0, h, w).uv(u0, v0).color(255, 255, 255, 255).endVertex();
            }
            case BOTTOM -> {
                builder.vertex(matrix, 0, 0, 0).uv(u0, v1).color(255, 255, 255, 255).endVertex();
                builder.vertex(matrix, w, 0, 0).uv(u1, v1).color(255, 255, 255, 255).endVertex();
                builder.vertex(matrix, w, 0, h).uv(u1, v0).color(255, 255, 255, 255).endVertex();
                builder.vertex(matrix, 0, 0, h).uv(u0, v0).color(255, 255, 255, 255).endVertex();
            }
            case TOP -> {
                // Same UV per position as BOTTOM, but wound the other way so the face is front-facing from above.
                builder.vertex(matrix, 0, 0, 0).uv(u0, v1).color(255, 255, 255, 255).endVertex();
                builder.vertex(matrix, 0, 0, h).uv(u0, v0).color(255, 255, 255, 255).endVertex();
                builder.vertex(matrix, w, 0, h).uv(u1, v0).color(255, 255, 255, 255).endVertex();
                builder.vertex(matrix, w, 0, 0).uv(u1, v1).color(255, 255, 255, 255).endVertex();
            }
        }

        BufferUploader.drawWithShader(builder.end());
        RenderSystem.disableDepthTest();

        // Restore blend state after screen rendering
        RenderSystem.enableBlend();

        // --- Render cursor overlay ---
        ScreenCursorTracker.CursorInfo cursor = ScreenCursorTracker.getCurrentCursor();
        if (ScreenCursorTracker.isCursorVisible() && cursor != null && cursor.pos.equals(blockEntity.getBlockPos()) && cursor.side == side) {
            float lx = (float) cursor.localX;
            float ly = (float) cursor.localY;
            float lz = (float) cursor.localZ;
            switch (side) {
                case SOUTH -> lz -= 1.0f;
                case EAST -> lx -= 1.0f;
                case TOP -> ly -= 1.0f;
            }
            drawArrowCursor(matrix, side, lx, ly, lz);
        }

        // Hybrid viewers: draw the owner's cursor (synced through the server) on the stream.
        if (screen.hybridMode && screen.remoteCursorVisible
                && System.currentTimeMillis() - screen.remoteCursorAt < 1500
                && !MCEFHelper.isLocalPlayerOwner(screen.owner, screen.ownerUuid)) {
            float lx = screen.remoteCursorX;
            float ly = screen.remoteCursorY;
            float lz = screen.remoteCursorZ;
            switch (side) {
                case SOUTH -> lz -= 1.0f;
                case EAST -> lx -= 1.0f;
                case TOP -> ly -= 1.0f;
            }
            drawArrowCursor(matrix, side, lx, ly, lz);
        }

        poseStack.popPose();
    }

    /** Classic arrow pointer, tip at the hit point; grid units (x right, y down), tip at (0,0). */
    private static final float[][] ARROW_TRIANGLES = {
            {0, 0, 0, 16, 4, 12},
            {0, 0, 4, 12, 6, 11},
            {0, 0, 6, 11, 11, 11},
            {4, 12, 7, 19, 6, 11},
            {7, 19, 9, 18, 6, 11},
    };
    private static final float CURSOR_UNIT = 0.0048f; // blocks per grid unit (arrow ~19 units tall)
    private static final float[][] OUTLINE_OFFSETS = {
            {-1.3f, 0}, {1.3f, 0}, {0, -1.3f}, {0, 1.3f}, {-1f, -1f}, {1f, -1f}, {-1f, 1f}, {1f, 1f}};

    /** Surface axes (screen-right, screen-up) matching how the texture is laid out for each side. */
    private static float[][] surfaceAxes(BlockSide side) {
        return switch (side) {
            case NORTH -> new float[][]{{-1, 0, 0}, {0, 1, 0}};
            case SOUTH -> new float[][]{{1, 0, 0}, {0, 1, 0}};
            case WEST -> new float[][]{{0, 0, 1}, {0, 1, 0}};
            case EAST -> new float[][]{{0, 0, -1}, {0, 1, 0}};
            default -> new float[][]{{1, 0, 0}, {0, 0, 1}}; // TOP / BOTTOM
        };
    }

    private static void drawArrowCursor(Matrix4f matrix, BlockSide side, float lx, float ly, float lz) {
        float[][] axes = surfaceAxes(side);
        float[] r = axes[0], u = axes[1];
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.setShaderTexture(0, 0);
        RenderSystem.disableCull();
        BufferBuilder cb = Tesselator.getInstance().getBuilder();
        cb.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (float[] offset : OUTLINE_OFFSETS) {
            addArrow(cb, matrix, lx, ly, lz, r, u, offset[0], offset[1], 0, 0, 0);
        }
        addArrow(cb, matrix, lx, ly, lz, r, u, 0, 0, 255, 255, 255);
        BufferUploader.drawWithShader(cb.end());
        RenderSystem.enableCull();
    }

    private static void addArrow(BufferBuilder cb, Matrix4f matrix, float lx, float ly, float lz,
                                 float[] r, float[] u, float offX, float offY, int red, int green, int blue) {
        for (float[] t : ARROW_TRIANGLES) {
            // A triangle is emitted as a quad with a repeated vertex.
            arrowVertex(cb, matrix, lx, ly, lz, r, u, t[0] + offX, t[1] + offY, red, green, blue);
            arrowVertex(cb, matrix, lx, ly, lz, r, u, t[2] + offX, t[3] + offY, red, green, blue);
            arrowVertex(cb, matrix, lx, ly, lz, r, u, t[4] + offX, t[5] + offY, red, green, blue);
            arrowVertex(cb, matrix, lx, ly, lz, r, u, t[4] + offX, t[5] + offY, red, green, blue);
        }
    }

    private static void arrowVertex(BufferBuilder cb, Matrix4f matrix, float lx, float ly, float lz,
                                    float[] r, float[] u, float gx, float gy, int red, int green, int blue) {
        float right = gx * CURSOR_UNIT;
        float up = -gy * CURSOR_UNIT;
        cb.vertex(matrix, lx + r[0] * right + u[0] * up, ly + r[1] * right + u[1] * up, lz + r[2] * right + u[2] * up)
                .color(red, green, blue, 255).endVertex();
    }

    @Override
    public int getViewDistance() {
        return 32;
    }

    @Override
    public boolean shouldRenderOffScreen(ScreenBlockEntity blockEntity) {
        return true;
    }
}