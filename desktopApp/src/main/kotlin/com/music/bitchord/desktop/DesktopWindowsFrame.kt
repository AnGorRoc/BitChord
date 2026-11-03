package com.music.bitchord.desktop

import com.sun.jna.Callback
import com.sun.jna.CallbackReference
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinDef.HWND
import com.sun.jna.platform.win32.WinDef.LPARAM
import com.sun.jna.platform.win32.WinDef.LRESULT
import com.sun.jna.platform.win32.WinDef.WPARAM
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.IntByReference
import java.awt.Window

/**
 * Windows' native frame integrated via JNA and DWM.
 *
 * The Win32 window retains standard overlapped styles so DWM natively owns its shadow,
 * Windows 11 rounded corners, resizing borders, and automatic repositioning/snapping (Aero Snap,
 * Snap Assist, Snap Layouts). WM_NCCALCSIZE removes only the system caption area so the Compose
 * UI and sidebar can draw to the top of the window.
 */
internal object DesktopWindowsFrame {

    @Volatile
    private var currentHwnd: HWND? = null

    @Volatile
    private var oldProc: Pointer? = null

    @Volatile
    private var currentProc: WindowProcCallback? = null

    @Volatile
    private var installed = false

    @Volatile
    var snapLayoutsAllowed: Boolean = true

    @Volatile
    var isNowPlaying: Boolean = false

    private var fullscreenActive = false
    private var fullscreenStyle = 0
    private val fullscreenRect = WinDef.RECT()

    val available: Boolean get() = DesktopPlatform.isWindows

    fun install(window: Window?): Boolean {
        if (!DesktopPlatform.isWindows || window == null) return false
        val pointer = runCatching { Native.getWindowPointer(window) }.getOrNull() ?: return false
        if (Pointer.nativeValue(pointer) == 0L) return false
        val hwnd = HWND(pointer)
        return installHwnd(hwnd)
    }

    suspend fun install(title: String): Boolean {
        if (!DesktopPlatform.isWindows) return false
        val hwnd = currentHwnd ?: return false
        return installHwnd(hwnd)
    }

    private fun installHwnd(hwnd: HWND): Boolean {
        if (installed && currentHwnd == hwnd && oldProc != null) return true

        return runCatching {
            currentHwnd = hwnd

            val currentStyle = User32.INSTANCE.GetWindowLong(hwnd, GWL_STYLE)
            val newStyle = (currentStyle and WS_POPUP.inv()) or WS_OVERLAPPEDWINDOW
            User32.INSTANCE.SetWindowLong(hwnd, GWL_STYLE, newStyle)

            val dwmapi = Dwmapi.INSTANCE
            if (dwmapi != null) {
                // Enable non-client rendering policy
                val policy = IntByReference(DWMNCRP_ENABLED)
                dwmapi.DwmSetWindowAttribute(hwnd, DWMWA_NCRENDERING_POLICY, policy.pointer, 4)

                // Windows 11 rounded corners preference
                val round = IntByReference(DWMWCP_ROUND)
                dwmapi.DwmSetWindowAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, round.pointer, 4)

                // Immersive dark mode
                val dark = IntByReference(1)
                dwmapi.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, dark.pointer, 4)

                // 1px frame margins so DWM draws the native drop shadow and border
                val margins = MARGINS(1, 1, 1, 1)
                dwmapi.DwmExtendFrameIntoClientArea(hwnd, margins)
            }

