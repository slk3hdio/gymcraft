package io.github.mousemeya.gymcraft.gym.health;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 单个 Mob 的伤害待读日志 —— {@code gymcraft:health_log} 观测组件的数据源，
 * 以 NeoForge 附件挂在受控 Agent 的 Mob 实体上（见 {@code ModAttachments.DAMAGE_LOG}）。
 * <p>
 * 组件语义为增量消费：{@link #drainNew()} 取出并清空自上次观测以来累积的全部伤害
 * 记录，同一观测间隔内的多次受伤全部保留（容量上限兜底）。日志随附件实例存活，
 * reset 替换实体后自然清空；仅挂在 Mob 附件上的日志才会被事件钩子写入，野生实体
 * 不受影响。事件回调发生在服务端 tick 线程，但所有访问仍经 {@code synchronized}
 * 串行化以保持与 RecentChatLog 一致的线程安全契约。
 * </p>
 */
public final class DamageLog {
    /** 单次观测间隔内最多保留的伤害条数；超出后丢弃最旧记录。 */
    private static final int CAPACITY = 64;

    /** 升序待读队列（队尾最新）。 */
    private final ArrayDeque<DamageEntry> pending = new ArrayDeque<>(CAPACITY);

    /** 单条伤害记录：受伤游戏刻、减免前后伤害值与来源描述。 */
    public record DamageEntry(
        long gameTick,
        float healthDamage,
        float originalDamage,
        String damageType,
        String sourceEntityType,
        int sourceEntityId,
        String directEntityType
    ) {
    }

    /**
     * 追加一条伤害记录，超出容量时丢弃最旧条目。
     *
     * @param entry 事件钩子提取的伤害记录（各字段已在钩子侧规整，空源类型允许）
     */
    public void append(DamageEntry entry) {
        synchronized (pending) {
            pending.addLast(entry);
            while (pending.size() > CAPACITY) {
                pending.pollFirst();
            }
        }
    }

    /**
     * 取出并清空自上次观测以来累积的全部伤害记录。
     * <p>
     * 增量消费契约：返回升序快照（旧->新）后队列即空，下次观测在新伤害发生前
     * 将得到空列表。该语义与 reset（替换实体、日志归零）共同保证观测返回的
     * 恰为"上次观测到本次观测之间"发生的伤害。
     * </p>
     *
     * @return 升序伤害记录快照列表
     */
    public List<DamageEntry> drainNew() {
        synchronized (pending) {
            List<DamageEntry> result = new ArrayList<>(pending);
            pending.clear();
            return result;
        }
    }

    /** 当前是否有未读记录（测试与诊断用）。 */
    public boolean hasPending() {
        synchronized (pending) {
            return !pending.isEmpty();
        }
    }
}
