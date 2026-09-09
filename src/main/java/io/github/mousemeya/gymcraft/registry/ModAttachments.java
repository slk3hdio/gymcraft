package io.github.mousemeya.gymcraft.registry;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

import io.github.mousemeya.gymcraft.GymCraft;
import io.github.mousemeya.gymcraft.gym.action.component.UseItemConsumption;
import io.github.mousemeya.gymcraft.gym.attachment.MobAttachmentAccessScope;
import io.github.mousemeya.gymcraft.gym.menu.session.LogicalMenuSession;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.transfer.item.ItemStacksResourceHandler;

/**
 * 附件类型注册入口 —— 通过 {@link DeferredRegister} 将 {@link AttachmentType}
 * 挂载到 NeoForge 内置的 {@link NeoForgeRegistries#ATTACHMENT_TYPES} 注册表上。
 * <p>
 * 组件的会话状态按需注册为附件并挂在 Mob 实体上
 * （{@code mob.getData(...)} / {@code mob.setData(...)}），每次操作经当前 Mob 读取，
 * 天然跟随 reset 后的新实体实例。
 * </p>
 */
public final class ModAttachments {
    public static final DeferredRegister<AttachmentType<?>> REGISTRY = DeferredRegister.create(
        NeoForgeRegistries.ATTACHMENT_TYPES,
        GymCraft.MODID
    );

    /**
     * 逻辑菜单会话附件 —— 打开菜单时 {@code setData}，关闭时 {@code removeData}；
     * 附件存在当且仅当会话打开。纯运行时状态，不提供序列化 codec。
     * 读取必须经 {@code hasData} 守卫（见 {@code LogicalMenuSessions.current}），
     * 默认值供应商不被使用。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<LogicalMenuSession>> MENU_SESSION = REGISTRY.register(
        "menu_session",
        () -> AttachmentType.<LogicalMenuSession>builder(() -> {
            throw new IllegalStateException("LogicalMenuSession attachment has no default; always guard reads with hasData");
        }).build()
    );

    /** 物品消费会话的纯运行时附件；读取前必须检查 hasData。 */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<UseItemConsumption>> ITEM_CONSUMPTION = REGISTRY.register(
        "item_consumption",
        () -> AttachmentType.<UseItemConsumption>builder(() -> {
            throw new IllegalStateException("Item consumption must be explicitly created");
        }).build()
    );

    /**
     * 当前 Mob 感兴趣的方块类型集合；纯运行时、无序且去重，不提供序列化 codec。
     * 默认供应商只在显式读取时创建空集合，reset 更换实体后自然清空。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Set<Block>>> INTERESTING_BLOCKS = REGISTRY.register(
        "interesting_blocks",
        () -> AttachmentType.<Set<Block>>builder((Supplier<Set<Block>>) HashSet::new).build()
    );

    /** 27 格专属背包持久化附件；死亡时不复制，由掉落事件转移其内容。 */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<ItemStacksResourceHandler>> AGENT_BACKPACK = REGISTRY.register(
        "agent_backpack",
        () -> AttachmentType.serializable(
            () -> new ItemStacksResourceHandler(io.github.mousemeya.gymcraft.gym.attachment.MobAttachments.AGENT_BACKPACK_SIZE)
        ).build()
    );

    /** 当前 env 的临时附件访问作用域；不序列化，也不随实体快照复制。 */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<MobAttachmentAccessScope>> ENV_ATTACHMENT_SCOPE = REGISTRY.register(
        "env_attachment_scope",
        () -> AttachmentType.<MobAttachmentAccessScope>builder(() -> {
            throw new IllegalStateException("Environment attachment scope must be explicitly activated");
        }).build()
    );

    /** 禁止实例化注册入口。 */
    private ModAttachments() {
    }
}
