package dev.mercenaries.client.render;

import java.util.function.Consumer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import org.joml.Vector3f;
import org.joml.Vector3fc;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.special.NoDataSpecialModelRenderer;
import net.minecraft.client.renderer.special.SpecialModelRenderer;

/**
 * Item model type {@code mercenaries:merc_mesh}: draws an extracted TF2 mesh for weapons and
 * projectiles. Only referenced by the generated resource pack, so without the pack items use flat icons.
 *
 * <pre>{"type": "minecraft:special", "base": "...", "model": {"type": "mercenaries:merc_mesh", "mesh": "weapon/minigun", "skin": 1}}</pre>
 */
public final class MeshItemRenderer implements NoDataSpecialModelRenderer {
	private final String mesh;
	private final int skin;

	private MeshItemRenderer(String mesh, int skin) {
		this.mesh = mesh;
		this.skin = skin;
	}

	@Override
	public void submit(PoseStack poseStack, SubmitNodeCollector collector, int light, int overlay, boolean foil, int outlineColor) {
		MercMesh m = MercMeshes.INSTANCE.get(mesh);
		if (m == null) return;
		m.submitAll(poseStack, collector, skin, light, overlay, 0xFFFFFFFF);
	}

	@Override
	public void getExtents(Consumer<Vector3fc> output) {
		output.accept(new Vector3f(0, 0, 0));
		output.accept(new Vector3f(1, 1, 1));
	}

	public record Unbaked(String mesh, int skin) implements NoDataSpecialModelRenderer.Unbaked {
		public static final MapCodec<Unbaked> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
				Codec.STRING.fieldOf("mesh").forGetter(Unbaked::mesh),
				Codec.INT.optionalFieldOf("skin", 0).forGetter(Unbaked::skin)
		).apply(i, Unbaked::new));

		@Override
		public MapCodec<Unbaked> type() {
			return MAP_CODEC;
		}

		@Override
		public MeshItemRenderer bake(SpecialModelRenderer.BakingContext context) {
			return new MeshItemRenderer(mesh, skin);
		}
	}
}
