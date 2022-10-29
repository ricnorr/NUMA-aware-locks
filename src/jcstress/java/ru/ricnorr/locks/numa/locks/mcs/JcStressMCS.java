package ru.ricnorr.locks.numa.locks.mcs;

import org.openjdk.jcstress.annotations.*;
import org.openjdk.jcstress.infra.results.III_Result;
import ru.ricnorr.numa.locks.mcs.MCSLock;

import java.util.concurrent.locks.Lock;

import static org.openjdk.jcstress.annotations.Expect.ACCEPTABLE;

@JCStressTest
@Outcome(id = {"1, 2, 3", "2, 1, 3", "1, 3, 2", "2, 3, 1", "3, 1, 2", "3, 2, 1"}, expect = ACCEPTABLE, desc = "Mutex works")
@State
public class JcStressMCS {

    private final Lock lock = new MCSLock();
    private int v;

    @Actor
    public void actor1(III_Result r) {
        lock.lock();
        try {
            r.r1 = ++v;
        } finally {
            lock.unlock();
        }
    }

    @Actor
    public void actor2(III_Result r) {
        lock.lock();
        try {
            r.r2 = ++v;
        } finally {
            lock.unlock();
        }
    }

    @Actor
    public void actor3(III_Result r) {
        lock.lock();
        try {
            r.r3 = ++v;
        } finally {
            lock.unlock();
        }
    }

}