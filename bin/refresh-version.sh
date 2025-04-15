#!/usr/bin/env bash
VERSION=$(grep -oP 'version\s*:=\s*"\K[^"]+' version.sbt)

yq e '.images[] |= select(.name == "ghcr.io/ogrodje/goo") .newTag = "'$VERSION'"' \
  -i k8s/base/kustomization.yaml

echo "Set app version to $VERSION in k8s/base/kustomization.yaml".