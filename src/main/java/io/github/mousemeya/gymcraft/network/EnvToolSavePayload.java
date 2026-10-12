package io.github.mousemeya.gymcraft.network;

import java.util.UUID;

import io.github.mousemeya.gymcraft.GymCraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端 → 服务端：把目标 Mob 绑定的环境保存为命名场景（当前世界与 agent 的即时快照）。
 */
public record EnvToolSavePayload(UUID target, String name) implements CustomPacketPayload {
    public static final Type<EnvToolSavePayload> TYPE = new Type<>(
        Identifier.fromNamespaceAndPath(GymCraft.MODID, "env_tool_save"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EnvToolSavePayload> STREAM_CODEC = StreamCodec.of(
        EnvToolSavePayload::write, EnvToolSavePayload::read);

    /** @param buffer 网络缓冲 @param value 保存请求 */
    private static void write(RegistryFriendlyByteBuf buffer, EnvToolSavePayload value) {
        buffer.writeUUID(value.target());
        buffer.writeUtf(value.name());
    }

    /** @param buffer 网络缓冲 @return 保存请求 */
    private static EnvToolSavePayload read(RegistryFriendlyByteBuf buffer) {
        return new EnvToolSavePayload(buffer.readUUID(), buffer.readUtf());
    }

    /** @return 协议类型 */
    @Override public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
