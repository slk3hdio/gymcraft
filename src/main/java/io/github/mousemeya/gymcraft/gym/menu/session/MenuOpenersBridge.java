package io.github.mousemeya.gymcraft.gym.menu.session;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ContainerOpenersCounter;

/**
 * 容器开盖计数桥 —— 让原版 {@link ContainerOpenersCounter} 的 5 tick 自检
 * （{@code recheckOpeners}）能看到 GymCraft 逻辑菜单会话。
 * <p>
 * 原版自检通过扫描世界实体统计打开者，但菜单会话独占的 FakePlayer 从未加入世界
 * 实体管理器，扫描恒为 0，导致计数被强清归零（菜单仍开、盖子关闭）、随后
 * {@code removed} 的递减产生负计数泄漏、盖子再也无法打开。本类按会话维度补充
 * 计数：活动会话独占 FakePlayer 的 {@code containerMenu} 由
 * {@link LogicalMenuHandle} 维护，直接复用 {@code isOwnContainer} 判定
 * （天然兼容双箱 {@code CompoundContainer} 与 Barrel/Shulker 等其它计数容器）。
 * </p>
 * <p>
 * 调用点仅 {@code ContainerOpenersCounterMixin#recheckOpeners}，运行于服务端 tick 线程。
 * </p>
 */
public final class MenuOpenersBridge {

    private MenuOpenersBridge() {
    }

    /**
     * 统计在指定方块容器上打开了菜单的活动 GymCraft 会话数。
     * <p>
     * 会话 FakePlayer 不在世界实体集合中，与世界实体扫描结果不会重复计数。
     * 距离范围不再复查：会话 refresh 的 {@code stillValid} 校验已保证 Agent
     * 处于交互距离内，超出距离的会话会被自动关闭。
     * </p>
     *
     * @param counter 正在自检的开盖计数器
     * @param level   容器所在世界
     * @return 额外的打开者数量
     */
    public static int extraOpeners(ContainerOpenersCounter counter, Level level) {
        int extra = 0;
        for (LogicalMenuSession session : MenuSessionHooks.openSessions()) {
            if (session.isClosed()) {
                continue;
            }
            var player = session.agentPlayer().player();
            if (player.level() == level && counter.isOwnContainer(player)) {
                extra++;
            }
        }
        return extra;
    }
}
