package ru.ricnorr.numa.locks;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static ru.ricnorr.numa.locks.HMCS_CCL_PLUS_NUMA_PLUS_SUPERNUMA_HIERARCHY.QNode.*;

/**
 * Взять лок на CCL, затем на нума ноде, затем на супер-нума ноде, затем глобальный
 * На 48 корной машинке нет смысла считать, только на 96 и 128
 */
public class HMCS_CCL_PLUS_NUMA_PLUS_SUPERNUMA_HIERARCHY extends AbstractLock {
    final List<HNode> leafs = new ArrayList<>();
    final ThreadLocal<QNode> localQNode = ThreadLocal.withInitial(QNode::new);
    final ThreadLocal<Integer> localClusterID = ThreadLocal.withInitial(Utils::kungpengGetClusterID);

    final boolean overSubscription;

    public HMCS_CCL_PLUS_NUMA_PLUS_SUPERNUMA_HIERARCHY(boolean overSubscription) {
        this.overSubscription = overSubscription;
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        int cclSize = 4;
        int numaNodesCount = 4;
        int cclPerNuma = (availableProcessors / cclSize) / numaNodesCount;
        var root = new HNode(null);
        List<HNode> superNumaNodes = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            superNumaNodes.add(new HNode(root));
        }
        List<HNode> numaNodes = new ArrayList<>();
        for (int i = 0; i < numaNodesCount; i++) {
            numaNodes.add(new HNode(superNumaNodes.get(i / 2)));
        }
        for (int i = 0; i < availableProcessors / cclSize; i++) {
            leafs.add(new HNode(numaNodes.get(i / cclPerNuma)));
        }
    }

    @Override
    public void lock() {
        lockH(localQNode.get(), leafs.get(localClusterID.get()));
    }

    @Override
    public void unlock() {
        unlockH(leafs.get(localClusterID.get()), localQNode.get());
    }

    private void lockH(QNode qNode, HNode hNode) {
        if (hNode.parent == null) {
            qNode.next.set(null);
            if (overSubscription) {
                qNode.thread = Thread.currentThread();
            }
            qNode.status = LOCKED;
            QNode pred = hNode.tail.getAndSet(qNode);
            if (pred == null) {
                qNode.status = UNLOCKED;
            } else {
                pred.next.set(qNode);
                while (qNode.status == LOCKED) {
                    if (overSubscription) {
                        LockSupport.park(this);
                    } else {
                        Thread.onSpinWait();
                    }
                } // spin
            }
        } else {
            qNode.next.set(null);
            if (overSubscription) {
                qNode.thread = Thread.currentThread();
            }
            qNode.status = WAIT;
            QNode pred = hNode.tail.getAndSet(qNode);
            if (pred != null) {
                pred.next.set(qNode);
                while (qNode.status == WAIT) {
                    if (overSubscription) {
                        //Thread.yield();
                        LockSupport.park(this);
                    } else {
                        Thread.onSpinWait();
                    }
                } // spin
                if (qNode.status < ACQUIRE_PARENT) {
                    return;
                }
            }
            qNode.status = COHORT_START;
            lockH(hNode.node, hNode.parent);
        }
    }

    private void unlockH(HNode hNode, QNode qNode) {
        if (hNode.parent == null) { // top hierarchy
            releaseHelper(hNode, qNode, UNLOCKED);
            return;
        }
        int curCount = qNode.status;
        if (curCount == 10000) {
            unlockH(hNode.parent, hNode.node);
            releaseHelper(hNode, qNode, ACQUIRE_PARENT);
            return;
        }
        QNode succ = qNode.next.get();
        if (succ != null) {
            succ.status = curCount + 1;
            if (overSubscription) {
                LockSupport.unpark(succ.thread);
            }
            return;
        }
        unlockH(hNode.parent, hNode.node);
        releaseHelper(hNode, qNode, ACQUIRE_PARENT);
    }

    private void releaseHelper(HNode l, QNode i, int val) {
        QNode succ = i.next.get();
        if (succ != null) {
            succ.status = val;
            if (overSubscription) {
                LockSupport.unpark(succ.thread);
            }
        } else {
            if (l.tail.compareAndSet(i, null)) {
                return;
            }
            do {
                succ = i.next.get();
            } while (succ == null);
            succ.status = val;
            if (overSubscription) {
                LockSupport.unpark(succ.thread);
            }
        }
    }

    public static class HNode {
        private final AtomicReference<QNode> tail;
        QNode node;
        private HNode parent;

        public HNode(HNode parent) {
            this.parent = parent;
            this.tail = new AtomicReference<>(null);
            this.node = new QNode();
        }
    }

    public static class QNode {

        static int WAIT = Integer.MAX_VALUE;
        static int ACQUIRE_PARENT = Integer.MAX_VALUE - 1;
        static int UNLOCKED = 0x0;
        static int LOCKED = 0x1;
        static int COHORT_START = 0x1;

        private final AtomicReference<QNode> next = new AtomicReference<>(null);
        private volatile int status = WAIT;

        private volatile Thread thread = null;

    }
}
