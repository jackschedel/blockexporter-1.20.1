package com.github.kazuofficial.blockexporter;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.registries.ForgeRegistries;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

public class ItemExportRenderer implements AutoCloseable {
    private final int textureSize;
    private final Path exportDirectory;
    private final Minecraft client;
    private final TextureTarget framebuffer;
    private final ExecutorService fileWriteExecutor;
    private final Semaphore fileWriteSemaphore;
    private final ConcurrentLinkedQueue<ItemStack> failedExports;
    private final ConcurrentLinkedQueue<Fluid> failedFluidExports;

    public ItemExportRenderer(int textureSize) {
        this.textureSize = textureSize;
        this.client = Minecraft.getInstance();
        this.exportDirectory = client.gameDirectory.toPath().resolve("item_exports");
        int coreCount = Runtime.getRuntime().availableProcessors();
        this.fileWriteExecutor = Executors.newFixedThreadPool(Math.max(2, coreCount / 2));
        this.fileWriteSemaphore = new Semaphore(coreCount);
        this.failedExports = new ConcurrentLinkedQueue<>();
        this.failedFluidExports = new ConcurrentLinkedQueue<>();

        try {
            Files.createDirectories(exportDirectory);
            BlockExporter.LOGGER.info("Created export directory: {}", exportDirectory.toAbsolutePath());
        } catch (IOException e) {
            BlockExporter.LOGGER.error("Failed to create export directory: {}", exportDirectory.toAbsolutePath(), e);
            throw new RuntimeException("Failed to create export directory", e);
        }

        this.framebuffer = new TextureTarget(this.textureSize, this.textureSize, true, Minecraft.ON_OSX);
    }

