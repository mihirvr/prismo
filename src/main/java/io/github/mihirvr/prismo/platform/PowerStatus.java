package io.github.mihirvr.prismo.platform;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Whether the computer runs on battery (or with battery saver on), so the colour background can stay still and save
 * power (2026-11-10). A desktop without a battery, or anything that can't be read, counts as "on mains power".
 *
 * <ul>
 *   <li>Windows: {@code GetSystemPowerStatus} (kernel32) through the FFM API, as {@link WindowsTitleBar} calls
 *       Windows: {@code ACLineStatus} 0 = on battery, {@code SystemStatusFlag} 1 = battery saver on.</li>
 *   <li>Linux: {@code /sys/class/power_supply}: on battery when a battery says "Discharging" and no mains supply
 *       says it is online.</li>
 * </ul>
 * Cheap (no process started), but still called off the JavaFX thread, every few seconds at most.
 */
public final class PowerStatus {

    private static final Logger log = LoggerFactory.getLogger(PowerStatus.class);
    private static final Path LINUX_SUPPLIES = Path.of("/sys/class/power_supply");
    private static final Windows WINDOWS = OperatingSystem.current() == OperatingSystem.WINDOWS ? Windows.load() : null;

    private PowerStatus() {
    }

    /** True on battery or with battery saver on; false on mains power, without a battery, or when unknown. */
    public static boolean onBattery() {
        try {
            if (WINDOWS != null) {
                return WINDOWS.onBattery();
            }
            if (OperatingSystem.current() != OperatingSystem.WINDOWS && Files.isDirectory(LINUX_SUPPLIES)) {
                return linuxOnBattery(LINUX_SUPPLIES);
            }
        } catch (Throwable e) {
            log.debug("Could not read the power status", e);
        }
        return false;
    }

    /** Linux's power supply folder: on battery when one battery discharges and no mains supply is online. */
    static boolean linuxOnBattery(Path supplies) throws IOException {
        boolean discharging = false;
        boolean mainsOnline = false;
        try (DirectoryStream<Path> all = Files.newDirectoryStream(supplies)) {
            for (Path supply : all) {
                String type = read(supply.resolve("type"));
                if ("Battery".equalsIgnoreCase(type)) {
                    discharging |= "Discharging".equalsIgnoreCase(read(supply.resolve("status")));
                } else if ("Mains".equalsIgnoreCase(type)) {
                    mainsOnline |= "1".equals(read(supply.resolve("online")));
                }
            }
        }
        return discharging && !mainsOnline;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file).strip();
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Windows' answer as the two bytes that matter: {@code SYSTEM_POWER_STATUS} is ACLineStatus, BatteryFlag,
     * BatteryLifePercent, SystemStatusFlag (one byte each), then two DWORDs.
     */
    static boolean windowsOnBattery(byte acLineStatus, byte systemStatusFlag) {
        return acLineStatus == 0 || systemStatusFlag == 1;
    }

    /** The native call. A separate class so nothing is looked up on Linux. */
    @SuppressWarnings("restricted") // FFM downcalls are "restricted": allowed by --enable-native-access=ALL-UNNAMED
    private record Windows(MethodHandle getSystemPowerStatus) {

        static Windows load() {
            try {
                SymbolLookup kernel32 = SymbolLookup.libraryLookup("kernel32", Arena.global());
                // BOOL GetSystemPowerStatus(LPSYSTEM_POWER_STATUS status)
                return new Windows(Linker.nativeLinker().downcallHandle(kernel32.findOrThrow("GetSystemPowerStatus"),
                        FunctionDescriptor.of(JAVA_INT, ADDRESS)));
            } catch (Throwable e) {
                log.debug("GetSystemPowerStatus is not available", e);
                return null;
            }
        }

        boolean onBattery() throws Throwable {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment status = arena.allocate(12);
                if ((int) getSystemPowerStatus.invokeExact(status) == 0) {
                    return false;
                }
                return windowsOnBattery(status.get(JAVA_BYTE, 0), status.get(JAVA_BYTE, 3));
            }
        }
    }
}
