package com.wekingchen.pandoraota;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class PreflightEmulatorTest {
    private static final String REPO = "wekingchen/GKI_KernelSU_SUSFS";
    @Test public void availableSpaceThresholdsAreExact() throws Exception {
        long gib = CapturePreflight.GIB;
        assertEquals(CapturePreflight.Space.BLOCK, CapturePreflight.space(-1));
        assertEquals(CapturePreflight.Space.BLOCK, CapturePreflight.space(gib - 1));
        assertEquals(CapturePreflight.Space.WARN, CapturePreflight.space(gib));
        assertEquals(CapturePreflight.Space.WARN, CapturePreflight.space(2 * gib - 1));
        assertEquals(CapturePreflight.Space.READY, CapturePreflight.space(2 * gib));
        android.content.Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue(CapturePreflight.available(ctx) > 0);
        assertTrue(RootCollector.captureDirectory(ctx).isDirectory());
    }
    @Test public void firstUseAndBuildOrFingerprintChangesRemindButSameBuildDoesNot() {
        String current = CapturePreflight.buildIdentity("OS4.0.5.0.XBLCNXM", "fingerprint-a");
        assertTrue(CapturePreflight.buildChanged("", current));
        assertFalse(CapturePreflight.buildChanged(current, current));
        assertTrue(CapturePreflight.buildChanged(current,
                CapturePreflight.buildIdentity("OS4.0.6.0.XBLCNXM", "fingerprint-a")));
        assertTrue(CapturePreflight.buildChanged(current,
                CapturePreflight.buildIdentity("OS4.0.5.0.XBLCNXM", "fingerprint-b")));
        assertFalse(CapturePreflight.buildChanged(current, CapturePreflight.buildIdentity("未知版本", "x")));
    }
    @Test public void d02BuildSyntaxIsAcceptedButMalformedSuffixFailsClosed() {
        assertTrue(RootCollector.isValidHyperOsBuild("OS4.0.5.0.XBLCNXM"));
        assertTrue(RootCollector.isValidHyperOsBuild("OS4.0.5.0.XBLCNXM.D02"));
        assertFalse(RootCollector.isValidHyperOsBuild("OS4.0.5.0.XBLCNXM.D2"));
        assertFalse(RootCollector.isValidHyperOsBuild("OS4.0.5.0.XBLCNXM.D02.EXTRA"));
        assertFalse(RootCollector.isValidHyperOsBuild("OS4.0.5.0.XBLCNXM.D002"));
        assertFalse(RootCollector.isValidHyperOsBuild(null));
    }
    @Test public void manuallyConfirmedSoftwareRevisionRequiresTwoDigits() {
        assertTrue(RootCollector.isValidOptionalSoftwareRevision(""));
        assertTrue(RootCollector.isValidOptionalSoftwareRevision("D02"));
        assertTrue(RootCollector.isValidOptionalSoftwareRevision("D99"));
        assertFalse(RootCollector.isValidOptionalSoftwareRevision("D2"));
        assertFalse(RootCollector.isValidOptionalSoftwareRevision("D002"));
        assertFalse(RootCollector.isValidOptionalSoftwareRevision("OS4.0.5.0.XBLCNXM.D02"));
        assertFalse(RootCollector.isValidOptionalSoftwareRevision(null));
    }
    @Test public void acknowledgementSurvivesStoreReload() {
        android.content.Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        android.content.SharedPreferences prefs = ctx.getSharedPreferences("private_config", 0);
        String old = prefs.getString("acknowledged_build", null);
        try {
            new SecretStore(ctx).acknowledgeBuild("synthetic-build");
            assertEquals("synthetic-build", new SecretStore(ctx).acknowledgedBuild());
        } finally {
            if (old == null) prefs.edit().remove("acknowledged_build").commit();
            else prefs.edit().putString("acknowledged_build", old).commit();
        }
    }
    static class Transport implements GitHubUploader.ConnectionFactory {
        final List<String> calls = new ArrayList<>();
        String tag;
        String name;
        int repoStatus = 200, createStatus = 201, deleteStatus = 204, dispatchStatus = 204;
        boolean offline, mismatch;
        String repoPadding = "", draftPadding = "";
        public HttpURLConnection open(URL url) throws Exception {
            if (offline) throw new IOException("synthetic network timeout");
            return new HttpURLConnection(url) {
                final ByteArrayOutputStream output = new ByteArrayOutputStream();
                public void connect() {}
                public void disconnect() {}
                public boolean usingProxy() { return false; }
                public OutputStream getOutputStream() { return output; }
                public int getResponseCode() throws IOException {
                    calls.add(method + " " + url.getPath());
                    assertEquals("https", url.getProtocol());
                    assertEquals("Bearer synthetic", getRequestProperty("Authorization"));
                    assertFalse(getInstanceFollowRedirects());
                    if (url.getPath().endsWith("/dispatches")) {
                        assertTrue(output.toString().contains("pandora_ota_preflight_v1"));
                        assertFalse(output.toString().contains("pandora_ota_upload_v1"));
                        return dispatchStatus;
                    }
                    if (method.equals("DELETE")) return deleteStatus;
                    if (method.equals("POST")) {
                        try {
                            JSONObject data = new JSONObject(output.toString());
                            tag = data.getString("tag_name"); name = data.getString("name");
                            assertTrue(data.getBoolean("draft"));
                        } catch (Exception e) { throw new IOException(e); }
                        return createStatus;
                    }
                    return url.getPath().endsWith("/73") ? 200 : repoStatus;
                }
                public InputStream getErrorStream() { return bytes("{}"); }
                public InputStream getInputStream() {
                    if (method.equals("DELETE") || url.getPath().endsWith("/dispatches")) return bytes("");
                    if (url.getPath().endsWith("/73")) {
                        return bytes("{\"id\":73,\"draft\":true,\"assets\":[],\"tag_name\":\""
                                + (mismatch ? "wrong" : tag) + "\",\"name\":\"" + name
                                + "\",\"padding\":\"" + draftPadding + "\"}");
                    }
                    return bytes(method.equals("POST") ? "{\"id\":73}"
                            : "{\"full_name\":\"" + REPO + "\",\"permissions\":{\"push\":true},"
                            + "\"padding\":\"" + repoPadding + "\"}");
                }
            };
        }
        InputStream bytes(String text) { return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }
    }
    private void mustFail(Transport t) throws Exception {
        try { GitHubUploader.verifyConnection(REPO, "synthetic", t); fail("must fail closed"); }
        catch (IOException expected) { }
    }
    @Test public void liveProofCreatesReadsDeletesOnlyNewDraftAndProbesNotification() throws Exception {
        Transport t = new Transport();
        GitHubUploader.verifyConnection(REPO, "synthetic", t);
        assertEquals(Arrays.asList("GET /repos/" + REPO, "POST /repos/" + REPO + "/releases",
                "GET /repos/" + REPO + "/releases/73", "DELETE /repos/" + REPO + "/releases/73",
                "POST /repos/" + REPO + "/dispatches"), t.calls);
    }
    private static String padding(int size) {
        char[] chars = new char[size];
        Arrays.fill(chars, 'x');
        return new String(chars);
    }
    @Test public void repositoryMetadataLargerThan16KiBIsNotTruncated() throws Exception {
        Transport t = new Transport();
        t.repoPadding = padding(24 * 1024);
        GitHubUploader.verifyConnection(REPO, "synthetic", t);
        assertEquals(5, t.calls.size());
    }
    @Test public void draftMetadataLargerThan16KiBIsNotTruncatedAndDraftIsCleaned() throws Exception {
        Transport t = new Transport();
        t.draftPadding = padding(24 * 1024);
        GitHubUploader.verifyConnection(REPO, "synthetic", t);
        assertTrue(t.calls.get(3).startsWith("DELETE"));
        assertEquals(5, t.calls.size());
    }
    @Test public void oversizedSuccessResponseFailsExplicitlyWithoutTryingToParsePartialJson() throws Exception {
        Transport t = new Transport();
        t.repoPadding = padding(2 * 1024 * 1024);
        try {
            GitHubUploader.verifyConnection(REPO, "synthetic", t);
            fail("oversized success response must fail closed");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("超过 2 MiB"));
        }
        assertEquals(1, t.calls.size());
    }
    @Test public void repositoryPushMetadataDoesNotProveTokenWritePermission() throws Exception {
        Transport t = new Transport(); t.createStatus = 403; mustFail(t);
        assertEquals(2, t.calls.size());
    }
    @Test public void expiredTokenCannotReachWriteProbe() throws Exception {
        Transport t = new Transport(); t.repoStatus = 401; mustFail(t); assertEquals(1, t.calls.size());
    }
    @Test public void offlineCannotBeReportedAsVerified() throws Exception {
        Transport t = new Transport(); t.offline = true; mustFail(t); assertTrue(t.calls.isEmpty());
    }
    @Test public void cleanupFailureBlocksCollectionAndIdentifiesOnlyCreatedDraft() throws Exception {
        Transport t = new Transport(); t.deleteStatus = 403;
        try { GitHubUploader.verifyConnection(REPO, "synthetic", t); fail(); }
        catch (IOException e) { assertTrue(e.getMessage().contains("#73")); }
        assertEquals(4, t.calls.size());
    }
    @Test public void notificationWriteDenialFailsAfterDraftIsCleaned() throws Exception {
        Transport t = new Transport(); t.dispatchStatus = 403; mustFail(t);
        assertTrue(t.calls.get(3).startsWith("DELETE"));
    }
    @Test public void mismatchedProofStillCleansOnlyNewDraftAndNeverDispatches() throws Exception {
        Transport t = new Transport(); t.mismatch = true; mustFail(t);
        assertEquals(4, t.calls.size()); assertTrue(t.calls.get(3).startsWith("DELETE"));
    }
}
