package io.kestra.plugin.gooddata.executions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.gooddata.AbstractGooddata;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

/**
 * Kestra task that executes an AFM (Analytical Framework Model) query against a GoodData workspace.
 *
 * <p>Calls {@code POST /api/v1/actions/workspaces/{workspaceId}/execution/afm/execute}
 * with a user-supplied raw JSON AFM body, stores the response, and returns the execution result ID.</p>
 */
@SuperBuilder
@ToString
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
@Schema(
    title = "Execute a GoodData AFM analytics query",
    description = "Posts an AFM (Analytical Framework Model) request to "
        + "`POST /api/v1/actions/workspaces/{workspaceId}/execution/afm/execute`. "
        + "The caller supplies the complete AFM JSON body. "
        + "Returns the execution result ID and stores the full response JSON."
)
@Plugin(
    examples = {
        @Example(
            title = "Execute a GoodData AFM query",
            full = true,
            code = """
                id: executions_execute
                namespace: company.team

                tasks:
                  - id: execute
                    type: io.kestra.plugin.gooddata.executions.Execute
                    url: "https://acme.cloud.gooddata.com"
                    apiToken: "{{ secret('GOODDATA_TOKEN') }}"
                    workspaceId: "my-workspace-id"
                    afmRequest: |
                      {
                        "execution": {
                          "measures": [
                            {
                              "localIdentifier": "m1",
                              "definition": {
                                "measure": {
                                  "item": { "identifier": "measure/revenue", "type": "measure" }
                                }
                              }
                            }
                          ],
                          "attributes": [],
                          "filters": []
                        },
                        "resultSpec": {
                          "dimensions": [
                            { "itemIdentifiers": ["measureGroup"] }
                          ]
                        }
                      }
                """
        )
    }
)
public class Execute extends AbstractGooddata implements RunnableTask<Execute.Output> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Schema(
        title = "Workspace ID",
        description = "The identifier of the GoodData workspace to execute the AFM query against."
    )
    private Property<String> workspaceId;

    @Schema(
        title = "AFM request JSON",
        description = "The complete AFM (Analytical Framework Model) request body as a raw JSON string. "
            + "Must conform to the GoodData AFM execute API schema."
    )
    private Property<String> afmRequest;

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        String renderedUrl     = runContext.render(url).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("'url' must be provided"));
        String renderedToken   = runContext.render(apiToken).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("'apiToken' must be provided"));
        String renderedWsId    = runContext.render(workspaceId).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("'workspaceId' must be provided"));
        String renderedAfm     = runContext.render(afmRequest).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("'afmRequest' must be provided"));

        String baseUrl     = renderedUrl.replaceAll("/+$", "");
        String executeUrl  = baseUrl + "/api/v1/actions/workspaces/" + renderedWsId
            + "/execution/afm/execute";
        logger.info("Executing AFM query at {}", executeUrl);
        logger.debug("AFM request body: {}", renderedAfm);

        HttpClient client = HttpClient.newBuilder().build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(executeUrl))
            .header("Authorization", "Bearer " + renderedToken)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(renderedAfm))
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException(
                "GoodData AFM execute API returned HTTP " + response.statusCode()
                    + ": " + response.body());
        }

        String rawJson = response.body();
        logger.debug("AFM execute raw response: {}", rawJson);

        // Extract execution result ID from response:
        // {"executionResponse":{"links":{"executionResult":"..."}}}
        JsonNode root = MAPPER.readTree(rawJson);
        String executionResultId = root.path("executionResponse")
            .path("links")
            .path("executionResult")
            .asText("");
        logger.info("AFM execution result ID: {}", executionResultId);

        URI storageUri = runContext.storage().putFile(
            new ByteArrayInputStream(rawJson.getBytes(StandardCharsets.UTF_8)),
            "execution.json");

        return Output.builder()
            .executionResultId(executionResultId)
            .uri(storageUri)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "Execution result ID",
            description = "The execution result identifier returned by GoodData, "
                + "extracted from executionResponse.links.executionResult."
        )
        private final String executionResultId;

        @Schema(
            title = "Raw response URI",
            description = "Kestra internal file URI pointing to the full AFM execute response JSON."
        )
        private final URI uri;
    }
}
