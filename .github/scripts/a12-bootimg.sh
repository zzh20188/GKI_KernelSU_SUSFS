#!/bin/bash

set -e  # Exit if any command fails

echo "Changing to configuration directory: $CONFIG..."
cd bootimgs

GKI_URL="https://dl.google.com/android/gki/gki-certified-boot-android12-5.10-${OS_PATCH_LEVEL}_r1.zip"
FALLBACK_URL="https://dl.google.com/android/gki/gki-certified-boot-android12-5.10-2023-01_r1.zip"

# Check if the GKI URL is available
echo "Checking if GKI kernel URL is reachable: $GKI_URL"
status=$(curl -sL -w "%{http_code}" "$GKI_URL" -o /dev/null)

if [ "$status" = "200" ]; then
    echo "[+] Downloading from GKI_URL"
    curl -Lo gki-kernel.zip "$GKI_URL"
else
    echo "[+] $GKI_URL not found, using $FALLBACK_URL"
    curl -Lo gki-kernel.zip "$FALLBACK_URL"
fi

# Unzip the downloaded kernel and remove the zip
echo "Unzipping the downloaded kernel..."
unzip gki-kernel.zip && rm gki-kernel.zip

echo "Unpacking boot.img and Building Kernels..."

echo "Unpacking boot.img..."
FULL_PATH=$(pwd)/boot-5.10.img
echo "Unpacking using: $FULL_PATH"

echo "Running unpack_bootimg.py..."
$UNPACK_BOOTIMG --boot_img="$FULL_PATH"

echo "Building Image.gz"
gzip -n -k -f -9 ./Image > ./Image.gz

echo "Building boot.img"
$MKBOOTIMG --header_version 4 --kernel Image --output boot.img --ramdisk out/ramdisk --os_version 12.0.0 --os_patch_level "$OS_PATCH_LEVEL"
$AVBTOOL add_hash_footer --partition_name boot --partition_size $((64 * 1024 * 1024)) --image boot.img --algorithm SHA256_RSA2048 --key "$BOOT_SIGN_KEY_PATH"
cp ./boot.img ../"${KERNEL_VERSION}.${SUB_LEVEL}-${ANDROID_VERSION}-${OS_PATCH_LEVEL}-boot.img"

echo "Building boot-gz.img"
$MKBOOTIMG --header_version 4 --kernel Image.gz --output boot-gz.img --ramdisk out/ramdisk --os_version 12.0.0 --os_patch_level "$OS_PATCH_LEVEL"
$AVBTOOL add_hash_footer --partition_name boot --partition_size $((64 * 1024 * 1024)) --image boot-gz.img --algorithm SHA256_RSA2048 --key "$BOOT_SIGN_KEY_PATH"
cp ./boot-gz.img ../"${KERNEL_VERSION}.${SUB_LEVEL}-${ANDROID_VERSION}-${OS_PATCH_LEVEL}-boot-gz.img"

echo "Building boot-lz4.img"
$MKBOOTIMG --header_version 4 --kernel Image.lz4 --output boot-lz4.img --ramdisk out/ramdisk --os_version 12.0.0 --os_patch_level "$OS_PATCH_LEVEL"
$AVBTOOL add_hash_footer --partition_name boot --partition_size $((64 * 1024 * 1024)) --image boot-lz4.img --algorithm SHA256_RSA2048 --key "$BOOT_SIGN_KEY_PATH"
cp ./boot-lz4.img ../"${KERNEL_VERSION}.${SUB_LEVEL}-${ANDROID_VERSION}-${OS_PATCH_LEVEL}-boot-lz4.img"
