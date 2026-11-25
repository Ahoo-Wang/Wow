{{/*
The service's name, its spring.application.name: every object of the chart is named after it, as the peers' default
SRV name (_internal._tcp.<name>-cluster.<namespace>.svc) and the service's contract expect.
*/}}
{{- define "wow-service.name" -}}
{{- required "service.name is required: the service's spring.application.name, as the values the Wow Gradle plugin generates (generateWowHelmValues) give it" .Values.service.name -}}
{{- end -}}

{{/* The labels that select the service's pods: its name alone, as the peers and the tools find them. */}}
{{- define "wow-service.selectorLabels" -}}
app.kubernetes.io/name: {{ include "wow-service.name" . }}
{{- end -}}

{{/* The labels of every object. */}}
{{- define "wow-service.labels" -}}
{{ include "wow-service.selectorLabels" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ include "wow-service.tag" . | trunc 63 | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 }}
{{- with .Values.partOf }}
app.kubernetes.io/part-of: {{ . }}
{{- end }}
{{- end -}}

{{/* The image's tag: its own, else the chart's appVersion, the Wow version the chart was released with. */}}
{{- define "wow-service.tag" -}}
{{- .Values.image.tag | default .Chart.AppVersion -}}
{{- end -}}

{{- define "wow-service.image" -}}
{{- $repository := required "image.repository is required: the service's image" .Values.image.repository -}}
{{- if .Values.image.digest -}}
{{- printf "%s@%s" $repository .Values.image.digest -}}
{{- else -}}
{{- printf "%s:%s" $repository (include "wow-service.tag" .) -}}
{{- end -}}
{{- end -}}

