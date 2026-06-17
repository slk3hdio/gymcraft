package io.github.mousemeya.withme.agent;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class CombatEnv extends EntityMcEnv {

    private static final List<String> ACTION_KEYS = List.of("gym.move_to", "gym.set_attack_target", "gym.attack_once", "gym.noop");
    private static final List<String> OBS_KEYS = List.of("gym.self", "gym.nearby_entities", "gym.nearby_blocks", "gym.inventory", "gym.world");

    private UUID targetEntityUuid;
    private int stepCount;
    private double lastTargetHealth;

    public CombatEnv(UUID entityUuid) {
        super(entityUuid, ACTION_KEYS, OBS_KEYS);
    }

    @Override
    protected String getEnvType() {
        return "combat";
    }

    @Override
    protected void onReset(Mob mob, Integer seed, Map<String, Object> options) {
        stepCount = 0;
        lastTargetHealth = 0;

        if (options != null && options.containsKey("target_uuid")) {
            targetEntityUuid = UUID.fromString((String) options.get("target_uuid"));
            var state = AgentRegistry.getState(mob);
            if (state != null) {
                state.attackTargetUuid = targetEntityUuid;
            }
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

            if (!target.isAlive()) {
                reward += 50.0;
            }
        }

        reward -= 0.01;
        return reward;
    }

    @Override
    protected boolean isTerminated(Mob mob) {
        if (!mob.isAlive()) return true;

        if (mob.getTarget() != null) {
            return !mob.getTarget().isAlive();
        }
        return false;
    }

    @Override
    protected boolean isTruncated(Mob mob) {
        return stepCount > 2400;
    }
}
