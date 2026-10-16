package io.kestra.plugin.gooddata.executions;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.models.tasks.common.FetchType;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.gooddata.AbstractGooddata;
import io.kestra.plugin.gooddata.GooddataApiException;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * Kestra task that executes an AFM (Analytical Framework Model) query against a GoodData workspace.
 *
 * <p>Posts the AFM query to GoodData, polls the resulting executionResult endpoint until complete,
 * and fetches or stores the computed tabular result according to the configured {@code fetchType}.</p>
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
        + "Polls the execution result until completion and handles the result according to `fetchType`."
)
@Plugin(
    examples = {
        @Example(
            title = "Execute a GoodData AFM query and store the result",
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
                    fetchType: STORE
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
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> workspaceId;

    @Schema(
        title = "AFM request JSON",
        description = "The complete AFM (Analytical Framework Model) request body as a raw JSON string."
    )
    @NotNull
    @PluginProperty(group = "main")
    private Property<String> afmRequest;

    @Schema(
        title = "Fetch type",
        description = "How to process execution results: STORE (store raw JSON in Kestra storage), "
            + "FETCH (return all result rows), FETCH_ONE (return first row), or NONE."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<FetchType> fetchType = Property.ofValue(FetchType.STORE);

    @Schema(
        title = "Poll interval",
        description = "Interval between polling requests when waiting for AFM execution completion."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Duration> pollInterval = Property.ofValue(Duration.ofSeconds(1));

    @Schema(
        title = "Maximum polling duration",
        description = "Maximum duration to poll for AFM execution completion before timing out."
    )
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Duration> maxPollDuration = Property.ofValue(Duration.ofSeconds(60));

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        String baseUrl       = validateAndNormalizeUrl(runContext.render(url).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("'url' must be provided")));
        String renderedToken = getRenderedApiToken(runContext);
        String renderedWsId  = runContext.render(workspaceId).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("'workspaceId' must be provided"));
        String renderedAfm   = runContext.render(afmRequest).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("'afmRequest' must be provided"));

        FetchType renderedFetchType = this.fetchType != null
            ? runContext.render(this.fetchType).as(FetchType.class).orElse(FetchType.STORE)
            : FetchType.STORE;

        Duration renderedMaxPollDuration = this.maxPollDuration != null
            ? runContext.render(this.maxPollDuration).as(Duration.class).orElse(Duration.ofSeconds(60))
            : Duration.ofSeconds(60);

        Duration renderedPollInterval = this.pollInterval != null
            ? runContext.render(this.pollInterval).as(Duration.class).orElse(Duration.ofSeconds(1))
            : Duration.ofSeconds(1);

        String encodedWsId = encodePath(renderedWsId);
        String executeUrl  = baseUrl + "/api/v1/actions/workspaces/" + encodedWsId + "/execution/afm/execute";
        logger.info("Executing GoodData AFM query at {}", executeUrl);

        HttpRequest executeRequest = HttpRequest.builder()
            .uri(URI.create(executeUrl))
            .method("POST")
            .addHeader("Authorization", "Bearer " + renderedToken)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .body(HttpRequest.StringRequestBody.builder()
                .content(renderedAfm)
                .contentType("application/json")
                .charset(StandardCharsets.UTF_8)
                .build())
            .build();

        HttpResponse<String> executeResponse = executeRequest(runContext, executeRequest);
        String executeJson = executeResponse.getBody() != null ? executeResponse.getBody() : "{}";

        JsonNode executeRoot = MAPPER.readTree(executeJson);
        String executionResultLink = executeRoot.path("executionResponse")
            .path("links")
            .path("executionResult")
            .asText("");
        if (executionResultLink.isBlank()) {
            executionResultLink = executeRoot.path("links").path("executionResult").asText("");
        }
        if (executionResultLink.isBlank()) {
            executionResultLink = executeRoot.path("executionResponse").path("executionResult").asText("");
        }
        if (executionResultLink.isBlank()) {
            executionResultLink = executeRoot.path("executionResult").asText("");
        }

        String executionResultId = "";
        String resultJson;

        if (!executionResultLink.isBlank()) {
            String trimmedLink = executionResultLink.trim().replaceAll("/+$", "");
            if (trimmedLink.startsWith("http://") || trimmedLink.startsWith("https://")) {
                URI resultUri = URI.create(trimmedLink);
                URI baseUri = URI.create(baseUrl);
                int basePort = baseUri.getPort() != -1 ? baseUri.getPort() : ("https".equalsIgnoreCase(baseUri.getScheme()) ? 443 : 80);
                int resultPort = resultUri.getPort() != -1 ? resultUri.getPort() : ("https".equalsIgnoreCase(resultUri.getScheme()) ? 443 : 80);

                if (resultUri.getHost() == null
                    || !resultUri.getHost().equalsIgnoreCase(baseUri.getHost())
                    || resultUri.getScheme() == null
                    || !resultUri.getScheme().equalsIgnoreCase(baseUri.getScheme())
                    || basePort != resultPort) {
                    throw new SecurityException("Untrusted executionResult host in GoodData response: '"
                        + resultUri + "' does not match configured base URL '" + baseUrl + "'");
                }
            }

            int lastSlash = trimmedLink.lastIndexOf('/');
            String rawId = lastSlash != -1 ? trimmedLink.substring(lastSlash + 1) : trimmedLink;
            if (rawId.contains("?")) {
                rawId = rawId.substring(0, rawId.indexOf('?'));
            }
            if (rawId.contains("#")) {
                rawId = rawId.substring(0, rawId.indexOf('#'));
            }
            try {
                rawId = java.net.URLDecoder.decode(rawId, StandardCharsets.UTF_8);
            } catch (Exception ignored) {}

            rawId = rawId != null ? rawId.trim() : "";
            if (rawId.isBlank() || ".".equals(rawId) || "..".equals(rawId)) {
                throw new IllegalArgumentException("Invalid GoodData executionResult identifier: '" + rawId + "'");
            }

            executionResultId = rawId;
            logger.info("AFM execution started, result ID: {}", executionResultId);

            String resultUrl = baseUrl + "/api/v1/actions/workspaces/" + encodedWsId
                + "/execution/afm/execute/result/" + encodePath(rawId);

            long startTime = System.currentTimeMillis();
            long maxDurationMs = renderedMaxPollDuration.toMillis();
            long pollIntervalMs = Math.max(100, renderedPollInterval.toMillis());
            int attempts = 0;
            String fetchedBody = null;

            while (System.currentTimeMillis() - startTime < maxDurationMs) {
                attempts++;
                HttpRequest pollRequest = HttpRequest.builder()
                    .uri(URI.create(resultUrl))
                    .method("GET")
                    .addHeader("Authorization", "Bearer " + renderedToken)
                    .addHeader("Accept", "application/json")
                    .build();

                HttpResponse<String> pollResponse = executeRequest(runContext, pollRequest);
                int status = pollResponse.getStatus() != null ? pollResponse.getStatus().getCode() : 200;

                if (status == 200) {
                    fetchedBody = pollResponse.getBody() != null ? pollResponse.getBody() : "{}";
                    logger.info("AFM execution completed after {} attempt(s)", attempts);
                    break;
                } else if (status == 202) {
                    logger.debug("AFM execution in progress (HTTP 202), attempt {}, waiting...", attempts);
                    long remainingMs = maxDurationMs - (System.currentTimeMillis() - startTime);
                    if (remainingMs <= 0) {
                        break;
                    }
                    Thread.sleep(Math.min(pollIntervalMs, remainingMs));
                } else {
                    String bodyExcerpt = pollResponse.getBody() != null
                        ? (pollResponse.getBody().length() > 500 ? pollResponse.getBody().substring(0, 500) + "..." : pollResponse.getBody())
                        : "";
                    throw new GooddataApiException(
                        "Unexpected HTTP status during GoodData AFM result polling: " + status + ". Expected 200 (OK) or 202 (Accepted).",
                        status,
                        bodyExcerpt
                    );
                }
            }

            if (fetchedBody == null) {
                throw new TimeoutException("AFM execution did not complete within timeout of "
                    + renderedMaxPollDuration.toMillis() + "ms for resultId: " + executionResultId);
            }
            resultJson = fetchedBody;
        } else {
            // No separate execution link; response contains execution result directly
            resultJson = executeJson;
        }

        JsonNode resultRoot = MAPPER.readTree(resultJson);
        JsonNode dataNode = resultRoot.path("data");
        if (dataNode.isMissingNode() && resultRoot.has("executionResult")) {
            dataNode = resultRoot.path("executionResult").path("data");
        }
        if ((dataNode.isMissingNode() || dataNode.isNull()) && resultRoot.has("result")) {
            dataNode = resultRoot.path("result").path("data");
        }

        List<Object> rows = null;
        Object singleRow = null;
        Integer size = null;

        if (dataNode.isArray()) {
            size = dataNode.size();
            if (renderedFetchType == FetchType.FETCH) {
                rows = MAPPER.convertValue(dataNode, new TypeReference<List<Object>>() {});
            } else if (renderedFetchType == FetchType.FETCH_ONE) {
                if (dataNode.size() > 0) {
                    singleRow = MAPPER.convertValue(dataNode.get(0), Object.class);
                }
            }
        } else {
            size = 0;
        }

        URI storageUri = null;
        if (renderedFetchType == FetchType.STORE) {
            storageUri = runContext.storage().putFile(
                new ByteArrayInputStream(resultJson.getBytes(StandardCharsets.UTF_8)),
                "execution_result.json"
            );
        }

        return Output.builder()
            .executionResultId(executionResultId)
            .uri(storageUri)
            .rows(rows)
            .row(singleRow)
            .size(size)
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
            description = "Kestra internal file URI pointing to the full AFM execute result JSON (when fetchType is STORE)."
        )
        private final URI uri;

        @Schema(
            title = "Fetched rows",
            description = "List of result rows returned by the AFM query (when fetchType is FETCH)."
        )
        private final List<Object> rows;

        @Schema(
            title = "Single row",
            description = "First result row returned by the AFM query (when fetchType is FETCH_ONE)."
        )
        private final Object row;

        @Schema(
            title = "Result size",
            description = "Number of data rows or elements returned in the result."
        )
        private final Integer size;
    }
}
