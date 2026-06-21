package com.xposed.wetypehook

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import org.json.JSONObject
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.kyant.capsule.ContinuousRoundedRectangle
import com.xposed.wetypehook.wetype.graphics.WeTypeBloomStrokeDrawable
import com.xposed.wetypehook.wetype.graphics.WeTypeCornerRadii
import com.xposed.wetypehook.wetype.graphics.createWeTypeContinuousRoundedPath
import com.xposed.wetypehook.wetype.hook.SwipeAction
import com.xposed.wetypehook.wetype.hook.SwipePresets
import com.xposed.wetypehook.wetype.settings.DARK_KEY_COLOR_GROUP_ID
import com.xposed.wetypehook.wetype.settings.LIGHT_KEY_COLOR_GROUP_ID
import com.xposed.wetypehook.wetype.settings.WeTypeAppearanceColorGroups
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.TabRowWithContour
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.rememberTopAppBarState
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

const val EXTRA_OPEN_WETYPE_EMBEDDED_SETTINGS = "com.xposed.wetypehook.extra.OPEN_WETYPE_EMBEDDED_SETTINGS"
private const val ACTIVATION_HEARTBEAT_WINDOW_MS = 4_000L
private const val ACTIVATION_KEYBOARD_RETRY_COUNT = 3
private const val ACTIVATION_KEYBOARD_RETRY_DELAY_MS = 450L

private fun ModuleActivationTracker.ActivationStatus.hasFreshHeartbeat(
    now: Long = System.currentTimeMillis()
): Boolean {
    if (!isActive || lastActivatedAt <= 0L) return false
    return now - lastActivatedAt <= ACTIVATION_HEARTBEAT_WINDOW_MS
}

class MainActivity : ComponentActivity() {
    private var hasAttemptedEmbeddedLaunch = false
    private var activationStatusListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var activationStatus by mutableStateOf(
        ModuleActivationTracker.ActivationStatus(
            isActive = false,
            sourcePackage = null,
            sourceProcess = null,
            lastActivatedAt = 0L
        )
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activationStatus = ModuleActivationTracker.resolveStatusForUi(this)
        activationStatusListener = ModuleActivationTracker.registerStatusListener(this) { status ->
            activationStatus = status
            if (!status.hasFreshHeartbeat()) return@registerStatusListener
            runOnUiThread {
                launchEmbeddedSettingsAndFinish()
            }
        }
        setContent {
            ActivationEntryApp(
                isActive = activationStatus.hasFreshHeartbeat(),
                onOpenEmbeddedSettings = ::launchEmbeddedSettingsAndFinish
            )
        }
        launchEmbeddedSettingsIfActive()
    }

    override fun onResume() {
        super.onResume()
        activationStatus = ModuleActivationTracker.resolveStatusForUi(this)
        launchEmbeddedSettingsIfActive()
    }

    override fun onDestroy() {
        activationStatusListener?.let {
            ModuleActivationTracker.unregisterStatusListener(this, it)
            activationStatusListener = null
        }
        super.onDestroy()
    }

    private fun launchEmbeddedSettingsIfActive(): Boolean {
        if (!hasAttemptedEmbeddedLaunch && activationStatus.hasFreshHeartbeat()) {
            return launchEmbeddedSettingsAndFinish()
        }
        return false
    }

    private fun launchEmbeddedSettingsAndFinish(): Boolean {
        if (hasAttemptedEmbeddedLaunch) return false
        hasAttemptedEmbeddedLaunch = true
        val launched = openEmbeddedWeTypeSettings()
        if (launched) {
            finish()
        } else {
            hasAttemptedEmbeddedLaunch = false
        }
        return launched
    }

    private fun openEmbeddedWeTypeSettings(): Boolean {
        val launchIntents = listOfNotNull(
            packageManager.getLaunchIntentForPackage("com.tencent.wetype")?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_OPEN_WETYPE_EMBEDDED_SETTINGS, true)
            },
            Intent(Intent.ACTION_MAIN).apply {
                setPackage("com.tencent.wetype")
                addCategory(Intent.CATEGORY_LAUNCHER)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_OPEN_WETYPE_EMBEDDED_SETTINGS, true)
            },
            Intent().apply {
                component = ComponentName(
                    "com.tencent.wetype",
                    "com.tencent.wetype.plugin.hld.ui.ImeAboutActivity"
                )
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_OPEN_WETYPE_EMBEDDED_SETTINGS, true)
            }
        )

        for (intent in launchIntents) {
            val launched = runCatching {
                startActivity(intent)
                true
            }.getOrElse { false }
            if (launched) return true
        }

        Toast.makeText(this, "Failed to open WeType", Toast.LENGTH_SHORT).show()
        return false
    }
}

@Composable
private fun ActivationEntryApp(
    isActive: Boolean,
    onOpenEmbeddedSettings: () -> Unit
) {
    val darkMode = isSystemInDarkTheme()
    MiuixTheme(colors = if (darkMode) darkColorScheme() else lightColorScheme()) {
        SyncSystemBars(darkMode = darkMode)
        ActivationEntryScreen(
            isActive = isActive,
            onOpenEmbeddedSettings = onOpenEmbeddedSettings
        )
    }
}

@Composable
private fun ActivationEntryScreen(
    isActive: Boolean,
    onOpenEmbeddedSettings: () -> Unit
) {
    var probeText by rememberSaveable { mutableStateOf("") }
    var isCheckingHeartbeat by rememberSaveable { mutableStateOf(!isActive) }
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    LaunchedEffect(isActive) {
        if (isActive) {
            isCheckingHeartbeat = false
            return@LaunchedEffect
        }

        isCheckingHeartbeat = true
        repeat(ACTIVATION_KEYBOARD_RETRY_COUNT) {
            delay(ACTIVATION_KEYBOARD_RETRY_DELAY_MS)
            focusRequester.requestFocus()
            keyboardController?.show()
        }
        isCheckingHeartbeat = false
    }

    val backgroundColor = if (isSystemInDarkTheme()) {
        ComposeColor.Black
    } else {
        ComposeColor(0xFFF7F7F7)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(0.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = MiuixIcons.Info,
                    contentDescription = null,
                    tint = MiuixTheme.colorScheme.primary,
                    modifier = Modifier
                        .width(72.dp)
                        .height(72.dp)
                )
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    text = stringResource(
                        if (isActive) {
                            R.string.activation_active_title
                        } else {
                            R.string.activation_required_title
                        }
                    ),
                    style = MiuixTheme.textStyles.headline1,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(
                        if (isActive) {
                            R.string.activation_active_summary
                        } else {
                            R.string.activation_required_summary
                        }
                    ),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.main,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            insideMargin = PaddingValues(0.dp)
        ) {
            if (isActive) {
                BasicComponent(
                    title = stringResource(R.string.activation_open_embedded_settings),
                    titleColor = BasicComponentDefaults.titleColor(
                        color = MiuixTheme.colorScheme.primary
                    ),
                    onClick = onOpenEmbeddedSettings
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 20.dp)
                ) {
                    Text(
                        text = stringResource(
                            if (isCheckingHeartbeat) {
                                R.string.activation_detecting_summary
                            } else {
                                R.string.activation_probe_summary
                            }
                        ),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        style = MiuixTheme.textStyles.body2
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    TextField(
                        value = probeText,
                        onValueChange = { probeText = it },
                        label = stringResource(R.string.activation_probe_label),
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                    )
                }
            }
        }


    }
}

@Composable
internal fun WeTypeSettingsApp(
    settingsContext: Context
) {
    val darkMode = isSystemInDarkTheme()
    MiuixTheme(colors = if (darkMode) darkColorScheme() else lightColorScheme()) {
        SyncSystemBars(darkMode = darkMode)
        WeTypeSettingsScreen(
            settingsContext = settingsContext
        )
    }
}

