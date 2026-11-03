package io.github.mihirvr.prismo.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mihirvr.prismo.thumbs.MediaRef;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NaturalOrderTest {

    private static List<String> sorted(String... names) {
        List<String> list = new ArrayList<>(List.of(names));
        list.sort(NaturalOrder.INSTANCE);
        return list;
    }

    @Test
    void numbersCompareByTheirValue() {
        assertEquals(List.of("1.jpg", "2.jpg", "10.jpg", "20.jpg", "100.jpg"),
                sorted("10.jpg", "100.jpg", "2.jpg", "20.jpg", "1.jpg"));
        assertEquals(List.of("IMG_2.jpg", "IMG_9.jpg", "IMG_10.jpg"), sorted("IMG_10.jpg", "IMG_9.jpg", "IMG_2.jpg"));
        assertEquals(List.of("Episode 1 part 2", "Episode 1 part 10", "Episode 2"),
                sorted("Episode 2", "Episode 1 part 10", "Episode 1 part 2"));
        assertEquals(List.of("a99999999999999999999b", "a100000000000000000000b"),
                sorted("a100000000000000000000b", "a99999999999999999999b"), "longer than a long");
    }

    @Test
    void digitsThenLettersThenSymbolsSoEachHeaderStaysInOnePiece() {
        assertEquals(List.of("2024 Trip", "Beach", "beach2", "Goa", "_Misc", "~old"),
                sorted("_Misc", "Goa", "~old", "beach2", "2024 Trip", "Beach"));
        assertEquals(List.of("Ecole", "École", "Zebra"), sorted("Zebra", "École", "Ecole"), "accents count as plain");
    }

    @Test
    void lookAlikeNamesStillGetOneFixedOrder() {
        assertEquals(List.of("1.jpg", "01.jpg", "001.jpg"), sorted("001.jpg", "01.jpg", "1.jpg"));
        assertEquals(List.of("IMG1", "img1"), sorted("img1", "IMG1"));
        assertTrue(NaturalOrder.INSTANCE.compare("same", "same") == 0);
    }

    @Test
    void headersAreDigitsLettersAndHash() {
        assertEquals("0-9", NaturalOrder.headerOf("10.jpg"));
        assertEquals("B", NaturalOrder.headerOf("beach.jpg"));
        assertEquals("E", NaturalOrder.headerOf("École.jpg"));
        assertEquals("#", NaturalOrder.headerOf("_Misc.jpg"));
        assertEquals("#", NaturalOrder.headerOf(""));
    }

    @Test
    void theNameSortPutsItemsInNaturalOrderEitherWay() {
        List<TimelineItem> items = new ArrayList<>();
        long id = 1;
        for (String name : new String[] {"10.jpg", "b.jpg", "2.jpg", "_x.jpg", "1.jpg"}) {
            items.add(new TimelineItem(new MediaRef(id++, Path.of(name), "jpg", 0), 0, 0, 0, false, false, false));
        }
        items.sort(TimelineQuery.naturalNames(false));
        assertEquals(List.of("1.jpg", "2.jpg", "10.jpg", "b.jpg", "_x.jpg"), names(items));
        items.sort(TimelineQuery.naturalNames(true));
        assertEquals(List.of("_x.jpg", "b.jpg", "10.jpg", "2.jpg", "1.jpg"), names(items));
    }

    private static List<String> names(List<TimelineItem> items) {
        return items.stream().map(item -> item.ref().path().toString()).toList();
    }
}
