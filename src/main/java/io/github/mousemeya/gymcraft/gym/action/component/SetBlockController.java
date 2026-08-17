package io.github.mousemeya.gymcraft.gym.action.component;

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
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;

import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.AbstractActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentFactory;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoSetBlock;
import io.github.mousemeya.gymcraft.gym.space.BoxSpace;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
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
 * 且主手持有与目标方块匹配的 {@link BlockItem},放置成功消耗 1 个物品,
 * 并附带 Mob 挥手动作与放置音效。该动作为瞬时动作,应用后立即返回终态。
 * </p>
 */
public class SetBlockController extends AbstractActionComponentController<ProtoSetBlock> {
    private static final Logger LOGGER = LoggerFactory.getLogger(SetBlockController.class);

    /** 原版生存模式默认方块交互距离 ({@code Attributes.BLOCK_INTERACTION_RANGE} 默认值)。 */
    private static final double REACH_DISTANCE = 4.5;
    /** setBlock 标志位: UPDATE_NEIGHBORS | UPDATE_CLIENTS, 与常规方块放置一致。 */
    private static final int SET_BLOCK_FLAGS = 3;

    private static final McSpace<Map<String, Object>> DEFAULT_SPACE = new DictSpace(Map.of(
        "x", new BoxSpace(-30_000_000, 30_000_000, 1),
        "y", new BoxSpace(-2048, 2048, 1),
        "z", new BoxSpace(-30_000_000, 30_000_000, 1),
        "block", new TextSpace()
    ));

    public SetBlockController(Mob mob) {
        super(mob);
    }

    @Override
    public boolean supports() {
        Mob mob = this.mob();
        // 必须具备手持功能:空手视为可持有,手中有物品时按原版规则确认该 Mob 能持有它
        ItemStack held = mob.getMainHandItem();
        return this.supportEntity(mob.getClass()) && (held.isEmpty() || mob.canHoldItem(held));
    }

    @Override
    public Class<ProtoSetBlock> protoType() {
        return ProtoSetBlock.class;
    }

    @Override
    public McSpace<Map<String, Object>> defaultSpace() {
        return DEFAULT_SPACE;
    }

    @Override
    public boolean contains(ProtoSetBlock component) {
        return component != null && this.space().contains(Map.of(
            "x", new double[] { component.getX() },
            "y", new double[] { component.getY() },
            "z", new double[] { component.getZ() },
            "block", component.getBlock()
        ));
    }

    @Override
    public ActionApplyResult apply(ProtoSetBlock component) {
        Mob mob = this.mob();
        if (!(mob.level() instanceof ServerLevel level)) {
            return ActionApplyResult.none(ActionState.failed("not in a server level"));
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
        ActionState validationError = validateTarget(mob, level, pos, held, parsed);
        if (validationError != null) {
            return ActionApplyResult.none(validationError);
        }

        BlockInput input = new BlockInput(parsed.blockState(), parsed.properties().keySet(), parsed.nbt());
        boolean placed = input.place(level, pos, SET_BLOCK_FLAGS);
        if (!placed) {
            return ActionApplyResult.none(ActionState.failed("set block failed", Map.of(
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
        SoundType soundType = placedState.getSoundType();
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

    @Override
    public ActionState getState(ProtoSetBlock component) {
        return ActionState.completed("set block applied");
    }

    /** 目标与手持物品校验,返回 null 表示可放置。 */
    private static ActionState validateTarget(Mob mob, ServerLevel level, BlockPos pos, ItemStack held, BlockStateParser.BlockResult parsed) {
        if (!(held.getItem() instanceof BlockItem blockItem) || blockItem.getBlock() != parsed.blockState().getBlock()) {
            return ActionState.failed("held item does not match the block", Map.of(
                "held_item", held.isEmpty() ? "empty" : held.getItem().toString(),
                "requested_block", parsed.blockState().getBlock().toString()
            ));
        }
        if (!level.hasChunkAt(pos)) {
            return ActionState.failed("target chunk is not loaded", Map.of("pos", pos.toShortString()));
        }
        if (!level.getBlockState(pos).canBeReplaced()) {
            return ActionState.failed("target position is occupied", Map.of(
                "pos", pos.toShortString(),
                "block", level.getBlockState(pos).getBlock().toString()
            ));
        }
        double distance = mob.getEyePosition().distanceTo(Vec3.atCenterOf(pos));
        if (distance > REACH_DISTANCE) {
            return ActionState.failed("target out of reach", Map.of(
                "pos", pos.toShortString(),
                "distance", distance,
                "reach_distance", REACH_DISTANCE
            ));
        }
        return null;
    }

    /**
     * 动作工厂 —— 注册表引用该内部轻量 {@link ActionComponentFactory}，而非目标类构造函数。
     */
    public static final class Factory implements ActionComponentFactory<ProtoSetBlock> {
        @Override
        public SetBlockController create(Mob mob) {
            return new SetBlockController(mob);
        }
    }
}