@Composable
private fun SyncSystemBars(darkMode: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return

    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        val systemBarColor = if (darkMode) Color.BLACK else Color.parseColor("#F7F7F7")
        window.statusBarColor = systemBarColor
        window.navigationBarColor = systemBarColor
        val insetsController = WindowCompat.getInsetsController(window, view)
        insetsController.isAppearanceLightStatusBars = !darkMode
        insetsController.isAppearanceLightNavigationBars = !darkMode
    }
}

@Composable
private fun WeTypeSettingsScreen(
    settingsContext: Context
) {
    val context = LocalContext.current
    val preferencesContext = remember(settingsContext) { settingsContext }
    val isEmbeddedHost = remember(settingsContext) {
        (context.applicationContext ?: context).packageName != "com.xposed.wetypehook"
    }
    val snapshot = remember(preferencesContext) { WeTypeSettings.readSnapshot(preferencesContext) }
    var activationStatus by remember(preferencesContext) {
        mutableStateOf(ModuleActivationTracker.resolveStatusForUi(preferencesContext))
    }
    val systemDarkMode = isSystemInDarkTheme()
    val appearanceGroups = remember { WeTypeAppearanceColorGroups.groups }
    val appearanceSectionGroups = remember(appearanceGroups) {
        appearanceGroups.filterNot { it.isKeyColorGroup }
    }

    var lightColor by rememberSaveable { mutableIntStateOf(snapshot.lightColor) }
    var darkColor by rememberSaveable { mutableIntStateOf(snapshot.darkColor) }
    var blurRadius by rememberSaveable { mutableIntStateOf(snapshot.blurRadius) }
    var cornerRadius by rememberSaveable { mutableIntStateOf(snapshot.cornerRadius) }
    var keyCornerRadius by rememberSaveable { mutableIntStateOf(snapshot.keyCornerRadius) }
    var edgeHighlightEnabled by rememberSaveable { mutableStateOf(snapshot.edgeHighlightEnabled) }
    var edgeHighlightIntensity by rememberSaveable { mutableIntStateOf(snapshot.edgeHighlightIntensity) }
    var candidateBackgroundAlpha by rememberSaveable {
        mutableIntStateOf(snapshot.candidateBackgroundAlpha)
    }
    var candidateBackgroundCorner by rememberSaveable {
        mutableIntStateOf(snapshot.candidateBackgroundCorner.roundToInt())
    }
    var candidateBackgroundLeftMarginDp by rememberSaveable {
        mutableStateOf(snapshot.candidateBackgroundLeftMarginDp.toString())
    }
    var candidatePinyinLeftMarginDp by rememberSaveable {
        mutableStateOf(snapshot.candidatePinyinLeftMarginDp.toString())
    }
    var toolbarIconBgOpacity by rememberSaveable {
        mutableIntStateOf(snapshot.toolbarIconBgOpacity)
    }
    var disableHotUpdate by rememberSaveable {
        mutableStateOf(snapshot.disableHotUpdate)
    }
    var colorCustomizationEnabled by rememberSaveable {
        mutableStateOf(snapshot.colorCustomizationEnabled)
    }
    // 下滑手势状态
    var swipeEnabled by rememberSaveable { mutableStateOf(snapshot.swipeEnabled) }
    var swipeLightColor by rememberSaveable { mutableIntStateOf(snapshot.swipeLightColor) }
    var swipeDarkColor by rememberSaveable { mutableIntStateOf(snapshot.swipeDarkColor) }
    var swipeTextSize by rememberSaveable { mutableIntStateOf(snapshot.swipeTextSize) }
    var swipeThreshold by rememberSaveable { mutableIntStateOf(snapshot.swipeThreshold) }
    var swipeKeyMap by rememberSaveable { mutableStateOf(snapshot.swipeKeyMap) }
    val appearanceGroupColors = rememberSaveable(
        saver = listSaver(
            save = { it.toList() },
            restore = { restored -> mutableStateListOf(*restored.toTypedArray()) }
        )
    ) {
        mutableStateListOf(
            *appearanceGroups.map { group ->
                snapshot.appearanceColors[group.id] ?: group.defaultColor
            }.toTypedArray()
        )
    }
    var currentModeIsDark by rememberSaveable { mutableStateOf(systemDarkMode) }
    var colorInput by rememberSaveable {
        mutableStateOf(formatRgb(if (currentModeIsDark) darkColor else lightColor))
    }
    var alphaValue by rememberSaveable {
        mutableIntStateOf(Color.alpha(if (currentModeIsDark) darkColor else lightColor))
    }

    fun currentColor(): Int = if (currentModeIsDark) darkColor else lightColor

    fun syncEditorFromState() {
        alphaValue = Color.alpha(currentColor())
        colorInput = formatRgb(currentColor())
    }

    fun updateColorFromArgb(argb: Int) {
        if (currentModeIsDark) darkColor = argb else lightColor = argb
    }

    fun currentAppearanceColors(): Map<String, Int> = appearanceGroups.mapIndexed { index, group ->
        group.id to appearanceGroupColors[index]
    }.toMap()

    fun groupIndex(groupId: String): Int =
        appearanceGroups.indexOfFirst { it.id == groupId }

    fun keyColorGroup(isDark: Boolean) = appearanceGroups.first {
        it.id == if (isDark) {
            DARK_KEY_COLOR_GROUP_ID
        } else {
            LIGHT_KEY_COLOR_GROUP_ID
        }
    }

    fun keyColorValue(isDark: Boolean): Int {
        val group = keyColorGroup(isDark)
        return appearanceGroupColors[groupIndex(group.id)]
    }

    fun saveSettings(showSavedToast: Boolean = true) {
        WeTypeSettings.save(
            context = preferencesContext,
            lightColor = lightColor,
            darkColor = darkColor,
            blurRadius = blurRadius,
            cornerRadius = cornerRadius,
            keyCornerRadius = keyCornerRadius,
            edgeHighlightEnabled = edgeHighlightEnabled,
            edgeHighlightIntensity = edgeHighlightIntensity,
            candidateBackgroundAlpha = candidateBackgroundAlpha,
            candidateBackgroundCorner = candidateBackgroundCorner.toFloat(),
            candidateBackgroundLeftMarginDp = candidateBackgroundLeftMarginDp.toIntOrNull()
                ?: WeTypeSettings.DEFAULT_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP,
            candidatePinyinLeftMarginDp = candidatePinyinLeftMarginDp.toIntOrNull()
                ?: WeTypeSettings.DEFAULT_CANDIDATE_PINYIN_LEFT_MARGIN_DP,
            toolbarIconBgOpacity = toolbarIconBgOpacity,
            appearanceColors = currentAppearanceColors(),
            colorCustomizationEnabled = colorCustomizationEnabled,
            disableHotUpdate = disableHotUpdate,
            swipeEnabled = swipeEnabled,
            swipeLightColor = swipeLightColor,
            swipeDarkColor = swipeDarkColor,
            swipeTextSize = swipeTextSize,
            swipeThreshold = swipeThreshold,
            swipeKeyMap = swipeKeyMap
        )
        if (showSavedToast) {
            Toast.makeText(context, R.string.settings_saved, Toast.LENGTH_SHORT).show()
        }
    }

    fun restoreDefaults() {
        lightColor = WeTypeSettings.DEFAULT_LIGHT_COLOR
        darkColor = WeTypeSettings.DEFAULT_DARK_COLOR
        blurRadius = WeTypeSettings.DEFAULT_BLUR_RADIUS
        cornerRadius = WeTypeSettings.DEFAULT_CORNER_RADIUS
        keyCornerRadius = WeTypeSettings.DEFAULT_KEY_CORNER_RADIUS
        edgeHighlightEnabled = WeTypeSettings.DEFAULT_EDGE_HIGHLIGHT_ENABLED
        edgeHighlightIntensity = WeTypeSettings.DEFAULT_EDGE_HIGHLIGHT_INTENSITY
        candidateBackgroundAlpha = WeTypeSettings.DEFAULT_CANDIDATE_BACKGROUND_ALPHA
        candidateBackgroundCorner = WeTypeSettings.DEFAULT_CANDIDATE_BACKGROUND_CORNER.roundToInt()
        candidateBackgroundLeftMarginDp =
            WeTypeSettings.DEFAULT_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP.toString()
        candidatePinyinLeftMarginDp = WeTypeSettings.DEFAULT_CANDIDATE_PINYIN_LEFT_MARGIN_DP.toString()
        toolbarIconBgOpacity = WeTypeSettings.DEFAULT_TOOLBAR_ICON_BG_OPACITY
        colorCustomizationEnabled = WeTypeSettings.DEFAULT_COLOR_CUSTOMIZATION_ENABLED
        disableHotUpdate = WeTypeSettings.DEFAULT_DISABLE_HOT_UPDATE
        swipeEnabled = WeTypeSettings.DEFAULT_SWIPE_ENABLED
        swipeLightColor = WeTypeSettings.DEFAULT_SWIPE_LIGHT_COLOR
        swipeDarkColor = WeTypeSettings.DEFAULT_SWIPE_DARK_COLOR
        swipeTextSize = WeTypeSettings.DEFAULT_SWIPE_TEXT_SIZE
        swipeThreshold = WeTypeSettings.DEFAULT_SWIPE_THRESHOLD
        swipeKeyMap = ""
        appearanceGroups.forEachIndexed { index, group ->
            appearanceGroupColors[index] = group.defaultColor
        }
        syncEditorFromState()
        Toast.makeText(context, context.getString(R.string.settings_reset_toast), Toast.LENGTH_SHORT).show()
        saveSettings(showSavedToast = false)
    }

    if (isEmbeddedHost) {
        LaunchedEffect(preferencesContext) {
            ModuleActivationTracker.syncActivationFromUiContext(preferencesContext)
        }
    } else {
        DisposableEffect(preferencesContext) {
            val listener = ModuleActivationTracker.registerStatusListener(preferencesContext) {
                activationStatus = it
            }
            onDispose {
                ModuleActivationTracker.unregisterStatusListener(preferencesContext, listener)
            }
        }
    }

    val previewColor = currentColor()
    val scrollBehavior = MiuixScrollBehavior(state = rememberTopAppBarState())

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background),
        topBar = {
            TopAppBar(
                title = stringResource(R.string.settings_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    ModuleActivationTag(
                        status = activationStatus,
                    )
                },
                actions = {
                    IconButton(
                        onClick = { saveSettings() }
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Ok,
                            contentDescription = stringResource(R.string.settings_save_title)
                        )
                    }
                },
                bottomContent = {
                    PreviewSection(
                        color = previewColor,
                        blurRadius = blurRadius,
                        cornerRadius = cornerRadius,
                        keyCornerRadius = keyCornerRadius,
                        edgeHighlightEnabled = edgeHighlightEnabled,
                        edgeHighlightIntensity = edgeHighlightIntensity,
                        lightKeyColor = keyColorValue(false),
                        darkKeyColor = keyColorValue(true),
                        isDark = currentModeIsDark
                    )
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = paddingValues.calculateTopPadding(),
                bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 颜色分组
            item {
                SmallTitle(
                    text = stringResource(R.string.settings_group_color)
                )
                Card(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    insideMargin = PaddingValues(0.dp)
                ) {
                    Column {
                        // 自定义颜色开关 - 放在所有颜色配置顶部
                        MiuixSwitchWidget(
                            title = stringResource(R.string.settings_color_customization_title),
                            description = stringResource(R.string.settings_color_customization_desc),
                            checked = colorCustomizationEnabled,
                            onCheckedChange = { colorCustomizationEnabled = it }
                        )

                        if (colorCustomizationEnabled) {
                            HorizontalDivider()

                            // 模式切换 - 使用 TabRow
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_section_mode),
                                    style = MiuixTheme.textStyles.main
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                val tabs = listOf(
                                    stringResource(R.string.settings_light_mode),
                                    stringResource(R.string.settings_dark_mode)
                                )
                                TabRowWithContour(
                                    tabs = tabs,
                                    selectedTabIndex = if (currentModeIsDark) 1 else 0,
                                    onTabSelected = { index ->
                                        val newDarkMode = index == 1
                                        if (newDarkMode != currentModeIsDark) {
                                            currentModeIsDark = newDarkMode
                                            syncEditorFromState()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            // 透明度滑块
                            SliderPreferenceItem(
                                title = stringResource(R.string.settings_alpha_title),
                                value = alphaValue,
                                max = 255,
                                onValueChange = {
                                    alphaValue = it
                                    val rgb = currentColor() and 0xFFFFFF
                                    updateColorFromArgb((alphaValue shl 24) or rgb)
                                    colorInput = formatRgb(currentColor())
                                }
                            )

                            Column(
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.settings_custom_color),
                                    style = MiuixTheme.textStyles.main
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = stringResource(R.string.settings_color_helper),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    style = MiuixTheme.textStyles.body2
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                TextField(
                                    value = colorInput,
                                    onValueChange = { input ->
                                        val trimmed = input.trim()
                                        val hasPrefix = trimmed.startsWith("#")
                                        val body = trimmed.removePrefix("#")
                                        if (body.length > 6 || !body.matches(Regex("^[0-9a-fA-F]*$"))) {
                                            return@TextField
                                        }

                                        colorInput = if (hasPrefix || body.isNotEmpty()) "#$body" else ""

                                        if (body.length == 6) {
                                            runCatching {
                                                val opaque = Color.parseColor("#$body")
                                                val argb = Color.argb(
                                                    alphaValue.coerceIn(0, 255),
                                                    Color.red(opaque),
                                                    Color.green(opaque),
                                                    Color.blue(opaque)
                                                )
                                                updateColorFromArgb(argb)
                                            }
                                        }
                                    },
                                    label = stringResource(R.string.settings_color_label),
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            val currentKeyGroup = keyColorGroup(currentModeIsDark)
                            val currentKeyGroupIndex = groupIndex(currentKeyGroup.id)

                            KeyColorEditor(
                                title = if (currentModeIsDark) {
                                    stringResource(R.string.settings_dark_key_color_title)
                                } else {
                                    stringResource(R.string.settings_light_key_color_title)
                                },
                                summary = stringResource(
                                    R.string.settings_key_color_group_summary,
                                    Color.alpha(appearanceGroupColors[currentKeyGroupIndex]),
                                    currentKeyGroup.entryCount
                                ),
                                color = appearanceGroupColors[currentKeyGroupIndex],
                                onColorChange = { appearanceGroupColors[currentKeyGroupIndex] = it }
                            )
                        }
                    }
                }
            }

            // 外观分组
            item {
                SmallTitle(
                    text = stringResource(R.string.settings_group_appearance)
                )
                Card(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    insideMargin = PaddingValues(0.dp)
                ) {
                    Column {
                        MiuixSwitchWidget(
                            title = stringResource(R.string.settings_edge_highlight_title),
                            description = stringResource(R.string.settings_edge_highlight_desc),
                            checked = edgeHighlightEnabled,
                            onCheckedChange = { edgeHighlightEnabled = it }
                        )

                        if (edgeHighlightEnabled) {
                            SliderPreferenceItem(
                                title = stringResource(R.string.settings_edge_highlight_intensity_title),
                                value = edgeHighlightIntensity,
                                max = 200,
                                onValueChange = { edgeHighlightIntensity = it }
                            )
                        }

                        HorizontalDivider()

                        // 模糊滑块
                        SliderPreferenceItem(
                            title = stringResource(R.string.settings_blur_title),
                            value = blurRadius,
                            max = 100,
                            onValueChange = { blurRadius = it }
                        )

                        // 圆角滑块
                        SliderPreferenceItem(
                            title = stringResource(R.string.settings_corner_title),
                            value = cornerRadius,
                            max = WeTypeSettings.MAX_CORNER_RADIUS,
                            onValueChange = { cornerRadius = it }
                        )

                        SliderPreferenceItem(
                            title = stringResource(R.string.settings_key_corner_title),
                            value = keyCornerRadius,
                            max = WeTypeSettings.MAX_KEY_CORNER_RADIUS,
                            onValueChange = { keyCornerRadius = it }
                        )

                        SliderPreferenceItem(
                            title = stringResource(R.string.settings_toolbar_icon_bg_opacity_title),
                            value = toolbarIconBgOpacity,
                            max = 255,
                            onValueChange = { toolbarIconBgOpacity = it }
                        )

                        appearanceSectionGroups.forEach { group ->
                            val index = groupIndex(group.id)
                            AppearanceColorGroupEditor(
                                title = group.displayName,
                                summary = stringResource(
                                    R.string.settings_appearance_color_group_summary,
                                    group.entryCount,
                                    formatArgb(group.defaultColor)
                                ),
                                color = appearanceGroupColors[index],
                                onColorChange = { appearanceGroupColors[index] = it }
                            )
                        }

                        NumericTextSettingItem(
                            title = stringResource(R.string.settings_candidate_background_left_margin_title),
                            summary = stringResource(R.string.settings_candidate_background_left_margin_desc),
                            value = candidateBackgroundLeftMarginDp,
                            onValueChange = { input ->
                                if (sanitizeIntegerInput(input, maxLength = 2) != null) {
                                    candidateBackgroundLeftMarginDp = input
                                }
                            }
                        )

                        NumericTextSettingItem(
                            title = stringResource(R.string.settings_candidate_pinyin_margin_title),
                            summary = stringResource(R.string.settings_candidate_pinyin_margin_desc),
                            value = candidatePinyinLeftMarginDp,
                            onValueChange = { input ->
                                if (sanitizeIntegerInput(input, maxLength = 2) != null) {
                                    candidatePinyinLeftMarginDp = input
                                }
                            }
                        )

                        SliderPreferenceItem(
                            title = stringResource(R.string.settings_key_color_hook_alpha_title),
                            value = candidateBackgroundAlpha,
                            max = 255,
                            onValueChange = { candidateBackgroundAlpha = it }
                        )

                        SliderPreferenceItem(
                            title = stringResource(R.string.settings_candidate_corner_title),
                            value = candidateBackgroundCorner,
                            max = WeTypeSettings.MAX_CANDIDATE_BACKGROUND_CORNER,
                            onValueChange = { candidateBackgroundCorner = it }
                        )
                    }
                }
            }

            // 其他分组
            item {
                SmallTitle(
                    text = stringResource(R.string.settings_group_other)
                )
                Card(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    insideMargin = PaddingValues(0.dp)
                ) {
                    Column {
                        MiuixSwitchWidget(
                            title = stringResource(R.string.settings_disable_hot_update_title),
                            description = stringResource(R.string.settings_disable_hot_update_desc),
                            checked = disableHotUpdate,
                            onCheckedChange = { disableHotUpdate = it }
                        )
                    }
                }
            }

            // 下滑手势分组
            item {
                SmallTitle(
                    text = stringResource(R.string.swipe_group_title)
                )
                Card(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    insideMargin = PaddingValues(0.dp)
                ) {
                    Column {
                        MiuixSwitchWidget(
                            title = stringResource(R.string.swipe_enabled_title),
                            description = stringResource(R.string.swipe_enabled_desc),
                            checked = swipeEnabled,
                            onCheckedChange = { swipeEnabled = it }
                        )

                        if (swipeEnabled) {
                            HorizontalDivider()

                            // 标签颜色（浅色）
                            SwipeColorInput(
                                title = stringResource(R.string.swipe_light_color_title),
                                summary = stringResource(R.string.swipe_light_color_desc),
                                color = swipeLightColor,
                                onColorChange = { swipeLightColor = it }
                            )

                            HorizontalDivider()

                            // 标签颜色（深色）
                            SwipeColorInput(
                                title = stringResource(R.string.swipe_dark_color_title),
                                summary = stringResource(R.string.swipe_dark_color_desc),
                                color = swipeDarkColor,
                                onColorChange = { swipeDarkColor = it }
                            )

                            HorizontalDivider()

                            // 标签字号
                            SliderPreferenceItem(
                                title = stringResource(R.string.swipe_text_size_title),
                                value = swipeTextSize,
                                min = WeTypeSettings.MIN_SWIPE_TEXT_SIZE,
                                max = WeTypeSettings.MAX_SWIPE_TEXT_SIZE,
                                onValueChange = { swipeTextSize = it }
                            )

                            HorizontalDivider()

                            // 下滑灵敏度
                            SliderPreferenceItem(
                                title = stringResource(R.string.swipe_threshold_title),
                                value = swipeThreshold,
                                min = WeTypeSettings.MIN_SWIPE_THRESHOLD,
                                max = WeTypeSettings.MAX_SWIPE_THRESHOLD,
                                onValueChange = { swipeThreshold = it }
                            )

                            HorizontalDivider()

                            // 预设方案
                            Text(
                                text = stringResource(R.string.swipe_preset_title),
                                style = MiuixTheme.textStyles.main,
                                modifier = Modifier.padding(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 4.dp)
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                SmallPresetButton(
                                    text = stringResource(R.string.swipe_preset_edit),
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        swipeKeyMap = SwipePresets.presetToJson(SwipePresets.EDIT)
                                    }
                                )
                                SmallPresetButton(
                                    text = stringResource(R.string.swipe_preset_quick),
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        swipeKeyMap = SwipePresets.presetToJson(SwipePresets.QUICK)
                                    }
                                )
                                SmallPresetButton(
                                    text = stringResource(R.string.swipe_preset_clear),
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        swipeKeyMap = ""
                                    }
                                )
                            }
                            // 预设说明
                            Column(
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.swipe_preset_edit_desc),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    style = MiuixTheme.textStyles.body2
                                )
                                Text(
                                    text = stringResource(R.string.swipe_preset_quick_desc),
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    style = MiuixTheme.textStyles.body2
                                )
                            }

                            HorizontalDivider()

                            // 自定义按键映射
                            SwipeCustomConfigSection(
                                swipeKeyMap = swipeKeyMap,
                                onSwipeKeyMapChange = { swipeKeyMap = it }
                            )

                            HorizontalDivider()

                            // 手势预览
                            SwipePreviewSection(
                                swipeKeyMap = swipeKeyMap,
                                isDark = isSystemInDarkTheme(),
                                lightColor = swipeLightColor,
                                darkColor = swipeDarkColor,
                                swipeTextSize = swipeTextSize
                            )
                        }
                    }
                }
            }

            // 操作分组
            item {
                SmallTitle(
                    text = stringResource(R.string.settings_group_actions)
                )
                Card(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    insideMargin = PaddingValues(0.dp)
                ) {
                    Column {
                        ArrowPreference(
                            title = stringResource(R.string.settings_reset_title),
                            summary = stringResource(R.string.settings_reset_desc),
                            onClick = ::restoreDefaults
                        )

                        HorizontalDivider()

                        // 导出全部设置
                        BasicComponent(
                            title = stringResource(R.string.actions_export_title),
                            summary = stringResource(R.string.actions_export_desc),
                            onClick = {
                                exportAllConfig(
                                    context = context,
                                    lightColor = lightColor,
                                    darkColor = darkColor,
                                    blurRadius = blurRadius,
                                    cornerRadius = cornerRadius,
                                    keyCornerRadius = keyCornerRadius,
                                    edgeHighlightEnabled = edgeHighlightEnabled,
                                    edgeHighlightIntensity = edgeHighlightIntensity,
                                    candidateBackgroundAlpha = candidateBackgroundAlpha,
                                    candidateBackgroundCorner = candidateBackgroundCorner,
                                    candidateBackgroundLeftMarginDp = candidateBackgroundLeftMarginDp,
                                    candidatePinyinLeftMarginDp = candidatePinyinLeftMarginDp,
                                    toolbarIconBgOpacity = toolbarIconBgOpacity,
                                    appearanceColors = currentAppearanceColors(),
                                    colorCustomizationEnabled = colorCustomizationEnabled,
                                    disableHotUpdate = disableHotUpdate,
                                    swipeEnabled = swipeEnabled,
                                    swipeLightColor = swipeLightColor,
                                    swipeDarkColor = swipeDarkColor,
                                    swipeTextSize = swipeTextSize,
                                    swipeThreshold = swipeThreshold,
                                    swipeKeyMap = swipeKeyMap
                                )
                            }
                        )

                        HorizontalDivider()

                        // 导入全部设置
                        BasicComponent(
                            title = stringResource(R.string.actions_import_title),
                            summary = stringResource(R.string.actions_import_desc),
                            onClick = {
                                val loaded = importAllConfig(context)
                                if (loaded != null) {
                                    lightColor = loaded.lightColor
                                    darkColor = loaded.darkColor
                                    blurRadius = loaded.blurRadius
                                    cornerRadius = loaded.cornerRadius
                                    keyCornerRadius = loaded.keyCornerRadius
                                    edgeHighlightEnabled = loaded.edgeHighlightEnabled
                                    edgeHighlightIntensity = loaded.edgeHighlightIntensity
                                    candidateBackgroundAlpha = loaded.candidateBackgroundAlpha
                                    candidateBackgroundCorner = loaded.candidateBackgroundCorner.roundToInt()
                                    candidateBackgroundLeftMarginDp = loaded.candidateBackgroundLeftMarginDp.toString()
                                    candidatePinyinLeftMarginDp = loaded.candidatePinyinLeftMarginDp.toString()
                                    toolbarIconBgOpacity = loaded.toolbarIconBgOpacity
                                    colorCustomizationEnabled = loaded.colorCustomizationEnabled
                                    disableHotUpdate = loaded.disableHotUpdate
                                    swipeEnabled = loaded.swipeEnabled
                                    swipeLightColor = loaded.swipeLightColor
                                    swipeDarkColor = loaded.swipeDarkColor
                                    swipeTextSize = loaded.swipeTextSize
                                    swipeThreshold = loaded.swipeThreshold
                                    swipeKeyMap = loaded.swipeKeyMap
                                    loaded.appearanceColors.forEach { (id, color) ->
                                        val idx = appearanceGroups.indexOfFirst { it.id == id }
                                        if (idx >= 0) appearanceGroupColors[idx] = color
                                    }
                                    syncEditorFromState()
                                    saveSettings(showSavedToast = false)
                                    Toast.makeText(context, R.string.actions_import_success, Toast.LENGTH_SHORT).show()
                                }
                            }
                        )

                        HorizontalDivider()

                        BasicComponent(
                            title = stringResource(R.string.settings_visit_github_title),
                            titleColor = BasicComponentDefaults.titleColor(
                                color = MiuixTheme.colorScheme.primary
                            ),
                            onClick = {
                                val intent = Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/liulei92/WeTypeEnhancer")
                                )
                                context.startActivity(intent)
                            }
                        )
                    }
                }
            }

        }
    }
}

