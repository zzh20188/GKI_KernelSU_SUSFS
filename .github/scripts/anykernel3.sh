#!/bin/bash

set -e  # Exit immediately if a command exits with a non-zero status

echo "Creating zip files for all formats..."
cd ./AnyKernel3

# Create and upload zip for each format
ZIP_NAME="${KERNEL_VERSION}.${SUB_LEVEL}-${ANDROID_VERSION}-${OS_PATCH_LEVEL}-AnyKernel3.zip"
echo "Creating zip file: $ZIP_NAME..."
mv ../Image ./Image
zip -r "../$ZIP_NAME" ./*
rm ./Image

ZIP_NAME="${KERNEL_VERSION}.${SUB_LEVEL}-${ANDROID_VERSION}-${OS_PATCH_LEVEL}-AnyKernel3-lz4.zip"
echo "Creating zip file: $ZIP_NAME..."
mv ../Image.lz4 ./Image.lz4
zip -r "../$ZIP_NAME" ./*
rm ./Image.lz4

ZIP_NAME="${KERNEL_VERSION}.${SUB_LEVEL}-${ANDROID_VERSION}-${OS_PATCH_LEVEL}-AnyKernel3-gz.zip"
echo "Creating zip file: $ZIP_NAME..."
mv ../Image.gz ./Image.gz
zip -r "../$ZIP_NAME" ./*
rm ./Image.gz
