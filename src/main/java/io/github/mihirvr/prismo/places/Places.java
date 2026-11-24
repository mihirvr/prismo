package io.github.mihirvr.prismo.places;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

/**
 * Where a photo was taken, in words, with no internet (1.4.0): the nearest town or city from a table that ships with
 * Prismo ({@code /places/places.tsv.gz}, made from GeoNames' list of places with more than 1,000 people, CC BY 4.0:
 * credited in Settings › About and the README).
 *
 * <p>The table: {@code #C code name} lines (countries), {@code #A code.admin1 name} lines (states, regions), then one
 * {@code name code admin1 lat lon} line per place. Loaded once, on first use, off the UI thread; then a lookup scans
 * the arrays (about 170,000 places: a millisecond or two).
 */
public final class Places {

    /** Farther than this from every known place (the sea, a wilderness): no name. */
    static final double MAX_KM = 60;
    /** Farther than this from the nearest place: "Near …". */
    static final double NEAR_KM = 8;

    private static final String RESOURCE = "/places/places.tsv.gz";
    private static volatile Places shared;

    private final float[] lat;
    private final float[] lon;
    private final String[] name;
    private final String[] region;  // "" when unknown
    private final String[] country;

    private Places(float[] lat, float[] lon, String[] name, String[] region, String[] country) {
        this.lat = lat;
        this.lon = lon;
        this.name = name;
        this.region = region;
        this.country = country;
    }

    /** The table that ships with Prismo, loaded on the first call (blocking: call it off the UI thread). */
    public static Places shared() {
        Places places = shared;
        if (places == null) {
            synchronized (Places.class) {
                places = shared;
                if (places == null) {
                    try (InputStream in = Places.class.getResourceAsStream(RESOURCE)) {
                        if (in == null) {
                            throw new IllegalStateException("missing " + RESOURCE);
                        }
                        places = read(new GZIPInputStream(in));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    shared = places;
                }
            }
        }
        return places;
    }

    /** Reads a table in the form above (tests pass a small one). */
    static Places read(InputStream table) throws IOException {
        Map<String, String> countries = new HashMap<>();
        Map<String, String> regions = new HashMap<>();
        List<String[]> rows = new ArrayList<>(180_000);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(table, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] cols = line.split("\t", -1);
                if (cols[0].equals("#C") && cols.length >= 3) {
                    countries.put(cols[1], cols[2]);
                } else if (cols[0].equals("#A") && cols.length >= 3) {
                    regions.put(cols[1], cols[2]);
                } else if (cols.length >= 5) {
                    rows.add(cols);
                }
            }
        }
        int n = rows.size();
        float[] lat = new float[n];
        float[] lon = new float[n];
        String[] name = new String[n];
        String[] region = new String[n];
        String[] country = new String[n];
        for (int i = 0; i < n; i++) {
            String[] r = rows.get(i);
            name[i] = r[0];
            country[i] = countries.getOrDefault(r[1], r[1]).intern();
            region[i] = regions.getOrDefault(r[1] + "." + r[2], "").intern();
            lat[i] = Float.parseFloat(r[3]);
            lon[i] = Float.parseFloat(r[4]);
        }
        return new Places(lat, lon, name, region, country);
    }

    /** How many places the table holds. */
    public int size() {
        return name.length;
    }

    /**
     * "Pune, Maharashtra, India"; "Near Lonavala, Maharashtra, India" when the nearest place is more than
     * {@link #NEAR_KM} away; empty when it is more than {@link #MAX_KM} away (or the table is empty).
     */
    public Optional<String> describe(GeoPoint at) {
        int best = nearest(at);
        if (best < 0) {
            return Optional.empty();
        }
        double km = at.kmTo(new GeoPoint(lat[best], lon[best]));
        if (km > MAX_KM) {
            return Optional.empty();
        }
        StringBuilder text = new StringBuilder(km > NEAR_KM ? "Near " : "").append(name[best]);
        if (!region[best].isEmpty() && !region[best].equalsIgnoreCase(name[best])) {
            text.append(", ").append(region[best]);
        }
        if (!country[best].isEmpty()) {
            text.append(", ").append(country[best]);
        }
        return Optional.of(text.toString());
    }

    /** The index of the closest place, or -1 if there are none. Flat-earth distance: fine for "which is nearest". */
    int nearest(GeoPoint at) {
        double cosLat = Math.cos(Math.toRadians(at.latitude()));
        int best = -1;
        double bestDistance = Double.MAX_VALUE;
        for (int i = 0; i < lat.length; i++) {
            double dLat = lat[i] - at.latitude();
            double dLon = lon[i] - at.longitude();
            if (dLon > 180) {
                dLon -= 360; // across the date line
            } else if (dLon < -180) {
                dLon += 360;
            }
            dLon *= cosLat;
            double d = dLat * dLat + dLon * dLon;
            if (d < bestDistance) {
                bestDistance = d;
                best = i;
            }
        }
        return best;
    }
}
