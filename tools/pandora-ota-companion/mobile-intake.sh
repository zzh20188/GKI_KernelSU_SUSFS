#!/usr/bin/env bash
# No flashing; validate mobile upload before replacing the single fixed OTA input.
set -Eeuo pipefail
: "$GITHUB_REPOSITORY" "$GH_TOKEN" "$RUNNER_TEMP" "$STAGED_RELEASE_ID" "$STAGED_ASSET_ID" "$CLIENT_SHA" "$CLIENT_BYTES"
[[ "$GITHUB_REPOSITORY" == "wekingchen/GKI_KernelSU_SUSFS" ]] || exit 2
[[ "$STAGED_RELEASE_ID" =~ ^[0-9]+$ && "$STAGED_ASSET_ID" =~ ^[0-9]+$ ]] || exit 2
[[ "$CLIENT_SHA" =~ ^[0-9a-f]{64}$ && "$CLIENT_BYTES" =~ ^[0-9]+$ ]] || exit 2
[[ "$CLIENT_BYTES" -gt 0 && "$CLIENT_BYTES" -lt 996147200 ]] || exit 2
R="$GITHUB_REPOSITORY"
DIR="$RUNNER_TEMP/pandora-mobile-intake"
mkdir -p "$DIR/input" "$DIR/converted" "$DIR/check"
echo '## 手机 OTA 只读采集安全接收' >> "$GITHUB_STEP_SUMMARY"
echo '本工作流不会刷机、更新 LKG 或跳过原 OEM ABI/KMI 门禁。' >> "$GITHUB_STEP_SUMMARY"

RELEASE="$(gh api "repos/$R/releases/$STAGED_RELEASE_ID")"
jq -e --argjson id "$STAGED_RELEASE_ID" \
  --argjson asset "$STAGED_ASSET_ID" --arg sha "$CLIENT_SHA" \
  -f tools/pandora-ota-companion/mobile-release-gate.jq \
  <<< "$RELEASE" >/dev/null
TAG="$(jq -r .tag_name <<< "$RELEASE")"
ASSET="$(jq -e --argjson id "$STAGED_ASSET_ID" \
  '[.assets[] | select(.id == $id)] | if length == 1 then .[0] else error("missing asset") end' \
  <<< "$RELEASE")"
ASSET_NAME="$(jq -r .name <<< "$ASSET")"
[[ "$ASSET_NAME" =~ ^pandora-os4-capture-[0-9]{8}-[0-9]{6}(-[0-9]{3})?\.zip$ ]] || exit 3
test "$(jq -r .size <<< "$ASSET")" = "$CLIENT_BYTES"

# Machine-readable source Draft Release receipt. This is NOT GKI or LKG verification.
RECEIPT_VALID=1
publish_receipt() {
  local state="$1"
  local body
  printf -v body 'MOBILE_OTA_INTAKE=%s\nMOBILE_ASSET_ID=%s\nMOBILE_SOURCE_SHA256=%s\nMOBILE_BUILD=%s\nMOBILE_INTAKE_RUN=%s\nSOURCE_PROVENANCE=ACTIVE_DEVICE_SNAPSHOT_NOT_VERIFIED_STOCK\n' \
    "$state" "$STAGED_ASSET_ID" "$CLIENT_SHA" "${BUILD:-UNKNOWN}" "$GITHUB_RUN_ID"
  if [[ -n "${SOFTWARE_REVISION:-}" ]]; then
    body+=$'\nMOBILE_SOFTWARE_REVISION='"$SOFTWARE_REVISION"
    body+=$'\nMOBILE_SOFTWARE_REVISION_SOURCE=USER_CONFIRMED_UPDATE_SCREEN'
    body+=$'\nMOBILE_SOFTWARE_REVISION_DEVICE_VERIFIED=NO'
  fi
  # GitHub may replace draft tag_name with untagged-* when updating its body.
  # Restore a stable app-owned tag so existing APK receipt queries still work.
  gh api -X PATCH "repos/$R/releases/$STAGED_RELEASE_ID" \
    -f tag_name="pandora-ota-capture-$STAGED_RELEASE_ID" -f body="$body" >/dev/null
}
on_intake_exit() {
  local code=$?
  trap - EXIT
  if [[ "$code" -ne 0 && "${RECEIPT_VALID:-0}" == "1" && "${TRANSACTION_STARTED:-0}" != "1" ]]; then
    publish_receipt REJECTED || true
  fi
  exit "$code"
}
trap on_intake_exit EXIT
# Draft Releases may not resolve by tag (GitHub gives untagged-* links).
# Fetch the exact authenticated asset by ID; a SHA-256 and size match is mandatory.
gh api -H "Accept: application/octet-stream" \
  "repos/$R/releases/assets/$STAGED_ASSET_ID" > "$DIR/input/$ASSET_NAME"
