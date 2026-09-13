#!/usr/bin/env bash
# =============================================================================
# Builds the CodeArena sandbox images.
#
# These are NOT docker-compose services: nothing long-running is started from them.
# They are the images the execution service creates throwaway containers from, and
# they must exist on the daemon before any submission can be judged, because sandbox
# containers are created with --pull never and --network none.
#
#   ./sandbox/build-images.sh
#
# Run it after changing a Dockerfile here, and after a fresh clone. The execution
# service checks at startup that every image is present and refuses to report itself
# healthy otherwise, so a missing image is visible immediately rather than as a run of
# failed submissions.
# =============================================================================
set -euo pipefail

cd "$(dirname "$0")/.."
TAG="${CODEARENA_SANDBOX_TAG:-1}"

for language in cpp java python; do
    image="codearena/sandbox-${language}:${TAG}"
    echo "==> building ${image}"
    # No build args and no secrets: these images must be reproducible from the
    # Dockerfile and the pinned base digest alone.
    docker build --pull=false -f "sandbox/${language}.Dockerfile" -t "${image}" sandbox/
done

echo
echo "Built:"
docker images --filter "reference=codearena/sandbox-*" \
    --format '  {{.Repository}}:{{.Tag}}  {{.Size}}'
