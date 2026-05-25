{{/*
Common Helm template helpers for the dlmm-platform chart.
Used by every Deployment / Service / ConfigMap / Secret / Ingress
template to keep names, labels, and image references consistent.
*/}}

{{/*
Expand the name of the chart. Truncated and trimmed for DNS-1123.
*/}}
{{- define "dlmm.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Fullname: release-name + chart-name, truncated and DNS-cleaned.
Used as a prefix for all created resources so multiple releases
of this chart can co-exist in one namespace.
*/}}
{{- define "dlmm.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/*
Chart name + version label.
*/}}
{{- define "dlmm.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Common labels — applied to every resource. Includes the recommended
Kubernetes labels (app.kubernetes.io/*) plus chart provenance.
*/}}
{{- define "dlmm.labels" -}}
helm.sh/chart: {{ include "dlmm.chart" . }}
{{ include "dlmm.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: dlmm-platform
{{- end -}}

{{/*
Selector labels — must be stable across upgrades (cannot include
chart/app version since those mutate per release).
*/}}
{{- define "dlmm.selectorLabels" -}}
app.kubernetes.io/name: {{ include "dlmm.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/*
Per-service labels — extends common labels with a component name so
each Deployment/Service has its own selector. Used as:
  {{- include "dlmm.serviceLabels" (dict "root" . "name" "gateway") | nindent 4 }}
*/}}
{{- define "dlmm.serviceLabels" -}}
{{ include "dlmm.labels" .root }}
app.kubernetes.io/component: {{ .name }}
{{- end -}}

{{/*
Per-service selector labels — narrow enough to bind a Service to its
Deployment's pods. Used as:
  {{- include "dlmm.serviceSelectorLabels" (dict "root" . "name" "gateway") | nindent 6 }}
*/}}
{{- define "dlmm.serviceSelectorLabels" -}}
{{ include "dlmm.selectorLabels" .root }}
app.kubernetes.io/component: {{ .name }}
{{- end -}}

{{/*
ServiceAccount name to use. If `serviceAccount.create` is true, default
to fullname; otherwise use the explicitly named one (or "default").
*/}}
{{- define "dlmm.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "dlmm.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{/*
Compute full image reference for a service.
Usage:
  {{ include "dlmm.image" (dict "root" . "svc" .Values.services.gateway) }}

Falls back to global.imageTag when svc.image.tag is unset.
Prepends global.imageRegistry when set.
*/}}
{{- define "dlmm.image" -}}
{{- $registry := .root.Values.global.imageRegistry -}}
{{- $repository := .svc.image.repository -}}
{{- $tag := default .root.Values.global.imageTag .svc.image.tag -}}
{{- if $registry -}}
{{- printf "%s/%s:%s" $registry $repository $tag -}}
{{- else -}}
{{- printf "%s:%s" $repository $tag -}}
{{- end -}}
{{- end -}}

{{/*
Per-service resource name. Conventional: <fullname>-<dlmm-service-name>.
Centralised so deployment + service + ingress agree on the same DNS
name (e.g. dlmm-gateway, dlmm-pool-engine — matches docker-compose).
*/}}
{{- define "dlmm.serviceName" -}}
{{- printf "dlmm-%s" .name -}}
{{- end -}}

{{/*
Reusable Deployment manifest. Every per-service template just delegates
to this with its (svc, name, kind) tuple. `kind` is "spring" for Java
services (envFrom ConfigMap+Secret, /actuator/health probes) or "nginx"
for SPA UIs (no envFrom, / probes, faster initialDelay).

Usage:
  {{- include "dlmm.deployment" (dict "root" . "name" "gateway" "svc" .Values.services.gateway "kind" "spring") }}
*/}}
{{- define "dlmm.deployment" -}}
{{- $root := .root -}}
{{- $svc := .svc -}}
{{- $name := .name -}}
{{- $isSpring := eq .kind "spring" -}}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "dlmm.serviceName" (dict "name" $name) }}
  labels:
    {{- include "dlmm.serviceLabels" (dict "root" $root "name" $name) | nindent 4 }}
spec:
  replicas: {{ $svc.replicas }}
  selector:
    matchLabels:
      {{- include "dlmm.serviceSelectorLabels" (dict "root" $root "name" $name) | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "dlmm.serviceSelectorLabels" (dict "root" $root "name" $name) | nindent 8 }}
      {{- if $isSpring }}
      annotations:
        # Pod restart on ConfigMap or Secret change — checksum forces rollout.
        checksum/config: {{ include (print $root.Template.BasePath "/configmap.yaml") $root | sha256sum }}
        checksum/secret: {{ include (print $root.Template.BasePath "/secrets.yaml") $root | sha256sum }}
        prometheus.io/scrape: "true"
        prometheus.io/path: /actuator/prometheus
        prometheus.io/port: {{ $svc.port | quote }}
      {{- end }}
    spec:
      serviceAccountName: {{ include "dlmm.serviceAccountName" $root }}
      {{- with $root.Values.global.imagePullSecrets }}
      imagePullSecrets:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      containers:
        - name: {{ $name }}
          image: {{ include "dlmm.image" (dict "root" $root "svc" $svc) }}
          imagePullPolicy: {{ $root.Values.global.imagePullPolicy }}
          ports:
            - name: http
              containerPort: {{ $svc.port }}
              protocol: TCP
          {{- if $isSpring }}
          envFrom:
            - configMapRef:
                name: {{ include "dlmm.fullname" $root }}-config
            - secretRef:
                name: {{ include "dlmm.fullname" $root }}-secrets
          {{- end }}
          {{- with $svc.env }}
          env:
            {{- range $k, $v := . }}
            - name: {{ $k }}
              value: {{ $v | quote }}
            {{- end }}
          {{- end }}
          livenessProbe:
            httpGet:
              path: {{ $svc.path }}
              port: http
            {{- if $isSpring }}
            initialDelaySeconds: 60
            periodSeconds: 15
            timeoutSeconds: 5
            failureThreshold: 6
            {{- else }}
            initialDelaySeconds: 5
            periodSeconds: 10
            timeoutSeconds: 3
            failureThreshold: 3
            {{- end }}
          readinessProbe:
            httpGet:
              path: {{ $svc.path }}
              port: http
            {{- if $isSpring }}
            initialDelaySeconds: 20
            periodSeconds: 10
            timeoutSeconds: 3
            failureThreshold: 3
            {{- else }}
            initialDelaySeconds: 3
            periodSeconds: 5
            timeoutSeconds: 2
            failureThreshold: 3
            {{- end }}
          resources:
            {{- toYaml $svc.resources | nindent 12 }}
{{- end -}}

{{/*
Reusable Service manifest. Identical across every component.

Usage:
  {{- include "dlmm.service" (dict "root" . "name" "gateway" "svc" .Values.services.gateway) }}
*/}}
{{- define "dlmm.service" -}}
{{- $root := .root -}}
{{- $svc := .svc -}}
{{- $name := .name -}}
apiVersion: v1
kind: Service
metadata:
  name: {{ include "dlmm.serviceName" (dict "name" $name) }}
  labels:
    {{- include "dlmm.serviceLabels" (dict "root" $root "name" $name) | nindent 4 }}
spec:
  type: ClusterIP
  ports:
    - name: http
      port: {{ $svc.port }}
      targetPort: http
      protocol: TCP
  selector:
    {{- include "dlmm.serviceSelectorLabels" (dict "root" $root "name" $name) | nindent 4 }}
{{- end -}}
