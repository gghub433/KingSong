#!/data/data/com.termux/files/usr/bin/bash
#
# Сборка Gyro APK прямо на телефоне в Termux.
#
#   bash termux-build.sh            # отладочный APK
#   bash termux-build.sh --clean    # пересобрать с нуля
#
# Первый запуск скачивает JDK, Android SDK, Gradle и зависимости (~1,5 ГБ) и идёт 10–30 минут.
# Повторные сборки — несколько минут.
#
# Почему не просто ./gradlew: Gradle скачивает aapt2 только для x86-компьютеров. На телефоне (ARM)
# он не запустится, поэтому скрипт ставит aapt2 из пакетов Termux и подсказывает его Gradle.

set -euo pipefail

say() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[1;33m%s\033[0m\n' "$*"; }
die() { printf '\n\033[1;31mОшибка: %s\033[0m\n' "$*" >&2; exit 1; }

# --- Параметры -------------------------------------------------------------------------------

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK_DIR="${ANDROID_HOME:-$HOME/android-sdk}"
CMDLINE_ZIP="commandlinetools-linux-15859902_latest.zip"
CMDLINE_SHA256="4e4c464f145a7512b57d088ac6c278c03c9eea610886b35a5e0804e74eedf583"
PLATFORM="platforms;android-36"
BUILD_TOOLS="build-tools;35.0.0"
APK_REL="app/build/outputs/apk/debug/app-debug.apk"

CLEAN=0
GRADLE_EXTRA=()
for arg in "$@"; do
    case "$arg" in
        --clean) CLEAN=1 ;;
        *) GRADLE_EXTRA+=("$arg") ;;
    esac
done

# --- Проверки окружения ----------------------------------------------------------------------

if [[ -z "${PREFIX:-}" || "$PREFIX" != *com.termux* ]]; then
    die "скрипт рассчитан на Termux. На компьютере используйте ./gradlew :app:assembleDebug"
fi

