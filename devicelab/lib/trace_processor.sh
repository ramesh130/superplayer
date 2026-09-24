# shellcheck shell=bash
# Copyright 2026 The SuperPlayer Authors
# SPDX-License-Identifier: Apache-2.0
#
# Perfetto's trace processor, pinned: the one binary every devicelab consumer queries a trace with.
#
# Downloaded rather than vendored, because it is a 13 MB native binary per host platform. Pinned twice
# over. The version is the catalog's `perfetto`, like every other version this repository uses, and
# the archive must match the sha256 in `perfetto/trace-processor.sha256`. An archive that does not is
# refused rather than run, so a version bump that forgets its hashes fails, loudly, on first use.
#
# Cached under $DEVICELAB_CACHE (default: ${XDG_CACHE_HOME:-~/.cache}/superplayer-devicelab), so a
# laptop downloads it once per version. TRACE_PROCESSOR=/path/to/trace_processor_shell skips all of
# this for a machine with no network. Whoever sets it vouches for the binary: nothing checks it.
#
# ref: https://perfetto.dev/docs/analysis/trace-processor
# ref: https://github.com/google/perfetto/releases

# The Perfetto release asset for a host, from `uname -s` and `uname -m`.
trace_processor_platform() {
    case "$1 $2" in
        "Darwin arm64") printf 'mac-arm64\n' ;;
        "Darwin x86_64") printf 'mac-amd64\n' ;;
        "Linux x86_64") printf 'linux-amd64\n' ;;
        "Linux aarch64" | "Linux arm64") printf 'linux-arm64\n' ;;
        *) die "Perfetto publishes no trace processor for a $1 $2 host; set TRACE_PROCESSOR to one you built" ;;
    esac
}

# The pinned sha256 of release `$1`'s archive for platform `$2`, from pin file `$3` (default: ours).
trace_processor_pinned_sha256() {
    local pins="${3:-$DEVICELAB_HOME/perfetto/trace-processor.sha256}" sha
    sha="$(awk -v version="$1" -v platform="$2" '$1 == version && $2 == platform {print $3; exit}' "$pins")"
    [ -n "$sha" ] ||
        die "no pinned sha256 for Perfetto $1 on $2; add the release's digest to devicelab/perfetto/trace-processor.sha256"
    printf '%s\n' "$sha"
}

# Prints the path of the pinned trace_processor_shell, downloading and checking it on first use.
trace_processor_bin() {
    if [ -n "${TRACE_PROCESSOR:-}" ]; then
        [ -x "$TRACE_PROCESSOR" ] || die "TRACE_PROCESSOR=$TRACE_PROCESSOR is not executable"
        printf '%s\n' "$TRACE_PROCESSOR"
        return
    fi
    local version platform sha dir binary archive
    version="$(catalog_version perfetto)"
    platform="$(trace_processor_platform "$(uname -s)" "$(uname -m)")"
    sha="$(trace_processor_pinned_sha256 "$version" "$platform")"
    dir="${DEVICELAB_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/superplayer-devicelab}/trace-processor-$version-$platform"
    binary="$dir/trace_processor_shell"

    # The binary's own hash is recorded when it is extracted from a checked archive, and compared on
    # every use after that, so a cache left half-written by an interrupted run is found, not run.
    if [ -x "$binary" ] && [ -f "$binary.sha256" ] && [ "$(sha256_of "$binary")" = "$(cat "$binary.sha256")" ]; then
        printf '%s\n' "$binary"
        return
    fi

    mkdir -p "$dir"
    archive="$dir/$platform.zip.part"
    log "downloading Perfetto $version's trace processor for $platform"
    curl -fsSL --retry 3 --max-time 300 -o "$archive" \
        "https://github.com/google/perfetto/releases/download/v$version/$platform.zip" ||
        die "could not download Perfetto $version for $platform"
    if [ "$(sha256_of "$archive")" != "$sha" ]; then
        rm -f "$archive"
        die "the downloaded Perfetto $version archive for $platform does not match its pinned sha256; refusing to run it"
    fi
    unzip -o -q -j "$archive" "$platform/trace_processor_shell" -d "$dir" ||
        die "the Perfetto $version archive has no $platform/trace_processor_shell"
    rm -f "$archive"
    chmod +x "$binary"
    sha256_of "$binary" > "$binary.sha256"
    printf '%s\n' "$binary"
}

# Runs query file `$2` against trace `$1` and prints its result as trace processor's CSV. Further
# arguments are NAME=VALUE pairs, each replacing @NAME@ in the query first.
#
# A query file should end in the one SELECT whose rows it means to return: every statement that
# returns rows prints them, one block after another.
trace_processor_query() {
    local trace="$1" query="$2" work pair name
    shift 2
    work="$(mktemp -d "${DEVICELAB_WORK:-${TMPDIR:-/tmp}}/tp.XXXXXX")"
    cp "$query" "$work/query.sql"
    for pair in "$@"; do
        name="${pair%%=*}"
        VALUE="${pair#*=}" perl -pi -e "s/\\@${name}\\@/\$ENV{VALUE}/g" "$work/query.sql"
    done
    if grep -q '@[A-Z_]*@' "$work/query.sql"; then
        die "unsubstituted placeholder in $query: $(grep -o '@[A-Z_]*@' "$work/query.sql" | head -1)"
    fi
    if ! "$(trace_processor_bin)" -q "$work/query.sql" "$trace" 2> "$work/stderr"; then
        tail -20 "$work/stderr" >&2
        rm -rf "$work"
        die "trace processor failed running $(basename "$query") on $trace"
    fi
    rm -rf "$work"
}

# --- Its output, for a report -----------------------------------------------------------------------
#
# Shared by every consumer that renders a query into report.md: the leak hunt first, then the startup
# scenario.

# Trace processor's CSV — strings quoted, a quote doubled — on stdin, as tab-separated fields. Blank
# lines, which separate one result from the next, are dropped.
tp_csv_to_tsv() {
    perl -ne '
        chomp; s/\r$//;
        next if $_ eq "";
        my ($rest, @fields) = ($_);
        while (length $rest) {
            if ($rest =~ s/^"((?:[^"]|"")*)"(?:,|$)//) { (my $v = $1) =~ s/""/"/g; push @fields, $v }
            elsif ($rest =~ s/^([^,]*)(?:,|$)//) { push @fields, $1 }
        }
        print join("\t", @fields), "\n";
    '
}

# Tab-separated rows on stdin, the first of them the header, as a Markdown table.
tsv_to_markdown() {
    perl -ne '
        chomp;
        my @cells = map { s/\|/\\|/gr } split /\t/, $_, -1;
        print "| ", join(" | ", @cells), " |\n";
        print "|", " --- |" x @cells, "\n" if $. == 1;
    '
}