@Composable
private fun ModuleActivationTag(
    status: ModuleActivationTracker.ActivationStatus,
    modifier: Modifier = Modifier
) {
    val backgroundColor = if (status.isActive) {
        ComposeColor(0xFF4F9A71)
    } else {
        ComposeColor(0xFFC86F67)
    }
    val label = if (status.isActive) {
        stringResource(R.string.settings_module_active_tag)
    } else {
        stringResource(R.string.settings_module_inactive_tag)
    }
    Box(
        modifier = modifier
            .clip(ContinuousRoundedRectangle(999.dp))
            .background(backgroundColor)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = ComposeColor.White,
            style = MiuixTheme.textStyles.body2
        )
    }
}

@Composable
private fun PreviewSection(
    color: Int,
    blurRadius: Int,
    cornerRadius: Int,
    keyCornerRadius: Int,
    edgeHighlightEnabled: Boolean,
    edgeHighlightIntensity: Int,
    lightKeyColor: Int,
    darkKeyColor: Int,
    isDark: Boolean
) {
    Column(
        modifier = Modifier.padding(bottom = 16.dp)
    ) {
        SmallTitle(
            text = stringResource(R.string.settings_preview_title)
        )
        Card(
            modifier = Modifier.padding(horizontal = 16.dp),
            insideMargin = PaddingValues(0.dp),
            colors = CardDefaults.defaultColors(
                color = ComposeColor.Transparent
            )
        ) {
            Box(
                modifier = Modifier.fillMaxWidth()
            ) {
                Image(
                    painter = painterResource(R.drawable.natural_texture_004),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize()
                )
                Column {
                    PreviewCard(
                        color = color,
                        blurRadius = blurRadius,
                        cornerRadius = cornerRadius,
                        keyCornerRadius = keyCornerRadius,
                        edgeHighlightEnabled = edgeHighlightEnabled,
                        edgeHighlightIntensity = edgeHighlightIntensity,
                        lightKeyColor = lightKeyColor,
                        darkKeyColor = darkKeyColor,
                        isDark = isDark
                    )
                }
            }
        }
    }
}

