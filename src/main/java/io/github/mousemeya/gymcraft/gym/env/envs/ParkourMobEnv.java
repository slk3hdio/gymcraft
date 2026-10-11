package io.github.mousemeya.gymcraft.gym.env.envs;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.env.McEnvFactory;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoMcObservation;
import io.github.mousemeya.gymcraft.gym.scenario.ScenarioStore;
import io.github.mousemeya.gymcraft.gym.state.EnvStateProfile;
import io.github.mousemeya.gymcraft.registry.ActionComponents;
import io.github.mousemeya.gymcraft.registry.ObservationCreators;

/**
 * 跳跃搭方块环境：Mob 在受限水平区域中跳跃并将手持方块放到脚下，逐层到达目标高度。
 * <p>
 * 场景来自内置资产 {@code gymcraft:scenario/parkour_mob}（起点平台 + 训练柱 + 主手
 * 方块），构造期解码为状态基线，首个 reset 物化；布局以资产为权威——需要不同布局
 * 时经 {@code /gymcraft env save/load} 自制场景。环境不管理区域实体（训练柱区域内
 * 其他实体不进基线）；close 不做世界恢复，场景残留由外部清理。
 * </p>
 */
public class ParkourMobEnv extends AbstractMcEnv {
    private static final String MAX_STEPS_OPTION = "max_steps";
    private static final int DEFAULT_MAX_STEPS = 256;

    /** 构造期锚定的场景原点（Mob 创建环境时的方块坐标）。 */
    private final BlockPos origin;
    private final double baseY;
    /** 训练柱目标高度（从场景区域高度推导：范围 y 覆盖 -1..targetHeight+2）。 */
    private final int targetHeight;
    private int maxSteps = DEFAULT_MAX_STEPS;
    private int steps;
    private int previousCount;
    private double previousY;
    private boolean success;
    private boolean outOfBounds;
    private boolean resourceExhausted;

    /** 创建跳搭环境并暴露跳跃、放置、移动和空操作组件。 */
    public ParkourMobEnv(Identifier envTypeId, Mob mob) {
        this(envTypeId, mob, null);
    }

    /** 在普通构造之上携带外部场景源（手动 env load）。 */
    public ParkourMobEnv(Identifier envTypeId, Mob mob, @Nullable ScenarioStore.LoadedScenario scenario) {
        super(envTypeId, mob,
            List.of(ActionComponents.NOOP.get(), ActionComponents.JUMP.get(),
                ActionComponents.SET_BLOCK.get(), ActionComponents.STEP_MOVE.get()),
            List.of(ObservationCreators.SELF.get(), ObservationCreators.NEARBY_BLOCKS.get(),
                ObservationCreators.WORLD.get()),
            List.of(),
            EnvStateProfile.bundled("parkour_mob"),
            scenario);

        observationComponent(ObservationCreators.NEARBY_BLOCKS.get()).setMaxBlocks(64);
        this.origin = BlockPos.containing(mob.position());
        this.baseY = this.origin.getY();
        // 场景是布局的唯一权威：训练柱目标高度从场景区域推导（y 覆盖 -1..targetHeight+2，
        // 修改资产即可改变任务难度）
        BoundingBox region = java.util.Objects.requireNonNull(this.stateRegion(),
            "parkour_mob scenario must declare a region");
        this.targetHeight = region.getYSpan() - 4;
    }

    /** 每回合重置计数器（场景内容与 agent 由状态基线恢复）。 */
    @Override
    protected void resetMob(Mob mob, Integer seed, Map<String, Object> options) {
        super.resetMob(mob, seed, options);
        this.maxSteps = positiveInt(options, MAX_STEPS_OPTION, DEFAULT_MAX_STEPS);
        this.previousCount = mob.getMainHandItem().getCount();
        this.steps = 0;
        this.previousY = mob.getY();
        this.success = false;
        this.outOfBounds = false;
        this.resourceExhausted = false;
    }

    /** 计算高度增量、放置奖励、成功奖励及失败惩罚。 */
    @Override
    protected double computeReward(ProtoMcObservation observation) {
        Mob mob = mob();
        double currentY = mob.getY();
        double reward = currentY - this.previousY;
        if (currentY - this.baseY >= this.targetHeight) {
            reward += 0.2 * Math.max(0, Math.floor(currentY - this.previousY));
            if (!this.success) {
                reward += 10.0;
                this.success = true;
            }
        }
        int count = mob.getMainHandItem().getCount();
        if (count < this.previousCount) {
            reward += 0.1;
        }
        this.previousCount = count;
        this.previousY = currentY;
        return reward;
    }

    /** 判断是否达到相对目标高度。 */
    @Override
    protected boolean isTerminated(ProtoMcObservation observation) {
        return this.success || super.isTerminated(observation);
    }

    /** 判断是否越界、耗尽资源或超过步数上限。 */
    @Override
    protected boolean isTruncated(ProtoMcObservation observation) {
        Mob mob = mob();
        this.steps++;
        AABB arena = this.scopeAabb();
        this.outOfBounds = mob.getX() < arena.minX || mob.getX() >= arena.maxX
            || mob.getZ() < arena.minZ || mob.getZ() >= arena.maxZ;
        this.resourceExhausted = mob.getMainHandItem().isEmpty();
        return !this.success && (this.outOfBounds || this.resourceExhausted || this.steps >= this.maxSteps);
    }

    /** 将环境专属运行状态加入 step 信息，供 Python demo 读取。 */
    @Override
    protected Map<String, Object> createStepInfo(ProtoMcObservation observation) {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("height", mob().getY() - this.baseY);
        info.put("target_height", this.targetHeight);
        info.put("remaining_blocks", mob().getMainHandItem().getCount());
        info.put("steps", this.steps);
        info.put("success", this.success);
        info.put("out_of_bounds", this.outOfBounds);
        info.put("resource_exhausted", this.resourceExhausted);
        return info;
    }

    /** 读取正整数 reset option。 */
    private static int positiveInt(Map<String, Object> options, String key, int fallback) {
        Object value = options.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number number) || number.intValue() <= 0) {
            throw new IllegalArgumentException("Reset option '" + key + "' must be a positive integer");
        }
        return number.intValue();
    }

    /** 注册表使用的环境工厂。 */
    public static final class Factory implements McEnvFactory {
        /** 创建指定 Mob 的跳搭环境。 */
        @Override
        public ParkourMobEnv create(Identifier envTypeId, Mob mob, @Nullable ScenarioStore.LoadedScenario scenario) {
            return new ParkourMobEnv(envTypeId, mob, scenario);
        }
    }
}
