package io.github.mousemeya.gymcraft.gym.action.component;

import java.util.Map;
import java.util.LinkedHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.ZombieVillager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import io.github.mousemeya.gymcraft.gym.action.AbstractActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentFactory;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoUseItem;
import io.github.mousemeya.gymcraft.gym.inventory.AgentInventoryLayout;
import io.github.mousemeya.gymcraft.gym.fakeplayer.AgentInventoryBridge;
import io.github.mousemeya.gymcraft.gym.fakeplayer.AgentInventoryTransaction;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.registry.ModAttachments;

/**
 * 按 Agent 槽号使用物品；外部目标先校验距离和视线，再转向并执行原版持物交互入口。
 * 目标不接受直接交互的物品（如投掷物）回退为面向目标的普通使用。
 * 瞬时交互通过独立玩家桥接，食物药水由 Mob 原版 tick 消费；不执行目标自身交互。
 */
public final class UseItemController extends AbstractActionComponentController<ProtoUseItem> {
    private static final McSpace<Map<String, Object>> DEFAULT_SPACE = new DictSpace(Map.of(
        "slot_id", new BoxSpace(0, Integer.MAX_VALUE, 1),
        "x", new BoxSpace(-30_000_000, 30_000_000, 1),
        "y", new BoxSpace(-2048, 2048, 1),
        "z", new BoxSpace(-30_000_000, 30_000_000, 1),
        "entity_id", new BoxSpace(0, Integer.MAX_VALUE, 1)
    ));
    private double blockReachDistance = 4.5;
    private double entityReachDistance = 3.0;
    private ActionState state = ActionState.failed("use item has not started");
    private Map<String, Object> details = Map.of();
    @Nullable private UseItemConsumption consumption;

    /**
     * @param mob 绑定的受控生物
     */
    public UseItemController(Mob mob) { super(mob); }

    /**
     * @param distance 方块交互距离，必须是有限正数
     */
    public void setBlockReachDistance(double distance) {
        validateDistance(distance);
        this.blockReachDistance = distance;
    }

    /**
     * @param distance 实体交互距离，必须是有限正数
     */
    public void setEntityReachDistance(double distance) {
        validateDistance(distance);
        this.entityReachDistance = distance;
    }

    /**
     * @param distance 待校验距离，无效时抛出参数异常
     */
    private static void validateDistance(double distance) {
        if (!Double.isFinite(distance) || distance <= 0) {
            throw new IllegalArgumentException("reach distance must be positive and finite");
        }
    }

    /** @return 对应协议类型 */
    @Override public Class<ProtoUseItem> protoType() { return ProtoUseItem.class; }

    /** @return 扁平化槽号与目标空间 */
    @Override public McSpace<Map<String, Object>> defaultSpace() { return DEFAULT_SPACE; }

    /**
     * @param action 协议动作
     * @return 是否显式指定槽号且数值有效
     */
    @Override public boolean contains(ProtoUseItem action) {
        return action != null && action.hasSlotId()
            && (!action.hasEntity() || action.getEntity().getEntityId() > 0)
            && this.space().contains(Map.of(
                "slot_id", new double[] { action.getSlotId() },
                "x", new double[] { action.getBlock().getX() },
                "y", new double[] { action.getBlock().getY() },
                "z", new double[] { action.getBlock().getZ() },
                "entity_id", new double[] { action.getEntity().getEntityId() }
            ));
    }

