package io.github.mihirvr.prismo.places;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A spot on Earth in degrees: latitude (north positive) and longitude (east positive). Pure.
 *
 * <p>1.4.0: photos give it from EXIF GPS; videos from the location a phone writes into the file, in the ISO 6709
 * form ({@code +18.5204+073.8567+560.000/}, read by {@link #parseIso6709}).
 */
public record GeoPoint(double latitude, double longitude) {

    /** "+DD.DDDD+DDD.DDDD", optionally followed by altitude and "/": the form phones write (decimal degrees). */
    private static final Pattern ISO_6709 = Pattern.compile("^\\s*([+-]\\d{1,2}(?:\\.\\d+)?)([+-]\\d{1,3}(?:\\.\\d+)?)");

    public GeoPoint {
        if (!(Math.abs(latitude) <= 90) || !(Math.abs(longitude) <= 180)) {
            throw new IllegalArgumentException("not on Earth: " + latitude + ", " + longitude);
        }
    }

    /** The point in an ISO 6709 text, or empty if there is none (blank, another form, 0,0 = a phone without a fix). */
    public static Optional<GeoPoint> parseIso6709(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Matcher m = ISO_6709.matcher(text);
        if (!m.find()) {
            return Optional.empty();
        }
        double lat = Double.parseDouble(m.group(1));
        double lon = Double.parseDouble(m.group(2));
        if (Math.abs(lat) > 90 || Math.abs(lon) > 180 || (lat == 0 && lon == 0)) {
            return Optional.empty();
        }
        return Optional.of(new GeoPoint(lat, lon));
    }

    /** "18.520430, 73.856743" (as the Info panel shows it). */
    public String text() {
        return String.format(Locale.ROOT, "%.6f, %.6f", latitude, longitude);
    }

    /**
     * The point on OpenStreetMap, opened in the browser only when the user clicks "Open in map" (Prismo itself never
     * goes online). Zoom 16: streets.
     */
    public String mapLink() {
        return String.format(Locale.ROOT, "https://www.openstreetmap.org/?mlat=%.6f&mlon=%.6f#map=16/%.6f/%.6f",
                latitude, longitude, latitude, longitude);
    }

    /** Great-circle distance in kilometres (haversine, Earth radius 6371 km). */
    public double kmTo(GeoPoint other) {
        double dLat = Math.toRadians(other.latitude - latitude);
        double dLon = Math.toRadians(other.longitude - longitude);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2) + Math.cos(Math.toRadians(latitude))
                * Math.cos(Math.toRadians(other.latitude)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * 6371 * Math.asin(Math.min(1, Math.sqrt(a)));
    }
}