            // Subclass the WindowProc
            val proc = WindowProcCallback { hWnd, uMsg, wParam, lParam ->
                when (uMsg) {
                    WM_NCCALCSIZE -> {
                        if (wParam.toInt() != 0) {
                            val extra = User32Extra.INSTANCE
                            val zoomed = extra?.IsZoomed(hWnd) ?: false
                            if (zoomed) {
                                val hMonitor = extra?.MonitorFromWindow(hWnd, 2 /* MONITOR_DEFAULTTONEAREST */)
                                if (hMonitor != null) {
                                    val info = WinUser.MONITORINFO()
                                    if (extra.GetMonitorInfoW(hMonitor, info)) {
                                        val p = Pointer(lParam.toLong())
                                        p.setInt(0, info.rcWork.left)
                                        p.setInt(4, info.rcWork.top)
                                        p.setInt(8, info.rcWork.right)
                                        p.setInt(12, info.rcWork.bottom)
                                    }
                                }
                            }
                            return@WindowProcCallback LRESULT(0)
                        }
                    }
                    WM_NCHITTEST -> {
                        val extra = User32Extra.INSTANCE
                        val zoomed = extra?.IsZoomed(hWnd) ?: false
                        if (!fullscreenActive) {
                            val x = (lParam.toLong() and 0xFFFF).toShort().toInt()
                            val y = ((lParam.toLong() shr 16) and 0xFFFF).toShort().toInt()
                            val rect = WinDef.RECT()
                            User32.INSTANCE.GetWindowRect(hWnd, rect)

                            val dpi = runCatching { extra?.GetDpiForWindow(hWnd) }.getOrNull()?.takeIf { it > 0 } ?: 96
                            val scale = dpi.toFloat() / 96f

                            // Resize borders (only active when not maximized)
                            if (!zoomed) {
                                val borderX = ((extra?.GetSystemMetricsForDpi(32 /* SM_CXFRAME */, dpi) ?: 8) +
                                    (extra?.GetSystemMetricsForDpi(92 /* SM_CXPADDEDBORDER */, dpi) ?: 0)).coerceAtLeast(6)
                                val borderY = ((extra?.GetSystemMetricsForDpi(33 /* SM_CYFRAME */, dpi) ?: 8) +
                                    (extra?.GetSystemMetricsForDpi(92 /* SM_CXPADDEDBORDER */, dpi) ?: 0)).coerceAtLeast(6)

                                val left = x < rect.left + borderX
                                val right = x >= rect.right - borderX
                                val top = y < rect.top + borderY
                                val bottom = y >= rect.bottom - borderY

                                if (top && left) return@WindowProcCallback LRESULT(HTTOPLEFT.toLong())
                                if (top && right) return@WindowProcCallback LRESULT(HTTOPRIGHT.toLong())
                                if (bottom && left) return@WindowProcCallback LRESULT(HTBOTTOMLEFT.toLong())
                                if (bottom && right) return@WindowProcCallback LRESULT(HTBOTTOMRIGHT.toLong())
                                if (left) return@WindowProcCallback LRESULT(HTLEFT.toLong())
                                if (right) return@WindowProcCallback LRESULT(HTRIGHT.toLong())
                                if (top) return@WindowProcCallback LRESULT(HTTOP.toLong())
                                if (bottom) return@WindowProcCallback LRESULT(HTBOTTOM.toLong())
                            }

                            // Snap Layouts on the Maximize button (Windows 11)
                            if (snapLayoutsAllowed && !isNowPlaying) {
                                val captionHeight = (32f * scale).toInt()
                                if (y in rect.top until (rect.top + captionHeight)) {
                                    val maxLeft = rect.right - ((46f * 2) * scale).toInt()
                                    val maxRight = rect.right - (46f * scale).toInt()
                                    if (x in maxLeft until maxRight) {
                                        return@WindowProcCallback LRESULT(HTMAXBUTTON.toLong())
                                    }
                                }
                            }
                        }
                    }
                    WM_BITCHORD_DRAG -> {
                        // Runs on the native window thread, the only one whose mouse capture
                        // (held by AWT since the press) ReleaseCapture can actually let go of.
                        val primary = if (User32.INSTANCE.GetSystemMetrics(23 /* SM_SWAPBUTTON */) != 0) 0x02 else 0x01
                        if ((User32.INSTANCE.GetAsyncKeyState(primary).toInt() and 0x8000) == 0) {
                            return@WindowProcCallback LRESULT(0)
                        }
                        User32Extra.INSTANCE?.ReleaseCapture()
                        User32.INSTANCE.DefWindowProc(hWnd, WM_SYSCOMMAND, WPARAM(SC_DRAGMOVE.toLong()), LPARAM(0))
                        // The move loop swallowed the release; hand it back so Compose does not
                        // think the button is still down.
                        val point = WinDef.POINT()
                        if (User32.INSTANCE.GetCursorPos(point)) {
                            User32Extra.INSTANCE?.ScreenToClient(hWnd, point)
                            val packed = ((point.y and 0xFFFF) shl 16) or (point.x and 0xFFFF)
                            User32.INSTANCE.PostMessage(hWnd, WM_LBUTTONUP, WPARAM(0), LPARAM(packed.toLong()))
                        }
                        return@WindowProcCallback LRESULT(0)
                    }
                    WM_NCDESTROY -> {
                        currentHwnd = null
                    }
                }

                val prev = oldProc
                if (prev != null) {
                    User32Extra.INSTANCE?.CallWindowProcW(prev, hWnd, uMsg, wParam, lParam)
                        ?: User32.INSTANCE.DefWindowProc(hWnd, uMsg, wParam, lParam)
                } else {
                    User32.INSTANCE.DefWindowProc(hWnd, uMsg, wParam, lParam)
                }
            }

