package com.sequenceiq.environment.environment.flow.creation.event;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.sequenceiq.cloudbreak.common.event.AcceptResult;
import com.sequenceiq.cloudbreak.eventbus.Promise;
import com.sequenceiq.flow.reactor.api.event.BaseFlowEvent;
import com.sequenceiq.flow.reactor.api.event.BaseNamedFlowEvent;

@JsonDeserialize(builder = EnvCreationEvent.EnvCreationEventBuilder.class)
public class EnvCreationEvent extends BaseNamedFlowEvent {

    public EnvCreationEvent(String selector, Long resourceId, String resourceName, String resourceCrn) {
        super(selector, resourceId, resourceName, resourceCrn);
    }

    public EnvCreationEvent(String selector, Long resourceId, Promise<AcceptResult> accepted, String resourceName, String resourceCrn) {
        super(selector, resourceId, accepted, resourceName, resourceCrn);
    }

    @Override
    public boolean equalsEvent(BaseFlowEvent other) {
        return isClassAndEqualsEvent(EnvCreationEvent.class, other);
    }

    public static EnvCreationEventBuilder builder() {
        return new EnvCreationEventBuilder();
    }

    @JsonPOJOBuilder
    public static final class EnvCreationEventBuilder {
        private String resourceName;

        private String resourceCrn;

        private String selector;

        private Long resourceId;

        private Promise<AcceptResult> accepted;

        private EnvCreationEventBuilder() {
        }

        public EnvCreationEventBuilder withResourceName(String resourceName) {
            this.resourceName = resourceName;
            return this;
        }

        public EnvCreationEventBuilder withSelector(String selector) {
            this.selector = selector;
            return this;
        }

        public EnvCreationEventBuilder withResourceId(Long resourceId) {
            this.resourceId = resourceId;
            return this;
        }

        public EnvCreationEventBuilder withResourceCrn(String resourceCrn) {
            this.resourceCrn = resourceCrn;
            return this;
        }

        public EnvCreationEventBuilder withAccepted(Promise<AcceptResult> accepted) {
            this.accepted = accepted;
            return this;
        }

        public EnvCreationEvent build() {
            return new EnvCreationEvent(selector, resourceId, accepted, resourceName, resourceCrn);
        }
    }
}
