package io.github.mousemeya.gymcraft.gym.menu.session;

import io.github.mousemeya.gymcraft.gym.fakeplayer.AgentInventoryBridge;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.annotation.Nullable;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerListener;
import net.minecraft.world.inventory.ContainerSynchronizer;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.event.entity.player.PlayerContainerEvent;

/**
 * 逻辑菜单句柄 —— 一次逻辑菜单会话的服务端打开/关闭适配。
 * <p>
 * 打开适配对应原版 {@code ServerPlayer#openMenu}/{@code initMenu} 链路中与服务端语义
 * 相关的部分：注册槽位监听、安装无网络 {@link ContainerSynchronizer}、建立
 * {@code fakePlayer.containerMenu = menu} 不变量并发布 {@link PlayerContainerEvent.Open}；
 * 不发送任何客户端数据包（无真实连接）。
 * </p>
 * <p>
 * 关闭对应原版 {@code ServerPlayer#doCloseContainer}：{@code removed} →
 * {@code inventoryMenu.transferState(menu)} → {@link PlayerContainerEvent.Close} →
 * {@code containerMenu} 复位。关闭必须且只能执行一次，{@link #closeMenuOnce} 幂等。
 * 会话状态（slot_id 映射、槽位快照）由后续阶段的 LogicalMenuSession 持有，
 * 本类只负责菜单生命周期与桥接清算。
 * </p>
 * <p>
 * 所有方法仅允许在服务端 tick 线程调用。
 * </p>
 */
public final class LogicalMenuHandle {
    private final MenuAgentPlayer agentPlayer;
    private final AbstractContainerMenu menu;
    @Nullable
    private final AgentInventoryBridge bridge;
    /** 幂等关闭标记：死亡分支每 tick 触发清理等重复路径必须扛住。 */
    private final AtomicBoolean closed = new AtomicBoolean();

    private LogicalMenuHandle(MenuAgentPlayer agentPlayer, AbstractContainerMenu menu, @Nullable AgentInventoryBridge bridge) {
        this.agentPlayer = agentPlayer;
        this.menu = menu;
        this.bridge = bridge;
    }

    /** 关闭结果：performed 表示本次调用实际执行了关闭；writeBack 为桥接写回/清算明细（无桥接时为 null）。 */
    public record CloseResult(boolean performed, @Nullable AgentInventoryBridge.WriteBackResult writeBack) {
    }

    /**
     * 执行打开适配并创建句柄。
     * <p>
     * 调用方需已完成菜单构造（{@code provider.createMenu(...)}）与候选验证；
     * 本方法执行原版服务端链路剩余的打开适配。部分菜单逻辑（按钮处理、槽位状态变更）
     * 校验 {@code player.containerMenu} 与 containerId 一致性，该不变量在任何菜单操作前建立。
     * </p>
     */
    public static LogicalMenuHandle open(MenuAgentPlayer agentPlayer, AbstractContainerMenu menu, @Nullable AgentInventoryBridge bridge) {
        // initMenu 等价：槽位监听 + 无网络同步器（不向不存在的客户端发送数据）
        menu.addSlotListener(LogicalContainerListener.INSTANCE);
        menu.setSynchronizer(NoNetworkSynchronizer.INSTANCE);
        FakePlayer player = agentPlayer.player();
        player.containerMenu = menu;
        NeoForge.EVENT_BUS.post(new PlayerContainerEvent.Open(player, menu));
        return new LogicalMenuHandle(agentPlayer, menu, bridge);
    }

    /** @return 当前菜单 */
    public AbstractContainerMenu menu() {
        return this.menu;
    }

    /** @return 会话独占 FakePlayer 持有者 */
    public MenuAgentPlayer agentPlayer() {
        return this.agentPlayer;
    }

    /** @return 是否已关闭 */
    public boolean isClosed() {
        return this.closed.get();
    }

    /**
     * 幂等关闭当前菜单。
     * <p>
     * 调用 {@code removed} 前把 FakePlayer 同步到 Mob 位置（距离校验与归还位置依赖）。
     * {@code removed} 产生的归还物、carried stack 和工作站临时输入会先落入
     * FakePlayer 物品栏，随后由桥接写回与清算（空主手 → Mob 自带容器 → 掉落）处理，
     * 任何路径都不静默删除。重复调用直接返回未执行结果。
     * </p>
     *
     * @param mob 所属 Agent 实体
     * @return 关闭结果；已关闭过时 performed=false
     */
    public CloseResult closeMenuOnce(Mob mob) {
        if (!this.closed.compareAndSet(false, true)) {
            return new CloseResult(false, null);
        }
        FakePlayer player = this.agentPlayer.player();
        this.agentPlayer.syncToMob(mob);
        this.menu.removed(player);
        player.inventoryMenu.transferState(this.menu);
        NeoForge.EVENT_BUS.post(new PlayerContainerEvent.Close(player, this.menu));
        player.containerMenu = player.inventoryMenu;
        AgentInventoryBridge.WriteBackResult writeBack = this.bridge == null ? null : this.bridge.writeBackToMob();
        return new CloseResult(true, writeBack);
    }

    /**
     * 无网络同步器 —— 菜单 broadcastChanges 触发的所有同步回调都丢弃。
     * 1.21.1 的 {@link ContainerSynchronizer} 没有 RemoteSlot/createSlot 概念。
     */
    private enum NoNetworkSynchronizer implements ContainerSynchronizer {
        INSTANCE;

        @Override
        public void sendInitialData(AbstractContainerMenu container, NonNullList<ItemStack> slotItems, ItemStack carried, int[] dataSlots) {
        }

        @Override
        public void sendSlotChange(AbstractContainerMenu container, int slotIndex, ItemStack itemStack) {
        }

        @Override
        public void sendCarriedChange(AbstractContainerMenu container, ItemStack itemStack) {
        }

        @Override
        public void sendDataChange(AbstractContainerMenu container, int id, int value) {
        }

    }

    /**
     * 逻辑菜单槽位监听 —— 原版监听只触发 INVENTORY_CHANGED 进度判据，
     * 对 FakePlayer 无意义，这里保留空实现以维持 initMenu 链路结构。
     */
    private enum LogicalContainerListener implements ContainerListener {
        INSTANCE;

        @Override
        public void slotChanged(AbstractContainerMenu container, int slotIndex, ItemStack itemStack) {
        }

        @Override
        public void dataChanged(AbstractContainerMenu container, int id, int value) {
        }
    }
}
