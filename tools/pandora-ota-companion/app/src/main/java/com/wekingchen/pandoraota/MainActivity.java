package com.wekingchen.pandoraota;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.MotionEvent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.*;
import java.io.File;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Single-screen, four-step OTA companion. No background transfer is claimed. */
public final class MainActivity extends Activity {
    private static final int BACKGROUND = Color.rgb(247, 249, 253);
    private static final int INK = Color.rgb(24, 34, 53);
    private static final int MUTED = Color.rgb(112, 124, 145);
    private static final int TEAL = Color.rgb(35, 105, 230);
    private static final int LIGHT_TEAL = Color.rgb(233, 241, 255);
    private static final int FIELD = Color.rgb(243, 246, 252);
    private static final int BORDER = Color.rgb(230, 235, 244);
    private static final int NOTICE = Color.rgb(238, 244, 255);
    private static final int WARNING = Color.rgb(173, 86, 45);
    private static final int WHITE = Color.WHITE;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SecretStore store;
    private EditText repoField, tokenField;
    private CheckBox bootOption;
    private Button saveButton, clearButton, rootButton, collectButton, uploadButton;
    private Button deleteButton, openReleaseButton, chooseArchiveButton, retryNoticeButton, checkIntakeButton;
    private TextView tokenState, repoState, fileState, uploadStage, uploadNumbers, uploadSpeed;
    private TextView percent, receiptState, message, rootStatus;
    private ProgressBar uploadBar, taskSpinner;
    private ScrollView workScroll, recordsScroll, settingsScroll;
    private LinearLayout archiveListing, statusBanner, appBar, recordCard, recordActionRow, helpRow;
    private TextView deviceStatus, rootPill, intakeSummary, recordFileState, localCount, capturePolicySub;
    private TextView workUploadedMetric, workRepoMetric, workGitHubMetric;
    private TextView recordCountMetric, recordUploadedMetric, recordGitHubMetric;
    private final LinearLayout[] navItems = new LinearLayout[3];
    private final ImageView[] navIcons = new ImageView[3];
    private final TextView[] navLabels = new TextView[3];
    private int activeTab;
    private boolean busy;
    private boolean foreground;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ForegroundIntakeTracker tracker = new ForegroundIntakeTracker(
            new ForegroundIntakeTracker.Scheduler() {
                public void post(Runnable task, long delay) { handler.postDelayed(task, delay); }
                public void cancel(Runnable task) { handler.removeCallbacks(task); }
            }, worker);
    private String githubStatus = "未验证";
    private TextView stageState, stageCheckedAt, stageDetails, feedbackHint;
    private Button stageRefresh, stageSource;
    private TextView preflightState;
    private AlertDialog buildReminder;

