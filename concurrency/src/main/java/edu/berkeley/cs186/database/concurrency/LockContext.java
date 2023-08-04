package edu.berkeley.cs186.database.concurrency;

import edu.berkeley.cs186.database.TransactionContext;
//import jdk.internal.loader.Resource;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LockContext wraps around LockManager to provide the hierarchical structure
 * of multigranularity locking. Calls to acquire/release/etc. locks should
 * be mostly done through a LockContext, which provides access to locking
 * methods at a certain point in the hierarchy (database, table X, etc.)
 */
public class LockContext {
    // You should not remove any of these fields. You may add additional
    // fields/methods as you see fit.

    // The underlying lock manager.
    protected final LockManager lockman;

    // The parent LockContext object, or null if this LockContext is at the top of the hierarchy.
    protected final LockContext parent;

    // The name of the resource this LockContext represents.
    protected ResourceName name;

    // Whether this LockContext is readonly. If a LockContext is readonly, acquire/release/promote/escalate should
    // throw an UnsupportedOperationException.
    protected boolean readonly;

    // A mapping between transaction numbers, and the number of locks on children of this LockContext
    // that the transaction holds.
    protected final Map<Long, Integer> numChildLocks;

    // You should not modify or use this directly.
    protected final Map<String, LockContext> children;

    // Whether or not any new child LockContexts should be marked readonly.
    protected boolean childLocksDisabled;

    public LockContext(LockManager lockman, LockContext parent, String name) {
        this(lockman, parent, name, false);
    }

    protected LockContext(LockManager lockman, LockContext parent, String name,
                          boolean readonly) {
        this.lockman = lockman;
        this.parent = parent;
        if (parent == null) {
            this.name = new ResourceName(name);
        } else {
            this.name = new ResourceName(parent.getResourceName(), name);
        }
        this.readonly = readonly;
        this.numChildLocks = new ConcurrentHashMap<>();
        this.children = new ConcurrentHashMap<>();
        this.childLocksDisabled = readonly;
    }

    /**
     * Gets a lock context corresponding to `name` from a lock manager.
     */
    public static LockContext fromResourceName(LockManager lockman, ResourceName name) {
        Iterator<String> names = name.getNames().iterator();
        LockContext ctx;
        String n1 = names.next();
        ctx = lockman.context(n1);
        while (names.hasNext()) {
            String n = names.next();
            ctx = ctx.childContext(n);
        }
        return ctx;
    }

    /**
     * Get the name of the resource that this lock context pertains to.
     */
    public ResourceName getResourceName() {
        return name;
    }

    /**
     * Acquire a `lockType` lock, for transaction `transaction`.
     *
     * Note: you must make any necessary updates to numChildLocks, or else calls
     * to LockContext#getNumChildren will not work properly.
     *
     * @throws InvalidLockException if the request is invalid
     * @throws DuplicateLockRequestException if a lock is already held by the
     * transaction.
     * @throws UnsupportedOperationException if context is readonly
     */
    //every resource has an unique lockContext and a lock manager is universal for every resource
    public void acquire(TransactionContext transaction, LockType lockType)
            throws InvalidLockException, DuplicateLockRequestException {
        // TODO(proj4_part2): implement

        if (this.readonly) {
            throw new UnsupportedOperationException();
        }
        //if resource doesn't have  aparent, just give it the lock
        if(this.getResourceName().parent() != null) {
            LockType parent = this.lockman.getLockType(transaction, this.getResourceName().parent());
            //parent lock is null
            if (!LockType.canBeParentLock(parent, lockType)) {  //parent is imcompatible with the child
                throw new InvalidLockException("invalid request");
            }
            this.lockman.acquire(transaction, this.getResourceName(), lockType);
            LockContext parents = LockContext.fromResourceName(this.lockman, this.getResourceName().parent());
            Integer currNum = parents.numChildLocks.getOrDefault(transaction.getTransNum(), 0) + 1;
            parents.numChildLocks.put(transaction.getTransNum(), currNum);
        } else {
            this.lockman.acquire(transaction, this.getResourceName(), lockType);
        }
    }

    /**
     * Release `transaction`'s lock on `name`.
     *
     * Note: you *must* make any necessary updates to numChildLocks, or
     * else calls to LockContext#getNumChildren will not work properly.
     *
     * @throws NoLockHeldException if no lock on `name` is held by `transaction`    implemented in part 1 lockManager
     * @throws InvalidLockException if the lock cannot be released because
     * doing so would violate multigranularity locking constraints
     * @throws UnsupportedOperationException if context is readonly
     */
    public void release(TransactionContext transaction)
            throws NoLockHeldException, InvalidLockException {
        // TODO(proj4_part2): implement

        if (this.readonly) {
            throw new UnsupportedOperationException();
        }
        List<ResourceName> descendants = sDescendants(transaction);
        if(!descendants.isEmpty()) {
            throw new InvalidLockException("Attempting to release an IS lock when child resource still holds S locks.");
        }
        this.lockman.release(transaction, this.getResourceName());
        if(this.name.parent() != null) {
            LockContext parent = LockContext.fromResourceName(this.lockman, this.name.parent());
            Integer currNum = parent.numChildLocks.getOrDefault(transaction.getTransNum(), 1) - 1;
            parent.numChildLocks.put(transaction.getTransNum(), currNum);

        }

    }

