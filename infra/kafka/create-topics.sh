#!/bin/bash
# Wait for Kafka to be ready
echo "Waiting for Kafka..."
while ! kafka-topics --bootstrap-server kafka:9092 --list > /dev/null 2>&1; do sleep 1; done

kafka-topics --bootstrap-server kafka:9092 --create --if-not-exists --topic workflow.steps.execute --partitions 12 --replication-factor 1
kafka-topics --bootstrap-server kafka:9092 --create --if-not-exists --topic workflow.steps.result --partitions 6 --replication-factor 1
kafka-topics --bootstrap-server kafka:9092 --create --if-not-exists --topic audit.events --partitions 6 --replication-factor 1
kafka-topics --bootstrap-server kafka:9092 --create --if-not-exists --topic domain.events --partitions 6 --replication-factor 1

echo "All topics created."
