package io.github.mihirvr.prismo.settings;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What Settings › About shows: the version (from the build) and the web page, both read from
 * {@code app.properties} inside the app. The web page is set there by hand ({@code website=}); until then the
 * link says "coming soon".
 *
 * @param website only a full {@code http} / {@code https} address counts; anything else is treated as not set
 */
public record AppInfo(String version, Optional<URI> website) {

    private static final Logger log = LoggerFactory.getLogger(AppInfo.class);
    /** The version when the file wasn't filled in by the build (e.g. run straight from an IDE). */
    static final String DEVELOPMENT = "development build";

    /** Reads {@code /app.properties}; a missing or broken file gives a development version and no web page. */
    public static AppInfo load() {
        Properties properties = new Properties();
        try (InputStream in = AppInfo.class.getResourceAsStream("/app.properties")) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException e) {
            log.warn("Could not read app.properties: {}", e.toString());
        }
        return from(properties);
    }

    static AppInfo from(Properties properties) {
        String version = properties.getProperty("version", "").strip();
        if (version.isEmpty() || version.contains("${")) {
            version = DEVELOPMENT;
        }
        return new AppInfo(version, website(properties.getProperty("website", "")));
    }

    private static Optional<URI> website(String text) {
        String address = text.strip();
        if (address.isEmpty()) {
            return Optional.empty();
        }
        try {
            URI uri = new URI(address);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if ((scheme.equals("http") || scheme.equals("https")) && uri.getHost() != null) {
                return Optional.of(uri);
            }
        } catch (URISyntaxException e) {
            // falls through: not an address
        }
        log.warn("Ignoring website in app.properties (not a full http/https address): {}", address);
        return Optional.empty();
    }
}
