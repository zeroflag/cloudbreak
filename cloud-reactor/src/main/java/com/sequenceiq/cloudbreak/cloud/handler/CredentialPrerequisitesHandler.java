package com.sequenceiq.cloudbreak.cloud.handler;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.sequenceiq.cloudbreak.cloud.CloudConnector;
import com.sequenceiq.cloudbreak.cloud.context.CloudContext;
import com.sequenceiq.cloudbreak.cloud.event.credential.CredentialPrerequisitesRequest;
import com.sequenceiq.cloudbreak.cloud.event.credential.CredentialPrerequisitesResult;
import com.sequenceiq.cloudbreak.cloud.init.CloudPlatformConnectors;
import com.sequenceiq.cloudbreak.cloud.response.CredentialPrerequisitesResponse;
import com.sequenceiq.cloudbreak.eventbus.Event;

@Component
public class CredentialPrerequisitesHandler implements CloudPlatformEventHandler<CredentialPrerequisitesRequest> {

    private static final Logger LOGGER = LoggerFactory.getLogger(CredentialPrerequisitesHandler.class);

    @Inject
    private CloudPlatformConnectors cloudPlatformConnectors;

    @Override
    public Class<CredentialPrerequisitesRequest> type() {
        return CredentialPrerequisitesRequest.class;
    }

    @Override
    public void accept(Event<CredentialPrerequisitesRequest> credentialPrerequisitesRequestEvent) {
        LOGGER.debug("Received event: {}", credentialPrerequisitesRequestEvent);
        CredentialPrerequisitesRequest request = credentialPrerequisitesRequestEvent.getData();
        CloudContext cloudContext = request.getCloudContext();
        try {
            LOGGER.info("Gathering credential prerequisites for platform: '{}'", cloudContext.getPlatform());
            CloudConnector connector = cloudPlatformConnectors.getDefault(cloudContext.getPlatform());
            CredentialPrerequisitesResponse result = connector.credentials()
                    .getPrerequisites(cloudContext, request.getExternalId(), request.getAuditExternalId(), request.getDeploymentAddress(), request.getType());
            CredentialPrerequisitesResult credentialPrerequisitesResult = new CredentialPrerequisitesResult(request.getResourceId(), result);
            request.getResult().onNext(credentialPrerequisitesResult);
            LOGGER.debug("Credential prerequisites have been collected successfully for platform: '{}'!", cloudContext.getPlatform().value());
        } catch (RuntimeException e) {
            request.getResult().onNext(new CredentialPrerequisitesResult(e.getMessage(), e, request.getResourceId()));
        }
    }
}
