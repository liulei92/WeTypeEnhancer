package com.xposed.wetypehook.wetype.hook

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.inputmethodservice.InputMethodService
import android.provider.Settings
import android.util.TypedValue
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.EditorInfo
import com.xposed.wetypehook.xposed.Log
import com.xposed.wetypehook.xposed.findMethod
import com.xposed.wetypehook.xposed.hookAfter
import com.xposed.wetypehook.xposed.hookBefore
import com.xposed.wetypehook.xposed.invokeMethodAs
import com.xposed.wetypehook.xposed.loadClassOrNull
import com.xposed.wetypehook.xposed.sameAs
import com.xposed.wetypehook.wetype.settings.WeTypeSettings
import org.json.JSONObject

// ============================================================
// SwipeAction 枚举：定义所有可用的下滑手势动作
// ============================================================
enum class SwipeAction(
    val id: String,
    val labelZh: String,      // UI 中显示的完整名称
    val labelShort: String     // 按键上显示的缩写（双字）
) {
    SELECT_ALL("select_all", "全选", "全选"),
    COPY("copy", "复制", "复制"),
    CUT("cut", "剪切", "剪切"),
    PASTE("paste", "粘贴", "粘贴"),
    CLEAR("clear", "清空内容", "清空"),
    LINE_START("line_start", "段首", "段首"),
    LINE_END("line_end", "段尾", "段尾"),
    CLIPBOARD("clipboard", "打开剪贴板", "剪板"),
    HIDE_KEYBOARD("hide_keyboard", "隐藏键盘", "隐藏"),
    SWITCH_IME("switch_ime", "切换输入法", "切换"),
    SETTINGS("settings", "输入法设置", "设置")
}

// ============================================================
// 预设方案
// ============================================================
object SwipePresets {
    /** 文本编辑方案：全选/复制/剪切/粘贴/清空/段首/段尾 */
    val EDIT: Map<String, SwipeAction> = mapOf(
        "Q" to SwipeAction.COPY, "W" to SwipeAction.CUT,
        "E" to SwipeAction.PASTE, "R" to SwipeAction.SELECT_ALL,
        "T" to SwipeAction.CLEAR,
        "A" to SwipeAction.LINE_START, "S" to SwipeAction.LINE_END,
        "Z" to SwipeAction.COPY, "X" to SwipeAction.CUT,
        "C" to SwipeAction.PASTE,
        "5" to SwipeAction.SELECT_ALL, "8" to SwipeAction.PASTE
    )

    /** 快捷操作方案：剪贴板/设置/隐藏键盘/切换输入法 */
    val QUICK: Map<String, SwipeAction> = mapOf(
        "Q" to SwipeAction.CLIPBOARD, "W" to SwipeAction.SETTINGS,
        "E" to SwipeAction.COPY, "R" to SwipeAction.PASTE,
        "A" to SwipeAction.HIDE_KEYBOARD, "S" to SwipeAction.SWITCH_IME,
        "Z" to SwipeAction.CLIPBOARD,
        "5" to SwipeAction.COPY, "8" to SwipeAction.PASTE
    )

    /** 清空方案：空映射 */
    val EMPTY: Map<String, SwipeAction> = emptyMap()

    /** 将方案序列化为 JSON 字符串 */
    fun presetToJson(preset: Map<String, SwipeAction>): String {
        if (preset.isEmpty()) return ""
        return JSONObject().apply {
            preset.forEach { (key, action) -> put(key, action.id) }
        }.toString()
    }

    /** 通过名称获取预设 */
    fun byName(name: String): Map<String, SwipeAction> = when (name) {
        "edit" -> EDIT
        "quick" -> QUICK
        else -> EMPTY
    }
}

// ============================================================
// 键盘键位辅助数据结构
// ============================================================
private data class KeyPos(val row: Int, val col: Int)