{{- define "wow-service.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- .Values.serviceAccount.name | default (include "wow-service.name" .) -}}
{{- else -}}
{{- .Values.serviceAccount.name | default "default" -}}
{{- end -}}
{{- end -}}

{{/*
The node's shutdown, in seconds, its steps one after the other: the preStop pause; the public server's graceful
shutdown, which lasts as long as its longest request, a wait (wow.wait.max-timeout); the node's stop, up to its stop
bound; then a margin. The stop bound is the formula of ServiceNodeOptions.stopBound (core/runtime), which the host's
shutdown phase is set from too: max(wait.max-timeout, 2 x subscription.stop-timeout + cluster.drain-timeout)
+ 3 x cluster.drain-timeout, its waits measured from the node's own stop. Change it there first.
*/}}
{{- define "wow-service.terminationGracePeriodSeconds" -}}
{{- $s := .Values.shutdown -}}
{{- $handOff := add (mul 2 $s.subscriptionStopTimeoutSeconds) $s.drainTimeoutSeconds -}}
{{- $stopBound := add (max $s.waitMaxTimeoutSeconds $handOff) (mul 3 $s.drainTimeoutSeconds) -}}
{{- add $s.preStopSeconds $s.waitMaxTimeoutSeconds $stopBound $s.marginSeconds -}}
{{- end -}}

{{/* The name of the port the metrics are scraped on: the HTTP port's when they share it (Actuator), else its own. */}}
{{- define "wow-service.metricsPort" -}}
{{- if eq (int .Values.metrics.port) (int .Values.ports.http) -}}http{{- else -}}metrics{{- end -}}
{{- end -}}

{{- define "wow-service.metricsEnabled" -}}
{{- if or .Values.metrics.serviceMonitor.enabled .Values.metrics.podMonitor.enabled -}}true{{- end -}}
{{- end -}}

{{/*
The service's peers, as a JSON list: those the build generated (service.contractPeers, the peers its code calls), then
the deployment's (service.peers), one entry a service, the deployment's taking the place of a generated one of the same
service (its namespace, say), in the generated order, the deployment's own after them (#6933). Helm replaces a list
whole, so the two lists are two keys: a values file that gives one peer its namespace keeps the generated others.
*/}}
{{- define "wow-service.peers" -}}
{{- $peers := list -}}
{{- $own := dict -}}
{{- range .Values.service.peers -}}
{{- $_ := set $own (.service | default .context | default "") . -}}
{{- end -}}
{{- $listed := dict -}}
{{- range .Values.service.contractPeers -}}
{{- $key := .service | default .context | default "" -}}
{{- $peers = append $peers (get $own $key | default .) -}}
{{- $_ := set $listed $key true -}}
{{- end -}}
{{- range .Values.service.peers -}}
{{- if not (hasKey $listed (.service | default .context | default "")) -}}
{{- $peers = append $peers . -}}
{{- end -}}
{{- end -}}
{{- toJson $peers -}}
{{- end -}}

{{/*
The Spring settings the chart gives as SPRING_APPLICATION_JSON: the values' settings, with each peer placed in another
namespace (a peer's namespace, wow-service.peers) as wow.peer.dns.namespaces.<service>.
*/}}
{{- define "wow-service.settings" -}}
{{- $settings := deepCopy (.Values.settings | default dict) -}}
{{- $namespaces := dict -}}
{{- range include "wow-service.peers" . | fromJsonArray -}}
{{- if and .namespace .service (ne .namespace $.Release.Namespace) -}}
{{- $_ := set $namespaces .service .namespace -}}
{{- end -}}
{{- end -}}
{{- if $namespaces -}}
{{- $settings = mergeOverwrite (dict "wow" (dict "peer" (dict "dns" (dict "namespaces" $namespaces)))) $settings -}}
{{- end -}}
{{- if $settings -}}{{- toJson $settings -}}{{- end -}}
{{- end -}}

{{/*
The keys the chart sets itself, each as Spring's relaxed binding reads it (its letters and digits, upper case), with why:
config, settings and env refuse them (#6934).
*/}}
{{- define "wow-service.ownedKeys" -}}
SERVERPORT: ", from ports.http, which the probes and the Services follow: remove it"
WOWINTERNALPORT: ", from ports.internal, which the peers' SRV records and the policy follow: remove it"
WOWINTERNALHOST: ", the pod's address: remove it"
WOWNODEID: ", the pod's name: remove it"
WOWPEERDNSNAMESPACE: ", the pod's namespace: give a peer's under service.peers instead"
WOWWAITMAXTIMEOUT: ": set shutdown.waitMaxTimeoutSeconds instead, which the grace period counts"
WOWCLUSTERDRAINTIMEOUT: ": set shutdown.drainTimeoutSeconds instead, which the grace period counts"
WOWSUBSCRIPTIONSTOPTIMEOUT: ": set shutdown.subscriptionStopTimeoutSeconds instead, which the grace period counts"
{{- end -}}

{{/* The paths of the leaves of a settings tree, .map under .prefix, one a line. */}}
{{- define "wow-service.settingPaths" -}}
{{- $prefix := .prefix -}}
{{- range $key, $value := .map -}}
{{- $path := ternary (toString $key) (printf "%s.%s" $prefix $key) (eq $prefix "") -}}
{{- if kindIs "map" $value -}}
{{- include "wow-service.settingPaths" (dict "prefix" $path "map" $value) -}}
{{- else -}}
{{- printf "%s\n" $path -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/* Refuses values that cannot work together; each template that renders them includes it, so one rendered alone (helm template -s) refuses them too. */}}
{{- define "wow-service.validate" -}}
{{- if and .Values.autoscaling.hpa.enabled .Values.autoscaling.keda.enabled -}}
{{- fail "autoscaling.hpa.enabled and autoscaling.keda.enabled are both true: KEDA creates the HPA of its ScaledObject itself, so enable one of them" -}}
{{- end -}}
{{- range $key := list "SERVER_PORT" "WOW_INTERNAL_PORT" "WOW_INTERNAL_HOST" "WOW_NODE_ID" "WOW_PEER_DNS_NAMESPACE" -}}
{{- if hasKey $.Values.config $key -}}
{{- fail (printf "config.%s is set by the chart itself, from ports and the pod: remove it" $key) -}}
{{- end -}}
{{- end -}}
{{- if hasKey .Values.config "WOW_WAIT_MAXTIMEOUT" -}}
{{- fail "config.WOW_WAIT_MAXTIMEOUT: set shutdown.waitMaxTimeoutSeconds instead, which the grace period counts" -}}
{{- end -}}
{{- if hasKey .Values.config "WOW_CLUSTER_DRAINTIMEOUT" -}}
{{- fail "config.WOW_CLUSTER_DRAINTIMEOUT: set shutdown.drainTimeoutSeconds instead, which the grace period counts" -}}
{{- end -}}
{{- if hasKey .Values.config "WOW_SUBSCRIPTION_STOPTIMEOUT" -}}
{{- fail "config.WOW_SUBSCRIPTION_STOPTIMEOUT: set shutdown.subscriptionStopTimeoutSeconds instead, which the grace period counts" -}}
{{- end -}}
{{- if hasKey .Values.config "SPRING_APPLICATION_JSON" -}}
{{- fail "config.SPRING_APPLICATION_JSON: put those keys under settings, which the chart renders as SPRING_APPLICATION_JSON" -}}
{{- end -}}
{{- /*
The same keys through settings, which SPRING_APPLICATION_JSON makes override every variable, or through env, which
overrides the ConfigMap (#6934): each key read as Spring's relaxed binding reads it, its letters and digits alone.
*/ -}}
{{- $owned := include "wow-service.ownedKeys" . | fromYaml -}}
{{- range $path := include "wow-service.settingPaths" (dict "prefix" "" "map" (.Values.settings | default dict)) | splitList "\n" | compact -}}
{{- with get $owned (regexReplaceAll "[^A-Za-z0-9]" $path "" | upper) -}}
{{- fail (printf "settings.%s is set by the chart itself%s" $path .) -}}
{{- end -}}
{{- end -}}
{{- $envOnly := dict "PODNAME" ", the pod's name, which WOW_NODE_ID is: remove it" "PODIP" ", the pod's address, which WOW_INTERNAL_HOST is: remove it" "SPRINGAPPLICATIONJSON" ": put those keys under settings, which the chart renders as SPRING_APPLICATION_JSON" -}}
{{- range .Values.env -}}
{{- $name := .name | default "" -}}
{{- $key := regexReplaceAll "[^A-Za-z0-9]" $name "" | upper -}}
{{- with get $owned $key | default (get $envOnly $key) -}}
{{- fail (printf "env %s is set by the chart itself%s" $name .) -}}
{{- end -}}
{{- end -}}
{{- /*
The chart's labels are the chart's: a map of labels the values lay over them (the pods', the monitors') sets none of
them. The pods are selected by app.kubernetes.io/name, and the platform's internal policies admit them by
app.kubernetes.io/part-of: a pod label of the same key would leave the Deployment's selector without its pods, which
the API server refuses, and the Services, the disruption budget and the policies without theirs; any other would be a
second key of the same name, which a strict YAML reader refuses.
*/ -}}
{{- $own := include "wow-service.labels" . | fromYaml -}}
{{- $_ := set $own "app.kubernetes.io/part-of" "" -}}
{{- $why := dict
  "app.kubernetes.io/name" ", from service.name, and selects the service's pods by it (the Deployment, the Services, the PodDisruptionBudget, the NetworkPolicies, the monitors, the topology spread): remove it"
  "app.kubernetes.io/part-of" ", from partOf, and the platform's internal NetworkPolicies admit its pods by it: set partOf instead"
-}}
{{- range $values, $labels := dict "podLabels" .Values.podLabels "metrics.serviceMonitor.labels" .Values.metrics.serviceMonitor.labels "metrics.podMonitor.labels" .Values.metrics.podMonitor.labels -}}
{{- range $key, $value := $labels -}}
{{- if hasKey $own $key -}}
{{- fail (printf "%s.%s is a label the chart sets itself%s" $values $key (get $why $key | default ": remove it")) -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- if and .Values.networkPolicy.enabled (not .Values.networkPolicy.public.from) -}}
{{- fail "networkPolicy.public.from is empty, which would admit every pod to the public port: name the gateway's pods, or disable networkPolicy" -}}
{{- end -}}
{{- /*
The gateway-only statement (#6935) holds only while every entry of from names pods by their labels: one with an empty
or no podSelector admits every pod of its namespaces, a namespaceSelector alone those of every namespace it selects, and
an ipBlock whatever has an address in it; each of them can then claim any identity.
*/ -}}
{{- if and .Values.networkPolicy.enabled .Values.networkPolicy.public.onlyGateway (not (hasKey .Values.config "COSEC_PIPELINE_IDENTITY_ONLYGATEWAYCANREACH")) -}}
{{- range $index, $from := .Values.networkPolicy.public.from -}}
{{- $pods := $from.podSelector | default dict -}}
{{- if not (or $pods.matchLabels $pods.matchExpressions) -}}
{{- fail (printf "networkPolicy.public.from[%d] names no pods by their labels, so it admits every pod it reaches: the statement that only the gateway reaches the public port (networkPolicy.public.onlyGateway) would not hold, and each such pod could claim any identity; name the gateway's pods with a podSelector, or set onlyGateway: false" $index) -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- if and (or .Values.metrics.serviceMonitor.enabled .Values.metrics.podMonitor.enabled) (eq (int .Values.metrics.port) (int .Values.ports.internal)) -}}
{{- fail "metrics.port is the internal port: the metrics are scraped on the HTTP port or a port of their own, never the internal one" -}}
{{- end -}}
{{- end -}}
