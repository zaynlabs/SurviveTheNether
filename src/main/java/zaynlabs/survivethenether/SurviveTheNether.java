package zaynlabs.survivethenether;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.fabricmc.api.ModInitializer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Main mod class for Survive the Nether.
 * Manages initialization, safe Crimson Forest spawn generation, and world-saved persistence.
 */
public final class SurviveTheNether implements ModInitializer {
	public static final String MOD_ID = "survive-the-nether";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	public static final int MIN_SPAWN_Y = 32;
	public static final int MAX_SPAWN_Y = 100;
	public static final int BIOME_SEARCH_RADIUS = 6400;
	public static final int COLUMN_SEARCH_RADIUS = 32;

	@Override
	public void onInitialize() {
		LOGGER.info("Survive the Nether initialized. Nether spawn routing active.");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}

	/**
	 * Dimension-saved data holding the persistent Nether world spawn coordinate.
	 */
	public static final class NetherSpawnData extends SavedData {
		public static final Codec<NetherSpawnData> CODEC = RecordCodecBuilder.create(
				i -> i.group(
						BlockPos.CODEC.optionalFieldOf("spawn_pos").forGetter(d -> Optional.ofNullable(d.spawnPos))
				).apply(i, opt -> new NetherSpawnData(opt.orElse(null)))
		);

		public static final SavedDataType<NetherSpawnData> TYPE = new SavedDataType<>(
				SurviveTheNether.id("nether_spawn"),
				NetherSpawnData::new,
				CODEC,
				null
		);

		private BlockPos spawnPos;

		public NetherSpawnData() {
			this(null);
		}

		public NetherSpawnData(BlockPos spawnPos) {
			this.spawnPos = spawnPos;
		}

		public BlockPos getSpawnPos() {
			return this.spawnPos;
		}

		public void setSpawnPos(BlockPos spawnPos) {
			this.spawnPos = spawnPos;
			this.setDirty();
		}
	}

	/**
	 * Retrieves the persistent Nether spawn point for the level, calculating and saving a new one if needed.
	 */
	public static BlockPos getOrFindNetherSpawn(ServerLevel netherLevel) {
		NetherSpawnData data = netherLevel.getDataStorage().computeIfAbsent(NetherSpawnData.TYPE);

		if (data.getSpawnPos() != null && isSafeSpawnPosition(netherLevel, data.getSpawnPos())) {
			return data.getSpawnPos();
		}

		BlockPos newSpawn = findSafeCrimsonSpawn(netherLevel).orElse(new BlockPos(0, 64, 0));
		data.setSpawnPos(newSpawn);
		return newSpawn;
	}

	/**
	 * Finds a safe spawn location within the nearest Crimson Forest in the Nether.
	 */
	public static Optional<BlockPos> findSafeCrimsonSpawn(ServerLevel netherLevel) {
		return findSafeCrimsonSpawn(netherLevel, BlockPos.ZERO);
	}

	/**
	 * Finds a safe spawn location within the nearest Crimson Forest starting from a given search origin.
	 */
	public static Optional<BlockPos> findSafeCrimsonSpawn(ServerLevel netherLevel, BlockPos searchOrigin) {
		LOGGER.info("Searching for Crimson Forest biome around origin {}...", searchOrigin);

		Pair<BlockPos, Holder<Biome>> biomeResult = netherLevel.findClosestBiome3d(
				biomeHolder -> biomeHolder.is(Biomes.CRIMSON_FOREST),
				searchOrigin,
				BIOME_SEARCH_RADIUS,
				32,
				64
		);

		if (biomeResult != null) {
			BlockPos biomeCenter = biomeResult.getFirst();
			LOGGER.info("Found Crimson Forest at [X={}, Y={}, Z={}]. Scanning for safe ground...",
					biomeCenter.getX(), biomeCenter.getY(), biomeCenter.getZ());

			Optional<BlockPos> safePos = searchAreaForSafeFloor(netherLevel, biomeCenter.getX(), biomeCenter.getZ(), COLUMN_SEARCH_RADIUS);
			if (safePos.isPresent()) {
				LOGGER.info("Located safe Crimson Forest spawn at {}", safePos.get());
				return safePos;
			}
			LOGGER.warn("Crimson Forest center at {} lacked safe surface; expanding search.", biomeCenter);
		} else {
			LOGGER.warn("No Crimson Forest found within radius {} from {}; using general spawn search.", BIOME_SEARCH_RADIUS, searchOrigin);
		}

		return searchAreaForSafeFloor(netherLevel, searchOrigin.getX(), searchOrigin.getZ(), 64);
	}

