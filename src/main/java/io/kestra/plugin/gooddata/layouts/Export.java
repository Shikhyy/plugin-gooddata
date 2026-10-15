package io.kestra.plugin.gooddata.layouts;

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
 * Kestra task that exports the full declarative workspace layout from GoodData.
 *
 * <p>Calls {@code GET /api/v1/layout/workspaces/{workspaceId}} with Bearer-token auth,
 * stores the raw JSON layout, and returns the count of LDM datasets.</p>
 */
@SuperBuilder
@ToString
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
@Schema(
    title = "Export a GoodData workspace layout",
    description = "Exports the full declarative layout (Logical Data Model + analytics model) "
        + "of a GoodData workspace by calling `GET /api/v1/layout/workspaces/{workspaceId}`. "
        + "The raw JSON is stored as a Kestra internal file."
)
@Plugin(
    examples = {
        @Example(
            title = "Export a GoodData workspace layout",
            full = true,
            code = """
                id: layouts_export
                namespace: company.team

                tasks:
                  - id: export
                    type: io.kestra.plugin.gooddata.layouts.Export
                    url: "https://acme.cloud.gooddata.com"
                    apiToken: "{{ secret('GOODDATA_TOKEN') }}"
                    workspaceId: "my-workspace-id"
                """
        )
    }
)
public class Export extends AbstractGooddata implements RunnableTask<Export.Output> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Schema(
        title = "Workspace ID",
        description = "The identifier of the GoodData workspace whose declarative layout to export."
    )
    private Property<String> workspaceId;

    @Override
    public Output run(RunContext runContext) throws Exception {
        Logger logger = runContext.logger();

        String renderedUrl   = runContext.render(url).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("'url' must be provided"));
        String renderedToken = runContext.render(apiToken).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("'apiToken' must be provided"));
        String renderedWsId  = runContext.render(workspaceId).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("'workspaceId' must be provided"));

        String baseUrl   = renderedUrl.replaceAll("/+$", "");
        String layoutUrl = baseUrl + "/api/v1/layout/workspaces/" + renderedWsId;
        logger.info("Exporting workspace layout from {}", layoutUrl);

        HttpClient client = HttpClient.newBuilder().build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(layoutUrl))
            .header("Authorization", "Bearer " + renderedToken)
            .header("Accept", "application/json")
            .GET()
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException(
                "GoodData layout API returned HTTP " + response.statusCode()
                    + ": " + response.body());
        }

        String rawJson = response.body();
        logger.debug("Workspace layout response length: {} bytes", rawJson.length());

        // Count LDM datasets: response shape uses ldm.datasets
        JsonNode root = MAPPER.readTree(rawJson);
        int datasetCount = root.path("ldm").path("datasets").size();
        if (datasetCount == 0) {
            // Fallback: workspaceLayout.ldm.datasets
            datasetCount = root.path("workspaceLayout").path("ldm").path("datasets").size();
        }
        logger.info("Workspace layout contains {} datasets", datasetCount);

        URI storageUri = runContext.storage().putFile(
            new ByteArrayInputStream(rawJson.getBytes(StandardCharsets.UTF_8)),
            "layout.json");

        return Output.builder()
            .uri(storageUri)
            .datasetCount(datasetCount)
            .build();
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "Workspace layout URI",
            description = "Kestra internal file URI pointing to the raw JSON workspace layout."
        )
        private final URI uri;

        @Schema(
            title = "Dataset count",
            description = "Number of LDM datasets present in the exported workspace layout."
        )
        private final int datasetCount;
    }
}
