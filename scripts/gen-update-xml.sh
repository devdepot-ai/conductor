#!/usr/bin/env sh
# Generate public/updatePlugins.xml for the self-hosted plugin repository.
# Usage: scripts/gen-update-xml.sh <version> [<zip-filename>]

set -eu

VERSION="${1:?version required}"
ZIP_NAME="${2:-conductor-${VERSION}.zip}"

prop() {
  grep "^$1[[:space:]]*=" gradle.properties | head -n1 | cut -d= -f2- | sed 's/^[[:space:]]*//; s/[[:space:]]*$//'
}

SINCE=$(prop pluginSinceBuild)
UNTIL=$(prop pluginUntilBuild)

mkdir -p public

cat > public/updatePlugins.xml <<EOF
<plugins>
  <plugin id="io.devdepot.conductor"
          url="https://github.com/devdepot-ai/conductor/releases/download/v${VERSION}/${ZIP_NAME}"
          version="${VERSION}">
    <idea-version since-build="${SINCE}" until-build="${UNTIL}"/>
    <name>Conductor</name>
    <vendor>devdepot</vendor>
    <description>AI Workspaces — isolated agent sessions backed by git worktrees.</description>
  </plugin>
</plugins>
EOF

echo "Wrote public/updatePlugins.xml for v${VERSION} -> ${ZIP_NAME}"
