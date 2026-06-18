package io.github.mousemeya.withme.gym.env;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.UUID;

import io.github.mousemeya.withme.registry.ActionComponents;
import io.github.mousemeya.withme.registry.ObservationComponents;

/**
 * 导航环境 —— 训练 Mob 移动到指定目标位置的 RL 环境。
 * <p>
 * 动作空间：{@code withme:move_to}（移动到坐标）、{@code withme:noop}（无操作）
 * <p>
 * 观测空间：自身状态、附近实体、附近方块、世界状态
 * <p>
 * 奖励设计：
 * <ul>
 *   <li>与目标距离成反比的持续惩罚（-dist * 0.01）</li>
 *   <li>到达目标（距离 < 1.5 格）时获得 +10 奖励</li>
 *   <li>每步固定存活惩罚（-0.01）</li>
 * </ul>
 * <p>
 * 终止条件：Mob 死亡 或 到达目标位置<br>
 * 截断条件：步数超过 1200
 */
public class NavigationEnv extends EntityMcEnv {
    private Vec3 goalPosition;  // 导航目标坐标，通过 reset 的 options 设置
    private int stepCount;      // 当前回合已执行的步数

    public NavigationEnv(UUID entityUuid) {
        super(
            entityUuid,
            java.util.List.of(ActionComponents.MOVE_TO.get(), ActionComponents.NOOP.get()),
            java.util.List.of(
                ObservationComponents.SELF.get(),
                ObservationComponents.NEARBY_ENTITIES.get(),
                ObservationComponents.NEARBY_BLOCKS.get(),
                ObservationComponents.WORLD.get()
            )
        );
    }

    @Override
    public String getEnvType() { return "withme:navigation"; }

    @Override
    protected void onReset(Mob mob, Integer seed, Map<String, Object> options) {
        stepCount = 0;
        if (options != null && options.containsKey("goal_x")) {
            goalPosition = new Vec3(
                ((Number) options.get("goal_x")).doubleValue(),
                ((Number) options.get("goal_y")).doubleValue(),
                ((Number) options.get("goal_z")).doubleValue()
            );
        } else {
            goalPosition = null;
        }
    }

    @Override
    protected double computeReward(Mob mob) {
        stepCount++;
        if (goalPosition == null) return 0;
        double dist = mob.position().distanceTo(goalPosition);
        double reward = -dist * 0.01;
        if (dist < 1.5) reward += 10.0;
        reward -= 0.01;
        return reward;
    }

    @Override
    protected boolean isTerminated(Mob mob) {
        return !mob.isAlive() || (goalPosition != null && mob.position().distanceTo(goalPosition) < 1.5);
    }

    @Override
    protected boolean isTruncated(Mob mob) { return stepCount > 1200; }
}