	/**
	 * Searches in an expanding spiral around (centerX, centerZ) for a safe column floor.
	 */
	public static Optional<BlockPos> searchAreaForSafeFloor(ServerLevel level, int centerX, int centerZ, int maxRadius) {
		for (int radius = 0; radius <= maxRadius; radius++) {
			for (int dx = -radius; dx <= radius; dx++) {
				for (int dz = -radius; dz <= radius; dz++) {
					if (Math.abs(dx) == radius || Math.abs(dz) == radius) {
						int targetX = centerX + dx;
						int targetZ = centerZ + dz;
						Optional<BlockPos> floor = findSafeFloorInColumn(level, targetX, targetZ);
						if (floor.isPresent()) {
							return floor;
						}
					}
				}
			}
		}
		return Optional.empty();
	}

	/**
	 * Scans a vertical column top-down (Y=100 to Y=32) for the highest safe standing position.
	 */
	public static Optional<BlockPos> findSafeFloorInColumn(ServerLevel level, int x, int z) {
		BlockPos.MutableBlockPos testPos = new BlockPos.MutableBlockPos();

		for (int y = MAX_SPAWN_Y; y >= MIN_SPAWN_Y; y--) {
			testPos.set(x, y, z);
			if (isSafeSpawnPosition(level, testPos)) {
				return Optional.of(testPos.immutable());
			}
		}

		return Optional.empty();
	}

	/**
	 * Validates whether the given BlockPos is safe for a player to spawn at (feet position).
	 */
	public static boolean isSafeSpawnPosition(ServerLevel level, BlockPos pos) {
		BlockPos floorPos = pos.below();
		BlockState floorState = level.getBlockState(floorPos);
		FluidState floorFluid = level.getFluidState(floorPos);

		// Floor must be sturdy, motion-blocking, and free of fluid
		if (!floorFluid.isEmpty() || !floorState.blocksMotion() || !floorState.isFaceSturdy(level, floorPos, Direction.UP)) {
			return false;
		}

		// Floor must not be a damaging block
		if (floorState.is(Blocks.MAGMA_BLOCK)
				|| floorState.is(Blocks.LAVA)
				|| floorState.is(Blocks.FIRE)
				|| floorState.is(Blocks.SOUL_FIRE)
				|| floorState.is(Blocks.CAMPFIRE)
				|| floorState.is(Blocks.SOUL_CAMPFIRE)
				|| floorState.is(Blocks.CACTUS)
				|| floorState.is(Blocks.SWEET_BERRY_BUSH)
				|| floorState.is(Blocks.WITHER_ROSE)
				|| floorState.is(Blocks.POWDER_SNOW)) {
			return false;
		}

		// Feet and Head position checks
		BlockPos headPos = pos.above();
		BlockState feetState = level.getBlockState(pos);
		BlockState headState = level.getBlockState(headPos);
		FluidState feetFluid = level.getFluidState(pos);
		FluidState headFluid = level.getFluidState(headPos);

		if (!feetFluid.isEmpty() || feetState.blocksMotion()) {
			return false;
		}
		if (feetState.is(Blocks.FIRE) || feetState.is(Blocks.SOUL_FIRE) || feetState.is(Blocks.LAVA)) {
			return false;
		}

		if (!headFluid.isEmpty() || headState.blocksMotion()) {
			return false;
		}
		if (headState.is(Blocks.FIRE) || headState.is(Blocks.SOUL_FIRE) || headState.is(Blocks.LAVA)) {
			return false;
		}

		// Check adjacent blocks for lava or fire hazards
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (dx == 0 && dz == 0) {
					continue;
				}

				for (int dy = -1; dy <= 1; dy++) {
					BlockPos adjacentPos = pos.offset(dx, dy, dz);
					BlockState adjacentState = level.getBlockState(adjacentPos);
					FluidState adjacentFluid = level.getFluidState(adjacentPos);

					if (adjacentFluid.is(FluidTags.LAVA)
							|| adjacentState.is(Blocks.LAVA)
							|| adjacentState.is(Blocks.FIRE)
							|| adjacentState.is(Blocks.SOUL_FIRE)) {
						return false;
					}
				}
			}
		}

		// Overhead ceiling checks (ensure no dripping/falling lava or gravel directly overhead)
		for (int dy = 2; dy <= 5; dy++) {
			BlockPos overheadPos = pos.above(dy);
			BlockState overheadState = level.getBlockState(overheadPos);
			FluidState overheadFluid = level.getFluidState(overheadPos);

			if (overheadFluid.is(FluidTags.LAVA)
					|| overheadState.is(Blocks.LAVA)
					|| overheadState.is(Blocks.GRAVEL)
					|| overheadState.is(Blocks.SUSPICIOUS_GRAVEL)) {
				return false;
			}
		}

		return true;
	}
}
