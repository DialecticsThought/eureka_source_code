/*
 * Copyright 2012 Netflix, Inc.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package com.netflix.eureka.registry;

import com.google.common.cache.CacheBuilder;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.ActionType;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.appinfo.LeaseInfo;
import com.netflix.discovery.EurekaClientConfig;
import com.netflix.discovery.shared.Application;
import com.netflix.discovery.shared.Applications;
import com.netflix.discovery.shared.Pair;
import com.netflix.eureka.EurekaServerConfig;
import com.netflix.eureka.lease.Lease;
import com.netflix.eureka.registry.rule.InstanceStatusOverrideRule;
import com.netflix.eureka.resources.ServerCodecs;
import com.netflix.eureka.util.MeasuredRate;
import com.netflix.servo.annotations.DataSourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static com.netflix.eureka.util.EurekaMonitors.*;

/**
 * Handles all registry requests from eureka clients.
 *
 * <p>
 * Primary operations that are performed are the
 * <em>Registers</em>, <em>Renewals</em>, <em>Cancels</em>, <em>Expirations</em>, and <em>Status Changes</em>. The
 * registry also stores only the delta operations
 * </p>
 *
 * @author Karthik Ranganathan
 */
public abstract class AbstractInstanceRegistry implements InstanceRegistry {
    private static final Logger logger = LoggerFactory.getLogger(AbstractInstanceRegistry.class);

    private static final String[] EMPTY_STR_ARRAY = new String[0];
    private final ConcurrentHashMap<String, Map<String, Lease<InstanceInfo>>> registry
            = new ConcurrentHashMap<String, Map<String, Lease<InstanceInfo>>>();
    protected Map<String, RemoteRegionRegistry> regionNameVSRemoteRegistry = new HashMap<String, RemoteRegionRegistry>();
    protected final ConcurrentMap<String, InstanceStatus> overriddenInstanceStatusMap = CacheBuilder
            .newBuilder().initialCapacity(500)
            .expireAfterAccess(1, TimeUnit.HOURS)
            .<String, InstanceStatus>build().asMap();

    // CircularQueues here for debugging/statistics purposes only
    private final CircularQueue<Pair<Long, String>> recentRegisteredQueue;
    private final CircularQueue<Pair<Long, String>> recentCanceledQueue;
    private ConcurrentLinkedQueue<RecentlyChangedItem> recentlyChangedQueue = new ConcurrentLinkedQueue<>();

    private final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();
    private final Lock read = readWriteLock.readLock();
    private final Lock write = readWriteLock.writeLock();
    protected final Object lock = new Object();

    private Timer deltaRetentionTimer = new Timer("Eureka-DeltaRetentionTimer", true);
    private Timer evictionTimer = new Timer("Eureka-EvictionTimer", true);
    private final MeasuredRate renewsLastMin;

    private final AtomicReference<EvictionTask> evictionTaskRef = new AtomicReference<>();

    protected String[] allKnownRemoteRegions = EMPTY_STR_ARRAY;
    protected volatile int numberOfRenewsPerMinThreshold;
    protected volatile int expectedNumberOfClientsSendingRenews;

    protected final EurekaServerConfig serverConfig;
    protected final EurekaClientConfig clientConfig;
    protected final ServerCodecs serverCodecs;
    protected volatile ResponseCache responseCache;

    /**
     * Create a new, empty instance registry.
     */
    protected AbstractInstanceRegistry(EurekaServerConfig serverConfig, EurekaClientConfig clientConfig, ServerCodecs serverCodecs) {
        this.serverConfig = serverConfig;
        this.clientConfig = clientConfig;
        this.serverCodecs = serverCodecs;
        this.recentCanceledQueue = new CircularQueue<Pair<Long, String>>(1000);
        this.recentRegisteredQueue = new CircularQueue<Pair<Long, String>>(1000);

        this.renewsLastMin = new MeasuredRate(1000 * 60 * 1);
        // 开启了recentCanceledQueue 中的元素的定时清除
        this.deltaRetentionTimer.schedule(getDeltaRetentionTask(),
                serverConfig.getDeltaRetentionTimerIntervalInMs(),
                serverConfig.getDeltaRetentionTimerIntervalInMs());
    }

    @Override
    public synchronized void initializedResponseCache() {
        if (responseCache == null) {
            responseCache = new ResponseCacheImpl(serverConfig, serverCodecs, this);
        }
    }

    protected void initRemoteRegionRegistry() throws MalformedURLException {
        Map<String, String> remoteRegionUrlsWithName = serverConfig.getRemoteRegionUrlsWithName();
        if (!remoteRegionUrlsWithName.isEmpty()) {
            allKnownRemoteRegions = new String[remoteRegionUrlsWithName.size()];
            int remoteRegionArrayIndex = 0;
            for (Map.Entry<String, String> remoteRegionUrlWithName : remoteRegionUrlsWithName.entrySet()) {
                RemoteRegionRegistry remoteRegionRegistry = new RemoteRegionRegistry(
                        serverConfig,
                        clientConfig,
                        serverCodecs,
                        remoteRegionUrlWithName.getKey(),
                        new URL(remoteRegionUrlWithName.getValue()));
                regionNameVSRemoteRegistry.put(remoteRegionUrlWithName.getKey(), remoteRegionRegistry);
                allKnownRemoteRegions[remoteRegionArrayIndex++] = remoteRegionUrlWithName.getKey();
            }
        }
        logger.info("Finished initializing remote region registries. All known remote regions: {}",
                (Object) allKnownRemoteRegions);
    }

    @Override
    public ResponseCache getResponseCache() {
        return responseCache;
    }

    public long getLocalRegistrySize() {
        long total = 0;
        for (Map<String, Lease<InstanceInfo>> entry : registry.values()) {
            total += entry.size();
        }
        return total;
    }

    /**
     * Completely clear the registry.
     */
    @Override
    public void clearRegistry() {
        overriddenInstanceStatusMap.clear();
        recentCanceledQueue.clear();
        recentRegisteredQueue.clear();
        recentlyChangedQueue.clear();
        registry.clear();
    }

    // for server info use
    @Override
    public Map<String, InstanceStatus> overriddenInstanceStatusesSnapshot() {
        return new HashMap<>(overriddenInstanceStatusMap);
    }

