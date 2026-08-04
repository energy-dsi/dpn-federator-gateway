ARG BASE_REGISTRY
FROM ${BASE_REGISTRY}/dpn/redis:7.2

USER redis

HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
  CMD redis-cli ping || exit 1
