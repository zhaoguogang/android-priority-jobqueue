package com.path.android.jobqueue;

import android.content.Context;
import com.path.android.jobqueue.cachedQueue.CachedJobQueue;
import com.path.android.jobqueue.config.Configuration;
import com.path.android.jobqueue.di.DependencyInjector;
import com.path.android.jobqueue.executor.JobConsumerExecutor;
import com.path.android.jobqueue.log.JqLog;
import com.path.android.jobqueue.network.NetworkEventProvider;
import com.path.android.jobqueue.network.NetworkUtil;
import com.path.android.jobqueue.nonPersistentQueue.NonPersistentPriorityQueue;
import com.path.android.jobqueue.persistentQueue.sqlite.SqliteJobQueue;

import java.util.concurrent.*;

/**
 * a JobManager that supports;
 * -> Persistent / Non Persistent Jobs
 * -> Job Priority
 * -> Running Jobs in Parallel
 * -> Grouping jobs so that they won't run at the same time
 * -> Stats like waiting Job Count
 */
public class JobManager implements NetworkEventProvider.Listener {
    public static final long NS_PER_MS = 1000000;
    public static final long NOT_RUNNING_SESSION_ID = Long.MIN_VALUE;
    public static final long NOT_DELAYED_JOB_DELAY = Long.MIN_VALUE;
    @SuppressWarnings("FieldCanBeLocal")//used for testing
    private final long sessionId;
    private boolean running;

    private final Context appContext;
    private final NetworkUtil networkUtil;
    private final DependencyInjector dependencyInjector;
    private final JobQueue persistentJobQueue;
    private final JobQueue nonPersistentJobQueue;
    private final CopyOnWriteGroupSet runningJobGroups;
    private final JobConsumerExecutor jobConsumerExecutor;
    private final Object newJobListeners = new Object();
    private final ConcurrentHashMap<Long, CountDownLatch> persistentOnAddedLocks;
    private final ConcurrentHashMap<Long, CountDownLatch> nonPersistentOnAddedLocks;
    private final ScheduledExecutorService timedExecutor;

    /**
     * Default constructor that will create a JobManager with 1 {@link SqliteJobQueue} and 1 {@link NonPersistentPriorityQueue}
     * @param context job manager will use applicationContext.
     */
    public JobManager(Context context) {
        this(context, "default");
    }


    /**
     * Default constructor that will create a JobManager with a default {@link Configuration}
     * @param context application context
     * @param id an id that is unique to this JobManager
     */
    public JobManager(Context context, String id) {
        this(context, new Configuration.Builder(context).id(id).build());
    }

    /**
     *
     * @param context used to acquire ApplicationContext
     * @param config
     */
    public JobManager(Context context, Configuration config) {
        if(config.getCustomLogger() != null) {
            JqLog.setCustomLogger(config.getCustomLogger());
        }
        appContext = context.getApplicationContext();
        running = true;
        runningJobGroups = new CopyOnWriteGroupSet();
        sessionId = System.nanoTime();
        this.persistentJobQueue = config.getQueueFactory().createPersistentQueue(context, sessionId, config.getId());
        this.nonPersistentJobQueue = config.getQueueFactory().createNonPersistent(context, sessionId, config.getId());
        persistentOnAddedLocks = new ConcurrentHashMap<Long, CountDownLatch>();
        nonPersistentOnAddedLocks = new ConcurrentHashMap<Long, CountDownLatch>();

        networkUtil = config.getNetworkUtil();
        dependencyInjector = config.getDependencyInjector();
        if(networkUtil instanceof NetworkEventProvider) {
            ((NetworkEventProvider) networkUtil).setListener(this);
        }
        //is important to initialize consumers last so that they can start running
        jobConsumerExecutor = new JobConsumerExecutor(config,consumerContract);
        timedExecutor = Executors.newSingleThreadScheduledExecutor();
        start();
    }


    /**
     * Stops consuming jobs. Currently running jobs will be finished but no new jobs will be run.
     */
    public void stop() {
        running = false;
    }

    /**
     * restarts the JobManager. Will create a new consumer if necessary.
     */
    public void start() {
        if(running) {
            return;
        }
        running = true;
        notifyJobConsumer();
    }

    /**
     * returns the # of jobs that are waiting to be executed.
     * This might be a good place to decide whether you should wake your app up on boot etc. to complete pending jobs.
     * @return # of total jobs.
     */
    public int count() {
        int cnt = 0;
        synchronized (nonPersistentJobQueue) {
            cnt += nonPersistentJobQueue.count();
        }
        synchronized (persistentJobQueue) {
            cnt += persistentJobQueue.count();
        }
        return cnt;
    }

