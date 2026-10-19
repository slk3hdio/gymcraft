package io.github.mousemeya.gymcraft.gametest;

import static io.github.mousemeya.gymcraft.gametest.MenuGameTestSupport.assertTrue;

import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;

import io.github.mousemeya.gymcraft.gym.EnvManager;
import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.env.McEnv;
import io.github.mousemeya.gymcraft.gym.state.AttachScope;

/**
 * 环境运行时抗卸载（{@code EnvChunkGuard}）测试：挂载环境自动强加载状态区域
 * 覆盖的区块（含结构边界外、此前未加载的区块），close 精确释放票且幂等。
 */
public final class EnvChunkGuardGameTests {
    /** 禁止实例化仅包含静态 GameTest 的工具类。 */
    private EnvChunkGuardGameTests() {
    }

    /**
     * 验证挂载带作用域环境时区域覆盖的区块被立即强加载（ticking 级，区块票等级
     * 经 DistanceManager 异步传播，故隔 tick 断言），close 后区块票被精确释放；
     * 关闭路径不抛错且二次 close 幂等。
     * <p>
     * 区域向西/北各延伸 24 格：西南角区块距 8 格宽的结构至少 20 格，必然落在本批
     * 结构强加载区块之外（同批邻居沿 x 轴间隔 13 格，偶发落在邻居结构区块内时
     * 仅弱化"此前未加载"前提，不会误判）。测试体无阻塞调用，全程运行在服务端线程。
     * </p>
     *
     * @param helper GameTest 辅助对象
     */
    public static void attachForcesRegionChunksAndCloseReleases(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Mob agent = MenuGameTestSupport.spawnAgent(helper, EntityType.HUSK, new BlockPos(4, 1, 4));
        UUID agentUuid = agent.getUUID();
        BlockPos anchor = agent.blockPosition();
        ChunkPos corner = new ChunkPos((anchor.getX() - 24) >> 4, (anchor.getZ() - 24) >> 4);
        boolean cornerWasTicking = isEntityTicking(level, corner);
        McEnv env = EnvManager.attach("gymcraft:simple_mob", agent,
            AttachScope.around(agent, 24, 1, 0, 1, 24, 1, true, false));
        // forceChunk(add=true) 同步加载区块：挂载返回时区域角区块必须已加载
        assertTrue(helper, level.hasChunk(corner.x(), corner.z()),
            "region corner chunk was not force-loaded on attach: " + corner);
        assertTrue(helper, ((AbstractMcEnv) env).stateRegion() != null, "scoped attach did not capture a region");
        helper.runAfterDelay(5, () -> {
            try {
                assertTrue(helper, isEntityTicking(level, corner),
                    "region corner chunk is not entity-ticking after attach: " + corner);
            } finally {
                EnvManager.close(agentUuid);
                // 关闭路径不抛错；直接重复 close 验证幂等
                env.close();
                env.close();
            }
        });
        helper.runAfterDelay(25, () -> {
            try {
                if (!cornerWasTicking) {
                    // 角区块此前无人持票：close 后票必须已释放（区块可驻留内存，但不再 entity-ticking）
                    assertTrue(helper, !isEntityTicking(level, corner),
                        "region corner ticket was not released on close: " + corner);
                }
                helper.succeed();
            } finally {
                EnvManager.close(agentUuid);
            }
        });
    }

    /** 查询区块当前是否处于 entity-ticking 票级（等价 /forceload 的强加载效果）。 */
    private static boolean isEntityTicking(ServerLevel level, ChunkPos pos) {
        return level.getChunkSource().chunkMap.getDistanceManager().inEntityTickingRange(pos.pack());
    }
}
