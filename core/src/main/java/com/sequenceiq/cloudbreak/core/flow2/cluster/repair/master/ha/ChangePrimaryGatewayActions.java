package com.sequenceiq.cloudbreak.core.flow2.cluster.repair.master.ha;

import java.util.Map;

import javax.inject.Inject;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.statemachine.action.Action;

import com.sequenceiq.cloudbreak.common.event.Selectable;
import com.sequenceiq.cloudbreak.core.flow2.cluster.AbstractClusterAction;
import com.sequenceiq.cloudbreak.core.flow2.cluster.ClusterViewContext;
import com.sequenceiq.cloudbreak.core.flow2.stack.AbstractStackFailureAction;
import com.sequenceiq.cloudbreak.core.flow2.stack.StackFailureContext;
import com.sequenceiq.cloudbreak.reactor.api.event.StackEvent;
import com.sequenceiq.cloudbreak.reactor.api.event.StackFailureEvent;
import com.sequenceiq.cloudbreak.reactor.api.event.cluster.WaitForAmbariServerSuccess;
import com.sequenceiq.cloudbreak.reactor.api.event.cluster.WaitForClusterServerRequest;
import com.sequenceiq.cloudbreak.reactor.api.event.orchestration.ChangePrimaryGatewayRequest;
import com.sequenceiq.cloudbreak.reactor.api.event.orchestration.ChangePrimaryGatewaySuccess;
import com.sequenceiq.cloudbreak.reactor.api.event.orchestration.ChangePrimaryGatewayTriggerEvent;
import com.sequenceiq.cloudbreak.service.stack.InstanceMetaDataService;
import com.sequenceiq.cloudbreak.view.InstanceMetadataView;

@Configuration
public class ChangePrimaryGatewayActions {
    @Inject
    private ChangePrimaryGatewayService changePrimaryGatewayService;

    @Inject
    private InstanceMetaDataService instanceMetaDataService;

    @Bean(name = "CHANGE_PRIMARY_GATEWAY_STATE")
    public Action<?, ?> repairGatewayAction() {
        return new AbstractClusterAction<>(ChangePrimaryGatewayTriggerEvent.class) {
            @Override
            protected void doExecute(ClusterViewContext context, ChangePrimaryGatewayTriggerEvent payload, Map<Object, Object> variables) {
                changePrimaryGatewayService.changePrimaryGatewayStarted(context.getStackId());
                Selectable request = new ChangePrimaryGatewayRequest(context.getStackId());
                sendEvent(context, request.selector(), request);
            }
        };
    }

    @Bean(name = "WAITING_FOR_AMBARI_SERVER_STATE")
    public Action<?, ?> waitingForAmbariServer() {
        return new AbstractClusterAction<>(ChangePrimaryGatewaySuccess.class) {
            @Override
            protected void doExecute(ClusterViewContext context, ChangePrimaryGatewaySuccess payload, Map<Object, Object> variables) throws Exception {
                changePrimaryGatewayService.primaryGatewayChanged(context.getStackId(), payload.getNewPrimaryGatewayFQDN());
                sendEvent(context);
            }

            @Override
            protected Selectable createRequest(ClusterViewContext context) {
                return new WaitForClusterServerRequest(context.getStackId());
            }
        };
    }

    @Bean(name = "CHANGE_PRIMARY_GATEWAY_FINISHED_STATE")
    public Action<?, ?> changeGatewayFinishedAction() {
        return new AbstractClusterAction<>(WaitForAmbariServerSuccess.class) {
            @Override
            protected void doExecute(ClusterViewContext context, WaitForAmbariServerSuccess payload, Map<Object, Object> variables) {
                InstanceMetadataView primaryGatewayInstanceMetadata = instanceMetaDataService.getPrimaryGatewayInstanceMetadataOrError(context.getStackId());
                changePrimaryGatewayService.ambariServerStarted(context.getStack(), context.getCluster(), primaryGatewayInstanceMetadata);
                sendEvent(context);
            }

            @Override
            protected Selectable createRequest(ClusterViewContext context) {
                return new StackEvent(ChangePrimaryGatewayEvent.CHANGE_PRIMARY_GATEWAY_FLOW_FINISHED.event(), context.getStackId());
            }
        };
    }

    @Bean(name = "CHANGE_PRIMARY_GATEWAY_FAILED_STATE")
    public Action<?, ?> repairGatewayFailedAction() {
        return new AbstractStackFailureAction<ChangePrimaryGatewayState, ChangePrimaryGatewayEvent>() {
            @Override
            protected void doExecute(StackFailureContext context, StackFailureEvent payload, Map<Object, Object> variables) {
                changePrimaryGatewayService.changePrimaryGatewayFailed(context.getStackId(), payload.getException());
                sendEvent(context);
            }

            @Override
            protected Selectable createRequest(StackFailureContext context) {
                return new StackEvent(ChangePrimaryGatewayEvent.CHANGE_PRIMARY_GATEWAY_FAILURE_HANDLED.event(), context.getStackId());
            }
        };
    }
}