    private int countReadyJobs(boolean hasNetwork) {
        //TODO we can cache this
        int total = 0;
        synchronized (nonPersistentJobQueue) {
            total += nonPersistentJobQueue.countReadyJobs(hasNetwork, runningJobGroups.getSafe());
        }
        synchronized (persistentJobQueue) {
            total += persistentJobQueue.countReadyJobs(hasNetwork, runningJobGroups.getSafe());
        }
        return total;
    }

    /**
     * Adds a job with given priority and returns the JobId.
     * @param priority Higher runs first
     * @param baseJob The actual job to run
     * @return job id
     */
    public long addJob(int priority, BaseJob baseJob) {
        return addJob(priority, 0, baseJob);
    }

    /**
     * Adds a job with given priority and returns the JobId.
     * @param priority Higher runs first
     * @param delay number of milliseconds that this job should be delayed
     * @param baseJob The actual job to run
     * @return a job id. is useless for now but we'll use this to cancel jobs in the future.
     */
    public long addJob(int priority, long delay, BaseJob baseJob) {
        JobHolder jobHolder = new JobHolder(priority, baseJob, delay > 0 ? System.nanoTime() + delay * NS_PER_MS : NOT_DELAYED_JOB_DELAY, NOT_RUNNING_SESSION_ID);
        long id;
        if (baseJob.isPersistent()) {
            synchronized (persistentJobQueue) {
                id = persistentJobQueue.insert(jobHolder);
                addOnAddedLock(persistentOnAddedLocks, id);
            }
        } else {
            synchronized (nonPersistentJobQueue) {
                id = nonPersistentJobQueue.insert(jobHolder);
                addOnAddedLock(nonPersistentOnAddedLocks, id);
            }
        }
        if(JqLog.isDebugEnabled()) {
            JqLog.d("added job id: %d class: %s priority: %d delay: %d group : %s persistent: %s requires network: %s"
                    , id, baseJob.getClass().getSimpleName(), priority, delay, baseJob.getRunGroupId()
                    , baseJob.isPersistent(), baseJob.requiresNetwork());
        }
        if(dependencyInjector != null) {
            //inject members b4 calling onAdded
            dependencyInjector.inject(baseJob);
        }
        jobHolder.getBaseJob().onAdded();
        if(baseJob.isPersistent()) {
            synchronized (persistentJobQueue) {
                clearOnAddedLock(persistentOnAddedLocks, id);
            }
        } else {
            synchronized (nonPersistentJobQueue) {
                clearOnAddedLock(nonPersistentOnAddedLocks, id);
            }
        }
        notifyJobConsumer();
        return id;
    }

    /**
     * Non-blocking convenience method to add a job in background thread.
     *
     * @see #addJob(int, BaseJob) addJob(priority, job).
     */
    public void addJobInBackground(final int priority, final BaseJob baseJob) {
        timedExecutor.execute(new Runnable() {
            @Override
            public void run() {
                addJob(priority, baseJob);
            }
        });
    }

    /**
     * Non-blocking convenience method to add a job in background thread.
     *
     * @see #addJob(int, long, BaseJob) addJob(priority, delay, job).
     */
    public void addJobInBackground(final int priority, final long delay, final BaseJob baseJob) {
        final long callTime = System.nanoTime();
        timedExecutor.execute(new Runnable() {
            @Override
            public void run() {
                final long runDelay = (System.nanoTime() - callTime) / NS_PER_MS;
                addJob(priority, Math.max(0, delay - runDelay), baseJob);
            }
        });
    }

    //need to sync on related job queue before calling this
    private void addOnAddedLock(ConcurrentHashMap<Long, CountDownLatch> lockMap, long id) {
        lockMap.put(id, new CountDownLatch(1));
    }

    //need to sync on related job queue before calling this
    private void waitForOnAddedLock(ConcurrentHashMap<Long, CountDownLatch> lockMap, long id) {
        CountDownLatch latch = lockMap.get(id);
        if(latch == null) {
            return;
        }
        try {
            latch.await();
        } catch (InterruptedException e) {
            JqLog.e(e, "could not wait for onAdded lock");
        }
    }

    //need to sync on related job queue before calling this
    private void clearOnAddedLock(ConcurrentHashMap<Long, CountDownLatch> lockMap, long id) {
        CountDownLatch latch = lockMap.get(id);
        if(latch != null) {
            latch.countDown();
        }
        lockMap.remove(id);
    }