    /**
     * Registers a new instance with a given duration.
     *
     * @see com.netflix.eureka.lease.LeaseManager#register(java.lang.Object, int, boolean)
     * TODO
     *  写操作加读锁
     */
    public void register(InstanceInfo registrant, int leaseDuration, boolean isReplication) {
        read.lock();
        try {
            //TODO 得到微服务的映射表
            Map<String, Lease<InstanceInfo>> gMap = registry.get(registrant.getAppName());
            REGISTER.increment(isReplication);
            if (gMap == null) {
                final ConcurrentHashMap<String, Lease<InstanceInfo>> gNewMap = new ConcurrentHashMap<String, Lease<InstanceInfo>>();
                //TODO 如果registrant.getAppName()存在 返回 旧的value 然后把 gNewMap（新值）复制到registrant.getAppName()
                gMap = registry.putIfAbsent(registrant.getAppName(), gNewMap);
                if (gMap == null) {
                    gMap = gNewMap;
                }
            }
            /*
             *TODO
             * existingLease != null 说明 已经有了
             * register有3种情况
             * 1.在应用启动时就可以直接进行register()，不过，需要提前在配置文件中配置 （这种情况 注册表没有）
             * 2.在renew时，如果server端返回的是NOT_FOUND，则提交register （这种情况 注册表没有）
             * 3.当Client的配置信息发生了变更，则Client提交register （这种情况 注册表有）
             * 只有第三种情况 existingLease!=null
             * */
            Lease<InstanceInfo> existingLease = gMap.get(registrant.getId());
            // Retain the last dirty timestamp without overwriting it, if there is already a lease
            if (existingLease != null && (existingLease.getHolder() != null)) {
                //TODO DirtyTimestamp只有Client的配置信息发生了变更 才会改变
                //TODO 当前的 DirtyTimestamp是注册表的旧的信息
                Long existingLastDirtyTimestamp = existingLease.getHolder().getLastDirtyTimestamp();
                //TODO 外面传入的LastDirtyTimestamp
                Long registrationLastDirtyTimestamp = registrant.getLastDirtyTimestamp();
                logger.debug("Existing lease found (existing={}, provided={}", existingLastDirtyTimestamp, registrationLastDirtyTimestamp);

                // this is a > instead of a >= because if the timestamps are equal, we still take the remote transmitted
                // InstanceInfo instead of the server local copy.
                /*
                 *TODO
                 *  作比较 一般情况 外面传入的LastDirtyTimestamp 比 existingLastDirtyTimestamp新
                 *  但是存在网络延时
                 *  第一次注册 更新了 existingLastDirtyTimestamp
                 *  然后再一次注册 更新了 existingLastDirtyTimestamp
                 *  第二次先于第一次更新
                 * */
                if (existingLastDirtyTimestamp > registrationLastDirtyTimestamp) {
                    logger.warn("There is an existing lease and the existing lease's dirty timestamp {} is greater" +
                            " than the one that is being registered {}", existingLastDirtyTimestamp, registrationLastDirtyTimestamp);
                    logger.warn("Using the existing instanceInfo instead of the new instanceInfo as the registrant");
                    //TODO 让注册表的赋值给registrant
                    registrant = existingLease.getHolder();
                }
            } else {
                // The lease does not exist and hence it is a new registration
                synchronized (lock) {
                    /*
                     *TODO
                     * renews（last min） 最后一分钟真正收到的续约的数量 < renews threshold 那么就不能续约过期 lease expiration
                     * updateRenewsPerMinThreshold()方法就是计算  renews threshold
                     * 为什么要执行该方法 => 因为 这个else{}是 新注册的逻辑 ， client数量发生变化，导致阈值重新计算
                     * */
                    if (this.expectedNumberOfClientsSendingRenews > 0) {
                        // Since the client wants to register it, increase the number of clients sending renews
                        /*
                         *TODO
                         * registry.expected-number-of-clients-sending-renews=1 期望的发送续约的client数量 默认为1
                         * 没有客户端的情况下 expectedNumberOfClientsSendingRenews的意义 ：不是0
                         * 有客户端的情况下 expectedNumberOfClientsSendingRenews代表客户端的数量
                         * */
                        this.expectedNumberOfClientsSendingRenews = this.expectedNumberOfClientsSendingRenews + 1;
                        /*
                         *TODO
                         * 当前server开启自我保护机制的最小心跳数量  updateRenewsPerMinThreshold方法算出来之后 显示在dashboard
                         *
                         * 一旦自我保护机制开启了，那么就将当前Server保护了起来，即当前server注册表中的所有client均不会过期，
                         * 即当client没有指定时间内（默认90秒）发送续约，也不会将其从注册表中删除。为什么?|
                         * 保证server的可用性 也就是保证 AP  保护注册表里面的实例不被删除
                         * */
                        updateRenewsPerMinThreshold();
                    }
                }
                logger.debug("No previous lease information found; it is new registration");
            }
            //TODO registrant是方法传入的  得到最新的微服务实例
            Lease<InstanceInfo> lease = new Lease<>(registrant, leaseDuration);
            if (existingLease != null) {
                //TODO 因为是注册 所以记录上限服务的时间戳
                lease.setServiceUpTimestamp(existingLease.getServiceUpTimestamp());
            }
            //TODO 把最新的微服务实例（也就是client）写入注册表
            // ☆☆☆☆☆☆☆☆☆☆☆☆☆☆☆☆
            gMap.put(registrant.getId(), lease);
            recentRegisteredQueue.add(new Pair<Long, String>(
                    System.currentTimeMillis(),
                    registrant.getAppName() + "(" + registrant.getId() + ")"));
            // This is where the initial state transfer of overridden status happens
            /*
             *TODO
             *  新注册（第一次注册）的实例默认OverriddenStatus是UNKNOWN
             *  但是如果client信息发生变更，并且之前做过状态修改执行register的话 就可以不是UNKNOWN
             * eg:
             * 1.新注册（第一次注册）的实例 register请求 =>OverriddenStatus是UNKNOWN
             * 2.接下来提交修改状态请求 update请求 => OverriddenStatus是OUT_OF_SERVICE
             * 3.每隔段时间会下载注册表到本地 => OverriddenStatus是OUT_OF_SERVICE
             * 4.修改配置文件 register请求 =>OverriddenStatus是OUT_OF_SERVICE
             *
             * OverriddenStatus只要不是down 就可以心跳
             * !InstanceStatus.UNKNOWN.equals(registrant.getOverriddenStatus()
             * 说明了 这次的register 肯定不是第一次
             *
             * 问题： 为什么当前注册的client的overriddenStatus不是UNKNOWN?
             *eg:
             * 1.创建了一个InstanceInfo(客户端)的overriddenStatus 肯定是unknown
             * InstanceInfo(客户端)发出第一次注册请求后 eureka server的注册表 记录该服务实例 的 overriddenStatus 是unknown
             * 2.接下来InstanceInfo(客户端)发出一个状态修改请求 修改成OUT_OF_SERVICE
             * 那么eureka server的注册表 记录该服务实例 的 overriddenStatus 是OUT_OF_SERVICE
             * 3.eureka server的注册表会定时更新到InstanceInfo(客户端)
             * 因为 原先InstanceInfo(客户端) 的 overriddenStatus 是unknown  所以出现变化 变成OUT_OF_SERVICE
             * 4.当InstanceInfo(客户端) 配置信息发出了变更 所以发出注册请求
             * 此时的 overriddenStatus 是 OUT_OF_SERVICE 而不是UNKNWON
             * */
            if (!InstanceStatus.UNKNOWN.equals(registrant.getOverriddenStatus())) {
                logger.debug("Found overridden status {} for instance {}. Checking to see if needs to be add to the "
                        + "overrides", registrant.getOverriddenStatus(), registrant.getId());
                /*
                 * OverriddenStatus不是UNKNOWN，那就更新本地缓存
                 * overriddenInstanceStatusMap是server端的缓存 记录Status
                 * registrant.getOverriddenStatus()是外来的
                 *TODO
                 * 问题：为什么当前注册的client的overriddenStatus不是UNKNOWN?
                 * eg:
                 * 1.创建了一个InstanceInfo(客户端)的overriddenStatus 肯定是unknown
                 * InstanceInfo(客户端)发出第一次注册请求后 eureka server的注册表 记录该服务实例 的 overriddenStatus 是unknown
                 * 2.接下来InstanceInfo(客户端)发出一个状态修改请求 修改成OUT_OF_SERVICE
                 * 那么eureka server的注册表 记录该服务实例 的 overriddenStatus 是OUT_OF_SERVICE
                 * 3.eureka server的注册表会定时更新到InstanceInfo(客户端)
                 * 因为 原先InstanceInfo(客户端) 的 overriddenStatus 是unknown  所以出现变化 变成OUT_OF_SERVICE
                 * 4.当InstanceInfo(客户端) 配置信息发出了变更 所以发出注册请求
                 * 此时的 overriddenStatus 是 OUT_OF_SERVICE 而不是UNKNWON
                 * 那么 eureka server的注册表的当前 实例的overriddenStatus和 实例发过来的overriddenStatus相同
                 * 5.如果此时有个用户 通过浏览器直接发出状态修改请求 改成up （原本服务实例和server的交互 出现了第三者）
                 * 那么  eureka server的注册表的当前 实例的overriddenStatus 被修改成 up 而不是 OUT_OF_SERVICE
                 * 6. 如果 InstanceInfo(客户端) 配置信息发出了变更  再一次发出注册请求
                 * 此时的 客户端的overriddenStatus 还是 OUT_OF_SERVICE （4和6操作之间 没有 server定时更新client数据）
                 *TODO
                 * 问题：此时能将注册Client中overriddenStatus的oUT_oF_SERVICE值换掉注册表中的该状态的UP值吗?
                 * 不能 因为用户的状态一定是最新的
                 * */
                if (!overriddenInstanceStatusMap.containsKey(registrant.getId())) {
                    logger.info("Not found overridden id {} and hence adding it", registrant.getId());
                    overriddenInstanceStatusMap.put(registrant.getId(), registrant.getOverriddenStatus());
                }
            }
            InstanceStatus overriddenStatusFromMap = overriddenInstanceStatusMap.get(registrant.getId());
            if (overriddenStatusFromMap != null) {
                logger.info("Storing overridden status {} from map", overriddenStatusFromMap);
                registrant.setOverriddenStatus(overriddenStatusFromMap);
            }

            // Set the status based on the overridden status rules
            //TODO 核心代码 查看源码 ☆☆☆☆☆☆☆☆☆☆☆☆☆☆☆☆
            InstanceStatus overriddenInstanceStatus = getOverriddenInstanceStatus(registrant, existingLease, isReplication);
            registrant.setStatusWithoutDirty(overriddenInstanceStatus);

            // If the lease is registered with UP status, set lease service up timestamp
            if (InstanceStatus.UP.equals(registrant.getStatus())) {
                lease.serviceUp();
            }
            registrant.setActionType(ActionType.ADDED);
            recentlyChangedQueue.add(new RecentlyChangedItem(lease));
            registrant.setLastUpdatedTimestamp();
            invalidateCache(registrant.getAppName(), registrant.getVIPAddress(), registrant.getSecureVipAddress());
            logger.info("Registered instance {}/{} with status {} (replication={})",
                    registrant.getAppName(), registrant.getId(), registrant.getStatus(), isReplication);
        } finally {
            read.unlock();
        }
    }

