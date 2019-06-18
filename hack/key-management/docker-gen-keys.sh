#!/usr/bin/env bash
set -eo pipefail

# Generates all necessary keys for a node using Docker image
# Usage:
# ./docker-gen-keys.sh <directory where to put keys> [--test]

if [[ "$1" == /* ]] || [[ "$1" == ~* ]]; then
    OUTPUT_DIR="$1"
else
    OUTPUT_DIR="$PWD/$1"
fi

if [[ ! -d "$OUTPUT_DIR" ]]; then
    echo "ERROR: output dir doesn't exist"
    echo "usage: ./docker-gen-keys.sh <dir>"
    exit "1"
fi

shift

docker pull casperlabs/key-generator:latest || true
docker run --rm -it -v "$OUTPUT_DIR":/keys casperlabs/key-generator /keys

if [[ "$1" == "--test" ]]; then
    if [[ -f "$OUTPUT_DIR/node-id" ]] && \
       [[ -f "$OUTPUT_DIR/validator-id" ]] && \
       [[ -f "$OUTPUT_DIR/node.key.pem" ]] && \
       [[ -f "$OUTPUT_DIR/node.certificate.pem" ]] && \
       [[ -f "$OUTPUT_DIR/validator-private.pem" ]] && \
       [[ -f "$OUTPUT_DIR/validator-public.pem" ]]; then
        exit "0"
    else
        echo "ERROR: Some of required files haven't created"
        exit "1"
    fi
fi