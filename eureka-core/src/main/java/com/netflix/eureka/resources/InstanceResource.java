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

package com.netflix.eureka.resources;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.eureka.EurekaServerConfig;
import com.netflix.eureka.cluster.PeerEurekaNode;
import com.netflix.eureka.registry.PeerAwareInstanceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import javax.ws.rs.core.UriInfo;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A <em>jersey</em> resource that handles operations for a particular instance.
 *
 * @author Karthik Ranganathan, Greg Kim
 *
 */
@Produces({"application/xml", "application/json"})
public class InstanceResource {
    private static final Logger logger = LoggerFactory
            .getLogger(InstanceResource.class);

    private final PeerAwareInstanceRegistry registry;
    private final EurekaServerConfig serverConfig;
    private final String id;
    private final ApplicationResource app;


    InstanceResource(ApplicationResource app, String id, EurekaServerConfig serverConfig, PeerAwareInstanceRegistry registry) {
        this.app = app;
        this.id = id;
        this.serverConfig = serverConfig;
        this.registry = registry;
    }

    /**
     * Get requests returns the information about the instance's
     * {@link InstanceInfo}.
     *
     * @return response containing information about the the instance's
     *         {@link InstanceInfo}.
     */
    @GET
    public Response getInstanceInfo() {
        InstanceInfo appInfo = registry
                .getInstanceByAppAndId(app.getName(), id);
        if (appInfo != null) {
            logger.debug("Found: {} - {}", app.getName(), id);
            return Response.ok(appInfo).build();
        } else {
            logger.debug("Not Found: {} - {}", app.getName(), id);
            return Response.status(Status.NOT_FOUND).build();
        }
    }

    /**
     * A put request for renewing lease from a client instance.
     *
     * @param isReplication
     *            a header parameter containing information whether this is
     *            replicated from other nodes.
     * @param overriddenStatus
     *            overridden status if any.
     * @param status
     *            the {@link InstanceStatus} of the instance.
     * @param lastDirtyTimestamp
     *            last timestamp when this instance information was updated.
     * @return response indicating whether the operation was a success or
     *         failure.
     *
     * TODO 处理客户端续约请求
     *  一般情况下 client先续约 server返回404后 再注册
     */
    @PUT
    public Response renewLease(
            //TODO 表示是否是server之间的复制同步
            @HeaderParam(PeerEurekaNode.HEADER_REPLICATION) String isReplication,
            @QueryParam("overriddenstatus") String overriddenStatus,//TODO 从client/另一个server传入的
            @QueryParam("status") String status,
            //TODO 另一个server或client传入属于他的lastDirtyTimestamp  用来和当前执行该方法的server作比较
            @QueryParam("lastDirtyTimestamp") String lastDirtyTimestamp) {
        boolean isFromReplicaNode = "true".equals(isReplication);
        //TODO 一开始 client先续约 server返回404后  也就是isSuccess=false
        //TODO 查看源码 com.netflix.eureka.registry.PeerAwareInstanceRegistryImpl.renew
        boolean isSuccess = registry.renew(app.getName(), id, isFromReplicaNode);

        // Not found in the registry, immediately ask for a register
        if (!isSuccess) {
            logger.warn("Not Found (Renew): {} - {}", app.getName(), id);
            //TODO client先续约 server返回404
            return Response.status(Status.NOT_FOUND).build();
        }
        // Check if we need to sync based on dirty time stamp, the client
        // instance might have changed some value
        /*
        * TODO 时间戳不同 就做同步 ，
        *  server与server之间做同步，说明server之间的值是不同的
        *  也可能是 client与server之间做同步
        * */
        Response response;
        //TODO 传过来的lastDirtyTimestamp不为空  shouldSyncWhenTimestampDiffers默认为true
        //shouldSyncWhenTimestampDiffers 表示是否需要做同步
        if (lastDirtyTimestamp != null && serverConfig.shouldSyncWhenTimestampDiffers()) {
            //TODO validateDirtyTimestamp（）方法验证 server之间的lastDirtyTimestamp是否相同  isFromReplicaNode=true
            //TODO 查看源码
            response = this.validateDirtyTimestamp(Long.valueOf(lastDirtyTimestamp), isFromReplicaNode);
            // Store the overridden status since the validation found out the node that replicates wins
            if (response.getStatus() == Response.Status.NOT_FOUND.getStatusCode()
                    && (overriddenStatus != null)
                    //TODO overriddenStatus不能是InstanceStatus.UNKNOWN
                    && !(InstanceStatus.UNKNOWN.name().equals(overriddenStatus))
                    //TODO 并且是server之间的同步
                    && isFromReplicaNode) {
                //TODO 真正执行同步
                //TODO 查看源码
                registry.storeOverriddenStatusIfRequired(app.getAppName(), id, InstanceStatus.valueOf(overriddenStatus));
            }
        } else {
            response = Response.ok().build();
        }
        logger.debug("Found (Renew): {} - {}; reply status={}", app.getName(), id, response.getStatus());
        return response;
    }

