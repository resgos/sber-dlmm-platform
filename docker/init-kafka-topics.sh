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
kafka-topics --bootstrap-server "$KAFKA_BROKER" --list
