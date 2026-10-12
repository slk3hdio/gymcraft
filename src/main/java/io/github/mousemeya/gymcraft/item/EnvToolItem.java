package io.github.mousemeya.gymcraft.item;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import io.github.mousemeya.gymcraft.gym.EnvManager;
import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.network.EnvToolScreenPayload;
import io.github.mousemeya.gymcraft.registry.RegistryKeys;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 环境工具物品：右键 Mob 打开环境管理界面（选择环境类型、设定状态范围与方块/实体
 * 快照开关、挂载、保存场景、移除环境），Shift+右键直接移除目标环境。
 * <p>
 * 实体右键由高优先级交互事件优先转发到本物品，确保村民等会主动消费右键的实体
 * 不会在打开管理界面前弹出自身界面；非 Mob 目标保持原版交互行为。
 * </p>
 */
public class EnvToolItem extends Item {
    /**
     * 创建环境工具物品。
     *
     * @param properties 物品属性
     */
    public EnvToolItem(Properties properties) {
        super(properties);
    }

    /**
     * 处理物品原生的生物交互回退入口。
     *
     * @param stack 当前环境工具物品栈
     * @param player 发起交互的玩家
     * @param target 被交互的生物
     * @param hand 使用环境工具的手
     * @return Mob 目标的环境操作结果；非 Mob 返回 PASS
     */
    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target, InteractionHand hand) {
        if (!(target instanceof Mob mob)) {
            return InteractionResult.PASS;
        }

        return interactWithMob(player, mob);
    }

    /**
     * 在原版实体交互前优先处理环境工具，避免村民交易等行为抢先消费右键。
     *
     * @param event 玩家普通实体交互事件
     */
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getItemStack().getItem() instanceof EnvToolItem envTool)
            || !(event.getTarget() instanceof Mob mob)) {
            return;
        }

        InteractionResult result = envTool.interactWithMob(event.getEntity(), mob);
        if (result.consumesAction()) {
            event.setCancellationResult(result);
            event.setCanceled(true);
        }
    }

    /**
     * 为目标 Mob 打开环境管理界面；Shift+右键为快速移除环境的捷径。
     *
     * @param player 发起交互的玩家
     * @param mob 目标 Mob
     * @return 已处理时返回 SUCCESS
     */
    private InteractionResult interactWithMob(Player player, Mob mob) {

        if (player.level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }

        if (player.isShiftKeyDown()) {
            boolean removed = EnvManager.close(mob.getUUID());
            String messageKey = removed
                ? "message.gymcraft.environment_removed"
                : "message.gymcraft.environment_missing";
            player.sendSystemMessage(Component.translatable(messageKey, mob.getUUID().toString()));
            return InteractionResult.SUCCESS;
        }

        if (player instanceof ServerPlayer serverPlayer) {
            openScreen(serverPlayer, mob);
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * 向玩家发送环境管理界面载荷（打开或就地刷新）：携带全部已注册环境类型与
     * 目标当前绑定的环境状态（未绑定时预填默认项）。
     *
     * @param player 目标玩家
     * @param mob 界面管理的 Mob
     */
    public static void openScreen(ServerPlayer player, Mob mob) {
        String currentEnvType = null;
        // 六方向延伸距离预填值：西/东/下/上/北/南；未绑定或无区域时全 0。
        int west = 0;
        int east = 0;
        int down = 0;
        int up = 0;
        int north = 0;
        int south = 0;
        boolean captureBlocks = false;
        boolean captureEntities = false;
        if (EnvManager.get(mob.getUUID()).orElse(null) instanceof AbstractMcEnv env) {
            currentEnvType = env.getRegisterId();
            BoundingBox region = env.stateRegion();
            if (region != null) {
                // 区域相对目标当前方块位置的各方向距离；agent 移出区域时为负，按 0 显示。
                BlockPos anchor = mob.blockPosition();
                west = Math.max(0, anchor.getX() - region.minX());
                east = Math.max(0, region.maxX() - anchor.getX());
                down = Math.max(0, anchor.getY() - region.minY());
                up = Math.max(0, region.maxY() - anchor.getY());
                north = Math.max(0, anchor.getZ() - region.minZ());
                south = Math.max(0, region.maxZ() - anchor.getZ());
                captureBlocks = env.baselineSnapshot().blocks() != null;
                captureEntities = env.baselineSnapshot().entities() != null;
            }
        }
        PacketDistributor.sendToPlayer(player, new EnvToolScreenPayload(
            mob.getUUID(), getRegisteredEnvTypes(), currentEnvType,
            west, east, down, up, north, south, captureBlocks, captureEntities));
    }

    /**
     * 获取所有已注册环境类型并按 ID 排序。
     *
     * @return 已排序的环境注册表 ID 列表
     */
    private static List<String> getRegisteredEnvTypes() {
        List<String> envTypes = new ArrayList<>();
        for (Identifier id : RegistryKeys.ENV_FACTORIES.keySet()) {
            envTypes.add(id.toString());
        }
        envTypes.sort(Comparator.naturalOrder());
        return envTypes;
    }
}
