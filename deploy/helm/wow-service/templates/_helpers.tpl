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
The Spring settings the chart gives as SPRING_APPLICATION_JSON: the values' settings, with each peer placed in another
namespace (service.peers[].namespace) as wow.peer.dns.namespaces.<service>.
*/}}
{{- define "wow-service.settings" -}}
{{- $settings := deepCopy (.Values.settings | default dict) -}}
{{- $namespaces := dict -}}
{{- range .Values.service.peers -}}
{{- if and .namespace .service (ne .namespace $.Release.Namespace) -}}
{{- $_ := set $namespaces .service .namespace -}}
{{- end -}}
{{- end -}}
{{- if $namespaces -}}
{{- $settings = mergeOverwrite (dict "wow" (dict "peer" (dict "dns" (dict "namespaces" $namespaces)))) $settings -}}
{{- end -}}
{{- if $settings -}}{{- toJson $settings -}}{{- end -}}
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
{{- if and .Values.networkPolicy.enabled (not .Values.networkPolicy.public.from) -}}
{{- fail "networkPolicy.public.from is empty, which would admit every pod to the public port: name the gateway's pods, or disable networkPolicy" -}}
{{- end -}}
{{- if and (or .Values.metrics.serviceMonitor.enabled .Values.metrics.podMonitor.enabled) (eq (int .Values.metrics.port) (int .Values.ports.internal)) -}}
{{- fail "metrics.port is the internal port: the metrics are scraped on the HTTP port or a port of their own, never the internal one" -}}
{{- end -}}
{{- end -}}