    /**
     * checks next available job and returns when it will be available (if it will, otherwise returns {@link Long#MAX_VALUE})
     * also creates a timer to notify listeners at that time
     * @param hasNetwork .
     * @return time wait until next job (in milliseconds)
     */
    private long ensureConsumerWhenNeeded(Boolean hasNetwork) {
        if(hasNetwork == null) {
            //if network util can inform us when network is recovered, we we'll check only next job that does not
            //require network. if it does not know how to inform us, we have to keep a busy loop.
            //noinspection SimplifiableConditionalExpression
            hasNetwork = networkUtil instanceof NetworkEventProvider ? hasNetwork() : true;
        }
        //this method is called when there are jobs but job consumer was not given any
        //this may happen in a race condition or when the latest job is a delayed job
        Long nextRunNs;
        synchronized (nonPersistentJobQueue) {
            nextRunNs = nonPersistentJobQueue.getNextJobDelayUntilNs(hasNetwork);
        }
        if(nextRunNs != null && nextRunNs <= System.nanoTime()) {
            notifyJobConsumer();
            return 0L;
        }
        Long persistedJobRunNs;
        synchronized (persistentJobQueue) {
            persistedJobRunNs = persistentJobQueue.getNextJobDelayUntilNs(hasNetwork);
        }
        if(persistedJobRunNs != null) {
            if(nextRunNs == null) {
                nextRunNs = persistedJobRunNs;
            } else if(persistedJobRunNs < nextRunNs) {
                nextRunNs = persistedJobRunNs;
            }
        }
        if(nextRunNs != null) {
            //to avoid overflow, we need to check equality first
            if(nextRunNs < System.nanoTime()) {
                notifyJobConsumer();
                return 0L;
            }
            long diff = (long)Math.ceil((double)(nextRunNs - System.nanoTime()) / NS_PER_MS);
            ensureConsumerOnTime(diff);
            return diff;
        }
        return Long.MAX_VALUE;
    }

    private void notifyJobConsumer() {
        synchronized (newJobListeners) {
            newJobListeners.notifyAll();
        }
        jobConsumerExecutor.considerAddingConsumer();
    }

    private final Runnable notifyRunnable = new Runnable() {
        @Override
        public void run() {
            notifyJobConsumer();
        }
    };

    private void ensureConsumerOnTime(long waitMs) {
        timedExecutor.schedule(notifyRunnable, waitMs, TimeUnit.MILLISECONDS);
    }

    private boolean hasNetwork() {
        return networkUtil == null || networkUtil.isConnected(appContext);
    }

    private JobHolder getNextJob() {
        boolean haveNetwork = hasNetwork();
        JobHolder jobHolder;
        boolean persistent = false;
        synchronized (nonPersistentJobQueue) {
            jobHolder = nonPersistentJobQueue.nextJobAndIncRunCount(haveNetwork, runningJobGroups.getSafe());
        }
        if (jobHolder == null) {
            //go to disk, there aren't any non-persistent jobs
            synchronized (persistentJobQueue) {
                jobHolder = persistentJobQueue.nextJobAndIncRunCount(haveNetwork, runningJobGroups.getSafe());
                persistent = true;
            }
        }
        if(jobHolder != null) {
            //wait for onAdded locks
            if(persistent) {
                waitForOnAddedLock(persistentOnAddedLocks, jobHolder.getId());
            } else {
                waitForOnAddedLock(nonPersistentOnAddedLocks, jobHolder.getId());
            }
        }
        if(persistent && jobHolder != null && dependencyInjector != null) {
            dependencyInjector.inject(jobHolder.getBaseJob());
        }
        if(jobHolder != null && jobHolder.getGroupId() != null) {
            runningJobGroups.add(jobHolder.getGroupId());
        }
        return jobHolder;
    }

    private void reAddJob(JobHolder jobHolder) {
        JqLog.d("re-adding job %s", jobHolder.getId());
        if (jobHolder.getBaseJob().isPersistent()) {
            synchronized (persistentJobQueue) {
                persistentJobQueue.insertOrReplace(jobHolder);
            }
        } else {
            synchronized (nonPersistentJobQueue) {
                nonPersistentJobQueue.insertOrReplace(jobHolder);
            }
        }
        if(jobHolder.getGroupId() != null) {
            runningJobGroups.remove(jobHolder.getGroupId());
        }
    }

    private void removeJob(JobHolder jobHolder) {
        if (jobHolder.getBaseJob().isPersistent()) {
            synchronized (persistentJobQueue) {
                persistentJobQueue.remove(jobHolder);
            }
        } else {
            synchronized (nonPersistentJobQueue) {
                nonPersistentJobQueue.remove(jobHolder);
            }
        }
        if(jobHolder.getGroupId() != null) {
            runningJobGroups.remove(jobHolder.getGroupId());
        }
    }

