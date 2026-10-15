This plugin integrates [GoodData](https://www.gooddata.com/) with [Kestra](https://kestra.io/),
letting you automate data-source scanning, analytics query execution, declarative workspace layout
export, and workspace change monitoring as part of your data pipelines.

## Tasks and Triggers

### `dataSources.Scan`

Triggers a schema scan on a GoodData data source via
`POST /api/v1/actions/dataSources/{dataSourceId}/scan`.

**Outputs**

| Field | Type | Description |
|---|---|---|
| `tablesFound` | `int` | Number of tables/views discovered |
| `uri` | `URI` | Kestra internal file URI containing the raw JSON scan output |

### `executions.Execute`

Executes an AFM (Analytical Framework Model) query against a GoodData workspace via
`POST /api/v1/actions/workspaces/{workspaceId}/execution/afm/execute`.

**Outputs**

| Field | Type | Description |
|---|---|---|
| `executionResultId` | `String` | GoodData execution result ID from executionResponse.links.executionResult |
| `uri` | `URI` | Kestra internal file URI containing the raw JSON execution response |

### `layouts.Export`

Exports the full declarative workspace layout (Logical Data Model + analytics model) via
`GET /api/v1/layout/workspaces/{workspaceId}`.

**Outputs**

| Field | Type | Description |
|---|---|---|
| `uri` | `URI` | Kestra internal file URI containing the raw JSON workspace layout |
| `datasetCount` | `int` | Count of LDM datasets found in the layout |

### `workspaces.List`

Lists all workspaces accessible to the API token via
`GET /api/v1/entities/workspaces`.

**Outputs**

| Field | Type | Description |
|---|---|---|
| `count` | `int` | Number of workspaces returned |
| `uri` | `URI` | Kestra internal file URI containing the raw JSON list |

### `workspaces.Trigger`

Polling trigger that monitors GoodData workspace changes and triggers executions when the
layout watermark changes, persisting state in Kestra's namespace KV Store.

## Authentication

All tasks accept a `url` (base URL of your GoodData Cloud or GoodData.CN instance) and an
`apiToken` (personal API token). Use Kestra secrets to avoid hardcoding credentials:

```yaml
url: "https://acme.cloud.gooddata.com"
apiToken: "{{ secret('GOODDATA_TOKEN') }}"
```

## Example Pipeline

```yaml
id: gooddata_pipeline
namespace: company.team

tasks:
  - id: scan
    type: io.kestra.plugin.gooddata.dataSources.Scan
    url: "https://acme.cloud.gooddata.com"
    apiToken: "{{ secret('GOODDATA_TOKEN') }}"
    dataSourceId: "warehouse"

  - id: export_layout
    type: io.kestra.plugin.gooddata.layouts.Export
    url: "https://acme.cloud.gooddata.com"
    apiToken: "{{ secret('GOODDATA_TOKEN') }}"
    workspaceId: "sales"

  - id: list_workspaces
    type: io.kestra.plugin.gooddata.workspaces.List
    url: "https://acme.cloud.gooddata.com"
    apiToken: "{{ secret('GOODDATA_TOKEN') }}"
```
