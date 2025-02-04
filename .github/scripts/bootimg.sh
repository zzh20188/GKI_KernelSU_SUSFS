#!/bin/bash

set -e  # Exit if any command fails

echo "Unpacking boot.img and Building Kernels..."
cd bootimgs

echo "Building Image.gz"
gzip -n -k -f -9 ./Image > ./Image.gz

echo "Building boot.img"
$MKBOOTIMG --header_version 4 --kernel Image --output boot.img
$AVBTOOL add_hash_footer --partition_name boot --partition_size $((64 * 1024 * 1024)) --image boot.img --algorithm SHA256_RSA2048 --key $BOOT_SIGN_KEY_PATH
cp ./boot.img ../${KERNEL_VERSION}.${SUB_LEVEL}-${ANDROID_VERSION}-${OS_PATCH_LEVEL}-boot.img

echo "Building boot-gz.img"
$MKBOOTIMG --header_version 4 --kernel Image.gz --output boot-gz.img
$AVBTOOL add_hash_footer --partition_name boot --partition_size $((64 * 1024 * 1024)) --image boot-gz.img --algorithm SHA256_RSA2048 --key $BOOT_SIGN_KEY_PATH
cp ./boot-gz.img ../${KERNEL_VERSION}.${SUB_LEVEL}-${ANDROID_VERSION}-${OS_PATCH_LEVEL}-boot-gz.img

echo "Building boot-lz4.img"
$MKBOOTIMG --header_version 4 --kernel Image.lz4 --output boot-lz4.img
$AVBTOOL add_hash_footer --partition_name boot --partition_size $((64 * 1024 * 1024)) --image boot-lz4.img --algorithm SHA256_RSA2048 --key $BOOT_SIGN_KEY_PATH
cp ./boot-lz4.img ../${KERNEL_VERSION}.${SUB_LEVEL}-${ANDROID_VERSION}-${OS_PATCH_LEVEL}-boot-lz4.img
