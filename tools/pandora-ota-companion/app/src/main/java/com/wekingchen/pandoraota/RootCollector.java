package com.wekingchen.pandoraota;

import android.content.Context;
import android.os.Build;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.*;

/**
 * Read-only rooted snapshot: all intermediate partitions and TARs are disposable.
 * Successful captures retain ONLY the final uploadable ZIP.
 * A failed capture removes its partial ZIP and attempts to remove its staging files.
 */
final class RootCollector {
    private RootCollector() {}
    private static final long STALE_AFTER_MS = 30L * 60L * 1000L;

    static boolean isPandora() {
        return "pandora".equalsIgnoreCase(Build.DEVICE)
                || "pandora".equalsIgnoreCase(Build.PRODUCT);
    }

    /** Read-only public system identity; does not require Root. */
    static String currentOsVersion() {
        String value = readProperty("ro.mi.os.version.incremental");
        if (value.isEmpty()) value = Build.VERSION.INCREMENTAL == null ? "" : Build.VERSION.INCREMENTAL.trim();
        return value.isEmpty() ? "未知版本" : value;
    }

    /** Human-facing active A/B slot label; collection still re-validates _a/_b under Root. */
    static String currentSlotLabel() {
        String value = readProperty("ro.boot.slot_suffix");
        if (value.isEmpty()) value = readProperty("ro.boot.slot");
        return normalizeSlotLabel(value);
    }

    static String normalizeSlotLabel(String value) {
        if (value == null) return "未知";
        String slot = value.trim().toLowerCase(Locale.US);
        if ("_a".equals(slot) || "a".equals(slot)) return "A";
        if ("_b".equals(slot) || "b".equals(slot)) return "B";
        return "未知";
    }

