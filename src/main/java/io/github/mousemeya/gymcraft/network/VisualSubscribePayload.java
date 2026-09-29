package io.github.mousemeya.gymcraft.network;

import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** 客户端生成请求标识，切换目标时可立即拒绝前一次订阅的迟到响应。 */
public record VisualSubscribePayload(UUID subscription, UUID agent, boolean cancel) implements CustomPacketPayload {
    public static final Type<VisualSubscribePayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("gymcraft", "visual_subscribe"));
    public static final StreamCodec<RegistryFriendlyByteBuf, VisualSubscribePayload> STREAM_CODEC = StreamCodec.of(
        VisualSubscribePayload::write, VisualSubscribePayload::read);

    /** @param buffer 网络缓冲 @param value 订阅请求 */
    private static void write(RegistryFriendlyByteBuf buffer, VisualSubscribePayload value) {
        buffer.writeUUID(value.subscription());
        buffer.writeUUID(value.agent());
        buffer.writeBoolean(value.cancel());
    }

    /** @param buffer 网络缓冲 @return 订阅请求 */
    private static VisualSubscribePayload read(RegistryFriendlyByteBuf buffer) {
        return new VisualSubscribePayload(buffer.readUUID(), buffer.readUUID(), buffer.readBoolean());
    }

    /** @return 协议类型 */
    @Override public Type<VisualSubscribePayload> type() { return TYPE; }
}