            currentProc = proc
            val procPointer = CallbackReference.getFunctionPointer(proc)
            oldProc = User32.INSTANCE.SetWindowLongPtr(hwnd, GWLP_WNDPROC, procPointer)

            User32.INSTANCE.SetWindowPos(
                hwnd, null, 0, 0, 0, 0,
                SWP_FRAMECHANGED or SWP_NOMOVE or SWP_NOSIZE or SWP_NOZORDER or SWP_NOACTIVATE,
            )

            DesktopTrackLog.log("DesktopWindowsFrame: successfully installed native frame on hwnd $hwnd")
            installed = true
            true
        }.getOrElse {
            DesktopTrackLog.log("DesktopWindowsFrame install error: ${it.message}")
            false
        }
    }

    fun minimize(): Boolean {
        val hwnd = currentHwnd ?: return false
        User32.INSTANCE.ShowWindow(hwnd, WinUser.SW_MINIMIZE)
        return true
    }

    fun toggleMaximize(): Boolean {
        val hwnd = currentHwnd ?: return false
        val zoomed = User32Extra.INSTANCE?.IsZoomed(hwnd) ?: false
        User32.INSTANCE.ShowWindow(hwnd, if (zoomed) WinUser.SW_RESTORE else WinUser.SW_MAXIMIZE)
        return true
    }

    fun startDrag(): Boolean {
        val hwnd = currentHwnd ?: return false
        User32.INSTANCE.PostMessage(hwnd, WM_BITCHORD_DRAG, WPARAM(0), LPARAM(0))
        return true
    }

    fun setFullscreen(on: Boolean): Boolean {
        val hwnd = currentHwnd ?: return false
        val user32 = User32.INSTANCE
        val extra = User32Extra.INSTANCE

        if (on) {
            if (fullscreenActive) return true
            fullscreenStyle = user32.GetWindowLong(hwnd, GWL_STYLE)
            user32.GetWindowRect(hwnd, fullscreenRect)

            val hMonitor = extra?.MonitorFromWindow(hwnd, 2 /* MONITOR_DEFAULTTONEAREST */) ?: return false
            val monitorInfo = WinUser.MONITORINFO()
            if (extra.GetMonitorInfoW(hMonitor, monitorInfo) == false) return false

            fullscreenActive = true

            val newStyle = fullscreenStyle and (WS_OVERLAPPEDWINDOW.inv())
            user32.SetWindowLong(hwnd, GWL_STYLE, newStyle)

            val rc = monitorInfo.rcMonitor
            val width = rc.right - rc.left
            val height = rc.bottom - rc.top

            user32.SetWindowPos(
                hwnd,
                HWND(Pointer.createConstant(-1L)), // HWND_TOPMOST
                rc.left, rc.top, width, height,
                SWP_FRAMECHANGED or SWP_NOACTIVATE,
            )
            return true
        }

        if (!fullscreenActive) return true
        fullscreenActive = false

        user32.SetWindowLong(hwnd, GWL_STYLE, fullscreenStyle)
        val width = fullscreenRect.right - fullscreenRect.left
        val height = fullscreenRect.bottom - fullscreenRect.top

        user32.SetWindowPos(
            hwnd,
            HWND(Pointer.createConstant(-2L)), // HWND_NOTOPMOST
            fullscreenRect.left, fullscreenRect.top, width, height,
            SWP_FRAMECHANGED or SWP_NOACTIVATE,
        )
        return true
    }

    fun setBackdrop(kind: Int): Boolean {
        val hwnd = currentHwnd ?: return false
        val dwmapi = Dwmapi.INSTANCE ?: return false

        val dark = IntByReference(1)
        dwmapi.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, dark.pointer, 4)

        val material = kind == 2 || kind == 3 // Mica or Acrylic
        val type = IntByReference(if (material) kind else 1 /* DWMSBT_NONE */)
        val hr = dwmapi.DwmSetWindowAttribute(hwnd, DWMWA_SYSTEMBACKDROP_TYPE, type.pointer, 4)
        if (hr != 0) return false

        val margins = if (material) MARGINS(-1, -1, -1, -1) else MARGINS(1, 1, 1, 1)
        dwmapi.DwmExtendFrameIntoClientArea(hwnd, margins)
        return true
    }

    fun interface WindowProcCallback : Callback {
        fun callback(hwnd: HWND, uMsg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT
    }

    private interface Dwmapi : Library {
        fun DwmSetWindowAttribute(hwnd: HWND, dwAttribute: Int, pvAttribute: Pointer, cbAttribute: Int): Int
        fun DwmExtendFrameIntoClientArea(hwnd: HWND, pMarInset: MARGINS): Int

        companion object {
            val INSTANCE: Dwmapi? = runCatching {
                Native.load("dwmapi", Dwmapi::class.java)
            }.getOrNull()
        }
    }

    @Structure.FieldOrder("cxLeftWidth", "cxRightWidth", "cyTopHeight", "cyBottomHeight")
    open class MARGINS : Structure {
        @JvmField var cxLeftWidth: Int = 0
        @JvmField var cxRightWidth: Int = 0
        @JvmField var cyTopHeight: Int = 0
        @JvmField var cyBottomHeight: Int = 0

        constructor() : super()
        constructor(left: Int, right: Int, top: Int, bottom: Int) : super() {
            this.cxLeftWidth = left
            this.cxRightWidth = right
            this.cyTopHeight = top
            this.cyBottomHeight = bottom
        }
    }

    private interface User32Extra : Library {
        fun CallWindowProcW(lpPrevWndFunc: Pointer, hWnd: HWND, uMsg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT
        fun DefWindowProcW(hWnd: HWND, uMsg: Int, wParam: WPARAM, lParam: LPARAM): LRESULT
        fun ReleaseCapture(): Boolean
        fun IsZoomed(hwnd: HWND): Boolean
        fun GetDpiForWindow(hwnd: HWND): Int
        fun GetSystemMetricsForDpi(nIndex: Int, dpi: Int): Int
        fun MonitorFromWindow(hwnd: HWND, dwFlags: Int): WinUser.HMONITOR?
        fun GetMonitorInfoW(hMonitor: WinUser.HMONITOR, lpmi: WinUser.MONITORINFO): Boolean
        fun ScreenToClient(hwnd: HWND, lpPoint: WinDef.POINT): Boolean

        companion object {
            val INSTANCE: User32Extra? = runCatching {
                Native.load("user32", User32Extra::class.java)
            }.getOrNull()
        }
    }

    private const val GWL_STYLE = -16
    private const val GWLP_WNDPROC = -4

    private const val WS_POPUP = 0x80000000.toInt()
    private const val WS_OVERLAPPED = 0x00000000
    private const val WS_CAPTION = 0x00C00000
    private const val WS_SYSMENU = 0x00080000
    private const val WS_THICKFRAME = 0x00040000
    private const val WS_MINIMIZEBOX = 0x00020000
    private const val WS_MAXIMIZEBOX = 0x00010000
    private const val WS_OVERLAPPEDWINDOW = WS_OVERLAPPED or WS_CAPTION or WS_SYSMENU or WS_THICKFRAME or WS_MINIMIZEBOX or WS_MAXIMIZEBOX

    private const val WM_NCCALCSIZE = 0x0083
    private const val WM_NCHITTEST = 0x0084
    private const val WM_NCDESTROY = 0x0082
    private const val WM_SYSCOMMAND = 0x0112
    private const val WM_LBUTTONUP = 0x0202
    private const val WM_BITCHORD_DRAG = 0x8000 + 0x42

    private const val SC_MOVE = 0xF010
    private const val SC_DRAGMOVE = SC_MOVE or 0x0002 // HTCAPTION

    private const val HTMAXBUTTON = 9
    private const val HTLEFT = 10
    private const val HTRIGHT = 11
    private const val HTTOP = 12
    private const val HTTOPLEFT = 13
    private const val HTTOPRIGHT = 14
    private const val HTBOTTOM = 15
    private const val HTBOTTOMLEFT = 16
    private const val HTBOTTOMRIGHT = 17

    private const val DWMWA_NCRENDERING_POLICY = 2
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
    private const val DWMWA_SYSTEMBACKDROP_TYPE = 38

    private const val DWMNCRP_ENABLED = 2
    private const val DWMWCP_ROUND = 2

    private const val SWP_NOSIZE = 0x0001
    private const val SWP_NOMOVE = 0x0002
    private const val SWP_NOZORDER = 0x0004
    private const val SWP_NOACTIVATE = 0x0010
    private const val SWP_FRAMECHANGED = 0x0020
}