    /**
     * Promote `transaction`'s lock to `newLockType`. For promotion to SIX from
     * IS/IX, all S and IS locks on descendants must be simultaneously
     * released. The helper function sisDescendants may be helpful here.
     *
     * Note: you *must* make any necessary updates to numChildLocks, or else
     * calls to LockContext#getNumChildren will not work properly.
     *
     * @throws DuplicateLockRequestException if `transaction` already has a
     * `newLockType` lock
     * @throws NoLockHeldException if `transaction` has no lock
     * @throws InvalidLockException if the requested lock type is not a
     * promotion or promoting would cause the lock manager to enter an invalid
     * state (e.g. IS(parent), X(child)). A promotion from lock type A to lock
     * type B is valid if B is substitutable for A and B is not equal to A, or
     * if B is SIX and A is IS/IX/S, and invalid otherwise. hasSIXAncestor may
     * be helpful here.
     * @throws UnsupportedOperationException if context is readonly
     */
    public void promote(TransactionContext transaction, LockType newLockType)
            throws DuplicateLockRequestException, NoLockHeldException, InvalidLockException {
        // TODO(proj4_part2): implement
        if (this.readonly) {
            throw new UnsupportedOperationException();
        }
        List<Lock> locks = this.lockman.getLocks(this.getResourceName());
        LockType currLock = this.getExplicitLockType(transaction);
        if (newLockType == LockType.SIX && hasSIXAncestor(transaction)) {       //promoting to SIX when an ancestor already has  SIX lock
            throw new InvalidLockException("Redundant Promotion");
        }
        LockType parentLockType = this.lockman.getLockType(transaction, this.name.parent());
        if (this.name.parent()!=null && !LockType.canBeParentLock(parentLockType, newLockType)) {
            throw new InvalidLockException("Parent is not compatible");
        }
        //check if the current parent can be a parent to the newlocktype --> canbeParent in lock type
        if (newLockType == LockType.SIX) {
            List<ResourceName> descendants = sisDescendants(transaction);     //all s or is descendants
            descendants.add(this.name);
            for (ResourceName n: descendants) {
                LockContext parent = LockContext.fromResourceName(this.lockman, n.parent());
                Integer currNum = parent.numChildLocks.get(transaction.getTransNum()) - 1;
                parent.numChildLocks.put(transaction.getTransNum(), currNum);
            }
            this.lockman.acquireAndRelease(transaction, this.getResourceName(), newLockType, descendants);
        }
        else {
            this.lockman.promote(transaction, this.getResourceName(), newLockType);
        }
        return;
    }

    /**
     * Escalate `transaction`'s lock from descendants of this context to this
     * level, using either an S or X lock. There should be no descendant locks
     * after this call, and every operation valid on descendants of this context
     * before this call must still be valid. You should only make *one* mutating
     * call to the lock manager, and should only request information about
     * TRANSACTION from the lock manager.
     *
     * For example, if a transaction has the following locks:
     *
     *                    IX(database)
     *                    /         \
     *               IX(table1)    S(table2)
     *                /      \
     *    S(table1 page3)  X(table1 page5)
     *
     * then after table1Context.escalate(transaction) is called, we should have:
     *
     *                    IX(database)
     *                    /         \
     *               X(table1)     S(table2)
     *
     * You should not make any mutating calls if the locks held by the
     * transaction do not change (such as when you call escalate multiple times
     * in a row).
     *
     * Note: you *must* make any necessary updates to numChildLocks of all
     * relevant contexts, or else calls to LockContext#getNumChildren will not
     * work properly.
     *
     * @throws NoLockHeldException if `transaction` has no lock at this level
     * @throws UnsupportedOperationException if context is readonly
     */
    public void escalate(TransactionContext transaction) throws NoLockHeldException {
        // TODO(proj4_part2): implement
        //release every descendant lock
        LockType whatWeWant;
        LockType currLock = this.getExplicitLockType(transaction);
        if (this.readonly) {
            throw new UnsupportedOperationException();
        }
        if (this.getExplicitLockType(transaction) == LockType.NL) {
            throw new NoLockHeldException("No Lock at this level");
        }
        boolean xix = false;
        List<ResourceName> descendants = new ArrayList<>();   //descendant locks
        descendants.add(this.getResourceName());
        List<Lock> locks = this.lockman.getLocks(transaction);          //locks within the transaction
        for (Lock l: locks) {
            if (l.name.isDescendantOf(this.getResourceName())) {
                descendants.add(l.name);
                if (l.lockType == LockType.IX || l.lockType == LockType.X || l.lockType == LockType.SIX) {
                    xix = true;
                }
            }
        }
        this.numChildLocks.put(transaction.getTransNum(), 0);

        if (xix || currLock == LockType.IX || currLock == LockType.X || currLock == LockType.SIX) {
            whatWeWant = LockType.X;

        }
        else {
            whatWeWant = LockType.S;
        }
        if (LockType.substitutable(whatWeWant, this.getExplicitLockType(transaction))) {
            return;
        }
        this.lockman.acquireAndRelease(transaction, this.getResourceName(), whatWeWant, descendants);
        return;
    }


