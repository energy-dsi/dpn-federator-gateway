ARG BASE_REGISTRY
FROM ${BASE_REGISTRY}/dpn/cp-kafka:7.5.3

USER 1001

HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
  CMD nc -z localhost 9092 || exit 1