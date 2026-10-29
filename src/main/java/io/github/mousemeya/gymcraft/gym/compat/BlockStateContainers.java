package io.github.mousemeya.gymcraft.gym.compat;

import com.mojang.serialization.Codec;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

/** 提供与场景 MCA 格式一致的 1.21.1 方块容器；编解码不得回退或丢失方块状态。 */
public final class BlockStateContainers {
    private final net.minecraft.core.Registry<net.minecraft.world.level.biome.Biome> biomes;

    /** 保存世界动态生物群系注册表；参数 access 为当前世界注册表。 */
    private BlockStateContainers(RegistryAccess access) { this.biomes = access.registryOrThrow(net.minecraft.core.registries.Registries.BIOME); }

    /** 创建容器工厂；参数 access 为当前世界注册表，返回方块存储工厂。 */
    public static BlockStateContainers create(RegistryAccess access) { return new BlockStateContainers(access); }

    /** 返回独立的空气区块段，使用当前世界生物群系注册表。 */
    public net.minecraft.world.level.chunk.LevelChunkSection createSection() {
        return new net.minecraft.world.level.chunk.LevelChunkSection(this.biomes);
    }

    /** 返回以空气填充的独立区块段方块容器，无参数。 */
    public PalettedContainer<BlockState> createForBlockStates() {
        return new PalettedContainer<>(Block.BLOCK_STATE_REGISTRY, Blocks.AIR.defaultBlockState(), PalettedContainer.Strategy.SECTION_STATES);
    }

    /** 返回原版区块方块调色板 Codec，无参数。 */
    public Codec<PalettedContainer<BlockState>> blockStatesContainerCodec() {
        return PalettedContainer.codecRW(Block.BLOCK_STATE_REGISTRY, BlockState.CODEC, PalettedContainer.Strategy.SECTION_STATES, Blocks.AIR.defaultBlockState());
    }
}