/** QWERTY 26 个字母键的位置映射（第0~2行） */
private val QWERTY_KEYS = mapOf(
    "Q" to KeyPos(0, 0), "W" to KeyPos(0, 1), "E" to KeyPos(0, 2),
    "R" to KeyPos(0, 3), "T" to KeyPos(0, 4), "Y" to KeyPos(0, 5),
    "U" to KeyPos(0, 6), "I" to KeyPos(0, 7), "O" to KeyPos(0, 8),
    "P" to KeyPos(0, 9),
    "A" to KeyPos(1, 0), "S" to KeyPos(1, 1), "D" to KeyPos(1, 2),
    "F" to KeyPos(1, 3), "G" to KeyPos(1, 4), "H" to KeyPos(1, 5),
    "J" to KeyPos(1, 6), "K" to KeyPos(1, 7), "L" to KeyPos(1, 8),
    "Z" to KeyPos(2, 0), "X" to KeyPos(2, 1), "C" to KeyPos(2, 2),
    "V" to KeyPos(2, 3), "B" to KeyPos(2, 4), "N" to KeyPos(2, 5),
    "M" to KeyPos(2, 6)
)

/** QWERTY 各行键数与缩进比例 */
private val QWERTY_ROW_COLS = intArrayOf(10, 9, 7)
private val QWERTY_ROW_INDENTS = floatArrayOf(0f, 0.05f, 0.15f)  // 缩进占容器宽度的比例

/** T9 可配按键的位置映射（第1~3行，3列） */
private val T9_KEYS = mapOf(
    "2" to KeyPos(1, 0), "3" to KeyPos(1, 1),
    "4" to KeyPos(2, 0), "5" to KeyPos(2, 1), "6" to KeyPos(2, 2),
    "7" to KeyPos(3, 0), "8" to KeyPos(3, 1), "9" to KeyPos(3, 2)
)

// ============================================================
// 闪烁状态
// ============================================================
private const val FLASH_DURATION_MS = 100L  // 总闪烁 100ms
private const val FLASH_HIDE_MS = 50L       // 前 50ms 隐藏

// ============================================================
// 主 Hook 对象
// ============================================================
internal object WeTypeSwipeGestureHooks {

    // ---- 当前键盘状态（IME进程同一时间只有一个键盘实例） ----
    private var trackedKeyboardView: View? = null
    private var currentIMS: InputMethodService? = null

    // 触摸状态
    private var intercepted: Boolean = false
    private var touchDownY: Float = 0f
    private var touchDownKey: String? = null

    // 闪烁状态
    private var flashStartMs: Long = 0L

    // dispatch hook 是否已初始化（只 hook 一次）
    @Volatile
    private var dispatchHooksInitialized: Boolean = false

    // 缓存的 Paint 对象（避免每次绘制重复创建）
    private var cachedPaint: Paint? = null
    private var lastColor: Int = 0
    private var lastTextSizePx: Float = 0f

    private fun resetTouch() {
        intercepted = false
        touchDownY = 0f
        touchDownKey = null
    }

    /** 闪烁判断：前50ms隐藏，后50ms显示 */
    private fun shouldDrawLabel(): Boolean {
        if (flashStartMs == 0L) return true
        val elapsed = System.currentTimeMillis() - flashStartMs
        if (elapsed >= FLASH_DURATION_MS) {
            flashStartMs = 0L
            return true
        }
        return elapsed >= FLASH_HIDE_MS
    }

    // ============================================================
    // 入口：注册所有 Hook
    // ============================================================
    fun hookSwipeGesture() {
        hookIMSLifecycle()
        Log.i("Success: Hook swipe gesture")
    }

