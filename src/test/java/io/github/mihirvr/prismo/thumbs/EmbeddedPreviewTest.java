package io.github.mihirvr.prismo.thumbs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.mihirvr.prismo.testing.TestMedia;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The EXIF preview as a thumbnail (1.0.1): used only when it is big enough and has the photo's shape. */
class EmbeddedPreviewTest {

    @TempDir
    Path tmp;

    private final ThumbnailGenerator generator = new ThumbnailGenerator(320);

    private static BufferedImage filled(int width, int height, Color colour) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(colour);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return image;
    }

    private BufferedImage thumbnailOf(Path source) throws Exception {
        Path target = tmp.resolve("thumb.jpg");
        generator.generate(source, target);
        return ImageIO.read(target.toFile());
    }

    @Test
    void aPreviewAsBigAsTheThumbnailAndOfThePhotosShapeIsUsed() throws Exception {
        Path photo = TestMedia.jpegWithPreview(tmp.resolve("camera.jpg"), filled(640, 480, Color.RED),
                filled(320, 240, Color.BLUE), 1);

        BufferedImage thumbnail = thumbnailOf(photo);

        assertEquals(320, thumbnail.getWidth());
        assertEquals(240, thumbnail.getHeight());
        assertTrue(isBlue(thumbnail.getRGB(160, 120)), "made from the preview, not the photo");
    }

    @Test
    void aSmallerPreviewIsNotUsed() throws Exception {
        Path photo = TestMedia.jpegWithPreview(tmp.resolve("old-camera.jpg"), filled(640, 480, Color.RED),
                filled(160, 120, Color.BLUE), 1);

        assertTrue(isRed(thumbnailOf(photo).getRGB(160, 120)), "a 160 px preview would blur: the photo is decoded");
    }

    @Test
    void aPreviewOfAnotherShapeIsNotUsed() throws Exception {
        // A 16:9 photo with a 4:3 preview: black bars, or a preview an editor left behind after cropping.
        Path photo = TestMedia.jpegWithPreview(tmp.resolve("wide.jpg"), filled(640, 360, Color.RED),
                filled(320, 240, Color.BLUE), 1);

        BufferedImage thumbnail = thumbnailOf(photo);

        assertEquals(180, thumbnail.getHeight());
        assertTrue(isRed(thumbnail.getRGB(160, 90)));
    }

    @Test
    void thePreviewIsTurnedByThePhotosOrientation() throws Exception {
        BufferedImage preview = filled(320, 240, Color.BLUE);
        Graphics2D g = preview.createGraphics();
        g.setColor(Color.GREEN);
        g.fillRect(0, 0, 80, 60); // the stored top-left corner
        g.dispose();
        Path photo = TestMedia.jpegWithPreview(tmp.resolve("portrait.jpg"), filled(640, 480, Color.RED), preview, 6);

        BufferedImage thumbnail = thumbnailOf(photo);

        assertEquals(240, thumbnail.getWidth(), "6 = turn a quarter clockwise: portrait");
        assertEquals(320, thumbnail.getHeight());
        assertTrue(isGreen(thumbnail.getRGB(230, 10)), "the stored top-left ends up top-right");
        assertTrue(isBlue(thumbnail.getRGB(10, 10)));
    }

    @Test
    void onlyTheStartOfTheFileIsNeeded() throws Exception {
        Path photo = TestMedia.jpegWithPreview(tmp.resolve("cut.jpg"), filled(640, 480, Color.RED),
                filled(320, 240, Color.BLUE), 1);
        byte[] whole = Files.readAllBytes(photo);
        int afterExif = 4 + ((whole[4] & 0xFF) << 8 | (whole[5] & 0xFF)); // the preview inside has a scan of its own
        int imageData = indexOf(whole, new byte[] {(byte) 0xFF, (byte) 0xDA}, afterExif); // the photo's pixels
        Files.write(photo, Arrays.copyOf(whole, imageData + 4)); // the pixels are gone

        assertTrue(isBlue(thumbnailOf(photo).getRGB(160, 120)));
    }

    @Test
    void theQuickPassMakesOnlyWhatReadsLittleOfTheFile() throws Exception {
        Path withPreview = TestMedia.jpegWithPreview(tmp.resolve("phone.jpg"), filled(640, 480, Color.RED),
                filled(320, 240, Color.BLUE), 1);
        Path small = TestMedia.png(tmp.resolve("chat.png"), 300, 200);
        BufferedImage noise = new BufferedImage(1600, 1200, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(5);
        for (int y = 0; y < noise.getHeight(); y++) {
            for (int x = 0; x < noise.getWidth(); x++) {
                noise.setRGB(x, y, random.nextInt());
            }
        }
        Path big = tmp.resolve("old-camera.jpg");
        ImageIO.write(noise, "jpg", big.toFile());
        assertTrue(Files.size(big) > ThumbnailGenerator.QUICK_BYTES, "a big photo with no preview");
        Path video = Files.write(tmp.resolve("clip.mp4"), new byte[] {1});

        assertTrue(generator.generateQuick(withPreview, tmp.resolve("1.jpg")));
        assertTrue(generator.generateQuick(small, tmp.resolve("2.jpg")));
        assertFalse(generator.generateQuick(big, tmp.resolve("3.jpg")), "read whole: pass 2");
        assertFalse(generator.generateQuick(video, tmp.resolve("4.jpg")), "videos: pass 2");
        assertFalse(Files.exists(tmp.resolve("3.jpg")) || Files.exists(tmp.resolve("4.jpg")), "nothing written");
    }

    @Test
    void headersWithoutAUsablePreviewFindNothing() throws Exception {
        Path plain = tmp.resolve("plain.jpg");
        ImageIO.write(filled(64, 48, Color.RED), "jpg", plain.toFile());
        assertFalse(EmbeddedPreview.find(Files.readAllBytes(plain)).isPresent(), "no EXIF");

        byte[] withPreview = Files.readAllBytes(TestMedia.jpegWithPreview(tmp.resolve("p.jpg"),
                filled(640, 480, Color.RED), filled(320, 240, Color.BLUE), 1));
        assertTrue(EmbeddedPreview.find(withPreview).isPresent());
        for (int cut : new int[] {0, 1, 2, 3, 20, 60, 200, 1000}) {
            assertFalse(EmbeddedPreview.find(Arrays.copyOf(withPreview, cut)).isPresent(), "cut at " + cut);
        }

        byte[] noise = new byte[4096];
        new Random(3).nextBytes(noise);
        noise[0] = (byte) 0xFF;
        noise[1] = (byte) 0xD8;
        assertFalse(EmbeddedPreview.find(noise).isPresent(), "a JPEG start and then rubbish");
    }

    @Test
    void theShapeMayDifferOnlyByRounding() {
        EmbeddedPreview.Found photo = new EmbeddedPreview.Found(new byte[0], 4000, 3000, 1);
        assertTrue(EmbeddedPreview.fits(320, 240, photo, 320));
        assertTrue(EmbeddedPreview.fits(320, 241, photo, 320), "a pixel of rounding");
        assertFalse(EmbeddedPreview.fits(320, 180, photo, 320), "16:9 against 4:3");
        assertFalse(EmbeddedPreview.fits(240, 320, photo, 320), "turned the other way");
        assertFalse(EmbeddedPreview.fits(256, 192, photo, 320), "smaller than the thumbnail");
    }

    private static int indexOf(byte[] bytes, byte[] part, int from) {
        for (int i = from; i + part.length <= bytes.length; i++) {
            if (Arrays.equals(bytes, i, i + part.length, part, 0, part.length)) {
                return i;
            }
        }
        throw new AssertionError("not found");
    }

    private static boolean isRed(int rgb) {
        return ((rgb >> 16) & 0xFF) > 200 && ((rgb >> 8) & 0xFF) < 60 && (rgb & 0xFF) < 60;
    }

    private static boolean isGreen(int rgb) {
        return ((rgb >> 16) & 0xFF) < 60 && ((rgb >> 8) & 0xFF) > 200 && (rgb & 0xFF) < 60;
    }

    private static boolean isBlue(int rgb) {
        return ((rgb >> 16) & 0xFF) < 60 && ((rgb >> 8) & 0xFF) < 60 && (rgb & 0xFF) > 200;
    }
}
