package io.github.mousemeya.gymcraft.gym.action.component;

import io.github.mousemeya.gymcraft.gym.component.EnvironmentRegionAware;
import io.github.mousemeya.gymcraft.gym.space.CoordinateSpaces;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import javax.annotation.Nullable;

import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.arguments.blocks.BlockInput;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.AbstractActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentFactory;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoSetBlock;
import io.github.mousemeya.gymcraft.gym.inventory.AgentInventoryLayout;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.TextSpace;

/**
 * 设置方块动作组件 —— 以 /setblock 方式在指定位置放置方块。
 * <p>
 * 放置语义复用原版 /setblock 链路:{@code block} 参数按 /setblock 语法
 * （如 {@code minecraft:oak_stairs[facing=north]}）经 {@link BlockStateParser} 解析,
 * 再由 {@link BlockInput#place} 落地（含邻居形状自适应与可选方块实体 NBT)。
 * 不做玩家视角朝向计算与支撑判定（与 /setblock 一致,可替换任意已有方块）。
 * </p>
 * <p>
 * 与指令的区别在于仍模拟生物行为:要求 Mob 具备手持能力（{@link Mob#canHoldItem}）
 * 且统一物品栏中存在与目标方块匹配的 {@link BlockItem}（不要求已在主手；
 * 匹配物品在其他槽位时先自动与主手交换）,放置成功消耗 1 个物品,
 * 并按玩家放置语义检查待放置方块的碰撞形状是否被自己或其他实体遮挡；
 * 成功后附带 Mob 挥手动作与放置音效。该动作为瞬时动作,应用后立即返回终态。
 * </p>
 */
public class SetBlockController extends AbstractActionComponentController<ProtoSetBlock> implements EnvironmentRegionAware {

    private static final Logger LOGGER = LoggerFactory.getLogger(SetBlockController.class);

    /** 原版生存模式默认方块交互距离 ({@code Attributes.BLOCK_INTERACTION_RANGE} 默认值)。 */
    public static final double DEFAULT_REACH_DISTANCE = 4.5;
    /** setBlock 标志位: UPDATE_NEIGHBORS | UPDATE_CLIENTS, 与常规方块放置一致。 */
    private static final int SET_BLOCK_FLAGS = 3;

    private static final DictSpace DEFAULT_SPACE = new DictSpace(Map.of(
        "x", new BoxSpace(-30_000_000, 30_000_000, 1),
        "y", new BoxSpace(-2048, 2048, 1),
        "z", new BoxSpace(-30_000_000, 30_000_000, 1),
        "block", new TextSpace()
    ));

    /** 当前环境实例的方块交互距离（默认 4.5，可用 {@link #setReachDistance} 覆盖）。 */
    private double reachDistance = DEFAULT_REACH_DISTANCE;

    /**
     * 创建并初始化组件内部状态。
     *
     * @param mob 受控生物
     */
    public SetBlockController(Mob mob) {
        super(mob);
    }

    /** 设置方块交互距离（必须为正数）。 */
    public void setReachDistance(double reachDistance) {
        if (reachDistance <= 0 || !Double.isFinite(reachDistance)) {
            throw new IllegalArgumentException("reach_distance must be a positive finite number, got: " + reachDistance);
        }
        this.reachDistance = reachDistance;
    }

    /**
     * 校验受控生物是否具备本动作要求的能力。
     *
     * @return 是否满足能力要求
     */
    @Override
    public boolean supports() {
        Mob mob = this.mob();
        // 必须具备手持功能:空手视为可持有,手中有物品时按原版规则确认该 Mob 能持有它
        ItemStack held = mob.getMainHandItem();
        return this.supportEntity(mob.getClass()) && (held.isEmpty() || mob.canHoldItem(held));
    }

    /**
     * 返回本组件的 protobuf 消息类型。
     *
     * @return 协议消息类
     */
    @Override
    public Class<ProtoSetBlock> protoType() {
        return ProtoSetBlock.class;
    }

    /**
     * 返回本组件默认参数空间，供实例初始化及结构校验使用。
     *
     * @return 完整默认参数空间
     */
    @Override
    public DictSpace defaultSpace() {
        return DEFAULT_SPACE;
    }