    private static String readProperty(String key) {
        Process p = null;
        try {
            p = new ProcessBuilder("getprop", key).redirectErrorStream(true).start();
            if (!p.waitFor(2, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                p.waitFor();
                return "";
            }
            if (p.exitValue() != 0) return "";
            return readLimited(p.getInputStream(), 512).trim();
        } catch (Exception ignored) {
            return "";
        } finally {
            if (p != null) p.destroy();
        }
    }

    static final class CaptureIdentity {
        final String build;
        final String slotSuffix;
        final String slotLabel;
        final String uname;

        CaptureIdentity(String build, String slotSuffix, String uname) {
            this.build = build;
            this.slotSuffix = slotSuffix;
            this.slotLabel = normalizeSlotLabel(slotSuffix);
            this.uname = uname;
        }
    }

    /** Read only the tiny manifest from a local app-created capture ZIP. */
    static CaptureIdentity inspectCaptureIdentity(File zip) throws Exception {
        if (zip == null || !zip.isFile() || zip.length() < 4096) {
            throw new IOException("采集包不存在或异常小");
        }
        try (ZipFile archive = new ZipFile(zip)) {
            ZipEntry manifest = archive.getEntry("manifest.txt");
            if (manifest == null || manifest.isDirectory()
                    || manifest.getSize() < 48 || manifest.getSize() > 8192) {
                throw new IOException("采集包 manifest 缺失或异常");
            }
            String content;
            try (InputStream in = archive.getInputStream(manifest)) {
                content = readLimited(in, 8192);
            }
            Map<String, String> fields = new HashMap<>();
            for (String line : content.split("\\r?\\n")) {
                if (line.trim().isEmpty()) continue;
                int cut = line.indexOf('=');
                if (cut <= 0) throw new IOException("采集包 manifest 格式异常");
                String key = line.substring(0, cut);
                String value = line.substring(cut + 1).trim();
                if (fields.put(key, value) != null) {
                    throw new IOException("采集包 manifest 字段重复：" + key);
                }
            }
            if (!"pandora-ota-capture-v1".equals(fields.get("schema"))
                    || !"pandora".equals(fields.get("device"))
                    || !"pandora".equals(fields.get("ro.product.device"))) {
                throw new SecurityException("采集包设备身份不匹配");
            }
            String build = fields.getOrDefault("ro.mi.os.version.incremental", "");
            String slot = fields.getOrDefault("slot", "");
            String propSlot = fields.getOrDefault("ro.boot.slot_suffix", "");
            String uname = fields.getOrDefault("uname", "");
            if (!isValidHyperOsBuild(build)) {
                throw new SecurityException("采集包 HyperOS 版本无效");
            }
            String revision = fields.getOrDefault("software_revision", "");
            if (!revision.isEmpty()) {
                if (!revision.matches("D[0-9]{2}")
                        || !"USER_CONFIRMED_UPDATE_SCREEN".equals(fields.get("software_revision_source"))
                        || !build.equals(fields.get("software_revision_base_build"))
                        || build.matches(".*\\.D[0-9]{2}$")) {
                    throw new SecurityException("软件修订号证据与真实系统 Build 不一致");
                }
            } else if (fields.containsKey("software_revision_source")
                    || fields.containsKey("software_revision_base_build")) {
                throw new SecurityException("软件修订号证据不完整");
            }
            if (!("_a".equals(slot) || "_b".equals(slot)) || !slot.equals(propSlot)) {
                throw new SecurityException("采集包活动 slot 信息不一致");
            }
            if (uname.isEmpty()) throw new SecurityException("采集包内核版本缺失");
            return new CaptureIdentity(build, slot, uname);
        }
    }

    /** Only a two-digit .Dnn software suffix is accepted; never weaken channel/build checks. */
    static boolean isValidHyperOsBuild(String build) {
        return build != null && build.matches(
                "OS[0-9]+(?:\\.[0-9]+)+\\.[A-Z0-9]+(?:\\.D[0-9]{2})?");
    }

    static boolean isValidOptionalSoftwareRevision(String revision) {
        return revision != null && (revision.isEmpty() || revision.matches("D[0-9]{2}"));
    }

    /** Prevent accidentally uploading a stale local ZIP from another OTA/slot. */
    static CaptureIdentity requireCurrentCaptureIdentity(File zip) throws Exception {
        CaptureIdentity identity = inspectCaptureIdentity(zip);
        String currentBuild = currentOsVersion();
        String currentSlot = currentSlotLabel();
        if ("未知版本".equals(currentBuild) || "未知".equals(currentSlot)) {
            throw new IllegalStateException("无法读取当前系统版本或活动 slot，暂不允许上传");
        }
        if (!identity.build.equals(currentBuild)) {
            throw new SecurityException("已选采集包属于 " + identity.build
                    + "，当前系统为 " + currentBuild + "；为避免误提交旧 OTA，已阻止上传");
        }
        if (!identity.slotLabel.equals(currentSlot)) {
            throw new SecurityException("已选采集包来自 slot " + identity.slotLabel
                    + "，当前活动 slot 为 " + currentSlot + "；请重新采集当前系统");
        }
        return identity;
    }

    static String checkRoot() throws Exception {
        // Root Manager might show an interactive prompt or reject this app's UID.
        // Always bound the wait: a hung su must not lock the UI worker indefinitely.
        Process p = new ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start();
        try {
            if (!p.waitFor(45, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                p.waitFor();
                throw new IllegalStateException(
                        "等待 Root 授权超过 45 秒。请在 Root 管理器中检查本应用的超级用户授权");
            }
            String output = readLimited(p.getInputStream(), 4096);
            int rc = p.exitValue();
            if (rc != 0 || !output.contains("uid=0")) {
                String reason = output.trim();
                if (reason.length() > 180) reason = reason.substring(0, 180);
                throw new IllegalStateException("当前应用未获得 Root（退出码 " + rc + "）"
                        + (reason.isEmpty() ? "；请查看 Root 管理器的应用授权" : "：" + reason));
            }
            return output.trim();
        } finally {
            p.destroy();
        }
    }

    static File capture(Context ctx, boolean includeBoot) throws Exception {
        return capture(ctx, includeBoot, "");
    }

    static File capture(Context ctx, boolean includeBoot, String softwareRevision) throws Exception {
        if (!isValidOptionalSoftwareRevision(softwareRevision))
            throw new IllegalArgumentException("软件修订号必须留空或为 D02 这样的两位数字格式");
        if (!softwareRevision.isEmpty() && currentOsVersion().matches(".*\\.D[0-9]{2}$"))
            throw new IllegalArgumentException("系统属性已包含 Dxx 修订，无需人工重复标记");
        if (!isPandora()) throw new IllegalStateException("仅支持小米17 Pro（pandora）");
        if (!includeBoot) throw new IllegalArgumentException("OTA 完整采集必须包括当前 slot 四个启动分区");
        CapturePreflight.requireSpace(ctx); // fail before Root or partition reads
        checkRoot();

        File parent = captureDirectory(ctx);
        purgeStaleStaging(ctx); // recover previous interrupted operations before consuming disk again
        CapturePreflight.requireSpace(ctx); // recheck immediately before staging
        String stamp = timestamp();
        File dir = new File(parent, "staging-" + stamp);
        if (!dir.mkdir()) throw new IllegalStateException("临时采集目录创建失败");
        File zip = new File(parent, "pandora-os4-capture-" + stamp + ".zip");
        if (zip.exists()) {
            cleanStaging(parent, dir);
            throw new IllegalStateException("已有同名 ZIP，拒绝覆盖");
        }

        boolean complete = false;
        Exception failure = null;
        try {
            // dir is under the application's own external files directory, never a device partition.
            String sh = "set -eu\n"
                    + "umask 077\n"
                    + "OUT=" + shellQuote(dir.getAbsolutePath()) + "\n"
                    + "printf 'schema=pandora-ota-capture-v1\\nsource=ACTIVE_DEVICE_SNAPSHOT_NOT_VERIFIED_STOCK\\ndevice=pandora\\n' > \"$OUT/manifest.txt\"\n"
                    + "printf 'boot_source=ACTIVE_SLOT_UNVERIFIED_OEM\\nboot_oem_original_verified=NO\\nboot_role=GKI_KERNEL_ANALYSIS_INPUT\\n' >> \"$OUT/manifest.txt\"\n"
                    + "printf 'init_boot_source=ROOTED_ACTIVE_SLOT_MAY_BE_PATCHED\\ninit_boot_oem_original_verified=NO\\ninit_boot_role=DIAGNOSTIC_ONLY_NOT_FACTORY_IMAGE\\n' >> \"$OUT/manifest.txt\"\n"
                    + "printf 'vendor_boot_source=ACTIVE_SLOT_UNVERIFIED_OEM\\ndtbo_source=ACTIVE_SLOT_UNVERIFIED_OEM\\n' >> \"$OUT/manifest.txt\"\n"
                    + "for key in ro.product.device ro.mi.os.version.incremental ro.build.version.release ro.build.version.incremental ro.build.fingerprint ro.boot.slot_suffix; do "
                    + "printf '%s=' \"$key\" >> \"$OUT/manifest.txt\"; getprop \"$key\" >> \"$OUT/manifest.txt\"; done\n"
                    + (softwareRevision.isEmpty() ? "" :
                       "printf 'software_revision=" + softwareRevision
                       + "\nsoftware_revision_source=USER_CONFIRMED_UPDATE_SCREEN\nsoftware_revision_base_build=' >> \"$OUT/manifest.txt\"; "
                       + "getprop ro.mi.os.version.incremental >> \"$OUT/manifest.txt\"\n")
                    + "uname -r > \"$OUT/kernel-release.txt\"\n"
                    + "cat /proc/version > \"$OUT/kernel-version.txt\"\n"
                    + "(cat /proc/modules || true) > \"$OUT/loaded-modules.txt\"\n"
                    + "(cat /sys/kernel/security/lsm || true) > \"$OUT/lsm.txt\"\n"
                    + "(cat /proc/cgroups || true) > \"$OUT/cgroups.txt\"\n"
                    + "modules=''\n"
                    + "for d in vendor_dlkm/lib/modules vendor/lib/modules odm_dlkm/lib/modules system_dlkm/lib/modules; do "
                    + "if [ -d \"/$d\" ]; then modules=\"$modules $d\"; fi; done\n"
                    + "if [ -z \"$modules\" ]; then echo 'OEM module paths missing' >&2; exit 21; fi\n"
                    + "(cd / && tar -chf \"$OUT/oem-modules.tar\" $modules)\n"
                    + "slot=\"$(getprop ro.boot.slot_suffix)\"\n"
                    + "case \"$slot\" in _a|_b) ;; *) echo 'invalid slot' >&2; exit 12;; esac\n"
                    + "for part in boot init_boot vendor_boot dtbo; do "
                    + "dev=\"/dev/block/by-name/$part$slot\"; "
                    + "test -r \"$dev\" || { echo \"missing $dev\" >&2; exit 13; }; "
                    + "dd if=\"$dev\" of=\"$OUT/$part$slot.img\" bs=1048576 2>/dev/null; "
                    + "test \"$(wc -c < \"$OUT/$part$slot.img\")\" -ge 4096 || exit 14; "
                    + "done\n"
                    + "echo \"slot=$slot\" >> \"$OUT/manifest.txt\"\n"
                    + "printf 'uname=' >> \"$OUT/manifest.txt\"; uname -r >> \"$OUT/manifest.txt\"\n"
                    + "chmod 644 \"$OUT\"/*\n";

            Process p = new ProcessBuilder("su", "-c", sh).redirectErrorStream(true).start();
            String shellLog = readLimited(p.getInputStream(), 8192);
            int rc = p.waitFor();
            if (rc != 0) throw new IOException("Root 只读采集失败 (code " + rc + "): " + shellLog);

            File[] produced = dir.listFiles();
            if (produced == null || produced.length < 8)
                throw new IOException("采集物缺失，拒绝生成部分 ZIP");
            verifyRequiredStaging(dir);
            int zipped = 0;
            try (ZipOutputStream out = new ZipOutputStream(
                    new BufferedOutputStream(new FileOutputStream(zip), 256 * 1024))) {
                byte[] buffer = new byte[256 * 1024];
                for (File f : produced) {
                    if (!f.isFile() || !f.getName().matches("[A-Za-z0-9._-]+"))
                        throw new SecurityException("存在非预期采集物：" + f.getName());
                    out.putNextEntry(new ZipEntry(f.getName()));
                    try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
                        int n;
                        while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                    }
                    out.closeEntry();
                    zipped++;
                }
            }
            if (zipped != produced.length || !zip.isFile() || zip.length() < 4096)
                throw new IOException("ZIP 内容缺失或异常小");

            // Only return success when ALL intermediate source files have been removed.
            cleanStaging(parent, dir);
            complete = true;
            return zip;
        } catch (Exception e) {
            failure = e;
            throw e;
        } finally {
            if (!complete && zip.isFile() && !zip.delete()) {
                IOException cleanup = new IOException("失败的临时 ZIP 无法删除：" + zip.getName());
                if (failure != null) failure.addSuppressed(cleanup);
                else throw cleanup;
            }
            // An interrupted/failed capture must not accumulate large root-owned TAR/IMG files.
            if (dir.exists()) {
                try {
                    cleanStaging(parent, dir);
                } catch (Exception cleanup) {
                    if (failure != null) failure.addSuppressed(cleanup);
                    else throw cleanup;
                }
            }
        }
    }

    private static void verifyRequiredStaging(File dir) throws Exception {
        requireFile(dir, "manifest.txt", 48);
        requireFile(dir, "kernel-release.txt", 12);
        requireFile(dir, "oem-modules.tar", 1024);
        File[] files = dir.listFiles();
        if (files == null) throw new IOException("采集目录丢失");
        for (String part : new String[]{"boot", "init_boot", "vendor_boot", "dtbo"}) {
            boolean exists = false;
            for (String slot : new String[]{"_a", "_b"}) {
                File image = new File(dir, part + slot + ".img");
                if (image.isFile() && image.length() >= 4096) exists = true;
            }
            if (!exists) throw new IOException("缺少分区镜像：" + part);
        }
    }

    private static void requireFile(File dir, String name, long minLength) throws IOException {
        File f = new File(dir, name);
        if (!f.isFile() || f.length() < minLength)
            throw new IOException("采集内容不完整：" + name);
    }

    static File captureDirectory(Context ctx) throws IOException {
        File external = ctx.getExternalFilesDir(null);
        if (external == null) throw new IOException("应用文件目录不可用");
        File parent = new File(external, "captures");
        if (!parent.isDirectory() && !parent.mkdirs())
            throw new IOException("无法创建应用采集目录");
        return parent.getCanonicalFile();
    }

    /**
     * Only the application's flat staging-YYYYMMDD-HHmmss-SSS directories are removable.
     * The final ZIP and any other user data are NEVER touched by this method.
     */
    static void purgeStaleStaging(Context ctx) throws Exception {
        File parent = captureDirectory(ctx);
        File[] children = parent.listFiles();
        if (children == null) return;
        long cutoff = System.currentTimeMillis() - STALE_AFTER_MS;
        for (File child : children) {
            if (child.getName().matches("staging-\\d{8}-\\d{6}-\\d{3}")
                    && child.isDirectory() && child.lastModified() < cutoff) {
                cleanStaging(parent, child);
            }
        }
    }

    private static void cleanStaging(File parent, File dir) throws Exception {
        // Validate both paths, but never follow a child symlink or recurse.
        if (!dir.getName().matches("staging-\\d{8}-\\d{6}-\\d{3}")
                || !dir.getCanonicalFile().getParentFile().equals(parent.getCanonicalFile())
                || !dir.isDirectory()) {
            throw new SecurityException("拒绝清理非应用临时目录");
        }
        File[] files = dir.listFiles();
        if (files == null) throw new IOException("无法枚举临时目录");
        for (File file : files) {
            if (!file.getParentFile().getCanonicalFile().equals(dir.getCanonicalFile())
                    || !file.getName().matches("[A-Za-z0-9._-]+")
                    || file.isDirectory()) {
                throw new SecurityException("临时目录包含异常文件，未执行清理");
            }
            if (!file.delete()) {
                // On some Root implementations the captured files are root-owned.
                // Restrict this fallback to a single validated flat file in our staging dir.
                Process p = new ProcessBuilder("su", "-c",
                        "rm -f -- " + shellQuote(file.getAbsolutePath()))
                        .redirectErrorStream(true).start();
                readLimited(p.getInputStream(), 1024);
                if (p.waitFor() != 0 || file.exists())
                    throw new IOException("无法删除临时采集物：" + file.getName());
            }
        }
        if (!dir.delete()) {
            Process p = new ProcessBuilder("su", "-c",
                    "rmdir -- " + shellQuote(dir.getAbsolutePath()))
                    .redirectErrorStream(true).start();
            readLimited(p.getInputStream(), 1024);
            if (p.waitFor() != 0 || dir.exists())
                throw new IOException("无法删除临时采集目录");
        }
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String timestamp() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("UTC"));
        return fmt.format(new Date());
    }

    private static String readLimited(InputStream input, int limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] b = new byte[1024];
        int n;
        while ((n = input.read(b)) != -1) {
            if (out.size() < limit) out.write(b, 0, Math.min(n, limit - out.size()));
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }
}