    /**
     * Cancels the registration of an instance.
     *
     * <p>
     * This is normally invoked by a client when it shuts down informing the
     * server to remove the instance from traffic.
     * </p>
     *
     * @param appName       the application name of the application.
     * @param id            the unique identifier of the instance.
     * @param isReplication true if this is a replication event from other nodes, false
     *                      otherwise.
     * @return true if the instance was removed from the {@link AbstractInstanceRegistry} successfully, false otherwise.
     */
    @Override
    public boolean cancel(String appName, String id, boolean isReplication) {
        return internalCancel(appName, id, isReplication);
    }

    /**
     * {@link #cancel(String, String, boolean)} method is overridden by {@link PeerAwareInstanceRegistry}, so each
     * cancel request is replicated to the peers. This is however not desired for expires which would be counted
     * in the remote peers as valid cancellations, so self preservation mode would not kick-in.
     */
    //TODO 处理服务下架
    protected boolean internalCancel(String appName, String id, boolean isReplication) {
        //TODO删除操作 是写操作 加的是读锁
        read.lock();
        try {
            CANCEL.increment(isReplication);
            Map<String, Lease<InstanceInfo>> gMap = registry.get(appName);
            Lease<InstanceInfo> leaseToCancel = null;
            if (gMap != null) {
                //TODO 将该client从注册表里里面删除 返回被删除的client的lease
                leaseToCancel = gMap.remove(id);
            }
            recentCanceledQueue.add(new Pair<Long, String>(System.currentTimeMillis(), appName + "(" + id + ")"));
            //TODO 从本地缓存map中 该client（服务实例）的overriddenStatus被删除
            InstanceStatus instanceStatus = overriddenInstanceStatusMap.remove(id);

            if (instanceStatus != null) {
                logger.debug("Removed instance id {} from the overridden map which has value {}", id, instanceStatus.name());
            }
            //TODO 说明 原来注册表中没有该client  返回false（404）
            if (leaseToCancel == null) {
                CANCEL_NOT_FOUND.increment(isReplication);
                logger.warn("DS: Registry: cancel failed because Lease is not registered for: {}/{}", appName, id);
                return false;
            } else {
                //TODO 该方法是记录 该client的时间戳
                leaseToCancel.cancel();
                InstanceInfo instanceInfo = leaseToCancel.getHolder();
                String vip = null;
                String svip = null;
                if (instanceInfo != null) {
                    //记录本次操作的类型
                    instanceInfo.setActionType(ActionType.DELETED);
                    //TODO 将本次操作 记录到 “最近更新队列”RecentlyChangedItem
                    recentlyChangedQueue.add(new RecentlyChangedItem(leaseToCancel));
                    instanceInfo.setLastUpdatedTimestamp();
                    vip = instanceInfo.getVIPAddress();
                    svip = instanceInfo.getSecureVipAddress();
                }
                //TODO 相关缓存被删除
                invalidateCache(appName, vip, svip);
                logger.info("Cancelled instance {}/{} (replication={})", appName, id, isReplication);
            }
        } finally {
            read.unlock();
        }

        synchronized (lock) {
            if (this.expectedNumberOfClientsSendingRenews > 0) {
                // Since the client wants to cancel it, reduce the number of clients to send renews.
                this.expectedNumberOfClientsSendingRenews = this.expectedNumberOfClientsSendingRenews - 1;
                updateRenewsPerMinThreshold();
            }
        }

        return true;
    }

    /**
     * Marks the given instance of the given app name as renewed, and also marks whether it originated from
     * replication.
     *
     * @see com.netflix.eureka.lease.LeaseManager#renew(java.lang.String, java.lang.String, boolean)
     * TODO
     * 该方法没有锁 ☆☆☆☆☆☆☆☆☆☆☆☆☆☆☆☆
     * 该方法的任务 计算出当前Client新的status，并将其写入到注册表
     */
    public boolean renew(String appName, String id, boolean isReplication) {
        RENEW.increment(isReplication);
        Map<String, Lease<InstanceInfo>> gMap = registry.get(appName);
        Lease<InstanceInfo> leaseToRenew = null;
        if (gMap != null) {
            leaseToRenew = gMap.get(id);
        }
        //TODO 说明没有注册
        if (leaseToRenew == null) {
            RENEW_NOT_FOUND.increment(isReplication);
            logger.warn("DS: Registry: lease doesn't exist, registering resource: {} - {}", appName, id);
            //TODO server会发送404
            return false;
        } else {
            //TODO 从注册表得到微服务实例 不是外来的
            InstanceInfo instanceInfo = leaseToRenew.getHolder();
            if (instanceInfo != null) {
                // touchASGCache(instanceInfo.getASGName());
                //TODO 续约的话 会对InstanceStatus计算  overriddenInstanceStatus是计算结果
                //TODO 这个方法是重点 查看源码
                // TODO  instanceInfo 和 leaseToRenew 都是注册表里面的 ☆☆☆☆☆☆☆☆☆☆☆☆☆☆☆☆
                InstanceStatus overriddenInstanceStatus = this.getOverriddenInstanceStatus(
                        //TODO 传入 注册表中的instanceInfo, leaseToRenew
                        instanceInfo, leaseToRenew, isReplication);
                //TODO 如果计算得到的状态是InstanceStatus.UNKNOWN
                //TODO InstanceStatus.UNKNOWN就是没有找到  那么不能正常发送心跳
                if (overriddenInstanceStatus == InstanceStatus.UNKNOWN) {
                    logger.info("Instance status UNKNOWN possibly due to deleted override for instance {}"
                            + "; re-register required", instanceInfo.getId());
                    RENEW_NOT_FOUND.increment(isReplication);
                    //TODO 表示续约失败
                    return false;
                }
                //TODO 计算出的状态和注册表的状态不同
                if (!instanceInfo.getStatus().equals(overriddenInstanceStatus)) {
                    logger.info(
                            "The instance status {} is different from overridden instance status {} for instance {}. "
                                    + "Hence setting the status to overridden status", instanceInfo.getStatus().name(),
                            overriddenInstanceStatus.name(),
                            instanceInfo.getId());
                    //TODO 把 计算出的状态更新到注册表中
                    instanceInfo.setStatusWithoutDirty(overriddenInstanceStatus);

                }
            }
            renewsLastMin.increment();
            leaseToRenew.renew();//TODO 修改续约时间
            return true;
        }
    }

    /**
     * @param id               the unique identifier of the instance.
     * @param overriddenStatus Overridden status if any.
     * @deprecated this is expensive, try not to use. See if you can use
     * {@link #storeOverriddenStatusIfRequired(String, String, InstanceStatus)} instead.
     * <p>
     * Stores overridden status if it is not already there. This happens during
     * a reconciliation process during renewal requests.
     */
    @Deprecated
    @Override
    public void storeOverriddenStatusIfRequired(String id, InstanceStatus overriddenStatus) {
        InstanceStatus instanceStatus = overriddenInstanceStatusMap.get(id);
        if ((instanceStatus == null)
                || (!overriddenStatus.equals(instanceStatus))) {
            // We might not have the overridden status if the server got restarted -this will help us maintain
            // the overridden state from the replica
            logger.info(
                    "Adding overridden status for instance id {} and the value is {}",
                    id, overriddenStatus.name());
            overriddenInstanceStatusMap.put(id, overriddenStatus);
            List<InstanceInfo> instanceInfo = this.getInstancesById(id, false);
            if ((instanceInfo != null) && (!instanceInfo.isEmpty())) {
                instanceInfo.iterator().next().setOverriddenStatus(overriddenStatus);
                logger.info(
                        "Setting the overridden status for instance id {} and the value is {} ",
                        id, overriddenStatus.name());

            }
        }
    }

