package io.github.mousemeya.gymcraft.gym.space;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Message;

import io.github.mousemeya.gymcraft.gym.space.proto.ProtoSpaceSpec;

/**
 * 通用空间接口，对应 Gymnasium 的 Space 概念。
 * <p>
 * 以 Protobuf Message 为数据载体：空间的类型识别基于 {@link Descriptor}（消息类型匹配），
 * 字段校验由 {@link #contains(Message)} 按 Descriptor 字段元数据与声明的字段约束执行。
 * 空间描述经 {@link #spec()} 输出为结构化 proto 消息，随 gRPC Connect 响应下发给客户端。
 * </p>
 *
 * @param <T> 空间校验的 Protobuf 消息类型
 */
public interface McSpace<T extends Message> {
    /** @return 本空间校验的 Protobuf 消息类型描述符 */
    Descriptor descriptor();

    /**
     * 检查给定消息是否属于该空间。
     * <p>
     * 消息类型与 {@link #descriptor()} 不一致时直接返回 false（类型识别）；
     * 类型一致时逐字段按声明约束校验（字段校验）。
     * </p>
     *
     * @param message 待校验的消息
     * @return 是否通过校验
     */
    boolean contains(T message);

    /** @return 结构化空间描述（proto 消息，按 Descriptor 字段声明顺序输出） */
    ProtoSpaceSpec spec();
}
