package io.github.gaming32.szslib.yaz0;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

public class Yaz0InputStream extends InputStream {
    private final InputStream delegate;
    private final byte[] buffer;

    private int outPtr;
    private int copyLen;
    private int copyOffset;
    private int group;
    private int groupLen;

    public Yaz0InputStream(InputStream delegate) throws IOException {
        this.delegate = delegate;
        try {
            if (delegate.read() != 'Y' || delegate.read() != 'a' || delegate.read() != 'z' || delegate.read() != '0') {
                throw new IOException("Invalid Yaz0 magic");
            }
            buffer = new byte[(delegate.read() << 24) | (delegate.read() << 16) | (delegate.read() << 8) | delegate.read()];
            delegate.skipNBytes(8);
        } catch (IOException e) {
            delegate.close();
            throw e;
        }
    }

    public Yaz0InputStream(Path path) throws IOException {
        this(Files.newInputStream(path));
    }

    public Yaz0InputStream(File file) throws IOException {
        this(new BufferedInputStream(new FileInputStream(file)));
    }

    public InputStream getDelegate() {
        return delegate;
    }

    public int getUncompressedSize() {
        return buffer.length;
    }

    @Override
    public int available() {
        return buffer.length - outPtr;
    }

    @Override
    public int read() throws IOException {
        if (outPtr >= buffer.length) {
            return -1;
        }
        if (copyLen > 0) {
            return copy1();
        }
        if (readChunk()) {
            final int value = delegate.read();
            if (value < 0) {
                throw new EOFException("Expected byte in chunk");
            }
            buffer[outPtr++] = (byte)value;
            return value;
        } else {
            if (group < 0) {
                return -1;
            }
            return copy1();
        }
    }

    @Override
    @SuppressWarnings("NullableProblems")
    public int read(byte[] b, int off, int len) throws IOException {
        if (outPtr >= buffer.length) {
            return -1;
        }
        final int totalToRead = Math.min(len, buffer.length - outPtr);
        len = totalToRead;
        while (len > 0) {
            if (copyLen > 0) {
                final int toCopy = Math.min(len, copyLen);
                if (copyOffset + toCopy >= outPtr) {
                    for (int i = 0; i < toCopy; i++) { // Custom loop to handle overlaps correctly
                        buffer[outPtr++] = buffer[copyOffset++];
                    }
                } else {
                    System.arraycopy(buffer, copyOffset, buffer, outPtr, toCopy);
                    copyOffset += toCopy;
                    outPtr += toCopy;
                }
                System.arraycopy(buffer, outPtr - toCopy, b, off, toCopy);
                off += toCopy;
                len -= toCopy;
                copyLen -= toCopy;
            } else if (readChunk()) {
                final int value = delegate.read();
                if (value < 0) {
                    throw new EOFException("Expected byte in chunk");
                }
                b[off++] = buffer[outPtr++] = (byte)value;
                len--;
            } else if (group < 0) {
                return totalToRead - len;
            }
        }
        return totalToRead;
    }

    private int copy1() {
        copyLen--;
        return (buffer[outPtr++] = buffer[copyOffset++]) & 0xff;
    }

    private void readGroup() throws IOException {
        group = delegate.read();
        if (group < 0) return;
        groupLen = 8;
    }

    private boolean readChunk() throws IOException {
        if (groupLen == 0) {
            readGroup();
            if (group < 0) {
                return false;
            }
        }
        groupLen--;
        if ((group & 0x80) != 0) {
            group <<= 1;
            return true;
        }
        final int byte1 = delegate.read();
        if (byte1 < 0) {
            throw new EOFException("Expected byte 1 of chunk header");
        }
        final int byte2 = delegate.read();
        if (byte2 < 0) {
            throw new EOFException("Expected byte 2 of chunk header");
        }
        copyOffset = outPtr - (((byte1 & 0xf) << 8) | byte2) - 1;
        if (copyOffset >= outPtr) {
            throw new IOException("Illegal copyOffset: " + copyOffset + " >= " + outPtr);
        } else if (copyOffset < 0) {
            throw new IOException("Illegal copyOffset: " + copyOffset + " < 0");
        }
        if ((byte1 & 0xf) == byte1) {
            final int byte3 = delegate.read();
            if (byte3 < 0) {
                throw new EOFException("Expected byte 3 of chunk header");
            }
            copyLen = byte3 + 0x12;
        } else {
            copyLen = (byte1 >> 4) + 2;
            if (copyLen < 3) {
                throw new IOException("copyLen < 3");
            }
        }
        if (outPtr + copyLen > buffer.length) {
            throw new IOException("Illegal copyLen: " + outPtr + " + " + copyLen + " > " + buffer.length);
        }
        group <<= 1;
        return false;
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
