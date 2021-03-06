package minibase;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;


/**
 * LockManager is class which manages locks for transactions.
 * It stores states of various locks on pages and provides atomic grant
 * and release of locks.
 * @author hrishi
 */
public class LockManager {
    
    private static final int BLOCK_DELAY_SHORT = 10;
    private static final int BLOCK_DELAY_LONG = 100;
    private static final int MAX_TRIES_SMALL = 250;
    private static final int MAX_TRIES_LARGE = 500;
    private static final int RAND_RANGE = 10;
    
    private HashMap<PageId, Set<TransactionId>> readLocks;
    private HashMap<PageId, TransactionId> writeLock;
    private HashMap<TransactionId, Set<PageId>> sharedPages;
    private HashMap<TransactionId, Set<PageId>> exclusivePages;
    private HashMap<TransactionId, Thread> transactionThread;


    public LockManager() {
    	readLocks = new HashMap<PageId, Set<TransactionId>>();
        writeLock = new HashMap<PageId, TransactionId>();
        sharedPages = new HashMap<TransactionId, Set<PageId>>();
        exclusivePages = new HashMap<TransactionId, Set<PageId>>();
        transactionThread = new HashMap<TransactionId, Thread>();
    }
    
    /**
     * Checks if transaction has lock on a page
     * @param tid Transaction Id
     * @param pid Page Id
     * @return boolean True if holds lock
     */
    public boolean holdsLock(TransactionId tid, PageId pid){
        if(readLocks.containsKey(pid) && readLocks.get(pid).contains(tid))
            return true;
        if(writeLock.containsKey(pid) && writeLock.get(pid).equals(tid))
            return true;
        return false;
    }

    private void addLock(TransactionId tid, PageId pid, Permissions pm){
        if(pm.equals(Permissions.READ_ONLY)){
            if(!readLocks.containsKey(pid))
                readLocks.put(pid, new HashSet<TransactionId>());
            readLocks.get(pid).add(tid);
            if(!sharedPages.containsKey(tid))
                sharedPages.put(tid, new HashSet<PageId>());
            sharedPages.get(tid).add(pid);
            return;
        }
        writeLock.put(pid, tid);
        if(!exclusivePages.containsKey(tid))
            exclusivePages.put(tid, new HashSet<PageId>());
        exclusivePages.get(tid).add(pid);
    }
    
    private void abortReadLocks(TransactionId requestingTid, PageId pid) 
            throws IOException{
        if(!readLocks.containsKey(pid)) return;
        List<TransactionId> tids = new ArrayList<TransactionId>();
        for(TransactionId tid: readLocks.get(pid))
            tids.add(tid);
        readLocks.get(pid).clear();
        for(TransactionId tid: tids)
            if(!tid.equals(requestingTid))
                transactionThread.get(tid).interrupt();
    }
    
    /**
     * Grants lock to the Transaction.
     * @param tid TransactionId requesting lock.
     * @param pid PageId on which the lock is requested.
     * @param pm The type of permission.
     * @return boolean True if lock is successfully granted.
     */
    public synchronized boolean grantLock(TransactionId tid, PageId pid,
            Permissions pm) {
        return grantLock(tid, pid, pm, false);
    }
    
