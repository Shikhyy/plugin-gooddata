package io.kestra.plugin.gooddata;

import io.kestra.core.exceptions.IllegalVariableEvaluationException;
import io.kestra.core.http.HttpRequest;
import io.kestra.core.http.HttpResponse;
import io.kestra.core.http.client.HttpClient;
import io.kestra.core.http.client.configurations.HttpConfiguration;
import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.kestra.core.runners.RunContext;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.SuperBuilder;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Abstract base class for all GoodData tasks.
 * Holds shared connection properties (url, apiToken, timeout) and provides common HTTP utilities.
 */
@SuperBuilder
@ToString
@EqualsAndHashCode(callSuper = true)
@Getter
@NoArgsConstructor
public abstract class AbstractGooddata extends Task {

    @Schema(
        title = "GoodData host URL",
        description = "Base URL of the GoodData Cloud or GoodData.CN instance, e.g. https://acme.cloud.gooddata.com"
    )
    @NotNull
    @PluginProperty(group = "connection")
    protected Property<String> url;

    @Schema(
        title = "API token",
        description = "Personal API token for authentication (sent as Authorization: Bearer <token>). "
            + "Use a Kestra secret expression such as {{ secret('GOODDATA_TOKEN') }}."
    )
    @NotNull
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    protected Property<String> apiToken;

    @Schema(
        title = "HTTP request timeout",
        description = "Timeout duration for individual HTTP requests to the GoodData API."
    )
    @Builder.Default
    @PluginProperty(group = "connection")
    protected Property<Duration> requestTimeout = Property.ofValue(Duration.ofSeconds(60));

    public static String encodePath(String pathSegment) {
        if (pathSegment == null) {
            return "";
        }
        return URLEncoder.encode(pathSegment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static String validateAndNormalizeUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new IllegalArgumentException("'url' must be provided");
        }
        String trimmed = rawUrl.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new IllegalArgumentException("GoodData URL must start with 'http://' or 'https://', got: " + trimmed);
        }
        return trimmed.replaceAll("/+$", "");
    }

    protected String getRenderedApiToken(RunContext runContext) throws IllegalVariableEvaluationException {
        return runContext.render(this.apiToken).as(String.class).orElseThrow(
            () -> new IllegalArgumentException("'apiToken' must be provided"));
    }

    public static HttpClient createHttpClient(RunContext runContext, Duration timeout) throws IllegalVariableEvaluationException {
        Duration effectiveTimeout = timeout != null ? timeout : Duration.ofSeconds(60);
        HttpConfiguration config = HttpConfiguration.builder()
            .connectTimeout(effectiveTimeout)
            .readTimeout(effectiveTimeout)
            .allowFailed(Property.ofValue(true))
            .followRedirects(Property.ofValue(false))
            .build();
        return new HttpClient(runContext, config);
    }

    protected HttpResponse<String> executeRequest(RunContext runContext, HttpRequest request) throws Exception {
        Duration renderedTimeout = this.requestTimeout != null ? runContext.render(this.requestTimeout).as(Duration.class).orElse(Duration.ofSeconds(60)) : Duration.ofSeconds(60);
        return execute(runContext, request, renderedTimeout);
    }

    public static HttpResponse<String> execute(RunContext runContext, HttpRequest request, Duration timeout) throws Exception {
        try (HttpClient client = createHttpClient(runContext, timeout)) {
            HttpResponse<String> response = client.request(request, String.class);
            int statusCode = response.getStatus() != null ? response.getStatus().getCode() : 0;
            if (statusCode < 200 || statusCode >= 300) {
                String responseBody = response.getBody();
                String excerpt = responseBody != null
                    ? (responseBody.length() > 500 ? responseBody.substring(0, 500) + "..." : responseBody)
                    : "";
                String hint = switch (statusCode) {
                    case 401 -> "Check that your API token is valid and not expired.";
                    case 403 -> "Check that your API token has sufficient permissions for this operation.";
                    case 404 -> "Check that the requested resource, data source, or workspace ID exists.";
                    case 429 -> "Rate limit reached. Reduce polling frequency or retry later.";
                    default -> "Verify your GoodData endpoint URL and request parameters.";
                };
                throw new GooddataApiException(
                    "GoodData API error (HTTP " + statusCode + " "
                        + (response.getStatus() != null ? response.getStatus().getReason() : "") + "): "
                        + excerpt + ". Remediation: " + hint,
                    statusCode,
                    excerpt
                );
            }
            return response;
        }
    }
}
