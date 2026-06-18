package io.github.mousemeya.withme.gym.env;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 战斗环境 —— 训练 Mob 攻击并击杀目标实体的 RL 环境。
 * <p>
 * 动作空间：{@code gym.move_to}、{@code gym.set_attack_target}、
 * {@code gym.attack_once}、{@code gym.noop}
 * <p>
 * 观测空间：自身状态、附近实体、附近方块、背包、世界状态
 * <p>
 * 奖励设计：
 * <ul>
 *   <li>对目标造成伤害时获得 damageDealt * 2.0 奖励</li>
 *   <li>击杀目标时获得 +50 奖励</li>
 *   <li>每步固定存活惩罚（-0.01）</li>
 * </ul>
 * <p>
 * 终止条件：Mob 自身死亡 或 目标被击杀<br>
 * 截断条件：步数超过 2400
 */
public class CombatEnv extends EntityMcEnv {

    // 该环境支持的动作键列表
    private static final List<String> ACTION_KEYS = List.of("gym.move_to", "gym.set_attack_target", "gym.attack_once", "gym.noop");
    // 该环境输出的观测键列表
    private static final List<String> OBS_KEYS = List.of("gym.self", "gym.nearby_entities", "gym.nearby_blocks", "gym.inventory", "gym.world");

    private UUID targetEntityUuid;   // 攻击目标的 UUID
    private int stepCount;           // 当前回合已执行的步数
    private double lastTargetHealth; // 上一步目标的生命值，用于计算伤害差值

    public CombatEnv(UUID entityUuid) {
        super(entityUuid, ACTION_KEYS, OBS_KEYS);
    }

    @Override
    protected String getEnvType() { return "combat"; }

    @Override
    protected void onReset(Mob mob, Integer seed, Map<String, Object> options) {
        stepCount = 0;
        lastTargetHealth = 0;
        if (options != null && options.containsKey("target_uuid")) {
            targetEntityUuid = UUID.fromString((String) options.get("target_uuid"));
            var state = io.github.mousemeya.withme.gym.agent.AgentRegistry.getState(mob);
            if (state != null) state.attackTargetUuid = targetEntityUuid;
        } else {
            targetEntityUuid = null;
        }
    }

    @Override
    protected double computeReward(Mob mob) {
        stepCount++;
        double reward = 0;
        LivingEntity target = mob.getTarget();
        if (target != null) {
            double currentHealth = target.getHealth();
            double damageDealt = lastTargetHealth - currentHealth;
            reward += damageDealt * 2.0;
            lastTargetHealth = currentHealth;
            if (!target.isAlive()) reward += 50.0;
        }
        reward -= 0.01;
        return reward;
    }

    @Override
    protected boolean isTerminated(Mob mob) {
        if (!mob.isAlive()) return true;
        if (mob.getTarget() != null) return !mob.getTarget().isAlive();
        return false;
    }

    @Override
    protected boolean isTruncated(Mob mob) { return stepCount > 2400; }
}
