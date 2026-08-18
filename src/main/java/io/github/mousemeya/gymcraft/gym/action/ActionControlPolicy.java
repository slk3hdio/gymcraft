package io.github.mousemeya.gymcraft.gym.action;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.server.level.ServerLevel;

/**
 * 动作控制策略。
 * <p>
 * 由单个或多个动作组件在解包后动态生成，用于在动作执行期间持续压制
 * 原版 Brain / Goal 系统的部分状态。
 * </p>
 */
public final class ActionControlPolicy {
    private final EnumSet<Goal.Flag> disabledGoalFlags = EnumSet.noneOf(Goal.Flag.class);
    private final Map<MemoryModuleType<?>, MemoryOperation<?>> memoryOperations = new LinkedHashMap<>();
    private boolean stopNavigation;
    private boolean stopBrain;

    public static ActionControlPolicy none() {
        return new ActionControlPolicy();
    }

    /**
     * 构造环境级原版 AI 压制策略。
     * <p>
     * 不设置 Mob 的 {@code NoAI} 标志，保留实体移动、重力和跳跃物理；
     * 仅关闭 Goal 控制、停止寻路，并清理 Brain 当前行为依赖的关键记忆。
     * </p>
     */
    public static ActionControlPolicy disableVanillaAi() {
        return none()
            .disableGoalFlags(Goal.Flag.MOVE, Goal.Flag.LOOK, Goal.Flag.JUMP, Goal.Flag.TARGET)
            .stopNavigation()
            .stopBrain()
            .eraseMemory(MemoryModuleType.WALK_TARGET)
            .eraseMemory(MemoryModuleType.PATH)
            .eraseMemory(MemoryModuleType.LOOK_TARGET)
            .eraseMemory(MemoryModuleType.ATTACK_TARGET);
    }

    public ActionControlPolicy disableGoalFlags(Goal.Flag... flags) {
        if (flags != null) {
            for (Goal.Flag flag : flags) {
                if (flag != null) {
                    this.disabledGoalFlags.add(flag);
                }
            }
        }
        return this;
    }

    public <T> ActionControlPolicy setMemory(MemoryModuleType<T> type, T value) {
        this.memoryOperations.put(type, MemoryOperation.set(value));
        return this;
    }

    public <T> ActionControlPolicy setMemoryWithExpiry(MemoryModuleType<T> type, T value, long ticks) {
        this.memoryOperations.put(type, MemoryOperation.setWithExpiry(value, ticks));
        return this;
    }

    public ActionControlPolicy eraseMemory(MemoryModuleType<?> type) {
        this.memoryOperations.put(type, MemoryOperation.erase());
        return this;
    }

    public ActionControlPolicy stopNavigation() {
        this.stopNavigation = true;
        return this;
    }

    public ActionControlPolicy stopBrain() {
        this.stopBrain = true;
        return this;
    }

    public ActionControlPolicy merge(ActionControlPolicy other) {
        if (other == null) {
            return this;
        }

        this.disabledGoalFlags.addAll(other.disabledGoalFlags);
        if (other.stopNavigation) {
            this.stopNavigation = true;
        }
        if (other.stopBrain) {
            this.stopBrain = true;
        }
        this.memoryOperations.putAll(other.memoryOperations);
        return this;
    }

    public void applyTo(Mob mob) {
        for (Goal.Flag flag : this.disabledGoalFlags) {
            mob.goalSelector.setControlFlag(flag, false);
        }

        if (this.stopNavigation) {
            mob.getNavigation().stop();
        }

        if (this.stopBrain && mob.level() instanceof ServerLevel level) {
            stopBrain(mob, level);
        }

        for (Map.Entry<MemoryModuleType<?>, MemoryOperation<?>> entry : this.memoryOperations.entrySet()) {
            applyMemoryOperation(mob, entry);
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static void applyMemoryOperation(Mob mob, Map.Entry<MemoryModuleType<?>, MemoryOperation<?>> entry) {
        ((MemoryOperation)entry.getValue()).apply(mob, entry.getKey());
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private static void stopBrain(Mob mob, ServerLevel level) {
        ((net.minecraft.world.entity.ai.Brain) mob.getBrain()).stopAll(level, mob);
    }

    public void releaseFrom(Mob mob) {
        for (Goal.Flag flag : this.disabledGoalFlags) {
            mob.goalSelector.setControlFlag(flag, true);
        }
    }

    private interface MemoryOperation<T> {
        void apply(Mob mob, MemoryModuleType<T> type);

        static <T> MemoryOperation<T> set(T value) {
            return (mob, type) -> mob.getBrain().setMemory(type, value);
        }

        static <T> MemoryOperation<T> setWithExpiry(T value, long ticks) {
            return (mob, type) -> mob.getBrain().setMemoryWithExpiry(type, value, ticks);
        }

        static <T> MemoryOperation<T> erase() {
            return (mob, type) -> mob.getBrain().eraseMemory(type);
        }
    }
}
