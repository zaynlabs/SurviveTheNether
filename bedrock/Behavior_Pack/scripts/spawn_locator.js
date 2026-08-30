import { world } from "@minecraft/server";

const NETHER_SPAWN_PROP = "survive:nether_spawn_coords";
const MIN_SEARCH_Y = 32;
const MAX_SEARCH_Y = 100;
const SEARCH_RADIUS = 48;
const STEP_SIZE = 4;

const HAZARDOUS_BLOCKS = new Set([
    "minecraft:lava",
    "minecraft:flowing_lava",
    "minecraft:fire",
    "minecraft:soul_fire",
    "minecraft:magma",
    "minecraft:campfire",
    "minecraft:soul_campfire",
    "minecraft:wither_rose",
    "minecraft:powder_snow",
    "minecraft:cactus"
]);

/**
 * Retrieves the cached Nether world spawn or calculates a safe position.
 *
 * @param {import("@minecraft/server").Dimension} netherDimension
 * @returns {{ x: number, y: number, z: number }}
 */
export function getOrFindSafeNetherSpawn(netherDimension) {
    const saved = world.getDynamicProperty(NETHER_SPAWN_PROP);
    if (saved && typeof saved === "string") {
        try {
            const parsed = JSON.parse(saved);
            if (
                parsed &&
                typeof parsed.x === "number" &&
                typeof parsed.y === "number" &&
                typeof parsed.z === "number"
            ) {
                return parsed;
            }
        } catch (e) {
            // Recalculate on parse failure
        }
    }

    const calculatedSpawn = findSafeNetherLocation(netherDimension, 0, 0);
    world.setDynamicProperty(NETHER_SPAWN_PROP, JSON.stringify(calculatedSpawn));
    return calculatedSpawn;
}

/**
 * Searches in a spiral around (centerX, centerZ) for a safe Nether floor.
 *
 * @param {import("@minecraft/server").Dimension} dimension
 * @param {number} centerX
 * @param {number} centerZ
 * @returns {{ x: number, y: number, z: number }}
 */
function findSafeNetherLocation(dimension, centerX, centerZ) {
    for (let r = 0; r <= SEARCH_RADIUS; r += STEP_SIZE) {
        for (let dx = -r; dx <= r; dx += STEP_SIZE) {
            for (let dz = -r; dz <= r; dz += STEP_SIZE) {
                const targetX = centerX + dx;
                const targetZ = centerZ + dz;

                const safePos = findSafeFloorInColumn(dimension, targetX, targetZ);
                if (safePos) {
                    return safePos;
                }
            }
        }
    }

    // Emergency platform fallback: build a 3x3 platform at Y=64
    return createEmergencyPlatform(dimension, 0, 64, 0);
}

/**
 * Scans a single column top-down (Y=100 -> 32) for a safe landing position.
 *
 * @param {import("@minecraft/server").Dimension} dimension
 * @param {number} x
 * @param {number} z
 * @returns {{ x: number, y: number, z: number } | null}
 */
export function findSafeFloorInColumn(dimension, x, z) {
    for (let y = MAX_SEARCH_Y; y >= MIN_SEARCH_Y; y--) {
        try {
            const floorBlock = dimension.getBlock({ x, y: y - 1, z });
            const feetBlock = dimension.getBlock({ x, y, z });
            const headBlock = dimension.getBlock({ x, y: y + 1, z });

            if (!floorBlock || !feetBlock || !headBlock) continue;

            // 1. Floor validation
            if (floorBlock.isAir || floorBlock.isLiquid) continue;
            if (HAZARDOUS_BLOCKS.has(floorBlock.typeId)) continue;

            // 2. Headspace validation (2 blocks of air)
            if (!feetBlock.isAir || feetBlock.isLiquid) continue;
            if (!headBlock.isAir || headBlock.isLiquid) continue;

            // 3. Perimeter check (1-block horizontal clearance around floor)
            if (!isPerimeterSafe(dimension, x, y - 1, z)) continue;

            // 4. Overhead check (Y+2 to Y+5 clearance from falling hazards)
            if (!isOverheadSafe(dimension, x, y + 2, z)) continue;

            return { x, y, z };
        } catch (e) {
            // Skip ungenerated chunk blocks
        }
    }
    return null;
}

/**
 * Ensures surrounding blocks at floor level do not contain lava or fire.
 */
function isPerimeterSafe(dimension, x, floorY, z) {
    const offsets = [
        [1, 0], [-1, 0], [0, 1], [0, -1],
        [1, 1], [-1, -1], [1, -1], [-1, 1]
    ];

    for (const [dx, dz] of offsets) {
        const adjacent = dimension.getBlock({ x: x + dx, y: floorY, z: z + dz });
        const adjacentAir = dimension.getBlock({ x: x + dx, y: floorY + 1, z: z + dz });

        if (adjacent && (adjacent.typeId === "minecraft:lava" || adjacent.typeId === "minecraft:flowing_lava")) {
            return false;
        }
        if (adjacentAir && (adjacentAir.typeId === "minecraft:fire" || adjacentAir.typeId === "minecraft:soul_fire")) {
            return false;
        }
    }
    return true;
}

/**
 * Checks vertical space above the player (Y+2 -> Y+5) for lava pockets or falling gravel.
 */
function isOverheadSafe(dimension, x, startY, z) {
    for (let y = startY; y <= startY + 3; y++) {
        const block = dimension.getBlock({ x, y, z });
        if (block) {
            const typeId = block.typeId;
            if (typeId === "minecraft:lava" || typeId === "minecraft:flowing_lava" || typeId === "minecraft:gravel") {
                return false;
            }
        }
    }
    return true;
}

/**
 * Creates an emergency 3x3 Crimson Nylium platform if no natural floor was found.
 */
function createEmergencyPlatform(dimension, centerX, y, centerZ) {
    for (let dx = -1; dx <= 1; dx++) {
        for (let dz = -1; dz <= 1; dz++) {
            const block = dimension.getBlock({ x: centerX + dx, y: y - 1, z: centerZ + dz });
            if (block) {
                block.setType("minecraft:crimson_nylium");
            }
            // Clear headspace
            for (let dy = 0; dy <= 2; dy++) {
                const air = dimension.getBlock({ x: centerX + dx, y: y + dy, z: centerZ + dz });
                if (air && !air.isAir) {
                    air.setType("minecraft:air");
                }
            }
        }
    }
    return { x: centerX, y, z: centerZ };
}
