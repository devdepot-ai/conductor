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

# Short blurb for the plugin-repo list row: first <p> from plugin.xml, tags stripped.
FEED_DESC=$(python3 - <<'PY'
import re, pathlib, html
xml = pathlib.Path("src/main/resources/META-INF/plugin.xml").read_text()
cdata = re.search(r"<description>\s*<!\[CDATA\[(.+?)\]\]>\s*</description>", xml, re.DOTALL)
body = cdata.group(1) if cdata else ""
p = re.search(r"<p>(.+?)</p>", body, re.DOTALL)
text = p.group(1) if p else body
text = re.sub(r"<[^>]+>", "", text)
text = re.sub(r"\s+", " ", text).strip()
print(html.escape(text))
PY
)

mkdir -p public

cat > public/updatePlugins.xml <<EOF
<plugins>
  <plugin id="io.devdepot.conductor"
          url="https://github.com/devdepot-ai/conductor/releases/download/v${VERSION}/${ZIP_NAME}"
          version="${VERSION}">
    <idea-version since-build="${SINCE}" until-build="${UNTIL}"/>
    <name>Conductor</name>
    <vendor>devdepot</vendor>
    <description>${FEED_DESC}</description>
  </plugin>
</plugins>
EOF

echo "Wrote public/updatePlugins.xml for v${VERSION} -> ${ZIP_NAME}"