    /**
     * Grants lock to the Transaction.
     * @param tid TransactionId requesting lock.
     * @param pid PageId on which the lock is requested.
     * @param pm The type of permission.
     * @param force Whether to force give this lock
     * @return boolean True if lock is successfully granted.
     */
    private synchronized boolean grantLock(TransactionId tid, PageId pid, 
            Permissions pm, boolean force) {
        // If Page requested is new and not in lock
        if( ( !readLocks.containsKey(pid) || readLocks.get(pid).isEmpty() ) && 
                !writeLock.containsKey(pid)){
            // We can grant any kind of lock
            addLock(tid, pid, pm);
            return true;
        }
        // Page in lock
        // If requested permission is read
        if(pm.equals(Permissions.READ_ONLY)){
            // If page permission is read write 
            // then we cannot give the lock before release of write lock.
            if(writeLock.containsKey(pid) && writeLock.get(pid) != tid)
                return false;
            // Else the page permission is read 
            // we can give the lock.
            addLock(tid, pid, pm);
            return true;
        }
        // Requested permission is write.
        // This lock can only be given iff there is no other transaction with 
        // any kind of lock on that page. But this can't happen as we
        // handled that case above.
        // This means we can only give lock iff tid is only transaction on the
        // requested page
        if(readLocks.containsKey(pid) && 
                readLocks.get(pid).contains(tid) &&
                readLocks.get(pid).size() == 1){
            addLock(tid, pid, pm);
            return true;
        }
        // But if transaction tid has already a write lock
        // then the result is already success
        if(exclusivePages.containsKey(tid) &&
                exclusivePages.get(tid).contains(pid)){
            return true;
        }
        // In case this call is a force call for write lock
        // Check if there is no write lock
        if(force && 
                pm.equals(Permissions.READ_WRITE) &&
                !writeLock.containsKey(pid)){
            try{
                abortReadLocks(tid, pid);
                addLock(tid, pid, pm);
                return true;
            }catch(IOException e){
                return false;
            }
        }
        // In all other cases we fail to grant lock.
        return false;
    }
    
    /**
     * Grants lock to the Transaction.
     * @param tid TransactionId requesting lock.
     * @param pid PageId on which the lock is requested.
     * @param perm The type of permission.
     */
    public void requestLock(TransactionId tid, PageId pid, 
            Permissions perm) throws TransactionAbortedException{
	int blockDelay = BLOCK_DELAY_LONG;
        int maxTries = MAX_TRIES_SMALL;
        // Check if old tid
        if(sharedPages.containsKey(tid) || 
                exclusivePages.containsKey(tid)){
            blockDelay = BLOCK_DELAY_SHORT;
            maxTries = MAX_TRIES_LARGE;
        }
        // Add this thread to map
        if(!transactionThread.containsKey(tid))
            transactionThread.put(tid, Thread.currentThread());
        boolean isGranted = this.grantLock(tid, pid, perm);
        Random random = new Random(System.currentTimeMillis());
        long startTime = System.currentTimeMillis();
        while(!isGranted){
            if(System.currentTimeMillis() - startTime > maxTries){
                if(perm.equals(Permissions.READ_ONLY)){
                    // Remove this thread from map
                    transactionThread.remove(tid);
                    throw new TransactionAbortedException();
                }
                else{
                    isGranted = this.grantLock(tid, pid, perm, true);
                    startTime = System.currentTimeMillis();
                    continue;
                }
            }
            try {
                // Block thread
                Thread.sleep(blockDelay + random.nextInt(RAND_RANGE));
            } catch (InterruptedException ex) {
                transactionThread.remove(tid);
                throw new TransactionAbortedException();
            }
            isGranted = this.grantLock(tid, pid, perm);
        }
	
    }
    
    /**
     * Releases locks associated with given transaction and page.
     * @param tid The TransactionId.
     * @param pid The PageId.
     */
    public synchronized void releaseLock(TransactionId tid, PageId pid){
   	if(readLocks.containsKey(pid))
            readLocks.get(pid).remove(tid);
        writeLock.remove(pid);
        if(sharedPages.containsKey(tid))
            sharedPages.get(tid).remove(pid);
        if(exclusivePages.containsKey(tid))
            exclusivePages.get(tid).remove(pid);
    }
    
    /**
     * Releases Lock related to a page
     * @param pid PageId
     */
    public synchronized void removePage(PageId pid){
	readLocks.remove(pid);
        writeLock.remove(pid);
    }
    
    /**
     * Releases all pages associated with given Transaction.
     * @param tid The TransactionId.
     */
    public void releaseAllPages(TransactionId tid){
	if(sharedPages.containsKey(tid)){
            for(PageId pid: sharedPages.get(tid))
                readLocks.get(pid).remove(tid);
            sharedPages.remove(tid);
        }
        if(exclusivePages.containsKey(tid)){
            for(PageId pid: exclusivePages.get(tid))
                writeLock.remove(pid);
            exclusivePages.remove(tid);
        }
    }
    
}
