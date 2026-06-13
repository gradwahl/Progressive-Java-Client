#!/usr/bin/env bash
set -e

if ! command -v java &>/dev/null; then
    echo "ERROR: Java is not installed or not in PATH. Install JDK 17 or newer." >&2
    exit 1
fi

if ! command -v javac &>/dev/null; then
    echo "ERROR: javac not found. You have a JRE, not a JDK. Install JDK 17 or newer." >&2
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

<<<<<<< Updated upstream
rm -rf target
mkdir -p target/classes
=======
JAR_OUTPUT_DIR="Jar Output"
CLASSES_DIR="$JAR_OUTPUT_DIR/classes"
CACHE_FILES=(
    main_file_cache.dat
    main_file_cache.idx0
    main_file_cache.idx1
    main_file_cache.idx2
    main_file_cache.idx3
    main_file_cache.idx4
)

test_cache_pack_dir() {
    local dir="$1"
    [ -f "$dir/main_file_cache.dat" ] && [ -f "$dir/main_file_cache.idx0" ]
}

resolve_server_cache_dir() {
    if [ -n "${RS254_SERVER_CACHE_DIR:-}" ]; then
        if test_cache_pack_dir "$RS254_SERVER_CACHE_DIR"; then
            printf '%s\n' "$RS254_SERVER_CACHE_DIR"
            return 0
        fi
        echo "ERROR: RS254_SERVER_CACHE_DIR does not point to a valid cache pack: $RS254_SERVER_CACHE_DIR" >&2
        exit 1
    fi

    local search_roots=("$SCRIPT_DIR/.." "$SCRIPT_DIR/../..")
    local best_dir=""
    local best_mtime=0

    for root in "${search_roots[@]}"; do
        [ -d "$root" ] || continue
        while IFS= read -r file; do
            local dir
            dir="$(dirname "$file")"
            test_cache_pack_dir "$dir" || continue

            local mtime
            if mtime="$(stat -c %Y "$dir/main_file_cache.dat" 2>/dev/null)"; then
                :
            else
                mtime="$(stat -f %m "$dir/main_file_cache.dat" 2>/dev/null || echo 0)"
            fi

            if [ "$mtime" -gt "$best_mtime" ]; then
                best_mtime="$mtime"
                best_dir="$dir"
            fi
        done < <(find "$root" -path '*/engine/data/pack/main_file_cache.dat' -print 2>/dev/null)
    done

    printf '%s\n' "$best_dir"
}

sync_cache_from_server_pack() {
    local server_cache_dir
    server_cache_dir="$(resolve_server_cache_dir)"

    if [ -z "$server_cache_dir" ]; then
        echo "No server cache pack found automatically. Using existing client cache files."
        return
    fi

    echo "Syncing cache from $server_cache_dir"
    mkdir -p cache

    local cache_file
    for cache_file in "${CACHE_FILES[@]}"; do
        if [ -f "$server_cache_dir/$cache_file" ]; then
            cp "$server_cache_dir/$cache_file" "cache/$cache_file"
        fi
    done
}

sync_cache_from_server_pack

rm -rf "$JAR_OUTPUT_DIR"
mkdir -p "$CLASSES_DIR"
>>>>>>> Stashed changes

if [ -d src/main/resources ]; then
    cp -r src/main/resources/. target/classes/
fi

<<<<<<< Updated upstream
=======
mkdir -p "$CLASSES_DIR/cache"
for cache_file in "${CACHE_FILES[@]}"; do
    if [ -f "cache/$cache_file" ]; then
        cp "cache/$cache_file" "$CLASSES_DIR/cache/"
    fi
done

>>>>>>> Stashed changes
find src/main/java -name "*.java" > sources.txt

javac -J-Xmx1g --release 17 -encoding UTF-8 -cp "lib/*" -d target/classes @sources.txt
rm sources.txt

# Fold runtime dependencies and LWJGL natives into the artifact so the JAR can
# be copied and launched without a sibling lib directory.
for dependency in lib/*.jar; do
    (cd target/classes && jar --extract --file "../../$dependency")
done
rm -f target/classes/META-INF/MANIFEST.MF
rm -f target/classes/META-INF/*.SF target/classes/META-INF/*.DSA target/classes/META-INF/*.RSA

BUILD_TIME="$(date -u +"%Y-%m-%dT%H:%M:%SZ")"
CLIENT_VERSION="${CLIENT_VERSION:-1.7}"
CLIENT_VERSION="${CLIENT_VERSION#v}"
cat > target/config.json <<EOF
{
  "version": "$CLIENT_VERSION",
  "web_host": "localhost",
  "web_port": 80,
  "game_port": 43594
}
EOF
printf 'Manifest-Version: 1.0\nImplementation-Version: %s\nBuild-Time: %s\n\n' "$CLIENT_VERSION" "$BUILD_TIME" > target/manifest.mf

# Build the updater jar first, then fold it into the client classes so it ships
# *inside* the client jar. At runtime the client extracts it back beside itself.
jar --create --file target/Progressive-Java-Updater.jar \
    --main-class com.gradwahl.rs254.update.UpdateHelper \
    -C target/classes com/gradwahl/rs254/update

cp target/Progressive-Java-Updater.jar target/classes/Progressive-Java-Updater.jar

jar --create --file target/Progressive-Java-Client.jar \
    --main-class com.gradwahl.rs254.Main \
    --manifest target/manifest.mf \
    -C target/classes .

<<<<<<< Updated upstream
rm target/manifest.mf

echo "Build complete: target/Progressive-Java-Client.jar"
echo "Build complete: target/Progressive-Java-Updater.jar"
=======
rm "$JAR_OUTPUT_DIR/manifest.mf"

echo "Build complete: Jar Output/Progressive-Java-Client.jar"
echo "Build complete: Jar Output/Progressive-Java-Updater.jar"
>>>>>>> Stashed changes
echo "Run with: ./run.sh"