    /**
     * Handles {@link InstanceStatus} updates.
     *
     * <p>
     * The status updates are normally done for administrative purposes to
     * change the instance status between {@link InstanceStatus#UP} and
     * {@link InstanceStatus#OUT_OF_SERVICE} to select or remove instances for
     * receiving traffic.
     * </p>
     *
     * @param newStatus
     *            the new status of the instance.
     * @param isReplication
     *            a header parameter containing information whether this is
     *            replicated from other nodes.
     * @param lastDirtyTimestamp
     *            last timestamp when this instance information was updated.
     * @return response indicating whether the operation was a success or
     *         failure.
     *TODO
     * 状态更新通常用于管理目的，
     * 以更改 {@link InstanceStatus#UP} 和 {@link InstanceStatus#OUT_OF_SERVICE} 之间的实例状态，
     * 以选择或删除实例以接收流量。
     */
    @PUT
    @Path("status")
    public Response statusUpdate(
            //TODO client发来的请求 有参数value和 lastDirtyTimestamp 请求头中有PeerEurekaNode.HEADER_REPLICATION
            //TODO isReplication的作用： 因为server 除了接受client请求 也要接受其他server的请求 因为server之间要replication
            @QueryParam("value") String newStatus,
            @HeaderParam(PeerEurekaNode.HEADER_REPLICATION) String isReplication,
            @QueryParam("lastDirtyTimestamp") String lastDirtyTimestamp) {
        try {
            //TODO 从注册表中找对饮的个微服务
            if (registry.getInstanceByAppAndId(app.getName(), id) == null) {
                logger.warn("Instance not found: {}/{}", app.getName(), id);
                //TODO Status.NOT_FOUND 说明没有找到
                return Response.status(Status.NOT_FOUND).build();
            }
            //TODO 状态修改
            boolean isSuccess = registry.statusUpdate(app.getName(), id,
                    InstanceStatus.valueOf(newStatus), lastDirtyTimestamp,
                    "true".equals(isReplication));

            if (isSuccess) {
                logger.info("Status updated: {} - {} - {}", app.getName(), id, newStatus);
                //TODO 状态修改成功 返回ok
                return Response.ok().build();
            } else {
                logger.warn("Unable to update status: {} - {} - {}", app.getName(), id, newStatus);
                //TODO 状态修改失败 返回500
                return Response.serverError().build();
            }
        } catch (Throwable e) {
            logger.error("Error updating instance {} for status {}", id,
                    newStatus);
            //TODO 状态修改失败 返回500
            return Response.serverError().build();
        }
    }

    /**
     * Removes status override for an instance, set with
     * {@link #statusUpdate(String, String, String)}.
     *
     * @param isReplication
     *            a header parameter containing information whether this is
     *            replicated from other nodes.
     * @param lastDirtyTimestamp
     *            last timestamp when this instance information was updated.
     * @return response indicating whether the operation was a success or
     *         failure.
     *TODO client端发送 特殊状态CANCEL_OVERRIDE 会触发该方法
     * 处理客户端删除overridden状态请求
     */
    @DELETE
    @Path("status")
    public Response deleteStatusUpdate(
            @HeaderParam(PeerEurekaNode.HEADER_REPLICATION) String isReplication,
            @QueryParam("value") String newStatusValue,//TODO 请求参数包含 新的状态
            @QueryParam("lastDirtyTimestamp") String lastDirtyTimestamp) {
        try {
            //TODO 根据微服务名字和实例id找到实例
            if (registry.getInstanceByAppAndId(app.getName(), id) == null) {
                logger.warn("Instance not found: {}/{}", app.getName(), id);
                return Response.status(Status.NOT_FOUND).build();//TODO 找不到 的
            }
            //TODO 找到了 就查看请求参数包含 新的状态是不是空 是的话 InstanceStatus.UNKNOWN  不是的话 就是请求参数指定的状态
            //TODO 查看客户端代码  CANCEL_OVERRIDE的时候  newStatusValue == null 所以这里 为true
            InstanceStatus newStatus = newStatusValue == null ? InstanceStatus.UNKNOWN : InstanceStatus.valueOf(newStatusValue);
            //TODO 传入 微服务名字 实例id InstanceStatus.UNKNOWN
            //TODO 查看源码
            boolean isSuccess = registry.deleteStatusOverride(app.getName(), id,
                    newStatus, lastDirtyTimestamp, "true".equals(isReplication));

            if (isSuccess) {
                logger.info("Status override removed: {} - {}", app.getName(), id);
                return Response.ok().build();
            } else {
                logger.warn("Unable to remove status override: {} - {}", app.getName(), id);
                return Response.serverError().build();
            }
        } catch (Throwable e) {
            logger.error("Error removing instance's {} status override", id);
            return Response.serverError().build();
        }
    }

