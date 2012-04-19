package com.urbanairship.datacube;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;

import com.google.common.base.Optional;
import com.urbanairship.datacube.dbharnesses.FullQueueException;

/**
 * A DataCube does no IO, it merely returns batches that can be executed. This class wraps
 * around a DataCube and does IO against a storage backend.
 * 
 * Thread safe. Writes can block for a long time if another thread is flushing to the database.
 * 
 * <h3>HOW INTERNAL ASYNC BATCH FLUSHING WORKS (for datacube developers, not clients):</h3>
 * 
 * Batches accumulate here, in DataSyncIo objects. When they pass their size or age threshold, they
 * are sent to the underlying DbHarness to be saved to the database. This is where it gets weird,
 * since the DbHarness layer is completely asynchronous and non-blocking.
 * 
 * When a Batch is sent to the DbHarness to be saved, either we get back a Future, or we get a
 * FullQueueException which means that the DbHarness cannot currently accept more batches for saving.
 * 
 * If we get back a Future, then one of two things will happen: 
 * 
 * <ul>
 * <li>If the client requested a blocking write using writeSync(), we block and wait for the Future 
 * to complete.</li>
 * <li>If the client requested a non-blocking write using writeAsync(), we will return new Future
 * that will wait for the DbHarness flush Future to complete.
 * </ul>
 * 
 * <h3>Error handling</h3>
 * 
 * If batch flushing encounters an exception during a writeSync(), the exception will be thrown to
 * the caller. If batch flushing encounters an exception during a writeAsync(), we can't throw the
 * exception back to the caller since the writeAsync() call has already returned. We make sure the
 * caller handles the error by doing two things.
 * 
 * First, we rethrow the exception from the Future returned by writeAsync(), which the caller will see
 * as an ExecutionException thrown by Future.get(). This alone is not sufficient though, because the
 * caller may never call Future.get().
 * 
 * Second, when an asynchronous batch flush has an exception, the exception is saved in
 * {@link #asyncException}. When {@link #asyncException} is non-null, all future calls to writeAsync()
 * will throw AsyncException and refuse to write. This prevents clients blithely throwing away data
 * if the underlying database is stuck in a bad state.
 */
public class DataCubeIo<T extends Op> {
    private static final Logger log = LogManager.getLogger(DataCubeIo.class);
    
    private final DbHarness<T> db;
    private final DataCube<T> cube;
    private final int batchSize;
    private final long maxBatchAgeMs;
    private final SyncLevel syncLevel;
    private AsyncException asyncException = null;
    
    private final Object lock = new Object();
    
    // This executor will wait for DB writes to complete then check if they had an error.
    private final ThreadPoolExecutor asyncErrorMonitorExecutor;
    
    private Batch<T> batchInProgress = new Batch<T>();
    private long batchStartTimeMs;
    
    /**
     * @param batchSize if after doing a write the number of rows to be written to the database
     * exceeds this number, a flush will be done.
     * @param maxBatchAgeMs if after doing a write the batch's oldest write was more than this long ago,
     * a flush will be done. This is not a hard ceiling on the age of writes in the batch, because the
     * batch will not be flushed until the *next* write arrives after the timeout.
     */
    public DataCubeIo(DataCube<T> cube, DbHarness<T> db, int batchSize, long maxBatchAgeMs,
            SyncLevel syncLevel) {
        this.cube = cube;
        this.db = db;
        this.batchSize = batchSize;
        this.maxBatchAgeMs = maxBatchAgeMs;
        this.syncLevel = syncLevel;
        
        this.asyncErrorMonitorExecutor = new ThreadPoolExecutor(1, Integer.MAX_VALUE,
                1, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>(), 
                new NamedThreadFactory("DataCubeIo async DB watcher"));
    }
    
    /**
     * Do some writes into the in-memory batch, possibly flushing to the backing database, depending
     * on the {@link SyncLevel}.
     * 
     * @return If the operation did not cause a database flush, Optional.absent will be returned.
     * Otherwise, if a batch flush is triggered, a Future will be returned. This Future's get() 
     * method will return null when the database flush is finished, and rethrow any exceptions as
     * ExecutionExceptions (see {@link java.util.concurrent.Future}). 
     */
    public Optional<Future<?>> writeAsync(T op, WriteBuilder at) throws AsyncException, InterruptedException {
        if(asyncException != null) {
            throw asyncException;
        }
        
        Batch<T> newBatch = cube.getWrites(at, op);
        Batch<T> batchToFlush = null;

        switch(syncLevel) {
        case FULL_SYNC: 
            // At this sync level we flush all batches immediately. No sharing between threads. 
            batchToFlush = newBatch;
            break;
        case BATCH_ASYNC:
        case BATCH_SYNC:
            synchronized (lock) {
                if(batchInProgress.getMap().isEmpty()) {
                    // Start the timer for this batch, it should be flushed when it becomes old
                    batchStartTimeMs = System.currentTimeMillis();
                }
                batchInProgress.putAll(newBatch);
                
                boolean shouldFlush = false;
                
                if(batchInProgress.getMap().size() >= batchSize) {
                    shouldFlush = true;
                } else if(System.currentTimeMillis() > batchStartTimeMs + maxBatchAgeMs) {
                    shouldFlush = true;
                }
                
                if(shouldFlush) {
                    batchToFlush = batchInProgress;
                    batchInProgress = new Batch<T>();
                }
            }
            break;
        default:
            throw new RuntimeException("Unknown sync level " + syncLevel);

        }
            
        if(batchToFlush != null) {
            return Optional.<Future<?>>of(runBatch(batchToFlush));
        } else {
            return Optional.absent();
        }
    }
    
