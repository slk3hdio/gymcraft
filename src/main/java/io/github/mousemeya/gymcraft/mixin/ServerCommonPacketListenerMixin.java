package io.github.mousemeya.gymcraft.mixin;

import io.github.mousemeya.gymcraft.gym.chat.ChatCapture;

import net.minecraft.network.PacketSendListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.protocol.Packet;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;

/**
 * 出站包咽喉点 mixin —— 在服务端到玩家包的终端发送方法上捕获聊天包，
 * 供 {@code gymcraft:chat} 观测组件还原聊天栏。
 * <p>
 * 只注入带 {@code ChannelFutureListener} 的重载：{@code send(Packet)} 会委托到这里，
 * 双注入会导致每包重复捕获。捕获逻辑全部转发给 {@link ChatCapture#onOutgoingPacket}，
 * 本类不做任何过滤与状态保存。
 * </p>
 */
@Mixin(ServerCommonPacketListenerImpl.class)
abstract class ServerCommonPacketListenerMixin {

    /**
     * 发送前把包与接收者监听器身份转发给聊天捕获器（非聊天包在捕获器内零成本放行；
     * 监听器实例仅用作区分广播与私发的身份键）。
     *
     * @param packet 即将发送的包
     * @param listener 包发送监听器（1.21.1 为 PacketSendListener；可能为 null，捕获不使用）
     * @param ci mixin 回调
     */
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketSendListener;)V", at = @At("HEAD"))
    private void gymcraft$captureChat(Packet<?> packet, PacketSendListener listener, CallbackInfo ci) {
        ChatCapture.onOutgoingPacket(this, packet);
    }
}