    /**
     * Stores overridden status if it is not already there. This happens during
     * a reconciliation process during renewal requests.
     *
     * @param appName          the application name of the instance.
     * @param id               the unique identifier of the instance.
     * @param overriddenStatus overridden status if any.
     *                         TODO 同步的本质是同步 overriddenStatus
     */
    @Override
    public void storeOverriddenStatusIfRequired(String appName, String id, InstanceStatus overriddenStatus) {
        InstanceStatus instanceStatus = overriddenInstanceStatusMap.get(id);//TODO 得到本地缓存的 instanceStatus
        //TODO 判断 外来server传入的 和本地的是否相同
        if ((instanceStatus == null) || (!overriddenStatus.equals(instanceStatus))) {
            // We might not have the overridden status if the server got
            // restarted -this will help us maintain the overridden state
            // from the replica
            logger.info("Adding overridden status for instance id {} and the value is {}",
                    id, overriddenStatus.name());
            //TODO 设置 微服务实例id 和对应的状态
            overriddenInstanceStatusMap.put(id, overriddenStatus);
            InstanceInfo instanceInfo = this.getInstanceByAppAndId(appName, id, false);
            //TODO 把外来的 overriddenStatus 设置成自己缓存的
            instanceInfo.setOverriddenStatus(overriddenStatus);
            logger.info("Set the overridden status for instance (appname:{}, id:{}} and the value is {} ",
                    appName, id, overriddenStatus.name());
        }
    }

    /**
     * Updates the status of an instance. Normally happens to put an instance
     * between {@link InstanceStatus#OUT_OF_SERVICE} and
     * {@link InstanceStatus#UP} to put the instance in and out of traffic.
     *
     * @param appName            the application name of the instance.
     * @param id                 the unique identifier of the instance.
     * @param newStatus          the new {@link InstanceStatus}.
     * @param lastDirtyTimestamp last timestamp when this instance information was updated.
     * @param isReplication      true if this is a replication event from other nodes, false
     *                           otherwise.
     * @return true if the status was successfully updated, false otherwise.
     */
    @Override
    public boolean statusUpdate(String appName, String id,
                                InstanceStatus newStatus, String lastDirtyTimestamp,
                                boolean isReplication) {
        read.lock();
        try {
            STATUS_UPDATE.increment(isReplication);
            Map<String, Lease<InstanceInfo>> gMap = registry.get(appName);
            Lease<InstanceInfo> lease = null;
            if (gMap != null) {
                lease = gMap.get(id);
            }
            if (lease == null) {
                return false;
            } else {
                lease.renew();
                InstanceInfo info = lease.getHolder();
                // Lease is always created with its instance info object.
                // This log statement is provided as a safeguard, in case this invariant is violated.
                if (info == null) {
                    logger.error("Found Lease without a holder for instance id {}", id);
                }
                if ((info != null) && !(info.getStatus().equals(newStatus))) {
                    // Mark service as UP if needed
                    if (InstanceStatus.UP.equals(newStatus)) {
                        lease.serviceUp();
                    }
                    // This is NAC overridden status
                    overriddenInstanceStatusMap.put(id, newStatus);
                    // Set it for transfer of overridden status to replica on
                    // replica start up
                    info.setOverriddenStatus(newStatus);
                    long replicaDirtyTimestamp = 0;
                    info.setStatusWithoutDirty(newStatus);
                    if (lastDirtyTimestamp != null) {
                        replicaDirtyTimestamp = Long.parseLong(lastDirtyTimestamp);
                    }
                    // If the replication's dirty timestamp is more than the existing one, just update
                    // it to the replica's.
                    if (replicaDirtyTimestamp > info.getLastDirtyTimestamp()) {
                        info.setLastDirtyTimestamp(replicaDirtyTimestamp);
                    }
                    info.setActionType(ActionType.MODIFIED);
                    recentlyChangedQueue.add(new RecentlyChangedItem(lease));
                    info.setLastUpdatedTimestamp();
                    invalidateCache(appName, info.getVIPAddress(), info.getSecureVipAddress());
                }
                return true;
            }
        } finally {
            read.unlock();
        }
    }

    /**
     * Removes status override for a give instance.
     *
     * @param appName            the application name of the instance.
     * @param id                 the unique identifier of the instance.
     * @param newStatus          the new {@link InstanceStatus}.
     * @param lastDirtyTimestamp last timestamp when this instance information was updated.
     * @param isReplication      true if this is a replication event from other nodes, false
     *                           otherwise.
     * @return true if the status was successfully updated, false otherwise.
     * TODO 注意，并没有将该Client从注册表中进行删除 ☆☆☆☆☆☆☆☆☆
     */
    @Override
    public boolean deleteStatusOverride(String appName, String id,
                                        InstanceStatus newStatus,
                                        String lastDirtyTimestamp,
                                        boolean isReplication) {
        read.lock();
        try {
            STATUS_OVERRIDE_DELETE.increment(isReplication);
            //TODO 根据微服务名字 找到 实例id和实例的map
            Map<String, Lease<InstanceInfo>> gMap = registry.get(appName);
            Lease<InstanceInfo> lease = null;
            if (gMap != null) {
                lease = gMap.get(id);
            }
            if (lease == null) {
                return false;
            } else {
                lease.renew();//TODO 不管什么请求 本质都是续约
                InstanceInfo info = lease.getHolder();

                // Lease is always created with its instance info object.
                // This log statement is provided as a safeguard, in case this invariant is violated.
                if (info == null) {
                    logger.error("Found Lease without a holder for instance id {}", id);
                }
                //TODO 1.将指定Client的overriddenStatus从overriddenInstanceStatusMap中删除☆☆☆☆☆☆☆☆
                // 返回删除的状态
                InstanceStatus currentOverride = overriddenInstanceStatusMap.remove(id);
                //TODO 2.修改注册表中该Client的status为UNKNOWN
                if (currentOverride != null && info != null) {
                    //TODO 一个实例 默认状态就是InstanceStatus.UNKNOWN 这里恢复到默认值
                    info.setOverriddenStatus(InstanceStatus.UNKNOWN);
                    // newStatus是外部传入的
                    //TODO 设置新的状态 也就是 newStatus=  InstanceStatus.UNKNOWN
                    info.setStatusWithoutDirty(newStatus);
                    long replicaDirtyTimestamp = 0;
                    if (lastDirtyTimestamp != null) {
                        replicaDirtyTimestamp = Long.parseLong(lastDirtyTimestamp);
                    }
                    // If the replication's dirty timestamp is more than the existing one, just update
                    // it to the replica's.
                    if (replicaDirtyTimestamp > info.getLastDirtyTimestamp()) {
                        info.setLastDirtyTimestamp(replicaDirtyTimestamp);
                    }
                    info.setActionType(ActionType.MODIFIED);
                    //TODO 3.将本次修改写入到recentlyChangedQueue缓存
                    recentlyChangedQueue.add(new RecentlyChangedItem(lease));
                    info.setLastUpdatedTimestamp();
                    invalidateCache(appName, info.getVIPAddress(), info.getSecureVipAddress());
                }
                return true;
            }
        } finally {
            read.unlock();
        }
    }

    /**
     * Evicts everything in the instance registry that has expired, if expiry is enabled.
     *
     * @see com.netflix.eureka.lease.LeaseManager#evict()
     */
    @Override
    public void evict() {
        evict(0l);
    }

