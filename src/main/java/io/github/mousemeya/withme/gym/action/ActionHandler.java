package io.github.mousemeya.withme.gym.action;

import com.google.protobuf.Any;
import net.minecraft.world.entity.Mob;

/**
 * 动作处理器接口，每种动作类型对应一个实现。
 * <p>
 * 处理器通过 {@link #actionKey()} 标识其负责的动作键（如 "gym.move_to"），
 * 通过 {@link #canHandle(Mob)} 判断是否适用于当前 Mob，
 * 通过 {@link #handle(Mob, Any)} 将 Protobuf 编码的动作参数应用到 Mob 上。
 */
public interface ActionHandler {
    /** 返回此处理器负责的动作键 */
    String actionKey();
    /** 判断此处理器是否可以处理指定的 Mob */
    boolean canHandle(Mob mob);
    /** 将动作参数应用到 Mob 上 */
    void handle(Mob mob, Any params) throws Exception;
}
