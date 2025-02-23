package com.sequenceiq.consumption.api.doc;

public class ConsumptionModelDescription {
    public static final String ENVIRONMENT_CRN = "CRN of the environment which the consumption data is for";
    public static final String STORAGE_LOCATION = "Cloud storage location whose consumption is being tracked";
    public static final String CLOUD_RESOURCE_ID = "Cloud resource ID whose consumption is being tracked";
    public static final String MONITORED_RESOURCE_TYPE = "Type of the resource whose consumption is being tracked";
    public static final String MONITORED_RESOURCE_CRN = "CRN of the resource whose consumption is being tracked";
    public static final String MONITORED_RESOURCE_NAME = "Name of the resource whose consumption is being tracked";
    public static final String EXISTS = "Indicates if the requested consumption already exists";
    public static final String CONSUMPTION_TYPE = "Type of the cloud resource whose consumption is being tracked";

    private ConsumptionModelDescription() {
    }
}
