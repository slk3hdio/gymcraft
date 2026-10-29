package io.github.mousemeya.gymcraft.gym.env;

import java.util.UUID;
import java.util.function.Supplier;

import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import io.github.mousemeya.gymcraft.GymCraft;

/**
 * 环境运行时抗卸载守卫：环境存活期间经 NeoForge 区块票（ticking 级，等价
 * {@code /forceload}）强加载状态区域覆盖的区块，并每 tick 跟随强加载 agent 当前
 * 所在区块（覆盖空区域环境与 agent 被推出区域的场景）；环境关闭时精确释放。
 * <p>
 * 票 owner 使用环境 envId 而非实体 UUID：与实体生命周期解耦，reset 同 UUID 替换
 * 实体不影响票归属，同 Mob 更换环境时各环境只释放自己 envId 的票。区域票与 agent
 * 跟随票分开记账：跟随区块落在区域集合内时不重复持票，迁出时也不释放区域票。
 * </p>
 * <p>
 * 票随存档持久化，但环境注册表是纯内存的（重启即空），故世界加载回调把本控制器
 * 的全部持久票按孤儿无条件清除，天然解决跨重启泄漏；服务器停止时不做主动释放。
 * </p>
 */
public final class EnvChunkGuard {
    private static final Logger LOGGER = LoggerFactory.getLogger(EnvChunkGuard.class);

    /** 环境区块票控制器：所有 GymCraft 环境共用，owner 区分环境实例。 */
    public static final TicketController CONTROLLER = new TicketController(
        ResourceLocation.fromNamespaceAndPath(GymCraft.MODID, "environment"),
        (level, helper) -> {
            // 环境不跨重启存活，持久票皆孤儿，无条件清除
            for (UUID owner : helper.getEntityTickets().keySet()) {
                LOGGER.info("Removing orphaned environment chunk tickets owner={} level={}", owner, level.dimension());
                helper.removeAllTickets(owner);
            }
        });

    /** 注册区块票控制器（模组事件总线，{@link RegisterTicketControllersEvent}）。 */
    public static void registerController(RegisterTicketControllersEvent event) {
        event.register(CONTROLLER);
    }

    private final ServerLevel level;
    private final UUID owner;
    private final Supplier<Mob> mobSupplier;
    /** 区域强加载区块集合（{@link ChunkPos#pack}），release 时逐个退票。 */
    private final LongOpenHashSet regionChunks = new LongOpenHashSet();
    /** 最近跟随的 agent 区块；未持独立票时也可能落在区域集合内（不重复持票）。 */
    private long agentChunk = ChunkPos.INVALID_CHUNK_POS;
    private boolean released;

    /**
     * 创建守卫并注册 tick 跟随监听。
     *
     * @param level 环境所在服务端世界
     * @param owner 票 owner（环境 envId）
     * @param mobSupplier 当前 agent 提供者（reset 同 UUID 替换实体后自动指向新实例）
     */
    public EnvChunkGuard(ServerLevel level, UUID owner, Supplier<Mob> mobSupplier) {
        this.level = level;
        this.owner = owner;
        this.mobSupplier = mobSupplier;
        NeoForge.EVENT_BUS.register(this);
    }

    /**
     * 强加载区域覆盖的全部区块：须在基线捕获/场景物化之前调用，顺带满足
     * {@code RegionBlockSnapshot.requireChunksLoaded} 的已加载前置；空区域跳过。
     *
     * @param region 环境状态区域（闭区间方块盒，Y 无关）；null 表示空区域
     */
    public void forceRegion(@Nullable BoundingBox region) {
        if (this.released || region == null) {
            return;
        }
        for (int chunkX = region.minX() >> 4; chunkX <= region.maxX() >> 4; chunkX++) {
            for (int chunkZ = region.minZ() >> 4; chunkZ <= region.maxZ() >> 4; chunkZ++) {
                CONTROLLER.forceChunk(this.level, this.owner, chunkX, chunkZ, true, true);
                this.regionChunks.add(ChunkPos.asLong(chunkX, chunkZ));
            }
        }
    }

    /** 立即强加载当前 agent 所在区块作为初始跟随（环境构造末尾调用一次）。 */
    public void followAgentNow() {
        Mob mob = this.mobSupplier.get();
        if (mob != null) {
            this.trackAgent(mob);
        }
    }

    /** 每 tick 检查 agent 区块迁移：迁入新区块时补票，迁出时释放旧跟随票。 */
    @SubscribeEvent
    private void onServerTickPost(ServerTickEvent.Post event) {
        Mob mob = this.mobSupplier.get();
        if (mob != null) {
            this.trackAgent(mob);
        }
    }

    /** 切换 agent 跟随区块：区域集合内的区块不重复持票，迁出时不释放区域票。 */
    private void trackAgent(Mob mob) {
        if (this.released) {
            return;
        }
        long current = mob.chunkPosition().toLong();
        if (current == this.agentChunk) {
            return;
        }
        long previous = this.agentChunk;
        this.agentChunk = current;
        if (!this.regionChunks.contains(current)) {
            CONTROLLER.forceChunk(this.level, this.owner, ChunkPos.getX(current), ChunkPos.getZ(current), true, true);
        }
        if (previous != ChunkPos.INVALID_CHUNK_POS && !this.regionChunks.contains(previous)) {
            CONTROLLER.forceChunk(this.level, this.owner, ChunkPos.getX(previous), ChunkPos.getZ(previous), false, true);
        }
    }

    /** 释放全部区域票与当前跟随票并注销监听；幂等（二次调用直接返回）。 */
    public void release() {
        if (this.released) {
            return;
        }
        this.released = true;
        NeoForge.EVENT_BUS.unregister(this);
        long agent = this.agentChunk;
        boolean agentTicketHeld = agent != ChunkPos.INVALID_CHUNK_POS && !this.regionChunks.contains(agent);
        this.agentChunk = ChunkPos.INVALID_CHUNK_POS;
        for (long packed : this.regionChunks) {
            CONTROLLER.forceChunk(this.level, this.owner, ChunkPos.getX(packed), ChunkPos.getZ(packed), false, true);
        }
        this.regionChunks.clear();
        if (agentTicketHeld) {
            CONTROLLER.forceChunk(this.level, this.owner, ChunkPos.getX(agent), ChunkPos.getZ(agent), false, true);
        }
    }
}
