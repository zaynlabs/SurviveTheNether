import { world, system } from "@minecraft/server";
import { getOrFindSafeNetherSpawn } from "./spawn_locator.js";

const OVERWORLD_DIM_ID = "minecraft:overworld";

const TAG_WELCOMED = "survive_the_nether:welcomed";
const TAG_HAS_BED = "survive:has_overworld_bed";
const TAG_ESCAPED = "survive:escaped_overworld";

/**
 * Safely resolves the Nether dimension in Bedrock Edition across script versions.
 */
function getNetherDimension() {
    try {
        return world.getDimension("minecraft:nether");
    } catch (e) {
        try {
            return world.getDimension("nether");
        } catch (e2) {
            return world.getDimension("the_nether");
        }
    }
}

// 1. Listen for player joins and respawns
world.afterEvents.playerSpawn.subscribe((event) => {
    const { player, initialSpawn } = event;
    const netherDimension = getNetherDimension();

    if (!netherDimension) {
        return;
    }

    // Initial Join: First time entering the world
    if (initialSpawn && !player.hasTag(TAG_WELCOMED)) {
        player.addTag(TAG_WELCOMED);

        system.run(() => {
            const spawnPos = getOrFindSafeNetherSpawn(netherDimension);

            // Teleport into Nether Crimson Forest
            player.teleport(
                { x: spawnPos.x + 0.5, y: spawnPos.y, z: spawnPos.z + 0.5 },
                {
                    dimension: netherDimension,
                    facingLocation: { x: spawnPos.x + 5, y: spawnPos.y, z: spawnPos.z }
                }
            );

            // Display Title Banner
            player.onScreenDisplay.setTitle(
                { translate: "message.survive-the-nether.welcome_title" },
                {
                    subtitle: { translate: "message.survive-the-nether.welcome_subtitle" },
                    fadeInDuration: 20,
                    stayDuration: 70,
                    fadeOutDuration: 20
                }
            );

            // Send Chat Survival Guidance
            player.sendMessage({ translate: "message.survive-the-nether.welcome_chat_1" });
            player.sendMessage({ translate: "message.survive-the-nether.welcome_chat_2" });
            player.sendMessage({ translate: "message.survive-the-nether.welcome_chat_3" });
        });
    }
    // Fallback Respawn: Player died without an active Respawn Anchor or Overworld Bed
    else if (!initialSpawn && (player.dimension.id === OVERWORLD_DIM_ID || player.dimension.id === "overworld")) {
        if (!player.hasTag(TAG_HAS_BED)) {
            system.run(() => {
                const spawnPos = getOrFindSafeNetherSpawn(netherDimension);

                player.teleport(
                    { x: spawnPos.x + 0.5, y: spawnPos.y, z: spawnPos.z + 0.5 },
                    { dimension: netherDimension }
                );

                player.sendMessage({ translate: "message.survive-the-nether.returned_to_trenches" });
            });
        }
    }
});

// 2. Track Overworld Bed Spawns (Player sleeps/interacts with bed in Overworld)
world.afterEvents.playerInteractWithBlock.subscribe((event) => {
    const { player, block } = event;

    if (
        (player.dimension.id === OVERWORLD_DIM_ID || player.dimension.id === "overworld") &&
        block.typeId.endsWith("_bed")
    ) {
        if (!player.hasTag(TAG_HAS_BED)) {
            player.addTag(TAG_HAS_BED);
        }
    }
});

// 3. First-Time Escape Announcement (Player steps into Overworld for the first time)
world.afterEvents.playerDimensionChange.subscribe((event) => {
    const { player, toDimension } = event;

    if (
        (toDimension.id === OVERWORLD_DIM_ID || toDimension.id === "overworld") &&
        !player.hasTag(TAG_ESCAPED)
    ) {
        player.addTag(TAG_ESCAPED);

        world.sendMessage({
            translate: "message.survive-the-nether.escaped_overworld",
            with: [player.name]
        });
    }
});
