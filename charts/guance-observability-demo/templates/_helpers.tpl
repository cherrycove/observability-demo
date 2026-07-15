{{- define "guance-observability-demo.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "guance-observability-demo.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name (include "guance-observability-demo.name" .) | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}

{{- define "guance-observability-demo.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
app.kubernetes.io/name: {{ include "guance-observability-demo.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
project: {{ required "observability.project is required" .Values.observability.project | quote }}
{{- end }}

{{- define "guance-observability-demo.selectorLabels" -}}
app.kubernetes.io/name: {{ include "guance-observability-demo.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{- define "guance-observability-demo.secretName" -}}
{{- if .Values.existingSecret -}}
{{- .Values.existingSecret -}}
{{- else -}}
{{- include "guance-observability-demo.fullname" . -}}
{{- end -}}
{{- end }}

{{- define "guance-observability-demo.image" -}}
{{- printf "%s/%s/guance-observability-demo-%s:%s" $.root.Values.image.registry $.root.Values.image.owner $.service $.root.Values.image.tag -}}
{{- end }}
