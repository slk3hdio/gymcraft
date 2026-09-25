package io.github.mousemeya.gymcraft.gym.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.level.Level;

/**
 * 玩家模拟实体（{@code gymcraft:player_sim}）—— 玩家外观的受控 Agent 实体。
 * <p>
 * 设计目标是在不引入 Carpet 式假玩家的前提下尽可能贴近玩家：
 * 玩家体型（0.6×1.8，眼高 1.62）、玩家属性（20 生命、0.1 移动速度、
 * 4.5 格方块交互距离、3 格实体交互距离）、玩家模型渲染
 * （见 {@code client/PlayerSimRenderer}，固定 Steve 皮肤）。
 * 动作/观测/菜单基建全部走既有 Mob 通路（use_item、break_block 等经
 * FakePlayer 桥执行），因此本实体对 GymCraft 环境零适配、可直接挂载。
 * </p>
 * <p>
 * 本实体不注册游荡或自主索敌 Goal：攻击目标完全由环境动作指定。
 * 仅注册原版近战执行 Goal，用于消费 {@code set_attack_target} 写入的目标，
 * 复用标准的追击、注视、视线、攻击距离、冷却和挥手逻辑；清除目标后立即停止。
 * 实体像玩家一样永不因距离自然消失。
 * </p>
 */
public class PlayerSimEntity extends PathfinderMob {

    /**
     * 创建玩家模拟实体。
     *
     * @param type 实体类型（注册表中的 {@code gymcraft:player_sim}）
     * @param level 所在世界
     */
    public PlayerSimEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        this.moveControl = new PlayerSimMoveControl(this);
    }

    /**
     * 玩家属性表 —— 以 {@link Mob#createMobAttributes()} 为基础，显式叠加
     * 与 {@code Player.createAttributes()} 对齐的数值（生命 20、徒手伤害 1、
     * 移动速度 0.1、玩家交互距离等）。
     *
     * @return 玩家风格属性构建器
     */
    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes()
            .add(Attributes.MAX_HEALTH, 20.0)
            .add(Attributes.MOVEMENT_SPEED, 0.1)
            .add(Attributes.ATTACK_DAMAGE, 1.0)
            .add(Attributes.ATTACK_SPEED, 4.0)
            .add(Attributes.BLOCK_INTERACTION_RANGE, 4.5)
            .add(Attributes.ENTITY_INTERACTION_RANGE, 3.0)
            .add(Attributes.STEP_HEIGHT, 0.6)
            .add(Attributes.SAFE_FALL_DISTANCE, 3.0)
            .add(Attributes.JUMP_STRENGTH, 0.42);
    }

    /**
     * 注册外部目标驱动的原版近战执行逻辑。
     * <p>
     * 不注册 {@code targetSelector} Goal，因此实体不会自主选择攻击对象；只有
     * {@code set_attack_target} 设置目标后，本 Goal 才会寻路、注视并按原版节奏攻击。
     * </p>
     */
    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(1, new MeleeAttackGoal(this, 1.0, true));
    }

    /**
     * 推进实体逻辑以及玩家式挥手动画时间轴。
     * <p>
     * {@link PathfinderMob} 不会像原版 {@code Player}/{@code Monster} 那样自动调用
     * {@link #updateSwingTime()}；若不显式推进，服务端与客户端虽然都收到了挥手事件，
     * 渲染状态中的 {@code attackAnim} 仍会一直为零。
     * </p>
     */
    @Override
    public void aiStep() {
        super.aiStep();
        this.updateSwingTime();
    }

    /**
     * 玩家不因距离自然消失；受控 Agent 同样永不消失，避免环境实体被回收。
     *
     * @param distanceToClosestPlayer 与最近玩家的距离
     * @return 恒为 false
     */
    @Override
    public boolean removeWhenFarAway(double distanceToClosestPlayer) {
        return false;
    }

    /**
     * 主手恒为右手，与绝大多数玩家一致（渲染与持物判定使用）。
     *
     * @return 右手
     */
    @Override
    public HumanoidArm getMainArm() {
        return HumanoidArm.RIGHT;
    }

    /**
     * 玩家模拟实体的移动控制器 —— 将单步移动输入按玩家完整移动速度执行。
     * <p>
     * 原版 {@link MoveControl#strafe(float, float)} 固定使用 {@code 0.25} 倍属性速度，
     * 这是 Mob 随机游走的速度语义；玩家输入则直接使用完整的
     * {@link Attributes#MOVEMENT_SPEED}，因此这里仅覆盖该倍率。
     * </p>
     */
    private static final class PlayerSimMoveControl extends MoveControl {
        /** 与原版 MoveControl 一致的到达判定距离平方。 */
        private static final double MIN_MOVE_DISTANCE_SQUARED = 2.5000003E-7;

        /**
         * 创建玩家速度移动控制器。
         *
         * @param mob 被控制的玩家模拟实体
         */
        private PlayerSimMoveControl(PlayerSimEntity mob) {
            super(mob);
        }

        /**
         * 接收前进与横移输入，并使用完整的玩家移动速度属性。
         *
         * @param forwards 前进输入，范围通常为 -1 到 1
         * @param right 右移输入，范围通常为 -1 到 1
         */
        @Override
        public void strafe(float forwards, float right) {
            super.strafe(forwards, right);
            this.speedModifier = 1.0;
        }

        /**
         * 推进移动控制，并将寻路产生的前进输入恢复为玩家使用的满量程输入。
         * <p>
         * Mob 的 {@code setSpeed} 会同时把 {@code zza} 设为速度值，导致玩家的
         * {@code 0.1} 属性在寻路时被重复乘算；保留速度值、仅恢复输入为 1，
         * 可得到与玩家连续按住前进键一致的地面移动速度。
         * </p>
         */
        @Override
        public void tick() {
            boolean shouldUseFullForwardInput = this.operation == Operation.JUMPING
                || this.operation == Operation.MOVE_TO && this.distanceToWantedSqr() >= MIN_MOVE_DISTANCE_SQUARED;
            super.tick();
            if (shouldUseFullForwardInput && this.mob.getSpeed() > 0.0F) {
                this.mob.setZza(1.0F);
            }
        }

        /**
         * 计算实体与当前 MoveControl 目标之间的三维距离平方。
         *
         * @return 到目标的距离平方
         */
        private double distanceToWantedSqr() {
            double dx = this.wantedX - this.mob.getX();
            double dy = this.wantedY - this.mob.getY();
            double dz = this.wantedZ - this.mob.getZ();
            return dx * dx + dy * dy + dz * dz;
        }
    }
}
