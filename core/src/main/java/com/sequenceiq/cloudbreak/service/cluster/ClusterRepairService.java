package com.sequenceiq.cloudbreak.service.cluster;

import static com.sequenceiq.cloudbreak.event.ResourceEvent.CLUSTER_MANUALRECOVERY_COULD_NOT_START;
import static com.sequenceiq.cloudbreak.event.ResourceEvent.CLUSTER_MANUALRECOVERY_NO_NODES_TO_RECOVER;
import static com.sequenceiq.cloudbreak.event.ResourceEvent.CLUSTER_MANUALRECOVERY_REQUESTED;
import static com.sequenceiq.cloudbreak.service.cluster.model.HostGroupName.hostGroupName;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import javax.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.sequenceiq.cloudbreak.api.endpoint.v4.common.DetailedStackStatus;
import com.sequenceiq.cloudbreak.api.endpoint.v4.stacks.base.InstanceStatus;
import com.sequenceiq.cloudbreak.api.endpoint.v4.stacks.base.RecoveryMode;
import com.sequenceiq.cloudbreak.auth.ThreadBasedUserCrnProvider;
import com.sequenceiq.cloudbreak.auth.altus.EntitlementService;
import com.sequenceiq.cloudbreak.cloud.model.Image;
import com.sequenceiq.cloudbreak.cloud.model.VolumeSetAttributes;
import com.sequenceiq.cloudbreak.cluster.util.ResourceAttributeUtil;
import com.sequenceiq.cloudbreak.common.exception.BadRequestException;
import com.sequenceiq.cloudbreak.common.exception.CloudbreakServiceException;
import com.sequenceiq.cloudbreak.core.CloudbreakImageCatalogException;
import com.sequenceiq.cloudbreak.core.CloudbreakImageNotFoundException;
import com.sequenceiq.cloudbreak.core.flow2.service.ReactorFlowManager;
import com.sequenceiq.cloudbreak.domain.Resource;
import com.sequenceiq.cloudbreak.domain.StopRestrictionReason;
import com.sequenceiq.cloudbreak.domain.stack.ManualClusterRepairMode;
import com.sequenceiq.cloudbreak.domain.stack.cluster.host.HostGroup;
import com.sequenceiq.cloudbreak.domain.stack.instance.InstanceMetaData;
import com.sequenceiq.cloudbreak.dto.InstanceGroupDto;
import com.sequenceiq.cloudbreak.dto.StackDto;
import com.sequenceiq.cloudbreak.service.ComponentConfigProviderService;
import com.sequenceiq.cloudbreak.service.StackUpdater;
import com.sequenceiq.cloudbreak.service.cluster.model.HostGroupName;
import com.sequenceiq.cloudbreak.service.cluster.model.RepairValidation;
import com.sequenceiq.cloudbreak.service.cluster.model.Result;
import com.sequenceiq.cloudbreak.service.environment.EnvironmentService;
import com.sequenceiq.cloudbreak.service.freeipa.FreeipaService;
import com.sequenceiq.cloudbreak.service.hostgroup.HostGroupService;
import com.sequenceiq.cloudbreak.service.image.ImageCatalogService;
import com.sequenceiq.cloudbreak.service.rdsconfig.RedbeamsClientService;
import com.sequenceiq.cloudbreak.service.resource.ResourceService;
import com.sequenceiq.cloudbreak.service.stack.StackDtoService;
import com.sequenceiq.cloudbreak.service.stack.StackService;
import com.sequenceiq.cloudbreak.service.stack.StackStopRestrictionService;
import com.sequenceiq.cloudbreak.service.stack.StackUpgradeService;
import com.sequenceiq.cloudbreak.structuredevent.CloudbreakRestRequestThreadLocalService;
import com.sequenceiq.cloudbreak.structuredevent.event.CloudbreakEventService;
import com.sequenceiq.cloudbreak.view.InstanceMetadataView;
import com.sequenceiq.cloudbreak.view.StackView;
import com.sequenceiq.common.api.type.InstanceGroupType;
import com.sequenceiq.common.model.AwsDiskType;
import com.sequenceiq.environment.api.v1.environment.model.response.EnvironmentStatus;
import com.sequenceiq.flow.api.model.FlowIdentifier;
import com.sequenceiq.redbeams.api.endpoint.v4.databaseserver.responses.DatabaseServerV4Response;