    /**
     * 校验当前动作分支实际使用的字段；实体位置由执行入口独立验证。
     *
     * @param component 当前动作负载
     * @return 当前分支参数是否有效
     */
    @Override
    public boolean contains(ProtoSetBlock component) {
        return component != null && this.space().containsFields(Map.of(
            "x", new double[] { component.getX() },
            "y", new double[] { component.getY() },
            "z", new double[] { component.getZ() },
            "block", component.getBlock()
        ));
    }

    /**
     * 解析并执行动作，目标越界时在产生副作用前返回失败。
     *
     * @param component 当前动作负载
     * @return 动作策略及初始状态
     */
    @Override
    public ActionApplyResult apply(ProtoSetBlock component) {
        Mob mob = this.mob();
        ActionState agentError = this.validateMobForAction();
        if (agentError != null) {
            return ActionApplyResult.none(agentError);
        }
        if (!(mob.level() instanceof ServerLevel level)) {
            return ActionApplyResult.none(ActionState.failed("not in a server level"));
        }
        // 越界请求必须在读取世界、切槽或消耗物品之前拒绝。
        if (!this.containsTarget(component.getX(), component.getY(), component.getZ())) {
            return ActionApplyResult.none(this.outsideTarget(component.getX(), component.getY(), component.getZ()));
        }
        if (!this.contains(component)) {
            return ActionApplyResult.none(ActionState.failed("invalid block action payload"));
        }
        BlockPos pos = new BlockPos(component.getX(), component.getY(), component.getZ());

        // 复用原版 /setblock 的方块描述解析(含 [properties] 与 {nbt})
        BlockStateParser.BlockResult parsed;
        try {
            parsed = BlockStateParser.parseForBlock(level.holderLookup(Registries.BLOCK), component.getBlock(), true);
        } catch (CommandSyntaxException e) {
            return ActionApplyResult.none(ActionState.failed("invalid block: " + e.getMessage(), Map.of(
                "block", component.getBlock()
            )));
        }

        ItemStack held = mob.getMainHandItem();
        if (!isBlockItemFor(held, parsed)) {
            // 主手不匹配时从统一物品栏查找匹配方块物品并换到主手（交换而非移动，保持物品守恒）。
            var source = AgentInventoryLayout.resolve(mob).slots().stream()
                .filter(slot -> isBlockItemFor(slot.getItem(), parsed))
                .findFirst();
            if (source.isEmpty()) {
                return ActionApplyResult.none(ActionState.failed("no matching block item in inventory", Map.of(
                    "held_item", held.isEmpty() ? "empty" : held.getItem().toString(),
                    "requested_block", parsed.blockState().getBlock().toString()
                )));
            }
            var sourceSlot = source.orElseThrow();
            ItemStack stack = sourceSlot.getItem();
            sourceSlot.setItem(held);
            mob.setItemSlot(EquipmentSlot.MAINHAND, stack);
            held = mob.getMainHandItem();
        }
        ActionState validationError = validateTarget(mob, level, pos, parsed);
        if (validationError != null) {
            return ActionApplyResult.none(validationError);
        }

        BlockInput input = new BlockInput(parsed.blockState(), parsed.properties().keySet(), parsed.nbt());
        boolean placed = input.place(level, pos, SET_BLOCK_FLAGS);
        if (!placed) {
            return ActionApplyResult.none(ActionState.failed(
                "block placement was rejected by game rules or an event handler", Map.of(
                "pos", pos.toShortString(),
                "block", component.getBlock()
            )));
        }

        // 模拟生物行为:消耗 1 个手持物品、看向目标并挥手
        held.shrink(1);
        mob.getLookControl().setLookAt(Vec3.atCenterOf(pos));
        mob.swing(InteractionHand.MAIN_HAND);

        // 放置音效与 GameEvent, 与 BlockItem.place 一致
        BlockState placedState = level.getBlockState(pos);
        SoundType soundType = placedState.getSoundType(level, pos, mob);
        level.playSound(null, pos, soundType.getPlaceSound(), SoundSource.BLOCKS,
            (soundType.getVolume() + 1.0F) / 2.0F, soundType.getPitch() * 0.8F);
        level.gameEvent(mob, GameEvent.BLOCK_PLACE, pos);

        LOGGER.info(
            "GymCraft SetBlock apply entity={} pos={} block={} remaining={}",
            mob.getUUID(), pos.toShortString(), component.getBlock(), held.getCount()
        );
        return ActionApplyResult.applied(ActionControlPolicy.none(), ActionState.completed("block set", Map.of(
            "pos", pos.toShortString(),
            "block", component.getBlock(),
            "placed_state", placedState.getBlock().toString(),
            "remaining_count", held.getCount()
        )));
    }

