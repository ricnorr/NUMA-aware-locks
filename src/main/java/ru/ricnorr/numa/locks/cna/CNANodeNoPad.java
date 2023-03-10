package ru.ricnorr.numa.locks.cna;

import java.util.concurrent.atomic.AtomicReference;

public class CNANodeNoPad implements CNANodeInterface {
    public final int socket;
    private final AtomicReference<CNANodeInterface> secTail;
    private volatile CNANodeInterface spin;
    private volatile CNANodeInterface next;


    public CNANodeNoPad(int clusterID) {
        spin = null;
        socket = clusterID;
        secTail = new AtomicReference<>(null);
        next = null;
    }

    @Override
    public void setSpinAtomically(CNANodeInterface cnaNode) {
        spin = cnaNode;
    }

    @Override
    public void setNextAtomically(CNANodeInterface cnaNode) {
        next = cnaNode;
    }

    @Override
    public void setSecTailAtomically(CNANodeInterface cnaNode) {
        secTail.set(cnaNode);
    }

    @Override
    public CNANodeInterface getSpin() {
        return spin;
    }

    @Override
    public CNANodeInterface getNext() {
        return next;
    }

    @Override
    public CNANodeInterface getSecTail() {
        return secTail.get();
    }

    @Override
    public int getSocket() {
        return socket;
    }
}