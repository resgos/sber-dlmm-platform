#!/bin/bash
# ============================================================================
# Sber DLMM Platform - Kafka Topic Initialization
# ============================================================================

KAFKA_BROKER="kafka:9092"

echo "Waiting for Kafka to be ready..."
while ! kafka-topics --bootstrap-server "$KAFKA_BROKER" --list >/dev/null 2>&1; do
    echo "Kafka not ready yet, retrying in 5 seconds..."
    sleep 5
done
echo "Kafka is ready."

echo "Creating Kafka topics..."

kafka-topics --create \
    --bootstrap-server "$KAFKA_BROKER" \
    --topic user-events \
    --partitions 3 \
    --replication-factor 1 \
    --if-not-exists

kafka-topics --create \
    --bootstrap-server "$KAFKA_BROKER" \
    --topic token-events \
    --partitions 3 \
    --replication-factor 1 \
    --if-not-exists

kafka-topics --create \
    --bootstrap-server "$KAFKA_BROKER" \
    --topic pool-events \
    --partitions 3 \
    --replication-factor 1 \
    --if-not-exists

kafka-topics --create \
    --bootstrap-server "$KAFKA_BROKER" \
    --topic fee-events \
    --partitions 3 \
    --replication-factor 1 \
    --if-not-exists

echo "All Kafka topics created successfully."
kafka-topics --bootstrap-server "$KAFKA_BROKER" --list || true

# Sprint 9-DS-r4 (known-live LOW) — explicit `exit 0` so the
# container exit code reflects "init done" rather than whatever the
# last `kafka-topics --create --if-not-exists` returned (Confluent
# CLI 7.6 returns 2 on the no-op "topic exists" branch even though
# the operation was successful from our perspective). Without this,
# `docker-compose ps` shows the init container as exit 2 on every
# restart even though everything is fine.
exit 0