    /**
     * 瞬时动作不会被运行时 tick；实现仅返回已应用终态以满足接口契约。
     *
     * @param component 当前动作
     * @return 完成状态
     */
    @Override
    public ActionState tick(ProtoSetBlock component) {
        return ActionState.completed("set block applied");
    }

    /**
     * 判断物品堆是否为匹配目标方块的 {@link BlockItem}。
     *
     * @param stack 待检查物品堆
     * @param parsed 已解析的目标方块状态
     * @return 物品可用于放置该方块时为 true
     */
    private static boolean isBlockItemFor(ItemStack stack, BlockStateParser.BlockResult parsed) {
        return stack.getItem() instanceof BlockItem blockItem && blockItem.getBlock() == parsed.blockState().getBlock();
    }

    /**
     * 校验目标区块、可替换状态、实体遮挡和交互距离。
     *
     * @param mob 执行动作的 Agent
     * @param level 目标服务端世界
     * @param pos 目标方块坐标
     * @param parsed 已解析的目标方块状态
     * @return 校验失败状态；返回 null 表示允许放置
     */
    private ActionState validateTarget(Mob mob, ServerLevel level, BlockPos pos, BlockStateParser.BlockResult parsed) {
        if (!level.isLoaded(pos)) {
            return ActionState.failed("target chunk is not loaded", Map.of("pos", pos.toShortString()));
        }
        if (!level.getBlockState(pos).canBeReplaced()) {
            return ActionState.failed("target position is occupied", Map.of(
                "pos", pos.toShortString(),
                "block", level.getBlockState(pos).getBlock().toString()
            ));
        }
        // 与 BlockItem#canPlace 一致，按待放置方块的实际碰撞形状检查自己和其他实体。
        CollisionContext placementContext = CollisionContext.withPosition(mob, mob.getY());
        if (!level.isUnobstructed(parsed.blockState(), pos, placementContext)) {
            return ActionState.failed("target position is obstructed by an entity", Map.of(
                "pos", pos.toShortString(),
                "block", parsed.blockState().getBlock().toString()
            ));
        }
        double distance = mob.getEyePosition().distanceTo(Vec3.atCenterOf(pos));
        if (distance > this.reachDistance) {
            return ActionState.failed("target out of reach", Map.of(
                "pos", pos.toShortString(),
                "distance", distance,
                "reach_distance", this.reachDistance
            ));
        }
        return null;
    }


    /** @param region 构造期默认区域；环境先注入区域，再应用具体环境的空间配置 */
    @Override
    public void initializeDefaultRegion(@Nullable BoundingBox region) {
        if (region != null) {
            this.setSpace(CoordinateSpaces.withRegion(this.space(), region, false));
        }
    }

    /**
     * @param x 目标 X
     * @param y 目标 Y
     * @param z 目标 Z
     * @return 是否落在本组件当前坐标空间
     */
    private boolean containsTarget(double x, double y, double z) {
        return this.space().containsFields(Map.of(
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
     * 动作工厂 —— 注册表引用该内部轻量 {@link ActionComponentFactory}，而非目标类构造函数。
     */
    public static final class Factory implements ActionComponentFactory<ProtoSetBlock, SetBlockController> {
        /**
         * 为指定受控生物创建独立组件实例。
         *
         * @param mob 受控生物
         * @return 新组件实例
         */
        @Override
        public SetBlockController create(Mob mob) {
            return new SetBlockController(mob);
        }

        /**
         * 返回该工厂创建的具体动作控制器类型。
         *
         * @return SetBlockController 的运行时类型
         */
        @Override
        public Class<SetBlockController> componentType() {
            return SetBlockController.class;
        }
    }
}
