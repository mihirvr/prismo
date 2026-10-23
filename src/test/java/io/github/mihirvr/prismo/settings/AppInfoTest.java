package io.github.mihirvr.prismo.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class AppInfoTest {

    @Test
    void theBuiltFileHasTheVersionAndTheWebsite() {
        AppInfo info = AppInfo.load();

        assertFalse(info.version().contains("${"), "the build filled in the version: " + info.version());
        assertFalse(info.version().isBlank());
        assertEquals(Optional.of(URI.create("https://prismo.mihirr.in")), info.website());
    }

    @Test
    void onlyAFullWebAddressCountsAsTheWebsite() {
        assertEquals(Optional.of(URI.create("https://prismo.example/download")),
                info("https://prismo.example/download").website());
        assertTrue(info("  http://example.com  ").website().isPresent(), "spaces around it are fine");
        assertEquals(Optional.empty(), info("").website());
        assertEquals(Optional.empty(), info("prismo.example").website(), "no http(s): not opened");
        assertEquals(Optional.empty(), info("javascript:alert(1)").website());
        assertEquals(Optional.empty(), info("file:///C:/Windows").website());
        assertEquals(Optional.empty(), info("https://").website(), "no host");
        assertEquals(Optional.empty(), info("ftp://prismo.example").website(), "a host, but not http(s)");
    }

    @Test
    void anUnfilledVersionReadsAsADevelopmentBuild() {
        Properties raw = new Properties();
        raw.setProperty("version", "${version}");
        assertEquals(AppInfo.DEVELOPMENT, AppInfo.from(raw).version());
        assertEquals(AppInfo.DEVELOPMENT, AppInfo.from(new Properties()).version());
    }

    private static AppInfo info(String website) {
        Properties properties = new Properties();
        properties.setProperty("version", "1.0.0");
        properties.setProperty("website", website);
        return AppInfo.from(properties);
    }
}
