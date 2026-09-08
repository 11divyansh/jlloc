# Phase 6 Diagonal Scaling

jlloc's Phase 6 contract is not "build a scaler". It is to expose a
stable, machine-readable scaling verdict that existing tooling can act
on.

## Contract

The daemon exports:

- `jlloc_service_process_count`
- `jlloc_service_heap_used_bytes`
- `jlloc_service_heap_max_bytes`
- `jlloc_service_heap_used_ratio`
- `jlloc_service_rss_bytes`
- `jlloc_service_container_limit_bytes`
- `jlloc_service_container_pressure`
- `jlloc_service_diagnosis`
- `jlloc_service_scaling_decision`

The last metric is the important one for diagonal scaling. It encodes:

- `axis`: `HORIZONTAL`, `VERTICAL`, `FOOTPRINT`, or `HOLD`
- `direction`: `UP`, `DOWN`, or `NONE`
- `recommendation`: the finite `RecommendationId`

## Interpretation

- `HORIZONTAL + UP` means add replicas.
- `VERTICAL + UP` means raise heap or container memory, depending on the recommendation.
- `VERTICAL + DOWN` means shrink heap or reduce footprint.
- `HOLD` means jlloc does not think scaling is the right move yet.

## Intended consumers

- KEDA Prometheus triggers
- HPA/VPA policy wrappers
- alerting rules
- human operators who want one glance at the scaling intent

## Example query

```promql
max by (service, axis, direction, recommendation) (
  jlloc_service_scaling_decision{axis="HORIZONTAL",recommendation="SCALE_HORIZONTALLY"}
)
```

If this returns `1`, the service is in a horizontal scale-out state.
