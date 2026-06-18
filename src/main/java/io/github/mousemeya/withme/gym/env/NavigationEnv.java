package io.github.mousemeya.withme.gym.env;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class NavigationEnv extends EntityMcEnv {

    private static final List<String> ACTION_KEYS = List.of("gym.move_to", "gym.noop");
    private static final List<String> OBS_KEYS = List.of("gym.self", "gym.nearby_entities", "gym.nearby_blocks", "gym.world");

    private Vec3 goalPosition;
    private int stepCount;

    public NavigationEnv(UUID entityUuid) {
        super(entityUuid, ACTION_KEYS, OBS_KEYS);
    }

    @Override
    protected String getEnvType() { return "navigation"; }

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