    /**
     * Updates user-specific metadata information. If the key is already available, its value will be overwritten.
     * If not, it will be added.
     * @param uriInfo - URI information generated by jersey.
     * @return response indicating whether the operation was a success or
     *         failure.
     */
    @PUT
    @Path("metadata")
    public Response updateMetadata(@Context UriInfo uriInfo) {
        try {
            InstanceInfo instanceInfo = registry.getInstanceByAppAndId(app.getName(), id);
            // ReplicationInstance information is not found, generate an error
            if (instanceInfo == null) {
                logger.warn("Cannot find instance while updating metadata for instance {}/{}", app.getName(), id);
                return Response.status(Status.NOT_FOUND).build();
            }
            MultivaluedMap<String, String> queryParams = uriInfo.getQueryParameters();
            Set<Entry<String, List<String>>> entrySet = queryParams.entrySet();
            Map<String, String> metadataMap = instanceInfo.getMetadata();
            // Metadata map is empty - create a new map
            if (Collections.emptyMap().getClass().equals(metadataMap.getClass())) {
                metadataMap = new ConcurrentHashMap<>();
                InstanceInfo.Builder builder = new InstanceInfo.Builder(instanceInfo);
                builder.setMetadata(metadataMap);
                instanceInfo = builder.build();
            }
            // Add all the user supplied entries to the map
            for (Entry<String, List<String>> entry : entrySet) {
                metadataMap.put(entry.getKey(), entry.getValue().get(0));
            }
            registry.register(instanceInfo, false);
            return Response.ok().build();
        } catch (Throwable e) {
            logger.error("Error updating metadata for instance {}", id, e);
            return Response.serverError().build();
        }

    }

    /**
     * Handles cancellation of leases for this particular instance.
     *
     * @param isReplication
     *            a header parameter containing information whether this is
     *            replicated from other nodes.
     * @return response indicating whether the operation was a success or
     *         failure.
     */
    //TODO 处理 服务下架请求
    @DELETE
    public Response cancelLease(
            @HeaderParam(PeerEurekaNode.HEADER_REPLICATION) String isReplication) {
        try {
            // 返回是否成功
            boolean isSuccess = registry.cancel(app.getName(), id,
                "true".equals(isReplication));

            if (isSuccess) {
                logger.debug("Found (Cancel): {} - {}", app.getName(), id);
                return Response.ok().build();
            } else {
                logger.info("Not Found (Cancel): {} - {}", app.getName(), id);
                return Response.status(Status.NOT_FOUND).build();
            }
        } catch (Throwable e) {
            logger.error("Error (cancel): {} - {}", app.getName(), id, e);
            return Response.serverError().build();
        }

    }

    private Response validateDirtyTimestamp(Long lastDirtyTimestamp,
                                            boolean isReplication) {
        //TODO 找到微服务实例
        InstanceInfo appInfo = registry.getInstanceByAppAndId(app.getName(), id, false);
        if (appInfo != null) {
            //TODO 另一个server传过来的的lastDirtyTimestamp（可能是client可能是server） 和自己的lastDirtyTimestamp 作比较
            if ((lastDirtyTimestamp != null) && (!lastDirtyTimestamp.equals(appInfo.getLastDirtyTimestamp()))) {
                Object[] args = {id, appInfo.getLastDirtyTimestamp(), lastDirtyTimestamp, isReplication};
                //TODO 外来的lastDirtyTimestamp（可能是client可能是server） 比当前的server的大  说明外来的新
                if (lastDirtyTimestamp > appInfo.getLastDirtyTimestamp()) {
                    logger.debug(
                            "Time to sync, since the last dirty timestamp differs -"
                                    + " ReplicationInstance id : {},Registry : {} Incoming: {} Replication: {}",
                            args);
                    //TODO 返回NOT_FOUND响应 表示待会要同步
                    // 因为你的比我的新，那么 你的数据在我这没有找到 所以NOT_FOUND
                    return Response.status(Status.NOT_FOUND).build();
                    //TODO 外来的lastDirtyTimestamp（可能是client可能是server） 比当前的server的小  说明外来的数据旧
                } else if (appInfo.getLastDirtyTimestamp() > lastDirtyTimestamp) {
                    // In the case of replication, send the current instance info in the registry for the
                    // replicating node to sync itself with this one.
                    //TODO 表示另一个server要与当前server做同步
                    if (isReplication) {
                        logger.debug(
                                "Time to sync, since the last dirty timestamp differs -"
                                        + " ReplicationInstance id : {},Registry : {} Incoming: {} Replication: {}",
                                args);
                        //TODO 返回Status.CONFLICT 表示冲突
                        return Response.status(Status.CONFLICT).entity(appInfo).build();
                    } else {
                        /*
                        *TODO
                        * 这个情况表示 当前是client与server做同步 并且server的lastDirtyTimestamp比client大
                        * 这个情况可能是client的配置文件发生变更  变更前发了lastDirtyTimestamp 变更后发了lastDirtyTimestamp
                        * 前者因为网络延时 比后者晚到server
                        * */
                        return Response.ok().build();
                    }
                }
            }

        }
        return Response.ok().build();
    }
}
