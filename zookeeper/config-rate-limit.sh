#!/bin/bash
set -e

CONFIG_FILE="config/rate-limit.yaml"
JOB_TEMPLATE="zookeeper-config-job.yaml"

# Check files exist
if [ ! -f "$CONFIG_FILE" ]; then
    echo "ERROR: $CONFIG_FILE not found"
    exit 1
fi

if [ ! -f "$JOB_TEMPLATE" ]; then
    echo "ERROR: $JOB_TEMPLATE not found"
    exit 1
fi

# Read configuration
CAPACITY=$(grep '^capacity:' "$CONFIG_FILE" | awk '{print $2}')
REFILL_RATE=$(grep '^refillRate:' "$CONFIG_FILE" | awk '{print $2}')

# Validate capacity
if ! [[ "$CAPACITY" =~ ^[1-9][0-9]*$ ]]; then
    echo "ERROR: capacity must be a positive integer"
    exit 1
fi

# Validate refillRate
if ! [[ "$REFILL_RATE" =~ ^([0-9]+([.][0-9]+)?|[.][0-9]+)$ ]]; then
    echo "ERROR: refillRate must be a positive number"
    exit 1
fi

# Make sure refillRate > 0
if ! awk "BEGIN {exit !($REFILL_RATE > 0)}"; then
    echo "ERROR: refillRate must be greater than 0"
    exit 1
fi

# Generate unique Job ID
JOB_ID=$(date +%s)

# Generate temporary Job YAML
TEMP_JOB="/tmp/zookeeper-config-job-${JOB_ID}.yaml"

sed \
    -e "s/__CAPACITY__/$CAPACITY/g" \
    -e "s/__REFILL_RATE__/$REFILL_RATE/g" \
    -e "s/__JOB_ID__/$JOB_ID/g" \
    "$JOB_TEMPLATE" > "$TEMP_JOB"

echo "Configuration:"
echo "  capacity=$CAPACITY"
echo "  refillRate=$REFILL_RATE"
echo
echo "Creating ZooKeeper configuration Job..."

kubectl apply -f "$TEMP_JOB"

rm -f "$TEMP_JOB"

echo
echo "Job created: zookeeper-config-$JOB_ID"
echo
echo "Watch it with:"
echo "kubectl get jobs"
echo "kubectl logs job/zookeeper-config-$JOB_ID"