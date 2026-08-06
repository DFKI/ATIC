#!/usr/bin/env bash

set -euo pipefail

# Require version argument
if [[ $# -ne 1 ]]; then
    echo "Usage: $0 <version>"
    exit 1
fi

VERSION="$1"

PROJECTS=(
    "atic-api"
    "atic-sqlite"
    "atic-server"
    "atic-sqlite-jmh"
    "atic-sqlite-tdb2tests"
)

for project in "${PROJECTS[@]}"; do
    echo "Project: $project"
    echo "Version: $VERSION"
    mvn -f "../$project/pom.xml" versions:set -DnewVersion="$VERSION"
    echo
done
