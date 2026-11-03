      - name: 集成 NoMount 挂载元模块
        if: inputs.nomount_choice != 'none'
        working-directory: ${{ env.KERNEL_ROOT }}/common
        run: |
          set -euo pipefail

          if [ "${{ inputs.nomount_choice }}" = "primary" ]; then
            echo "=== 集成 NoMount (maxsteeel/nomount dev) ==="
            curl -fsSL --retry 5 --retry-delay 5 --retry-all-errors \
              https://raw.githubusercontent.com/maxsteeel/nomount/refs/heads/dev/kernel/setup.sh \
              | sh -s dev
            if [ ! -L "fs/nomount" ] && [ ! -d "fs/nomount" ]; then
              echo "::error::NoMount 集成失败：fs/nomount 缺失"
              exit 1
            fi
            if ! grep -q '^CONFIG_NOMOUNT=y' "$DEFCONFIG"; then
              echo "CONFIG_NOMOUNT=y" >> "$DEFCONFIG"
            fi
          fi

          if [ "${{ inputs.nomount_choice }}" = "secondary" ]; then
            echo "=== 构建 NoMount-Suite hookless LKM ==="
            chmod +x "$GITHUB_WORKSPACE/scripts/build-nomount-lkm.sh"
            "$GITHUB_WORKSPACE/scripts/build-nomount-lkm.sh" \
              "${{ env.KERNEL_ROOT }}/common" \
              "${{ env.KERNEL_ROOT }}/out/modules"

            # defconfig 确保模块支持
            for opt in CONFIG_MODULES=y CONFIG_MODULE_UNLOAD=y CONFIG_KALLSYMS=y CONFIG_KALLSYMS_ALL=y; do
              if ! grep -q "^${opt%*=*}" "$DEFCONFIG"; then
                echo "$opt" >> "$DEFCONFIG"
              fi
            done
            echo "NoMount-Suite hookless LKM 集成完成"
          fi
