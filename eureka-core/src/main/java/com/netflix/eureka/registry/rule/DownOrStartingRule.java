package com.netflix.eureka.registry.rule;

import com.netflix.appinfo.InstanceInfo;
import com.netflix.eureka.lease.Lease;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This rule matches if the instance is DOWN or STARTING.
 *
 * Created by Nikos Michalakis on 7/13/16.
 */
public class DownOrStartingRule implements InstanceStatusOverrideRule {
    private static final Logger logger = LoggerFactory.getLogger(DownOrStartingRule.class);

    @Override
    public StatusOverrideResult apply(InstanceInfo instanceInfo,
                                      Lease<InstanceInfo> existingLease,
                                      boolean isReplication) {
        // ReplicationInstance is DOWN or STARTING - believe that, but when the instance says UP, question that
        // The client instance sends STARTING or DOWN (because of heartbeat failures), then we accept what
        // the client says. The same is the case with replica as well.
        // The OUT_OF_SERVICE from the client or replica needs to be confirmed as well since the service may be
        // currently in SERVICE
        /*
        * ReplicationInstance 是 DOWN 或 STARTING - 相信，但是当实例说 UP 时，质疑客户端实例发送 STARTING 或 DOWN（因为心跳失败），
        * 然后我们接受客户端所说的。 副本也是如此。来自客户端或副本的 OUT_OF_SERVICE 也需要确认，因为该服务当前可能处于 SERVICE 中
        * */
        //外来的 状态不是 up 也不是  OUT_OF_SERVICE
        if ((!InstanceInfo.InstanceStatus.UP.equals(instanceInfo.getStatus()))
                && (!InstanceInfo.InstanceStatus.OUT_OF_SERVICE.equals(instanceInfo.getStatus()))) {
            logger.debug("Trusting the instance status {} from replica or instance for instance {}",
                    instanceInfo.getStatus(), instanceInfo.getId());
            //TODO 核心代码 查看源码 ☆☆☆☆☆☆☆☆☆☆☆☆☆☆☆☆
            return StatusOverrideResult.matchingStatus(instanceInfo.getStatus());
        }
        return StatusOverrideResult.NO_MATCH;
    }

    @Override
    public String toString() {
        return DownOrStartingRule.class.getName();
    }
}
