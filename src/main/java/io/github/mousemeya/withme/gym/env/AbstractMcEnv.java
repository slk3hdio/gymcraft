package io.github.mousemeya.withme.gym.env;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import io.github.mousemeya.withme.gym.action.ActionController;
import io.github.mousemeya.withme.gym.action.ActionComponentController;
import io.github.mousemeya.withme.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.withme.gym.observation.ObservationCreator;
import io.github.mousemeya.withme.gym.observation.ObservationComponentCreator;
import io.github.mousemeya.withme.gym.observation.proto.ProtoMcObservation;
import io.github.mousemeya.withme.gym.space.McSpace;




/**
 * 基于实体的 RL 环境抽象基类，实现 {@link McEnv} 接口。
 * <p>
 * 将 Gymnasium 的 Env 概念绑定到一个具体的 Minecraft Mob 实体上，
 * 提供通用的 reset/step 流程，子类只需实现奖励计算、终止判断等策略方法。
 * <p>
 * 工厂方法 {@link #create(String, UUID)} 根据环境类型创建对应子类实例
 * 环境实现由 NeoForge 自定义注册表中的 McEnvFactory 创建。
 */
public abstract class AbstractMcEnv implements McEnv {
    protected final Mob mob;
    protected final UUID envId;

    public AbstractMcEnv(Mob mob, UUID envId) {
        this.mob = mob;
        this.envId = envId;
    }
    
    @Override
    public ResetResult reset(Integer seed, Map<String, Object> options) {
        return null;
    }

    @Override
    public StepResult step(ProtoMcAction action) {
        return null;
    }

    @Override
    public abstract McSpace<Map<String, Object>> getObservationSpace();
    @Override
    public abstract McSpace<Map<String, Object>> getActionSpace();
    @Override
    public abstract Map<String, Object> getMetadata();
    @Override
    public abstract void close();

}
