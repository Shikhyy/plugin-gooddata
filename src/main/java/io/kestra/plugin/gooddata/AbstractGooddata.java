package io.kestra.plugin.gooddata;

import io.kestra.core.models.annotations.PluginProperty;
import io.kestra.core.models.property.Property;
import io.kestra.core.models.tasks.Task;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;
import lombok.experimental.SuperBuilder;

/**
 * Abstract base class for all GoodData tasks that use the new sub-package structure.
 * Holds shared authentication properties (url + apiToken).
 */
@SuperBuilder
@ToString
@EqualsAndHashCode
@Getter
@NoArgsConstructor
public abstract class AbstractGooddata extends Task {

    @Schema(
        title = "GoodData host URL",
        description = "Base URL of the GoodData Cloud or GoodData.CN instance, "
            + "e.g. https://acme.cloud.gooddata.com"
    )
    protected Property<String> url;

    @Schema(
        title = "API token",
        description = "Personal API token for authentication (sent as Authorization: Bearer <token>). "
            + "Use a Kestra secret expression such as {{ secret('GOODDATA_TOKEN') }}."
    )
    @PluginProperty(group = "connection", secret = true)
    @ToString.Exclude
    protected Property<String> apiToken;
}
