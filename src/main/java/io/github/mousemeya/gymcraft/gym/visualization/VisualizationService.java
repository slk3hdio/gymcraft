package io.github.mousemeya.gymcraft.gym.visualization;

import io.github.mousemeya.gymcraft.GymCraft;
import io.github.mousemeya.gymcraft.gym.EnvManager;
import io.github.mousemeya.gymcraft.gym.action.ActionState;
import io.github.mousemeya.gymcraft.gym.action.proto.ProtoMcAction;
import io.github.mousemeya.gymcraft.gym.env.AbstractMcEnv;
import io.github.mousemeya.gymcraft.gym.state.RegionEntitySnapshot;
import io.github.mousemeya.gymcraft.gym.env.McEnv;
import io.github.mousemeya.gymcraft.gym.observation.proto.ProtoMcObservation;
import io.github.mousemeya.gymcraft.network.VisualFramePayload;
import io.github.mousemeya.gymcraft.network.VisualSubscribePayload;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 服务端线程独占的可视化服务。
 *
 * <p>职责：维护「观察者玩家 → Agent 实体」的订阅表，并在动作执行、观测生成、reset、
 * 环境关闭等运行时事件上把只读调试图形编码为 {@link VisualFrame} 推送给订阅玩家；
 * 订阅确认与 reset 帧随带环境状态范围的边界线框。
 * 订阅与 gRPC 会话完全解耦：不占用环境、不推进 tick、不重复调用任何 creator 或
 * 动作控制器，只消费运行时已经产生的结果。</p>
 *
 * <p>使用契约：除 {@link #close}（内部转交服务端线程）外，所有挂钩都只能在服务端
 * 线程调用；发送前统一按距离与字节预算截断图形，保证单包有界；订阅失效（离线、
 * 越界、失权、环境关闭）由 {@link #onTick} 兜底回收。</p>
 */
public final class VisualizationService {
    /** 玩家 UUID → 订阅；每个玩家同时最多观察一个 Agent，重复 subscribe 直接替换。 */
    private static final Map<UUID, Subscription> SUBSCRIPTIONS = new HashMap<>();
    /** Agent UUID → 执行前几何缓存；供瞬时动作复用与运行中动作节流。 */
    private static final Map<UUID, ActiveAction> ACTIONS = new HashMap<>();
    /** 动作事件自增序号；客户端用它关联同一动作的多次推送。 */
    private static long nextAction;

    /**
     * 单个玩家的订阅状态。
     *
     * <p>持有创建订阅时的环境实例 {@code env}：同 UUID 实体被 reset 重建后
     * {@code EnvManager.get} 返回新实例，旧订阅据此判定失效。{@code generation} 在
     * 每次 reset 后自增、{@code sequence} 每帧自增，客户端据两者丢弃乱序、重放与
     * 旧代次数据。</p>
     */
    private static final class Subscription {
        /** 观察者；离线或失权时订阅回收。 */
        private final ServerPlayer player;
        /** 客户端生成的订阅标识；回复帧原样带回，用于拒绝迟到响应。 */
        private final UUID request;
        /** 被观察 Agent 的实体 UUID。 */
        private final UUID agent;
        /** 创建订阅时的环境实例，用于检测环境替换。 */
        private final McEnv env;
        /** 环境 ID（取自环境元数据），随帧下发供客户端隔离旧环境数据。 */
        private final UUID environment;
        /** 订阅时的维度；跨维度即失效。 */
        private final String dimension;
        /** reset 代次，从 0 开始。 */
        private long generation;
        /** 帧序号，每发送一帧自增。 */
        private long sequence;

        /** @param player 观察者 @param request 请求 @param env 当前环境 */
        private Subscription(ServerPlayer player, VisualSubscribePayload request, McEnv env) {
            this.player = player;
            this.request = request.subscription();
            this.agent = request.agent();
            this.env = env;
            this.environment = UUID.fromString(env.getMetadata().get("env_id").toString());
            this.dimension = player.level().dimension().identifier().toString();
        }
    }

    /**
     * 当前动作的执行前快照。
     *
     * @param id       动作事件序号，客户端据此关联同一动作的多次推送
     * @param action   正在执行的动作实例（引用比较用于识别动作切换）
     * @param before   apply 前生成的几何；瞬时动作的终态反馈直接复用
     * @param lastTick 上次推送时的游戏 tick，用于 2 tick 节流
     */
    private record ActiveAction(long id, ProtoMcAction action, List<VisualElement> before, long lastTick) { }

    /** 禁止实例化静态服务。 */
    private VisualizationService() { }

    /**
     * 处理订阅请求；仅在服务端线程调用。
     *
     * <p>每个玩家同时只保留一个订阅，重复请求先移除旧订阅。校验链：管理员权限、
     * 目标环境存在、目标实体是 Mob、与玩家同维度且 128 格以内。取消请求直接回复
     * {@link VisualFrame.Kind#CLEAR}；校验失败回复 {@link VisualFrame.Kind#DENIED}；
     * 成功回复 {@link VisualFrame.Kind#SUBSCRIBED} 并等待下一次 step/reset 数据。</p>
     *
     * @param player  请求玩家
     * @param request 订阅或取消请求
     */
    public static void subscribe(ServerPlayer player, VisualSubscribePayload request) {
        SUBSCRIPTIONS.remove(player.getUUID());
        var env = EnvManager.get(request.agent()).orElse(null);
        if (request.cancel()) {
            // 描述字段统一携带翻译键，由客户端按玩家语言渲染。
            sendReply(player, request, VisualFrame.Kind.CLEAR, "message.gymcraft.visual.cancelled");
            return;
        }
        var target = ((ServerLevel) player.level()).getEntity(request.agent());
        if (!permitted(player) || env == null || !(target instanceof Mob) || target.distanceToSqr(player) > 128 * 128) {
            sendReply(player, request, VisualFrame.Kind.DENIED, "message.gymcraft.visual.denied");
            return;
        }
        var subscription = new Subscription(player, request, env);
        SUBSCRIPTIONS.put(player.getUUID(), subscription);
        send(subscription, VisualFrame.Kind.SUBSCRIBED, 0, "", "",
            "message.gymcraft.visual.subscribed_waiting", boundaryElements(env), (Mob) target);
    }

    /**
     * 环境边界图形：把环境声明的状态范围（闭区间方块盒）转为半开区间线框，
     * 标签携带环境注册 ID；空范围环境返回空列表。
     *
     * <p>范围可能在首个 reset 才随布局选项确定（如 parkour_mob），因此订阅确认与
     * 每次 reset 帧都重新采样下发，客户端总是展示最新边界。</p>
     *
     * @param env 目标环境
     * @return 边界线框图形，可能为空
     */
    private static List<VisualElement> boundaryElements(McEnv env) {
        if (!(env instanceof AbstractMcEnv abstractEnv)) return List.of();
        var region = abstractEnv.stateRegion();
        if (region == null) return List.of();
        return List.of(VisualGeometry.box(VisualElement.ENV_BOUNDARY_COMPONENT, true,
            RegionEntitySnapshot.toAabb(region), abstractEnv.getRegisterId()));
    }

    /**
     * 校验玩家权限。
     *
     * @param player 待校验玩家
     * @return 是否满足现有管理员权限规则（与指令一致的游戏管理员等级）
     */
    private static boolean permitted(ServerPlayer player) {
        return Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(player.createCommandSourceStack());
    }

    /**
     * 发送不带世界图形的控制帧（CLEAR/DENIED 等）。
     *
     * <p>环境 UUID 置零、代次与序号取中性值：客户端收到后只更新提示文字并清空
     * 缓存，不会与任何世界数据帧混淆。{@code reason} 携带翻译键，由客户端按玩家
     * 语言渲染。</p>
     *
     * @param player  玩家
     * @param request 原始请求，其订阅标识被原样带回
     * @param kind    回复类型
     * @param reason  提示文字的翻译键
     */
    private static void sendReply(ServerPlayer player, VisualSubscribePayload request, VisualFrame.Kind kind, String reason) {
        var frame = new VisualFrame(kind, request.subscription(), new UUID(0, 0), request.agent(),
            player.level().dimension().identifier().toString(), 0, 1, player.level().getGameTime(), 0,
            "", "", reason, 0, List.of());
        PacketDistributor.sendToPlayer(player, new VisualFramePayload(frame));
    }

    /**
     * 复查订阅是否仍指向合法目标。
     *
     * <p>任一条件不满足即判定失效：玩家离线、权限被撤销、跨维度、环境实例被替换
     * （同 UUID 重建）、实体消失或超出 128 格。</p>
     *
     * @param subscription 待检查订阅
     * @return 当前实体；订阅失效时为 null
     */
    private static Mob validMob(Subscription subscription) {
        ServerPlayer player = subscription.player;
        if (player.hasDisconnected() || !permitted(player)
            || !player.level().dimension().identifier().toString().equals(subscription.dimension)
            || EnvManager.get(subscription.agent).orElse(null) != subscription.env) return null;
        var entity = ((ServerLevel) player.level()).getEntity(subscription.agent);
        return entity instanceof Mob mob && mob.distanceToSqr(player) <= 128 * 128 ? mob : null;
    }

    /**
     * 判断某 Agent 是否正被有效观察；作为所有推送挂钩的第一道闸门。
     *
     * <p>只遍历内存中的订阅表并复查订阅有效性，不触发任何世界扫描或区块加载，
     * 无人观看时运行时挂钩的开销接近于零。</p>
     *
     * @param mob Agent 实体
     * @return 当前是否存在有效观察者
     */
    private static boolean watched(Mob mob) {
        return SUBSCRIPTIONS.values().stream().anyMatch(s -> s.agent.equals(mob.getUUID()) && validMob(s) == mob);
    }

    /**
     * 动作 apply 前的挂钩：缓存执行前几何。
     *
     * <p>瞬时动作（破坏、拾取等）一步完成，终态时目标可能已移动或方块已消失，
     * 因此必须在 apply 前采样目标几何；无人观察时不做任何工作。</p>
     *
     * @param mob    Agent 实体
     * @param action 即将执行的动作
     */
    public static void beforeAction(Mob mob, ProtoMcAction action) {
        if (!watched(mob)) return;
        ACTIONS.put(mob.getUUID(), new ActiveAction(++nextAction, action,
            VisualAdapters.action(mob, action, ActionState.running("visual target")), -1));
    }

    /**
     * 动作执行过程中的推送挂钩。
     *
     * <p>语义：运行中状态按 2 tick 节流（{@code force=true} 或终态绕过节流），保证
     * 快速动作的终态反馈不丢失；动作实例变化时自动补采执行前几何；瞬时动作的终态
     * 复用执行前快照，运行中动作与 drop_item 改为从执行结果重新推导几何。</p>
     *
     * @param mob    Agent 实体
     * @param action 已执行的动作
     * @param state  动作控制器返回的真实状态，本方法不修改
     * @param force  true 时绕过位置节流（apply 完成、中断与终态收尾时使用）
     */
    public static void action(Mob mob, ProtoMcAction action, ActionState state, boolean force) {
        if (!watched(mob)) { ACTIONS.remove(mob.getUUID()); return; }
        ActiveAction active = ACTIONS.get(mob.getUUID());
        // 动作实例变化（或调用方跳过了 beforeAction）：补采执行前几何。
        if (active == null || active.action() != action) {
            beforeAction(mob, action);
            active = ACTIONS.get(mob.getUUID());
        }
        if (active == null) return;
        long tick = mob.level().getGameTime();
        // 运行中状态按 2 tick 节流；force 与终态放行，保证快速动作的终态不丢。
        if (!force && !state.isTerminal() && tick - active.lastTick() < 2) return;
        // 瞬时动作复用执行前几何；丢出物品必须读取执行结果中的新实体 ID。
        List<VisualElement> elements = active.before();
        if (!state.isTerminal() || action.getComponentId().equals("gymcraft:drop_item")) {
            List<VisualElement> updated = VisualAdapters.action(mob, action, state);
            if (!updated.isEmpty()) elements = updated;
        }
        elements = elements.stream().map(e -> e.withStatus(state.status().name())).toList();
        for (Subscription subscription : SUBSCRIPTIONS.values()) {
            if (subscription.agent.equals(mob.getUUID()) && validMob(subscription) == mob) {
                send(subscription, VisualFrame.Kind.ACTION, active.id(), action.getComponentId(),
                    state.status().name(), state.description(), elements, mob);
            }
        }
        if (state.isTerminal()) ACTIONS.remove(mob.getUUID());
        // 运行中则更新缓存：保留动作序号，记录本次几何与 tick 供下一轮节流。
        else ACTIONS.put(mob.getUUID(), new ActiveAction(active.id(), action, elements, tick));
    }

    /**
     * 观测生成后的推送挂钩。
     *
     * <p>只消费本次真实返回给 RL 侧的观测快照，绝不重复调用 creator（部分 creator
     * 带有会话副作用）；图形因此固定在采样时刻的坐标上，不会随后续世界变化漂移。</p>
     *
     * @param mob         采样实体
     * @param observation 实际完成的观测
     */
    public static void observation(Mob mob, ProtoMcObservation observation) {
        if (!watched(mob)) return;
        var elements = VisualAdapters.observation(mob, observation);
        for (Subscription subscription : SUBSCRIPTIONS.values()) {
            if (subscription.agent.equals(mob.getUUID()) && validMob(subscription) == mob) {
                send(subscription, VisualFrame.Kind.OBSERVATION, 0, "", "", "", elements, mob);
            }
        }
    }

    /**
     * reset 后的推送挂钩。
     *
     * <p>丢弃当前动作缓存并给所有相关订阅提升代次；客户端收到更高代次的帧后会
     * 清空旧观测与反馈图形，等待 reset 观测重新建立画面。帧内随带环境边界图形：
     * 状态范围可能在首个 reset 才确定，代次提升时客户端正好整体替换。</p>
     *
     * @param mob reset 后（可能重建的）同 UUID 实体
     */
    public static void reset(Mob mob) {
        ACTIONS.remove(mob.getUUID());
        for (Subscription subscription : SUBSCRIPTIONS.values()) {
            if (subscription.agent.equals(mob.getUUID()) && validMob(subscription) == mob) {
                subscription.generation++;
                send(subscription, VisualFrame.Kind.RESET, 0, "", "",
                    "message.gymcraft.visual.waiting_reset", boundaryElements(subscription.env), mob);
            }
        }
    }

    /**
     * 环境关闭后的清理挂钩。
     *
     * <p>close 可能从任意线程调用（如 gRPC 线程），而订阅表只在服务端线程读写，
     * 因此统一转交服务端线程执行；闭包捕获 {@code closing} 实例做同一性比较，
     * 避免延迟执行时误删同 UUID 新环境的订阅。</p>
     *
     * @param mob     环境实体
     * @param closing 正在关闭的环境实例
     */
    public static void close(Mob mob, McEnv closing) {
        if (!(mob.level() instanceof ServerLevel level)) return;
        // 捕获环境实例，避免延迟关闭误删同 UUID 的新环境订阅。
        level.getServer().execute(() -> {
            SUBSCRIPTIONS.values().removeIf(s -> {
                if (!s.agent.equals(mob.getUUID()) || s.env != closing) return false;
                if (!s.player.hasDisconnected()) sendReply(s.player,
                    new VisualSubscribePayload(s.request, s.agent, true), VisualFrame.Kind.CLEAR,
                    "message.gymcraft.visual.env_closed");
                return true;
            });
        });
    }

    /**
     * 组帧并推送给单个订阅者。
     *
     * <p>自动盖章代次与自增序号，发送前经 {@link VisualFrame#bounded} 按距离与
     * 字节预算截断图形；玩家在发送瞬间离线等传输异常按 debug 级别吞掉，不影响
     * 环境与订阅生命周期（失效订阅由 onTick 回收）。</p>
     *
     * @param s           订阅
     * @param kind        消息类型
     * @param action      动作事件序号（控制帧为 0）
     * @param component   动作组件 ID
     * @param status      动作状态名
     * @param description 展示文字
     * @param elements    候选图形（发送前截断）
     * @param mob         Agent 实体，提供游戏时间与截断原点
     */
    private static void send(Subscription s, VisualFrame.Kind kind, long action, String component, String status,
                             String description, List<VisualElement> elements, Mob mob) {
        var frame = new VisualFrame(kind, s.request, s.environment, s.agent, s.dimension,
            s.generation, ++s.sequence, mob.level().getGameTime(), action, component, status, description,
            elements.size(), VisualFrame.bounded(elements, mob.position()));
        try {
            PacketDistributor.sendToPlayer(s.player, new VisualFramePayload(frame));
        } catch (RuntimeException error) {
            GymCraft.LOGGER.debug("Unable to send visualization to disconnected player", error);
        }
    }

    /**
     * 每 tick 兜底回收；由服务端事件总线驱动。
     *
     * <p>回收离线、越界、失权及环境关闭的订阅（失效玩家仍在线时先补发 CLEAR），
     * 随后清理已无任何观察者的动作缓存，防止无人观看的 Agent 泄漏快照。</p>
     *
     * @param event 服务端 tick 事件
     */
    public static void onTick(ServerTickEvent.Post event) {
        var iterator = SUBSCRIPTIONS.values().iterator();
        while (iterator.hasNext()) {
            Subscription s = iterator.next();
            if (validMob(s) == null) {
                if (!s.player.hasDisconnected()) sendReply(s.player,
                    new VisualSubscribePayload(s.request, s.agent, true), VisualFrame.Kind.CLEAR,
                    "message.gymcraft.visual.subscription_expired");
                iterator.remove();
            }
        }
        ACTIONS.keySet().removeIf(agent -> SUBSCRIPTIONS.values().stream().noneMatch(s -> s.agent.equals(agent)));
    }

    /**
     * 停服清理；静态状态不能泄漏到下一个单人世界。
     *
     * @param event 服务端停止事件
     */
    public static void onStopping(ServerStoppingEvent event) {
        SUBSCRIPTIONS.clear();
        ACTIONS.clear();
        nextAction = 0;
    }
}
