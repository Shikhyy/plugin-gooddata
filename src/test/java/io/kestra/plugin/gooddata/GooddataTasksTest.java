package io.kestra.plugin.gooddata;

import io.kestra.core.models.property.Property;
import io.kestra.plugin.gooddata.dataSources.Scan;
import io.kestra.plugin.gooddata.executions.Execute;
import io.kestra.plugin.gooddata.layouts.Export;
import io.kestra.plugin.gooddata.workspaces.List;
import io.kestra.plugin.gooddata.workspaces.Trigger;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

public class GooddataTasksTest {

    @Test
    void scanDataSourceTaskBuilder() {
        Scan scan = Scan.builder()
            .id("scan-test")
            .url(Property.of("https://acme.cloud.gooddata.com"))
            .apiToken(Property.of("secret-token"))
            .dataSourceId(Property.of("postgres-source"))
            .build();

        assertThat(scan.getId(), is("scan-test"));
        assertThat(scan.getUrl(), notNullValue());
        assertThat(scan.getApiToken(), notNullValue());
        assertThat(scan.getDataSourceId(), notNullValue());
    }

    @Test
    void executeAfmTaskBuilder() {
        Execute execute = Execute.builder()
            .id("exec-test")
            .url(Property.of("https://acme.cloud.gooddata.com"))
            .apiToken(Property.of("secret-token"))
            .workspaceId(Property.of("sales-workspace"))
            .afmRequest(Property.of("{\"execution\": {}}"))
            .build();

        assertThat(execute.getId(), is("exec-test"));
        assertThat(execute.getWorkspaceId(), notNullValue());
        assertThat(execute.getAfmRequest(), notNullValue());
    }

    @Test
    void exportWorkspaceLayoutTaskBuilder() {
        Export export = Export.builder()
            .id("export-test")
            .url(Property.of("https://acme.cloud.gooddata.com"))
            .apiToken(Property.of("secret-token"))
            .workspaceId(Property.of("marketing-workspace"))
            .build();

        assertThat(export.getId(), is("export-test"));
        assertThat(export.getWorkspaceId(), notNullValue());
    }

    @Test
    void listWorkspacesTaskBuilder() {
        List list = List.builder()
            .id("list-test")
            .url(Property.of("https://acme.cloud.gooddata.com"))
            .apiToken(Property.of("secret-token"))
            .build();

        assertThat(list.getId(), is("list-test"));
        assertThat(list.getUrl(), notNullValue());
    }

    @Test
    void triggerBuilder() {
        Trigger trigger = Trigger.builder()
            .id("trigger-test")
            .url(Property.of("https://acme.cloud.gooddata.com"))
            .apiToken(Property.of("secret-token"))
            .workspaceId(Property.of("sales-workspace"))
            .interval(Duration.ofMinutes(10))
            .build();

        assertThat(trigger.getId(), is("trigger-test"));
        assertThat(trigger.getInterval(), is(Duration.ofMinutes(10)));
        assertThat(trigger.getWorkspaceId(), notNullValue());
    }
}
