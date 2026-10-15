package io.kestra.plugin.gooddata.workspaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.plugin.gooddata.AbstractGooddata;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Kestra task that lists all GoodData workspaces accessible by the API token.
 *
 * <p>Calls {@code GET /api/v1/entities/workspaces} with Bearer-token auth, stores
 * the raw JSON response as a Kestra internal file, and returns the workspace count.</p>
 */
@SuperBuilder
@ToString
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
@Schema(
    title = "List GoodData workspaces",
    description = "Retrieves all workspaces accessible to the configured API token by calling "
        + "`GET /api/v1/entities/workspaces`. "
        + "The raw JSON response is stored as a Kestra internal file."
)
@Plugin(
    examples = {
        @Example(
            title = "List all GoodData workspaces",
            full = true,
            code = """
                id: workspaces_list
                namespace: company.team

                tasks:
                  - id: list
                    type: io.kestra.plugin.gooddata.workspaces.List
                    url: "https://acme.cloud.gooddata.com"
                    apiToken: "{{ secret('GOODDATA_TOKEN') }}"
                """
        )
    }
)
public class List extends AbstractGooddata implements RunnableTask<List.Output> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        String baseUrl = validateAndNormalizeUrl(runContext.render(url).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("'url' must be provided")));
        String renderedToken = getRenderedApiToken(runContext);

        String endpoint = baseUrl + "/api/v1/entities/workspaces";
        logger.info("Listing GoodData workspaces from {}", endpoint);

        HttpRequest request = HttpRequest.builder()
            .uri(URI.create(endpoint))
            .method("GET")
            .addHeader("Authorization", "Bearer " + renderedToken)
            .addHeader("Accept", "application/json")
            .build();

        HttpResponse<String> response = executeRequest(runContext, request);
        String rawJson = response.getBody() != null ? response.getBody() : "{}";

        JsonNode root = MAPPER.readTree(rawJson);
        int count = root.path("data").size();
        logger.info("Found {} workspaces", count);

        byte[] bytes = rawJson.getBytes(StandardCharsets.UTF_8);
        URI storageUri = runContext.storage().putFile(new ByteArrayInputStream(bytes), "workspaces.json");

        return Output.builder()
            .count(count)
            .uri(storageUri)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "Workspace count",
            description = "The number of workspaces returned by the GoodData API."
        )
        private final int count;

        @Schema(
            title = "Raw response URI",
            description = "Kestra internal file URI pointing to the raw JSON workspaces response."
        )
        private final URI uri;
    }
}
