package zaynlabs.survivethenether.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.config.PrepareSpawnTask;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import zaynlabs.survivethenether.SurviveTheNether;

import java.util.Optional;

/**
 * Intercepts player spawn preparation during connection configuration.
 * If a player does not have existing save data (i.e. first time joining),
 * sets their initial spawn dimension to the Nether and calculates a safe Crimson Forest position.
 */
@Mixin(PrepareSpawnTask.class)
public abstract class PrepareSpawnTaskMixin {
	@Shadow
	@Final
	private MinecraftServer server;

	@Redirect(
			method = "start",
			at = @At(
					value = "INVOKE",
					target = "Ljava/util/Optional;orElse(Ljava/lang/Object;)Ljava/lang/Object;",
					ordinal = 0
			)
	)
	private Object surviveTheNether$overrideInitialSpawn(Optional<?> instance, Object fallback) {
		if (instance.isEmpty()) {
			ServerLevel netherLevel = this.server.getLevel(Level.NETHER);
			if (netherLevel != null) {
				BlockPos spawnPos = SurviveTheNether.getOrFindNetherSpawn(netherLevel);
				Vec3 spawnVec = Vec3.atBottomCenterOf(spawnPos);

				return new ServerPlayer.SavedPosition(
						Optional.of(Level.NETHER),
						Optional.of(spawnVec),
						Optional.of(new Vec2(0.0F, 0.0F))
				);
			}
		}

		return instance.isPresent() ? instance.get() : fallback;
	}
}