    /**
     * Get the type of lock that `transaction` holds at this level, or NL if no
     * lock is held at this level.
     */
    public LockType getExplicitLockType(TransactionContext transaction) {
        if (transaction == null) return LockType.NL;
        // TODO(proj4_part2): implement
        return this.lockman.getLockType(transaction, this.getResourceName());
    }

    /**
     * Gets the type of lock that the transaction has at this level, either
     * implicitly (e.g. explicit S lock at higher level implies S lock at this
     * level) or explicitly. Returns NL if there is no explicit nor implicit
     * lock.
     */
    public LockType getEffectiveLockType(TransactionContext transaction) {
        //get implied type
        //go up each parent and see if they have s or x lock
        //whatever the parent has the children also have
        if (transaction == null) return LockType.NL;
        // TODO(proj4_part2): implement
        //while parent not null and current lock type is NL
        //parent.getnumchildren
        ResourceName par = this.getResourceName().parent();
        LockType highest = this.getExplicitLockType(transaction);
        while(par != null) {
            LockType parent = this.lockman.getLockType(transaction, par);
            if (parent == LockType.X || parent == LockType.S) {
                highest = parent;
            }
            if (parent == LockType.SIX) {
                highest = LockType.S;
            }
            par = par.parent();
        }
        return highest;
    }

    /**
     * Helper method to see if the transaction holds a SIX lock at an ancestor
     * of this context
     * @param transaction the transaction
     * @return true if holds a SIX at an ancestor, false if not
     */
    private boolean hasSIXAncestor(TransactionContext transaction) {
        // TODO(proj4_part2): implement
        LockContext x = this.parent;
        while (x.parent!=null) {
            if (x.getExplicitLockType(transaction) == LockType.SIX) {
                return true;
            }
        }
        return false;
    }

    /**
     * Helper method to get a list of resourceNames of all locks that are S or
     * IS and are descendants of current context for the given transaction.
     * @param transaction the given transaction
     * @return a list of ResourceNames of descendants which the transaction
     * holds an S or IS lock.
     */
    //get all the locks the current transaction holds on different levels of resources
    private List<ResourceName> sisDescendants(TransactionContext transaction) {
        // TODO(proj4_part2): implement
        List<ResourceName> names = new ArrayList<>();
        List<Lock> locks = this.lockman.getLocks(transaction);          //locks within the transaction
        for (Lock l: locks) {
            if (l.name.isDescendantOf(this.getResourceName()) && (l.lockType==LockType.IS || l.lockType==LockType.S)) {
                names.add(l.name);
            }
        }
        return names;
    }

    private List<ResourceName> sDescendants(TransactionContext transaction) {
        // TODO(proj4_part2): implement
        List<ResourceName> names = new ArrayList<>();
        List<Lock> locks = this.lockman.getLocks(transaction);          //locks within the transaction
        for (Lock l: locks) {
            if (l.name.isDescendantOf(this.getResourceName()) && l.lockType==LockType.S) {
                names.add(l.name);
            }
        }
        return names;
    }

    /**
     * Disables locking descendants. This causes all new child contexts of this
     * context to be readonly. This is used for indices and temporary tables
     * (where we disallow finer-grain locks), the former due to complexity
     * locking B+ trees, and the latter due to the fact that temporary tables
     * are only accessible to one transaction, so finer-grain locks make no
     * sense.
     */
    public void disableChildLocks() {
        this.childLocksDisabled = true;
    }

    /**
     * Gets the parent context.
     */
    public LockContext parentContext() {
        return parent;
    }

    /**
     * Gets the context for the child with name `name` and readable name
     * `readable`
     */
    public synchronized LockContext childContext(String name) {
        LockContext temp = new LockContext(lockman, this, name,
                this.childLocksDisabled || this.readonly);
        LockContext child = this.children.putIfAbsent(name, temp);
        if (child == null) child = temp;
        return child;
    }

    /**
     * Gets the context for the child with name `name`.
     */
    public synchronized LockContext childContext(long name) {
        return childContext(Long.toString(name));
    }

    /**
     * Gets the number of locks held on children a single transaction.
     */
    public int getNumChildren(TransactionContext transaction) {
        return numChildLocks.getOrDefault(transaction.getTransNum(), 0);
    }

    @Override
    public String toString() {
        return "LockContext(" + name.toString() + ")";
    }
}

