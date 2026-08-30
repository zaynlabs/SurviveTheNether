package zaynlabs.survivethenether.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayer.RespawnConfig;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.jspecify.annotations.Nullable;
import zaynlabs.survivethenether.SurviveTheNether;

import java.util.Set;

/**
 * Intercepts player respawn calculations.
 * When a player dies without a valid, active respawn block (such as an anchor or bed),
 * prevents them from falling back to the Overworld and instead routes them to the Nether world spawn.
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerRespawnMixin {
	@Shadow
	@Final
	protected MinecraftServer server;

	@Shadow
	public abstract @Nullable RespawnConfig getRespawnConfig();

	@Inject(
			method = "findRespawnPositionAndUseSpawnBlock",
			at = @At("RETURN"),
			cancellable = true
	)
	private void surviveTheNether$fallbackToNetherSpawn(
			boolean consumeSpawnBlock,
			TeleportTransition.PostTeleportTransition postTeleportTransition,
			CallbackInfoReturnable<TeleportTransition> cir
	) {
		TeleportTransition original = cir.getReturnValue();
		boolean hasValidCustomSpawn = this.getRespawnConfig() != null && !original.missingRespawnBlock();

		// If the player does not have a valid custom respawn block (no anchor, or anchor broken/depleted)
		if (!hasValidCustomSpawn) {
			ServerLevel netherLevel = this.server.getLevel(Level.NETHER);
			if (netherLevel != null) {
				BlockPos spawnPos = SurviveTheNether.getOrFindNetherSpawn(netherLevel);
				Vec3 spawnVec = Vec3.atBottomCenterOf(spawnPos);

				cir.setReturnValue(new TeleportTransition(
						netherLevel,
						spawnVec,
						Vec3.ZERO,
						original.yRot(),
						original.xRot(),
						original.missingRespawnBlock(),
						false,
						Set.of(),
						postTeleportTransition
				));
			}
		}
	}
}
