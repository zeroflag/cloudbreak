package com.sequenceiq.it.cloudbreak.testcase.mock;

import javax.inject.Inject;

import org.testng.annotations.Test;

import com.sequenceiq.it.cloudbreak.client.StackTestClient;
import com.sequenceiq.it.cloudbreak.context.Description;
import com.sequenceiq.it.cloudbreak.context.MockedTestContext;
import com.sequenceiq.it.cloudbreak.context.TestContext;
import com.sequenceiq.it.cloudbreak.dto.stack.StackTestDto;
import com.sequenceiq.it.cloudbreak.exception.TestFailException;
import com.sequenceiq.it.cloudbreak.microservice.CloudbreakClient;

public class ShowStackCliRequestTest extends AbstractMockTest {

    @Inject
    private StackTestClient stackTestClient;

    @Test(dataProvider = TEST_CONTEXT_WITH_MOCK)
    @Description(given = "stack", when = "cluster exist", then = "we should return with the cli json")
    public void testGetBlueprintWhenClusterIsAliveThenShouldReturnWithBlueprint(MockedTestContext testContext) {
        String clusterName = resourcePropertyProvider().getName();
        testContext
                .given(StackTestDto.class).valid()
                .withName(clusterName)
                .when(stackTestClient.createV4())
                .await(STACK_AVAILABLE)
                .when(stackTestClient.requestV4())
                .then(ShowStackCliRequestTest::checkCliSkeleton)
                .validate();
    }

    private static StackTestDto checkCliSkeleton(TestContext testContext, StackTestDto stackTestDto, CloudbreakClient cloudbreakClient) {
        if (stackTestDto.getRequest() == null) {
            throw new TestFailException("Generated cli skeleton does not exist");
        }
        return stackTestDto;
    }
}
