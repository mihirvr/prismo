package io.github.mihirvr.prismo.platform;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PowerStatusTest {

    @TempDir
    Path supplies;

    private void supply(String name, String type, String file, String value) throws IOException {
        Path folder = Files.createDirectories(supplies.resolve(name));
        Files.writeString(folder.resolve("type"), type + "\n");
        Files.writeString(folder.resolve(file), value + "\n");
    }

    @Test
    void aLaptopOnItsBatteryIsOnBattery() throws IOException {
        supply("BAT0", "Battery", "status", "Discharging");
        supply("AC", "Mains", "online", "0");
        assertTrue(PowerStatus.linuxOnBattery(supplies));
    }

    @Test
    void pluggedInIsNotOnBattery() throws IOException {
        supply("BAT0", "Battery", "status", "Charging");
        supply("AC", "Mains", "online", "1");
        assertFalse(PowerStatus.linuxOnBattery(supplies));
    }

    @Test
    void mainsOnlineWinsOverADischargingSecondBattery() throws IOException {
        supply("BAT1", "Battery", "status", "Discharging");
        supply("ACAD", "Mains", "online", "1");
        assertFalse(PowerStatus.linuxOnBattery(supplies));
    }

    @Test
    void aDesktopWithoutBatteriesIsNotOnBattery() throws IOException {
        assertFalse(PowerStatus.linuxOnBattery(supplies), "no supplies at all");
        supply("hidpp_battery_0", "Battery", "status", "Discharging"); // a wireless mouse's battery...
        supply("AC", "Mains", "online", "1"); // ...on a desktop on mains power
        assertFalse(PowerStatus.linuxOnBattery(supplies));
    }

    @Test
    void windowsReadsTheMainsLineAndBatterySaver() {
        assertTrue(PowerStatus.windowsOnBattery((byte) 0, (byte) 0), "AC line offline");
        assertFalse(PowerStatus.windowsOnBattery((byte) 1, (byte) 0), "AC line online");
        assertFalse(PowerStatus.windowsOnBattery((byte) 255, (byte) 0), "unknown counts as mains");
        assertTrue(PowerStatus.windowsOnBattery((byte) 1, (byte) 1), "battery saver on, even plugged in");
    }
}