case "$PROJECT_DIR" in
    /storage/*|/sdcard/*|"$HOME"/storage/*)
        die "проект лежит в общей памяти ($PROJECT_DIR). Там Gradle не может запускать файлы и ставить блокировки.
Склонируйте его в домашнюю папку Termux: cd ~ && git clone <адрес репозитория>"
        ;;
esac

TMPDIR="${TMPDIR:-$PREFIX/tmp}"
mkdir -p "$TMPDIR"

# --- 1. Пакеты Termux: JDK, aapt2 для ARM, утилиты -------------------------------------------

say "Проверяю пакеты Termux (openjdk-17, aapt2, unzip)"
need_pkgs=()
[[ -x "$PREFIX/lib/jvm/java-17-openjdk/bin/java" ]] || need_pkgs+=(openjdk-17)
[[ -x "$PREFIX/bin/aapt2" ]] || need_pkgs+=(aapt2)
command -v unzip >/dev/null || need_pkgs+=(unzip)
command -v curl >/dev/null || need_pkgs+=(curl)
if ((${#need_pkgs[@]})); then
    pkg install -y "${need_pkgs[@]}" || { pkg update -y && pkg install -y "${need_pkgs[@]}"; } \
        || die "не удалось установить пакеты: ${need_pkgs[*]}"
fi

export JAVA_HOME="$PREFIX/lib/jvm/java-17-openjdk"
[[ -x "$JAVA_HOME/bin/java" ]] || die "JDK 17 не найден в $JAVA_HOME"
AAPT2="$PREFIX/bin/aapt2"
[[ -x "$AAPT2" ]] || die "aapt2 не установлен (pkg install aapt2)"
echo "Java:  $("$JAVA_HOME/bin/java" -version 2>&1 | head -1)"
echo "aapt2: $("$AAPT2" version 2>&1 | head -1)"

# --- 2. Android SDK: платформа и build-tools -------------------------------------------------

SDKMANAGER="$SDK_DIR/cmdline-tools/latest/bin/sdkmanager"
if [[ ! -f "$SDKMANAGER" ]]; then
    say "Скачиваю Android command-line tools в $SDK_DIR"
    mkdir -p "$SDK_DIR/cmdline-tools"
    zip="$TMPDIR/$CMDLINE_ZIP"
    curl -fL --retry 3 -o "$zip" "https://dl.google.com/android/repository/$CMDLINE_ZIP" \
        || die "не удалось скачать $CMDLINE_ZIP"
    echo "$CMDLINE_SHA256  $zip" | sha256sum -c - >/dev/null \
        || die "контрольная сумма $CMDLINE_ZIP не совпала — файл повреждён, запустите скрипт ещё раз"
    rm -rf "$SDK_DIR/cmdline-tools/latest" "$TMPDIR/cmdline-tools"
    unzip -q "$zip" -d "$TMPDIR"
    mv "$TMPDIR/cmdline-tools" "$SDK_DIR/cmdline-tools/latest"
    rm -f "$zip"
fi

sdk() { JAVA_HOME="$JAVA_HOME" sh "$SDKMANAGER" --sdk_root="$SDK_DIR" "$@"; }

if [[ ! -f "$SDK_DIR/platforms/android-36/android.jar" || ! -d "$SDK_DIR/build-tools/35.0.0" ]]; then
    say "Устанавливаю $PLATFORM и $BUILD_TOOLS (принимаю лицензии Android SDK)"
    yes | sdk --licenses >/dev/null 2>&1 || true
    sdk "$PLATFORM" "$BUILD_TOOLS" || die "sdkmanager не смог установить $PLATFORM / $BUILD_TOOLS"
fi
# Бинарники из build-tools собраны для x86 и на телефоне не запускаются; Gradle нужен только их
# каталог, а aapt2 берётся из Termux.

say "Проверяю, что aapt2 читает android.jar платформы 36"
probe="$TMPDIR/gyro-aapt2-probe"
rm -rf "$probe" && mkdir -p "$probe"
cat >"$probe/AndroidManifest.xml" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="probe.gyro" />
XML
if ! "$AAPT2" link -I "$SDK_DIR/platforms/android-36/android.jar" --manifest "$probe/AndroidManifest.xml" \
        -o "$probe/out.apk" >"$probe/log" 2>&1; then
    cat "$probe/log"
    die "aapt2 из Termux не справился с android-36. Обновите пакеты: pkg upgrade aapt2"
fi
rm -rf "$probe"

# --- 3. Сборка --------------------------------------------------------------------------------

cd "$PROJECT_DIR"
printf 'sdk.dir=%s\n' "$SDK_DIR" >local.properties

# Память телефона ограничена: треть ОЗУ под Gradle, от 1 до 3 ГБ.
mem_mb=$(awk '/MemTotal/ {print int($2 / 1024)}' /proc/meminfo)
heap_mb=$((mem_mb / 3))
((heap_mb < 1024)) && heap_mb=1024
((heap_mb > 3072)) && heap_mb=3072

export ANDROID_HOME="$SDK_DIR" ANDROID_SDK_ROOT="$SDK_DIR"
# Нативные библиотеки Gradle собраны под glibc, а в Android другая libc — отключаем их.
export GRADLE_OPTS="-Djava.io.tmpdir=$TMPDIR -Dorg.gradle.native=false"

tasks=(:app:assembleDebug)
((CLEAN)) && tasks=(clean :app:assembleDebug)

say "Собираю APK (Gradle ${heap_mb} МБ). Первый раз это долго — скачиваются зависимости"
bash ./gradlew \
    --no-watch-fs --no-parallel --console=plain \
    "-Dorg.gradle.jvmargs=-Xmx${heap_mb}m -Dfile.encoding=UTF-8 -Djava.io.tmpdir=$TMPDIR" \
    "-Pandroid.aapt2FromMavenOverride=$AAPT2" \
    "-Pkotlin.compiler.execution.strategy=in-process" \
    "${tasks[@]}" "${GRADLE_EXTRA[@]}" \
    || die "сборка не удалась, подробности выше"

[[ -f "$APK_REL" ]] || die "Gradle закончил без ошибок, но $APK_REL не найден"

# --- 4. Куда положить APK --------------------------------------------------------------------

say "Готово"
if [[ -d "$HOME/storage/downloads" ]]; then
    cp -f "$APK_REL" "$HOME/storage/downloads/Gyro-debug.apk"
    echo "APK скопирован в «Загрузки»: Gyro-debug.apk — откройте его в файловом менеджере и установите."
else
    echo "APK: $PROJECT_DIR/$APK_REL"
    warn "Чтобы скрипт сам клал APK в «Загрузки», один раз выполните: termux-setup-storage"
fi
echo "Установить сразу из Termux: termux-open $PROJECT_DIR/$APK_REL"
