package de.voids.chestesp;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

public final class ChestEspClient implements ClientModInitializer {
    private static final int SEARCH_RADIUS = 48;
    private static final float EDGE = 0.035f;
    private static final RenderPipeline THROUGH_WALLS = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("chestesp", "pipeline/chest_outline_through_walls"))
                    .withDepthStencilState(Optional.empty())
                    .build()
    );
    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
    private static final StagedVertexBuffer BUFFER = new StagedVertexBuffer(() -> "ChestESP buffer", RenderType.SMALL_BUFFER_SIZE);
    private static final KeyMapping TOGGLE_KEY = KeyMappingHelper.registerKeyMapping(
            new KeyMapping("key.chestesp.toggle", GLFW.GLFW_KEY_K, "category.chestesp")
    );
    private volatile List<Box> boxes = List.of();
    private boolean enabled = true;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (TOGGLE_KEY.consumeClick()) enabled = !enabled;
        });
        LevelExtractionEvents.END_EXTRACTION.register(this::extract);
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(this::render);
    }

    private void extract(LevelExtractionContext context) {
        if (!enabled || context.level() == null) {
            boxes = List.of();
            return;
        }
        BlockPos center = context.camera().blockPosition();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        List<Box> found = new ArrayList<>();
        int minY = Math.max(context.level().getMinY(), center.getY() - SEARCH_RADIUS);
        int maxY = Math.min(context.level().getMaxY(), center.getY() + SEARCH_RADIUS);
        for (int x = center.getX() - SEARCH_RADIUS; x <= center.getX() + SEARCH_RADIUS; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = center.getZ() - SEARCH_RADIUS; z <= center.getZ() + SEARCH_RADIUS; z++) {
                    cursor.set(x, y, z);
                    BlockEntity be = context.level().getBlockEntity(cursor);
                    if (be instanceof ChestBlockEntity || be instanceof EnderChestBlockEntity) {
                        found.add(new Box(x, y, z));
                    }
                }
            }
        }
        boxes = List.copyOf(found);
    }

    private void render(LevelRenderContext context) {
        List<Box> current = boxes;
        if (!enabled || current.isEmpty()) return;
        RenderPipeline pipeline = THROUGH_WALLS;
        VertexFormat format = pipeline.getVertexFormatBinding(0);
        PrimitiveTopology topology = pipeline.getPrimitiveTopology();
        StagedVertexBuffer.Draw draw = BUFFER.appendDraw(format, topology, null);
        PoseStack pose = context.poseStack();
        Vec3 camera = context.levelState().cameraRenderState.pos;
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        VertexConsumer out = BUFFER.getVertexBuilder(draw);
        for (Box b : current) addOutline(out, pose.last().pose(), b.x, b.y, b.z);
        pose.popPose();
        BUFFER.upload();
        StagedVertexBuffer.ExecuteInfo info = BUFFER.getExecuteInfo(draw);
        if (info != null) draw(Minecraft.getInstance(), info, pipeline);
        BUFFER.endFrame();
    }

    private static void addOutline(VertexConsumer out, Matrix4fc matrix, float x, float y, float z) {
        float x0=x+0.015f, y0=y+0.015f, z0=z+0.015f;
        float x1=x+0.985f, y1=y+0.985f, z1=z+0.985f;
        bar(out,matrix,x0,y0,z0,x0+EDGE,y1,z0+EDGE);
        bar(out,matrix,x1-EDGE,y0,z0,x1,y1,z0+EDGE);
        bar(out,matrix,x0,y0,z1-EDGE,x0+EDGE,y1,z1);
        bar(out,matrix,x1-EDGE,y0,z1-EDGE,x1,y1,z1);
        bar(out,matrix,x0,y1-EDGE,z0,x1,y1,z0+EDGE);
        bar(out,matrix,x0,y1-EDGE,z1-EDGE,x1,y1,z1);
        bar(out,matrix,x0,y1-EDGE,z0,x0+EDGE,y1,z1);
        bar(out,matrix,x1-EDGE,y1-EDGE,z0,x1,y1,z1);
        bar(out,matrix,x0,y0,z0,x1,y0+EDGE,z0+EDGE);
        bar(out,matrix,x0,y0,z1-EDGE,x1,y0+EDGE,z1);
        bar(out,matrix,x0,y0,z0,x0+EDGE,y0+EDGE,z1);
        bar(out,matrix,x1-EDGE,y0,z0,x1,y0+EDGE,z1);
    }

    private static void bar(VertexConsumer out, Matrix4fc m, float x0,float y0,float z0,float x1,float y1,float z1) {
        face(out,m,x0,y0,z1, x1,y0,z1, x1,y1,z1, x0,y1,z1);
        face(out,m,x1,y0,z0, x0,y0,z0, x0,y1,z0, x1,y1,z0);
        face(out,m,x0,y0,z0, x0,y0,z1, x0,y1,z1, x0,y1,z0);
        face(out,m,x1,y0,z1, x1,y0,z0, x1,y1,z0, x1,y1,z1);
        face(out,m,x0,y1,z1, x1,y1,z1, x1,y1,z0, x0,y1,z0);
        face(out,m,x0,y0,z0, x1,y0,z0, x1,y0,z1, x0,y0,z1);
    }

    private static void face(VertexConsumer out, Matrix4fc m,
                             float ax,float ay,float az, float bx,float by,float bz,
                             float cx,float cy,float cz, float dx,float dy,float dz) {
        vertex(out,m,ax,ay,az); vertex(out,m,bx,by,bz); vertex(out,m,cx,cy,cz); vertex(out,m,dx,dy,dz);
    }
    private static void vertex(VertexConsumer out, Matrix4fc m, float x,float y,float z) {
        out.addVertex(m,x,y,z).setColor(1f,1f,1f,1f);
    }

    private static void draw(Minecraft client, StagedVertexBuffer.ExecuteInfo info, RenderPipeline pipeline) {
        GpuBufferSlice transforms = RenderSystem.getDynamicUniforms().writeTransform(
                RenderSystem.getModelViewMatrixCopy(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);
        RenderTarget target = client.gameRenderer.mainRenderTarget();
        GpuTextureView color = target.getColorTextureView();
        if (color == null) return;
        try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                () -> "ChestESP", color, Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", transforms);
            pass.setVertexBuffer(0, info.vertexBuffer().slice());
            pass.setIndexBuffer(info.indexBuffer(), info.indexType());
            pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
        }
    }
    private record Box(int x, int y, int z) { }
}