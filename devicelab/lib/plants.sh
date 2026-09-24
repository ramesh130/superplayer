# shellcheck shell=bash
# Copyright 2026 The SuperPlayer Authors
# SPDX-License-Identifier: Apache-2.0
#
# Plants: a known regression, applied to the source for one run and taken out again, so that a trace of
# it exists without the regression ever reaching a commit. `lab run <scenario> --plant <name>` applies
# `plants/<name>.patch`; plants/README.md is the manual and lists what each one is for.
#
# The procedure is the leak hunt's negative test (leak/README.md), made mechanical: apply, publish and
# build, install, and revert as soon as the APK is on the device, before anything is measured. What was
# measured is then the base commit plus the patch, and both are recorded — the commit and the patch's
# sha256 in run.json, the patch itself beside it — so a capture can be rebuilt from its metadata alone.
#
# A plant is applied only to a clean tree. With uncommitted changes as well, the build would be a third
# thing, and the run would name two of the three.

PLANT_NAME=""
PLANT_PATCH_SHA256=""
PLANT_BASE_COMMIT=""
PLANT_REPO=""
PLANT_APPLIED=0

plants_available() {
    local patch
    for patch in "$DEVICELAB_HOME"/plants/*.patch; do
        [ -e "$patch" ] || continue
        basename "$patch" .patch
    done
}

# The patch file for plant `$1`, or a failure naming the plants there are.
plant_file() {
    local file="$DEVICELAB_HOME/plants/$1.patch"
    [ -f "$file" ] || die "no plant '$1'; available: $(plants_available | tr '\n' ' ')"
    printf '%s\n' "$file"
}

# Fails unless plant `$1` would apply to repository `$2` as it stands, which is clean. Touches nothing:
# a run calls it before a device is found or anything is built, so a patch that has rotted, or a dirty
# tree, costs a second rather than a boot.
plant_check() {
    local name="$1" repo="$2" file output
    file="$(plant_file "$name")"
    [ -z "$(git -C "$repo" status --porcelain)" ] ||
        die "plant '$name' is applied only to a clean tree: the run records the commit and the patch, and uncommitted changes would be a third thing it measured and did not name"
    output="$(git -C "$repo" apply --check "$file" 2>&1)" ||
        die "plant '$name' does not apply to $(git -C "$repo" rev-parse --short HEAD): $output"
}

# Applies plant `$1` to repository `$2`, recording what it was applied to.
plant_apply() {
    local name="$1" repo="$2" file
    plant_check "$name" "$repo"
    file="$(plant_file "$name")"
    PLANT_NAME="$name"
    PLANT_REPO="$repo"
    PLANT_BASE_COMMIT="$(git -C "$repo" rev-parse HEAD)"
    PLANT_PATCH_SHA256="$(sha256_of "$file")"
    git -C "$repo" apply "$file" || die "plant '$name' passed its check and then failed to apply"
    PLANT_APPLIED=1
    log "planted '$name' (patch sha256 ${PLANT_PATCH_SHA256:0:12}…) on $PLANT_BASE_COMMIT"
}

# Takes the applied plant out again, and proves the tree is as clean as it was. Returns non-zero rather
# than exiting, because the run's exit trap calls it too, and a trap that died here would skip the rest
# of its cleanup. A no-op when nothing is applied.
plant_revert() {
    [ "$PLANT_APPLIED" = 1 ] || return 0
    if ! git -C "$PLANT_REPO" apply -R "$(plant_file "$PLANT_NAME")"; then
        log "error: could not revert plant '$PLANT_NAME'; \`git -C $PLANT_REPO status\` shows what is left of it"
        return 1
    fi
    PLANT_APPLIED=0
    if [ -n "$(git -C "$PLANT_REPO" status --porcelain)" ]; then
        log "error: the tree is not clean after reverting plant '$PLANT_NAME'"
        return 1
    fi
    log "reverted plant '$PLANT_NAME'; the tree is clean again"
}

# run.json's `plant`: null for a run with none, which is every run but a planted one.
plant_json() {
    if [ -z "$PLANT_NAME" ]; then
        printf 'null'
        return
    fi
    printf '{"name": %s, "patch": %s, "patch_sha256": %s, "base_commit": %s}' \
        "$(json_str "$PLANT_NAME")" "$(json_str "plant.patch")" \
        "$(json_str "$PLANT_PATCH_SHA256")" "$(json_str "$PLANT_BASE_COMMIT")"
}
