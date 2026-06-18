package io.github.mousemeya.withme.registry;

import io.github.mousemeya.withme.WithMe;
import io.github.mousemeya.withme.gym.action.ActionComponent;
import io.github.mousemeya.withme.gym.action.component.AttackOnceActionComponent;
import io.github.mousemeya.withme.gym.action.component.MoveToActionComponent;
import io.github.mousemeya.withme.gym.action.component.NoopActionComponent;
import io.github.mousemeya.withme.gym.action.component.SetAttackTargetActionComponent;
import io.github.mousemeya.withme.gym.action.component.StepMoveActionComponent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 动作组件注册入口 —— 通过 {@link DeferredRegister} 将所有 {@link ActionComponent} 实现
 * 挂载到 {@link RegistryKeys#ACTION_COMPONENTS} 注册表上。
 * <p>
 * 所有动作组件基于注册表 ID（如 {@code withme:move_to}）在运行时唯一标识。
 * 环境构造时引用这些 {@link DeferredHolder} 来获取组件实例并组合成动作空间。
 * </p>
 */
public final class ActionComponents {
    public static final DeferredRegister<ActionComponent<?>> REGISTRY = DeferredRegister.create(
        RegistryKeys.ACTION_COMPONENTS,
        WithMe.MODID
    );

    public static final DeferredHolder<ActionComponent<?>, MoveToActionComponent> MOVE_TO = REGISTRY.register(
        "move_to",
        MoveToActionComponent::new
    );
    public static final DeferredHolder<ActionComponent<?>, StepMoveActionComponent> STEP_MOVE = REGISTRY.register(
        "step_move",
        StepMoveActionComponent::new
    );
    public static final DeferredHolder<ActionComponent<?>, SetAttackTargetActionComponent> SET_ATTACK_TARGET = REGISTRY.register(
        "set_attack_target",
        SetAttackTargetActionComponent::new
    );
    public static final DeferredHolder<ActionComponent<?>, AttackOnceActionComponent> ATTACK_ONCE = REGISTRY.register(
        "attack_once",
        AttackOnceActionComponent::new
    );
    public static final DeferredHolder<ActionComponent<?>, NoopActionComponent> NOOP = REGISTRY.register(
        "noop",
        NoopActionComponent::new
    );

    private ActionComponents() {
    }
}
