package io.github.mousemeya.gymcraft.gym.visualization;

import com.google.protobuf.Any;
import io.github.mousemeya.gymcraft.GymCraft;
import io.github.mousemeya.gymcraft.gym.EnvManager;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.navigation.MobNavigationTask;
import io.github.mousemeya.gymcraft.gym.action.navigation.NavigationPathProvider;
import io.github.mousemeya.gymcraft.gym.action.proto.*;
import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.observation.proto.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 内置空间组件的可视化适配器目录。
 *
 * <p>按组件注册 ID 维护动作与观测两张转换表：动作适配器把动作输入转为目标几何，
 * 观测适配器把观测负载转为固定图形。扩展可在初始化期间经
 * {@link #registerAction}/{@link #registerObservation} 追加；单个适配器抛出异常只
 * 记录一次告警并返回空图形，绝不影响环境与动作执行。</p>
 */
public final class VisualAdapters {
    /** 动作组件 ID → 只读适配器；LinkedHashMap 保持注册顺序便于排查。 */
    private static final Map<String, ActionVisualAdapter> ACTIONS = new LinkedHashMap<>();
    /** 观测组件 ID → 只读适配器。 */
    private static final Map<String, ObservationVisualAdapter> OBSERVATIONS = new LinkedHashMap<>();
    /** 已告警过的组件 ID；每个组件只记录一次异常，避免日志洪泛。 */
    private static final java.util.Set<String> WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    static {
        registerActions();
        registerObservations();
    }

    /** 禁止实例化静态目录。 */
    private VisualAdapters() { }

    /** @param id 注册 ID @param adapter 只读动作适配器 */
    public static void registerAction(String id, ActionVisualAdapter adapter) { ACTIONS.put(id, adapter); }

    /** @param id 注册 ID @param adapter 只读观测适配器 */
    public static void registerObservation(String id, ObservationVisualAdapter adapter) { OBSERVATIONS.put(id, adapter); }

    /**
     * 把动作转换为候选几何。
     *
     * <p>未注册的组件或无负载的动作返回空列表；适配器异常被吞掉并按组件告警一次，
     * 调用方无需再捕获。</p>
     *
     * @param mob    Agent 实体
     * @param action 当前动作
     * @param state  已有执行状态（供 drop_item 等读取执行结果）
     * @return 有界前的图形列表，可能为空
     */
    public static List<VisualElement> action(Mob mob, ProtoMcAction action, ActionState state) {
        var adapter = ACTIONS.get(action.getComponentId());
        if (adapter == null || !action.hasPayload()) return List.of();
        try {
            return adapter.create(mob, action.getPayload(), state);
        } catch (Exception error) {
            warn(action.getComponentId(), error);
            return List.of();
        }
    }

    /**
     * 把观测负载逐组件转换为固定图形。
     *
     * <p>只遍历给定观测中实际存在的组件；单个组件转换失败不影响其余组件。</p>
     *
     * @param mob         采样实体
     * @param observation 实际返回给 RL 侧的观测
     * @return 图形列表，可能为空
     */
    public static List<VisualElement> observation(Mob mob, ProtoMcObservation observation) {
        var result = new ArrayList<VisualElement>();
        observation.getComponentsMap().forEach((id, payload) -> {
            var adapter = OBSERVATIONS.get(id);
            if (adapter != null) {
                try {
                    result.addAll(adapter.create(mob, payload));
                } catch (Exception error) {
                    warn(id, error);
                }
            }
        });
        return result;
    }

    /** @param id 组件 ID @param error 转换异常；每个组件只记录一次以避免日志洪泛 */
    private static void warn(String id, Exception error) {
        if (WARNED.add(id)) GymCraft.LOGGER.warn("Visualization adapter failed for {}", id, error);
    }

    /**
     * 注册十三种空间动作适配器。
     *
     * <p>几何表达动作的输入目标（方块/实体/方向）；寻路类动作（move_to/swim_to/
     * pick_up_item）在运行中额外绘制 controller 持有的实际规划路径，路径不可用时
     * 退回目标点与视线连线。与移动、朝向相关的箭头使用与原版输入变换一致的数学，
     * 保证视觉方向可信。</p>
     */
    private static void registerActions() {
        // 破坏/放置：目标方块线框 + 视线连线。
        registerAction("gymcraft:break_block", (mob, payload, state) -> {
            var p = payload.unpack(ProtoBreakBlock.class);
            return VisualGeometry.block(mob, "gymcraft:break_block", new Vec3(p.getX(), p.getY(), p.getZ()));
        });
        registerAction("gymcraft:set_block", (mob, payload, state) -> {
            var p = payload.unpack(ProtoSetBlock.class);
            return VisualGeometry.block(mob, "gymcraft:set_block", new Vec3(p.getX(), p.getY(), p.getZ()));
        });
        // 寻路类：目标点 + 实际规划路径（无活动路径时退回目标点 + 视线连线）。
        // move_to/swim_to 目标点与 controller 一致修正到方块中心，避免画在边角。
        registerAction("gymcraft:move_to", (mob, payload, state) -> {
            var p = payload.unpack(ProtoMoveTo.class);
            return navigation(mob, "gymcraft:move_to", MobNavigationTask.blockCenterTarget(p.getX(), p.getY(), p.getZ()));
        });
        registerAction("gymcraft:swim_to", (mob, payload, state) -> {
            var p = payload.unpack(ProtoSwimTo.class);
            return navigation(mob, "gymcraft:swim_to", MobNavigationTask.blockCenterTarget(p.getX(), p.getY(), p.getZ()));
        });
        registerAction("gymcraft:jump", (mob, payload, state) -> {
            payload.unpack(ProtoJump.class);
            return arrow(mob, "gymcraft:jump", new Vec3(0, 1, 0));
        });
        registerAction("gymcraft:step_move", (mob, payload, state) -> {
            var p = payload.unpack(ProtoStepMove.class);
            // apply 前采样输入方向，终态复用该结果，避免 yaw_delta 被重复计算。
            double yaw = Math.toRadians(mob.getYRot() + p.getYawDelta());
            Vec3 direction = new Vec3(-Math.sin(yaw) * p.getForward() + Math.cos(yaw) * p.getStrafeRight(),
                p.getJump() ? 1 : 0, Math.cos(yaw) * p.getForward() + Math.sin(yaw) * p.getStrafeRight());
            return arrow(mob, "gymcraft:step_move", direction);
        });
        registerAction("gymcraft:look_at", VisualAdapters::lookAt);
        registerAction("gymcraft:open_menu", VisualAdapters::openMenu);
        registerAction("gymcraft:use_item", VisualAdapters::useItem);
        // 攻击/拾取/设目标：目标实体框 + 连线（设目标支持 UUID 回退解析）；
        // 拾取在寻路接近期间同时绘制实际规划路径。
        registerAction("gymcraft:attack_once", (mob, payload, state) -> VisualGeometry.entity(mob, "gymcraft:attack_once",
            mob.level().getEntity(payload.unpack(ProtoAttackOnce.class).getTargetEntityId()), false));
        registerAction("gymcraft:pick_up_item", (mob, payload, state) -> {
            var elements = new ArrayList<>(VisualGeometry.entity(mob, "gymcraft:pick_up_item",
                mob.level().getEntity(payload.unpack(ProtoPickUpItem.class).getEntityId()), false));
            elements.addAll(VisualGeometry.path("gymcraft:pick_up_item", mob, currentPath(mob, "gymcraft:pick_up_item")));
            return elements;
        });
        registerAction("gymcraft:set_attack_target", (mob, payload, state) -> {
            var p = payload.unpack(ProtoSetAttackTarget.class);
            // 网络 ID 优先；非生物目标或 ID 未命中时回退观测中的 UUID 解析。
            Entity target = p.getTargetEntityId() > 0 ? mob.level().getEntity(p.getTargetEntityId()) : null;
            if (!(target instanceof net.minecraft.world.entity.LivingEntity) && !p.getTargetUuid().isEmpty() && mob.level() instanceof ServerLevel level) {
                target = level.getEntity(UUID.fromString(p.getTargetUuid()));
            }
            return VisualGeometry.entity(mob, "gymcraft:set_attack_target", target, false);
        });
        // 丢出物品：从执行结果的 details 中读取新生成的掉落物实体 ID。
        registerAction("gymcraft:drop_item", (mob, payload, state) -> {
            payload.unpack(ProtoDropItem.class);
            Object id = state.details().get("entity_id");
            return id instanceof Number number ? VisualGeometry.entity(mob, "gymcraft:drop_item",
                mob.level().getEntity(number.intValue()), false) : List.of();
        });
    }

    /** @param mob Agent @param payload 注视参数 @param state 已有状态 @return 注视目标和眼睛连线 */
    private static List<VisualElement> lookAt(Mob mob, Any payload, ActionState state) throws Exception {
        var p = payload.unpack(ProtoLookAt.class);
        return switch (p.getTargetCase()) {
            case BLOCK -> VisualGeometry.block(mob, "gymcraft:look_at", new Vec3(p.getBlock().getX(), p.getBlock().getY(), p.getBlock().getZ()));
            case ENTITY -> VisualGeometry.entity(mob, "gymcraft:look_at", mob.level().getEntity(p.getEntity().getEntityId()), true);
            case ITEM -> VisualGeometry.entity(mob, "gymcraft:look_at", mob.level().getEntity(p.getItem().getEntityId()), false);
            default -> List.of();
        };
    }

    /** @param mob Agent @param payload 菜单参数 @param state 已有状态 @return 菜单目标标记 */
    private static List<VisualElement> openMenu(Mob mob, Any payload, ActionState state) throws Exception {
        var p = payload.unpack(ProtoOpenMenu.class);
        return switch (p.getTargetCase()) {
            case BLOCK -> VisualGeometry.block(mob, "gymcraft:open_menu", new Vec3(p.getBlock().getX(), p.getBlock().getY(), p.getBlock().getZ()));
            case ENTITY -> VisualGeometry.entity(mob, "gymcraft:open_menu", mob.level().getEntity(p.getEntity().getEntityId()), false);
            case SELF -> VisualGeometry.point(mob, "gymcraft:open_menu", mob.getEyePosition());
            default -> List.of();
        };
    }

    /** @param mob Agent @param payload 使用参数 @param state 已有状态 @return 使用目标标记 */
    private static List<VisualElement> useItem(Mob mob, Any payload, ActionState state) throws Exception {
        var p = payload.unpack(ProtoUseItem.class);
        return switch (p.getTargetCase()) {
            case BLOCK -> VisualGeometry.block(mob, "gymcraft:use_item", new Vec3(p.getBlock().getX(), p.getBlock().getY(), p.getBlock().getZ()));
            case ENTITY -> VisualGeometry.entity(mob, "gymcraft:use_item", mob.level().getEntity(p.getEntity().getEntityId()), false);
            default -> VisualGeometry.point(mob, "gymcraft:use_item", mob.getEyePosition());
        };
    }

    /** @param mob Agent @param id 组件 @param direction 输入方向 @return 从实体中心出发的箭头 */
    private static List<VisualElement> arrow(Mob mob, String id, Vec3 direction) {
        Vec3 start = mob.getBoundingBox().getCenter();
        return List.of(VisualGeometry.shape(id, false, VisualElement.Shape.ARROW, start, start.add(direction), id));
    }

    /**
     * 寻路类动作的目标标记与实时路径组合：存在跟随中的路径时绘制路径线段并省去
     * 视线连线（路径已从 Mob 脚下出发），规划失败、末段直行等无路径时刻退回
     * 目标点与视线连线。
     *
     * @param mob    Agent
     * @param id     组件 ID
     * @param target 动作请求目标
     * @return 目标点与路径线段图形
     */
    private static List<VisualElement> navigation(Mob mob, String id, Vec3 target) {
        List<VisualElement> path = VisualGeometry.path(id, mob, currentPath(mob, id));
        if (path.isEmpty()) {
            return VisualGeometry.point(mob, id, target);
        }
        var elements = new ArrayList<VisualElement>(path.size() + 1);
        elements.add(VisualGeometry.target(id, target));
        elements.addAll(path);
        return elements;
    }

    /**
     * 经环境解析寻路动作 controller 持有的实际规划路径；环境未注册、组件缺失或
     * 组件不是寻路动作时返回 null，只读取既有状态，绝不触碰导航或世界。
     *
     * @param mob         Agent 实体
     * @param componentId 组件注册 ID
     * @return 正在跟随的规划路径，可能为 null
     */
    @Nullable
    private static Path currentPath(Mob mob, String componentId) {
        if (!(EnvManager.get(mob.getUUID()).orElse(null) instanceof AbstractMcEnv env)) return null;
        if (!(env.findActionComponent(componentId).orElse(null) instanceof NavigationPathProvider provider)) return null;
        return provider.currentPath();
    }

    /**
     * 注册六种空间观测适配器。
     *
     * <p>全部位置取自给定 protobuf 快照，绝不重扫世界或读取实时实体位置，保证
     * 可视化与 RL 看到的数据一致。</p>
     */
    private static void registerObservations() {
        registerObservation("gymcraft:nearby_blocks", (mob, p) -> blocks("gymcraft:nearby_blocks", p.unpack(ProtoNearbyBlocks.class).getBlocksList()));
        registerObservation("gymcraft:interesting_blocks", (mob, p) -> blocks("gymcraft:interesting_blocks", p.unpack(ProtoInterestingBlocks.class).getBlocksList()));
        registerObservation("gymcraft:nearby_entities", (mob, payload) -> {
            var result = new ArrayList<VisualElement>();
            for (var e : payload.unpack(ProtoNearbyEntities.class).getEntitiesList()) {
                result.add(VisualGeometry.observedEntity(mob, "gymcraft:nearby_entities", e.getEntityId(), e.getUuid(),
                    new Vec3(e.getX(), e.getY(), e.getZ()), e.getEntityType()));
            }
            return result;
        });
        registerObservation("gymcraft:nearby_items", (mob, payload) -> {
            var result = new ArrayList<VisualElement>();
            for (var e : payload.unpack(ProtoNearbyItems.class).getItemsList()) {
                result.add(VisualGeometry.observedEntity(mob, "gymcraft:nearby_items", e.getEntityId(), e.getUuid(),
                    new Vec3(e.getX(), e.getY(), e.getZ()), "item #" + e.getEntityId()));
            }
            return result;
        });
        // 自身的位置、朝向和速度都来自同一份快照，不从当前实体反推。
        registerObservation("gymcraft:self", (mob, payload) -> {
            var p = payload.unpack(ProtoSelfState.class);
            Vec3 position = new Vec3(p.getX(), p.getY(), p.getZ());
            return List.of(VisualGeometry.shape("gymcraft:self", true, VisualElement.Shape.POINT, position, position, "self"),
                VisualGeometry.shape("gymcraft:self", true, VisualElement.Shape.ARROW, position,
                    position.add(Vec3.directionFromRotation(p.getPitch(), p.getYaw())), "look"),
                VisualGeometry.shape("gymcraft:self", true, VisualElement.Shape.ARROW, position,
                    position.add(p.getVx(), p.getVy(), p.getVz()), "velocity"));
        });
        registerObservation("gymcraft:world", (mob, payload) -> {
            var result = new ArrayList<VisualElement>();
            for (var structure : payload.unpack(ProtoWorldState.class).getStructuresList()) {
                Vec3 position = new Vec3(structure.getX(), structure.getY(), structure.getZ());
                result.add(VisualGeometry.shape("gymcraft:world", true, VisualElement.Shape.POINT, position, position, structure.getStructureId()));
            }
            return result;
        });
    }

    /** @param id 组件 ID @param blocks 已返回的方块 @return 固定方块线框 */
    private static List<VisualElement> blocks(String id, List<ProtoBlockView> blocks) {
        return blocks.stream().map(block -> {
            Vec3 point = new Vec3(block.getX(), block.getY(), block.getZ());
            return VisualGeometry.box(id, true, new AABB(point, point.add(1, 1, 1)), block.getBlockId());
        }).toList();
    }
}
