package io.kestra.plugin.gooddata.workspaces;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.RunnableTask;
import io.kestra.core.runners.RunContext;
import io.kestra.core.serializers.JacksonMapper;
import io.kestra.plugin.gooddata.AbstractGooddata;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

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

    private static final ObjectMapper MAPPER = JacksonMapper.ofJson();

    @Schema(
        title = "Maximum pages",
        description = "Maximum number of pagination pages to fetch. Must be at least 1. Defaults to 50."
    )
    @NotNull
    @Min(1)
    @Builder.Default
    @PluginProperty(group = "processing")
    private Property<Integer> maxPages = Property.ofValue(50);

    @Override
    public Output run(RunContext runContext) throws Exception {
        var logger = runContext.logger();

        var rUrl = validateAndNormalizeUrl(runContext.render(url).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("'url' must be provided")));
        var rToken = getRenderedApiToken(runContext);

        var rMaxPages = this.maxPages != null
            ? runContext.render(this.maxPages).as(Integer.class).orElse(50)
            : 50;

        if (rMaxPages < 1) {
            throw new IllegalArgumentException("'maxPages' must be greater than or equal to 1, got: " + rMaxPages);
        }

        var currentUrl = rUrl + "/api/v1/entities/workspaces";
        logger.info("Listing GoodData workspaces from {}", currentUrl);

        var allWorkspaces = MAPPER.createArrayNode();
        String firstPageJson = null;
        var pageCount = 0;
        var isTruncated = false;

        var visitedUrls = new HashSet<String>();
        visitedUrls.add(currentUrl);

        while (currentUrl != null && !currentUrl.isBlank()) {
            if (pageCount >= rMaxPages) {
                isTruncated = true;
                logger.warn("GoodData workspace list reached maximum page limit of {} with remaining pages available. "
                    + "The results are truncated. Consider increasing 'maxPages'.", rMaxPages);
                break;
            }

            pageCount++;
            var request = HttpRequest.builder()
                .uri(URI.create(currentUrl))
                .method("GET")
                .addHeader("Authorization", "Bearer " + rToken)
                .addHeader("Accept", "application/json")
                .build();

            var response = executeRequest(runContext, request);
            var pageJson = response.getBody() != null ? response.getBody() : "{}";
            if (firstPageJson == null) {
                firstPageJson = pageJson;
            }

            var pageRoot = MAPPER.readTree(pageJson);
            var dataNode = pageRoot.path("data");
            if (dataNode.isArray()) {
                for (var item : dataNode) {
                    allWorkspaces.add(item);
                }
            }

            var nextLink = pageRoot.path("links").path("next").asText("");
            if (!nextLink.isBlank()) {
                String resolvedNextUrl;
                if (nextLink.startsWith("http://") || nextLink.startsWith("https://")) {
                    var nextUri = URI.create(nextLink);
                    var baseUri = URI.create(rUrl);
                    var basePort = baseUri.getPort() != -1 ? baseUri.getPort() : ("https".equalsIgnoreCase(baseUri.getScheme()) ? 443 : 80);
                    var nextPort = nextUri.getPort() != -1 ? nextUri.getPort() : ("https".equalsIgnoreCase(nextUri.getScheme()) ? 443 : 80);

                    if (nextUri.getHost() == null
                        || !nextUri.getHost().equalsIgnoreCase(baseUri.getHost())
                        || nextUri.getScheme() == null
                        || !nextUri.getScheme().equalsIgnoreCase(baseUri.getScheme())
                        || basePort != nextPort) {
                        throw new SecurityException("Untrusted next page host in GoodData response: '"
                            + nextUri + "' does not match configured base URL '" + rUrl + "'");
                    }
                    resolvedNextUrl = nextLink;
                } else {
                    resolvedNextUrl = rUrl + (nextLink.startsWith("/") ? nextLink : "/" + nextLink);
                }

                if (visitedUrls.contains(resolvedNextUrl)) {
                    logger.warn("Detected circular pagination loop in GoodData workspaces. Next URL '{}' already visited. Halting pagination.", resolvedNextUrl);
                    currentUrl = null;
                } else {
                    visitedUrls.add(resolvedNextUrl);
                    currentUrl = resolvedNextUrl;
                    logger.debug("Fetching next page of workspaces from: {}", currentUrl);
                }
            } else {
                currentUrl = null;
            }
        }

        var count = allWorkspaces.size();
        logger.info("Found {} workspaces across {} page(s)", count, pageCount);

        byte[] bytes;
        if (pageCount <= 1) {
            bytes = (firstPageJson != null ? firstPageJson : "{}").getBytes(StandardCharsets.UTF_8);
        } else {
            var consolidated = MAPPER.createObjectNode();
            consolidated.set("data", allWorkspaces);
            bytes = MAPPER.writeValueAsBytes(consolidated);
        }

        var storageUri = runContext.storage().putFile(new ByteArrayInputStream(bytes), "workspaces.json");

        return Output.builder()
            .count(count)
            .uri(storageUri)
            .truncated(isTruncated)
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

        @Schema(
            title = "Truncated flag",
            description = "Whether the workspace list was truncated due to reaching maxPages while more pages were available."
        )
        private final Boolean truncated;
    }
}