    /**
     * Hand off a batch to the DbHarness layer, retrying on FullQueueException.
     */
    private Future<?> runBatch(Batch<T> batch) throws InterruptedException {
        while(true) {
            try {
                final Future<?> flushFuture = db.runBatchAsync(batch);
                
                Future<?> waitForFlushFuture = asyncErrorMonitorExecutor.submit(
                        new AsyncFlushCallable(flushFuture)); 
                return waitForFlushFuture;
            } catch (FullQueueException e) {
                // Sleeping and retrying like this means batches may be flushed out of order
                log.debug("Async queue is full, retrying soon");
                Thread.sleep(100);
            }
        }
    }
    
    /**
     * If the {@link SyncLevel} is {@link SyncLevel#FULL_SYNC} or {@link SyncLevel#BATCH_SYNC}, then 
     * no asynchronous IO is happening. You can use this function to write instead of 
     * {@link #writeAsync(Op, WriteBuilder)} without catching AsyncException or InterruptedException.
     * 
     * IMPORTANT: the name of this function does not imply that the write is immediately flushed to
     * the database. This is only true if {@link SyncLevel#FULL_SYNC} is set. Otherwise your write
     * is probably just staged in a batch for writing later.
     * 
     * You can only use this function if this DataCubeIo was constructed with 
     * {@link SyncLevel#FULL_SYNC} or {@link SyncLevel#BATCH_SYNC}.
     */
    public void writeSync(T op, WriteBuilder at) throws IOException {
        if(syncLevel == SyncLevel.BATCH_ASYNC) {
            throw new IllegalArgumentException("You can't use WriteSync for this cube with " + 
                    "SyncLevel " + syncLevel);
        }
        
        Optional<Future<?>> optFuture;
        try {
            optFuture = writeAsync(op, at);
        } catch (AsyncException pe) {
            throw new RuntimeException("Internal error, when at a synchronized syncLevel there should" +
            		" be no asynchronous exceptions");
        } catch (InterruptedException e) {
            throw new IOException("Couldn't enqueue batch, InterruptedException", e);
        }
        
        if(optFuture.isPresent()) {
            // Our write triggered a batch flush. Wait for it to finish, rethrowing exceptions.
            try {
                optFuture.get().get();
            } catch (InterruptedException ie) {
                throw new IOException("Interrupted during write", ie);
            } catch (ExecutionException ee) {
                Throwable flushException = ee.getCause();
                if(flushException instanceof IOException) {
                    throw new IOException(flushException);
                } else if(flushException instanceof RuntimeException) {
                    throw new RuntimeException(flushException);
                } else {
                    throw new RuntimeException("Unreachable");
                }
            } 
        } else {
            // Our write updated a batch in memory without causing a flush. Return success.
        }
    }
    
    /**
     * @return absent if the bucket doesn't exist, or the bucket if it does.
     */
    public Optional<T> get(Address addr) throws IOException {
        cube.checkValidReadOrThrow(addr);
        return db.get(addr);
    }
    
    public Optional<T> get(ReadBuilder readBuilder) throws IOException {
        return this.get(readBuilder.build());
    }
    
    public void flush() throws InterruptedException {
        Batch<T> batchToFlush;
        synchronized(lock) {
            batchToFlush = batchInProgress;
            batchInProgress = new Batch<T>();
        }
        runBatch(batchToFlush);
        db.flush();
    }
    
    /**
     * Waits on a Future, ignoring its results. We use this to notice errors in asynchronous DB 
     * flushes and put the DataCubeIo into an error state where it won't accept any more writes.
     */
    private class AsyncFlushCallable implements Callable<Object> {
        private final Future<?> future;
        
        public AsyncFlushCallable(Future<?> future) {
            this.future = future;
        }
        
        @Override
        public Object call() throws Exception {
            try {
                future.get();
                return null; // we have nothing to return but Callable requires that we return something
            } catch (ExecutionException ee) {
                Throwable t = ee.getCause();
                asyncException = new AsyncException(t); // This will refuse any more writes
                log.error("Putting DataCubeIo into an error state due to flush exception", t);
                if(t instanceof Exception) {
                    throw (Exception)t;
                } else {
                    throw new Exception(t);
                }
            } catch (InterruptedException ie) {
                asyncException = new AsyncException(ie);
                log.error("Putting DataCubeIo into an error state due to interruption", ie);
                throw ie; // Rethrow Exception
            }
        }
    }
}