package io.kestra.plugin.gooddata.workspaces;

import io.kestra.core.models.annotations.Example;
import io.kestra.core.models.annotations.Plugin;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.conditions.ConditionContext;
import io.kestra.core.models.executions.Execution;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.triggers.AbstractTrigger;
import io.kestra.core.models.triggers.PollingTriggerInterface;
import io.kestra.core.models.triggers.TriggerContext;
import io.kestra.core.models.triggers.TriggerOutput;
import io.kestra.core.models.triggers.TriggerService;
import io.kestra.core.runners.RunContext;
import io.kestra.core.storages.kv.KVStore;
import io.kestra.core.storages.kv.KVValue;
import io.kestra.core.storages.kv.KVValueAndMetadata;
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
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Polling trigger that fires when a GoodData workspace layout or workspace list changes
 * relative to the persisted watermark in Kestra's namespace KV Store.
 */
@SuperBuilder
@ToString
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
@Schema(
    title = "Trigger on GoodData workspace changes",
    description = "Polls GoodData for workspace or layout changes. Fires when the layout watermark "
        + "differs from the previous watermark stored in Kestra's namespace KV Store."
)
@Plugin(
    examples = {
        @Example(
            title = "Trigger a flow when a GoodData workspace layout changes",
            full = true,
            code = """
                id: on_gooddata_change
                namespace: company.team

                triggers:
                  - id: watch
                    type: io.kestra.plugin.gooddata.workspaces.Trigger
                    url: "https://acme.cloud.gooddata.com"
                    apiToken: "{{ secret('GOODDATA_TOKEN') }}"
                    interval: PT5M

                tasks:
                  - id: handle_event
                    type: io.kestra.plugin.core.log.Log
                    message: "GoodData change detected. New watermark: {{ trigger.watermark }}"
                """
        )
    }
)
public class Trigger extends AbstractTrigger implements PollingTriggerInterface, TriggerOutput<Trigger.Output> {

    @Schema(
        title = "Polling interval",
        description = "Interval between poll checks."
    )
    @Builder.Default
    private Duration interval = Duration.ofMinutes(5);

    @Schema(
        title = "GoodData host URL",
        description = "Base URL of the GoodData Cloud or GoodData.CN instance, e.g. https://acme.cloud.gooddata.com"
    )
    private Property<String> url;

    @Schema(
        title = "API token",
        description = "Personal API token for authentication."
    )
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    private Property<String> apiToken;

    @Schema(
        title = "Workspace ID",
        description = "Optional workspace ID to monitor layout changes for. If omitted, monitors the workspace list."
    )
    private Property<String> workspaceId;

    @Override
    public Duration getInterval() {
        return interval;
    }

    @Override
    public Optional<Execution> evaluate(ConditionContext conditionContext, TriggerContext triggerContext) throws Exception {
        RunContext runContext = conditionContext.getRunContext();
        Logger logger = runContext.logger();

        String renderedUrl   = runContext.render(url).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("'url' must be provided"));
        String renderedToken = runContext.render(apiToken).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("'apiToken' must be provided"));
        String renderedWsId  = workspaceId != null ? runContext.render(workspaceId).as(String.class).orElse(null) : null;

        String baseUrl = renderedUrl.replaceAll("/+$", "");
        String endpoint = (renderedWsId != null && !renderedWsId.isBlank())
            ? baseUrl + "/api/v1/layout/workspaces/" + renderedWsId
            : baseUrl + "/api/v1/entities/workspaces";

        logger.debug("Checking GoodData change at endpoint: {}", endpoint);

        HttpClient client = HttpClient.newBuilder().build();
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(endpoint))
            .header("Authorization", "Bearer " + renderedToken)
            .header("Accept", "application/json")
            .GET()
            .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            logger.warn("GoodData trigger polling returned HTTP {}: {}", response.statusCode(), response.body());
            return Optional.empty();
        }

        String rawJson = response.body();

        // Calculate watermark fingerprint via SHA-256
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(rawJson.getBytes(StandardCharsets.UTF_8));
        String currentWatermark = HexFormat.of().formatHex(hash);

        // Retrieve last-seen watermark from Kestra KV store
        String namespace = triggerContext.getNamespace();
        KVStore kvStore = runContext.namespaceKv(namespace);
        String targetSuffix = (renderedWsId != null && !renderedWsId.isBlank())
            ? renderedWsId.replaceAll("[^a-zA-Z0-9_-]", "_")
            : "entities";
        String kvKey = "gooddata_watermark_" + targetSuffix;

        Optional<KVValue> previousValue = kvStore.getValue(kvKey);
        if (previousValue.isPresent() && currentWatermark.equals(String.valueOf(previousValue.get().value()))) {
            logger.debug("No GoodData change detected for {}, watermark={}", kvKey, currentWatermark);
            return Optional.empty();
        }

        logger.info("GoodData change detected for {}. Updating watermark to {}", kvKey, currentWatermark);
        kvStore.put(kvKey, new KVValueAndMetadata(null, currentWatermark));

        URI storageUri = runContext.storage().putFile(
            new ByteArrayInputStream(rawJson.getBytes(StandardCharsets.UTF_8)),
            "trigger_" + targetSuffix + ".json"
        );

        Output output = Output.builder()
            .watermark(currentWatermark)
            .workspaceId(renderedWsId)
            .uri(storageUri)
            .build();

        Execution execution = TriggerService.generateExecution(this, conditionContext, triggerContext, output);
        return Optional.of(execution);
    }

    @Builder
    @Getter
    public static class Output implements io.kestra.core.models.tasks.Output {

        @Schema(
            title = "Watermark",
            description = "The updated SHA-256 fingerprint representing the new layout/entities state."
        )
        private final String watermark;

        @Schema(
            title = "Workspace ID",
            description = "The monitored workspace ID, if configured."
        )
        private final String workspaceId;

        @Schema(
            title = "State URI",
            description = "Kestra internal file URI pointing to the detected state JSON."
        )
        private final URI uri;
    }
}
