package io.github.gaming32.szslib;

import org.apache.commons.compress.utils.IOUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;

public class SzsDetector {
    public static final int YAZ0_MAGIC = 0x59617A30; // "Yaz0"
    public static final int U8_MAGIC = 0x55AA382D;
    public static final int SARC_MAGIC = 0x53415243; // "SARC"

    public static Format getFormat(int magic) {
        return switch (magic) {
            case YAZ0_MAGIC -> Format.Yaz0;
            case U8_MAGIC -> Format.U8;
            case SARC_MAGIC -> Format.SARC;
            default -> null;
        };
    }

    public static Format getFormat(InputStream is) throws IOException {
        final byte[] buffer = new byte[4];
        IOUtils.readFully(is, buffer);
        return getFormat(
            ((buffer[0] & 0xff) << 24)
                | ((buffer[1] & 0xff) << 16)
                | ((buffer[2] & 0xff) << 8)
                | (buffer[3] & 0xff)
        );
    }

    public static Format getFormat(ByteBuffer buffer) {
        final ByteOrder order = buffer.order();
        buffer.order(ByteOrder.BIG_ENDIAN);
        final Format result = getFormat(buffer.getInt());
        buffer.order(order);
        return result;
    }

    public static Format getFormat(ReadableByteChannel channel) throws IOException {
        final ByteBuffer buffer = ByteBuffer.allocate(4);
        IOUtils.readFully(channel, buffer);
        buffer.flip();
        return getFormat(buffer);
    }

    public static Format getFormat(File file) throws IOException {
        try (final InputStream is = new FileInputStream(file)) {
            return getFormat(is);
        }
    }

    public static Format getFormat(Path path) throws IOException {
        try (final InputStream is = Files.newInputStream(path)) {
            return getFormat(is);
        }
    }

    public enum Format {
        Yaz0(YAZ0_MAGIC),
        U8(U8_MAGIC),
        SARC(SARC_MAGIC);

        private final int magic;

        Format(int magic) {
            this.magic = magic;
        }

        public int getMagic() {
            return magic;
        }
    }
}