    /**
     * 在获取到键盘 View 实例后，动态 hook 其实际类的 dispatch 方法。
     * 使用 [findMethodInHierarchy] 找到最子类的重写版本，确保 hook 能触发。
     */
    private fun initDynamicDispatchHooks(viewInstance: View) {
        if (dispatchHooksInitialized) return
        dispatchHooksInitialized = true

        val clazz = viewInstance.javaClass
        Log.i("Swipe gesture: initializing dispatch hooks on ${clazz.name}")

        // Hook dispatchDraw（hookAfter 绘制标签）
        runCatching {
            val dispatchDrawMethod = clazz.findMethodInHierarchy {
                name == "dispatchDraw" && parameterTypes.sameAs(Canvas::class.java)
            }
            dispatchDrawMethod.hookAfter { param ->
                if (!WeTypeSettings.isSwipeEnabledXposed()) return@hookAfter
                val view = param.thisObject as? View ?: return@hookAfter
                if (view !== trackedKeyboardView) return@hookAfter
                val canvas = param.args.getOrNull(0) as? Canvas ?: return@hookAfter

                // 闪烁处理
                if (!shouldDrawLabel()) return@hookAfter

                // 绘制标签
                drawKeyLabels(view, canvas)
            }
            Log.i("Success: Hook dispatchDraw on ${clazz.name}")
        }.onFailure {
            Log.e("Failed: Hook dispatchDraw for swipe gesture")
            Log.i(it)
        }

        // Hook dispatchTouchEvent（hookBefore 检测下滑）
        runCatching {
            val dispatchTouchEventMethod = clazz.findMethodInHierarchy {
                name == "dispatchTouchEvent" && parameterTypes.sameAs(MotionEvent::class.java)
            }
            dispatchTouchEventMethod.hookBefore { param ->
                if (!WeTypeSettings.isSwipeEnabledXposed()) return@hookBefore
                val view = param.thisObject as? View ?: return@hookBefore
                if (view !== trackedKeyboardView) return@hookBefore

                val event = param.args.getOrNull(0) as? MotionEvent ?: return@hookBefore

                val action = event.action and MotionEvent.ACTION_MASK
                when (action) {
                    MotionEvent.ACTION_DOWN -> {
                        resetTouch()
                        touchDownY = event.y
                        touchDownKey = estimateKeyAt(view, event.x, event.y)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (intercepted) return@hookBefore
                        val dy = event.y - touchDownY
                        val thresholdDp = WeTypeSettings.getSwipeThresholdXposed()
                        val thresholdPx = TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_DIP,
                            thresholdDp.toFloat(),
                            view.context.resources.displayMetrics
                        )
                        // 下滑超过阈值则拦截，仅在触发了已配置的按键时有效
                        if (dy >= thresholdPx) {
                            val key = touchDownKey ?: return@hookBefore
                            val keyMap = parseKeyMap(WeTypeSettings.getSwipeKeyMapXposed())
                            val actionConfig = keyMap[key] ?: return@hookBefore
                            intercepted = true
                            // 将事件替换为 ACTION_CANCEL，让键盘重置状态
                            val cancelEvent = MotionEvent.obtain(
                                event.downTime, event.eventTime,
                                MotionEvent.ACTION_CANCEL,
                                event.x, event.y, event.metaState
                            )
                            param.args[0] = cancelEvent

                            // 执行按键对应的动作
                            val service = currentIMS
                            if (service != null) {
                                executeAction(actionConfig, service, view.context)
                                // 触发闪烁反馈
                                flashStartMs = System.currentTimeMillis()
                                view.invalidate()
                            }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        resetTouch()
                    }
                }
            }
            Log.i("Success: Hook dispatchTouchEvent on ${clazz.name}")
        }.onFailure {
            Log.e("Failed: Hook dispatchTouchEvent for swipe gesture")
            Log.i(it)
        }
    }

    // ============================================================
    // dispatchDraw hookAfter：在键盘绘制完成后绘制标签
    // ============================================================
    // 移至 initDynamicDispatchHooks，在获取键盘 View 实例后动态注册

