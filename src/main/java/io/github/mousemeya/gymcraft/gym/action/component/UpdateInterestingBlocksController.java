package io.github.mousemeya.gymcraft.gym.action.component;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Block;

import io.github.mousemeya.gymcraft.gym.action.AbstractActionComponentController;
import io.github.mousemeya.gymcraft.gym.action.ActionApplyResult;
import io.github.mousemeya.gymcraft.gym.action.ActionComponentFactory;
import io.github.mousemeya.gymcraft.gym.action.ActionControlPolicy;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoUpdateInterestingBlocks;
import io.github.mousemeya.gymcraft.gym.space.DictSpace;
import io.github.mousemeya.gymcraft.gym.space.McSpace;
import io.github.mousemeya.gymcraft.gym.space.SequenceSpace;
import io.github.mousemeya.gymcraft.gym.space.TextSpace;
import io.github.mousemeya.gymcraft.registry.ModAttachments;

/**
 * 感兴趣方块更新动作 —— 原子地批量添加和移除当前 Mob 关注的方块类型。
 * 输入使用方块注册 ID；非法 ID 或增删集合冲突会使整批操作失败且不改变附件状态。
 */
public class UpdateInterestingBlocksController extends AbstractActionComponentController<ProtoUpdateInterestingBlocks> {
    /** 动作允许任意长度的方块 ID 列表，实际集合由注册表中的方块类型自然限定。 */
    private static final McSpace<Map<String, Object>> DEFAULT_SPACE = new DictSpace(Map.of(
        "add_block_ids", new SequenceSpace<>(new TextSpace(), Integer.MAX_VALUE),
        "remove_block_ids", new SequenceSpace<>(new TextSpace(), Integer.MAX_VALUE)
    ));

    /** 创建绑定到指定 Mob 的更新控制器。 */
    public UpdateInterestingBlocksController(Mob mob) {
        super(mob);
    }

    /** 返回该动作对应的 protobuf 类型。 */
    @Override
    public Class<ProtoUpdateInterestingBlocks> protoType() {
        return ProtoUpdateInterestingBlocks.class;
    }

    /** 返回批量增删字段的默认动作空间。 */
    @Override
    public McSpace<Map<String, Object>> defaultSpace() {
        return DEFAULT_SPACE;
    }

    /** 判断消息结构是否落在当前动作空间内。 */
    @Override
    public boolean contains(ProtoUpdateInterestingBlocks component) {
        return component != null && this.space().contains(Map.of(
            "add_block_ids", component.getAddBlockIdsList(),
            "remove_block_ids", component.getRemoveBlockIdsList()
        ));
    }

    /** 校验整批输入并原子更新 Mob 的兴趣集合。 */
    @Override
    public ActionApplyResult apply(ProtoUpdateInterestingBlocks component) {
        ActionState agentError = this.validateMobForAction();
        if (agentError != null) {
            return ActionApplyResult.none(agentError);
        }

        var additions = resolveBlocks(component.getAddBlockIdsList());
        if (additions.error() != null) {
            return ActionApplyResult.none(additions.error());
        }
        var removals = resolveBlocks(component.getRemoveBlockIdsList());
        if (removals.error() != null) {
            return ActionApplyResult.none(removals.error());
        }

        var conflicts = new LinkedHashSet<>(additions.blocks());
        conflicts.retainAll(removals.blocks());
        if (!conflicts.isEmpty()) {
            return ActionApplyResult.none(ActionState.failed("block id appears in both add and remove lists", Map.of(
                "conflicting_block_ids", canonicalIds(conflicts)
            )));
        }

        Mob mob = this.mob();
        Set<Block> updated = mob.hasData(ModAttachments.INTERESTING_BLOCKS)
            ? new HashSet<>(mob.getData(ModAttachments.INTERESTING_BLOCKS))
            : new HashSet<>();
        updated.addAll(additions.blocks());
        updated.removeAll(removals.blocks());
        if (updated.isEmpty()) {
            mob.removeData(ModAttachments.INTERESTING_BLOCKS);
        } else {
            mob.setData(ModAttachments.INTERESTING_BLOCKS, updated);
        }

        return ActionApplyResult.applied(ActionControlPolicy.none(), ActionState.completed(
            "interesting blocks updated",
            Map.of("interesting_block_count", updated.size())
        ));
    }

    /** 返回瞬时动作完成后的稳定状态。 */
    @Override
    public ActionState getState(ProtoUpdateInterestingBlocks component) {
        return ActionState.completed("interesting blocks updated");
    }

    /** 将输入 ID 全部解析为方块；任一 ID 非法时返回失败且不暴露部分结果。 */
    private static ResolvedBlocks resolveBlocks(List<String> blockIds) {
        var blocks = new LinkedHashSet<Block>();
        for (String blockId : blockIds) {
            ResourceLocation id;
            try {
                id = ResourceLocation.parse(blockId);
            } catch (RuntimeException exception) {
                return ResolvedBlocks.failed(blockId);
            }
            var block = BuiltInRegistries.BLOCK.getOptional(id);
            if (block.isEmpty()) {
                return ResolvedBlocks.failed(blockId);
            }
            blocks.add(block.get());
        }
        return new ResolvedBlocks(blocks, null);
    }

    /** 将方块集合转换为稳定的规范注册 ID 列表。 */
    private static List<String> canonicalIds(Set<Block> blocks) {
        return blocks.stream()
            .map(block -> BuiltInRegistries.BLOCK.getKey(block).toString())
            .sorted()
            .toList();
    }

    /** 批量 ID 解析结果 —— 成功时携带完整方块集合，失败时携带动作错误。 */
    private record ResolvedBlocks(Set<Block> blocks, ActionState error) {
        /** 创建非法方块 ID 对应的失败结果。 */
        private static ResolvedBlocks failed(String blockId) {
            return new ResolvedBlocks(Set.of(), ActionState.failed("invalid block id", Map.of("block_id", blockId)));
        }
    }

    /** 动作工厂 —— 为每个环境创建独立且绑定当前 Mob 的控制器。 */
    public static final class Factory implements ActionComponentFactory<ProtoUpdateInterestingBlocks, UpdateInterestingBlocksController> {
        /** 创建动作控制器。 */
        @Override
        public UpdateInterestingBlocksController create(Mob mob) {
            return new UpdateInterestingBlocksController(mob);
        }

        /** 返回工厂创建的控制器运行时类型。 */
        @Override
        public Class<UpdateInterestingBlocksController> componentType() {
            return UpdateInterestingBlocksController.class;
        }
    }
}
