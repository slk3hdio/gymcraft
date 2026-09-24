package io.github.mousemeya.gymcraft.gym.entity;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
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
 * 本实体不注册任何自主 AI Goal：行为完全由环境动作驱动
 * （{@code DISABLE_VANILLA_AI} 之外也没有游荡/索敌逻辑），且像玩家一样
 * 永不因距离自然消失。
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
     * 不注册任何 Goal：实体行为由 GymCraft 环境动作完全接管。
     */
    @Override
    protected void registerGoals() {
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
}
