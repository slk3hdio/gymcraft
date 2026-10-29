package io.github.mousemeya.gymcraft.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.ContainerOpenersCounter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;

import io.github.mousemeya.gymcraft.gym.menu.session.MenuOpenersBridge;

/**
 * 修复 GymCraft 逻辑菜单会话与原版容器开盖自检的计数不一致。
 * <p>
 * 原版 {@code recheckOpeners}（打开后 5 tick 由方块 tick 触发）通过扫描世界实体
 * 重新统计打开者并直接覆盖计数。菜单会话独占的 FakePlayer 不在世界实体集合中，
 * 扫描恒为 0，导致：菜单仍开但盖子被强制关闭；会话真正关闭时的递减把计数压成
 * 负值泄漏；此后计数回不到 1，{@code onOpen} 不再触发，盖子永远无法再打开。
 * 本注入在自检逻辑中并入 {@link MenuOpenersBridge} 统计的活动会话数，
 * 使计数在会话存续期间保持正确，会话关闭时 {@code removed} 的递减恰好归零。
 * 方法体复制自原版实现，仅把会话补充计数加入 openCount。
 * </p>
 */
@Mixin(ContainerOpenersCounter.class)
public abstract class ContainerOpenersCounterMixin {

    /** 原版自检排程间隔（{@code ContainerOpenersCounter.CHECK_TICK_DELAY}）。 */
    private static final int CHECK_TICK_DELAY = 5;

    @Shadow
    private int openCount;

    @Shadow
    private double maxInteractionRange;

    @Shadow
    protected abstract void onOpen(Level level, BlockPos pos, BlockState blockState);

    @Shadow
    protected abstract void onClose(Level level, BlockPos pos, BlockState blockState);

    @Shadow
    protected abstract void openerCountChanged(Level level, BlockPos pos, BlockState blockState, int previous, int current);

    @Shadow
    protected abstract List<Player> getPlayersWithContainerOpen(Level level, BlockPos pos);

    @Shadow
    protected abstract boolean isOwnContainer(Player player);

    /**
     * 目标容器对指定玩家的“是否持有本容器菜单”判定（包装 shadow 的抽象方法）。
     *
     * @param player 待判定的玩家
     * @return 玩家当前打开的菜单是否正是本容器
     */
    private boolean gymcraft$isOwnContainer(Player player) {
        return this.isOwnContainer(player);
    }

    /**
     * 重实现自检：在世界实体扫描结果上并入 GymCraft 活动菜单会话数。
     *
     * @param ci 回调；原方法始终被取消，由本实现接管
     */
    @Inject(method = "recheckOpeners", at = @At("HEAD"), cancellable = true)
    private void gymcraft$recheckOpeners(Level level, BlockPos pos, BlockState blockState, CallbackInfo ci) {
        List<Player> players = this.getPlayersWithContainerOpen(level, pos);
        this.maxInteractionRange = 0.0;
        for (Player player : players) {
            this.maxInteractionRange = Math.max(player.blockInteractionRange(), this.maxInteractionRange);
        }

        // 会话补充计数经谓词传入：isOwnContainer 在 1.21.1 为 protected，
        // 不能用 AT 放开（会破坏原版匿名实现类的窄化覆盖）
        int openCount = players.size()
            + MenuOpenersBridge.extraOpeners(this::gymcraft$isOwnContainer, level);
        int prevCount = this.openCount;
        if (prevCount != openCount) {
            boolean isOpen = openCount != 0;
            boolean wasOpen = prevCount != 0;
            if (isOpen && !wasOpen) {
                this.onOpen(level, pos, blockState);
                level.gameEvent(null, GameEvent.CONTAINER_OPEN, pos);
            } else if (!isOpen) {
                this.onClose(level, pos, blockState);
                level.gameEvent(null, GameEvent.CONTAINER_CLOSE, pos);
            }
            this.openCount = openCount;
        }

        this.openerCountChanged(level, pos, blockState, prevCount, openCount);
        if (openCount > 0) {
            level.scheduleTick(pos, blockState.getBlock(), CHECK_TICK_DELAY);
        }
        ci.cancel();
    }
}
