package io.github.mousemeya.withme.gym.action;

import com.mojang.logging.LogUtils;
import io.github.mousemeya.withme.gym.action.proto.McAction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class EntityAgentController {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Map<String, ActionHandler> handlers = new LinkedHashMap<>();

    public EntityAgentController(List<ActionHandler> handlers) {
        for (var h : handlers) {
            this.handlers.put(h.actionKey(), h);
        }
    }

    private static EntityAgentController DEFAULT;

    public static EntityAgentController defaultController() {
        if (DEFAULT == null) {
            DEFAULT = new EntityAgentController(List.of(
                new MoveToHandler(),
                new StepMoveHandler(),
                new SetAttackTargetHandler(),
                new AttackOnceHandler(),
                new NoopHandler()
            ));
        }
        return DEFAULT;
    }

    public void apply(Mob mob, McAction action) {
        if (action.getComponentsCount() == 0) return;

        for (var entry : action.getComponentsMap().entrySet()) {
            var key = entry.getKey();
            var params = entry.getValue();
            var handler = handlers.get(key);
            if (handler == null) {
                LOGGER.debug("No handler for action key: {}", key);
                continue;
            }
            if (!handler.canHandle(mob)) {
                LOGGER.debug("Handler {} cannot handle mob type {}", key, BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()));
                continue;
            }
            try {
                handler.handle(mob, params);
            } catch (Exception e) {
                LOGGER.warn("Error applying action {}: {}", key, e.getMessage());
            }
        }
    }
}
