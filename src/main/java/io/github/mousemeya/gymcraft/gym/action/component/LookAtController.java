package io.github.mousemeya.gymcraft.gym.action.component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.Vec3;

import io.github.mousemeya.gymcraft.gym.ComponentOptionAware;
import io.github.mousemeya.gymcraft.gym.EnvironmentRegionAware;
import io.github.mousemeya.gymcraft.gym.action.AbstractActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentFactory;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoLookAt;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoLookAtBlockTarget;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoLookAtEntityTarget;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoLookAtSlotTarget;
import io.github.mousemeya.gymcraft.gym.describe.TargetDescriber;
import io.github.mousemeya.gymcraft.gym.env.ResetOptionValues;
import io.github.mousemeya.gymcraft.gym.inventory.AgentInventoryLayout;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.MessageSpace;

/**
 * 注视目标动作组件 —— 将 Agent 的当前视线瞬时对准实体或方块，或查询自身物品栏槽位。
 * <p>
 * 实体目标接受已加载、存活的任意 {@link Entity}，普通实体注视眼睛位置，{@link ItemEntity}
 * 注视包围盒中心；方块目标只需位于已加载区块，空气也可作为目标，注视对应方块坐标中心。
 * 视线动作会同步实体朝向、俯仰角和头部朝向，同时向 {@code LookControl} 写入同一目标。
 * 槽位目标使用统一 slot_id 只读查询 Agent 物品栏，不移动视线。成功反馈携带目标描述。
 * 实现了 {@link EnvironmentRegionAware}：环境区域非空时方块目标坐标空间收紧到区域边界。
 * </p>
 * <p>
 * 该动作为瞬时动作，应用后立即返回终态；不限制注视距离，也不要求目标可见。
 * </p>
 */