    public void evict(long additionalLeaseMs) {
        logger.debug("Running the evict task");
        //若注册表中的实例不会过期，则直接结束，不用清除
        if (!isLeaseExpirationEnabled()) {
            logger.debug("DS: lease expiration is currently disabled.");
            return;
        }

        // We collect first all expired items, to evict them in random order. For large eviction sets,
        // if we do not that, we might wipe out whole apps before self preservation kicks in. By randomizing it,
        // the impact should be evenly distributed across all applications.
        List<Lease<InstanceInfo>> expiredLeases = new ArrayList<>();
        //遍历注册表，查找所有过期的cLient
        for (Entry<String, Map<String, Lease<InstanceInfo>>> groupEntry : registry.entrySet()) {
            Map<String, Lease<InstanceInfo>> leaseMap = groupEntry.getValue();
            if (leaseMap != null) {
                for (Entry<String, Lease<InstanceInfo>> leaseEntry : leaseMap.entrySet()) {
                    Lease<InstanceInfo> lease = leaseEntry.getValue();
                    // 只要当前的lease 过期了 就把lease 添加到expiredLeases
                    if (lease.isExpired(additionalLeaseMs) && lease.getHolder() != null) {
                        expiredLeases.add(lease);
                    }
                }
            }
        }

        // To compensate for GC pauses or drifting local time, we need to use current registry size as a base for
        // triggering self-preservation. Without that we would wipe out full registry.
        //得到 注册表中注册的客户端（服务实例）的数量
        int registrySize = (int) getLocalRegistrySize();
        //计算出开启自我保护机制的阈值
        int registrySizeThreshold = (int) (registrySize * serverConfig.getRenewalPercentThreshold());
        // 计算出 可以清除的阈值
        int evictionLimit = registrySize - registrySizeThreshold;
        //确定 要清除的client个数
        int toEvict = Math.min(expiredLeases.size(), evictionLimit);
        if (toEvict > 0) {
            logger.info("Evicting {} items (expired={}, evictionLimit={})", toEvict, expiredLeases.size(), evictionLimit);

            Random random = new Random(System.currentTimeMillis());
            for (int i = 0; i < toEvict; i++) {
                //使用洗牌算法 删除一个元素
                // Pick a random item (Knuth shuffle algorithm)
                //得到随机数
                int next = i + random.nextInt(expiredLeases.size() - i);
                //交换expiredLeases列表中 第i和第next个元素
                Collections.swap(expiredLeases, i, next);
                //得到 第i个元素
                Lease<InstanceInfo> lease = expiredLeases.get(i);
                //获取 元素对应的微服务名字
                String appName = lease.getHolder().getAppName();
                // 获取 id
                String id = lease.getHolder().getId();
                EXPIRED.increment();
                logger.warn("DS: Registry: expired lease for {}/{}", appName, id);
                //TODO 核心代码 查看源码 ☆☆☆☆☆☆☆☆☆☆☆☆☆☆☆☆
                internalCancel(appName, id, false);
            }
        }
    }


    /**
     * Returns the given app that is in this instance only, falling back to other regions transparently only
     * if specified in this client configuration.
     *
     * @param appName the application name of the application
     * @return the application
     * @see com.netflix.discovery.shared.LookupService#getApplication(java.lang.String)
     */
    @Override
    public Application getApplication(String appName) {
        boolean disableTransparentFallback = serverConfig.disableTransparentFallbackToOtherRegion();
        return this.getApplication(appName, !disableTransparentFallback);
    }

    /**
     * Get application information.
     *
     * @param appName             The name of the application
     * @param includeRemoteRegion true, if we need to include applications from remote regions
     *                            as indicated by the region {@link URL} by this property
     *                            {@link EurekaServerConfig#getRemoteRegionUrls()}, false otherwise
     * @return the application
     */
    @Override
    public Application getApplication(String appName, boolean includeRemoteRegion) {
        Application app = null;

        Map<String, Lease<InstanceInfo>> leaseMap = registry.get(appName);

        if (leaseMap != null && leaseMap.size() > 0) {
            for (Entry<String, Lease<InstanceInfo>> entry : leaseMap.entrySet()) {
                if (app == null) {
                    app = new Application(appName);
                }
                app.addInstance(decorateInstanceInfo(entry.getValue()));
            }
        } else if (includeRemoteRegion) {
            for (RemoteRegionRegistry remoteRegistry : this.regionNameVSRemoteRegistry.values()) {
                Application application = remoteRegistry.getApplication(appName);
                if (application != null) {
                    return application;
                }
            }
        }
        return app;
    }

    /**
     * Get all applications in this instance registry, falling back to other regions if allowed in the Eureka config.
     *
     * @return the list of all known applications
     * @see com.netflix.discovery.shared.LookupService#getApplications()
     */
    public Applications getApplications() {
        boolean disableTransparentFallback = serverConfig.disableTransparentFallbackToOtherRegion();
        if (disableTransparentFallback) {
            return getApplicationsFromLocalRegionOnly();
        } else {
            return getApplicationsFromAllRemoteRegions();  // Behavior of falling back to remote region can be disabled.
        }
    }

    /**
     * Returns applications including instances from all remote regions. <br/>
     * Same as calling {@link #getApplicationsFromMultipleRegions(String[])} with a <code>null</code> argument.
     */
    public Applications getApplicationsFromAllRemoteRegions() {
        return getApplicationsFromMultipleRegions(allKnownRemoteRegions);
    }

    /**
     * Returns applications including instances from local region only. <br/>
     * Same as calling {@link #getApplicationsFromMultipleRegions(String[])} with an empty array.
     */
    @Override
    public Applications getApplicationsFromLocalRegionOnly() {
        return getApplicationsFromMultipleRegions(EMPTY_STR_ARRAY);
    }

    /**
     * This method will return applications with instances from all passed remote regions as well as the current region.
     * Thus, this gives a union view of instances from multiple regions. <br/>
     * The application instances for which this union will be done can be restricted to the names returned by
     * {@link EurekaServerConfig#getRemoteRegionAppWhitelist(String)} for every region. In case, there is no whitelist
     * defined for a region, this method will also look for a global whitelist by passing <code>null</code> to the
     * method {@link EurekaServerConfig#getRemoteRegionAppWhitelist(String)} <br/>
     * If you are not selectively requesting for a remote region, use {@link #getApplicationsFromAllRemoteRegions()}
     * or {@link #getApplicationsFromLocalRegionOnly()}
     *
     * @param remoteRegions The remote regions for which the instances are to be queried. The instances may be limited
     *                      by a whitelist as explained above. If <code>null</code> or empty no remote regions are
     *                      included.
     * @return The applications with instances from the passed remote regions as well as local region. The instances
     * from remote regions can be only for certain whitelisted apps as explained above.
     */
    public Applications getApplicationsFromMultipleRegions(String[] remoteRegions) {

        boolean includeRemoteRegion = null != remoteRegions && remoteRegions.length != 0;

        logger.debug("Fetching applications registry with remote regions: {}, Regions argument {}",
                includeRemoteRegion, remoteRegions);

        if (includeRemoteRegion) {
            GET_ALL_WITH_REMOTE_REGIONS_CACHE_MISS.increment();
        } else {
            GET_ALL_CACHE_MISS.increment();
        }
        //TODO 包装了eureka server返回的所有注册信息的类
        Applications apps = new Applications();
        apps.setVersion(1L);
        //TODO  获取本地注册表中的服务(将双层map变为applications)
        for (Entry<String, Map<String, Lease<InstanceInfo>>> entry : registry.entrySet()) {
            Application app = null;
            //TODO 如果注册表中 不为空 就遍历注册表
            if (entry.getValue() != null) {
                //TODO 逦历注册表内层map的所有entry(这些entry 中的Lease来自于同一个微服务，即微服务名称相同)
                for (Entry<String, Lease<InstanceInfo>> stringLeaseEntry : entry.getValue().entrySet()) {
                    //TODO 获取到当前遍历entry 的value,Lease对象（可以看作是client)
                    Lease<InstanceInfo> lease = stringLeaseEntry.getValue();
                    if (app == null) {
                        app = new Application(lease.getHolder().getAppName());
                    }
                    //当前lease封装为instanceInfo 并放入到app中
                    app.addInstance(decorateInstanceInfo(lease));
                }
            }
            if (app != null) {
                apps.addApplication(app);
            }
        }
        //获取远程Region中的所有服务(将来自于远程Region 中的applications添加到这里的apps中)
        if (includeRemoteRegion) {
            //遍历所有region
            for (String remoteRegion : remoteRegions) {
                // 获取 当前遍历到的region的注册表
                RemoteRegionRegistry remoteRegistry = regionNameVSRemoteRegistry.get(remoteRegion);
                if (null != remoteRegistry) {
                    //获取远程Region的注册数据，是一个applications
                    Applications remoteApps = remoteRegistry.getApplications();
                    //遍历当前的applications
                    for (Application application : remoteApps.getRegisteredApplications()) {
                        if (shouldFetchFromRemoteRegistry(application.getName(), remoteRegion)) {
                            logger.info("Application {}  fetched from the remote region {}",
                                    application.getName(), remoteRegion);
                            //根据微服务名称获取到其application
                            Application appInstanceTillNow = apps.getRegisteredApplications(application.getName());
                            //将这个application放入到apps
                            if (appInstanceTillNow == null) {
                                appInstanceTillNow = new Application(application.getName());
                                apps.addApplication(appInstanceTillNow);
                            }
                            /*
                             * 将当前遍历的applicaion中所有的instanceInfo放入到appInstanceTillNow中,
                             * 即放入到了apps中
                             * */
                            for (InstanceInfo instanceInfo : application.getInstances()) {
                                appInstanceTillNow.addInstance(instanceInfo);
                            }
                        } else {
                            logger.debug("Application {} not fetched from the remote region {} as there exists a "
                                            + "whitelist and this app is not in the whitelist.",
                                    application.getName(), remoteRegion);
                        }
                    }
                } else {
                    logger.warn("No remote registry available for the remote region {}", remoteRegion);
                }
            }
        }
        apps.setAppsHashCode(apps.getReconcileHashCode());
        return apps;
    }

