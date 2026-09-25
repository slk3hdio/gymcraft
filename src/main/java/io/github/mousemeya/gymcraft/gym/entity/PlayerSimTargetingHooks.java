package io.github.mousemeya.gymcraft.gym.entity;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

/**
 * 玩家模拟实体敌对关系钩子 —— 为原版及模组敌对 Mob 补充寻找
 * {@link PlayerSimEntity} 的目标 Goal，使其获得与玩家阵营一致的基础仇恨关系。
 */
public final class PlayerSimTargetingHooks {
    /** 禁止实例化仅承载事件处理器的工具类。 */
    private PlayerSimTargetingHooks() {
    }

    /**
     * 在敌对 Mob 加入服务端世界时安装 PlayerSim 目标选择逻辑。
     *
     * @param event 实体加入世界事件
     */
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()
            || !(event.getEntity() instanceof Mob mob)
            || !(mob instanceof Enemy)
            || hasPlayerSimTargetGoal(mob)) {
            return;
        }
        mob.targetSelector.addGoal(2, new PlayerSimTargetGoal(mob));
    }

    /**
     * 判断指定 Mob 是否已经安装 PlayerSim 目标 Goal，避免实体重新加入世界时重复注册。
     *
     * @param mob 待检查敌对 Mob
     * @return 已安装时返回 true
     */
    private static boolean hasPlayerSimTargetGoal(Mob mob) {
        return mob.targetSelector.getAvailableGoals().stream()
            .anyMatch(wrapped -> wrapped.getGoal() instanceof PlayerSimTargetGoal);
    }

    /**
     * PlayerSim 专用目标选择 Goal，仅负责复用原版最近可攻击目标搜索与目标写入逻辑。
     */
    private static final class PlayerSimTargetGoal extends NearestAttackableTargetGoal<PlayerSimEntity> {
        /**
         * 创建敌对 Mob 的 PlayerSim 目标选择器。
         *
         * @param mob 持有该 Goal 的敌对 Mob
         */
        private PlayerSimTargetGoal(Mob mob) {
            super(mob, PlayerSimEntity.class, true);
        }

        /**
         * 仅在当前目标仍是本 Goal 选中的 PlayerSim 时继续运行，避免覆盖环境动作
         * 在同一 tick 内显式写入的新攻击目标。
         *
         * @return 当前目标仍归本 Goal 所有且满足原版追踪条件时返回 true
         */
        @Override
        public boolean canContinueToUse() {
            return this.mob.getTarget() == this.target && super.canContinueToUse();
        }

        /**
         * 停止 Goal 时只清理由本 Goal 选中的目标；若环境动作已替换目标则予以保留。
         */
        @Override
        public void stop() {
            LivingEntity currentTarget = this.mob.getTarget();
            LivingEntity selectedTarget = this.target;
            super.stop();
            if (currentTarget != null && currentTarget != selectedTarget) {
                this.mob.setTarget(currentTarget);
            }
            this.target = null;
        }
    }
}
