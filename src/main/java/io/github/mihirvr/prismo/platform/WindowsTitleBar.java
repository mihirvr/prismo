package io.github.mihirvr.prismo.platform;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Windows only: asks Windows to draw a window's title bar dark or light.
 *
 * <p>Windows draws the title bar of a normal window, not the app. Apps opt in to a dark one per window with
 * {@code DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, TRUE)} (Windows 10 20H1 and later; attribute 19
 * on older Windows 10). JavaFX 25 never makes that call for a normal decorated window (its {@code glass.dll} doesn't
 * use the function), so Prismo does, through the FFM API (JEP 454: calling a C function from Java without JNI).
 *
 * <p>JavaFX doesn't hand out the native window handle (HWND) in its public API, so the window is found by its title
 * among this process's own top-level windows ({@code FindWindowExW} + {@code GetWindowThreadProcessId}). Anything
 * that fails is logged and ignored: the title bar just stays as Windows drew it.
 */
public final class WindowsTitleBar {

    private static final Logger log = LoggerFactory.getLogger(WindowsTitleBar.class);

    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;
    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1 = 19;
    private static final int SWP_NOSIZE = 0x0001;
    private static final int SWP_NOMOVE = 0x0002;
    private static final int SWP_NOZORDER = 0x0004;
    private static final int SWP_NOACTIVATE = 0x0010;
    private static final int SWP_FRAMECHANGED = 0x0020;

    /** The Windows functions, looked up once; null when not on Windows or if the lookup failed. */
    private static final Functions FUNCTIONS = OperatingSystem.current() == OperatingSystem.WINDOWS ? Functions.load() : null;

    private WindowsTitleBar() {
    }

    /**
     * Makes the title bar of this process's window titled {@code title} dark or light. Call once the window is
     * showing (before that it has no native window). Does nothing on Linux. Returns true if a window was changed.
     */
    public static boolean setDark(String title, boolean dark) {
        if (FUNCTIONS == null || title == null || title.isEmpty()) {
            return false;
        }
        try {
            return FUNCTIONS.setDark(title, dark);
        } catch (Throwable e) {
            log.debug("Could not set the title bar of \"{}\" to {}", title, dark ? "dark" : "light", e);
            return false;
        }
    }

    /** The native calls. A separate class so nothing is looked up on Linux. */
    @SuppressWarnings("restricted") // FFM downcalls are "restricted": allowed by --enable-native-access=ALL-UNNAMED
    private record Functions(MethodHandle findWindowEx, MethodHandle windowProcessId, MethodHandle setAttribute,
            MethodHandle setWindowPos) {

        static Functions load() {
            try {
                Linker linker = Linker.nativeLinker();
                SymbolLookup user32 = SymbolLookup.libraryLookup("user32", Arena.global());
                SymbolLookup dwmapi = SymbolLookup.libraryLookup("dwmapi", Arena.global());
                return new Functions(
                        // HWND FindWindowExW(HWND parent, HWND after, LPCWSTR className, LPCWSTR windowName)
                        linker.downcallHandle(user32.findOrThrow("FindWindowExW"),
                                FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS)),
                        // DWORD GetWindowThreadProcessId(HWND window, LPDWORD processId)
                        linker.downcallHandle(user32.findOrThrow("GetWindowThreadProcessId"),
                                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS)),
                        // HRESULT DwmSetWindowAttribute(HWND window, DWORD attribute, LPCVOID value, DWORD size)
                        linker.downcallHandle(dwmapi.findOrThrow("DwmSetWindowAttribute"),
                                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT)),
                        // BOOL SetWindowPos(HWND window, HWND after, int x, int y, int cx, int cy, UINT flags)
                        linker.downcallHandle(user32.findOrThrow("SetWindowPos"),
                                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT,
                                        JAVA_INT, JAVA_INT)));
            } catch (RuntimeException | Error e) {
                log.warn("Dark title bars are not available: {}", e.toString());
                return null;
            }
        }

        boolean setDark(String title, boolean dark) throws Throwable {
            long ourProcess = ProcessHandle.current().pid();
            boolean changed = false;
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment name = arena.allocateFrom(title, StandardCharsets.UTF_16LE); // with its 2-byte end mark
                MemorySegment processId = arena.allocate(JAVA_INT);
                MemorySegment value = arena.allocateFrom(JAVA_INT, dark ? 1 : 0); // a Win32 BOOL
                MemorySegment window = MemorySegment.NULL;
                // Every top-level window with this title, any process: keep only ours.
                while (true) {
                    window = (MemorySegment) findWindowEx.invokeExact(MemorySegment.NULL, window, MemorySegment.NULL,
                            name);
                    if (window.address() == 0) {
                        break;
                    }
                    int thread = (int) windowProcessId.invokeExact(window, processId);
                    if (thread == 0 || Integer.toUnsignedLong(processId.get(JAVA_INT, 0)) != ourProcess) {
                        continue;
                    }
                    int result = (int) setAttribute.invokeExact(window, DWMWA_USE_IMMERSIVE_DARK_MODE, value, 4);
                    if (result != 0) {
                        result = (int) setAttribute.invokeExact(window, DWMWA_USE_IMMERSIVE_DARK_MODE_BEFORE_20H1,
                                value, 4);
                    }
                    if (result == 0) {
                        // Redraws the frame now; otherwise the new colour shows only after the next activation.
                        int ignored = (int) setWindowPos.invokeExact(window, MemorySegment.NULL, 0, 0, 0, 0,
                                SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED);
                        changed = true;
                    } else {
                        log.debug("DwmSetWindowAttribute failed for \"{}\": 0x{}", title, Integer.toHexString(result));
                    }
                }
            }
            return changed;
        }
    }
}
