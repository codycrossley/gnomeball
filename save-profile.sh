#!/bin/bash
set -euo pipefail

if [ $# -ne 1 ]; then
    echo "Usage: ./save-profile.sh <profile-name>"
    echo "Example: ./save-profile.sh red"
    echo ""
    echo "Log into the account using ./gradlew run first, then run this script"
    echo "to save the credentials into a reusable profile."
    exit 1
fi

PROFILE="$1"
SOURCE="$HOME/.runelite"
DEST="$HOME/.runelite-${PROFILE}"

if [ ! -f "$SOURCE/credentials.properties" ]; then
    echo "Error: No credentials found at $SOURCE/credentials.properties"
    echo "Launch RuneLite with ./gradlew run and log in first."
    exit 1
fi

DISPLAY_NAME=$(grep JX_DISPLAY_NAME "$SOURCE/credentials.properties" | cut -d= -f2)
echo "Saving profile '$PROFILE' for account: $DISPLAY_NAME"

mkdir -p "$DEST/.runelite"

cp "$SOURCE/credentials.properties" "$DEST/.runelite/credentials.properties"
cp "$SOURCE/random.dat" "$DEST/.runelite/random.dat" 2>/dev/null || true
cp "$SOURCE/jagex_cl_oldschool_LIVE.dat" "$DEST/.runelite/jagex_cl_oldschool_LIVE.dat" 2>/dev/null || true

echo "Profile saved to $DEST"
echo "Launch with: ./gradlew run$(echo "$PROFILE" | sed 's/.*/\u&/')"
