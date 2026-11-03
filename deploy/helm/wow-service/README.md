# wow-service

The Helm chart of one Wow service: its nodes, the Services of its public and internal ports, their network policies, its disruption budget, and optionally an autoscaler and the Prometheus Operator's monitors. It is released with Wow, at Wow's version, as an OCI chart. [Operate › Kubernetes](https://wow.ahoo.me/operate/kubernetes) explains the choices behind it.

## Install

The service's build writes the values it knows, from its contract (`./gradlew :<service>:generateWowHelmValues`): the service's name, the peers whose contracts are on its class path and its Manifest's route, in `build/wow/helm/values.yaml`. Your own values file adds the deployment's: the image, the replicas, the resources, the profiles and the stores. Pass it after the generated one, so it overrides it:

```bash
helm install ordering oci://ghcr.io/ahoo-wang/wow-service --version <wow-version> \
  -f ordering-server/build/wow/helm/values.yaml \
  -f ordering-values.yaml
```

`values.schema.json` holds the values to the keys of `values.yaml`: a misspelt key fails the install, as Wow refuses a `wow.*` key it does not know.

## What it renders

Every object is named after `service.name`, the service's `spring.application.name`, never after the release:

| Object | Name | Holds |
| --- | --- | --- |
| Deployment | `<name>` | The nodes: the probes, the preStop pause, `maxUnavailable: 0`, a grace period of `shutdown.*`, the node id and address from the pod, the namespace from the downward API, the spread over zones and hosts |
| ConfigMap | `<name>-config` | `config` as environment variables, `settings` as `SPRING_APPLICATION_JSON`, the JVM's options and the shutdown's bounds |
| Service | `<name>` | The public HTTP port |
| Service | `<name>-cluster` | The internal port, headless, every node ready or not: the SRV records `_internal._tcp.<name>-cluster.<namespace>.svc` the peers find the nodes by |
| NetworkPolicy | `<name>-public`, `<name>-internal` | The gateway's pods to the public port; the service's own nodes, its platform (`partOf`), its peers in their namespaces and `networkPolicy.internal.from` to the internal port |
| PodDisruptionBudget | `<name>` | One node at a time by default |
| ServiceAccount | `<name>` | No token mounted |
| HorizontalPodAutoscaler | `<name>` | With `autoscaling.hpa.enabled`: CPU, a slow scale-down |
| ScaledObject | `<name>` | With `autoscaling.keda.enabled` and KEDA installed: the subscriptions' backlog through Prometheus |
| ServiceMonitor, PodMonitor | `<name>` | With `metrics.serviceMonitor.enabled` or `metrics.podMonitor.enabled` and the Prometheus Operator installed |

A peer in another namespace takes `namespace` in `service.peers`: the node looks it up there (`wow.peer.dns.namespaces`) and the internal policy admits its pods from there.

## In an umbrella chart

Add it once per service, under an alias, and nest each service's values under its alias:

```yaml
dependencies:
  - name: wow-service
    version: <wow-version>
    repository: oci://ghcr.io/ahoo-wang
    alias: ordering
  - name: wow-service
    version: <wow-version>
    repository: oci://ghcr.io/ahoo-wang
    alias: basket
```

The schema accepts the umbrella's `global` values, which the chart does not read.

## With Kustomize

Render the chart and lay your patches over the output, instead of keeping a second set of manifests:

```bash
helm template eshop-ordering oci://ghcr.io/ahoo-wang/wow-service --version <wow-version> \
  --namespace shop -f generated-values.yaml -f values.yaml > base/eshop-ordering.yaml
```

Then list `eshop-ordering.yaml` among the `resources` of `base/kustomization.yaml`. Render it again on each Wow upgrade.

## Tests

`tests/` holds the chart's unit tests ([helm-unittest](https://github.com/helm-unittest/helm-unittest)) and `ci/` the value sets CI lints, renders and validates; neither is packaged. `ci.yml` runs them, and installs the chart on kind.