@Composable
private fun PreviewCard(
    color: Int,
    blurRadius: Int,
    cornerRadius: Int,
    keyCornerRadius: Int,
    edgeHighlightEnabled: Boolean,
    edgeHighlightIntensity: Int,
    lightKeyColor: Int,
    darkKeyColor: Int,
    isDark: Boolean
) {
    val context = LocalContext.current
    val weTypeFontFamily = remember(context) {
        FontFamily(
            Font(
                path = "WE-Regular.ttf",
                assetManager = context.assets
            )
        )
    }
    val previewCornerValue = cornerRadius.coerceIn(0, WeTypeSettings.MAX_CORNER_RADIUS)
    val previewCorner = previewCornerValue.dp
    val previewMinHeight = maxOf(88.dp, (previewCornerValue * 2).dp)
    val previewShape = ContinuousRoundedRectangle(
        topStart = CornerSize(previewCorner),
        topEnd = CornerSize(previewCorner),
        bottomEnd = CornerSize(0.dp),
        bottomStart = CornerSize(0.dp)
    )
    val previewKeyColor = if (isDark) darkKeyColor else lightKeyColor
    val previewKeyShape = ContinuousRoundedRectangle(keyCornerRadius.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = 20.dp, end = 16.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = previewMinHeight)
                    .weTypePreviewBloom(
                        color = color,
                        cornerRadius = previewCorner,
                        edgeHighlightEnabled = edgeHighlightEnabled,
                        edgeHighlightIntensity = edgeHighlightIntensity,
                        isDark = isDark
                    )
                    .clip(previewShape)
            ) {
                Image(
                    painter = painterResource(R.drawable.natural_texture_004),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .matchParentSize()
                        .blur((blurRadius / 3f).coerceAtLeast(0f).dp)
                )
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(ComposeColor(color))
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isDark) stringResource(R.string.settings_preview_mode_dark) else stringResource(R.string.settings_preview_mode_light),
                            color = previewTextColor(color).copy(alpha = 0.7f),
                            style = MiuixTheme.textStyles.body2
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .clip(previewKeyShape)
                                    .background(ComposeColor(previewKeyColor))
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = formatArgb(color),
                                    color = previewTextColor(color),
                                    style = MiuixTheme.textStyles.headline1,
                                    fontFamily = weTypeFontFamily
                                )
                            }

                            for (i in 'A'..'C') {
                                Box(
                                    modifier = Modifier
                                        .clip(previewKeyShape)
                                        .background(ComposeColor(previewKeyColor))
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        text = i.toString(),
                                        color = previewTextColor(color),
                                        style = MiuixTheme.textStyles.headline1,
                                        fontFamily = weTypeFontFamily
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${stringResource(R.string.settings_blur_label)} $blurRadius · ${stringResource(R.string.settings_corner_label)} $cornerRadius",
                            color = previewTextColor(color).copy(alpha = 0.7f),
                            style = MiuixTheme.textStyles.body2
                        )
                    }
                }
            }
        }
    }
}