    public synchronized void clear() {
        synchronized (nonPersistentJobQueue) {
            nonPersistentJobQueue.clear();
            nonPersistentOnAddedLocks.clear();
        }
        synchronized (persistentJobQueue) {
            persistentJobQueue.clear();
            persistentOnAddedLocks.clear();
        }
        runningJobGroups.clear();
    }

    /**
     * if {@link NetworkUtil} implements {@link NetworkEventProvider}, this method is called when network is recovered
     * @param isConnected network connection state.
     */
    @Override
    public void onNetworkChange(boolean isConnected) {
        ensureConsumerWhenNeeded(isConnected);
    }

    @SuppressWarnings("FieldCanBeLocal")
    private final JobConsumerExecutor.Contract consumerContract = new JobConsumerExecutor.Contract() {
        @Override
        public boolean isRunning() {
            return running;
        }

        @Override
        public void insertOrReplace(JobHolder jobHolder) {
            reAddJob(jobHolder);
        }

        @Override
        public void removeJob(JobHolder jobHolder) {
            JobManager.this.removeJob(jobHolder);
        }

        @Override
        public JobHolder getNextJob(int wait, TimeUnit waitDuration) {
            //be optimistic
            JobHolder nextJob = JobManager.this.getNextJob();
            if(nextJob != null) {
                return nextJob;
            }
            long start = System.nanoTime();
            long remainingWait = waitDuration.toNanos(wait);
            long waitUntil = remainingWait + start;
            //for delayed jobs,
            long nextJobDelay = ensureConsumerWhenNeeded(null);
            while (nextJob == null && waitUntil > System.nanoTime()) {
                //keep running inside here to avoid busy loop
                nextJob = running ? JobManager.this.getNextJob() : null;
                if(nextJob == null) {
                    long remaining = waitUntil - System.nanoTime();
                    if(remaining > 0) {
                        //if we can't detect network changes, we won't be notified.
                        //to avoid waiting up to give time, wait in chunks of 500 ms max
                        long maxWait = Math.min(nextJobDelay, TimeUnit.NANOSECONDS.toMillis(remaining));
                        if(maxWait < 1) {
                            continue;//wait(0) will cause infinite wait.
                        }
                        if(networkUtil instanceof NetworkEventProvider) {
                            //to handle delayed jobs, make sure we trigger this first
                            //looks like there is no job available right now, wait for an event.
                            //there is a chance that if it triggers a timer and it gets called before I enter
                            //sync block, i am going to lose it
                            //TODO fix above case where we may wait unnecessarily long if a job is about to become available
                            synchronized (newJobListeners) {
                                try {
                                    newJobListeners.wait(maxWait);
                                } catch (InterruptedException e) {
                                    JqLog.e(e, "exception while waiting for a new job.");
                                }
                            }
                        } else {
                            //we cannot detect network changes. our best option is to wait for some time and try again
                            //then trigger {@link ensureConsumerWhenNeeded)
                            synchronized (newJobListeners) {
                                try {
                                    newJobListeners.wait(Math.min(500, maxWait));
                                } catch (InterruptedException e) {
                                    JqLog.e(e, "exception while waiting for a new job.");
                                }
                            }
                        }
                    }
                }
            }
            return nextJob;
        }

        @Override
        public int countRemainingReadyJobs() {
            //if we can't detect network changes, assume we have network otherwise nothing will trigger a consumer
            //noinspection SimplifiableConditionalExpression
            return countReadyJobs(networkUtil instanceof NetworkEventProvider ? hasNetwork() : true);
        }
    };

    /**
     * Default implementation of QueueFactory that creates one {@link SqliteJobQueue} and one {@link NonPersistentPriorityQueue}
     * both are wrapped inside a {@link CachedJobQueue} to improve performance
     */
    public static class DefaultQueueFactory implements QueueFactory {
        SqliteJobQueue.JobSerializer jobSerializer;

        public DefaultQueueFactory() {
            jobSerializer = new SqliteJobQueue.JavaSerializer();
        }

        public DefaultQueueFactory(SqliteJobQueue.JobSerializer jobSerializer) {
            this.jobSerializer = jobSerializer;
        }

        @Override
        public JobQueue createPersistentQueue(Context context, Long sessionId, String id) {
            return new CachedJobQueue(new SqliteJobQueue(context, sessionId, id, jobSerializer));
        }
        @Override
        public JobQueue createNonPersistent(Context context, Long sessionId, String id) {
            return new CachedJobQueue(new NonPersistentPriorityQueue(sessionId, id));
        }
    }
}