public class LookAtController extends AbstractActionComponentController<ProtoLookAt>
    implements EnvironmentRegionAware, ComponentOptionAware {

    private static final int DEFAULT_DESCRIPTION_MAX_LINES = 8;
    private static final Set<String> OPTION_KEYS = Set.of("description_max_lines");

    private int descriptionMaxLines = DEFAULT_DESCRIPTION_MAX_LINES;

    /**
     * 创建绑定指定 Agent 的注视动作控制器。
     *
     * @param mob 受控 Agent
     */
    public LookAtController(Mob mob) {
        super(mob);
    }

    /** 设置成功反馈的最大描述行数（必须为正整数）。 */
    public void setDescriptionMaxLines(int descriptionMaxLines) {
        if (descriptionMaxLines < 1) {
            throw new IllegalArgumentException("description_max_lines must be positive, got: " + descriptionMaxLines);
        }
        this.descriptionMaxLines = descriptionMaxLines;
    }

    @Override
    public Set<String> optionKeys() {
        return OPTION_KEYS;
    }

    /** 应用 reset options 参数覆盖：{@code description_max_lines}（正整数）。 */
    @Override
    public void applyOptions(Map<String, Object> options) {
        ComponentOptionAware.rejectUnknownKeys(this.getClass().getSimpleName(), OPTION_KEYS, options);
        if (options.containsKey("description_max_lines")) {
            this.setDescriptionMaxLines(ResetOptionValues.intValue(options, "description_max_lines"));
        }
    }

    /**
     * 返回该动作对应的 protobuf 类型。
     *
     * @return {@link ProtoLookAt} 类型
     */
    @Override
    public Class<ProtoLookAt> protoType() {
        return ProtoLookAt.class;
    }

    /** 按 oneof 分支声明嵌套约束；{@code target} oneof 必须设置分支。 */
    @Override
    public McSpace<ProtoLookAt> defaultSpace() {
        return buildSpace(null);
    }

    /** 接受环境区域（构造期一次性推送）：非空时方块目标坐标空间收紧到区域边界。 */
    @Override
    public void setEnvironmentRegion(@Nullable BoundingBox region) {
        this.setSpace(buildSpace(region));
    }

    /**
     * 构造目标空间：区域非空时 {@code block} 分支 x/y/z 收紧到区域闭区间边界，
     * 实体/槽位分支与 oneof 要求不随区域变化。
     *
     * @param region 环境区域；null 表示无区域约束
     * @return 动作参数空间
     */
    private static McSpace<ProtoLookAt> buildSpace(@Nullable BoundingBox region) {
        return MessageSpace.<ProtoLookAt>builder(ProtoLookAt.getDescriptor())
            .message("block", blockTargetSpace(region))
            .message("entity", MessageSpace.<ProtoLookAtEntityTarget>builder(ProtoLookAtEntityTarget.getDescriptor())
                .range("entity_id", 1, Integer.MAX_VALUE)
                .build())
            .message("slot", MessageSpace.<ProtoLookAtSlotTarget>builder(ProtoLookAtSlotTarget.getDescriptor())
                .range("slot_id", 0, Integer.MAX_VALUE)
                .build())
            .requiredOneof("target")
            .build();
    }

    /**
     * 构造方块目标分支的坐标空间：区域非空时收紧到区域边界，否则全球默认范围。
     *
     * @param region 环境区域；null 表示无区域约束
     * @return 方块目标消息空间
     */
    private static MessageSpace<ProtoLookAtBlockTarget> blockTargetSpace(@Nullable BoundingBox region) {
        var builder = MessageSpace.<ProtoLookAtBlockTarget>builder(ProtoLookAtBlockTarget.getDescriptor());
        if (region != null) {
            builder.range("x", region.minX(), region.maxX())
                .range("y", region.minY(), region.maxY())
                .range("z", region.minZ(), region.maxZ());
        } else {
            builder.range("x", -30_000_000, 30_000_000)
                .range("y", -2048, 2048)
                .range("z", -30_000_000, 30_000_000);
        }
        return builder.build();
    }

    /**
     * 解析目标并立即更新 Agent 视线或查询槽位。
     *
     * @param component 注视动作负载
     * @return 动作应用结果；目标无效时返回 FAILED
     */
    @Override
    public ActionApplyResult apply(ProtoLookAt component) {
        ActionState agentError = this.validateMobForAction();
        if (agentError != null) {
            return ActionApplyResult.none(agentError);
        }

        return switch (component.getTargetCase()) {
            case ENTITY -> this.lookAtEntity(component.getEntity().getEntityId());
            case SLOT -> this.lookAtSlot(component.getSlot().getSlotId());
            case BLOCK -> this.lookAtBlock(new BlockPos(
                component.getBlock().getX(),
                component.getBlock().getY(),
                component.getBlock().getZ()
            ));
            case TARGET_NOT_SET -> ActionApplyResult.none(ActionState.failed("look target is not set"));
        };
    }

    /**
     * 瞬时动作始终保持已应用终态，且不会被运行时 tick。
     *
     * @param component 注视动作负载
     * @return COMPLETED 状态
     */
    @Override
    public ActionState tick(ProtoLookAt component) {
        return ActionState.completed("look at applied");
    }

    /**
     * 注视已加载实体；普通实体注视眼睛，掉落物注视包围盒中心。
     *
     * @param entityId 目标网络实体 ID
     * @return 动作应用结果
     */
    private ActionApplyResult lookAtEntity(int entityId) {
        Mob mob = this.mob();
        Entity found = mob.level().getEntity(entityId);
        if (found == null) {
            return ActionApplyResult.none(ActionState.failed("entity target was not found or is not loaded", Map.of(
                "entity_id", entityId
            )));
        }
        if (found == mob) {
            return ActionApplyResult.none(ActionState.failed("agent cannot look at itself as an entity target", Map.of(
                "entity_id", entityId
            )));
        }
        if (!found.isAlive() || found.isRemoved()) {
            return ActionApplyResult.none(ActionState.failed("entity target is dead or removed", Map.of(
                "entity_id", entityId
            )));
        }
        if (found instanceof ItemEntity item && item.getItem().isEmpty()) {
            return ActionApplyResult.none(ActionState.failed("entity target contains no item stack", Map.of(
                "entity_id", entityId
            )));
        }

        String entityType = BuiltInRegistries.ENTITY_TYPE.getKey(found.getType()).toString();
        String targetId = entityType;
        Vec3 targetPos = EntityAnchorArgument.Anchor.EYES.apply(found);
        var targetDetails = new LinkedHashMap<String, Object>();
        targetDetails.put("target_type", "entity");
        targetDetails.put("entity_id", entityId);
        targetDetails.put("entity_type", entityType);
        if (found instanceof ItemEntity item) {
            targetPos = item.getBoundingBox().getCenter();
            targetId = BuiltInRegistries.ITEM.getKey(item.getItem().getItem()).toString();
            targetDetails.put("item", targetId);
        }
        return this.applyLook(targetPos, targetId, TargetDescriber.describeEntity(found), targetDetails);
    }

    /** 只读查询 Agent 统一物品栏槽位，成功反馈物品描述且不改变视线。 */
    private ActionApplyResult lookAtSlot(int slotId) {
        var layout = AgentInventoryLayout.resolve(this.mob());
        if (slotId < 0 || slotId >= layout.size()) {
            return ActionApplyResult.none(ActionState.failed("slot id out of range", Map.of(
                "slot_id", slotId,
                "layout_size", layout.size()
            )));
        }

        var slot = layout.slot(slotId);
        String category = slot.category();
        List<String> descriptionLines = TargetDescriber.truncate(
            TargetDescriber.describeItemStack(this.mob().level(), slot.getItem()),
            this.descriptionMaxLines
        );
        var details = new LinkedHashMap<String, Object>();
        details.put("target_type", "slot");
        details.put("slot_id", slotId);
        details.put("category", category);
        details.put("target_description", TargetDescriber.join(descriptionLines));
        details.put("target_description_lines", descriptionLines);
        String description = "slot " + slotId + " (" + category + "): " + TargetDescriber.join(descriptionLines);
        return ActionApplyResult.applied(ActionControlPolicy.none(), ActionState.completed(description, details));
    }

    /**
     * 注视已加载方块坐标的中心，允许该位置为空气。
     *
     * @param pos 目标方块坐标
     * @return 动作应用结果
     */
    private ActionApplyResult lookAtBlock(BlockPos pos) {
        var level = this.mob().level();
        if (!level.isLoaded(pos)) {
            return ActionApplyResult.none(ActionState.failed("block target chunk is not loaded", Map.of(
                "pos", pos.toShortString()
            )));
        }
        var state = level.getBlockState(pos);
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return this.applyLook(
            Vec3.atCenterOf(pos),
            blockId,
            TargetDescriber.describeBlock(level, pos, state),
            Map.of(
                "target_type", "block",
                "pos", pos.toShortString(),
                "block", blockId
            )
        );
    }

    /**
     * 同步更新直接旋转和 LookControl，并构造统一成功详情。
     *
     * @param targetPos 世界坐标中的精确注视点
     * @param targetId 目标注册表 ID
     * @param rawDescriptionLines 未截断的目标描述
     * @param targetDetails 目标类型相关详情
     * @return COMPLETED 动作应用结果
     */
    private ActionApplyResult applyLook(
        Vec3 targetPos,
        String targetId,
        List<String> rawDescriptionLines,
        Map<String, Object> targetDetails
    ) {
        Mob mob = this.mob();
        if (mob.getEyePosition().distanceToSqr(targetPos) < 1.0E-12) {
            return ActionApplyResult.none(ActionState.failed("look target coincides with agent eyes"));
        }

        // 直接旋转保证动作瞬时生效；LookControl 保证当前原版实体 tick 不会复位俯仰角。
        ActionLook.apply(mob, targetPos);

        List<String> descriptionLines = TargetDescriber.truncate(rawDescriptionLines, this.descriptionMaxLines);
        var details = new LinkedHashMap<String, Object>(targetDetails);
        details.put("target_x", targetPos.x);
        details.put("target_y", targetPos.y);
        details.put("target_z", targetPos.z);
        details.put("yaw", mob.getYRot());
        details.put("pitch", mob.getXRot());
        details.put("target_description", TargetDescriber.join(descriptionLines));
        details.put("target_description_lines", descriptionLines);
        return ActionApplyResult.applied(
            ActionControlPolicy.none().disableGoalFlags(Goal.Flag.LOOK),
            ActionState.completed("look at " + targetId + ": " + TargetDescriber.join(descriptionLines), details)
        );
    }

    /**
     * 动作工厂 —— 为每个环境创建独立的注视动作控制器。
     */
    public static final class Factory implements ActionComponentFactory<ProtoLookAt, LookAtController> {
        /**
         * 创建绑定指定 Agent 的控制器。
         *
         * @param mob 受控 Agent
         * @return 新控制器实例
         */
        @Override
        public LookAtController create(Mob mob) {
            return new LookAtController(mob);
        }

        /**
         * 返回该工厂创建的具体动作控制器类型。
         *
         * @return LookAtController 的运行时类型
         */
        @Override
        public Class<LookAtController> componentType() {
            return LookAtController.class;
        }
    }
}