SOURCE="$DIR/input/$ASSET_NAME"
test -s "$SOURCE"
echo "$CLIENT_SHA  $SOURCE" | sha256sum -c -
test "$(stat -c '%s' "$SOURCE")" = "$CLIENT_BYTES"

python3 tools/pandora-ota-companion/normalize_mobile_capture.py \
  --input "$SOURCE" --output "$DIR/converted/incoming-xm.zip" \
  > "$DIR/normalize-report.json"
BUILD="$(jq -r .build "$DIR/normalize-report.json")"
SLOT="$(jq -r .slot "$DIR/normalize-report.json")"
SOFTWARE_REVISION="$(jq -r '.software_revision // ""' "$DIR/normalize-report.json")"
NEW_NAME="pandora-stock-$BUILD-$SLOT-xm.zip"
NEW="$DIR/converted/$NEW_NAME"
mv "$DIR/converted/incoming-xm.zip" "$NEW"
python3 scripts/xiaomi-os4/inspect-ota-stock-input.py \
  --zip "$NEW" \
  --stock-config .github/config/pandora-os4-stock-input.env \
  --output "$DIR/stock-audit.json"
STATE="$(jq -r .status "$DIR/stock-audit.json")"
[[ "$STATE" == "SAME_PINNED_BUILD" || "$STATE" == "NEW_OTA_CANDIDATE" || "$STATE" == "SOFTWARE_OTA_REVIEW" ]] || {
  echo "::error::OTA 来源身份审计阻断，固定输入完全不变"; exit 4;
}

# Validate canonical manifest and count before touching the fixed release.
python3 - "$NEW" <<'PY'
import hashlib, io, re, sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    names = ["boot.img","init_boot.img","vendor_boot.img","dtbo.img","oem-modules.zip"]
    # These mandatory labels prevent the Root-patched active init_boot from
    # being silently promoted as a factory-original / flashable OEM image.
    readme = dict(line.split("=", 1) for line in
                  z.read("README.txt").decode("utf-8").splitlines()
                  if "=" in line)
    expected = {
        "boot_oem_original_verified": "NO",
        "boot_role": "GKI_KERNEL_ANALYSIS_INPUT",
        "init_boot_source": "ROOTED_ACTIVE_SLOT_MAY_BE_PATCHED",
        "init_boot_oem_original_verified": "NO",
        "init_boot_role": "DIAGNOSTIC_ONLY_NOT_FACTORY_IMAGE",
    }
    if any(readme.get(key) != value for key, value in expected.items()):
        raise SystemExit("source provenance missing: rooted init_boot is not factory OEM input")
    records = {}
    for line in z.read("SHA256SUMS.txt").decode("ascii").splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  ([A-Za-z0-9_.-]+)", line)
        if not match or match[2] in records:
            raise SystemExit("invalid SHA256SUMS")
        records[match[2]] = match[1]
    if set(names) != set(records):
        raise SystemExit("incomplete SHA256SUMS")
    for name in names:
        if hashlib.sha256(z.read(name)).hexdigest() != records[name]:
            raise SystemExit("sha256 mismatch")
    count = int(z.read("oem-modules-count.txt").decode().strip().split("=")[1])
    with zipfile.ZipFile(io.BytesIO(z.read("oem-modules.zip"))) as nested:
        actual = sum(bool(re.search(r"\.ko(\.(zst|xz|gz))?$", n, re.I))
                     for n in nested.namelist())
    if not (1 <= count == actual <= 4096):
        raise SystemExit("OEM module count mismatch")
