package io.github.mousemeya.gymcraft.gym.action.component;

import io.github.mousemeya.gymcraft.gym.component.EnvironmentRegionAware;
import io.github.mousemeya.gymcraft.gym.space.CoordinateSpaces;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import javax.annotation.Nullable;

import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.AbstractActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentFactory;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoOpenMenu;
import io.github.mousemeya.gymcraft.gym.menu.session.LogicalMenuSession;
import io.github.mousemeya.gymcraft.gym.menu.session.LogicalMenuSessions;
import io.github.mousemeya.gymcraft.gym.menu.session.MenuSessionHooks;
import io.github.mousemeya.gymcraft.gym.menu.MenuTypeUtil;
import io.github.mousemeya.gymcraft.gym.menu.session.OpenMenuTarget;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;

/**
 * 打开菜单动作组件 —— 为 Agent 打开指定目标的逻辑菜单会话。
 * <p>
 * 目标由 {@code ProtoOpenMenu} 的 oneof 指定：方块（x/y/z 坐标，经
 * {@code BlockState#getMenuProvider} 解析）、实体（网络实体 ID，菜单实体/商人/马）
 * 或自身背包。实际打开语义（候选验证、旧会话替换、会话 ID 分配）全部由
 * {@link LogicalMenuSessions#open} 承载，本控制器只负责 proto → {@link OpenMenuTarget}
 * 转换与结果上报。该动作为瞬时动作，应用后立即返回终态。
 * </p>
 */
public class OpenMenuController extends AbstractActionComponentController<ProtoOpenMenu> implements EnvironmentRegionAware {

    private static final McSpace<Map<String, Object>> DEFAULT_SPACE = new DictSpace(Map.of(
        "x", new BoxSpace(-30_000_000, 30_000_000, 1),
        "y", new BoxSpace(-2048, 2048, 1),
        "z", new BoxSpace(-30_000_000, 30_000_000, 1),
        "entity_id", new BoxSpace(0, Integer.MAX_VALUE, 1)
    ));

    /**
     * 创建并初始化组件内部状态。
     *
     * @param mob 受控生物
     */
    public OpenMenuController(Mob mob) {
        super(mob);
    }

    /**
     * 返回本组件的 protobuf 消息类型。
     *
     * @return 协议消息类
     */
    @Override
    public Class<ProtoOpenMenu> protoType() {
        return ProtoOpenMenu.class;
    }

    /**
     * 返回本组件默认参数空间，供实例初始化及结构校验使用。
     *
     * @return 完整默认参数空间
     */
    @Override
    public McSpace<Map<String, Object>> defaultSpace() {
        return DEFAULT_SPACE;
    }

    /**
     * 校验当前动作分支实际使用的字段；实体位置由执行入口独立验证。
     *
     * @param component 当前动作负载
     * @return 当前分支参数是否有效
     */
    @Override
    public boolean contains(ProtoOpenMenu component) {
        if (component == null) {
            return false;
        }
        // 只校验当前 oneof 分支实际使用的字段，自身操作不检查坐标。
        return switch (component.getTargetCase()) {
            case BLOCK -> ((DictSpace) this.space()).containsFields(Map.of(
                "x", new double[] { component.getBlock().getX() },
                "y", new double[] { component.getBlock().getY() },
                "z", new double[] { component.getBlock().getZ() }
            ));
            case ENTITY -> ((DictSpace) this.space()).containsFields(Map.of(
                "entity_id", new double[] { component.getEntity().getEntityId() }
            ));
            case SELF -> true;
            case TARGET_NOT_SET -> false;
        };
    }

    /**
     * 解析并执行动作，目标越界时在产生副作用前返回失败。
     *
     * @param component 当前动作负载
     * @return 动作策略及初始状态
     */
    @Override
    public ActionApplyResult apply(ProtoOpenMenu component) {
        Mob mob = this.mob();
        if (component != null && component.hasBlock()
            && !this.containsTarget(component.getBlock().getX(), component.getBlock().getY(), component.getBlock().getZ())) {
            return ActionApplyResult.none(this.outsideTarget(component.getBlock().getX(), component.getBlock().getY(), component.getBlock().getZ()));
        }
        if (!this.contains(component)) {
            return ActionApplyResult.none(ActionState.failed("invalid action payload"));
        }
        ActionState agentError = this.validateMobForAction();
        if (agentError != null) {
            return ActionApplyResult.none(agentError);
        }
        if (component.hasEntity()) {
            var entity = mob.level().getEntity(component.getEntity().getEntityId());
            if (entity != null) {
                ActionState boundaryError = this.checkTargetEntity(entity);
                if (boundaryError != null) return ActionApplyResult.none(boundaryError);
            }
        }
        OpenMenuTarget target = toTarget(component);
        if (target == null) {
            return ActionApplyResult.none(ActionState.failed("menu target is not set"));
        }
        LogicalMenuSessions.OpenResult result = LogicalMenuSessions.open(mob, target);
        if (!result.success() || result.session() == null) {
            return ActionApplyResult.none(ActionState.failed(
                "open menu failed: " + result.failureReason(),
                Map.of("target", describeTarget(target))
            ));
        }
        LogicalMenuSession session = result.session();
        animateOpen(mob, target);
        return ActionApplyResult.applied(ActionControlPolicy.none(), ActionState.completed(
            "menu opened: " + describeTarget(target) + " session_id=" + session.sessionId(),
            Map.of(
                "session_id", session.sessionId(),
                "menu_type", MenuTypeUtil.idOf(session.menu()),
                "title", session.title(),
                "self_menu", session.isSelfMenu(),
                "target", describeTarget(target)
            )
        ));
    }

