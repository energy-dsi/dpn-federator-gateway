ARG BASE_REGISTRY
FROM ${BASE_REGISTRY}/dpn/cp-zookeeper:7.5.3

USER 1001

HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
  CMD bash -c "echo ruok | nc localhost 2181 | grep imok" || exit 