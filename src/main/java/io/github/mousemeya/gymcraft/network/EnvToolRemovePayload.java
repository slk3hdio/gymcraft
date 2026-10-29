package io.github.mousemeya.gymcraft.network;

import java.util.UUID;

import io.github.mousemeya.gymcraft.GymCraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：移除目标实体当前绑定的环境。
 */
public record EnvToolRemovePayload(UUID target) implements CustomPacketPayload {
    public static final Type<EnvToolRemovePayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath(GymCraft.MODID, "env_tool_remove"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EnvToolRemovePayload> STREAM_CODEC = StreamCodec.of(
        EnvToolRemovePayload::write, EnvToolRemovePayload::read);

    /** @param buffer 网络缓冲 @param value 移除请求 */
    private static void write(RegistryFriendlyByteBuf buffer, EnvToolRemovePayload value) {
        buffer.writeUUID(value.target());
    }

    /** @param buffer 网络缓冲 @return 移除请求 */
    private static EnvToolRemovePayload read(RegistryFriendlyByteBuf buffer) {
        return new EnvToolRemovePayload(buffer.readUUID());
    }

    /** @return 协议类型 */
    @Override public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
