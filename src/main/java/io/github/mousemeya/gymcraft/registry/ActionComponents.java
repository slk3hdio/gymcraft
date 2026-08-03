package io.github.mousemeya.gymcraft.registry;

import io.github.mousemeya.gymcraft.GymCraft;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentFactory;
import io.github.mousemeya.gymcraft.gym.action.component.AttackOnceController;
import io.github.mousemeya.gymcraft.gym.action.component.BreakBlockController;
import io.github.mousemeya.gymcraft.gym.action.component.MoveToController;
import io.github.mousemeya.gymcraft.gym.action.component.NoopController;
import io.github.mousemeya.gymcraft.gym.action.component.PlaceBlockController;
import io.github.mousemeya.gymcraft.gym.action.component.SetAttackTargetController;
import io.github.mousemeya.gymcraft.gym.action.component.StepMoveController;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoAttackOnce;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoBreakBlock;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMoveTo;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoNoop;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoPlaceBlock;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoSetAttackTarget;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoStepMove;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 动作组件注册入口 —— 通过 {@link DeferredRegister} 将所有 {@link ActionComponentFactory} 实现
 * 挂载到 {@link RegistryKeys#ACTION_COMPONENT_FACTORIES} 注册表上。
 * <p>
 * 与 {@code env_factories} 一致：注册表保存动作类型（工厂），注册对象为各 controller 类内
 * 定义并实现的轻量 {@code Factory} 类；环境构造时通过 {@code factory.create()} 为每个环境
 * 创建独立的 {@link ActionComponentController} 实例。
 * 所有动作组件基于注册表 ID（如 {@code GymCraft:move_to}）在运行时唯一标识。
 * </p>
 */
public final class ActionComponents {
    public static final DeferredRegister<ActionComponentFactory<?>> REGISTRY = DeferredRegister.create(
        RegistryKeys.ACTION_COMPONENT_FACTORIES,
        GymCraft.MODID
    );

    public static final DeferredHolder<ActionComponentFactory<?>, ActionComponentFactory<ProtoMoveTo>> MOVE_TO = REGISTRY.register(
        "move_to",
        MoveToController.Factory::new
    );
    public static final DeferredHolder<ActionComponentFactory<?>, ActionComponentFactory<ProtoStepMove>> STEP_MOVE = REGISTRY.register(
        "step_move",
        StepMoveController.Factory::new
    );
    public static final DeferredHolder<ActionComponentFactory<?>, ActionComponentFactory<ProtoSetAttackTarget>> SET_ATTACK_TARGET = REGISTRY.register(
        "set_attack_target",
        SetAttackTargetController.Factory::new
    );
    public static final DeferredHolder<ActionComponentFactory<?>, ActionComponentFactory<ProtoBreakBlock>> BREAK_BLOCK = REGISTRY.register(
        "break_block",
        BreakBlockController.Factory::new
    );
    public static final DeferredHolder<ActionComponentFactory<?>, ActionComponentFactory<ProtoPlaceBlock>> PLACE_BLOCK = REGISTRY.register(
        "place_block",
        PlaceBlockController.Factory::new
    );
    public static final DeferredHolder<ActionComponentFactory<?>, ActionComponentFactory<ProtoAttackOnce>> ATTACK_ONCE = REGISTRY.register(
        "attack_once",
        AttackOnceController.Factory::new
    );
    public static final DeferredHolder<ActionComponentFactory<?>, ActionComponentFactory<ProtoNoop>> NOOP = REGISTRY.register(
        "noop",
        NoopController.Factory::new
    );

    private ActionComponents() {
    }
}