    // ============================================================
    // IMS 生命周期 Hook：跟踪键盘容器 View
    // ============================================================
    private fun hookIMSLifecycle() {
        runCatching {
            val imsClass = loadClassOrNull("android.inputmethodservice.InputMethodService")
                ?: error("InputMethodService class not found")

            // onStartInputView：获取键盘容器 View 并注册
            imsClass.findMethod {
                name == "onStartInputView" && parameterTypes.sameAs(
                    EditorInfo::class.java, java.lang.Boolean.TYPE
                )
            }.hookAfter { param ->
                val ims = param.thisObject as? InputMethodService ?: return@hookAfter
                currentIMS = ims
                val keyboardView = ims.invokeMethodAs<View>("getCurrentInputView")
                if (keyboardView != null) {
                    trackedKeyboardView = keyboardView
                    initDynamicDispatchHooks(keyboardView)
                    Log.i("Swipe gesture: tracked keyboard view registered, class=${keyboardView.javaClass.name}")
                } else {
                    Log.i("Swipe gesture: keyboard view is null in onStartInputView")
                }
            }

            // onFinishInputView：清理
            imsClass.findMethod {
                name == "onFinishInputView" && parameterTypes.sameAs(java.lang.Boolean.TYPE)
            }.hookAfter {
                currentIMS = null
                trackedKeyboardView = null
                resetTouch()
                flashStartMs = 0L
                dispatchHooksInitialized = false
                // 清理缓存的 Paint 对象，确保下次使用时重新创建
                cachedPaint = null
                lastColor = 0
                lastTextSizePx = 0f
            }

            // onDestroy：完整清理
            imsClass.findMethod {
                name == "onDestroy" && parameterTypes.isEmpty()
            }.hookAfter {
                currentIMS = null
                trackedKeyboardView = null
                resetTouch()
                flashStartMs = 0L
                dispatchHooksInitialized = false
                // 清理缓存的 Paint 对象
                cachedPaint = null
                lastColor = 0
                lastTextSizePx = 0f
            }
        }.onFailure {
            Log.e("Failed: Hook IMS lifecycle for swipe gesture")
            Log.i(it)
        }
    }

    // ============================================================
    // 标签绘制
    // ============================================================
    private fun drawKeyLabels(view: View, canvas: Canvas) {
        val context = view.context
        val isT9 = isT9Keyboard(view.width, view.height)
        val keyMap = if (isT9) T9_KEYS else QWERTY_KEYS
        val configuredActions = parseKeyMap(WeTypeSettings.getSwipeKeyMapXposed())
        if (configuredActions.isEmpty()) return

        // 确定标签颜色（根据深色/浅色模式）
        val isDark = (context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val labelColor = if (isDark) {
            WeTypeSettings.getSwipeDarkColorXposed()
        } else {
            WeTypeSettings.getSwipeLightColorXposed()
        }
        val textSizeSp = WeTypeSettings.getSwipeTextSizeXposed()
        val textSizePx = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            textSizeSp.toFloat(),
            context.resources.displayMetrics
        )

        // 使用缓存的 Paint 对象，仅在配置变更时重新创建
        val paint = getCachedPaint(labelColor, textSizePx)

        for ((keyLabel, action) in configuredActions) {
            val pos = keyMap[keyLabel] ?: continue
            val (cx, cy) = keyCenter(keyLabel, pos, view.width, view.height, isT9)
            // 使用 fontMetrics 精确垂直居中，确保双字标签位置准确
            val textY = cy - (paint.fontMetrics.ascent + paint.fontMetrics.descent) / 2f
            canvas.drawText(action.labelShort, cx, textY, paint)
        }
    }

    /** 获取缓存的 Paint 对象，仅在颜色或字号变更时重新创建 */
    private fun getCachedPaint(color: Int, textSizePx: Float): Paint {
        val cached = cachedPaint
        if (cached != null && lastColor == color && lastTextSizePx == textSizePx) {
            return cached
        }
        // 配置变更，创建新的 Paint
        val newPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            this.textSize = textSizePx
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
        cachedPaint = newPaint
        lastColor = color
        lastTextSizePx = textSizePx
        return newPaint
    }