@Service
public class ClusterRepairService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClusterRepairService.class);

    private static final List<String> REATTACH_NOT_SUPPORTED_VOLUME_TYPES = List.of(AwsDiskType.Ephemeral.value());

    private static final String RECOVERY = "RECOVERY";

    private static final String RECOVERY_FAILED = "RECOVERY_FAILED";

    @Inject
    private StackDtoService stackDtoService;

    @Inject
    private StackUpdater stackUpdater;

    @Inject
    private ComponentConfigProviderService componentConfigProviderService;

    @Inject
    private ImageCatalogService imageCatalogService;

    @Inject
    private HostGroupService hostGroupService;

    @Inject
    private ResourceService resourceService;

    @Inject
    private ResourceAttributeUtil resourceAttributeUtil;

    @Inject
    private ReactorFlowManager flowManager;

    @Inject
    private CloudbreakEventService eventService;

    @Inject
    private ClusterDBValidationService clusterDBValidationService;

    @Inject
    private RedbeamsClientService redbeamsClientService;

    @Inject
    private EnvironmentService environmentService;

    @Inject
    private FreeipaService freeipaService;

    @Inject
    private EntitlementService entitlementService;

    @Inject
    private StackStopRestrictionService stackStopRestrictionService;

    @Inject
    private StackUpgradeService stackUpgradeService;

    @Inject
    private CloudbreakRestRequestThreadLocalService restRequestThreadLocalService;

    public FlowIdentifier repairAll(StackView stackView, boolean upgrade, boolean keepVariant) {
        Result<Map<HostGroupName, Set<InstanceMetaData>>, RepairValidation> repairStart =
                validateRepair(ManualClusterRepairMode.ALL, stackView.getId(), Set.of(), false);
        Set<String> repairableHostGroups;
        if (repairStart.isSuccess()) {
            repairableHostGroups = repairStart.getSuccess()
                    .keySet()
                    .stream()
                    .map(HostGroupName::value)
                    .collect(toSet());
        } else {
            repairableHostGroups = Set.of();
        }
        String userCrn = restRequestThreadLocalService.getUserCrn();
        String upgradeVariant = stackUpgradeService.calculateUpgradeVariant(stackView, userCrn, keepVariant);
        return triggerRepairOrThrowBadRequest(stackView.getId(), repairStart, true, false, repairableHostGroups, upgradeVariant, upgrade);
    }

    public FlowIdentifier repairHostGroups(Long stackId, Set<String> hostGroups, boolean restartServices) {
        Result<Map<HostGroupName, Set<InstanceMetaData>>, RepairValidation> repairStart =
                validateRepair(ManualClusterRepairMode.HOST_GROUP, stackId, hostGroups, false);
        return triggerRepairOrThrowBadRequest(stackId, repairStart, false, restartServices, hostGroups, null, false);
    }

    public FlowIdentifier repairNodes(Long stackId, Set<String> nodeIds, boolean deleteVolumes, boolean restartServices) {
        Result<Map<HostGroupName, Set<InstanceMetaData>>, RepairValidation> repairStart =
                validateRepair(ManualClusterRepairMode.NODE_ID, stackId, nodeIds, deleteVolumes);
        return triggerRepairOrThrowBadRequest(stackId, repairStart, false, restartServices, nodeIds, null, false);
    }

    public Result<Map<HostGroupName, Set<InstanceMetaData>>, RepairValidation> repairWithDryRun(Long stackId) {
        Result<Map<HostGroupName, Set<InstanceMetaData>>, RepairValidation> repairStart =
                validateRepair(ManualClusterRepairMode.DRY_RUN, stackId, Set.of(), false);
        if (!repairStart.isSuccess()) {
            LOGGER.info("Stack {} is not repairable. {}", stackId, repairStart.getError().getValidationErrors());
        }
        return repairStart;
    }

    public Result<Map<HostGroupName, Set<InstanceMetaData>>, RepairValidation> validateRepair(ManualClusterRepairMode repairMode, Long stackId,
            Set<String> selectedParts, boolean deleteVolumes) {
        StackDto stack = stackDtoService.getById(stackId);
        return validateRepair(repairMode, stack, selectedParts, deleteVolumes);
    }

    public Result<Map<HostGroupName, Set<InstanceMetaData>>, RepairValidation> validateRepair(ManualClusterRepairMode repairMode, StackDto stack,
            Set<String> selectedParts, boolean deleteVolumes) {
        boolean reattach = !deleteVolumes;
        Result<Map<HostGroupName, Set<InstanceMetaData>>, RepairValidation> repairStartResult;
        Optional<RepairValidation> repairValidationError = validateRepairConditions(repairMode, stack, selectedParts);
        if (repairValidationError.isPresent()) {
            repairStartResult = Result.error(repairValidationError.get());
        } else if (!isReattachSupportedOnProvider(stack.getStack(), reattach)) {
            repairStartResult = Result.error(RepairValidation
                    .of(String.format("Volume reattach currently not supported on %s platform!", stack.getPlatformVariant())));
        } else {
            Pair<Predicate<HostGroup>, Predicate<InstanceMetaData>> instanceSelectors = getInstanceSelectors(repairMode, selectedParts);
            Map<HostGroupName, Set<InstanceMetaData>> repairableNodes = selectRepairableNodes(instanceSelectors, stack.getStack());
            if (repairableNodes.isEmpty()) {
                repairStartResult = Result.error(RepairValidation.of("Repairable node list is empty. Please check node statuses and try again."));
            } else {
                RepairValidation validationBySelectedNodes = validateSelectedNodes(stack, repairableNodes, reattach);
                if (!validationBySelectedNodes.getValidationErrors().isEmpty()) {
                    repairStartResult = Result.error(validationBySelectedNodes);
                } else {
                    // TODO: it should not be here... we should move it to the repair flow.
                    setStackStatusAndMarkDeletableVolumes(repairMode, deleteVolumes, stack.getStack(), repairableNodes.values().stream()
                            .flatMap(Collection::stream).map(instanceMetaData -> (InstanceMetadataView) instanceMetaData).collect(Collectors.toList()));
                    repairStartResult = Result.success(repairableNodes);
                }
            }
        }
        return repairStartResult;
    }

    public Optional<RepairValidation> validateRepairConditions(ManualClusterRepairMode repairMode, StackDto stack, Set<String> selectedParts) {
        List<String> stoppedInstanceIds = getStoppedNotSelectedInstanceIds(stack, repairMode, selectedParts);
        if (!freeipaService.checkFreeipaRunning(stack.getEnvironmentCrn())) {
            return Optional.of(RepairValidation.of("Action cannot be performed because the FreeIPA isn't available. Please check the FreeIPA state."));
        } else if (!environmentService.environmentStatusInDesiredState(stack.getStack(), Set.of(EnvironmentStatus.AVAILABLE))) {
            return Optional.of(RepairValidation.of("Action cannot be performed because the Environment isn't available. Please check the Environment state."));
        } else if (!stoppedInstanceIds.isEmpty()) {
            return Optional.of(RepairValidation.of("Action cannot be performed because there are stopped nodes in the cluster. " +
                            "Stopped nodes: [" + String.join(", ", stoppedInstanceIds) + "]. " +
                            "Please select them for node replacement or start the stopped nodes."));
        } else if (hasNotAvailableDatabase(stack)) {
            return Optional.of(RepairValidation.of(String.format("Database %s is not in AVAILABLE status, could not start node replacement.",
                    stack.getCluster().getDatabaseServerCrn())));
        } else if (isHAClusterAndRepairNotAllowed(stack)) {
            return Optional.of(RepairValidation.of("Repair is not supported when the cluster uses cluster proxy and has multiple gateway nodes. " +
                    "This will be fixed in future releases."));
        } else if (isAnyGWUnhealthyAndItIsNotSelected(repairMode, selectedParts, stack)) {
            return Optional.of(RepairValidation.of("Gateway node is unhealthy, it must be repaired first."));
        } else {
            return Optional.empty();
        }
    }

    private boolean isAnyGWUnhealthyAndItIsNotSelected(ManualClusterRepairMode repairMode, Set<String> selectedParts, StackDto stack) {
        List<InstanceMetadataView> gatewayInstances = stack.getNotTerminatedGatewayInstanceMetadata();
        if (gatewayInstances.size() < 1) {
            LOGGER.info("Stack has no GW");
            return false;
        }
        List<InstanceMetadataView> unhealthyGWs = gatewayInstances.stream().filter(gatewayInstance -> !gatewayInstance.isHealthy()).collect(toList());
        if (ManualClusterRepairMode.HOST_GROUP.equals(repairMode)) {
            LOGGER.info("Host group based repair mode, so GW hostgroup should be selected if any GW is not healthy. Unhealthy GWs: {}. Selected instances: {}",
                    unhealthyGWs, selectedParts);
            return unhealthyGWs.stream().anyMatch(unhealthyGW -> !selectedParts.contains(unhealthyGW.getInstanceGroupName()));
        } else if (ManualClusterRepairMode.NODE_ID.equals(repairMode)) {
            LOGGER.info("Node id based repair mode, so GW instance should be selected if it is not healthy. Unhealthy GWs: {}. Selected hostgroups: {}",
                    unhealthyGWs, selectedParts);
            return unhealthyGWs.stream().anyMatch(unhealthyGW -> !selectedParts.contains(unhealthyGW.getInstanceId()));
        } else {
            LOGGER.info("Repair mode is not host group or node id based: {}", repairMode);
            return false;
        }
    }

    private boolean isHAClusterAndRepairNotAllowed(StackDto stack) {
        String accountId = ThreadBasedUserCrnProvider.getAccountId();
        return !entitlementService.haRepairEnabled(accountId)
                && !entitlementService.haUpgradeEnabled(accountId)
                && stack.getTunnel().useClusterProxy()
                && hasMultipleGatewayInstances(stack);
    }

    private boolean hasMultipleGatewayInstances(StackDto stack) {
        int gatewayInstanceCount = 0;
        for (InstanceGroupDto instanceGroup : stack.getInstanceGroupDtos()) {
            if (InstanceGroupType.isGateway(instanceGroup.getInstanceGroup().getInstanceGroupType())) {
                gatewayInstanceCount += instanceGroup.getNodeCount();
            }
        }
        return gatewayInstanceCount > 1;
    }

    private boolean hasNotAvailableDatabase(StackDto stack) {
        String databaseServerCrn = stack.getCluster().getDatabaseServerCrn();
        if (StringUtils.isNotBlank(databaseServerCrn)) {
            DatabaseServerV4Response databaseServerResponse = redbeamsClientService.getByCrn(databaseServerCrn);
            if (!databaseServerResponse.getStatus().isAvailable()) {
                return true;
            }
        }
        return false;
    }

    private List<String> getStoppedNotSelectedInstanceIds(StackDto stack, ManualClusterRepairMode repairMode, Set<String> selectedParts) {
        if (ManualClusterRepairMode.HOST_GROUP.equals(repairMode)) {
            return stack.getNotTerminatedInstanceMetaData()
                    .stream()
                    .filter(im -> !selectedParts.contains(im.getInstanceGroupName()) &&
                            InstanceStatus.STOPPED.equals(im.getInstanceStatus()))
                    .map(InstanceMetadataView::getInstanceId)
                    .collect(Collectors.toList());
        } else if (ManualClusterRepairMode.NODE_ID.equals(repairMode)) {
            return stack.getNotTerminatedInstanceMetaData()
                    .stream()
                    .filter(im -> !selectedParts.contains(im.getInstanceId()) &&
                            InstanceStatus.STOPPED.equals(im.getInstanceStatus()))
                    .map(InstanceMetadataView::getInstanceId)
                    .collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    private boolean isReattachSupportedOnProvider(StackView stack, boolean repairWithReattach) {
        return !repairWithReattach || StackService.REATTACH_COMPATIBLE_PLATFORMS.contains(stack.getPlatformVariant());
    }

    private Pair<Predicate<HostGroup>, Predicate<InstanceMetaData>> getInstanceSelectors(ManualClusterRepairMode repairMode, Set<String> selectedParts) {
        switch (repairMode) {
            case DRY_RUN:
            case ALL:
                return Pair.of(allHostGroup(), allInstances());
            case HOST_GROUP:
                return Pair.of(selectHostGroups(selectedParts), this::isUnhealthyInstance);
            case NODE_ID:
                return Pair.of(allHostGroup(), selectInstances(selectedParts));
            default:
                throw new IllegalArgumentException("Unknown maunal repair mode " + repairMode);
        }
    }

    private Predicate<HostGroup> allHostGroup() {
        return hostGroup -> true;
    }

    private Predicate<InstanceMetaData> allInstances() {
        return instanceMetaData -> true;
    }

    private Predicate<HostGroup> selectHostGroups(Set<String> hostGroups) {
        return hostGroup -> hostGroups.contains(hostGroup.getName());
    }

    private Predicate<InstanceMetaData> selectInstances(Set<String> nodeIds) {
        return instanceMetaData -> nodeIds.contains(instanceMetaData.getInstanceId());
    }

    private boolean isUnhealthyInstance(InstanceMetaData instanceMetaData) {
        return !instanceMetaData.isHealthy();
    }

    private Map<HostGroupName, Set<InstanceMetaData>> selectRepairableNodes(
            Pair<Predicate<HostGroup>, Predicate<InstanceMetaData>> instanceSelectors,
            StackView stack) {
        return hostGroupService.getByCluster(stack.getClusterId())
                .stream()
                .filter(hostGroup -> RecoveryMode.MANUAL.equals(hostGroup.getRecoveryMode()))
                .filter(instanceSelectors.getLeft())
                .map(hostGroup -> Map.entry(hostGroupName(hostGroup.getName()), hostGroup
                        .getInstanceGroup()
                        .getNotTerminatedAndNotZombieInstanceMetaDataSet()
                        .stream()
                        .filter(instanceMetaData -> instanceMetaData.getDiscoveryFQDN() != null)
                        .filter(instanceSelectors.getRight())
                        .collect(toSet())))
                .filter(entry -> !entry.getValue().isEmpty())
                .collect(toMap(Entry::getKey, Entry::getValue));
    }

    private RepairValidation validateSelectedNodes(StackDto stack, Map<HostGroupName, Set<InstanceMetaData>> nodesToRepair, boolean reattach) {
        return new RepairValidation(nodesToRepair
                .entrySet()
                .stream()
                .map(entry -> validateRepairableNodes(stack, entry.getKey(), entry.getValue(), reattach))
                .flatMap(Collection::stream)
                .collect(toList())
        );
    }

    private List<String> validateRepairableNodes(StackDto stack, HostGroupName hostGroupName, Set<InstanceMetaData> instances, boolean reattach) {
        List<String> validationResult = new ArrayList<>();
        if (reattach) {
            for (InstanceMetaData instanceMetaData : instances) {
                validationResult.addAll(validateOnGateway(stack, instanceMetaData));
            }
            if (stackStopRestrictionService.isInfrastructureStoppable(stack) != StopRestrictionReason.NONE) {
                validationResult.add("Reattach not supported for this disk type.");
            }
        }
        return validationResult;
    }

    private List<String> validateOnGateway(StackDto stack, InstanceMetadataView instanceMetaData) {
        List<String> validationResult = new ArrayList<>();
        if (instanceMetaData.isGatewayOrPrimaryGateway()) {
            if (isCreatedFromBaseImage(stack.getStack())) {
                validationResult.add("Action is only supported if the image already contains Cloudera Manager and Cloudera Data Platform artifacts.");
            }
            if (!clusterDBValidationService.isGatewayRepairEnabled(stack.getCluster())) {
                validationResult.add(
                        "Action is only supported if Cloudera Manager state is stored in external Database or the cluster was launched after Mar/16/21.");
            }
        }
        return validationResult;
    }

    private boolean isCreatedFromBaseImage(StackView stack) {
        try {
            Image image = componentConfigProviderService.getImage(stack.getId());
            return !imageCatalogService.getImage(stack.getWorkspaceId(), image.getImageCatalogUrl(), image.getImageCatalogName(),
                    image.getImageId()).getImage().isPrewarmed();
        } catch (CloudbreakImageNotFoundException | CloudbreakImageCatalogException e) {
            throw new BadRequestException(e.getMessage(), e);
        }
    }

    private void updateVolumesDeleteFlag(StackView stack, Set<String> instanceIds, Set<String> instanceFQDNs, boolean deleteVolumes) {
        List<Resource> volumes = resourceService.findByStackIdAndType(stack.getId(), stack.getDiskResourceType());
        volumes = volumes.stream()
                .filter(volume -> instanceIds.contains(volume.getInstanceId()) || volumeFQDNIsInInstanceFQDNSet(volume, instanceFQDNs))
                .map(volumeSet -> updateDeleteVolumesFlag(deleteVolumes, volumeSet))
                .collect(toList());
        List<String> volumeNames = volumes.stream().map(Resource::getResourceName).collect(toList());
        LOGGER.info("Update delete volume flag on {} to {}", volumeNames, deleteVolumes);
        resourceService.saveAll(volumes);
    }

    private boolean volumeFQDNIsInInstanceFQDNSet(Resource volume, Set<String> instanceFQDNs) {
        try {
            Optional<VolumeSetAttributes> attributes = resourceAttributeUtil.getTypedAttributes(volume, VolumeSetAttributes.class);
            if (attributes.isPresent()) {
                if (instanceFQDNs.contains(attributes.get().getDiscoveryFQDN())) {
                    return true;
                }
            }
        } catch (CloudbreakServiceException cloudbreakServiceException) {
            LOGGER.warn("Can't parse resource attribute into VolumeSetAttributes class", cloudbreakServiceException);
        }
        return false;
    }

    private Resource updateDeleteVolumesFlag(boolean deleteVolumes, Resource volumeSet) {
        Optional<VolumeSetAttributes> attributes = resourceAttributeUtil.getTypedAttributes(volumeSet, VolumeSetAttributes.class);
        attributes.ifPresent(volumeSetAttributes -> {
            volumeSetAttributes.setDeleteOnTermination(deleteVolumes);
            resourceAttributeUtil.setTypedAttributes(volumeSet, volumeSetAttributes);
        });
        return volumeSet;
    }

    public void markVolumesToNonDeletable(StackView stack, List<InstanceMetadataView> instanceMetadataViews) {
        updateVolumesDeleteFlag(stack, instanceMetadataViews, false);
    }

    public void setStackStatusAndMarkDeletableVolumes(ManualClusterRepairMode repairMode, boolean deleteVolumes, StackView stack,
            List<InstanceMetadataView> instances) {
        if (!ManualClusterRepairMode.DRY_RUN.equals(repairMode) && !instances.isEmpty()) {
            LOGGER.info("Repair mode is not a dry run, {}", repairMode);
            updateVolumesDeleteFlag(stack, instances, deleteVolumes);
            LOGGER.info("Update stack status to REPAIR_IN_PROGRESS");
            stackUpdater.updateStackStatus(stack.getId(), DetailedStackStatus.REPAIR_IN_PROGRESS);
        }
    }

    private void updateVolumesDeleteFlag(StackView stack, List<InstanceMetadataView> instanceMetadataViews, boolean deleteVolumes) {
        Set<String> instanceIds = instanceMetadataViews.stream().map(InstanceMetadataView::getInstanceId).collect(toSet());
        Set<String> instanceFQDNs = instanceMetadataViews.stream().map(InstanceMetadataView::getDiscoveryFQDN).collect(toSet());
        updateVolumesDeleteFlag(stack, instanceIds, instanceFQDNs, deleteVolumes);
    }

    private FlowIdentifier triggerRepairOrThrowBadRequest(Long stackId, Result<Map<HostGroupName, Set<InstanceMetaData>>,
            RepairValidation> repairValidationResult, boolean oneNodeFromEachHostGroupAtOnce, boolean restartServices, Set<String> recoveryMessageArgument,
            String upgradeVariant, boolean upgrade) {
        if (repairValidationResult.isError()) {
            eventService.fireCloudbreakEvent(stackId, RECOVERY_FAILED, CLUSTER_MANUALRECOVERY_COULD_NOT_START,
                    repairValidationResult.getError().getValidationErrors());
            throw new BadRequestException(String.join(" ", repairValidationResult.getError().getValidationErrors()));
        } else {
            if (!repairValidationResult.getSuccess().isEmpty()) {
                FlowIdentifier flowIdentifier = flowManager.triggerClusterRepairFlow(stackId, toStringMap(repairValidationResult.getSuccess()),
                        oneNodeFromEachHostGroupAtOnce, restartServices, upgradeVariant, upgrade);
                eventService.fireCloudbreakEvent(stackId, RECOVERY, CLUSTER_MANUALRECOVERY_REQUESTED,
                        List.of(String.join(",", recoveryMessageArgument)));
                return flowIdentifier;
            } else {
                eventService.fireCloudbreakEvent(stackId, RECOVERY_FAILED, CLUSTER_MANUALRECOVERY_NO_NODES_TO_RECOVER, recoveryMessageArgument);
                throw new BadRequestException(String.format("Could not trigger cluster repair for stack %s because node list is incorrect", stackId));
            }
        }
    }

    private Map<String, List<String>> toStringMap(Map<HostGroupName, Set<InstanceMetaData>> repairableNodes) {
        return repairableNodes
                .entrySet()
                .stream()
                .collect(toMap(entry -> entry.getKey().value(),
                        entry -> entry.getValue()
                                .stream()
                                .filter(i -> i.getDiscoveryFQDN() != null)
                                .map(InstanceMetaData::getDiscoveryFQDN)
                                .collect(toList())));
    }
}
