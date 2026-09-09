package io.github.mousemeya.gymcraft.gym.fakeplayer;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

import com.mojang.authlib.GameProfile;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * GymCraft FakePlayer 执行者工厂与手部执行者缓存。
 * <p>
 * 菜单和物品事务始终获得独占随机身份；只有无菜单状态的手部动作按维度复用
 * 稳定身份。所有入口仅允许在服务端 tick 线程调用。
 * </p>
 */
public final class AgentFakePlayerService {
    private static final GameProfile HAND_PROFILE = new GameProfile(
        UUID.nameUUIDFromBytes("gymcraft-hand-simulator".getBytes(StandardCharsets.UTF_8)),
        "[GymCraft]"
    );
    private static final Map<ServerLevel, AgentFakePlayerActor> HAND_ACTORS = new WeakHashMap<>();

    /** 禁止实例化静态服务。 */
    private AgentFakePlayerService() {
    }

    /**
     * 创建菜单会话独占的执行者。
     *
     * @param mob 菜单所属 Mob
     * @return 已按脚部位置准备的独占执行者
     */
    public static AgentFakePlayerActor createMenuActor(Mob mob) {
        return createExclusive(mob, "[GymCraftMenu]", FakePlayerSyncMode.MENU_FEET);
    }

    /**
     * 创建单次物品库存事务独占的执行者。
     *
     * @param mob 物品操作所属 Mob
     * @return 已按眼高位置准备的独占执行者
     */
    public static AgentFakePlayerActor createInventoryActor(Mob mob) {
        return createExclusive(mob, "[GymCraftUse]", FakePlayerSyncMode.ITEM_USE_EYES);
    }

    /**
     * 取得并重新准备当前维度复用的手部执行者。
     *
     * @param mob 本次手部动作所属 Mob
     * @return 已清理并同步状态的手部执行者
     */
    public static AgentFakePlayerActor borrowHandActor(Mob mob) {
        ServerLevel level = requireServerLevel(mob);
        AgentFakePlayerActor actor = HAND_ACTORS.computeIfAbsent(level, ignored ->
            new AgentFakePlayerActor(new FakePlayer(level, HAND_PROFILE), FakePlayerSyncMode.HAND_ACTION)
        );
        actor.prepareFor(mob);
        return actor;
    }

    /**
     * 创建独占执行者并完成首次状态准备。
     *
     * @param mob 执行者所属 Mob
     * @param name FakePlayer 显示名
     * @param mode 状态同步模式
     * @return 已准备的独占执行者
     */
    private static AgentFakePlayerActor createExclusive(Mob mob, String name, FakePlayerSyncMode mode) {
        ServerLevel level = requireServerLevel(mob);
        var actor = new AgentFakePlayerActor(
            new FakePlayer(level, new GameProfile(UUID.randomUUID(), name)), mode
        );
        actor.prepareFor(mob);
        return actor;
    }

    /**
     * 取得 Mob 所在服务端世界，不满足时拒绝创建执行者。
     *
     * @param mob 待解析 Mob
     * @return Mob 所在服务端世界
     */
    private static ServerLevel requireServerLevel(Mob mob) {
        if (mob.level() instanceof ServerLevel level) {
            return level;
        }
        throw new IllegalArgumentException("Mob is not in a server level: " + mob.getUUID());
    }
}