@Composable
private fun Modifier.weTypePreviewBloom(
    color: Int,
    cornerRadius: androidx.compose.ui.unit.Dp,
    edgeHighlightEnabled: Boolean,
    edgeHighlightIntensity: Int,
    isDark: Boolean
): Modifier {
    val context = LocalContext.current
    val density = LocalDensity.current
    val previewContext = remember(context, isDark) {
        createPreviewContext(context, isDark)
    }
    val cornerRadiusPx = with(density) { cornerRadius.toPx() }
    return this.drawWithCache {
        val previewCornerRadii = WeTypeCornerRadii(
            topLeft = cornerRadiusPx,
            topRight = cornerRadiusPx,
            bottomRight = 0f,
            bottomLeft = 0f
        )
        val widthPx = size.width.roundToInt()
        val heightPx = size.height.roundToInt()
        // The bloom overlay relies on clipPath + BlurMaskFilter + Path.op, which are not reliably
        // supported on Compose's hardware-accelerated recording canvas and crash the preview. Render
        // it once into an offscreen software bitmap (which supports every operation) and blit the
        // result, keeping the preview pixel-accurate.
        val overlayBitmap = if (edgeHighlightEnabled && widthPx > 0 && heightPx > 0) {
            runCatching {
                val bloomDrawable = WeTypeBloomStrokeDrawable(
                    context = previewContext,
                    cornerRadii = previewCornerRadii,
                    surfaceColor = color,
                    intensityScale = edgeHighlightIntensity / 100f
                )
                bloomDrawable.setBounds(0, 0, widthPx, heightPx)
                val clipPath = createWeTypeContinuousRoundedPath(
                    width = widthPx.toFloat(),
                    height = heightPx.toFloat(),
                    cornerRadii = previewCornerRadii
                )
                Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888).also { bitmap ->
                    val bitmapCanvas = Canvas(bitmap)
                    bitmapCanvas.clipPath(clipPath)
                    bloomDrawable.draw(bitmapCanvas)
                }
            }.getOrNull()
        } else {
            null
        }

        onDrawWithContent {
            drawContent()
            val bitmap = overlayBitmap ?: return@onDrawWithContent
            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.drawBitmap(bitmap, 0f, 0f, null)
            }
        }
    }
}