    public void exportItemsBatch(List<ItemStack> stacks, AtomicInteger completionCounter) {
        if (stacks == null || stacks.isEmpty()) {
            return;
        }

        Matrix4f ortho = new Matrix4f().setOrtho(0.0F, this.textureSize, 0.0F, this.textureSize, -1000.0F, 1000.0F);
        RenderSystem.setProjectionMatrix(ortho, VertexSorting.ORTHOGRAPHIC_Z);

        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            exportSingleItem(stack, completionCounter);
        }
    }

    private void exportSingleItem(ItemStack stack, AtomicInteger completionCounter) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());

        try {
            this.framebuffer.bindWrite(true);
            RenderSystem.clearColor(0.0F, 0.0F, 0.0F, 0.0F);
            RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);

            net.minecraft.client.renderer.entity.ItemRenderer itemRenderer = client.getItemRenderer();
            BakedModel model = itemRenderer.getModel(stack, client.level, null, 0);

            if (model.usesBlockLight()) {
                Lighting.setupFor3DItems();
            } else {
                Lighting.setupForFlatItems();
            }

            PoseStack poseStack = new PoseStack();
            poseStack.pushPose();
            poseStack.translate(this.textureSize / 2.0, this.textureSize / 2.0, 100.0);
            poseStack.scale(this.textureSize, this.textureSize, this.textureSize);

            MultiBufferSource.BufferSource bufferSource = client.renderBuffers().bufferSource();
            itemRenderer.render(stack, ItemDisplayContext.GUI, false, poseStack, bufferSource,
                15728880, OverlayTexture.NO_OVERLAY, model);
            bufferSource.endBatch();

            poseStack.popPose();

            this.framebuffer.unbindWrite();

            Path filePath = exportDirectory.resolve(id.getNamespace() + "_" + id.getPath() + ".png");
            final ItemStack failedStack = stack;
            captureAndSaveAsync(filePath, id, () -> this.failedExports.add(failedStack), completionCounter);

        } catch (Exception e) {
            BlockExporter.LOGGER.error("Failed to export item: {}", id, e);
            this.failedExports.add(stack);
            completionCounter.incrementAndGet();
            this.framebuffer.unbindWrite();
        }
    }

    public void exportFluidsBatch(List<Fluid> fluids, AtomicInteger completionCounter) {
        if (fluids == null || fluids.isEmpty()) {
            return;
        }

        Matrix4f ortho = new Matrix4f().setOrtho(0.0F, this.textureSize, 0.0F, this.textureSize, -1000.0F, 1000.0F);
        RenderSystem.setProjectionMatrix(ortho, VertexSorting.ORTHOGRAPHIC_Z);

        for (Fluid fluid : fluids) {
            if (fluid == null) {
                continue;
            }
            exportSingleFluid(fluid, completionCounter);
        }
    }

    private void exportSingleFluid(Fluid fluid, AtomicInteger completionCounter) {
        ResourceLocation id = ForgeRegistries.FLUIDS.getKey(fluid);

        try {
            IClientFluidTypeExtensions fluidExtensions = IClientFluidTypeExtensions.of(fluid);
            ResourceLocation stillTexture = fluidExtensions.getStillTexture();
            TextureAtlasSprite sprite = client.getTextureAtlas(InventoryMenu.BLOCK_ATLAS).apply(stillTexture);
            int tintColor = fluidExtensions.getTintColor();

            this.framebuffer.bindWrite(true);
            RenderSystem.clearColor(0.0F, 0.0F, 0.0F, 0.0F);
            RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, Minecraft.ON_OSX);

            drawFluidSprite(sprite, tintColor);

            this.framebuffer.unbindWrite();

            Path filePath = exportDirectory.resolve("fluids").resolve(id.getNamespace() + "_" + id.getPath() + ".png");
            final Fluid failedFluid = fluid;
            captureAndSaveAsync(filePath, id, () -> this.failedFluidExports.add(failedFluid), completionCounter);

        } catch (Exception e) {
            BlockExporter.LOGGER.error("Failed to export fluid: {}", id, e);
            this.failedFluidExports.add(fluid);
            completionCounter.incrementAndGet();
            this.framebuffer.unbindWrite();
        }
    }

    private void drawFluidSprite(TextureAtlasSprite sprite, int tintColor) {
        int a = (tintColor >> 24) & 0xFF;
        if (a == 0) {
            a = 0xFF;
        }
        int r = (tintColor >> 16) & 0xFF;
        int g = (tintColor >> 8) & 0xFF;
        int b = tintColor & 0xFF;

        RenderSystem.setShader(GameRenderer::getPositionTexColorShader);
        RenderSystem.setShaderTexture(0, sprite.atlasLocation());
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();

        RenderSystem.getModelViewStack().pushPose();
        RenderSystem.getModelViewStack().setIdentity();
        RenderSystem.applyModelViewMatrix();

        float u0 = sprite.getU0();
        float u1 = sprite.getU1();
        float v0 = sprite.getV0();
        float v1 = sprite.getV1();

        Tesselator tesselator = Tesselator.getInstance();
        BufferBuilder buffer = tesselator.getBuilder();
        buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        buffer.vertex(0.0, 0.0, 0.0).uv(u0, v1).color(r, g, b, a).endVertex();
        buffer.vertex(this.textureSize, 0.0, 0.0).uv(u1, v1).color(r, g, b, a).endVertex();
        buffer.vertex(this.textureSize, this.textureSize, 0.0).uv(u1, v0).color(r, g, b, a).endVertex();
        buffer.vertex(0.0, this.textureSize, 0.0).uv(u0, v0).color(r, g, b, a).endVertex();
        tesselator.end();

        RenderSystem.getModelViewStack().popPose();
        RenderSystem.applyModelViewMatrix();
    }

    private void captureAndSaveAsync(Path filePath, ResourceLocation id, Runnable onFailure, AtomicInteger completionCounter) {
        NativeImage image = new NativeImage(this.textureSize, this.textureSize, false);
        RenderSystem.bindTexture(this.framebuffer.getColorTextureId());
        image.downloadTexture(0, false);
        image.flipY();

        CompletableFuture.runAsync(() -> {
            try {
                this.fileWriteSemaphore.acquire();
                Files.createDirectories(filePath.getParent());
                image.writeToFile(filePath);
                BlockExporter.LOGGER.debug("Exported: {}", filePath.getFileName());
            } catch (IOException e) {
                BlockExporter.LOGGER.error("Failed to save exported image: {}", id, e);
                onFailure.run();
            } catch (InterruptedException e) {
                BlockExporter.LOGGER.error("Screenshot thread interrupted for: {}", id, e);
                onFailure.run();
                Thread.currentThread().interrupt();
            } finally {
                image.close();
                this.fileWriteSemaphore.release();
                completionCounter.incrementAndGet();
            }
        }, fileWriteExecutor);
    }

    public List<ItemStack> getFailedExports() {
        return List.copyOf(this.failedExports);
    }

    public List<Fluid> getFailedFluidExports() {
        return List.copyOf(this.failedFluidExports);
    }

    @Override
    public void close() {
        this.fileWriteExecutor.shutdown();
        this.framebuffer.destroyBuffers();
    }
}
