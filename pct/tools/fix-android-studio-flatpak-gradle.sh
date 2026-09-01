#!/usr/bin/env bash
# Fix: ijMapper1.gradle does not exist (Android Studio Flatpak + Gradle host daemon)
#
# Ejecutar UNA VEZ en terminal, luego cerrar y reabrir Android Studio.
set -euo pipefail

APP_ID="com.google.AndroidStudio"
CACHE="${HOME}/.cache/pct-android-studio-tmp"

echo "==> 1/5 Flatpak: acceso al /tmp real del host"
flatpak override --user "$APP_ID" --filesystem=host
flatpak override --user "$APP_ID" --unset-env=TMPDIR 2>/dev/null || true
flatpak override --user "$APP_ID" --unset-env=GRADLE_OPTS 2>/dev/null || true

echo "==> 2/5 Limpiar studio.vmoptions (java.io.tmpdir roto)"
for f in "${HOME}/.var/app/${APP_ID}/config/Google/AndroidStudio2026."*/studio.vmoptions; do
  [ -f "$f" ] || continue
  sed -i '/java.io.tmpdir/d' "$f"
done

echo "==> 3/5 Puente /tmp/ijMapper1.gradle (Gradle exige esa ruta fija)"
mkdir -p "$CACHE"
cat > "${CACHE}/ijMapper1.gradle" << 'EOF'
// Puente ijMapper — Android Studio Flatpak; sobrescrito durante sync.
EOF
chmod 666 "${CACHE}/ijMapper1.gradle"
ln -sf "${CACHE}/ijMapper1.gradle" /tmp/ijMapper1.gradle

echo "==> 4/5 Daemon Gradle del host"
mkdir -p "${HOME}/.gradle"
printf '%s\n' 'org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=/tmp' \
  > "${HOME}/.gradle/gradle.properties"

echo "==> 5/5 Detener daemons Gradle viejos"
pkill -f 'org.gradle.launcher.daemon.bootstrap.GradleDaemon' 2>/dev/null || true
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
"${ROOT}/app/app-demo/gradlew" --stop 2>/dev/null || true
"${ROOT}/library/gradlew" --stop 2>/dev/null || true

echo ""
echo "LISTO. Ahora:"
echo "  1. Cierra Android Studio (File → Exit)"
echo "  2. Ábrelo de nuevo"
echo "  3. File → Sync Project with Gradle Files"
echo ""
echo "Overrides Flatpak:"
flatpak override --user "$APP_ID" --show
echo ""
ls -la /tmp/ijMapper1.gradle