private fun createPreviewContext(baseContext: Context, isDark: Boolean): Context {
    val configuration = Configuration(baseContext.resources.configuration).apply {
        uiMode =
            (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (isDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    }
    return baseContext.createConfigurationContext(configuration)
}

@Composable
private fun AppearanceColorGroupEditor(
    title: String,
    summary: String,
    color: Int,
    onColorChange: (Int) -> Unit
) {
    var input by rememberSaveable(title) { mutableStateOf(formatArgb(color)) }

    LaunchedEffect(color) {
        val formatted = formatArgb(color)
        if (!input.equals(formatted, ignoreCase = true) && parseHexColor(input) != color) {
            input = formatted
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(32.dp)
                    .height(32.dp)
                    .clip(ContinuousRoundedRectangle(999.dp))
                    .background(ComposeColor(color))
                    .border(1.dp, MiuixTheme.colorScheme.outline, ContinuousRoundedRectangle(999.dp))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.main
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = summary,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextField(
            value = input,
            onValueChange = { raw ->
                val sanitized = sanitizeHexColorInput(raw) ?: return@TextField
                input = sanitized
                parseHexColor(sanitized)?.let(onColorChange)
            },
            label = stringResource(R.string.settings_appearance_color_input_label),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun KeyColorEditor(
    title: String,
    summary: String,
    color: Int,
    onColorChange: (Int) -> Unit
) {
    var input by rememberSaveable(title) { mutableStateOf(formatRgb(color)) }

    LaunchedEffect(color) {
        val formatted = formatRgb(color)
        val normalizedColor = Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
        if (!input.equals(formatted, ignoreCase = true) && parseRgbColor(input) != normalizedColor) {
            input = formatted
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp)
        ) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.main
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = summary,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.body2
            )
        }
        // Per-mode key opacity, baked directly into the key color's alpha channel (same logic as
        // the background opacity slider above).
        SliderPreferenceItem(
            title = stringResource(R.string.settings_key_opacity_title),
            value = Color.alpha(color),
            max = 255,
            onValueChange = { alpha ->
                onColorChange((alpha shl 24) or (color and 0xFFFFFF))
            }
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
        ) {
            TextField(
                value = input,
                onValueChange = { raw ->
                    val sanitized = sanitizeRgbColorInput(raw) ?: return@TextField
                    input = sanitized
                    parseRgbColor(sanitized)?.let { rgb ->
                        onColorChange((color and 0xFF000000.toInt()) or (rgb and 0xFFFFFF))
                    }
                },
                label = stringResource(R.string.settings_key_color_input_label),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun NumericTextSettingItem(
    title: String,
    summary: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.main
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = summary,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body2
        )
        Spacer(modifier = Modifier.height(12.dp))
        TextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            label = "DP"
        )
    }
}

@Composable
private fun SliderPreferenceItem(
    title: String,
    value: Int,
    min: Int = 0,
    max: Int,
    onValueChange: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MiuixTheme.textStyles.main,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "$value",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.main
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.roundToInt()) },
            valueRange = min.toFloat()..max.toFloat(),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun MiuixSwitchWidget(
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val toggleAction = {
        onCheckedChange(!checked)
    }

    BasicComponent(
        title = title,
        summary = description,
        onClick = toggleAction,
        endActions = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange
            )
        }
    )
}

// ===== 下滑手势辅助组件 =====

/** 小型预设按钮 */
@Composable
private fun SmallPresetButton(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    BasicComponent(
        title = text,
        modifier = modifier,
        onClick = onClick
    )
}

/** 下滑标签颜色输入 */
@Composable
private fun SwipeColorInput(
    title: String,
    summary: String,
    color: Int,
    onColorChange: (Int) -> Unit
) {
    var input by rememberSaveable(title) { mutableStateOf(formatArgb(color)) }

    LaunchedEffect(color) {
        val formatted = formatArgb(color)
        if (!input.equals(formatted, ignoreCase = true) && parseHexColor(input) != color) {
            input = formatted
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(24.dp)
                    .height(24.dp)
                    .clip(ContinuousRoundedRectangle(4.dp))
                    .background(ComposeColor(color))
                    .border(1.dp, MiuixTheme.colorScheme.outline, ContinuousRoundedRectangle(4.dp))
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.main
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = summary,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    style = MiuixTheme.textStyles.body2
                )
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        TextField(
            value = input,
            onValueChange = { raw ->
                val sanitized = sanitizeHexColorInput(raw) ?: return@TextField
                input = sanitized
                parseHexColor(sanitized)?.let(onColorChange)
            },
            label = "#AARRGGBB",
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

// ===== 下滑手势自定义配置组件 =====

/** 解析下滑键映射 JSON 字符串为 Map */
private fun parseSwipeKeyMap(json: String): Map<String, SwipeAction> {
    if (json.isBlank()) return emptyMap()
    return runCatching {
        val obj = JSONObject(json)
        val idToAction = SwipeAction.entries.associateBy { it.id }
        obj.keys().asSequence().mapNotNull { key ->
            val actionId = obj.optString(key, "")
            idToAction[actionId]?.let { action: SwipeAction -> key to action }
        }.toMap()
    }.getOrDefault(emptyMap())
}

/** 自定义按键映射配置 UI */
@Composable
private fun SwipeCustomConfigSection(
    swipeKeyMap: String,
    onSwipeKeyMapChange: (String) -> Unit
) {
    val currentConfig = remember(swipeKeyMap) { parseSwipeKeyMap(swipeKeyMap) }
    var selectedKey by remember { mutableStateOf("") }
    val qwertyRows = remember {
        listOf(
            listOf("Q","W","E","R","T","Y","U","I","O","P"),
            listOf("A","S","D","F","G","H","J","K","L"),
            listOf("Z","X","C","V","B","N","M")
        )
    }
    val t9Keys = remember { listOf("2","3","4","5","6","7","8","9") }

    fun updateKeyAction(key: String, action: SwipeAction?) {
        val mutable = currentConfig.toMutableMap()
        if (action != null) mutable[key] = action else mutable.remove(key)
        onSwipeKeyMapChange(SwipePresets.presetToJson(mutable))
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.swipe_custom_config_title),
            style = MiuixTheme.textStyles.main
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.swipe_custom_config_label),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body2
        )
        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.swipe_custom_config_select_key),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body2
        )
        Spacer(modifier = Modifier.height(8.dp))

        // QWERTY 键盘行
        for (row in qwertyRows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                row.forEach { key ->
                    val isSelected = selectedKey == key
                    val hasAction = currentConfig.containsKey(key)
                    Box(
                        modifier = Modifier
                            .padding(2.dp)
                            .clip(ContinuousRoundedRectangle(6.dp))
                            .background(
                                when {
                                    isSelected -> MiuixTheme.colorScheme.primary
                                    hasAction -> MiuixTheme.colorScheme.secondaryContainer
                                    else -> MiuixTheme.colorScheme.surfaceVariant
                                }
                            )
                            .clickable { selectedKey = key }
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = key,
                            style = MiuixTheme.textStyles.body2,
                            color = if (isSelected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // T9 数字键
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            t9Keys.forEach { key ->
                val isSelected = selectedKey == key
                val hasAction = currentConfig.containsKey(key)
                Box(
                    modifier = Modifier
                        .padding(2.dp)
                        .clip(ContinuousRoundedRectangle(6.dp))
                        .background(
                            when {
                                isSelected -> MiuixTheme.colorScheme.primary
                                hasAction -> MiuixTheme.colorScheme.secondaryContainer
                                else -> MiuixTheme.colorScheme.surfaceVariant
                            }
                        )
                        .clickable { selectedKey = key }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = key,
                        style = MiuixTheme.textStyles.body2,
                        color = if (isSelected) MiuixTheme.colorScheme.onPrimary else MiuixTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (selectedKey.isNotEmpty()) {
            // 显示选中按键的当前动作
            val currentAction = currentConfig[selectedKey]
            Text(
                text = stringResource(R.string.swipe_custom_config_select_action),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.body2
            )
            Spacer(modifier = Modifier.height(8.dp))

            // 显示所有可用动作
            SwipeAction.entries.forEach { action ->
                val isActiveAction = currentAction == action
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(ContinuousRoundedRectangle(8.dp))
                        .background(
                            if (isActiveAction) MiuixTheme.colorScheme.primaryContainer
                            else MiuixTheme.colorScheme.surfaceVariant
                        )
                        .clickable { updateKeyAction(selectedKey, action) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = action.labelZh,
                        style = MiuixTheme.textStyles.main,
                        modifier = Modifier.weight(1f)
                    )
                    if (isActiveAction) {
                        Icon(
                            imageVector = MiuixIcons.Ok,
                            contentDescription = null,
                            tint = MiuixTheme.colorScheme.primary
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            // 移除该按键配置（清空映射）
            if (currentAction != null) {
                Spacer(modifier = Modifier.height(4.dp))
                BasicComponent(
                    title = stringResource(R.string.swipe_custom_config_remove),
                    onClick = { updateKeyAction(selectedKey, null) }
                )
            }
        } else if (currentConfig.isEmpty()) {
            Text(
                text = stringResource(R.string.swipe_custom_config_no_data),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.body2
            )
        }
    }
}

/** 下滑手势效果预览 */
@Composable
private fun SwipePreviewSection(
    swipeKeyMap: String,
    isDark: Boolean,
    lightColor: Int,
    darkColor: Int,
    swipeTextSize: Int
) {
    val density = LocalDensity.current
    val configuredActions = remember(swipeKeyMap) { parseSwipeKeyMap(swipeKeyMap) }
    val labelColor = if (isDark) darkColor else lightColor
    val textSizePx = with(density) { swipeTextSize.sp.toPx() }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        Text(
            text = stringResource(R.string.swipe_preview_title),
            style = MiuixTheme.textStyles.main
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.swipe_preview_desc),
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            style = MiuixTheme.textStyles.body2
        )
        Spacer(modifier = Modifier.height(12.dp))

        if (configuredActions.isEmpty()) {
            Text(
                text = stringResource(R.string.swipe_no_keys_configured),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                style = MiuixTheme.textStyles.body2
            )
        } else {
            androidx.compose.foundation.Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .clip(ContinuousRoundedRectangle(8.dp))
                    .background(MiuixTheme.colorScheme.surfaceVariant)
            ) {
                val viewW = size.width
                val viewH = size.height
                val cellH = viewH / 4f

                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    color = labelColor
                    textSize = textSizePx
                    textAlign = android.graphics.Paint.Align.CENTER
                    isAntiAlias = true
                }

                val rows = listOf(
                    listOf("Q","W","E","R","T","Y","U","I","O","P"),
                    listOf("A","S","D","F","G","H","J","K","L"),
                    listOf("Z","X","C","V","B","N","M")
                )
                val rowCols = intArrayOf(10, 9, 7)
                val rowIndents = floatArrayOf(0f, 0.05f, 0.15f)

                for ((rowIdx, rowKeys) in rows.withIndex()) {
                    val cols = rowCols[rowIdx]
                    val indent = rowIndents[rowIdx]
                    val usableW = viewW * (1f - 2f * indent)
                    val cellW = usableW / cols
                    val offsetX = indent * viewW
                    val cy = (rowIdx + 1) * cellH + cellH / 2f

                    for ((colIdx, keyLabel) in rowKeys.withIndex()) {
                        val cx = offsetX + colIdx * cellW + cellW / 2f
                        val action = configuredActions[keyLabel]
                        if (action != null) {
                            drawRoundRect(
                                color = ComposeColor(0x33000000),
                                topLeft = Offset(cx - cellW / 2f + 2f, cy - cellH / 2f + 2f),
                                size = Size(cellW - 4f, cellH - 4f),
                                cornerRadius = CornerRadius(4f)
                            )
                            drawContext.canvas.nativeCanvas.drawText(
                                action.labelShort,
                                cx,
                                cy - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f,
                                paint
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 导出全部设置到剪贴板 */
private fun exportAllConfig(
    context: Context,
    lightColor: Int,
    darkColor: Int,
    blurRadius: Int,
    cornerRadius: Int,
    keyCornerRadius: Int,
    edgeHighlightEnabled: Boolean,
    edgeHighlightIntensity: Int,
    candidateBackgroundAlpha: Int,
    candidateBackgroundCorner: Int,
    candidateBackgroundLeftMarginDp: String,
    candidatePinyinLeftMarginDp: String,
    toolbarIconBgOpacity: Int,
    appearanceColors: Map<String, Int>,
    colorCustomizationEnabled: Boolean,
    disableHotUpdate: Boolean,
    swipeEnabled: Boolean,
    swipeLightColor: Int,
    swipeDarkColor: Int,
    swipeTextSize: Int,
    swipeThreshold: Int,
    swipeKeyMap: String
) {
    val json = JSONObject().apply {
        put("version", 1)
        put("lightColor", lightColor)
        put("darkColor", darkColor)
        put("blurRadius", blurRadius)
        put("cornerRadius", cornerRadius)
        put("keyCornerRadius", keyCornerRadius)
        put("edgeHighlightEnabled", edgeHighlightEnabled)
        put("edgeHighlightIntensity", edgeHighlightIntensity)
        put("candidateBackgroundAlpha", candidateBackgroundAlpha)
        put("candidateBackgroundCorner", candidateBackgroundCorner.toDouble())
        put("candidateBackgroundLeftMarginDp", candidateBackgroundLeftMarginDp.toIntOrNull() ?: WeTypeSettings.DEFAULT_CANDIDATE_BACKGROUND_LEFT_MARGIN_DP)
        put("candidatePinyinLeftMarginDp", candidatePinyinLeftMarginDp.toIntOrNull() ?: WeTypeSettings.DEFAULT_CANDIDATE_PINYIN_LEFT_MARGIN_DP)
        put("toolbarIconBgOpacity", toolbarIconBgOpacity)
        put("colorCustomizationEnabled", colorCustomizationEnabled)
        put("disableHotUpdate", disableHotUpdate)
        put("swipeEnabled", swipeEnabled)
        put("swipeLightColor", swipeLightColor)
        put("swipeDarkColor", swipeDarkColor)
        put("swipeTextSize", swipeTextSize)
        put("swipeThreshold", swipeThreshold)
        put("swipeKeyMap", swipeKeyMap)
        put("appearanceColors", JSONObject(appearanceColors))
    }
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("wetype_all_config", json.toString(2)))
    Toast.makeText(context, R.string.actions_export_success, Toast.LENGTH_SHORT).show()
}

/** 从剪贴板导入全部设置，返回 Snapshot，格式无效则返回 null */
private fun importAllConfig(context: Context): WeTypeSettings.Snapshot? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = clipboard.primaryClip ?: run {
        Toast.makeText(context, R.string.actions_import_error, Toast.LENGTH_SHORT).show()
        return null
    }
    val input = (clip.getItemAt(0)?.text?.toString() ?: "").trim()
    if (input.isBlank()) {
        Toast.makeText(context, R.string.actions_import_error, Toast.LENGTH_SHORT).show()
        return null
    }
    return try {
        val obj = JSONObject(input)
        if (obj.optInt("version", 0) != 1) error("Invalid version")
        val appearanceColorsObj = obj.optJSONObject("appearanceColors")
        WeTypeSettings.Snapshot(
            lightColor = obj.getInt("lightColor"),
            darkColor = obj.getInt("darkColor"),
            blurRadius = obj.getInt("blurRadius"),
            cornerRadius = obj.getInt("cornerRadius"),
            keyCornerRadius = obj.getInt("keyCornerRadius"),
            edgeHighlightEnabled = obj.getBoolean("edgeHighlightEnabled"),
            edgeHighlightIntensity = obj.getInt("edgeHighlightIntensity"),
            candidateBackgroundAlpha = obj.getInt("candidateBackgroundAlpha"),
            candidateBackgroundCorner = obj.getDouble("candidateBackgroundCorner").toFloat(),
            candidateBackgroundLeftMarginDp = obj.getInt("candidateBackgroundLeftMarginDp"),
            candidatePinyinLeftMarginDp = obj.getInt("candidatePinyinLeftMarginDp"),
            toolbarIconBgOpacity = obj.getInt("toolbarIconBgOpacity"),
            colorCustomizationEnabled = obj.getBoolean("colorCustomizationEnabled"),
            disableHotUpdate = obj.getBoolean("disableHotUpdate"),
            swipeEnabled = obj.getBoolean("swipeEnabled"),
            swipeLightColor = obj.getInt("swipeLightColor"),
            swipeDarkColor = obj.getInt("swipeDarkColor"),
            swipeTextSize = obj.getInt("swipeTextSize"),
            swipeThreshold = obj.getInt("swipeThreshold"),
            swipeKeyMap = obj.getString("swipeKeyMap"),
            appearanceColors = if (appearanceColorsObj != null) {
                appearanceColorsObj.keys().asSequence().mapNotNull { key ->
                    if (appearanceColorsObj.has(key)) key to appearanceColorsObj.getInt(key) else null
                }.toMap()
            } else emptyMap()
        )
    } catch (e: Exception) {
        Toast.makeText(context, R.string.actions_import_error, Toast.LENGTH_SHORT).show()
        null
    }
}

private fun previewTextColor(color: Int): ComposeColor =
    if (isLightColor(color)) ComposeColor.Black else ComposeColor.White

private fun formatRgb(color: Int): String = String.format("#%06X", color and 0xFFFFFF)

private fun formatArgb(color: Int): String = String.format("#%08X", color)

private fun sanitizeHexColorInput(input: String): String? {
    val trimmed = input.trim()
    val hasPrefix = trimmed.startsWith("#")
    val body = trimmed.removePrefix("#")
    if (body.length > 8 || !body.matches(Regex("^[0-9a-fA-F]*$"))) {
        return null
    }
    return if (hasPrefix || body.isNotEmpty()) "#$body" else ""
}

private fun sanitizeRgbColorInput(input: String): String? {
    val trimmed = input.trim()
    val hasPrefix = trimmed.startsWith("#")
    val body = trimmed.removePrefix("#")
    if (body.length > 6 || !body.matches(Regex("^[0-9a-fA-F]*$"))) {
        return null
    }
    return if (hasPrefix || body.isNotEmpty()) "#$body" else ""
}

private fun parseHexColor(input: String): Int? {
    val body = input.trim().removePrefix("#")
    return when (body.length) {
        6, 8 -> runCatching { Color.parseColor("#$body") }.getOrNull()
        else -> null
    }
}

private fun parseRgbColor(input: String): Int? {
    val body = input.trim().removePrefix("#")
    if (body.length != 6) return null
    return runCatching { Color.parseColor("#$body") }.getOrNull()
}

private fun sanitizeIntegerInput(input: String, maxLength: Int): String? {
    val trimmed = input.trim()
    if (trimmed.length > maxLength) return null
    if (trimmed.isNotEmpty() && !trimmed.matches(Regex("^\\d*$"))) return null
    return trimmed
}

private fun isLightColor(color: Int): Boolean {
    val luminance =
        (Color.red(color) * 0.299 + Color.green(color) * 0.587 + Color.blue(color) * 0.114) / 255
    return luminance > 0.5
}