PY

FIXED_TAG="pandora-os4-stock-input"
FIXED="$(gh api "repos/$R/releases/tags/$FIXED_TAG")"
jq -e --arg tag "$FIXED_TAG" '.draft == false and .tag_name == $tag' <<< "$FIXED" >/dev/null
mapfile -t OLD_IDS < <(jq -r '.assets[] | select(.name | endswith("xm.zip")) | .id' <<< "$FIXED")
[[ 1 -eq "$(printf '%s\n' "$FIXED" | jq '[.assets[] | select(.name | endswith("xm.zip"))] | length')" ]] || exit 5
OLD_ID="$OLD_IDS"
OLD_NAME="$(jq -r --argjson id "$OLD_ID" '.assets[] | select(.id == $id) | .name' <<< "$FIXED")"
FIXED_ID="$(jq -r .id <<< "$FIXED")"
gh release download "$FIXED_TAG" --repo "$R" --pattern "$OLD_NAME" --dir "$DIR/check"
test -s "$DIR/check/$OLD_NAME"
# The static config is only the original OS4 legacy baseline. The authoritative
# monotonic version gate is the CURRENT fixed Release asset, so later OTAs can
# never be rolled back by uploading an older historical capture.
FALLBACK_BUILD="$(grep '^OS4_STOCK_INPUT_BUILD=' .github/config/pandora-os4-stock-input.env | cut -d= -f2-)"
[[ "$FALLBACK_BUILD" =~ ^OS[0-9] ]] || { echo "::error::缺少静态 legacy build"; exit 8; }
set +e
python3 scripts/xiaomi-os4/guard-current-stock-input.py \
  --current-zip "$DIR/check/$OLD_NAME" \
  --incoming-zip "$NEW" \
  --incoming-build "$BUILD" \
  --fallback-current-build "$FALLBACK_BUILD" \
  --incoming-state "$STATE" \
  > "$DIR/current-version-guard.env"
rc=$?
set -e
cat "$DIR/current-version-guard.env" >> "$GITHUB_STEP_SUMMARY" || true
if [[ "$rc" -eq 80 ]]; then
  echo '与 GitHub 当前固定 OTA 输入一致，无须替换资产。' >> "$GITHUB_STEP_SUMMARY"
  publish_receipt ALREADY_CURRENT
  exit 0
fi
if [[ "$rc" -eq 81 ]]; then
  echo '软件 OTA 版本证据已保存至手机来源 Draft；底层内核/OEM 输入尚未证明相同，不替换固定 stock-input，不构建 GKI，不修改 LKG。' >> "$GITHUB_STEP_SUMMARY"
  publish_receipt REVIEW_REQUIRED
  exit 0
fi
[[ "$rc" -eq 0 ]] || {
  echo "::error::当前固定 OTA 版本门禁拒绝替换；旧 stock-input 保持不变"
  exit 8
}

# Once transaction preparation starts, an interruption is PENDING until audited.
# Never label a potentially committed fixed-input switch REJECTED in an EXIT trap.
TRANSACTION_STARTED=1
python3 scripts/xiaomi-os4/stock-input-transaction.py switch \
  --source-release "$STAGED_RELEASE_ID" --source-asset "$STAGED_ASSET_ID" \
  --source-sha "$CLIENT_SHA" --run "$GITHUB_RUN_ID" --build "$BUILD" \
  --new "$NEW" --old "$DIR/check/$OLD_NAME"
echo "固定输入事务已提交并保存持久备份与回执。" >> "$GITHUB_STEP_SUMMARY"
if gh workflow run xiaomi-sm8850-os4-ota-auto-candidate.yml --repo "$R" --ref dev; then
  echo "已主动触发原 OS4 OTA 自动 GKI 候选检查" >> "$GITHUB_STEP_SUMMARY"
else
  echo "::warning::固定输入成功切换，但即时工作流触发失败；每日定时补查仍可接管"
fi