    /**
     * 瞬时动作不会被运行时 tick；实现仅返回已应用终态以满足接口契约。
     *
     * @param component 当前动作
     * @return 完成状态
     */
    @Override
    public ActionState tick(ProtoOpenMenu component) {
        return ActionState.completed("open menu applied");
    }

    /**
     * reset/关闭时关闭当前菜单会话；生产路径中会话只能由本组件创建，
     * 故此处单点释放（底层 {@code closeOnce} 幂等）。
     */
    @Override
    public void release(String reason) {
        MenuSessionHooks.closeFor(this.mob(), reason);
    }

    /** proto oneof → 菜单目标模型；oneof 未设置时返回 null（contains 已拦截，此处为防御）。 */
    private static OpenMenuTarget toTarget(ProtoOpenMenu component) {
        return switch (component.getTargetCase()) {
            case BLOCK -> {
                var block = component.getBlock();
                yield new OpenMenuTarget.Block(new BlockPos(block.getX(), block.getY(), block.getZ()));
            }
            // 实体解析（加载/存活校验）在 LogicalMenuSessions 的 resolver 内完成
            case ENTITY -> new OpenMenuTarget.Entity(component.getEntity().getEntityId());
            case SELF -> new OpenMenuTarget.Self();
            case TARGET_NOT_SET -> null;
        };
    }

    /**
     * 返回菜单目标的可读描述。
     *
     * @param target 已解析目标
     * @return 可读目标描述
     */
    private static String describeTarget(OpenMenuTarget target) {
        return switch (target) {
            case OpenMenuTarget.Block block -> "block " + block.pos().toShortString();
            case OpenMenuTarget.Entity entity -> "entity #" + entity.entityId();
            case OpenMenuTarget.Self ignored -> "self";
        };
    }

    /**
     * 为世界中的菜单目标播放玩家式转向与右手交互动画。
     * 自身背包由玩家按键直接打开，原版不会挥手，因此保持静止。
     *
     * @param mob 执行动作的 Agent
     * @param target 已成功打开的菜单目标
     */
    private static void animateOpen(Mob mob, OpenMenuTarget target) {
        Vec3 lookTarget = switch (target) {
            case OpenMenuTarget.Block block -> Vec3.atCenterOf(block.pos());
            case OpenMenuTarget.Entity entity -> {
                var targetEntity = mob.level().getEntity(entity.entityId());
                yield targetEntity == null ? null : targetEntity.getEyePosition();
            }
            case OpenMenuTarget.Self ignored -> null;
        };
        if (lookTarget != null) {
            ActionLook.apply(mob, lookTarget);
            mob.swing(InteractionHand.MAIN_HAND);
        }
    }


    /** @param region 构造期默认区域；环境先注入区域，再应用具体环境的空间配置 */
    @Override
    public void initializeDefaultRegion(@Nullable BoundingBox region) {
        if (region != null) {
            this.setSpace(CoordinateSpaces.withRegion(this.space(), region, true));
        }
    }

    /**
     * @param x 目标 X
     * @param y 目标 Y
     * @param z 目标 Z
     * @return 是否落在本组件当前坐标空间
     */
    private boolean containsTarget(double x, double y, double z) {
        return ((DictSpace) this.space()).containsFields(Map.of(
            "x", new double[] {x}, "y", new double[] {y}, "z", new double[] {z}));
    }

    /**
     * @param x 目标 X
     * @param y 目标 Y
     * @param z 目标 Z
     * @return 本组件的越界失败及空间诊断
     */
    private ActionState outsideTarget(double x, double y, double z) {
        return ActionState.failed("target outside action space", CoordinateSpaces.details(this.space(), x, y, z));
    }

    /**
     * @param target 已解析实体
     * @return 维度或位置不符合本组件空间时的失败；合法返回 null
     */
    private ActionState checkTargetEntity(net.minecraft.world.entity.Entity target) {
        if (target.level() != this.mob().level()) {
            return ActionState.failed("target entity is in another dimension", Map.of("entity_id", target.getId()));
        }
        return this.containsTarget(target.getX(), target.getY(), target.getZ())
            ? null : this.outsideTarget(target.getX(), target.getY(), target.getZ());
    }

    /**
     * 动作工厂 —— 注册表引用该内部轻量 {@link ActionComponentFactory}，而非目标类构造函数。
     */
    public static final class Factory implements ActionComponentFactory<ProtoOpenMenu, OpenMenuController> {
        /**
         * 为指定受控生物创建独立组件实例。
         *
         * @param mob 受控生物
         * @return 新组件实例
         */
        @Override
        public OpenMenuController create(Mob mob) {
            return new OpenMenuController(mob);
        }

        /**
         * 返回该工厂创建的具体动作控制器类型。
         *
         * @return OpenMenuController 的运行时类型
         */
        @Override
        public Class<OpenMenuController> componentType() {
            return OpenMenuController.class;
        }
    }
}