    private boolean shouldFetchFromRemoteRegistry(String appName, String remoteRegion) {
        Set<String> whiteList = serverConfig.getRemoteRegionAppWhitelist(remoteRegion);
        if (null == whiteList) {
            whiteList = serverConfig.getRemoteRegionAppWhitelist(null); // see global whitelist.
        }
        return null == whiteList || whiteList.contains(appName);
    }

    /**
     * Get the registry information about all {@link Applications}.
     *
     * @param includeRemoteRegion true, if we need to include applications from remote regions
     *                            as indicated by the region {@link URL} by this property
     *                            {@link EurekaServerConfig#getRemoteRegionUrls()}, false otherwise
     * @return applications
     * @deprecated Use {@link #getApplicationsFromMultipleRegions(String[])} instead. This method has a flawed behavior
     * of transparently falling back to a remote region if no instances for an app is available locally. The new
     * behavior is to explicitly specify if you need a remote region.
     */
    @Deprecated
    public Applications getApplications(boolean includeRemoteRegion) {
        GET_ALL_CACHE_MISS.increment();
        Applications apps = new Applications();
        apps.setVersion(1L);
        for (Entry<String, Map<String, Lease<InstanceInfo>>> entry : registry.entrySet()) {
            Application app = null;

            if (entry.getValue() != null) {
                for (Entry<String, Lease<InstanceInfo>> stringLeaseEntry : entry.getValue().entrySet()) {

                    Lease<InstanceInfo> lease = stringLeaseEntry.getValue();

                    if (app == null) {
                        app = new Application(lease.getHolder().getAppName());
                    }

                    app.addInstance(decorateInstanceInfo(lease));
                }
            }
            if (app != null) {
                apps.addApplication(app);
            }
        }
        if (includeRemoteRegion) {
            for (RemoteRegionRegistry remoteRegistry : this.regionNameVSRemoteRegistry.values()) {
                Applications applications = remoteRegistry.getApplications();
                for (Application application : applications
                        .getRegisteredApplications()) {
                    Application appInLocalRegistry = apps
                            .getRegisteredApplications(application.getName());
                    if (appInLocalRegistry == null) {
                        apps.addApplication(application);
                    }
                }
            }
        }
        apps.setAppsHashCode(apps.getReconcileHashCode());
        return apps;
    }

    /**
     * Get the registry information about the delta changes. The deltas are
     * cached for a window specified by
     * {@link EurekaServerConfig#getRetentionTimeInMSInDeltaQueue()}. Subsequent
     * requests for delta information may return the same information and client
     * must make sure this does not adversely affect them.
     *
     * @return all application deltas.
     * @deprecated use {@link #getApplicationDeltasFromMultipleRegions(String[])} instead. This method has a
     * flawed behavior of transparently falling back to a remote region if no instances for an app is available locally.
     * The new behavior is to explicitly specify if you need a remote region.
     */
    @Deprecated
    public Applications getApplicationDeltas() {
        GET_ALL_CACHE_MISS_DELTA.increment();
        Applications apps = new Applications();
        apps.setVersion(responseCache.getVersionDelta().get());
        Map<String, Application> applicationInstancesMap = new HashMap<String, Application>();
        write.lock();
        try {
            Iterator<RecentlyChangedItem> iter = this.recentlyChangedQueue.iterator();
            logger.debug("The number of elements in the delta queue is : {}",
                    this.recentlyChangedQueue.size());
            while (iter.hasNext()) {
                Lease<InstanceInfo> lease = iter.next().getLeaseInfo();
                InstanceInfo instanceInfo = lease.getHolder();
                logger.debug(
                        "The instance id {} is found with status {} and actiontype {}",
                        instanceInfo.getId(), instanceInfo.getStatus().name(), instanceInfo.getActionType().name());
                Application app = applicationInstancesMap.get(instanceInfo
                        .getAppName());
                if (app == null) {
                    app = new Application(instanceInfo.getAppName());
                    applicationInstancesMap.put(instanceInfo.getAppName(), app);
                    apps.addApplication(app);
                }
                app.addInstance(new InstanceInfo(decorateInstanceInfo(lease)));
            }

            boolean disableTransparentFallback = serverConfig.disableTransparentFallbackToOtherRegion();

            if (!disableTransparentFallback) {
                Applications allAppsInLocalRegion = getApplications(false);

                for (RemoteRegionRegistry remoteRegistry : this.regionNameVSRemoteRegistry.values()) {
                    Applications applications = remoteRegistry.getApplicationDeltas();
                    for (Application application : applications.getRegisteredApplications()) {
                        Application appInLocalRegistry =
                                allAppsInLocalRegion.getRegisteredApplications(application.getName());
                        if (appInLocalRegistry == null) {
                            apps.addApplication(application);
                        }
                    }
                }
            }

            Applications allApps = getApplications(!disableTransparentFallback);
            apps.setAppsHashCode(allApps.getReconcileHashCode());
            return apps;
        } finally {
            write.unlock();
        }
    }

