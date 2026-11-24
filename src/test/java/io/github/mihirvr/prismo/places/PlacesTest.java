package io.github.mihirvr.prismo.places;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PlacesTest {

    private static Places small() throws IOException {
        String table = """
                #C\tIN\tIndia
                #C\tFJ\tFiji
                #A\tIN.16\tMaharashtra
                #A\tIN.07\tDelhi
                Pune\tIN\t16\t18.5196\t73.8553
                Lonavala\tIN\t16\t18.7546\t73.4062
                Delhi\tIN\t07\t28.6519\t77.2315
                Levuka\tFJ\t05\t-17.6833\t178.8333
                Vuna\tFJ\t03\t-16.8500\t-179.9700
                """;
        return Places.read(new ByteArrayInputStream(table.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void theNearestPlaceWithItsRegionAndCountry() throws IOException {
        assertEquals(Optional.of("Pune, Maharashtra, India"), small().describe(new GeoPoint(18.5204, 73.8567)));
    }

    @Test
    void aBitFurtherAwayIsNear() throws IOException {
        // About 15 km from Lonavala, 40 km from Pune.
        assertEquals(Optional.of("Near Lonavala, Maharashtra, India"), small().describe(new GeoPoint(18.65, 73.50)));
    }

    @Test
    void aRegionWithTheSameNameIsNotRepeated() throws IOException {
        assertEquals(Optional.of("Delhi, India"), small().describe(new GeoPoint(28.65, 77.23)));
    }

    @Test
    void farFromEveryPlaceHasNoName() throws IOException {
        assertEquals(Optional.empty(), small().describe(new GeoPoint(0.5, -30))); // mid-Atlantic
    }

    @Test
    void acrossTheDateLine() throws IOException {
        // 179.98 E and 179.97 W are about 5 km apart, not the whole world.
        assertEquals(Optional.of("Vuna, Fiji"), small().describe(new GeoPoint(-16.85, 179.98)));
    }

    @Test
    void theTableThatShipsWithPrismo() {
        Places places = Places.shared();
        assertTrue(places.size() > 100_000, "towns of 1,000 people and more: " + places.size());
        String pune = places.describe(new GeoPoint(18.5204, 73.8567)).orElseThrow();
        assertTrue(pune.endsWith("Maharashtra, India"), pune);
        String paris = places.describe(new GeoPoint(48.8584, 2.2945)).orElseThrow(); // the Eiffel Tower
        assertTrue(paris.endsWith("France"), paris);
    }

    @Test
    void readingIso6709() {
        assertEquals(Optional.of(new GeoPoint(18.5204, 73.8567)), GeoPoint.parseIso6709("+18.5204+073.8567+560.000/"));
        assertEquals(Optional.of(new GeoPoint(-33.8688, -151.2093)), GeoPoint.parseIso6709("-33.8688-151.2093/"));
        assertEquals(Optional.empty(), GeoPoint.parseIso6709("+00.0000+000.0000/"), "0, 0: no fix");
        assertEquals(Optional.empty(), GeoPoint.parseIso6709(""));
        assertEquals(Optional.empty(), GeoPoint.parseIso6709("somewhere"));
        assertEquals(Optional.empty(), GeoPoint.parseIso6709("+95.0000+010.0000/"), "not on Earth");
    }

    @Test
    void textMapLinkAndDistance() {
        GeoPoint pune = new GeoPoint(18.5204, 73.8567);
        assertEquals("18.520400, 73.856700", pune.text());
        assertEquals("https://www.openstreetmap.org/?mlat=18.520400&mlon=73.856700#map=16/18.520400/73.856700",
                pune.mapLink());
        double km = pune.kmTo(new GeoPoint(19.0760, 72.8777)); // Mumbai, about 120 km
        assertTrue(km > 115 && km < 125, "Pune to Mumbai: " + km);
        assertThrows(IllegalArgumentException.class, () -> new GeoPoint(91, 0));
    }
}