    private volatile String liveOsVersion = "正在读取";
    private volatile String liveSlotLabel = "…";

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new SecretStore(this);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        // A native Android shell: persistent status, three actual destinations,
        // bottom navigation and a single focused action at a time.
        LinearLayout shell = vertical();
        GradientDrawable shellBackground = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(251, 253, 255), Color.rgb(244, 248, 255), Color.rgb(251, 253, 255)});
        shell.setBackground(shellBackground);
        shell.setClipChildren(false);
        shell.setClipToPadding(false);
        setContentView(shell);
        if (Build.VERSION.SDK_INT >= 35) {
            getWindow().getDecorView().setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
                shell.setPadding(0, bars.top, 0, bars.bottom);
                return insets;
            });
        }
        appBar = new LinearLayout(this);
        appBar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        appBar.setPadding(dp(18), dp(7), dp(18), dp(7));
        shell.addView(appBar, new LinearLayout.LayoutParams(-1, dp(58)));

        ImageView brand = new ImageView(this);
        brand.setImageResource(R.drawable.ic_pandora_ota);
        brand.setContentDescription("Pandora OTA");
        brand.setScaleType(ImageView.ScaleType.FIT_CENTER);
        appBar.addView(brand, new LinearLayout.LayoutParams(dp(34), dp(34)));

        LinearLayout brandCopy = vertical();
        LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(0, -2, 1);
        brandParams.leftMargin = dp(10);
        appBar.addView(brandCopy, brandParams);
        brandCopy.addView(text("Pandora OTA", 19, INK, true));
        brandCopy.addView(text("OS4 · Reliable · Secure", 9.5f, MUTED, false), blockParams(dp(1)));

        statusBanner = new LinearLayout(this);
        statusBanner.setGravity(android.view.Gravity.CENTER_VERTICAL);
        statusBanner.setBackground(round(NOTICE, dp(14)));
        statusBanner.setPadding(dp(13), dp(10), dp(13), dp(10));
        LinearLayout.LayoutParams noticeParams = new LinearLayout.LayoutParams(-1, -2);
        noticeParams.setMargins(dp(18), 0, dp(18), dp(6));
        shell.addView(statusBanner, noticeParams);
        taskSpinner = new ProgressBar(this, null, android.R.attr.progressBarStyleSmall);
        taskSpinner.setIndeterminateTintList(ColorStateList.valueOf(TEAL));
        statusBanner.addView(taskSpinner, new LinearLayout.LayoutParams(dp(18), dp(18)));
        message = text("", 12, INK, false);
        message.setMaxLines(3);
        message.setTextIsSelectable(true);
        LinearLayout.LayoutParams msgParams = new LinearLayout.LayoutParams(0, -2, 1);
        msgParams.leftMargin = dp(9);
        statusBanner.addView(message, msgParams);
        taskSpinner.setVisibility(View.GONE);
        statusBanner.setVisibility(View.GONE);

        FrameLayout destinations = new FrameLayout(this);
        shell.addView(destinations, new LinearLayout.LayoutParams(-1, 0, 1));
        workScroll = page(destinations);
        recordsScroll = page(destinations);
        settingsScroll = page(destinations);
        LinearLayout work = pageContents(workScroll);
        LinearLayout records = pageContents(recordsScroll);
        LinearLayout settings = pageContents(settingsScroll);

        // Workbench — premium hero + current capture, aligned to the commercial visual system.
        FrameLayout hero = new FrameLayout(this);
        GradientDrawable heroBackground = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.WHITE, Color.rgb(235, 243, 255), Color.rgb(224, 237, 255)});
        heroBackground.setCornerRadius(dp(30));
        hero.setBackground(heroBackground);
        hero.setElevation(dp(2));
        work.addView(hero, new LinearLayout.LayoutParams(-1, dp(196)));

        LinearLayout heroCopy = vertical();
        heroCopy.setPadding(dp(20), dp(20), dp(18), 0);
        FrameLayout.LayoutParams heroCopyParams = new FrameLayout.LayoutParams(-1, -2);
        heroCopyParams.rightMargin = dp(118);
        hero.addView(heroCopy, heroCopyParams);

        heroCopy.addView(text("Xiaomi 17 Pro", 24, INK, true));
        deviceStatus = text(RootCollector.isPandora()
                        ? "正在读取系统版本…"
                        : "pandora · 当前设备不受支持",
                11, RootCollector.isPandora() ? MUTED : WARNING, false);
        deviceStatus.setSingleLine(true);
        heroCopy.addView(deviceStatus, blockParams(dp(7)));

        LinearLayout rootRow = new LinearLayout(this);
        rootRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        heroCopy.addView(rootRow, blockParams(dp(14)));
        rootPill = text("Root 未检查", 11, MUTED, true);
        rootPill.setPadding(dp(11), dp(6), dp(11), dp(6));
        rootPill.setBackground(round(FIELD, dp(18)));
        rootRow.addView(rootPill);
        rootButton = button("检查", false);
        rootButton.setMinHeight(0);
        rootButton.setMinimumHeight(0);
        rootButton.setPadding(dp(12), 0, dp(12), 0);
        LinearLayout.LayoutParams rootActionParams = new LinearLayout.LayoutParams(-2, dp(34));
        rootActionParams.leftMargin = dp(8);
        rootRow.addView(rootButton, rootActionParams);

        rootStatus = text("尚未检测 Root；如不弹窗，请在 Root 管理器手动授权。",
                11, MUTED, false);
        rootStatus.setTextIsSelectable(true);
        rootStatus.setVisibility(View.GONE);
        heroCopy.addView(rootStatus, blockParams(dp(9)));

        ImageView deviceArt = new ImageView(this);
        deviceArt.setImageResource(R.drawable.device_pandora_render);
        deviceArt.setScaleType(ImageView.ScaleType.FIT_CENTER);
        FrameLayout.LayoutParams deviceArtParams = new FrameLayout.LayoutParams(dp(114), dp(160));
        deviceArtParams.gravity = android.view.Gravity.TOP | android.view.Gravity.END;
        deviceArtParams.topMargin = dp(4);
        deviceArtParams.rightMargin = dp(7);
        hero.addView(deviceArt, deviceArtParams);

        bootOption = new CheckBox(this);
        bootOption.setChecked(true);
        bootOption.setEnabled(false);
        bootOption.setVisibility(View.GONE);

        collectButton = button("开始采集   →", true);
        collectButton.setBackground(primaryGradientBackground());
        LinearLayout.LayoutParams captureParams = new LinearLayout.LayoutParams(-1, dp(58));
        captureParams.topMargin = dp(12);
        work.addView(collectButton, captureParams);

        rootButton.setOnClickListener(v -> {
            rootStatus.setVisibility(View.VISIBLE);
            rootStatus.setText("正在请求 Root 授权…");
            rootPill.setText("Root 检查中");
            runTask("正在请求 Root 授权…", () -> {
                try {
                    String identityResult = RootCollector.checkRoot();
                    runOnUiThread(() -> {
                        rootPill.setText("✓  Root 已授权");
                        rootPill.setTextColor(Color.rgb(31, 117, 93));
                        rootPill.setBackground(round(Color.rgb(229, 247, 240), dp(18)));
                        rootButton.setVisibility(View.GONE);
                        rootStatus.setVisibility(View.GONE);
                        rootStatus.setText("授权成功 · " + identityResult);
                    });
                    return "Root 已授权，可以开始采集";
                } catch (Exception e) {
                    String explanation = safeMessage(e);
                    runOnUiThread(() -> {
                        rootPill.setText("Root 未授权");
                        rootPill.setTextColor(WARNING);
                        rootStatus.setText("授权失败 · " + explanation);
                    });
                    throw e;
                }
            });
        });
        collectButton.setOnClickListener(v -> startCapturePreflight());
        preflightState = text("采集前将检查空间、GitHub 连接与写权限", 11, MUTED, false);
        work.addView(preflightState, blockParams(dp(8)));

        LinearLayout recentHeader = new LinearLayout(this);
        recentHeader.setGravity(android.view.Gravity.CENTER_VERTICAL);
        work.addView(recentHeader, blockParams(dp(24)));
        recentHeader.addView(text("最近采集", 19, INK, true),
                new LinearLayout.LayoutParams(0, -2, 1));
        TextView detailsHint = text("查看全部", 12, TEAL, true);
        detailsHint.setClickable(true);
        detailsHint.setFocusable(true);
        detailsHint.setPadding(dp(8), dp(5), dp(8), dp(5));
        detailsHint.setBackground(touchBackground(Color.TRANSPARENT, dp(12)));
        detailsHint.setOnClickListener(v -> selectTab(1));
        recentHeader.addView(detailsHint);

        LinearLayout recentCard = vertical();
        recentCard.setBackground(round(WHITE, dp(20)));
        recentCard.setPadding(dp(15), dp(14), dp(15), dp(15));
        recentCard.setElevation(dp(1));
        work.addView(recentCard, blockParams(dp(11)));

        LinearLayout fileRow = new LinearLayout(this);
        fileRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        recentCard.addView(fileRow);
        fileRow.addView(iconBox(R.drawable.ic_file_zip, dp(52), dp(18)));
        fileState = text("", 14, INK, true);
        fileState.setTextIsSelectable(true);
        LinearLayout.LayoutParams currentFileParams = new LinearLayout.LayoutParams(0, -2, 1);
        currentFileParams.leftMargin = dp(14);
        fileRow.addView(fileState, currentFileParams);

        uploadButton = button("上传到 GitHub", false);
        uploadButton.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_external_simple, 0, 0, 0);
        uploadButton.setCompoundDrawablePadding(dp(8));
        recentCard.addView(uploadButton, blockParams(dp(15)));
        uploadButton.setOnClickListener(v -> startUpload());

        LinearLayout progressHeader = new LinearLayout(this);
        progressHeader.setGravity(android.view.Gravity.CENTER_VERTICAL);
        recentCard.addView(progressHeader, blockParams(dp(14)));
        uploadStage = text("等待上传", 12, MUTED, true);
        progressHeader.addView(uploadStage, new LinearLayout.LayoutParams(0, -2, 1));
        percent = text("0%", 16, TEAL, true);
        progressHeader.addView(percent);
        uploadBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        uploadBar.setMax(100);
        uploadBar.setProgressTintList(ColorStateList.valueOf(TEAL));
        uploadBar.setProgressBackgroundTintList(ColorStateList.valueOf(LIGHT_TEAL));
        recentCard.addView(uploadBar, new LinearLayout.LayoutParams(-1, dp(5)));
        uploadNumbers = text("0 B / 0 B", 11, MUTED, false);
        recentCard.addView(uploadNumbers, blockParams(dp(8)));
        uploadSpeed = text("速率 --   ·   耗时 --", 11, MUTED, false);
        recentCard.addView(uploadSpeed, blockParams(dp(3)));
        intakeSummary = text("", 12, MUTED, false);
        recentCard.addView(intakeSummary, blockParams(dp(10)));
        checkIntakeButton = button("查询处理结果", false);
        recentCard.addView(checkIntakeButton, blockParams(dp(9)));
        checkIntakeButton.setOnClickListener(v -> checkBackendIntake());

        LinearLayout workMetrics = new LinearLayout(this);
        workMetrics.setOrientation(LinearLayout.HORIZONTAL);
        workMetrics.setBackground(round(WHITE, dp(20)));
        workMetrics.setPadding(dp(3), dp(4), dp(3), dp(4));
        workMetrics.setElevation(dp(1));
        work.addView(workMetrics, blockParams(dp(14)));
        LinearLayout uploadedMetric = metricTile("已上传", R.drawable.ic_external_simple);
        LinearLayout repoMetric = metricTile("仓库", R.drawable.ic_repo);
        LinearLayout githubMetric = metricTile("GitHub", R.drawable.ic_key_simple);
        uploadedMetric.setBackgroundColor(Color.TRANSPARENT);
        uploadedMetric.setElevation(0);
        repoMetric.setBackgroundColor(Color.TRANSPARENT);
        repoMetric.setElevation(0);
        githubMetric.setBackgroundColor(Color.TRANSPARENT);
        githubMetric.setElevation(0);
        workUploadedMetric = metricValue(uploadedMetric);
        workRepoMetric = metricValue(repoMetric);
        workGitHubMetric = metricValue(githubMetric);
        LinearLayout.LayoutParams metricLp = new LinearLayout.LayoutParams(0, dp(82), 1);
        LinearLayout.LayoutParams metricMidLp = new LinearLayout.LayoutParams(0, dp(82), 1);
        metricMidLp.leftMargin = dp(8);
        LinearLayout.LayoutParams metricLastLp = new LinearLayout.LayoutParams(0, dp(82), 1);
        metricLastLp.leftMargin = dp(8);
        workMetrics.addView(uploadedMetric, metricLp);
        workMetrics.addView(repoMetric, metricMidLp);
        workMetrics.addView(githubMetric, metricLastLp);

        LinearLayout feedbackCard = surfaceCard(work, dp(18), dp(18));
        feedbackCard.addView(sectionHeading("", "真机测试反馈", "电脑临时启动后，在手机完成检查与提交"));
        Button feedbackButton = button("填写测试结果   →", false);
        feedbackCard.addView(feedbackButton, blockParams(dp(14)));
        feedbackButton.setOnClickListener(v -> startActivity(new Intent(this, FeedbackActivity.class)));
        feedbackHint = text("仅在电脑临时启动新的待测镜像并完成实际测试后提交。", 11, MUTED, false);
        feedbackCard.addView(feedbackHint, blockParams(dp(9)));

        LinearLayout stageCard = surfaceCard(work, dp(18), dp(18));
        stageCard.addView(sectionHeading("", "OTA 全链路状态", "当前 GitHub 固定输入 · 来源校验后展示"));
        stageState = text("尚未查询\n点击刷新，查看 GKI、ABI、待测、基线与 LKG 阶段。", 13, MUTED, false);
        stageCard.addView(stageState, blockParams(dp(14)));
        stageCheckedAt = text("", 11, MUTED, false);
        stageCheckedAt.setVisibility(View.GONE);
        stageCard.addView(stageCheckedAt, blockParams(dp(8)));
        stageDetails = text("查看核验详情  ›", 12, TEAL, true);
        stageDetails.setVisibility(View.GONE);
        stageDetails.setClickable(true);
        stageDetails.setFocusable(true);
        stageDetails.setPadding(dp(6), dp(8), dp(6), dp(8));
        stageDetails.setBackground(touchBackground(Color.TRANSPARENT, dp(12)));
        stageCard.addView(stageDetails, blockParams(dp(8)));
        stageRefresh = button("刷新可信状态", false);
        stageCard.addView(stageRefresh, blockParams(dp(14)));
        stageRefresh.setOnClickListener(v -> refreshStage());
        stageSource = button("查看状态来源   ↗", false);
        stageSource.setVisibility(View.GONE);
        stageCard.addView(stageSource, blockParams(dp(8)));

        // Records — status-led, concise and action-oriented.
        records.addView(pageHeading("记录", "采集文件与 GitHub 记录"));

        LinearLayout recordOverview = new LinearLayout(this);
        recordOverview.setOrientation(LinearLayout.HORIZONTAL);
        records.addView(recordOverview, blockParams(dp(18)));
        LinearLayout collectedTile = metricTile("采集文件", R.drawable.ic_file_outline);
        LinearLayout uploadedTile = metricTile("已上传", R.drawable.ic_external_simple);
        LinearLayout githubTile = metricTile("GitHub", R.drawable.ic_repo);
        recordCountMetric = metricValue(collectedTile);
        recordUploadedMetric = metricValue(uploadedTile);
        recordGitHubMetric = metricValue(githubTile);
        LinearLayout.LayoutParams overviewLp = new LinearLayout.LayoutParams(0, dp(84), 1);
        LinearLayout.LayoutParams overviewMidLp = new LinearLayout.LayoutParams(0, dp(84), 1);
        overviewMidLp.leftMargin = dp(8);
        LinearLayout.LayoutParams overviewLastLp = new LinearLayout.LayoutParams(0, dp(84), 1);
        overviewLastLp.leftMargin = dp(8);
        recordOverview.addView(collectedTile, overviewLp);
        recordOverview.addView(uploadedTile, overviewMidLp);
        recordOverview.addView(githubTile, overviewLastLp);

        recordCard = vertical();
        recordCard.setBackground(round(WHITE, dp(28)));
        recordCard.setPadding(dp(20), dp(20), dp(20), dp(21));
        recordCard.setElevation(dp(1));
        records.addView(recordCard, blockParams(dp(18)));

        LinearLayout activeFileRow = new LinearLayout(this);
        activeFileRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        recordCard.addView(activeFileRow);
        activeFileRow.addView(iconBox(R.drawable.ic_file_zip, dp(58), dp(18)));
        recordFileState = text("", 15, INK, true);
        recordFileState.setTextIsSelectable(true);
        LinearLayout.LayoutParams recordTextParams = new LinearLayout.LayoutParams(0, -2, 1);
        recordTextParams.leftMargin = dp(14);
        activeFileRow.addView(recordFileState, recordTextParams);

        chooseArchiveButton = button("切换", false);
        chooseArchiveButton.setMinHeight(0);
        chooseArchiveButton.setMinimumHeight(0);
        activeFileRow.addView(chooseArchiveButton, new LinearLayout.LayoutParams(-2, dp(42)));
        chooseArchiveButton.setOnClickListener(v -> chooseArchive());

        View recordDivider = new View(this);
        recordDivider.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, dp(1));
        dividerParams.topMargin = dp(18);
        recordCard.addView(recordDivider, dividerParams);

        LinearLayout receiptRow = new LinearLayout(this);
        receiptRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        recordCard.addView(receiptRow, blockParams(dp(16)));
        ImageView receiptIcon = iconBox(R.drawable.ic_repo, dp(44), dp(15));
        receiptRow.addView(receiptIcon);
        LinearLayout receiptTexts = vertical();
        LinearLayout.LayoutParams receiptTextParams = new LinearLayout.LayoutParams(0, -2, 1);
        receiptTextParams.leftMargin = dp(12);
        receiptRow.addView(receiptTexts, receiptTextParams);
        receiptTexts.addView(text("GitHub 状态", 13, INK, true));
        receiptState = text("", 12, MUTED, false);
        receiptState.setTextIsSelectable(true);
        receiptTexts.addView(receiptState, blockParams(dp(4)));

        recordActionRow = new LinearLayout(this);
        recordActionRow.setOrientation(LinearLayout.HORIZONTAL);
        recordCard.addView(recordActionRow, blockParams(dp(18)));

        openReleaseButton = actionTile("查看草稿", R.drawable.ic_external_simple);
        retryNoticeButton = actionTile("重新通知", R.drawable.ic_refresh_simple);
        deleteButton = actionTile("核验后清理", R.drawable.ic_delete_simple);
        LinearLayout.LayoutParams tile = new LinearLayout.LayoutParams(0, dp(86), 1);
        LinearLayout.LayoutParams tileMiddle = new LinearLayout.LayoutParams(0, dp(86), 1);
        tileMiddle.leftMargin = dp(8);
        LinearLayout.LayoutParams tileLast = new LinearLayout.LayoutParams(0, dp(86), 1);
        tileLast.leftMargin = dp(8);
        recordActionRow.addView(openReleaseButton, tile);
        recordActionRow.addView(retryNoticeButton, tileMiddle);
        recordActionRow.addView(deleteButton, tileLast);
        openReleaseButton.setOnClickListener(v -> openRelease());
        retryNoticeButton.setOnClickListener(v -> retryDispatch());
        deleteButton.setOnClickListener(v -> confirmDelete());

        LinearLayout localHeader = new LinearLayout(this);
        localHeader.setGravity(android.view.Gravity.CENTER_VERTICAL);
        records.addView(localHeader, blockParams(dp(28)));
        localHeader.addView(text("本地记录", 22, INK, true),
                new LinearLayout.LayoutParams(0, -2, 1));
        localCount = text("0 条", 11, MUTED, false);
        localHeader.addView(localCount);
        archiveListing = vertical();
        records.addView(archiveListing, blockParams(dp(7)));

        // Settings — list-style, matching the selected commercial concept.
        settings.addView(pageHeading("设置", "连接、采集与安全"));

        LinearLayout connectionCard = vertical();
        connectionCard.setBackground(round(WHITE, dp(24)));
        connectionCard.setPadding(dp(8), dp(7), dp(8), dp(7));
        connectionCard.setElevation(dp(1));
        settings.addView(connectionCard, blockParams(dp(18)));

        LinearLayout repoRow = settingRow(R.drawable.ic_repo, "GitHub 仓库");
        repoState = (TextView) repoRow.getChildAt(1);
        connectionCard.addView(repoRow);
        repoRow.setOnClickListener(v -> showRepoEditor());

        View settingsDivider = new View(this);
        settingsDivider.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams settingsDividerParams = new LinearLayout.LayoutParams(-1, dp(1));
        settingsDividerParams.leftMargin = dp(66);
        settingsDividerParams.rightMargin = dp(12);
        connectionCard.addView(settingsDivider, settingsDividerParams);

        LinearLayout tokenRow = settingRow(R.drawable.ic_key_simple, "访问令牌");
        tokenState = (TextView) tokenRow.getChildAt(1);
        connectionCard.addView(tokenRow);
        tokenRow.setOnClickListener(v -> showTokenEditor());

        TextView strategyTitle = text("采集与上传", 18, INK, true);
        settings.addView(strategyTitle, blockParams(dp(24)));

        LinearLayout strategyCard = vertical();
        strategyCard.setBackground(round(WHITE, dp(22)));
        strategyCard.setPadding(dp(8), dp(6), dp(8), dp(6));
        strategyCard.setElevation(dp(1));
        settings.addView(strategyCard, blockParams(dp(9)));

        LinearLayout capturePolicyRow = settingRow(R.drawable.ic_file_zip, "采集设置");
        capturePolicySub = (TextView) capturePolicyRow.getChildAt(1);
        renderSettingRow(capturePolicySub, "采集设置", "活动 slot … · 只读完整采集");
        strategyCard.addView(capturePolicyRow);
        capturePolicyRow.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("采集设置")
                .setMessage("固定采集 boot / init_boot / vendor_boot / dtbo 与 OEM 模块。只读取当前活动 slot，不修改系统分区。")
                .setPositiveButton("知道了", null)
                .show());

        View strategyDivider = new View(this);
        strategyDivider.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams strategyDividerParams = new LinearLayout.LayoutParams(-1, dp(1));
        strategyDividerParams.leftMargin = dp(66);
        strategyDividerParams.rightMargin = dp(12);
        strategyCard.addView(strategyDivider, strategyDividerParams);

        LinearLayout uploadPolicyRow = settingRow(R.drawable.ic_external_simple, "上传设置");
        TextView uploadPolicySub = (TextView) uploadPolicyRow.getChildAt(1);
        renderSettingRow(uploadPolicySub, "上传设置", "GitHub Draft · 前台上传 · 自动跟踪接收");
        strategyCard.addView(uploadPolicyRow);
        LinearLayout verifyRow = settingRow(R.drawable.ic_repo, "验证 GitHub 连接");
        renderSettingRow((TextView) verifyRow.getChildAt(1), "验证 GitHub 连接", "实时验证仓库、草稿写权限和工作流通知权限");
        strategyCard.addView(verifyRow);
        verifyRow.setOnClickListener(v -> verifyGitHubConnection());
        uploadPolicyRow.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("上传设置")
                .setMessage("采集包上传到 GitHub Draft Release；上传任务保持前台运行，随后由接收工作流执行身份与安全审计。应用在前台自动跟踪接收状态，退出前台暂停、返回后恢复；网络错误会自动重试，也可手动查询。")
                .setPositiveButton("知道了", null)
                .show());

        TextView aboutTitle = text("帮助与关于", 18, INK, true);
        settings.addView(aboutTitle, blockParams(dp(28)));

        LinearLayout aboutCard = vertical();
        aboutCard.setBackground(round(WHITE, dp(24)));
        aboutCard.setPadding(dp(8), dp(7), dp(8), dp(7));
        aboutCard.setElevation(dp(1));
        settings.addView(aboutCard, blockParams(dp(10)));

        helpRow = settingRow(R.drawable.ic_guide_simple, "使用指南");
        TextView helpSub = (TextView) helpRow.getChildAt(1);
        renderSettingRow(helpSub, "使用指南", "新 OTA → 采集 → 审计 → 待测 → LKG");
        aboutCard.addView(helpRow);
        helpRow.setOnClickListener(v -> showHelpGuide());

        View helpDivider = new View(this);
        helpDivider.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams helpDividerParams = new LinearLayout.LayoutParams(-1, dp(1));
        helpDividerParams.leftMargin = dp(66);
        helpDividerParams.rightMargin = dp(12);
        aboutCard.addView(helpDivider, helpDividerParams);

        LinearLayout securityRow = settingRow(R.drawable.ic_shield_simple, "安全与隐私");
        TextView securitySub = (TextView) securityRow.getChildAt(1);
        renderSettingRow(securitySub, "安全与隐私", "Keystore 加密 · 只读采集");
        aboutCard.addView(securityRow);
        securityRow.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("安全与隐私")
                .setMessage("访问令牌由 Android Keystore 加密保存。采集只读，不刷机、不修改活动分区，也不会自动晋升 LKG。")
                .setPositiveButton("知道了", null)
                .show());

        View aboutDivider = new View(this);
        aboutDivider.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams aboutDividerParams = new LinearLayout.LayoutParams(-1, dp(1));
        aboutDividerParams.leftMargin = dp(66);
        aboutDividerParams.rightMargin = dp(12);
        aboutCard.addView(aboutDivider, aboutDividerParams);

        LinearLayout aboutRow = settingRow(R.drawable.ic_pandora_ota, "关于 Pandora OTA");
        TextView aboutSub = (TextView) aboutRow.getChildAt(1);
        renderSettingRow(aboutSub, "关于 Pandora OTA",
                "版本 " + getAppVersion() + " · Xiaomi 17 Pro");
        aboutCard.addView(aboutRow);
        aboutRow.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Pandora OTA")
                .setMessage("版本 " + getAppVersion()
                        + "\n\n仅适用于 Xiaomi 17 Pro · pandora。原始 init_boot.img 请从同版本 OTA 离线保存；应用内采集的是 Root 后快照。")
                .setPositiveButton("关闭", null)
                .show());

        // Keep hidden native fields for credential state/tests; editing is done through dialogs.
        repoField = field("owner/repo", store.repo(), false);
        repoField.setVisibility(View.GONE);
        settings.addView(repoField, new LinearLayout.LayoutParams(1, 1));
        tokenField = field("", "", true);
        tokenField.setVisibility(View.GONE);
        settings.addView(tokenField, new LinearLayout.LayoutParams(1, 1));
        saveButton = button("保存", true);
        saveButton.setVisibility(View.GONE);
        settings.addView(saveButton, new LinearLayout.LayoutParams(1, 1));
        clearButton = button("清除", false);
        clearButton.setVisibility(View.GONE);
        settings.addView(clearButton, new LinearLayout.LayoutParams(1, 1));

        // Material-like native bottom destination bar, always visible.
        LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setPadding(dp(18), dp(4), dp(18), dp(5));
        bottom.setBackgroundColor(Color.rgb(252, 253, 255));
        shell.addView(bottom, new LinearLayout.LayoutParams(-1, dp(60)));
        int[] glyphs = {R.drawable.ic_nav_workbench, R.drawable.ic_nav_records, R.drawable.ic_nav_settings};
        String[] labels = {"工作台", "记录", "设置"};
        for (int i = 0; i < 3; i++) {
            final int destination = i;
            LinearLayout item = vertical();
            item.setGravity(android.view.Gravity.CENTER);
            item.setClickable(true);
            item.setFocusable(true);
            item.setBackground(touchBackground(Color.TRANSPARENT, dp(15)));
            ImageView icon = new ImageView(this);
            icon.setImageResource(glyphs[i]);
            icon.setImageTintList(ColorStateList.valueOf(MUTED));
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setPadding(dp(5), dp(5), dp(5), dp(5));
            item.addView(icon, new LinearLayout.LayoutParams(dp(31), dp(31)));
            TextView label = text(labels[i], 10, MUTED, true);
            label.setGravity(android.view.Gravity.CENTER);
            item.addView(label, blockParams(dp(2)));
            bottom.addView(item, new LinearLayout.LayoutParams(0, -1, 1));
            navIcons[i] = icon;
            navLabels[i] = label;
            navItems[i] = item;
            item.setContentDescription(labels[i]);
            item.setOnClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                selectTab(destination);
            });
        }
        selectTab(0);
        refresh();
        worker.execute(() -> {
            final String osVersion = RootCollector.currentOsVersion();
            final String slotLabel = RootCollector.currentSlotLabel();
            liveOsVersion = osVersion;
            liveSlotLabel = slotLabel;
            runOnUiThread(() -> {
                if (deviceStatus != null && RootCollector.isPandora()) {
                    deviceStatus.setText(osVersion);
                }
                showBuildReminder(osVersion);
                if (capturePolicySub != null) {
                    renderSettingRow(capturePolicySub, "采集设置",
                            "活动 slot " + slotLabel + " · 只读完整采集");
                }
            });
            try {
                RootCollector.purgeStaleStaging(getApplicationContext());
            } catch (Exception e) {
                final String detail = safeMessage(e);
                runOnUiThread(() -> notifyMessage("历史临时文件清理未完成：" + detail));
            }
        });
    }

    private LinearLayout settingRow(int drawable, String title) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10), dp(13), dp(10), dp(13));
        row.setClickable(true);
        row.setFocusable(true);
        row.setBackground(touchBackground(Color.TRANSPARENT, dp(18)));

        ImageView icon = iconBox(drawable, dp(48), dp(15));
        row.addView(icon);

        TextView copy = text("", 12, MUTED, false);
        copy.setTag(title);
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1);
        copyParams.leftMargin = dp(12);
        row.addView(copy, copyParams);

        TextView chevron = text("›", 27, MUTED, false);
        row.addView(chevron);
        return row;
    }

    private void renderSettingRow(TextView view, String title, String subtitle) {
        String content = title + "\n" + subtitle;
        android.text.SpannableString styled = new android.text.SpannableString(content);
        int split = title.length() + 1;
        styled.setSpan(new android.text.style.StyleSpan(Typeface.BOLD),
                0, title.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        styled.setSpan(new android.text.style.ForegroundColorSpan(MUTED),
                split, content.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        styled.setSpan(new android.text.style.RelativeSizeSpan(0.84f),
                split, content.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        view.setText(styled);
        view.setTextColor(INK);
        view.setTextSize(14);
        view.setTypeface(Typeface.DEFAULT, Typeface.NORMAL);
        view.setLineSpacing(dp(1), 1.06f);
    }

    private void showHelpGuide() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(false);

        LinearLayout guide = vertical();
        guide.setPadding(dp(20), dp(18), dp(20), dp(12));
        guide.setBackgroundColor(BACKGROUND);
        scroll.addView(guide, new ScrollView.LayoutParams(-1, -2));

        guide.addView(text("使用指南", 24, INK, true));
        guide.addView(text("从新 OTA 到稳定内核，一条链路看懂", 12, MUTED, false),
                blockParams(dp(4)));

        LinearLayout current = vertical();
        current.setPadding(dp(16), dp(14), dp(16), dp(14));
        current.setBackground(round(LIGHT_TEAL, dp(20)));
        guide.addView(current, blockParams(dp(16)));
        current.addView(text("当前设备", 11, TEAL, true));
        current.addView(text(liveOsVersion + "  ·  slot " + liveSlotLabel,
                17, INK, true), blockParams(dp(4)));
        current.addView(text("Xiaomi 17 Pro · pandora", 11, MUTED, false),
                blockParams(dp(3)));

        guide.addView(text("这款 App 做什么", 17, INK, true), blockParams(dp(20)));
        guide.addView(text(
                "系统更新到新的 HyperOS OTA 后，预检并读取当前活动 slot 的只读快照，"
                + "交给 GitHub 建立同版本内核适配基线；电脑临时启动后，还能在手机读取信息、填表提交测试结果。App 不刷机，也不会自动晋升 LKG。",
                12, MUTED, false), blockParams(dp(7)));

        LinearLayout steps = vertical();
        steps.setBackground(round(WHITE, dp(22)));
        steps.setPadding(dp(15), dp(8), dp(15), dp(8));
        steps.setElevation(dp(1));
        guide.addView(steps, blockParams(dp(16)));
        steps.addView(guideStep("1", R.drawable.ic_refresh_simple, "新 OTA 与原始镜像",
                "先离线保存同版本原始 init_boot.img，再修补恢复 Root。新 build 提醒的「已了解」只代表读过说明，不代表已经备份。"));
        steps.addView(guideDivider());
        steps.addView(guideStep("2", R.drawable.ic_key_simple, "配置与预检",
                "设置仓库和 PAT：采集/上传需 Contents 读写，手机反馈另需 Actions 读写。设置可实时验证 GitHub；采集目录不足 1 GiB 阻止，1 GiB 至不足 2 GiB 警告。"));
        steps.addView(guideDivider());
        steps.addView(guideStep("3", R.drawable.ic_file_zip, "只读采集并上传",
                "为本 App 单独授权 Root；若更新页面显示 D02 但系统 Build 不变，采集前在「软件修订号」确认 D02。此声明仅供人工核验，不伪造系统属性。再读取活动 slot 四个启动分区及 OEM 模块，上传到 Draft；打包后清理中间文件。"));
        steps.addView(guideDivider());
        steps.addView(guideStep("4", R.drawable.ic_external_simple, "自动跟踪接收",
                "前台自动查询 PENDING → ACCEPTED / ALREADY_CURRENT / REVIEW_REQUIRED / REJECTED，后台暂停、返回恢复。软件修订 Dxx 只保存人工确认的更新页证据；本机最近采集会显示软件 Dxx，不修改系统 Build，不替换内核输入。通知失败可重试，接收成功不代表 ABI 或开机通过。"));
        steps.addView(guideDivider());
        steps.addView(guideStep("5", R.drawable.ic_repo, "电脑临时启动，手机反馈",
                "候选和 OEM ABI 全绿后，由电脑 fastboot boot。手机测试后在工作台「真机测试反馈」选择候选，自动读取设备、build、启动状态、uname 和模块，人工确认功能并提交原工作流。"));
        steps.addView(guideDivider());
        steps.addView(guideStep("6", R.drawable.ic_shield_simple, "失败、恢复与重试",
                "未能进入待测系统、恢复后才打开 App，选择恢复后失败，不能提交恢复环境冒充待测证据。响应未知先查原运行；两分钟仍未建立记录可重发同一反馈，服务器阻止重复处理。"));
        steps.addView(guideDivider());
        steps.addView(guideStep("7", R.drawable.ic_guide_simple, "开发基线与稳定 LKG",
                "反馈通过只建立同版本开发基线。自定义功能编译成功就提供待测 IMG 和实验性 AnyKernel3；真正持久安装、稳定使用后，再由你在唯一 LKG 晋升入口确认。"));

        guide.addView(text("为什么要分这么多步", 17, INK, true), blockParams(dp(20)));
        LinearLayout reason = vertical();
        reason.setPadding(dp(16), dp(14), dp(16), dp(14));
        reason.setBackground(round(WHITE, dp(20)));
        reason.setElevation(dp(1));
        guide.addView(reason, blockParams(dp(8)));
        reason.addView(text(
                "Root 后的 init_boot 可能已经被修改，所以 App 采集的是“当前设备快照”，"
                + "不能冒充厂商原始镜像。把采集、静态审计、临时启动和 LKG 分开，"
                + "可以避免旧 OTA、错误 slot 或未验证内核被当成稳定版本。",
                12, MUTED, false));

        LinearLayout caution = vertical();
        caution.setPadding(dp(16), dp(14), dp(16), dp(14));
        caution.setBackground(round(Color.rgb(255, 247, 242), dp(20)));
        guide.addView(caution, blockParams(dp(12)));
        caution.addView(text("记住这 3 件事", 13, WARNING, true));
        caution.addView(text("• 新 OTA 到来后重新采集，不要上传旧版本 ZIP。", 12, INK, false),
                blockParams(dp(8)));
        caution.addView(text("• GitHub Draft 只用于私有接收，不要 Publish。", 12, INK, false),
                blockParams(dp(5)));
        caution.addView(text("• 待测 boot 只能 fastboot boot，不要 fastboot flash。", 12, INK, false),
                blockParams(dp(5)));

        guide.addView(text("常见问题与下一步", 17, INK, true), blockParams(dp(20)));
        guide.addView(text(
                "• 五项本机读取成功不会自动勾选测试通过。模块读不到会明确提示；纯 GKI 临时会话不一定有 Root。\n"
                + "• 新候选预填 IMG SHA-256；旧候选需手填。build / uname / 启动完成不能证明电脑实际用了指定镜像，仍需你核对。\n"
                + "• 本地清理要等上传确认、通知成功和接收终态，再在线核验远端与本地 SHA。REJECTED 不等于上传失败，符合条件后也可主动清理；远端附件不删除。\n"
                + "• 真机反馈的模块全文留在本机草稿供查看，原工作流提交的是实际读数和人工确认；最近反馈历史保留 20 条。\n"
                + "• 上传和查询请保持前台；目前没有后台断点续传。工作台「OTA 全链路状态」可刷新服务器已核验的阶段快照；这是当前固定输入的证据状态，并非逐秒编译进度。",
                12, MUTED, false), blockParams(dp(7)));

        guide.addView(text("如何理解全链路状态", 17, INK, true), blockParams(dp(20)));
        guide.addView(text("没有新 OTA ≠ 新待测包；GKI 已构建还需通过 OEM ABI。待测包已生成 / 等待真机测试只允许由电脑临时启动；开发基线已验证不代表自定义功能稳定。自定义内核待验证仍需人工测试，稳定 LKG 已长期归档才表示完成明确的稳定晋升。输入变化、附件过期或来源不匹配时，App 会停止展示结论并提示重新核验。", 12, MUTED, false), blockParams(dp(7)));

        guide.addView(text("完成 App 内步骤之后", 17, INK, true), blockParams(dp(20)));
        guide.addView(text(
                "你只需要等待 GitHub 的接收与 ABI/KMI 结果。若生成待测 boot，"
                + "由电脑临时启动，在手机测试后回到「真机测试反馈」提交；原工作流核验通过后建立该 OTA 的开发基线，"
                + "后续自定义内核稳定后再进入 LKG 晋升与长期归档。",
                12, MUTED, false), blockParams(dp(7)));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(scroll)
                .setPositiveButton("看懂了", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(TEAL);
            if (dialog.getWindow() != null) {
                dialog.getWindow().setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            }
        });
        dialog.show();
    }

    private LinearLayout guideStep(String number, int drawable, String title, String detail) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.TOP);
        row.setPadding(0, dp(12), 0, dp(12));

        TextView badge = text(number, 11, WHITE, true);
        badge.setGravity(android.view.Gravity.CENTER);
        badge.setBackground(round(TEAL, dp(16)));
        row.addView(badge, new LinearLayout.LayoutParams(dp(30), dp(30)));

        ImageView icon = new ImageView(this);
        icon.setImageResource(drawable);
        icon.setImageTintList(ColorStateList.valueOf(TEAL));
        icon.setPadding(dp(5), dp(5), dp(5), dp(5));
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(30), dp(30));
        iconParams.leftMargin = dp(8);
        row.addView(icon, iconParams);

        LinearLayout copy = vertical();
        LinearLayout.LayoutParams copyParams = new LinearLayout.LayoutParams(0, -2, 1);
        copyParams.leftMargin = dp(10);
        row.addView(copy, copyParams);
        copy.addView(text(title, 14, INK, true));
        copy.addView(text(detail, 11, MUTED, false), blockParams(dp(4)));
        return row;
    }

    private View guideDivider() {
        View line = new View(this);
        line.setBackgroundColor(BORDER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(1));
        params.leftMargin = dp(68);
        line.setLayoutParams(params);
        return line;
    }

    private void showRepoEditor() {
        if (busy) return;
        EditText input = field("owner/repo", store.repo(), false);
        input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this)
                .setTitle("GitHub 仓库")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (dialog, which) -> {
                    try {
                        String repo = input.getText().toString().trim();
                        if (!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))
                            throw new IllegalArgumentException("仓库格式应为 owner/repo");
                        store.saveRepo(repo);
                        githubStatus = "未验证";
                        repoField.setText(repo);
                        refresh();
                        notifyMessage("GitHub 仓库已更新");
                    } catch (Exception e) {
                        notifyMessage("保存失败：" + safeMessage(e));
                    }
                }).show();
    }

    private void showTokenEditor() {
        if (busy) return;
        EditText input = field("粘贴新的 Fine-grained PAT", "", true);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("访问令牌")
                .setView(input)
                .setNegativeButton("取消", null)
                .setNeutralButton("清除本机令牌", null)
                .setPositiveButton("保存", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    String token = input.getText().toString().trim();
                    if (token.isEmpty()) throw new IllegalArgumentException("请输入访问令牌");
                    store.saveToken(token);
                    githubStatus = "未验证";
                    input.setText("");
                    refresh();
                    notifyMessage("访问令牌已安全保存");
                    dialog.dismiss();
                } catch (Exception e) {
                    notifyMessage("保存失败：" + safeMessage(e));
                }
            });
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                store.clearToken();
                githubStatus = "未验证";
                refresh();
                notifyMessage("本机 GitHub 令牌已删除");
                dialog.dismiss();
            });
        });
        dialog.show();
    }

    private void showBuildReminder(String osVersion) {
        if (!foreground || !RootCollector.isPandora() || isFinishing()) return;
        String identity = CapturePreflight.buildIdentity(osVersion, Build.FINGERPRINT);
        if (!CapturePreflight.buildChanged(store.acknowledgedBuild(), identity)
                || (buildReminder != null && buildReminder.isShowing())) return;
        buildReminder = new AlertDialog.Builder(this)
                .setTitle(store.acknowledgedBuild().isEmpty() ? "首次使用 · 保存原始镜像" : "检测到系统 build 变化")
                .setMessage("当前系统：" + osVersion
                        + "\n\n请离线保存这个版本未经修改的原始 init_boot.img，再修改镜像获取 Root。已经取得 Root 时，请从同版本官方 OTA 保留原始文件。"
                        + "\n\n应用采集的 init_boot 可能已被 Root 修改，仅供诊断，不能代替原始镜像。后续采集仍需 Root。")
                .setPositiveButton("已了解", (dialog, which) -> {
                    try { store.acknowledgeBuild(identity); }
                    catch (Exception e) { notifyMessage(safeMessage(e)); }
                })
                .setNegativeButton("稍后提醒", null).show();
    }

    private void verifyGitHubConnection() {
        if (busy) return;
        String repo = store.repo();
        githubStatus = "验证中";
        runTask("正在验证 GitHub 实时连接与写权限…", () -> {
            try {
                GitHubUploader.verifyConnection(repo, store.loadToken());
                runOnUiThread(() -> githubStatus = "已验证");
                return "GitHub 实时连接、草稿创建/读取/删除与通知权限已验证。";
            } catch (Exception e) {
                runOnUiThread(() -> githubStatus = "验证失败");
                throw e;
            }
        });
    }

    private void startCapturePreflight() {
        if (busy) return;
        String repo = store.repo();
        beginBusy("采集前预检：空间与 GitHub 连接/写权限…");
        worker.execute(() -> {
            try {
                final long bytes = CapturePreflight.requireSpace(getApplicationContext());
                final String os = RootCollector.currentOsVersion();
                GitHubUploader.verifyConnection(repo, store.loadToken());
                runOnUiThread(() -> {
                    githubStatus = "已验证";
                    finishBusy();
                    preflightState.setText("可用 " + String.format(Locale.US, "%.2f GiB", bytes / (double) CapturePreflight.GIB)
                            + " · GitHub 写权限已验证");
                    if (!foreground || isFinishing()) return;
                    if (CapturePreflight.buildChanged(store.acknowledgedBuild(),
                            CapturePreflight.buildIdentity(os, Build.FINGERPRINT))) {
                        showBuildReminder(os);
                        notifyMessage("请先阅读当前版本原始 init_boot.img 保存提醒，再开始采集。");
                        return;
                    }
                    if (CapturePreflight.space(bytes) == CapturePreflight.Space.WARN) {
                        new AlertDialog.Builder(this).setTitle("采集空间偏少")
                                .setMessage("采集目录可用空间介于 1–2 GiB，采集及压缩可能耗尽空间。建议先清理旧文件。")
                                .setPositiveButton("继续采集", (d, w) -> promptSoftwareRevisionBeforeCapture())
                                .setNegativeButton("先清理空间", null).show();
                    } else promptSoftwareRevisionBeforeCapture();
                });
            } catch (Exception e) {
                String error = safeMessage(e);
                runOnUiThread(() -> {
                    githubStatus = "未通过";
                    finishBusy();
                    preflightState.setText("预检未通过 · " + error);
                    notifyMessage("采集预检失败：" + error);
                });
            }
        });
    }

    private void promptSoftwareRevisionBeforeCapture() {
        if (!foreground || isFinishing()) return;
        if (RootCollector.currentOsVersion().matches(".*\\.D[0-9]{2}$")) {
            collectAfterPreflight("");
            return;
        }
        EditText revision = new EditText(this);
        revision.setSingleLine(true);
        revision.setHint("例如 D02");
        revision.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        LinearLayout wrapper = vertical();
        wrapper.setPadding(dp(24), dp(2), dp(24), dp(2));
        wrapper.addView(revision);
        new AlertDialog.Builder(this)
                .setTitle("软件修订号 · 可选")
                .setMessage("Android 系统属性可能仍是旧 Build，但小米更新页面显示 D02。"
                        + "只有亲眼确认更新页面出现 D02 时才填写；这是人工确认的修订证据，"
                        + "不是内核变化证明，也不会自动升级 LKG。")
                .setView(wrapper)
                .setNegativeButton("取消", null)
                .setNeutralButton("无软件修订", (d, w) -> collectAfterPreflight(""))
                .setPositiveButton("确认并采集", (d, w) -> {
                    String value = revision.getText().toString().trim().toUpperCase(Locale.US);
                    if (!value.matches("D[0-9]{2}")) {
                        notifyMessage("请输入 D02 这样的修订号；没有软件修订请选择「无软件修订」");
                        return;
                    }
                    collectAfterPreflight(value);
                }).show();
    }

    private void collectAfterPreflight(String softwareRevision) {
        if (busy || !foreground) return;
        runTask("正在采集设备数据…", () -> {
            File output = RootCollector.capture(getApplicationContext(), true, softwareRevision);
            store.saveArchive(output.getAbsolutePath());
            return "采集完成 · " + output.getName() + " · " + size(output.length());
        });
    }

    private void updateIntakeTracking() {
        File file = selectedFile();
        if (!foreground || busy || file == null || !store.hasToken() || !store.dispatched()
                || !store.isUploaded(new SecretStore.FileInfo(file.getAbsolutePath(), file.length()))
                || ForegroundIntakeTracker.terminal(store.intakeState())) {
            tracker.stop();
            return;
        }
        final String path = file.getAbsolutePath();
        final String repo = store.repo();
        final long releaseId = store.lastReleaseId(), assetId = store.lastAssetId();
        final String sha = store.uploadedSha256(new SecretStore.FileInfo(path, file.length()));
        String key = repo + ":" + path + ":" + releaseId + ":" + assetId + ":" + sha;
        tracker.start(key, () -> GitHubUploader.checkIntake(repo, store.loadToken(), releaseId, assetId, sha),
                (state, error) -> {
                    if (!path.equals(store.lastArchive()) || !repo.equals(store.repo())
                            || releaseId != store.lastReleaseId() || assetId != store.lastAssetId()) {
                        tracker.stop();
                        return;
                    }
                    if (error != null) {
                        intakeSummary.setText("自动查询暂未成功，将重试；也可手动查询。\n" + safeMessage(error));
                        return;
                    }
                    try {
                        store.recordIntake(state);
                        refresh();
                        if (ForegroundIntakeTracker.terminal(state))
                            notifyMessage("GitHub 接收状态：" + intakeLabel(state));
                    } catch (Exception e) {
                        tracker.stop();
                        notifyMessage("保存接收状态失败：" + safeMessage(e));
                    }
                });
    }

    private void saveConnection() {
        try {
            String repo = repoField.getText().toString().trim();
            if (!repo.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))
                throw new IllegalArgumentException("仓库格式应为 owner/repo");
            String token = tokenField.getText().toString().trim();
            store.saveRepo(repo);
            if (!token.isEmpty()) store.saveToken(token);
            tokenField.setText("");
            githubStatus = "未验证";
            refresh();
            notifyMessage("连接设置已保存。令牌由 Android Keystore 加密。");
        } catch (Exception e) {
            notifyMessage("保存失败：" + safeMessage(e));
        }
    }

    private void startUpload() {
        File file = selectedFile();
        if (file == null) {
            notifyMessage("请先采集 ZIP 文件");
            return;
        }
        if (store.isUploaded(new SecretStore.FileInfo(file.getAbsolutePath(), file.length()))) {
            notifyMessage("该采集包已上传成功，无需重复上传");
            return;
        }
        final String repo = store.repo();
        beginBusy("正在准备上传…");
        uploadBar.setProgress(0);
        percent.setText("0%");
        uploadNumbers.setText("0 B / " + size(file.length()));
        uploadSpeed.setText("速率 --   ·   耗时 --");
        receiptState.setText("等待 GitHub 服务器确认");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        worker.execute(() -> {
            try {
                RootCollector.requireCurrentCaptureIdentity(file);
                String token = store.loadToken();
                GitHubUploader.verifyConnection(repo, token);
                final long started = SystemClock.elapsedRealtime();
                GitHubUploader.Result result = GitHubUploader.upload(repo, token, file,
                        new GitHubUploader.Listener() {
                    private long lastMs = started;
                    private long lastBytes = 0;
                    @Override
                    public void onStage(String stage) {
                        runOnUiThread(() -> {
                            uploadStage.setText(stage);
                            notifyMessage("GitHub 上传 · " + stage);
                        });
                    }
                    @Override
                    public void onProgress(long sent, long total) {
                        long now = SystemClock.elapsedRealtime();
                        long interval = now - lastMs;
                        if (sent < total && interval < 350) return;
                        double rate = interval > 0
                                ? (sent - lastBytes) * 1000.0 / interval : 0;
                        lastMs = now;
                        lastBytes = sent;
                        long elapsed = now - started;
                        runOnUiThread(() -> renderProgress(sent, total, rate, elapsed));
                    }
                });
                store.confirmedUpload(file.getAbsolutePath(), file.length(), result);
                runOnUiThread(() -> {
                    finishBusy();
                    uploadStage.setText("GitHub 已确认上传");
                    receiptState.setText("Release #" + result.releaseId
                            + " · 附件 #" + result.assetId
                            + "\nSHA-256：" + result.sha256
                            + "\n工作流通知：" + (result.dispatched
                            ? "请求已发送（不代表工作流已执行）" : "发送失败，需另行处理"));
                    notifyMessage(result.dispatched
                            ? "GitHub 已保存附件，正在前台自动跟踪接收结果。"
                            : "GitHub 已保存附件，但通知失败；请点重新通知，附件无需重复上传。");
                });
            } catch (Exception e) {
                String error = safeMessage(e);
                runOnUiThread(() -> {
                    finishBusy();
                    uploadStage.setText("上传未确认成功");
                    receiptState.setText("未取得有效服务器回执；请保留本地文件并检查 GitHub 草稿");
                    notifyMessage("上传失败：" + error);
                });
            } finally {
                runOnUiThread(() -> getWindow().clearFlags(
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON));
            }
        });
    }

    private void retryDispatch() {
        File file = selectedFile();
        if (file == null || store.dispatched() || store.lastAssetId() <= 0
                || !store.isUploaded(new SecretStore.FileInfo(file.getAbsolutePath(), file.length()))) {
            notifyMessage("没有可以安全重新发送的工作流通知");
            return;
        }
        final File selected = file;
        final String repo = store.repo();
        final long releaseId = store.lastReleaseId();
        final long assetId = store.lastAssetId();
        final String sha = store.uploadedSha256(
                new SecretStore.FileInfo(selected.getAbsolutePath(), selected.length()));
        runTask("正在核对远端附件并重新发送工作流通知…", () -> {
            String token = store.loadToken();
            GitHubUploader.retryDispatch(repo, token, selected, releaseId, assetId, sha);
            store.markDispatchSucceeded();
            return "工作流通知已再次发送；仍需等待 GitHub 接收工作流执行完成。";
        });
    }

    private String intakeLabel(String state) {
        switch (state) {
            case "ACCEPTED": return "新输入已接收（不是 ABI/KMI 或真机验证通过）";
            case "ALREADY_CURRENT": return "与固定输入一致，无须重复分析";
            case "REJECTED": return "OTA 身份门禁未通过，远端采集附件已保留";
            case "REVIEW_REQUIRED": return "软件修订证据已安全保存（人工核验中） · 固定内核基线未变；无需重新编译";
            default: return "等待后端接收结果 · 前台自动跟踪";
        }
    }

    private void checkBackendIntake() {
        File file = selectedFile();
        if (file == null || !store.dispatched() || store.lastAssetId() <= 0) {
            notifyMessage("需先完成上传并发送工作流通知");
            return;
        }
        final String repo = store.repo();
        final long releaseId = store.lastReleaseId();
        final long assetId = store.lastAssetId();
        final String sha = store.uploadedSha256(
                new SecretStore.FileInfo(file.getAbsolutePath(), file.length()));
        runTask("正在查询 GitHub 的后端接收结果…", () -> {
            String token = store.loadToken();
            String outcome = GitHubUploader.checkIntake(repo, token, releaseId, assetId, sha);
            store.recordIntake(outcome);
            return "GitHub 接收状态：" + intakeLabel(outcome)
                    + "\n注意：接收成功不代表纯 GKI、ABI 或启动测试通过。";
        });
    }

    private void renderProgress(long sent, long total, double bytesPerSecond, long elapsed) {
        int value = total <= 0 ? 0 : (int) (sent * 100 / total);
        uploadBar.setProgress(value);
        percent.setText(value + "%");
        uploadNumbers.setText(size(sent) + " / " + size(total));
        uploadSpeed.setText("速率 " + size((long) bytesPerSecond) + "/s"
                + "   ·   耗时 " + (elapsed / 1000) + " 秒");
    }

    private void chooseArchive() {
        File[] archives = store.archives();
        if (archives.length == 0) {
            notifyMessage("本机没有可选的采集包");
            return;
        }
        String[] names = new String[archives.length];
        for (int i = 0; i < archives.length; i++) {
            File f = archives[i];
            boolean uploaded = store.isUploaded(
                    new SecretStore.FileInfo(f.getAbsolutePath(), f.length()));
            names[i] = f.getName() + "\n" + captureIdentityLabel(f) + "\n"
                    + size(f.length()) + " · "
                    + (uploaded ? "已上传" : "待上传");
        }
        new AlertDialog.Builder(this)
                .setTitle("选择本地采集包")
                .setSingleChoiceItems(names, -1, (dialog, which) -> {
                    store.saveArchive(archives[which].getAbsolutePath());
                    dialog.dismiss();
                    refresh();
                    notifyMessage("已切换：" + archives[which].getName());
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDelete() {
        File file = selectedFile();
        String intake = store.intakeState();
        boolean intakeTerminal = "ACCEPTED".equals(intake)
                || "ALREADY_CURRENT".equals(intake) || "REJECTED".equals(intake)
                || "REVIEW_REQUIRED".equals(intake);
        if (file == null || !store.isUploaded(
                new SecretStore.FileInfo(file.getAbsolutePath(), file.length()))
                || !store.hasToken() || store.lastAssetId() <= 0) {
            notifyMessage("未取得 GitHub 附件上传回执，请先确认上传成功");
            return;
        }
        if (!store.dispatched()) {
            notifyMessage("工作流通知尚未成功发送，请先重新通知 GitHub");
            return;
        }
        if (!intakeTerminal) {
            notifyMessage("后端接收结果尚未完成，请先查询到终态（含 REVIEW_REQUIRED）");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("确认删除本地文件？")
                .setMessage(file.getName() + "\n\n后端接收已得到终态；REVIEW_REQUIRED / REJECTED 仍会保留 GitHub 远端证据，核验哈希后才允许手动清理本地副本。操作前将在线核验 GitHub 附件 ID、大小和 SHA-256。\n\n仅删除手机上的 ZIP，远端采集附件保持不变。"
                        + "本地删除后无法在应用内恢复。")
                .setNegativeButton("保留文件", null)
                .setPositiveButton("删除本地 ZIP", (dialog, which) ->
                    runTask("正在删除本地采集包…", () -> {
                        File target = new File(store.lastArchive()).getCanonicalFile();
                        File directory = new File(getExternalFilesDir(null), "captures")
                                .getCanonicalFile();
                        if (!directory.equals(target.getParentFile())
                                || !target.getName().matches(
                                "pandora-os4-capture-\\d{8}-\\d{6}(?:-\\d{3})?\\.zip")) {
                            throw new SecurityException("路径不在应用的 OTA 采集目录内");
                        }
                        if (!store.isUploaded(new SecretStore.FileInfo(
                                target.getAbsolutePath(), target.length()))) {
                            throw new SecurityException("无 GitHub 成功上传回执");
                        }
                        String expectedSha = store.uploadedSha256(
                                new SecretStore.FileInfo(target.getAbsolutePath(), target.length()));
                        if (expectedSha.isEmpty() ||
                                !expectedSha.equalsIgnoreCase(GitHubUploader.sha256(target))) {
                            throw new SecurityException("本地文件 SHA-256 与上传回执不符，拒绝删除");
                        }
                        GitHubUploader.verifyRemoteArchive(store.repo(), store.loadToken(),
                                store.lastReleaseId(), store.lastAssetId(), target, expectedSha);
                        if (!expectedSha.equalsIgnoreCase(GitHubUploader.sha256(target))) {
                            throw new SecurityException("核验期间本地文件发生变化，禁止删除");
                        }
                        if (!target.delete()) throw new IllegalStateException("无法删除文件");
                        store.forgetArchive();
                        return "已手动删除本地 ZIP；GitHub 附件已保留";
                    })).show();
    }

    private void openRelease() {
        File file = selectedFile();
        if (file == null || !file.isFile()
                || !store.isUploaded(new SecretStore.FileInfo(
                        file.getAbsolutePath(), file.length()))
                || !store.hasToken() || store.lastReleaseId() <= 0
                || store.lastAssetId() <= 0) {
            notifyMessage("尚无可核验的 GitHub 草稿采集附件");
            return;
        }
        final String repo = store.repo();
        final String path = file.getAbsolutePath();
        final long releaseId = store.lastReleaseId();
        final long assetId = store.lastAssetId();
        final String sha = store.uploadedSha256(
                new SecretStore.FileInfo(path, file.length()));
        runTask("正在核验 GitHub 草稿附件并打开管理页面…", () -> {
            String target = GitHubUploader.verifiedDraftEditorUrl(
                    repo, store.loadToken(), releaseId, assetId, file, sha);
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(target)));
                } catch (Exception e) {
                    notifyMessage("无法打开浏览器：" + safeMessage(e));
                }
            });
            return "已请求打开 GitHub 草稿管理页。请使用有仓库写权限的账号登录；"
                    + "切勿发布此未经原厂身份验证的采集包。";
        });
    }

    private void refresh() {
        boolean credentials = store.hasToken();
        tokenState.setText(credentials ? "✓ 已配置 · 安全存储" : "未配置");
        if (repoState != null) renderSettingRow(repoState, "GitHub 仓库",
                store.repo().isEmpty() ? "未配置" : store.repo());
        if (tokenState != null) renderSettingRow(tokenState, "访问令牌",
                credentials ? "✓ 已配置 · Android Keystore" : "未配置");
        File file = selectedFile();
        boolean exists = file != null;
        boolean uploaded = exists && store.isUploaded(
                new SecretStore.FileInfo(file.getAbsolutePath(), file.length()));
        String state = uploaded ? store.intakeState() : "PENDING";
        File[] allArchives = store.archives();
        int uploadedCount = 0;
        for (File archive : allArchives) {
            if (store.isUploaded(new SecretStore.FileInfo(
                    archive.getAbsolutePath(), archive.length()))) uploadedCount++;
        }
        if (workUploadedMetric != null) workUploadedMetric.setText(String.valueOf(uploadedCount));
        if (workRepoMetric != null) workRepoMetric.setText(store.repo().isEmpty() ? "未配置" : "已配置");
        if (workGitHubMetric != null) workGitHubMetric.setText(credentials ? githubStatus : "未配置");
        if (recordCountMetric != null) recordCountMetric.setText(String.valueOf(allArchives.length));
        if (recordUploadedMetric != null) recordUploadedMetric.setText(String.valueOf(uploadedCount));
        if (recordGitHubMetric != null) recordGitHubMetric.setText(credentials ? githubStatus : "未配置");
        if (localCount != null) localCount.setText(allArchives.length == 0
                ? "0 条" : "最近 " + Math.min(allArchives.length, 12) + " 条");
        String captureIdentity = exists ? captureIdentityLabel(file) : "";
        fileState.setText(exists
                ? file.getName() + "\n" + captureIdentity + "\n" + size(file.length())
                    + " · " + (uploaded ? "已上传" : "待上传")
                : "暂无采集记录");
        recordFileState.setText(exists
                ? file.getName() + "\n" + captureIdentity + "\n" + size(file.length())
                    + " · " + (uploaded ? "已上传" : "仅本地")
                : "尚无采集记录");
        if (recordCard != null) recordCard.setVisibility(exists ? View.VISIBLE : View.GONE);
        saveButton.setEnabled(!busy);
        clearButton.setEnabled(!busy && credentials);
        repoField.setEnabled(!busy);
        tokenField.setEnabled(!busy);
        bootOption.setEnabled(false);
        rootButton.setEnabled(!busy);
        collectButton.setEnabled(!busy && RootCollector.isPandora());
        uploadButton.setEnabled(!busy && exists && !uploaded && credentials);
        uploadButton.setText("上传到 GitHub");
        uploadButton.setVisibility(exists && !uploaded ? View.VISIBLE : View.GONE);
        boolean showProgress = exists && (busy || uploaded);
        uploadStage.setVisibility(showProgress ? View.VISIBLE : View.GONE);
        percent.setVisibility(showProgress ? View.VISIBLE : View.GONE);
        uploadBar.setVisibility(showProgress ? View.VISIBLE : View.GONE);
        uploadNumbers.setVisibility(busy ? View.VISIBLE : View.GONE);
        uploadSpeed.setVisibility(busy ? View.VISIBLE : View.GONE);
        intakeSummary.setVisibility(uploaded ? View.VISIBLE : View.GONE);
        chooseArchiveButton.setEnabled(!busy && store.archives().length > 0);
        boolean intakeTerminal = "ACCEPTED".equals(state)
                || "ALREADY_CURRENT".equals(state) || "REJECTED".equals(state)
                || "REVIEW_REQUIRED".equals(state);
        deleteButton.setEnabled(!busy && uploaded && store.hasToken()
                && store.lastAssetId() > 0 && store.dispatched() && intakeTerminal);
        checkIntakeButton.setVisibility(uploaded && store.dispatched()
                ? View.VISIBLE : View.GONE);
        checkIntakeButton.setEnabled(!busy && credentials);
        retryNoticeButton.setVisibility(uploaded && !store.dispatched()
                && store.lastAssetId() > 0 ? View.VISIBLE : View.GONE);
        retryNoticeButton.setEnabled(!busy && credentials);
        openReleaseButton.setVisibility(uploaded
                && store.lastReleaseId() > 0 && store.lastAssetId() > 0
                ? View.VISIBLE : View.GONE);
        openReleaseButton.setEnabled(!busy && credentials);
        taskSpinner.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (!busy && uploaded) {
            uploadStage.setText(store.intakeAccepted() ? "已接收 · 校验完成"
                    : "REJECTED".equals(state) ? "已上传 · 后端安全拒收"
                    : "REVIEW_REQUIRED".equals(state) ? "已上传 · 软件 OTA 证据待核验"
                    : "已上传 · 等待后端审计");
            uploadBar.setProgress(100);
            percent.setText("100%");
            uploadNumbers.setText(size(file.length()) + " / " + size(file.length()));
            uploadSpeed.setText("GitHub 附件已保存");
            intakeSummary.setText("后端状态  ·  " + intakeLabel(state));
            intakeSummary.setTextColor("REJECTED".equals(state) || "REVIEW_REQUIRED".equals(state) ? WARNING : TEAL);
            receiptState.setText("Release #" + store.lastReleaseId()
                    + " · 附件 #" + store.lastAssetId()
                    + "\nSHA-256：" + store.uploadedSha256(
                        new SecretStore.FileInfo(file.getAbsolutePath(), file.length()))
                    + "\n工作流通知：" + (store.dispatched() ? "已发送" : "未成功")
                    + "\n接收结果：" + intakeLabel(state));
        } else if (!busy) {
            uploadStage.setText(exists ? "等待上传" : "尚无采集文件");
            uploadBar.setProgress(0);
            percent.setText("0%");
            uploadNumbers.setText(exists ? "0 B / " + size(file.length()) : "0 B / 0 B");
            uploadSpeed.setText("速率 --   ·   耗时 --");
            receiptState.setText("尚无 GitHub 附件上传回执");
            intakeSummary.setText("尚未收到后端审计结果");
            intakeSummary.setTextColor(MUTED);
        }
        refreshArchiveListing(file);
        updateIntakeTracking();
    }

    private String captureIdentityLabel(File file) {
        try {
            RootCollector.CaptureIdentity identity = RootCollector.inspectCaptureIdentity(file);
            return identity.displayLabel();
        } catch (Exception e) {
            return "采集身份待核验";
        }
    }

    private File selectedFile() {
        String path = store.lastArchive();
        if (path.isEmpty() || !new File(path).isFile()) {
            File[] files = store.archives();
            if (files.length == 0) return null;
            store.saveArchive(files[0].getAbsolutePath());
            path = files[0].getAbsolutePath();
        }
        File file = new File(path);
        return file.isFile() && file.length() > 0 ? file : null;
    }

    private interface Task { String run() throws Exception; }
    private void runTask(String stage, Task task) {
        beginBusy(stage);
        worker.execute(() -> {
            String result;
            try {
                result = task.run();
            } catch (Exception e) {
                result = "操作失败：" + safeMessage(e);
            }
            final String finalResult = result;
            runOnUiThread(() -> {
                finishBusy();
                refresh();
                notifyMessage(finalResult);
            });
        });
    }

    private void beginBusy(String label) {
        busy = true;
        notifyMessage(label);
        refresh();
    }

    private void finishBusy() {
        busy = false;
        refresh();
    }

    private void notifyMessage(String value) {
        if (statusBanner != null) statusBanner.setVisibility(View.VISIBLE);
        message.setText(value);
        boolean error = value.contains("失败") || value.contains("拒收")
                || value.contains("无法") || value.contains("错误");
        message.setTextColor(error ? WARNING : INK);
    }

    private static String safeMessage(Exception e) {
        String detail = e.getMessage();
        if (detail == null || detail.isEmpty()) return e.getClass().getSimpleName();
        return detail.length() > 260 ? detail.substring(0, 260) : detail;
    }

    private String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.CHINA, "%.1f KiB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024)
            return String.format(Locale.CHINA, "%.2f MiB", bytes / (1024.0 * 1024));
        return String.format(Locale.CHINA, "%.2f GiB",
                bytes / (1024.0 * 1024 * 1024));
    }

    private ScrollView page(FrameLayout host) {
        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        host.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        return scroll;
    }

    private LinearLayout pageContents(ScrollView scroll) {
        LinearLayout contents = vertical();
        contents.setPadding(dp(18), dp(8), dp(18), dp(30));
        scroll.addView(contents);
        return contents;
    }

    private LinearLayout surfaceCard(LinearLayout parent, int top, int inset) {
        LinearLayout card = vertical();
        GradientDrawable bg = round(WHITE, dp(24));
        card.setBackground(bg);
        card.setElevation(dp(1));
        card.setPadding(inset, dp(21), inset, dp(23));
        parent.addView(card, blockParams(top));
        return card;
    }

    private LinearLayout sectionHeading(String number, String heading, String detail) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        View accent = new View(this);
        accent.setBackground(round(TEAL, dp(3)));
        row.addView(accent, new LinearLayout.LayoutParams(dp(5), dp(34)));
        LinearLayout labels = vertical();
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.leftMargin = dp(13);
        row.addView(labels, lp);
        labels.addView(text(heading, 18, INK, true));
        labels.addView(text(detail, 11, MUTED, false), blockParams(dp(4)));
        return row;
    }

    private LinearLayout pageHeading(String heading, String subheading) {
        LinearLayout block = vertical();
        block.addView(text(heading, 26, INK, true));
        block.addView(text(subheading, 12, MUTED, false), blockParams(dp(5)));
        return block;
    }

    private GradientDrawable topStroke(int color, int border) {
        GradientDrawable bg = round(color, 0);
        bg.setStroke(dp(1), border);
        return bg;
    }

    private RippleDrawable touchBackground(int base, int radius) {
        return new RippleDrawable(ColorStateList.valueOf(Color.argb(30, 35, 105, 230)),
                round(base, radius), round(WHITE, radius));
    }

    private void selectTab(int index) {
        if (index < 0 || index > 2) return;
        activeTab = index;
        if (workScroll == null || recordsScroll == null || settingsScroll == null) return;
        workScroll.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        recordsScroll.setVisibility(index == 1 ? View.VISIBLE : View.GONE);
        settingsScroll.setVisibility(index == 2 ? View.VISIBLE : View.GONE);
        if (appBar != null) appBar.setVisibility(index == 0 ? View.VISIBLE : View.GONE);
        for (int i = 0; i < navItems.length; i++) {
            boolean active = i == index;
            navIcons[i].setImageTintList(ColorStateList.valueOf(active ? TEAL : MUTED));
            navLabels[i].setTextColor(active ? TEAL : MUTED);
            navItems[i].setBackground(touchBackground(Color.TRANSPARENT, dp(18)));
            navIcons[i].setBackground(round(active ? LIGHT_TEAL : Color.TRANSPARENT, dp(18)));
            navItems[i].setSelected(active);
        }
    }

    private void refreshArchiveListing(File selected) {
        if (archiveListing == null) return;
        archiveListing.removeAllViews();
        File[] archives = store.archives();
        if (archives.length == 0) {
            LinearLayout empty = new LinearLayout(this);
            empty.setGravity(android.view.Gravity.CENTER_VERTICAL);
            empty.setPadding(dp(14), dp(14), dp(14), dp(14));
            empty.setBackground(round(WHITE, dp(18)));
            empty.setElevation(dp(1));
            empty.addView(iconBox(R.drawable.ic_file_zip, dp(42), dp(13)));
            LinearLayout emptyCopy = vertical();
            LinearLayout.LayoutParams emptyCopyParams = new LinearLayout.LayoutParams(0, -2, 1);
            emptyCopyParams.leftMargin = dp(12);
            empty.addView(emptyCopy, emptyCopyParams);
            emptyCopy.addView(text("尚无采集记录", 13, INK, true));
            emptyCopy.addView(text("完成一次采集后会显示在这里", 11, MUTED, false), blockParams(dp(3)));
            archiveListing.addView(empty);
            return;
        }
        for (int i = 0; i < Math.min(archives.length, 12); i++) {
            File entry = archives[i];
            boolean active = selected != null
                    && entry.getAbsolutePath().equals(selected.getAbsolutePath());
            LinearLayout item = new LinearLayout(this);
            item.setGravity(android.view.Gravity.CENTER_VERTICAL);
            item.setPadding(dp(14), dp(13), dp(14), dp(13));
            item.setClickable(true);
            item.setFocusable(true);
            item.setBackground(touchBackground(active ? LIGHT_TEAL : WHITE, dp(20)));
            item.setElevation(dp(1));
            item.addView(iconBox(R.drawable.ic_file_zip, dp(46), dp(14)));
            TextView itemText = text(entry.getName()
                    + "\n" + captureIdentityLabel(entry)
                    + "\n" + size(entry.length())
                    + " · " + (store.isUploaded(
                    new SecretStore.FileInfo(entry.getAbsolutePath(), entry.length()))
                    ? "已上传" : "仅本地"), 12, active ? TEAL : INK, active);
            LinearLayout.LayoutParams itemTextParams = new LinearLayout.LayoutParams(0, -2, 1);
            itemTextParams.leftMargin = dp(12);
            item.addView(itemText, itemTextParams);
            TextView chevron = text("›", 26, MUTED, false);
            item.addView(chevron);
            archiveListing.addView(item, blockParams(dp(9)));
            item.setOnClickListener(v -> {
                if (busy) return;
                v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                store.saveArchive(entry.getAbsolutePath());
                refresh();
                notifyMessage("已切换到：" + entry.getName());
            });
        }
        if (archives.length > 12) {
            archiveListing.addView(text("其余 " + (archives.length - 12)
                    + " 个文件可通过「切换本地采集包」选择。", 11, MUTED, false),
                    blockParams(dp(8)));
        }
    }

    private ImageView iconBox(int drawable, int size, int padding) {
        ImageView icon = new ImageView(this);
        icon.setImageResource(drawable);
        icon.setImageTintList(ColorStateList.valueOf(TEAL));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setPadding(padding, padding, padding, padding);
        icon.setBackground(round(LIGHT_TEAL, dp(17)));
        icon.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return icon;
    }

    private LinearLayout metricTile(String label, int drawable) {
        LinearLayout tile = vertical();
        tile.setGravity(android.view.Gravity.CENTER);
        tile.setBackground(round(WHITE, dp(18)));
        tile.setElevation(dp(1));
        ImageView icon = new ImageView(this);
        icon.setImageResource(drawable);
        icon.setImageTintList(ColorStateList.valueOf(TEAL));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        tile.addView(icon, new LinearLayout.LayoutParams(dp(23), dp(23)));
        TextView caption = text(label, 11, MUTED, false);
        caption.setGravity(android.view.Gravity.CENTER);
        tile.addView(caption, blockParams(dp(5)));
        TextView value = text("—", 12, INK, true);
        value.setGravity(android.view.Gravity.CENTER);
        value.setTag("metric-value");
        tile.addView(value, blockParams(dp(3)));
        return tile;
    }

    private TextView metricValue(LinearLayout tile) {
        for (int i = 0; i < tile.getChildCount(); i++) {
            View child = tile.getChildAt(i);
            if (child instanceof TextView && "metric-value".equals(child.getTag())) {
                return (TextView) child;
            }
        }
        throw new IllegalStateException("metric value missing");
    }

    private Button actionTile(String caption, int drawable) {
        Button control = button(caption, false);
        control.setTextSize(11);
        control.setCompoundDrawablesWithIntrinsicBounds(0, drawable, 0, 0);
        control.setCompoundDrawablePadding(dp(7));
        control.setPadding(dp(5), dp(9), dp(5), dp(8));
        control.setGravity(android.view.Gravity.CENTER);
        return control;
    }

    private String getAppVersion() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "未知版本";
        }
    }

    @Override
    public void onBackPressed() {
        if (activeTab != 0) {
            selectTab(0);
        } else {
            super.onBackPressed();
        }
    }

    private void refreshStage() {
        stageRefresh.setEnabled(false);
        stageSource.setVisibility(View.GONE);
        stageDetails.setVisibility(View.GONE);
        stageCheckedAt.setVisibility(View.GONE);
        stageState.setText("正在核验 GitHub 阶段快照与当前固定输入…");
        worker.execute(() -> {
            try {
                StageClient.State state = new StageClient(store.repo(), store.loadToken()).latest();
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    String headline = state.title();
                    String content = headline + "\n" + state.build + "\n\n" + state.friendlyDetail() + "\n" + state.next();
                    android.text.SpannableString styled = new android.text.SpannableString(content);
                    styled.setSpan(new android.text.style.StyleSpan(Typeface.BOLD), 0, headline.length(),
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    styled.setSpan(new android.text.style.RelativeSizeSpan(1.18f), 0, headline.length(),
                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    stageState.setText(styled);
                    stageState.setTextColor(state.stage.equals("ABI_BLOCKED") ? WARNING : INK);
                    stageCheckedAt.setText("GitHub 最近核验 · " + state.verifiedAt);
                    stageCheckedAt.setVisibility(View.VISIBLE);
                    stageDetails.setVisibility(View.VISIBLE);
                    stageDetails.setOnClickListener(v -> new AlertDialog.Builder(this)
                            .setTitle("来源与核验详情")
                            .setMessage(state.technicalDetails())
                            .setPositiveButton("关闭", null).show());
                    if (state.stage.equals("LKG_ARCHIVED") || state.stage.equals("NO_NEW_OTA")) {
                        feedbackHint.setText("当前版本没有新的待测任务，不需要提交测试反馈。更新 OTA 后再按流程操作。");
                    } else if (state.stage.equals("TESTBOOT_READY") || state.stage.equals("WAITING_HUMAN")) {
                        feedbackHint.setText("已有待测候选；先在电脑临时启动并实际测试，再回来填写反馈。");
                    } else {
                        feedbackHint.setText("仅在电脑临时启动新的待测镜像并完成实际测试后提交。");
                    }
                    stageSource.setVisibility(View.VISIBLE);
                    stageSource.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/wekingchen/GKI_KernelSU_SUSFS/actions/runs/" + state.run))));
                    stageRefresh.setEnabled(true);
                });
            } catch (Exception error) {
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
                    stageState.setText("状态未验证\n" + error.getMessage() + "\n请核对网络与 PAT，或检查全链路可信阶段状态工作流。");
                    stageState.setTextColor(WARNING);
                    feedbackHint.setText("尚未确认当前是否有待测任务；请先刷新可信状态。");
                    stageRefresh.setEnabled(true);
                });
            }
        });
    }

    private LinearLayout card(LinearLayout parent, String step, String title, String sub) {
        LinearLayout card = vertical();
        card.setBackground(round(WHITE, dp(20)));
        card.setPadding(dp(18), dp(17), dp(18), dp(20));
        card.setElevation(dp(2));
        parent.addView(card, blockParams(dp(16)));
        LinearLayout header = new LinearLayout(this);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        card.addView(header);
        TextView badge = text(step, 12, TEAL, true);
        badge.setGravity(android.view.Gravity.CENTER);
        badge.setBackground(round(LIGHT_TEAL, dp(11)));
        header.addView(badge, new LinearLayout.LayoutParams(dp(35), dp(34)));
        LinearLayout heading = vertical();
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(0, -2, 1);
        hp.leftMargin = dp(11);
        header.addView(heading, hp);
        heading.addView(text(title, 17, INK, true));
        heading.addView(text(sub, 12, MUTED, false), blockParams(dp(3)));
        return card;
    }

    private LinearLayout vertical() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout.LayoutParams blockParams(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = top;
        return p;
    }

    private TextView text(String value, float sp, int color, boolean bold) {
        TextView item = new TextView(this);
        item.setText(value);
        item.setTextSize(sp);
        item.setTextColor(color);
        if (bold) item.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        item.setLineSpacing(dp(2), 1f);
        return item;
    }

    private EditText field(String hint, String value, boolean password) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(14);
        input.setTextColor(INK);
        input.setHintTextColor(MUTED);
        input.setHint(hint);
        input.setText(value);
        input.setPadding(dp(13), dp(12), dp(13), dp(12));
        input.setBackground(round(FIELD, dp(11)));
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | (password ? InputType.TYPE_TEXT_VARIATION_PASSWORD :
                InputType.TYPE_TEXT_VARIATION_NORMAL));
        return input;
    }

    private Button button(String caption, boolean primary) {
        Button b = new Button(this);
        b.setText(caption);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setLetterSpacing(0.015f);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(primary ? WHITE : TEAL);
        b.setBackground(touchBackground(primary ? TEAL : LIGHT_TEAL, dp(18)));
        b.setMinHeight(dp(primary ? 56 : 50));
        b.setMinimumHeight(dp(primary ? 56 : 50));
        b.setPadding(dp(14), 0, dp(14), 0);
        b.setStateListAnimator(null);
        b.setOnTouchListener((view, event) -> {
            if (!view.isEnabled()) return false;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
                    view.animate().scaleX(.975f).scaleY(.975f).setDuration(90).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(130).start();
                    break;
            }
            return false;
        });
        return b;
    }

    private RippleDrawable primaryGradientBackground() {
        GradientDrawable gradient = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.rgb(58, 129, 255), Color.rgb(35, 105, 230)});
        gradient.setCornerRadius(dp(18));
        return new RippleDrawable(
                ColorStateList.valueOf(Color.argb(34, 255, 255, 255)),
                gradient, round(WHITE, dp(18)));
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }

    @Override
    protected void onStart() {
        super.onStart();
        foreground = true;
        refresh();
        worker.execute(() -> {
            String os = RootCollector.currentOsVersion();
            runOnUiThread(() -> showBuildReminder(os));
        });
    }

    @Override
    protected void onStop() {
        foreground = false;
        tracker.stop();
        if (buildReminder != null) buildReminder.dismiss();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        tracker.stop();
        handler.removeCallbacksAndMessages(null);
        worker.shutdown();
        super.onDestroy();
    }
}