    /**
     * Gets the application delta also including instances from the passed remote regions, with the instances from the
     * local region. <br/>
     * <p>
     * The remote regions from where the instances will be chosen can further be restricted if this application does not
     * appear in the whitelist specified for the region as returned by
     * {@link EurekaServerConfig#getRemoteRegionAppWhitelist(String)} for a region. In case, there is no whitelist
     * defined for a region, this method will also look for a global whitelist by passing <code>null</code> to the
     * method {@link EurekaServerConfig#getRemoteRegionAppWhitelist(String)} <br/>
     *
     * @param remoteRegions The remote regions for which the instances are to be queried. The instances may be limited
     *                      by a whitelist as explained above. If <code>null</code> all remote regions are included.
     *                      If empty list then no remote region is included.
     * @return The delta with instances from the passed remote regions as well as local region. The instances
     * from remote regions can be further be restricted as explained above. <code>null</code> if the application does
     * not exist locally or in remote regions.
     */
    public Applications getApplicationDeltasFromMultipleRegions(String[] remoteRegions) {
        if (null == remoteRegions) {
            remoteRegions = allKnownRemoteRegions; // null means all remote regions.
        }

        boolean includeRemoteRegion = remoteRegions.length != 0;

        if (includeRemoteRegion) {
            GET_ALL_WITH_REMOTE_REGIONS_CACHE_MISS_DELTA.increment();
        } else {
            GET_ALL_CACHE_MISS_DELTA.increment();
        }

        Applications apps = new Applications();
        apps.setVersion(responseCache.getVersionDeltaWithRegions().get());
        Map<String, Application> applicationInstancesMap = new HashMap<String, Application>();
        //TODO 读操作添加了写锁
        write.lock();
        try {
            //TODO 遍历recentlychangedQueue中所有的客户端信息，并写入到apps
            // 近期发生变更的微服务实例的迭代器
            Iterator<RecentlyChangedItem> iter = this.recentlyChangedQueue.iterator();
            logger.debug("The number of elements in the delta queue is :{}", this.recentlyChangedQueue.size());
            while (iter.hasNext()) {
                //TODO  根据队列元素 获取其包含的lease和instanceInfo
                Lease<InstanceInfo> lease = iter.next().getLeaseInfo();
                InstanceInfo instanceInfo = lease.getHolder();
                logger.debug("The instance id {} is found with status {} and actiontype {}",
                        instanceInfo.getId(), instanceInfo.getStatus().name(), instanceInfo.getActionType().name());
                // 根据未付名称 获取对应的application
                Application app = applicationInstancesMap.get(instanceInfo.getAppName());
                // 将app加入到apps 再将lease封装成instanceInfo 写入到app
                if (app == null) {
                    app = new Application(instanceInfo.getAppName());
                    applicationInstancesMap.put(instanceInfo.getAppName(), app);
                    apps.addApplication(app);
                }
                /*
                 * 1.为什么decorateInstanceInfo()返回的就是InstanceInfo，在这里还需要再将其创建为一个InstanceInfo?
                 *  decorateInstanceinfo(Lease)返回的instanceInfo实例，实际是来自recentlyChangedQueue的实例
                 * 若在当前方法结束后，在当前方法的返回值apps还未发送给client之前，某个Lease的数据又被修改了。那么，
                 * 发送给cLient的这个数据就是最新的二次修改的数据。这种情况下会导致client接收到的这个增量数据中丢失了
                 * 次修改时的情况，而这个丢失会引发client进行全量下载。为了避免这种情况的发生,使用decorateInstanceInfo()
                 * 返回的instanceInfo数据再创建一个新的instanceInfo，使当前方法返回的这个apps中的instanceInfo与recentlyChangedQueue无关
                 * 2.为什么全量下载时没有做这里的第二次创建操作?
                 * 对于全量下载，其操作的共享集合为注册表registry，与recentlyChangedoueue无关。
                 * 若在当前方法（全量下载方法）结束后，在当前方法的返回值apps还未发送给client之前，
                 * 某个Lease的数据又被修改了。那么，发送给client的这个数据就是最新的二次修改的数据。
                 * 二次修改前的数据对于client来说就丢失了。但对于客户端来说，这次丢失是好还是不好呢?
                 * 是好的，可以使Client获取到最新的微服务信息。所以, 不用decorateInstanceInfo()的结果创建新的对象
                 * */
                app.addInstance(new InstanceInfo(decorateInstanceInfo(lease)));
            }
            //处理远程region的服务
            if (includeRemoteRegion) {
                //遍历所有region
                for (String remoteRegion : remoteRegions) {
                    // 获取 当前遍历到的region的注册表
                    RemoteRegionRegistry remoteRegistry = regionNameVSRemoteRegistry.get(remoteRegion);
                    if (null != remoteRegistry) {
                        //遍历当前的applications
                        Applications remoteAppsDelta = remoteRegistry.getApplicationDeltas();
                        if (null != remoteAppsDelta) {
                            for (Application application : remoteAppsDelta.getRegisteredApplications()) {
                                if (shouldFetchFromRemoteRegistry(application.getName(), remoteRegion)) {
                                    //根据微服务名称获取到其application
                                    Application appInstanceTillNow =
                                            apps.getRegisteredApplications(application.getName());
                                    //将这个application放入到apps
                                    if (appInstanceTillNow == null) {
                                        appInstanceTillNow = new Application(application.getName());
                                        apps.addApplication(appInstanceTillNow);
                                    }
                                    /*
                                     * 将当前遍历的applicaion中所有的instanceInfo放入到appInstanceTillNow中,
                                     * 即放入到了apps中
                                     * */
                                    for (InstanceInfo instanceInfo : application.getInstances()) {
                                        appInstanceTillNow.addInstance(new InstanceInfo(instanceInfo));
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Applications allApps = getApplicationsFromMultipleRegions(remoteRegions);
            apps.setAppsHashCode(allApps.getReconcileHashCode());
            return apps;
        } finally {
            write.unlock();
        }
    }

    /**
     * Gets the {@link InstanceInfo} information.
     *
     * @param appName the application name for which the information is requested.
     * @param id      the unique identifier of the instance.
     * @return the information about the instance.
     */
    @Override
    public InstanceInfo getInstanceByAppAndId(String appName, String id) {
        return this.getInstanceByAppAndId(appName, id, true);
    }

    /**
     * Gets the {@link InstanceInfo} information.
     *
     * @param appName              the application name for which the information is requested.
     * @param id                   the unique identifier of the instance.
     * @param includeRemoteRegions true, if we need to include applications from remote regions
     *                             as indicated by the region {@link URL} by this property
     *                             {@link EurekaServerConfig#getRemoteRegionUrls()}, false otherwise
     * @return the information about the instance.
     */
    @Override
    public InstanceInfo getInstanceByAppAndId(String appName, String id, boolean includeRemoteRegions) {
        //TODO 根据微服务名字 得到 内层map
        Map<String, Lease<InstanceInfo>> leaseMap = registry.get(appName);
        Lease<InstanceInfo> lease = null;
        if (leaseMap != null) {//TODO 内层map不为空 就根据实例的id 得到 实例的包装类
            lease = leaseMap.get(id);
        }
        //TODO 实例存在 有没有启用实例过期（自我保护机制是否开启）  实例有没有过期
        if (lease != null
                && (!isLeaseExpirationEnabled() || !lease.isExpired())) {
            //TODO 对微服务实例的包装了 再包装 查看下面的源码
            return decorateInstanceInfo(lease);
            //TODO 上面的if为false
        } else if (includeRemoteRegions) {//TODO 是否有远程region
            //TODO 远程region和远程region对应的注册中心
            for (RemoteRegionRegistry remoteRegistry : this.regionNameVSRemoteRegistry.values()) {
                //TODO 得到远程region对应的注册中心后 再根据微服务名字得到微服务application
                Application application = remoteRegistry.getApplication(appName);
                if (application != null) {
                    //TODO 从微服务application 根据id 得到微服务实例
                    return application.getByInstanceId(id);
                }
            }
        }
        return null;
    }

    /**
     * @see com.netflix.discovery.shared.LookupService#getInstancesById(String)
     * @deprecated Try {@link #getInstanceByAppAndId(String, String)} instead.
     * <p>
     * Get all instances by ID, including automatically asking other regions if the ID is unknown.
     */
    @Deprecated
    public List<InstanceInfo> getInstancesById(String id) {
        return this.getInstancesById(id, true);
    }

    /**
     * @param id                   the unique id of the instance
     * @param includeRemoteRegions true, if we need to include applications from remote regions
     *                             as indicated by the region {@link URL} by this property
     *                             {@link EurekaServerConfig#getRemoteRegionUrls()}, false otherwise
     * @return list of InstanceInfo objects.
     * @deprecated Try {@link #getInstanceByAppAndId(String, String, boolean)} instead.
     * <p>
     * Get the list of instances by its unique id.
     */
    @Deprecated
    public List<InstanceInfo> getInstancesById(String id, boolean includeRemoteRegions) {
        List<InstanceInfo> list = new ArrayList<>();

        for (Iterator<Entry<String, Map<String, Lease<InstanceInfo>>>> iter =
             registry.entrySet().iterator(); iter.hasNext(); ) {

            Map<String, Lease<InstanceInfo>> leaseMap = iter.next().getValue();
            if (leaseMap != null) {
                Lease<InstanceInfo> lease = leaseMap.get(id);

                if (lease == null || (isLeaseExpirationEnabled() && lease.isExpired())) {
                    continue;
                }

                if (list == Collections.EMPTY_LIST) {
                    list = new ArrayList<>();
                }
                list.add(decorateInstanceInfo(lease));
            }
        }
        if (list.isEmpty() && includeRemoteRegions) {
            for (RemoteRegionRegistry remoteRegistry : this.regionNameVSRemoteRegistry.values()) {
                for (Application application : remoteRegistry.getApplications()
                        .getRegisteredApplications()) {
                    InstanceInfo instanceInfo = application.getByInstanceId(id);
                    if (instanceInfo != null) {
                        list.add(instanceInfo);
                        return list;
                    }
                }
            }
        }
        return list;
    }

    //TODO 传入的是注册表里得到的微服务实例的包装类对象   输出  微服务实例 本身
    private InstanceInfo decorateInstanceInfo(Lease<InstanceInfo> lease) {
        InstanceInfo info = lease.getHolder();//TODO 得到 微服务实例  holder

        // client app settings
        int renewalInterval = LeaseInfo.DEFAULT_LEASE_RENEWAL_INTERVAL;
        int leaseDuration = LeaseInfo.DEFAULT_LEASE_DURATION;

        // TODO: clean this up
        if (info.getLeaseInfo() != null) {//TODO 如果在注册表里的微服务实例的续约信息不为空
            //TODO 下面2个信息都是从注册表里的微服务实例读取到
            renewalInterval = info.getLeaseInfo().getRenewalIntervalInSecs();
            leaseDuration = info.getLeaseInfo().getDurationInSecs();
        }
        //TODO 设置info 就是设置到注册表里去
        info.setLeaseInfo(LeaseInfo.Builder.newBuilder()
                .setRegistrationTimestamp(lease.getRegistrationTimestamp())
                .setRenewalTimestamp(lease.getLastRenewalTimestamp())
                .setServiceUpTimestamp(lease.getServiceUpTimestamp())
                .setRenewalIntervalInSecs(renewalInterval)
                .setDurationInSecs(leaseDuration)
                .setEvictionTimestamp(lease.getEvictionTimestamp()).build());

        info.setIsCoordinatingDiscoveryServer();
        //TODO 输出  微服务实例 本身
        return info;
    }

    /**
     * Servo route; do not call.
     *
     * @return servo data
     */
    @com.netflix.servo.annotations.Monitor(name = "numOfRenewsInLastMin",
            description = "Number of total heartbeats received in the last minute", type = DataSourceType.GAUGE)
    @Override
    public long getNumOfRenewsInLastMin() {
        return renewsLastMin.getCount();
    }


    /**
     * Gets the threshold for the renewals per minute.
     *
     * @return the integer representing the threshold for the renewals per
     * minute.
     */
    @com.netflix.servo.annotations.Monitor(name = "numOfRenewsPerMinThreshold", type = DataSourceType.GAUGE)
    @Override
    public int getNumOfRenewsPerMinThreshold() {
        return numberOfRenewsPerMinThreshold;
    }

    /**
     * Get the N instances that are most recently registered.
     *
     * @return
     */
    @Override
    public List<Pair<Long, String>> getLastNRegisteredInstances() {
        List<Pair<Long, String>> list = new ArrayList<>(recentRegisteredQueue);
        Collections.reverse(list);
        return list;
    }

    /**
     * Get the N instances that have most recently canceled.
     *
     * @return
     */
    @Override
    public List<Pair<Long, String>> getLastNCanceledInstances() {
        List<Pair<Long, String>> list = new ArrayList<>(recentCanceledQueue);
        Collections.reverse(list);
        return list;
    }

    private void invalidateCache(String appName, @Nullable String vipAddress, @Nullable String secureVipAddress) {
        // invalidate cache
        responseCache.invalidate(appName, vipAddress, secureVipAddress);
    }

    protected void updateRenewsPerMinThreshold() {
        /*
         * TODO server.expected-client-renewal-interval-seconds=3o 期望客户端发送续约的间隔
         *  客户端数量 * (60 / 心跳间隔（默认为30） ) * 自我保护开启的阈值因子 =
         *  客户端数量 * 每个客户端每分钟发送心跳的数量 * 阈值因子 =
         *  所有client每分钟发送的心跳数量 * 阈值因子 =
         *  = 当前server开启自我保护机制的最小心跳数量 = 页面上的 Renew Threshold
         * */
        this.numberOfRenewsPerMinThreshold = (int) (this.expectedNumberOfClientsSendingRenews
                * (60.0 / serverConfig.getExpectedClientRenewalIntervalSeconds())
                * serverConfig.getRenewalPercentThreshold());
    }

    private static final class RecentlyChangedItem {
        private long lastUpdateTime;
        private Lease<InstanceInfo> leaseInfo;

        public RecentlyChangedItem(Lease<InstanceInfo> lease) {
            this.leaseInfo = lease;
            lastUpdateTime = System.currentTimeMillis();
        }

        public long getLastUpdateTime() {
            return this.lastUpdateTime;
        }

        public Lease<InstanceInfo> getLeaseInfo() {
            return this.leaseInfo;
        }
    }

    protected void postInit() {
        renewsLastMin.start();
        //若清除任务不为null，则先将该任务取消
        if (evictionTaskRef.get() != null) {
            evictionTaskRef.get().cancel();
        }
        //将新的清除任务添加到ref对象，并开启这个定时任务
        evictionTaskRef.set(new EvictionTask());
        evictionTimer.schedule(evictionTaskRef.get(),
                serverConfig.getEvictionIntervalTimerInMs(),
                serverConfig.getEvictionIntervalTimerInMs());
    }

    /**
     * Perform all cleanup and shutdown operations.
     */
    @Override
    public void shutdown() {
        deltaRetentionTimer.cancel();
        evictionTimer.cancel();
        renewsLastMin.stop();
        responseCache.stop();
    }

    @com.netflix.servo.annotations.Monitor(name = "numOfElementsinInstanceCache", description = "Number of overrides in the instance Cache", type = DataSourceType.GAUGE)
    public long getNumberofElementsininstanceCache() {
        return overriddenInstanceStatusMap.size();
    }

    /* visible for testing */ class EvictionTask extends TimerTask {

        private final AtomicLong lastExecutionNanosRef = new AtomicLong(0l);

        @Override
        public void run() {
            try {
                //计算补偿时间
                long compensationTimeMs = getCompensationTimeMs();
                logger.info("Running the evict task with compensationTime {}ms", compensationTimeMs);
                //开始清除
                evict(compensationTimeMs);
            } catch (Throwable e) {
                logger.error("Could not run the evict task", e);
            }
        }

        /**
         * compute a compensation time defined as the actual time this task was executed since the prev iteration,
         * vs the configured amount of time for execution. This is useful for cases where changes in time (due to
         * clock skew or gc for example) causes the actual eviction task to execute later than the desired time
         * according to the configured cycle.
         */
        long getCompensationTimeMs() {
            //返回正在运行的 Java 虚拟机的当前值的高分辨率时间源，以纳秒为单位。
            //当获取本次清除操作开始的时间点
            long currNanos = getCurrentTimeNano();
            // 当获取上一次清除操作开始的时间点
            long lastNanos = lastExecutionNanosRef.getAndSet(currNanos);
            if (lastNanos == 0l) {
                return 0l;
            }
            //计算本次与上次删除颠作间接时间间隔
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(currNanos - lastNanos);
            // 建个市场 -  配置的 清除操作时间间隔 = 补偿时间
            long compensationTime = elapsedMs - serverConfig.getEvictionIntervalTimerInMs();
            return compensationTime <= 0l ? 0l : compensationTime;
        }

        long getCurrentTimeNano() {  // for testing
            return System.nanoTime();
        }

    }

    /* visible for testing */ static class CircularQueue<E> extends AbstractQueue<E> {

        private final ArrayBlockingQueue<E> delegate;
        private final int capacity;

        public CircularQueue(int capacity) {
            this.capacity = capacity;
            this.delegate = new ArrayBlockingQueue<>(capacity);
        }

        @Override
        public Iterator<E> iterator() {
            return delegate.iterator();
        }

        @Override
        public int size() {
            return delegate.size();
        }

        @Override
        public boolean offer(E e) {
            while (!delegate.offer(e)) {
                delegate.poll();
            }
            return true;
        }

        @Override
        public E poll() {
            return delegate.poll();
        }

        @Override
        public E peek() {
            return delegate.peek();
        }

        @Override
        public void clear() {
            delegate.clear();
        }

        @Override
        public Object[] toArray() {
            return delegate.toArray();
        }
    }

    /**
     * @return The rule that will process the instance status override.
     */
    protected abstract InstanceStatusOverrideRule getInstanceInfoOverrideRule();

    protected InstanceInfo.InstanceStatus getOverriddenInstanceStatus(InstanceInfo r,
                                                                      Lease<InstanceInfo> existingLease,
                                                                      boolean isReplication) {
        //TODO 核心代码 查看源码 ☆☆☆☆☆☆☆☆☆☆☆☆☆☆☆☆
        InstanceStatusOverrideRule rule = getInstanceInfoOverrideRule();
        logger.debug("Processing override status using rule: {}", rule);
        //TODO 核心代码 查看源码 ☆☆☆☆☆☆☆☆☆☆☆☆☆☆☆☆
        return rule.apply(r, existingLease, isReplication).status();
    }

    private TimerTask getDeltaRetentionTask() {
        return new TimerTask() {
            /*
            * recentlyChangedQueue队列是有序的，其是按照添加到队列的时间升序排列的，
            * 即最新添加的元素一定是放在队尾的（时间最大)，最老添加的元素一定是放在队首的(时间最小)
            * */
            @Override
            public void run() {
                //遍历 队列迭代器
                Iterator<RecentlyChangedItem> it = recentlyChangedQueue.iterator();
                /*
                * it.next().getLastUpdateTime()是当前元素当前client被修改的最后时间戳
                * serverConfig.getRetentionTimeInMSInDeltaQueue()recentCanceledQueue队列中的recent事件
                * */
                while (it.hasNext()) {
                    /*
                    * 将if()中的条件变化一下形式;
                    * <=> it.next( ).getLastUpdateTime( ) <System.currentTimeMillis( ) - serverConfig.getRetentionTimeInMSinDeltaQueue()
                    * <=> System.currentTimeMillis() - serverConfig.getRetentionTimeInNSInDeLtaQueue() > it.next().getLastUpdateTime()
                    * <=> System.currentTimeMillis() - it.next( ).getLastUpdateTime() > serverConfig.getRetentionTimeInMSInDeltaQueue()
                    * <=>当前时间–最后修改时间>元素可以在队列中存在的最长时间
                    * */
                    if (it.next().getLastUpdateTime() <
                            System.currentTimeMillis() - serverConfig.getRetentionTimeInMSInDeltaQueue()) {
                        it.remove();//当前元素删除
                    } else {
                        //只要当前遍历的元素不满足前面的条件，那么后面的所有元素都不会满足，不用再判断了
                        break;
                    }
                }
            }

        };
    }
}
