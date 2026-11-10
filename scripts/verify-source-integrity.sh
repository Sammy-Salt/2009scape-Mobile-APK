#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS="$ROOT/app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/Tools.java"
JRE="$ROOT/app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/utils/JREUtils.java"
MULTIRT="$ROOT/app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/multirt/MultiRTUtils.java"
EGL="$ROOT/app_pojavlauncher/src/main/jni/egl_bridge.c"
GUI="$ROOT/app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/JavaGUILauncherActivity.java"
GUI_LAYOUT="$ROOT/app_pojavlauncher/src/main/res/layout/activity_java_gui_launcher.xml"
MANIFEST="$ROOT/app_pojavlauncher/src/main/AndroidManifest.xml"

check_size() {
  local file="$1" minimum="$2"
  [[ -f "$file" ]] || { echo "Missing required source: $file" >&2; exit 1; }
  local actual
  actual="$(wc -c < "$file")"
  (( actual >= minimum )) || { echo "Source unexpectedly small ($actual bytes): $file" >&2; exit 1; }
}

check_size "$TOOLS" 25000
check_size "$JRE" 20000
check_size "$EGL" 20000
check_size "$GUI" 5000

grep -q 'public final class Tools' "$TOOLS"
grep -q 'getLWJGL3ClassPathPublic' "$TOOLS"
grep -q 'public static int launchJavaVM' "$JRE"
grep -q 'MultiRTUtils.postPrepare' "$JRE"
grep -q 'RuntimeNativeCheck' "$JRE"
grep -q 'FreeType dlopen=' "$JRE"
grep -q 'FontManager dlopen=' "$JRE"
grep -Fq 'Os.symlink(tarEntry.getLinkName(), destPath.getAbsolutePath())' "$MULTIRT"
grep -q 'findFreetypeLibrary(dest)' "$MULTIRT"
if grep -Fq 'Os.symlink(tarEntry.getName(), tarEntry.getLinkName())' "$MULTIRT"; then
  echo "Runtime symlink target/path order is incorrect." >&2
  exit 1
fi
grep -q 'Java_net_kdt_pojavlaunch_utils_JREUtils_setupBridgeWindow' "$EGL"
grep -q 'Java_org_lwjgl_opengl_GL_getNativeWidthHeight' "$EGL"
grep -q 'JavaGUILauncherActivity' "$TOOLS"
grep -q 'android:name=".JavaGUILauncherActivity"' "$MANIFEST"
grep -q 'LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_LEFT' "$GUI"
grep -q 'LwjglGlfwKeycode.GLFW_MOUSE_BUTTON_RIGHT' "$GUI"

if grep -Eq 'CallbackBridge\.MOUSE_(LEFT|RIGHT)|R\.id\.(install_texture_view|keyboardToggle)' "$GUI"; then
  echo "Stale JavaGUI mouse constants or layout IDs detected." >&2
  exit 1
fi

while IFS= read -r id; do
  grep -Fq "android:id=\"@+id/$id\"" "$GUI_LAYOUT" || {
    echo "JavaGUI references a missing layout ID: $id" >&2
    exit 1
  }
done < <(grep -oE 'R\.id\.[A-Za-z0-9_]+' "$GUI" | sed 's/^R\.id\.//' | sort -u)
for flag in 'LIBGL_FB' 'LIBGL_COPY' 'LIBGL_NOTEXSUBIMAGE' 'LIBGL_SHRINK' 'LIBGL_NOERROR' 'LIBGL_USEPBO'; do
  grep -q "$flag" "$JRE" || { echo "Missing required Mali compatibility flag: $flag" >&2; exit 1; }
done

if grep -Eqi 'PLACEHOLDER|see payload|truncated reconstruction|real .* file .* in the local payload' "$TOOLS" "$JRE" "$EGL"; then
  echo "Placeholder/truncated source detected." >&2
  exit 1
fi

test -s "$ROOT/app_pojavlauncher/src/main/assets/rt4.jar"
unzip -tq "$ROOT/app_pojavlauncher/src/main/assets/rt4.jar"
echo "Source integrity checks passed."

