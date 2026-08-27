package io.github.mousemeya.gymcraft.registry;

import io.github.mousemeya.gymcraft.GymCraft;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentFactory;
import io.github.mousemeya.gymcraft.gym.action.component.AttackOnceController;
import io.github.mousemeya.gymcraft.gym.action.component.BreakBlockController;
import io.github.mousemeya.gymcraft.gym.action.component.ClickMenuButtonController;
import io.github.mousemeya.gymcraft.gym.action.component.CloseMenuController;
import io.github.mousemeya.gymcraft.gym.action.component.JumpController;
import io.github.mousemeya.gymcraft.gym.action.component.LookAtController;
import io.github.mousemeya.gymcraft.gym.action.component.MoveMenuItemController;
import io.github.mousemeya.gymcraft.gym.action.component.MoveToController;
import io.github.mousemeya.gymcraft.gym.action.component.NoopController;
import io.github.mousemeya.gymcraft.gym.action.component.OpenMenuController;
import io.github.mousemeya.gymcraft.gym.action.component.PickUpItemController;
import io.github.mousemeya.gymcraft.gym.action.component.SetAttackTargetController;
import io.github.mousemeya.gymcraft.gym.action.component.SetBlockController;
import io.github.mousemeya.gymcraft.gym.action.component.StepMoveController;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoAttackOnce;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoBreakBlock;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoClickMenuButton;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoCloseMenu;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoJump;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoLookAt;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMoveMenuItem;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMoveTo;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoNoop;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoOpenMenu;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoPickUpItem;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoSetAttackTarget;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoSetBlock;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoStepMove;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 动作组件注册入口 —— 通过 {@link DeferredRegister} 将所有 {@link ActionComponentFactory} 实现
 * 挂载到 {@link RegistryKeys#ACTION_COMPONENT_FACTORIES} 注册表上。
 * <p>
 * 与 {@code env_factories} 一致：注册表保存动作类型（工厂），注册对象为各 controller 类内
 * 定义并实现的轻量 {@code Factory} 类；环境构造时通过 {@code factory.create(mob)} 为每个环境
 * 创建独立的 {@link ActionComponentController} 实例，组件默认值可在环境构造期经
 * {@code AbstractMcEnv.actionComponent(factory)} 取实例后调用 setter 覆盖。
 * 所有动作组件基于注册表 ID（如 {@code GymCraft:move_to}）在运行时唯一标识。
 * </p>
 */
public final class ActionComponents {
    public static final DeferredRegister<ActionComponentFactory<?, ?>> REGISTRY = DeferredRegister.create(
        RegistryKeys.ACTION_COMPONENT_FACTORIES,
        GymCraft.MODID
    );

    public static final DeferredHolder<ActionComponentFactory<?, ?>, ActionComponentFactory<ProtoMoveTo, MoveToController>> MOVE_TO = REGISTRY.register(
        "move_to",
        MoveToController.Factory::new
    );
    public static final DeferredHolder<ActionComponentFactory<?, ?>, ActionComponentFactory<ProtoStepMove, StepMoveController>> STEP_MOVE = REGISTRY.register(
        "step_move",
        StepMoveController.Factory::new
    );
    public static final DeferredHolder<ActionComponentFactory<?, ?>, ActionComponentFactory<ProtoLookAt, LookAtController>> LOOK_AT = REGISTRY.register(
        "look_at",
        LookAtController.Factory::new
    );
    public static final DeferredHolder<ActionComponentFactory<?, ?>, ActionComponentFactory<ProtoSetAttackTarget, SetAttackTargetController>> SET_ATTACK_TARGET = REGISTRY.register(
        "set_attack_target",
        SetAttackTargetController.Factory::new
    );
    public static final DeferredHolder<ActionComponentFactory<?, ?>, ActionComponentFactory<ProtoBreakBlock, BreakBlockController>> BREAK_BLOCK = REGISTRY.register(
        "break_block",
        BreakBlockController.Factory::new
    );
    public static final DeferredHolder<ActionComponentFactory<?, ?>, ActionComponentFactory<ProtoSetBlock, SetBlockController>> SET_BLOCK = REGISTRY.register(
        "set_block",
        SetBlockController.Factory::new
    );
    public static final DeferredHolder<ActionComponentFactory<?, ?>, ActionComponentFactory<ProtoAttackOnce, AttackOnceController>> ATTACK_ONCE = REGISTRY.register(
        "attack_once",
        AttackOnceController.Factory::new
    );
    public static final DeferredHolder<ActionComponentFactory<?, ?>, ActionComponentFactory<ProtoNoop, NoopController>> NOOP = REGISTRY.register(
        "noop",
        NoopController.Factory::new
    );
    public static final DeferredHolder<ActionComponentFactory<?, ?>, ActionComponentFactory<ProtoJump, JumpController>> JUMP = REGISTRY.register(
        "jump",
        JumpController.Factory::new
    );
    public static final DeferredHolder<ActionComponentFactory<?, ?>, ActionComponentFactory<ProtoOpenMenu, OpenMenuController>> OPEN_MENU = REGISTRY.register(
        "open_menu",
        OpenMenuController.Factory::new
    );
    public static final DeferredHolder<ActionComponentFactory<?, ?>, ActionComponentFactory<ProtoCloseMenu, CloseMenuController>> CLOSE_MENU = REGISTRY.register(
        "close_menu",
        CloseMenuController.Factory::new
    );
    public static final DeferredHolder<ActionComponentFactory<?, ?>, ActionComponentFactory<ProtoMoveMenuItem, MoveMenuItemController>> MOVE_MENU_ITEM = REGISTRY.register(
        "move_menu_item",
        MoveMenuItemController.Factory::new
    );
    public static final DeferredHolder<ActionComponentFactory<?, ?>, ActionComponentFactory<ProtoClickMenuButton, ClickMenuButtonController>> CLICK_MENU_BUTTON = REGISTRY.register(
        "click_menu_button",
        ClickMenuButtonController.Factory::new
    );
    public static final DeferredHolder<ActionComponentFactory<?, ?>, ActionComponentFactory<ProtoPickUpItem, PickUpItemController>> PICK_UP_ITEM = REGISTRY.register(
        "pick_up_item",
        PickUpItemController.Factory::new
    );

    /** 禁止实例化纯注册入口类。 */
    private ActionComponents() {
    }
}