    // ============================================================
    // 按键位置估算
    // ============================================================
    /** 判断是否为 T9 布局（宽高比小于 1.2 时为 T9） */
    private fun isT9Keyboard(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && width < height * 1.2f

    /** 估算按键中心坐标 */
    private fun keyCenter(
        key: String, pos: KeyPos, viewW: Int, viewH: Int, isT9: Boolean
    ): Pair<Float, Float> {
        return if (isT9) {
            // T9：5 行 × 3 列
            val rows = 5
            val cols = 3
            val cellW = viewW.toFloat() / cols
            val cellH = viewH.toFloat() / rows
            (pos.col * cellW + cellW / 2f) to (pos.row * cellH + cellH / 2f)
        } else {
            // QWERTY：4 行（含数字行），字母行从第 1 行开始
            val rows = 4
            val cellH = viewH.toFloat() / rows
            val rowCols = QWERTY_ROW_COLS[pos.row]
            val indent = QWERTY_ROW_INDENTS[pos.row]
            val usableW = viewW.toFloat() * (1f - 2f * indent)
            val cellW = usableW / rowCols
            val offsetX = indent * viewW.toFloat()
            val cx = offsetX + pos.col * cellW + cellW / 2f
            val cy = (pos.row + 1) * cellH + cellH / 2f  // +1 跳过数字行
            cx to cy
        }
    }

    /** 估算触摸点对应的按键标签 */
    private fun estimateKeyAt(view: View, x: Float, y: Float): String? {
        // 边界检查：确保触摸坐标在有效范围内
        if (x < 0 || y < 0 || x >= view.width || y >= view.height) {
            return null
        }

        val isT9 = isT9Keyboard(view.width, view.height)
        val keyMap = if (isT9) T9_KEYS else QWERTY_KEYS

        var nearestKey: String? = null
        var nearestDistance = Float.MAX_VALUE

        for ((keyLabel, pos) in keyMap) {
            val (cx, cy) = keyCenter(keyLabel, pos, view.width, view.height, isT9)
            val cellW = if (isT9) {
                view.width.toFloat() / 3f
            } else {
                val indent = QWERTY_ROW_INDENTS[pos.row]
                val usableW = view.width.toFloat() * (1f - 2f * indent)
                usableW / QWERTY_ROW_COLS[pos.row]
            }
            val cellH = if (isT9) {
                view.height.toFloat() / 5f
            } else {
                view.height.toFloat() / 4f
            }
            val halfW = cellW / 2f
            val halfH = cellH / 2f

            // 检查触摸点是否在该按键范围内
            if (x in (cx - halfW)..(cx + halfW) && y in (cy - halfH)..(cy + halfH)) {
                // 计算触摸点到按键中心的距离（使用平方距离避免开方运算）
                val dx = x - cx
                val dy = y - cy
                val distanceSquared = dx * dx + dy * dy

                // 记录最近的按键
                if (distanceSquared < nearestDistance) {
                    nearestDistance = distanceSquared
                    nearestKey = keyLabel
                }
            }
        }
        return nearestKey
    }

    // ============================================================
    // JSON 键映射解析
    // ============================================================
    private fun parseKeyMap(json: String): Map<String, SwipeAction> {
        if (json.isBlank()) return emptyMap()
        return runCatching {
            val obj = JSONObject(json)
            val idToAction = SwipeAction.entries.associateBy { it.id }
            obj.keys().asSequence().mapNotNull { key ->
                val actionId = obj.optString(key, "")
                idToAction[actionId]?.let { action -> key to action }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    // ============================================================
    // 动作执行
    // ============================================================
    private fun executeAction(action: SwipeAction, service: InputMethodService, context: Context) {
        try {
            when (action) {
                SwipeAction.SELECT_ALL -> {
                    service.currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
                }
                SwipeAction.COPY -> {
                    service.currentInputConnection?.performContextMenuAction(android.R.id.copy)
                }
                SwipeAction.CUT -> {
                    service.currentInputConnection?.performContextMenuAction(android.R.id.cut)
                }
                SwipeAction.PASTE -> {
                    service.currentInputConnection?.performContextMenuAction(android.R.id.paste)
                }
                SwipeAction.CLEAR -> {
                    // 全选后剪切 = 清空内容
                    val conn = service.currentInputConnection
                    conn?.let { ic ->
                        ic.performContextMenuAction(android.R.id.selectAll)
                        ic.performContextMenuAction(android.R.id.cut)
                    }
                }
                SwipeAction.LINE_START -> {
                    sendKeyEvent(service, KeyEvent.KEYCODE_MOVE_HOME)
                }
                SwipeAction.LINE_END -> {
                    sendKeyEvent(service, KeyEvent.KEYCODE_MOVE_END)
                }
                SwipeAction.CLIPBOARD -> {
                    // 打开系统剪贴板（无标准 Intent，尝试反射调用 WeType 内部 Activity）
                    openClipboard(context)
                }
                SwipeAction.HIDE_KEYBOARD -> {
                    hideKeyboard(service)
                }
                SwipeAction.SWITCH_IME -> {
                    switchIME(service)
                }
                SwipeAction.SETTINGS -> {
                    openIMESettings(context)
                }
            }
        } catch (e: Exception) {
            Log.e("Failed: Execute swipe action ${action.id}")
            Log.i(e)
        }
    }

    private fun sendKeyEvent(service: InputMethodService, keyCode: Int) {
        val conn = service.currentInputConnection ?: return
        val eventDown = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        val eventUp = KeyEvent(KeyEvent.ACTION_UP, keyCode)
        conn.sendKeyEvent(eventDown)
        conn.sendKeyEvent(eventUp)
    }

    private fun hideKeyboard(service: InputMethodService) {
        try {
            // 尝试获取 window token（反射调用非公共 API，需做好兼容性处理）
            val token = runCatching {
                val softInputWindow = service.invokeMethodAs<Any>("getWindow")
                softInputWindow?.invokeMethodAs<Window>("getWindow")?.decorView?.windowToken
            }.getOrNull()

            if (token != null) {
                val imm = service.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(token, 0)
                Log.i("Success: Hide keyboard via swipe")
            } else {
                Log.w("Failed: Cannot get window token for hiding keyboard")
            }
        } catch (e: Exception) {
            Log.e("Failed: Hide keyboard via swipe (API compatibility issue)")
            Log.i(e)
        }
    }

    private fun switchIME(service: InputMethodService) {
        try {
            // 尝试获取 window token（反射调用非公共 API，需做好兼容性处理）
            val token = runCatching {
                val softInputWindow = service.invokeMethodAs<Any>("getWindow")
                softInputWindow?.invokeMethodAs<Window>("getWindow")?.decorView?.windowToken
            }.getOrNull()

            if (token != null) {
                val imm = service.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                // switchToNextInputMethod 参数说明：
                // - token: 当前 IME 窗口的 token
                // - onlyCurrentIme: true 表示仅在当前 IME 的子类型间切换（如简体/繁体）
                //                   false 表示切换到完全不同的 IME 输入法
                imm?.switchToNextInputMethod(token, false)
                Log.i("Success: Switch IME via swipe")
            } else {
                Log.w("Failed: Cannot get window token for switching IME")
            }
        } catch (e: Exception) {
            Log.e("Failed: Switch IME via swipe (API compatibility issue)")
            Log.i(e)
        }
    }

    private fun openIMESettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("Failed: Open IME settings via swipe")
            Log.i(e)
        }
    }

    private fun openClipboard(context: Context) {
        // 系统未提供标准剪贴板界面 Intent。
        // 尝试通过一些通用方式打开（部分设备支持 com.android.systemui 等）
        try {
            val intent = Intent("android.intent.action.CLIPBOARD").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                `package` = "com.tencent.wetype"
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            // 静默失败，CLIPBOARD 动作作为占位符
            Log.i("Swipe CLIPBOARD: no clipboard activity found")
        }
    }
}
