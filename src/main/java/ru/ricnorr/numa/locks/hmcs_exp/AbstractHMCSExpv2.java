package ru.ricnorr.numa.locks.hmcs_exp;

import java.lang.reflect.Array;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;

import jdk.internal.vm.annotation.Contended;
import kotlin.Triple;
import ru.ricnorr.numa.locks.AbstractNumaLock;
import ru.ricnorr.numa.locks.Utils;

import static ru.ricnorr.numa.locks.hmcs.HMCSQNodeInterface.ACQUIRE_PARENT;
import static ru.ricnorr.numa.locks.hmcs.HMCSQNodeInterface.COHORT_START;
import static ru.ricnorr.numa.locks.hmcs.HMCSQNodeInterface.LOCKED;
import static ru.ricnorr.numa.locks.hmcs.HMCSQNodeInterface.UNLOCKED;
import static ru.ricnorr.numa.locks.hmcs.HMCSQNodeInterface.WAIT;

public abstract class AbstractHMCSExpv2 extends AbstractNumaLock {


  protected final HNode[] leafs;

  protected HNode root;

  private final ThreadLocal<HMCSQNodeExp> threadLocalQNode;

  @SuppressWarnings("unchecked")
  public AbstractHMCSExpv2(Supplier<HMCSQNodeExp> qNodeSupplier, Supplier<Integer> clusterIdSupplier, int leafsCnt) {
    super(clusterIdSupplier);
    this.leafs = (HNode[]) Array.newInstance(HNode.class, leafsCnt);
    this.threadLocalQNode = ThreadLocal.withInitial(qNodeSupplier);
  }

  @Override
  @SuppressWarnings("unchecked")
  public Object lock(Object obj) {
    HMCSQNodeExp node = new HMCSQNodeExp();
    boolean fastPath = false;
    int clusterId = -1;
    if (root.lockIsTaken.compareAndSet(false, true)) {
      fastPath = true;
    } else {
      clusterId = getClusterId();
      lockH(node, leafs[clusterId]);
    }
    return new Triple<>(node, clusterId, fastPath);
  }

  @Override
  @SuppressWarnings("unchecked")
  public void unlock(Object obj) {
    Triple<HMCSQNodeExp, Integer, Boolean> qnodeAndClusterId = (Triple<HMCSQNodeExp, Integer, Boolean>) obj;
    if (qnodeAndClusterId.getThird()) {
      root.lockIsTaken.set(false);
      return;
    }
    unlockH(leafs[qnodeAndClusterId.component2()], qnodeAndClusterId.component1());
  }


  @Override
  public boolean hasNext(Object obj) {
    throw new IllegalStateException("Not implemented");
  }

  private void lockH(HMCSQNodeExp qNode, HNode hNode) {
    if (hNode.parent == null) {
      qNode.setNextAtomically(null);
      qNode.setStatusAtomically(LOCKED);
      qNode.thread = Thread.currentThread();
      HMCSQNodeExp pred = hNode.tail.getAndSet(qNode);
      if (pred == null) {
        qNode.setStatusAtomically(UNLOCKED);
      } else {
        pred.setNextAtomically(qNode);
        int counter = 0;
        while (qNode.getStatus() == LOCKED) {
          counter++;
          if (counter > 256) {
            counter = 0;
            LockSupport.park();
          }
        }
      }
      while (true) {
        while (root.lockIsTaken.get()) {
          // пока true
        }
        if (root.lockIsTaken.compareAndSet(false, true)) {
          break;
        }
      }
    } else {
      qNode.setNextAtomically(null);
      qNode.setStatusAtomically(WAIT);
      qNode.thread = Thread.currentThread();
      HMCSQNodeExp pred = hNode.tail.getAndSet(qNode);
      if (pred != null) {
        pred.setNextAtomically(qNode);
        int counter = 0;
        while (qNode.getStatus() == WAIT) {
          counter++;
          if (counter > 256) {
            counter = 0;
            LockSupport.park();
          }
        } // spin
        if (qNode.getStatus() < ACQUIRE_PARENT) {
          while (true) {
            while (root.lockIsTaken.get()) {
            } // пока true
            if (root.lockIsTaken.compareAndSet(false, true)) {
              break;
            }
          }
          return;
        }
      }
      qNode.setStatusAtomically(COHORT_START);
      lockH(hNode.node, hNode.parent);
    }
  }

  private void unlockH(HNode hNode, HMCSQNodeExp qNode) {
    if (hNode.parent == null) { // top hierarchy
      root.lockIsTaken.set(false);
      releaseHelper(hNode, qNode, UNLOCKED);
      return;
    }
    int curCount = qNode.getStatus();
    if (curCount == 1000) {
      unlockH(hNode.parent, hNode.node);
      releaseHelper(hNode, qNode, ACQUIRE_PARENT);
      return;
    }
    HMCSQNodeExp succ = qNode.getNext();
    if (succ != null) {
      root.lockIsTaken.set(false);
      succ.setStatusAtomically(curCount + 1);
      LockSupport.unparkNextAndYieldThis(succ.thread, Utils.getCurrentCarrierThread());
      return;
    }
    unlockH(hNode.parent, hNode.node);
    releaseHelper(hNode, qNode, ACQUIRE_PARENT);
  }

  private void releaseHelper(HNode l, HMCSQNodeExp i, int val) {
    HMCSQNodeExp succ = i.getNext();
    if (succ != null) {
      succ.setStatusAtomically(val);
      LockSupport.unpark(succ.thread);
    } else {
      if (l.tail.compareAndSet(i, null)) {
        return;
      }
      do {
        succ = i.getNext();
      } while (succ == null);
      succ.setStatusAtomically(val);
      LockSupport.unpark(succ.thread);
    }
  }

  @Contended
  public class HNode {

    private final AtomicReference<HMCSQNodeExp> tail;
    private final HNode parent;
    HMCSQNodeExp node;

    final AtomicBoolean lockIsTaken = new AtomicBoolean(false);

    public HNode(HNode parent, HMCSQNodeExp qNode) {
      this.parent = parent;
      this.tail = new AtomicReference<>(null);
      this.node = qNode;
    }
  }
}