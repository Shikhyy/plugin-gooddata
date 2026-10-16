package io.kestra.plugin.gooddata.workspaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * <p>Calls {@code GET /api/v1/entities/workspaces} with Bearer-token auth, follows
 * pagination links ({@code links.next}) when present, stores the consolidated JSON response
 * as a Kestra internal file, and returns the workspace count.</p>
 */
@SuperBuilder
@ToString
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
@Schema(
    title = "List GoodData workspaces",
    description = "Retrieves all workspaces accessible to the configured API token by calling "
        + "`GET /api/v1/entities/workspaces`. Follows pagination links when multiple pages exist. "
        + "The raw or aggregated JSON response is stored as a Kestra internal file."
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

        String currentUrl = baseUrl + "/api/v1/entities/workspaces";
        logger.info("Listing GoodData workspaces from {}", currentUrl);

        ArrayNode allWorkspaces = MAPPER.createArrayNode();
        String firstPageJson = null;
        int pageCount = 0;
        final int maxPages = 1000;

        while (currentUrl != null && !currentUrl.isBlank() && pageCount < maxPages) {
            pageCount++;
            HttpRequest request = HttpRequest.builder()
                .uri(URI.create(currentUrl))
                .method("GET")
                .addHeader("Authorization", "Bearer " + renderedToken)
                .addHeader("Accept", "application/json")
                .build();

            HttpResponse<String> response = executeRequest(runContext, request);
            String pageJson = response.getBody() != null ? response.getBody() : "{}";
            if (firstPageJson == null) {
                firstPageJson = pageJson;
            }

            JsonNode pageRoot = MAPPER.readTree(pageJson);
            JsonNode dataNode = pageRoot.path("data");
            if (dataNode.isArray()) {
                for (JsonNode item : dataNode) {
                    allWorkspaces.add(item);
                }
            }

            String nextLink = pageRoot.path("links").path("next").asText("");
            if (!nextLink.isBlank()) {
                if (nextLink.startsWith("http://") || nextLink.startsWith("https://")) {
                    URI nextUri = URI.create(nextLink);
                    URI baseUri = URI.create(baseUrl);
                    int basePort = baseUri.getPort() != -1 ? baseUri.getPort() : ("https".equalsIgnoreCase(baseUri.getScheme()) ? 443 : 80);
                    int nextPort = nextUri.getPort() != -1 ? nextUri.getPort() : ("https".equalsIgnoreCase(nextUri.getScheme()) ? 443 : 80);

                    if (nextUri.getHost() == null
                        || !nextUri.getHost().equalsIgnoreCase(baseUri.getHost())
                        || nextUri.getScheme() == null
                        || !nextUri.getScheme().equalsIgnoreCase(baseUri.getScheme())
                        || basePort != nextPort) {
                        throw new SecurityException("Untrusted next page host in GoodData response: '"
                            + nextUri + "' does not match configured base URL '" + baseUrl + "'");
                    }
                    currentUrl = nextLink;
                } else {
                    currentUrl = baseUrl + (nextLink.startsWith("/") ? nextLink : "/" + nextLink);
                }
                logger.debug("Fetching next page of workspaces from: {}", currentUrl);
            } else {
                currentUrl = null;
            }
        }

        int count = allWorkspaces.size();
        logger.info("Found {} workspaces across {} page(s)", count, pageCount);

        byte[] bytes;
        if (pageCount <= 1) {
            bytes = (firstPageJson != null ? firstPageJson : "{}").getBytes(StandardCharsets.UTF_8);
        } else {
            ObjectNode consolidated = MAPPER.createObjectNode();
            consolidated.set("data", allWorkspaces);
            bytes = MAPPER.writeValueAsBytes(consolidated);
        }

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
            description = "Kestra internal file URI pointing to the raw or consolidated JSON workspaces response."
        )
        private final URI uri;
    }
}
