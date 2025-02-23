package com.sequenceiq.environment.environment.flow.config.update.event;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import com.sequenceiq.cloudbreak.common.event.AcceptResult;
import com.sequenceiq.cloudbreak.eventbus.Promise;
import com.sequenceiq.flow.reactor.api.event.BaseFlowEvent;
import com.sequenceiq.flow.reactor.api.event.BaseNamedFlowEvent;

@JsonDeserialize(builder = EnvStackConfigUpdatesEvent.EnvStackConfigUpdatesEventBuilder.class)
public class EnvStackConfigUpdatesEvent extends BaseNamedFlowEvent {

    public EnvStackConfigUpdatesEvent(String selector, Long resourceId, String resourceName,
        String resourceCrn) {
        super(selector, resourceId, resourceName, resourceCrn);
    }

    public EnvStackConfigUpdatesEvent(String selector, Long resourceId,
        Promise<AcceptResult> accepted, String resourceName, String resourceCrn) {
        super(selector, resourceId, accepted, resourceName, resourceCrn);
    }

    @Override
    public boolean equalsEvent(BaseFlowEvent other) {
        return isClassAndEqualsEvent(EnvStackConfigUpdatesEvent.class, other);
    }

    @JsonPOJOBuilder
    public static final class EnvStackConfigUpdatesEventBuilder {

        private String resourceName;

        private String resourceCrn;

        private String selector;

        private Long resourceId;

        private Promise<AcceptResult> accepted;

        private EnvStackConfigUpdatesEventBuilder() {
        }

        public static EnvStackConfigUpdatesEventBuilder anEnvStackConfigUpdatesEvent() {
            return new EnvStackConfigUpdatesEventBuilder();
        }

        public EnvStackConfigUpdatesEventBuilder withResourceName(String resourceName) {
            this.resourceName = resourceName;
            return this;
        }

        public EnvStackConfigUpdatesEventBuilder withSelector(String selector) {
            this.selector = selector;
            return this;
        }

        public EnvStackConfigUpdatesEventBuilder withResourceId(Long resourceId) {
            this.resourceId = resourceId;
            return this;
        }

        public EnvStackConfigUpdatesEventBuilder withAccepted(Promise<AcceptResult> accepted) {
            this.accepted = accepted;
            return this;
        }

        public EnvStackConfigUpdatesEventBuilder withResourceCrn(String resourceCrn) {
            this.resourceCrn = resourceCrn;
            return this;
        }

        public EnvStackConfigUpdatesEvent build() {
            EnvStackConfigUpdatesEvent event = new EnvStackConfigUpdatesEvent(selector, resourceId,
                accepted, resourceName, resourceCrn);
            return event;
        }
    }
}
