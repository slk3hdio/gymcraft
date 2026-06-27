package io.github.mousemeya.gymcraft.network;

import io.github.mousemeya.gymcraft.GymCraft;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 客户端选择环境类型的数据包。
 */
public record SelectEnvTypePayload(String envType) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SelectEnvTypePayload> TYPE = new CustomPacketPayload.Type<>(
        Identifier.fromNamespaceAndPath(GymCraft.MODID, "select_env_type")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, SelectEnvTypePayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8,
        SelectEnvTypePayload::envType,
        SelectEnvTypePayload::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
