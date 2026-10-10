#!/usr/bin/env bash
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DEST=${1:?pass output classes directory}
mkdir -p "$DEST"
javac -encoding UTF-8 -d "$DEST" \
  "$ROOT/app/src/main/java/com/vision/app/StrictJson.java" \
  "$ROOT/app/src/main/java/com/vision/app/VisionAction.java" \
  "$ROOT/app/src/main/java/com/vision/app/VisionActionParser.java" \
  "$ROOT/app/src/main/java/com/vision/app/ReasoningProposalValidator.java" \
  "$ROOT/app/src/main/java/com/vision/app/VisionRiskPolicy.java" \
  "$ROOT/app/src/main/java/com/vision/app/VisionToolRegistry.java" \
  "$ROOT/tools/provider-pilot/BoundaryProbe.java"
