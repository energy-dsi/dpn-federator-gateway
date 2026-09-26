{{- define "dpn.labels" -}}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/managed-by: Helm
{{- end }}

{{- define "dpn.redis.fullname" -}}
{{ .Values.redis.name }}
{{- end }}
