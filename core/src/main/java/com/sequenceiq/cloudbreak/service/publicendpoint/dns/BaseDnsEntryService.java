package com.sequenceiq.cloudbreak.service.publicendpoint.dns;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.CollectionUtils;

import com.sequenceiq.cloudbreak.auth.ThreadBasedUserCrnProvider;
import com.sequenceiq.cloudbreak.dto.StackDtoDelegate;
import com.sequenceiq.cloudbreak.service.environment.EnvironmentClientService;
import com.sequenceiq.cloudbreak.service.publicendpoint.BasePublicEndpointManagementService;
import com.sequenceiq.cloudbreak.view.InstanceMetadataView;
import com.sequenceiq.environment.api.v1.environment.model.response.DetailedEnvironmentResponse;

public abstract class BaseDnsEntryService extends BasePublicEndpointManagementService {

    private static final Logger LOGGER = LoggerFactory.getLogger(BaseDnsEntryService.class);

    @Inject
    private EnvironmentClientService environmentClientService;

    protected abstract Map<String, List<String>> getComponentLocation(StackDtoDelegate stack);

    protected abstract String logName();

    public Map<String, String> createOrUpdate(StackDtoDelegate stack) {
        return doActionOnStack(stack, null, this::createOrUpdateDnsEntries);
    }

    public Map<String, String> deregister(StackDtoDelegate stack) {
        return doActionOnStack(stack, null, this::doDeregister);
    }

    public Map<String, String> createOrUpdateCandidates(StackDtoDelegate stack, Map<String, String> candidateAddressesByFqdn) {
        return doActionOnStack(stack, candidateAddressesByFqdn, this::createOrUpdateDnsEntries);
    }

    public Map<String, String> deregister(StackDtoDelegate stack, Map<String, String> candidateAddressesByFqdn) {
        return doActionOnStack(stack, candidateAddressesByFqdn, this::doDeregister);
    }

    private Map<String, String> doActionOnStack(
            StackDtoDelegate stack,
            Map<String, String> candidateAddressesByFqdn,
            BiFunction<Map<String, String>, String, Map<String, String>> action) {

        Map<String, String> result = new HashMap<>();
        if (stack.getCluster() != null && manageCertificateAndDnsInPem()) {
            LOGGER.info("Modifying DNS entries for {} on stack '{}'", logName(), stack.getName());
            Map<String, String> ipsByFqdn = getCandidateIpsByFqdn(stack);
            if (!CollectionUtils.isEmpty(candidateAddressesByFqdn)) {
                LOGGER.info("Modifying DNS entries for {} on stack '{}', whitelist of candidates for the update: '{}'", logName(), stack.getName(),
                        String.join(",", candidateAddressesByFqdn.keySet()));
                ipsByFqdn = ipsByFqdn.entrySet().stream()
                        .filter(entry -> candidateAddressesByFqdn.containsKey(entry.getKey()))
                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
            }
            result.putAll(action.apply(ipsByFqdn, stack.getEnvironmentCrn()));
        }
        return result;
    }

    private Map<String, String> getCandidateIpsByFqdn(StackDtoDelegate stack) {
        Map<String, List<String>> componentLocation = getComponentLocation(stack);
        final List<? extends InstanceMetadataView> runningInstanceMetaData = stack.getAllAvailableInstances();
        final InstanceMetadataView primaryGatewayInstanceMetadata = stack.getPrimaryGatewayInstance();
        return componentLocation
                .values()
                .stream()
                .flatMap(fqdns -> runningInstanceMetaData.stream().filter(im -> fqdns.contains(im.getDiscoveryFQDN())))
                .filter(im -> primaryGatewayInstanceMetadata != null && !primaryGatewayInstanceMetadata.getPrivateId().equals(im.getPrivateId()))
                .collect(Collectors.toMap(InstanceMetadataView::getDiscoveryFQDN, InstanceMetadataView::getPublicIpWrapper));
    }

    private Map<String, String> createOrUpdateDnsEntries(Map<String, String> ipsByFqdn, String environmentCrn) {
        LOGGER.info("Register DNS entries for {} for FQDNs: '{}'", logName(), String.join(",", ipsByFqdn.keySet()));
        String accountId = ThreadBasedUserCrnProvider.getAccountId();
        DetailedEnvironmentResponse environment = environmentClientService.getByCrn(environmentCrn);

        return ipsByFqdn
                .entrySet()
                .stream()
                .filter(entry -> getDnsManagementService().createOrUpdateDnsEntryWithIp(accountId,
                        getShortHostname(entry.getKey()), environment.getName(), false, List.of(entry.getValue())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private Map<String, String> doDeregister(Map<String, String> ipsByFqdn, String environmentCrn) {
        LOGGER.info("Deregister DNS entries for {} for FQDNs: '{}'", logName(), String.join(",", ipsByFqdn.keySet()));
        String accountId = ThreadBasedUserCrnProvider.getAccountId();
        DetailedEnvironmentResponse environment = environmentClientService.getByCrn(environmentCrn);

        return ipsByFqdn
                .entrySet()
                .stream()
                .filter(entry -> getDnsManagementService().deleteDnsEntryWithIp(accountId,
                        getShortHostname(entry.getKey()), environment.getName(), false, List.of(entry.getValue())))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private String getShortHostname(String fqdn) {
        if (StringUtils.isEmpty(fqdn)) {
            return null;
        }
        return fqdn.split("\\.")[0];
    }

    protected void setCertGenerationEnabled(boolean enabled) {
        super.setCertGenerationEnabled(enabled);
    }
}
