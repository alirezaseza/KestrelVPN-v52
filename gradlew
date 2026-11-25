#!/usr/bin/env sh
# Bootstrap wrapper: downloads the Gradle version named in gradle-wrapper.properties on first use.
# Integrity: when `distributionSha256Sum` is set in that file, the download is verified BEFORE it is
# unpacked. Set KESTREL_REQUIRE_SHA=1 (CI does) to make a missing checksum a hard error.
set -eu
DIR="$(cd "$(dirname "$0")" && pwd)"
PROPS="$DIR/gradle/wrapper/gradle-wrapper.properties"
URL="$(sed -n 's/^distributionUrl=//p' "$PROPS" | sed 's#\\##g')"
SHA="$(sed -n 's/^distributionSha256Sum=//p' "$PROPS" | tr -d '[:space:]')"
case "$URL" in
  https://*) ;;
  *) echo "gradlew: distributionUrl must be https" >&2; exit 1 ;;
esac
NAME="$(basename "$URL" -bin.zip)"
HOME_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}/kestrel-dist"

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then sha256sum "$1" | cut -d' ' -f1
  else shasum -a 256 "$1" | cut -d' ' -f1; fi
}

if [ ! -x "$HOME_DIR/$NAME/bin/gradle" ]; then
  mkdir -p "$HOME_DIR"
  TMP="$(mktemp)"
  trap 'rm -f "$TMP"' EXIT
  if command -v curl >/dev/null 2>&1; then curl -fsSL "$URL" -o "$TMP"; else wget -q "$URL" -O "$TMP"; fi
  if [ -n "$SHA" ]; then
    ACTUAL="$(sha256_of "$TMP")"
    if [ "$ACTUAL" != "$SHA" ]; then
      echo "gradlew: checksum mismatch for $URL (expected $SHA, got $ACTUAL)" >&2
      exit 1
    fi
  elif [ "${KESTREL_REQUIRE_SHA:-0}" = "1" ]; then
    echo "gradlew: distributionSha256Sum is missing in gradle-wrapper.properties" >&2
    exit 1
  else
    echo "gradlew: WARNING - Gradle download is not checksum-verified (add distributionSha256Sum)" >&2
  fi
  unzip -q "$TMP" -d "$HOME_DIR"
fi
exec "$HOME_DIR/$NAME/bin/gradle" -p "$DIR" "$@"