    /**
     * @param action 来源与可选目标
     * @return 即时结果或持续消费状态
     */
    @Override public ActionApplyResult apply(ProtoUseItem action) {
        this.onInterrupt(action);
        this.consumption = null;
        this.details = Map.of("slot_id", action.getSlotId(), "target", action.getTargetCase().name());
        ActionState error = this.validateMobForAction();
        if (error != null) {
            this.state = error;
            return ActionApplyResult.none(error);
        }
        if (!this.contains(action)) {
            return this.fail("invalid use item payload");
        }
        var layout = AgentInventoryLayout.resolve(this.mob());
        if (action.getSlotId() >= layout.size()) {
            return this.fail("slot id out of range");
        }
        ItemStack stack = layout.slot(action.getSlotId()).getItem();
        if (stack.isEmpty()) {
            return this.fail("source slot is empty");
        }
        var targetDetails = new LinkedHashMap<String, Object>();
        targetDetails.put("slot_id", action.getSlotId());
        targetDetails.put("item_id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        targetDetails.put("target", action.hasBlock() ? "block" : action.hasEntity() ? "entity" : "self");
        if (action.hasBlock()) {
            targetDetails.put("x", action.getBlock().getX());
            targetDetails.put("y", action.getBlock().getY());
            targetDetails.put("z", action.getBlock().getZ());
        } else if (action.hasEntity()) {
            targetDetails.put("entity_id", action.getEntity().getEntityId());
        }
        this.details = Map.copyOf(targetDetails);
        if (this.mob().isUsingItem() || this.mob().hasData(ModAttachments.ITEM_CONSUMPTION)) {
            return this.fail("agent is already using an item");
        }

        // 所有目标检查在临时挪动物品之前执行，失败不改变物品栏。
        try {
            BlockHitResult blockHit = action.hasBlock() ? this.blockTarget(action) : null;
            LivingEntity entity = action.hasEntity() ? this.entityTarget(action) : null;
            // 1.21.1 无 CONSUMABLE 组件：FOOD 组件或 EAT/DRINK 使用动作（药水、蜂蜜等饮品）都走消费会话
            if (!action.hasBlock() && !action.hasEntity() && this.isConsumable(stack)) {
                // 提前验证桥接容量，保证跨 tick 消费结束后一定能建立清算桥接。
                AgentInventoryBridge.validateCapacity(this.mob());
                this.consumption = new UseItemConsumption(this.mob(), action.getSlotId());
                this.consumption.start();
                this.state = ActionState.running("consuming item", this.details);
                this.tick(action);
                return ActionApplyResult.applied(this.policy(), this.state);
            }
            InteractionResult result;
            try (var inventory = AgentInventoryTransaction.open(this.mob(), action.getSlotId())) {
                if (!action.hasBlock() && !action.hasEntity() && stack.getUseDuration(inventory.player()) > 0) {
                    return this.fail("non-consumable sustained use is not supported");
                }
                inventory.stageSelectedInMainHand();
                try {
                    result = this.use(inventory, blockHit, entity);
                } finally {
                    inventory.player().stopUsingItem();
                }
            }
            this.state = result.consumesAction() ? ActionState.completed("item used", this.details)
                : ActionState.failed("item did not accept target", this.details);
            return ActionApplyResult.applied(this.policy(), this.state);
        } catch (Exception exception) {
            if (this.consumption != null) {
                this.consumption.close();
            }
            return this.fail("use item failed: " + exception.getMessage());
        }
    }

    /** @return 消费期间阻止自主移动、转头和跳跃的控制策略 */
    private ActionControlPolicy policy() {
        return this.consumption != null ? ActionControlPolicy.disableVanillaAi()
            : ActionControlPolicy.none().disableGoalFlags(Goal.Flag.LOOK);
    }

    /**
     * @param reason 失败原因
     * @return 同步失败结果
     */
    private ActionApplyResult fail(String reason) {
        this.state = ActionState.failed(reason, this.details);
        return ActionApplyResult.none(this.state);
    }

    /**
     * @param action 方块目标动作
     * @return 含真实命中面的射线结果，无效时抛出异常
     */
    private BlockHitResult blockTarget(ProtoUseItem action) {
        var target = action.getBlock();
        BlockPos pos = new BlockPos(target.getX(), target.getY(), target.getZ());
        if (!this.mob().level().isLoaded(pos)) {
            throw new IllegalArgumentException("block target chunk is not loaded");
        }
        Vec3 point = Vec3.atCenterOf(pos);
        BlockHitResult hit = this.clip(point);
        // 幼苗等矮轮廓不覆盖方块中心；从真实形状中寻找可见注视点，仍只接受目标自身命中。
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)) {
            var shape = this.mob().level().getBlockState(pos).getShape(this.mob().level(), pos);
            for (var box : shape.toAabbs()) {
                Vec3 candidate = box.getCenter().add(pos.getX(), pos.getY(), pos.getZ());
                BlockHitResult candidateHit = this.clip(candidate);
                if (candidateHit.getType() == HitResult.Type.BLOCK && candidateHit.getBlockPos().equals(pos)) {
                    point = candidate;
                    hit = candidateHit;
                    break;
                }
            }
        }
        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)) {
            throw new IllegalArgumentException("block target is obstructed or has no hit shape");
        }
        this.checkReach(hit.getLocation(), this.blockReachDistance);
        ActionLook.apply(this.mob(), point);
        return hit;
    }

    /**
     * @param action 实体目标动作
     * @return 已验证的活实体，无效时抛出异常
     */
    private LivingEntity entityTarget(ProtoUseItem action) {
        var found = this.mob().level().getEntity(action.getEntity().getEntityId());
        if (!(found instanceof LivingEntity entity) || entity == this.mob() || !entity.isAlive() || entity.isRemoved()) {
            throw new IllegalArgumentException("entity target is not a valid living entity");
        }
        Vec3 eyes = this.mob().getEyePosition();
        Vec3 point = entity.getEyePosition();
        Vec3 hit = entity.getBoundingBox().clip(eyes, point).orElse(point);
        this.checkReach(hit, this.entityReachDistance);
        BlockHitResult block = this.clip(point);
        if (block.getType() != HitResult.Type.MISS && eyes.distanceToSqr(block.getLocation()) < eyes.distanceToSqr(hit)) {
            throw new IllegalArgumentException("entity target is obstructed");
        }
        for (var other : this.mob().level().getEntities(this.mob(), this.mob().getBoundingBox().expandTowards(point.subtract(eyes)).inflate(1),
            candidate -> candidate != entity && candidate.isPickable() && !candidate.isSpectator())) {
            var intercept = other.getBoundingBox().clip(eyes, point);
            if (intercept.isPresent() && eyes.distanceToSqr(intercept.get()) < eyes.distanceToSqr(hit)) {
                throw new IllegalArgumentException("entity target is obstructed by another entity");
            }
        }
        ActionLook.apply(this.mob(), point);
        return entity;
    }

    /**
     * @param point 命中位置
     * @param reach 最大距离；超距或与眼睛重合时抛异常
     */
    private void checkReach(Vec3 point, double reach) {
        double distance = this.mob().getEyePosition().distanceToSqr(point);
        if (distance < 1.0E-12 || distance > reach * reach) {
            throw new IllegalArgumentException("target is outside interaction reach");
        }
    }

    /**
     * @param point 射线终点
     * @return 从 Mob 眼睛出发的方块轮廓命中
     */
    private BlockHitResult clip(Vec3 point) {
        return this.mob().level().clip(new ClipContext(this.mob().getEyePosition(), point,
            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, this.mob()));
    }

    /**
     * @param inventory 玩家桥接
     * @param block 方块命中或 null
     * @param entity 实体或 null
     * @return 原版持物交互结果
     */
    private InteractionResult use(AgentInventoryTransaction inventory, @Nullable BlockHitResult block, @Nullable LivingEntity entity) {
        ItemStack stack = inventory.player().getMainHandItem();
        InteractionResult result;
        if (block != null) {
            // 原版右键方块先让方块处理手中物品；堆肥桶的投入逻辑位于此入口。
            // NeoForge 1.21.1 的 useItemOn 返回 ItemInteractionResult，经 result() 转回原版枚举。
            result = this.mob().level().getBlockState(block.getBlockPos()).useItemOn(
                stack, this.mob().level(), inventory.player(), InteractionHand.MAIN_HAND, block
            ).result();
            if (!result.consumesAction()) {
                result = stack.useOn(new UseOnContext(inventory.player(), InteractionHand.MAIN_HAND, block));
            }
        } else if (entity != null) {
            // 由目标实体自身处理的持物交互白名单：僵尸村民的金苹果治愈、铁傀儡的铁锭治疗；
            // 仅开放这两个组合，避免触发交易或骑乘。
            if (entity instanceof ZombieVillager && stack.is(Items.GOLDEN_APPLE)
                || entity instanceof IronGolem && stack.is(Items.IRON_INGOT)) {
                result = entity.interact(inventory.player(), InteractionHand.MAIN_HAND);
            } else {
                result = stack.interactLivingEntity(inventory.player(), entity, InteractionHand.MAIN_HAND);
            }
        } else {
            result = this.useSustained(stack, inventory);
        }
        // 目标不接受直接交互时（如投掷物），保持已转向目标的姿态按普通使用执行，与原版右键落空后继续使用物品一致。
        if ((block != null || entity != null) && !result.consumesAction()) {
            result = this.useSustained(stack, inventory);
        }
        // 1.21.1 无 InteractionResult.Success#heldItemTransformedTo：普通交互直接原地修改传入堆栈，
        // 桥接玩家主手已同步，无需额外写回。
        if (inventory.player().isUsingItem()) {
            throw new IllegalArgumentException("non-consumable sustained use is not supported");
        }
        return result;
    }

    /**
     * @param stack 待使用的堆栈
     * @param eater 使用者（桥接玩家）
     * @return 该物品是否应进入跨 tick 消费会话（食物/饮品）
     */
    private boolean isConsumable(ItemStack stack) {
        if (stack.has(DataComponents.FOOD)) {
            return true;
        }
        // 1.21.1 的 ItemStack#getUseAnimation 无参
        net.minecraft.world.item.UseAnim animation = stack.getUseAnimation();
        return animation == net.minecraft.world.item.UseAnim.EAT
            || animation == net.minecraft.world.item.UseAnim.DRINK;
    }

    /**
     * 普通使用（饮食/投掷等）。1.21.1 的 {@code ItemStack#use} 返回
     * {@link InteractionResultHolder}，需要把用后堆栈（如汤碗）写回主手。
     *
     * @param stack 使用前的主手堆栈
     * @param inventory 桥接事务
     * @return 原版交互结果
     */
    private InteractionResult useSustained(ItemStack stack, AgentInventoryTransaction inventory) {
        InteractionResultHolder<ItemStack> holder =
            stack.use(this.mob().level(), inventory.player(), InteractionHand.MAIN_HAND);
        if (holder.getResult().consumesAction() && !ItemStack.matches(stack, holder.getObject())) {
            inventory.player().setItemInHand(InteractionHand.MAIN_HAND, holder.getObject());
        }
        return holder.getResult();
    }

    /**
     * @param action 当前动作；观察消费结束并更新终态
     */
    @Override public void tick(ProtoUseItem action) {
        if (this.consumption != null) {
            this.consumption.tick();
            if (this.consumption.closed()) {
                this.state = this.consumption.finished() ? ActionState.completed("item consumed", this.details)
                    : ActionState.failed("item consumption interrupted", this.details);
            }
        }
    }

    /**
     * @param action 被中断动作；清算物品，不提前消费
     */
    @Override public void onInterrupt(ProtoUseItem action) {
        if (this.consumption != null && !this.consumption.closed()) {
            this.consumption.close();
            this.tick(action);
        }
    }

    /**
     * @param action 当前动作
     * @return 保留的即时终态或消费进度
     */
    @Override public ActionState getState(ProtoUseItem action) { return this.state; }

    /** 为每个环境创建独立控制器的注册工厂。 */
    public static final class Factory implements ActionComponentFactory<ProtoUseItem, UseItemController> {
        /**
         * @param mob 受控生物
         * @return 新控制器
         */
        @Override public UseItemController create(Mob mob) { return new UseItemController(mob); }
        /** @return 控制器类型 */
        @Override public Class<UseItemController> componentType() { return UseItemController.class; }
    }
}
