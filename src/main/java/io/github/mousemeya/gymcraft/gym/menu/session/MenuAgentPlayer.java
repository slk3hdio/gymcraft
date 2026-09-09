package io.github.mousemeya.gymcraft.gym.menu.session;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.common.util.FakePlayer;

import io.github.mousemeya.gymcraft.gym.fakeplayer.AgentFakePlayerActor;
import io.github.mousemeya.gymcraft.gym.fakeplayer.AgentFakePlayerService;

/**
 * 逻辑菜单会话独占的 FakePlayer 持有者。
 * <p>
 * 原版 {@code MenuProvider#createMenu} 要求 {@code Player.Inventory}，因此每个环境
 * （Agent）的每个活动或候选菜单会话使用独占 {@link FakePlayer}；不同环境、活动会话
 * 和候选会话之间不共享实例或 GameProfile UUID。不得复用
 * {@link AgentFakePlayerService} 的手部复用执行者——长生命周期菜单会共享并覆盖
 * {@code containerMenu}、物品栏、商人 trading player、容器 opener 状态和位置校验。
 * </p>
 * <p>
 * containerId 计数器由本类按原版语义在 1–100 范围循环维护
 * （{@code ServerPlayer#nextContainerCounter()} 在 1.26 为 private，无法直接调用，
 * 此处实现等价逻辑）。协议 session_id 独立于 containerId，由会话层分配。
 * </p>
 * <p>
 * 所有方法仅允许在服务端 tick 线程调用。
 * </p>
 */
public final class MenuAgentPlayer {
    private final AgentFakePlayerActor actor;
    private int containerCounter;

    /** 使用菜单会话独占执行者创建包装器。 */
    private MenuAgentPlayer(AgentFakePlayerActor actor) {
        this.actor = actor;
    }

    /**
     * 为指定 Mob 创建独占 FakePlayer（GameProfile UUID 随机生成，保证全局唯一），
     * 并把位置/朝向同步到 Mob 当前状态。
     */
    public static MenuAgentPlayer create(Mob mob, ServerLevel level) {
        if (mob.level() != level) {
            throw new IllegalArgumentException("Mob is not in the requested menu level");
        }
        return new MenuAgentPlayer(AgentFakePlayerService.createMenuActor(mob));
    }

    /** @return 会话独占的 FakePlayer 实例 */
    public FakePlayer player() {
        return this.actor.player();
    }

    /**
     * 分配下一个 containerId（原版语义：{@code counter % 100 + 1}，即 1–100 循环）。
     *
     * @return 新的 containerId
     */
    public int nextContainerId() {
        this.containerCounter = this.containerCounter % 100 + 1;
        return this.containerCounter;
    }

    /** @return 当前 containerId（尚未分配时为 0） */
    public int containerId() {
        return this.containerCounter;
    }

    /**
     * 把 FakePlayer 位置和朝向同步到 Mob。
     * <p>
     * 菜单 {@code stillValid} 的距离校验与 {@code removed} 的归还/掉落位置都读取
     * 执行者（FakePlayer）坐标，因此打开菜单、执行 {@code removed} 前都必须调用。
     * </p>
     */
    public void syncToMob(Mob mob) {
        this.actor.syncToMob(mob);
    }
}
