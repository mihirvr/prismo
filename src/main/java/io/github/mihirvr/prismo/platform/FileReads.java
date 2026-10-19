package io.github.mihirvr.prismo.platform;

import com.drew.imaging.ImageMetadataReader;
import com.drew.imaging.ImageProcessingException;
import com.drew.metadata.Metadata;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageInputStreamImpl;

/**
 * Opens the user's files for reading without stopping anyone else from deleting or renaming them.
 *
 * <p>On Windows, a file opened through {@code java.io} ({@code FileInputStream}, {@code RandomAccessFile}) can't
 * be deleted or renamed by anyone until it is closed; Explorer says "file in use". A file opened through
 * {@code java.nio.file} ({@code Files.newInputStream}, {@code Files.newByteChannel}) can. Checked in #8, 6 of 6:
 * delete and rename both refused while a {@code RandomAccessFile} or {@code FileInputStream} was open, both fine
 * with {@code Files.newInputStream}. {@code ImageIO.createImageInputStream(File)} uses a
 * {@code RandomAccessFile}, and metadata-extractor's {@code readMetadata(File)} a {@code FileInputStream}, so
 * Prismo opens files here instead.
 */
public final class FileReads {

    private FileReads() {
    }

    /** metadata-extractor's {@code readMetadata(File)}, without locking the file. */
    public static Metadata metadata(Path file) throws IOException, ImageProcessingException {
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            return ImageMetadataReader.readMetadata(in, Files.size(file));
        }
    }

    /**
     * {@code ImageIO.createImageInputStream(File)}, without locking the file. Not ImageIO's own stream version:
     * by default that copies everything it reads into a temporary file so it can seek back.
     */
    public static ImageInputStream image(Path file) throws IOException {
        return new ChannelImageInputStream(Files.newByteChannel(file, StandardOpenOption.READ));
    }

    /**
     * An {@link ImageInputStream} over a seekable channel, with a read buffer (ImageIO readers ask for small
     * pieces at a time). Position, mark/reset and bit reading come from {@link ImageInputStreamImpl}; this class
     * only says how to fetch bytes from a position.
     */
    static final class ChannelImageInputStream extends ImageInputStreamImpl {

        private static final int BUFFER_SIZE = 64 * 1024;

        private final SeekableByteChannel channel;
        private final ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE);
        /** The file position of {@code buffer}'s first byte; the buffer holds {@code buffer.limit()} bytes. */
        private long bufferStart;

        ChannelImageInputStream(SeekableByteChannel channel) {
            this.channel = channel;
            buffer.limit(0);
        }

        @Override
        public int read() throws IOException {
            checkClosed();
            bitOffset = 0;
            if (!fill()) {
                return -1;
            }
            int value = buffer.get((int) (streamPos - bufferStart)) & 0xff;
            streamPos++;
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            checkClosed();
            if (offset < 0 || length < 0 || offset + length > bytes.length) {
                throw new IndexOutOfBoundsException();
            }
            bitOffset = 0;
            if (length == 0) {
                return 0;
            }
            if (!fill()) {
                return -1;
            }
            int from = (int) (streamPos - bufferStart);
            int count = Math.min(length, buffer.limit() - from);
            buffer.get(from, bytes, offset, count);
            streamPos += count;
            return count;
        }

        /**
         * Makes sure the buffer holds the byte at {@code streamPos}, reading from the channel if not.
         *
         * @return false at the end of the file
         */
        private boolean fill() throws IOException {
            if (streamPos >= bufferStart && streamPos < bufferStart + buffer.limit()) {
                return true;
            }
            buffer.clear();
            channel.position(streamPos);
            int read;
            do {
                read = channel.read(buffer);
            } while (read == 0);
            buffer.flip();
            bufferStart = streamPos;
            return buffer.limit() > 0;
        }

        @Override
        public long length() {
            try {
                return channel.size();
            } catch (IOException e) {
                return -1; // "unknown", as the interface allows
            }
        }

        @Override
        public void close() throws IOException {
            super.close();
            channel.close();
        }
    }
}
