package io.github.mousemeya.withme.gym.action;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;

/**
 * RL 动作对原版 AI 系统的压制策略。
 * <p>
 * 声明在动作执行期间需要禁用的 Goal Flag 和需要维持的 Brain Memory 状态，
 * 防止原版 Goal/Brain 系统抢占 RL 智能体的控制权。
 * </p>
 * <p>
 * 实例通过 {@link Builder} 构建，多个组件的策略通过 {@link #merge(ActionControlPolicy)} 合并。
 * </p>
 */
public final class ActionControlPolicy {
    private static final ActionControlPolicy NONE = new ActionControlPolicy(
        EnumSet.noneOf(Goal.Flag.class),
        Set.of(),
        Map.of(),
        Map.of(),
        false,
        false
    );

    private final EnumSet<Goal.Flag> disabledGoalFlags;
    private final Set<MemoryModuleType<?>> erasedMemories;
    private final Map<MemoryModuleType<?>, Object> setMemories;
    private final Map<MemoryModuleType<?>, ExpiringValue> setMemoriesWithExpiry;
    private final boolean stopNavigation;
    private final boolean clearTarget;

    private ActionControlPolicy(
        EnumSet<Goal.Flag> disabledGoalFlags,
        Set<MemoryModuleType<?>> erasedMemories,
        Map<MemoryModuleType<?>, Object> setMemories,
        Map<MemoryModuleType<?>, ExpiringValue> setMemoriesWithExpiry,
        boolean stopNavigation,
        boolean clearTarget
    ) {
        this.disabledGoalFlags = disabledGoalFlags;
        this.erasedMemories = Set.copyOf(erasedMemories);
        this.setMemories = Map.copyOf(setMemories);
        this.setMemoriesWithExpiry = Map.copyOf(setMemoriesWithExpiry);
        this.stopNavigation = stopNavigation;
        this.clearTarget = clearTarget;
    }

    /**
     * @return 不压制任何 AI 的空白策略。
     */
    public static ActionControlPolicy none() {
        return NONE;
    }

    /**
     * @return 新 Builder 实例。
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 将本策略的限制应用到指定实体。
     */
    @SuppressWarnings("unchecked")
    public void applyTo(Mob mob) {
        if (this == NONE) return;

        for (Goal.Flag flag : disabledGoalFlags) {
            mob.goalSelector.setControlFlag(flag, false);
        }

        for (MemoryModuleType<?> type : erasedMemories) {
            eraseMemory(mob, type);
        }

        for (var entry : setMemories.entrySet()) {
            setMemory(mob, (MemoryModuleType<Object>) entry.getKey(), entry.getValue());
        }

        for (var entry : setMemoriesWithExpiry.entrySet()) {
            setMemoryWithExpiry(mob, (MemoryModuleType<Object>) entry.getKey(), entry.getValue().value, entry.getValue().ticksToLive);
        }

        if (clearTarget) {
            mob.setTarget(null);
        }

        if (stopNavigation) {
            mob.getNavigation().stop();
        }
    }

    /**
     * 释放本策略的限制，恢复 Goal Flag 到启用状态。
     */
    public void release(Mob mob) {
        if (this == NONE) return;

        for (Goal.Flag flag : disabledGoalFlags) {
            mob.goalSelector.setControlFlag(flag, true);
        }
    }

    /**
     * 合并另一个策略。set 优先于 erase；同 memory 多个 set 时后者覆盖。
     */
    public ActionControlPolicy merge(ActionControlPolicy other) {
        if (other == NONE || other == this) return this;
        if (this == NONE) return other;

        EnumSet<Goal.Flag> flags = EnumSet.copyOf(this.disabledGoalFlags);
        flags.addAll(other.disabledGoalFlags);

        Set<MemoryModuleType<?>> erased = new HashSet<>(this.erasedMemories);
        erased.addAll(other.erasedMemories);

        Map<MemoryModuleType<?>, Object> set = new HashMap<>(this.setMemories);
        set.putAll(other.setMemories);

        Map<MemoryModuleType<?>, ExpiringValue> expiry = new HashMap<>(this.setMemoriesWithExpiry);
        expiry.putAll(other.setMemoriesWithExpiry);

        erased.removeAll(set.keySet());
        erased.removeAll(expiry.keySet());

        return new ActionControlPolicy(
            flags,
            erased,
            set,
            expiry,
            this.stopNavigation || other.stopNavigation,
            this.clearTarget || other.clearTarget
        );
    }

    @SuppressWarnings("unchecked")
    private static <U> void eraseMemory(Mob mob, MemoryModuleType<U> type) {
        mob.getBrain().eraseMemory(type);
    }

    @SuppressWarnings("unchecked")
    private static <U> void setMemory(Mob mob, MemoryModuleType<U> type, Object value) {
        mob.getBrain().setMemory(type, (U) value);
    }

    @SuppressWarnings("unchecked")
    private static <U> void setMemoryWithExpiry(Mob mob, MemoryModuleType<U> type, Object value, long ticksToLive) {
        mob.getBrain().setMemoryWithExpiry(type, (U) value, ticksToLive);
    }

    public boolean isNone() {
        return this == NONE;
    }

    /** 内部过期值包装。 */
    private record ExpiringValue(Object value, long ticksToLive) {}

    /**
     * {@link ActionControlPolicy} 构建器。
     */
    public static final class Builder {
        private final EnumSet<Goal.Flag> disabledGoalFlags = EnumSet.noneOf(Goal.Flag.class);
        private final Set<MemoryModuleType<?>> erasedMemories = new HashSet<>();
        private final Map<MemoryModuleType<?>, Object> setMemories = new HashMap<>();
        private final Map<MemoryModuleType<?>, ExpiringValue> setMemoriesWithExpiry = new HashMap<>();
        private boolean stopNavigation = false;
        private boolean clearTarget = false;

        public Builder disableGoalFlags(Goal.Flag... flags) {
            disabledGoalFlags.addAll(EnumSet.of(flags[0], flags));
            return this;
        }

        public Builder eraseMemory(MemoryModuleType<?> type) {
            erasedMemories.add(type);
            return this;
        }

        public <U> Builder setMemory(MemoryModuleType<U> type, U value) {
            setMemories.put(type, value);
            erasedMemories.remove(type);
            return this;
        }

        public <U> Builder setMemoryWithExpiry(MemoryModuleType<U> type, U value, long ticksToLive) {
            setMemoriesWithExpiry.put(type, new ExpiringValue(value, ticksToLive));
            erasedMemories.remove(type);
            return this;
        }

        public Builder stopNavigation() {
            this.stopNavigation = true;
            return this;
        }

        public Builder clearTarget() {
            this.clearTarget = true;
            return this;
        }

        public ActionControlPolicy build() {
            return new ActionControlPolicy(
                disabledGoalFlags,
                erasedMemories,
                setMemories,
                setMemoriesWithExpiry,
                stopNavigation,
                clearTarget
            );
        }
    }
}
