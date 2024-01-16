package com.netflix.discovery;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.discovery.util.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A task for updating and replicating the local instanceinfo to the remote server. Properties of this task are:
 * - configured with a single update thread to guarantee sequential update to the remote server
 * - update tasks can be scheduled on-demand via onDemandUpdate()
 * - task processing is rate limited by burstSize
 * - a new update task is always scheduled automatically after an earlier update task. However if an on-demand task
 *   is started, the scheduled automatic update task is discarded (and a new one will be scheduled after the new
 *   on-demand update).
 *
 *   @author dliu
 */
class InstanceInfoReplicator implements Runnable {
    private static final Logger logger = LoggerFactory.getLogger(InstanceInfoReplicator.class);

    private final DiscoveryClient discoveryClient;
    private final InstanceInfo instanceInfo;

    private final int replicationIntervalSeconds;
    private final ScheduledExecutorService scheduler;
    private final AtomicReference<Future> scheduledPeriodicRef;

    private final AtomicBoolean started;
    private final RateLimiter rateLimiter;
    private final int burstSize;
    private final int allowedRatePerMinute;

    InstanceInfoReplicator(DiscoveryClient discoveryClient, InstanceInfo instanceInfo, int replicationIntervalSeconds, int burstSize) {
        this.discoveryClient = discoveryClient;
        this.instanceInfo = instanceInfo;
        this.scheduler = Executors.newScheduledThreadPool(1,
                new ThreadFactoryBuilder()
                        .setNameFormat("DiscoveryClient-InstanceInfoReplicator-%d")
                        .setDaemon(true)
                        .build());

        this.scheduledPeriodicRef = new AtomicReference<Future>();

        this.started = new AtomicBoolean(false);
        //TODO 令牌桶限流用的参数
        this.rateLimiter = new RateLimiter(TimeUnit.MINUTES);
        //TODO 配置文件中的属性instance-info-replication-interval-seconds 每隔多少时间检测一次
        this.replicationIntervalSeconds = replicationIntervalSeconds;
        //TODO  burstSize 爆发系数
        this.burstSize = burstSize;
        //TODO 一分钟允许最大的变化次数 防止更新的次数和查看更新的次数不对等
        this.allowedRatePerMinute = 60 * this.burstSize / this.replicationIntervalSeconds;
        logger.info("InstanceInfoReplicator onDemand update allowed rate per min is {}", allowedRatePerMinute);
    }

    public void start(int initialDelayMs) {
        //TODO started默认是false 改成true 表示 发生了变化
        if (started.compareAndSet(false, true)) {
            instanceInfo.setIsDirty();  // for initial register
            //TODO 定时任务 这个schedule方法是一次性调用的 但是 任务本身是周期性的
            Future next = scheduler.schedule(this, initialDelayMs, TimeUnit.SECONDS);
            //TODO 把定时任务 放到 原子变量
            scheduledPeriodicRef.set(next);
        }
    }

    public void stop() {
        shutdownAndAwaitTermination(scheduler);
        started.set(false);
    }

    private void shutdownAndAwaitTermination(ExecutorService pool) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(3, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            logger.warn("InstanceInfoReplicator stop interrupted");
        }
    }
    //TODO 监听器 所实现的 周期地执行的方法  也就是同步更新的时候执行的方法
    public boolean onDemandUpdate() {
        //TODO 令牌桶限流用器  防止更新配置文件的频率太快
        //TODO 如果频率没有超过allowedRatePerMinute设置的参数
        if (rateLimiter.acquire(burstSize, allowedRatePerMinute)) {
            if (!scheduler.isShutdown()) {
                //TODO 提交任务给scheduler
                scheduler.submit(new Runnable() {
                    @Override
                    public void run() {
                        logger.debug("Executing on-demand update of local InstanceInfo");

                        Future latestPeriodic = scheduledPeriodicRef.get();
                        //TODO 如果原子变量里面的任务 没有完成 就取消掉 该任务   不会多个线程执行run()
                        if (latestPeriodic != null && !latestPeriodic.isDone()) {
                            logger.debug("Canceling the latest scheduled update, it will be rescheduled at the end of on demand update");
                            latestPeriodic.cancel(false);
                        }
                        //TODO 执行当前对象的run 看下面  也就是注册
                        //TODO 会开启一个线程
                        InstanceInfoReplicator.this.run();
                    }
                });
                return true;
            } else {
                logger.warn("Ignoring onDemand update due to stopped scheduler");
                return false;
            }
        } else {
            logger.warn("Ignoring onDemand update due to rate limiter");
            return false;
        }
    }
    //TODO 会开启一个线程
    public void run() {
        try {
            //这是第三种情况 当Client的配置信息发生了变更，则Client提交register()
            discoveryClient.refreshInstanceInfo();
            //TODO 如果 实例配置文件发生了变更 一定有 变更的事件戳
            // 该标志位表示客户端对信息发生了修改 ，需要同步到server 所以下一步是注册
            Long dirtyTimestamp = instanceInfo.isDirtyWithTime();
            if (dirtyTimestamp != null) {//TODO 配置文件发生了变更 就提交注册请求
                /*
                *TODO 三种情况
                * 在应用启动时就可以直接进行register()，不过，需要提前在配置文件中配置  就是强制注册
                * 在renew时，如果server端返回的是NOT_FOUND，则提交register
                * 当Client的配置信息发生了变更，则Client提交register
                * */
                discoveryClient.register();
                //TODO 完成注册后 server和client同步后 就不脏了
                instanceInfo.unsetIsDirty(dirtyTimestamp);
            }
        } catch (Throwable t) {
            logger.warn("There was a problem with the instance info replicator", t);
        } finally {
            //TODO 任务的结尾 又一次 定时任务
            Future next = scheduler.schedule(this, replicationIntervalSeconds, TimeUnit.SECONDS);
            //TODO 把定时任务 放到 原子变量
            scheduledPeriodicRef.set(next);
        }
    }

}
