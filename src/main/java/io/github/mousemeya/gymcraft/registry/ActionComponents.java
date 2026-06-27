package io.github.mousemeya.gymcraft.registry;

import java.util.Optional;

import io.github.mousemeya.gymcraft.GymCraft;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.component.AttackOnceController;
import io.github.mousemeya.gymcraft.gym.action.component.MoveToController;
import io.github.mousemeya.gymcraft.gym.action.component.NoopController;
import io.github.mousemeya.gymcraft.gym.action.component.SetAttackTargetController;
import io.github.mousemeya.gymcraft.gym.action.component.StepMoveController;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 动作组件注册入口 —— 通过 {@link DeferredRegister} 将所有 {@link ActionComponentController} 实现
 * 挂载到 {@link RegistryKeys#ACTION_COMPONENT_CONTROLLERS} 注册表上。
 * <p>
 * 所有动作组件基于注册表 ID（如 {@code GymCraft:move_to}）在运行时唯一标识。
 * 环境构造时引用这些 {@link DeferredHolder} 来获取组件实例并组合成动作空间。
 * </p>
 */
public final class ActionComponents {
    public static final DeferredRegister<ActionComponentController<?>> REGISTRY = DeferredRegister.create(
        RegistryKeys.ACTION_COMPONENT_CONTROLLERS,
        GymCraft.MODID
    );

    public static final DeferredHolder<ActionComponentController<?>, MoveToController> MOVE_TO = REGISTRY.register(
        "move_to",
        () -> new MoveToController(Optional.empty())
    );
    public static final DeferredHolder<ActionComponentController<?>, StepMoveController> STEP_MOVE = REGISTRY.register(
        "step_move",
        () -> new StepMoveController(Optional.empty())
    );
    public static final DeferredHolder<ActionComponentController<?>, SetAttackTargetController> SET_ATTACK_TARGET = REGISTRY.register(
        "set_attack_target",
        () -> new SetAttackTargetController(Optional.empty())
    );
    public static final DeferredHolder<ActionComponentController<?>, AttackOnceController> ATTACK_ONCE = REGISTRY.register(
        "attack_once",
        () -> new AttackOnceController(Optional.empty())
    );
    public static final DeferredHolder<ActionComponentController<?>, NoopController> NOOP = REGISTRY.register(
        "noop",
        () -> new NoopController(Optional.empty())
    );

    private ActionComponents() {
    }
}
