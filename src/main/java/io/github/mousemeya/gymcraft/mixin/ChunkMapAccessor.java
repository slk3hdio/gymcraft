package io.github.mousemeya.gymcraft.mixin;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * 区块可见集合的只读访问桥：观测复制 holder 快照后枚举已加载结构起点。
 * 必须在服务端线程调用，不得修改返回的底层映射或请求加载任何区块。
 */
@Mixin(ChunkMap.class)
public interface ChunkMapAccessor {
    /** @return 原版可见 holder 映射，仅用于复制只读快照，禁止写入 */
    @Accessor("visibleChunkMap")
    Long2ObjectLinkedOpenHashMap<ChunkHolder> gymcraft$getVisibleChunkMap();
}
