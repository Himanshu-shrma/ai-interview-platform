#!/bin/bash
# Load .env.local and start the Spring Boot backend
set -a
source "$(dirname "$0")/.env.local"
set +a
cd "$(dirname "$0")/backend"
mvn spring-boot:run
