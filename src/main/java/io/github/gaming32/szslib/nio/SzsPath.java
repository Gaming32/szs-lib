package io.github.gaming32.szslib.nio;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;

public class SzsPath implements Path {
    private static final int[] EMPTY_OFFSETS = new int[0];

    private final SzsFileSystem fileSystem;
    private final String path;
    private String normalized;
    private int[] offsets;

    SzsPath(SzsFileSystem fileSystem, String path, boolean canonical) {
        this.fileSystem = fileSystem;
        this.path = canonical ? path : canonicalize(path);
    }

    private static String canonicalize(String path) {
        if (path.length() < 2) {
            return path;
        }
        for (int i = 1; i < path.length(); i++) {
            if (path.charAt(i) == '/' && path.charAt(i - 1) == '/') {
                return canonicalize0(path, i);
            }
        }
        if (path.charAt(path.length() - 1) == '/') {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }

    private static String canonicalize0(String path, int goodUntil) {
        final StringBuilder result = new StringBuilder(path);
        result.setLength(goodUntil);
        int lastI = goodUntil + 1;
        int i = lastI;
        while (true) {
            for (; i < path.length(); i++) {
                if (path.charAt(i) == '/' && path.charAt(i - 1) == '/') break;
            }
            result.append(path, lastI, i);
            if (i == path.length()) break;
            lastI = ++i;
        }
        if (result.charAt(result.length() - 1) == '/') {
            result.setLength(result.length() - 1);
        }
        return result.toString();
    }

    @Override
    public SzsFileSystem getFileSystem() {
        return fileSystem;
    }

    @Override
    public boolean isAbsolute() {
        return path.startsWith("/");
    }

    @Override
    public Path getRoot() {
        return fileSystem.getRootPath();
    }

    @Override
    public Path getFileName() {
        final int slashIndex = path.lastIndexOf('/');
        return slashIndex == -1 ? this : new SzsPath(fileSystem, path.substring(slashIndex + 1), true);
    }

    @Override
    public Path getParent() {
        final int slashIndex = path.lastIndexOf('/');
        return slashIndex < 1 ? null : new SzsPath(fileSystem, path.substring(slashIndex), true);
    }

    private int[] initOffsets() {
        if (offsets == null) {
            // Ok if calculated multiple times
            if (path.equals("/")) {
                return offsets = EMPTY_OFFSETS;
            }

            int offsetCount = 1;
            for (int i = 1; i < path.length(); i++) {
                if (path.charAt(i) == '/') {
                    offsetCount++;
                }
            }

            final int[] newOffsets = new int[offsetCount];
            int index = isAbsolute() ? 1 : 0;
            int offset = 1;
            newOffsets[0] = index;
            while (index < path.length()) {
                final int next = path.indexOf('/', index) + 1;
                if (next == 0) break;
                newOffsets[offset++] = next;
                index = next;
            }

            return offsets = newOffsets;
        }
        return offsets;
    }

    @Override
    public int getNameCount() {
        return initOffsets().length;
    }

    private String getSubPath(int index) {
        final int[] offsets = initOffsets();
        if (index == offsets.length - 1) {
            return path.substring(offsets[index]);
        } else {
            return path.substring(offsets[index], offsets[index + 1] - 1);
        }
    }

    @Override
    public Path getName(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("index (" + index + ") < 0");
        }
        final int[] offsets = initOffsets();
        if (index >= offsets.length) {
            throw new IllegalArgumentException("index (" + index + ") >= " + offsets.length);
        }
        return new SzsPath(fileSystem, getSubPath(index), true);
    }

    @Override
    public Path subpath(int beginIndex, int endIndex) {
        if (beginIndex < 0) {
            throw new IllegalArgumentException("beginIndex (" + beginIndex + ") < 0");
        }
        if (endIndex < 0) {
            throw new IllegalArgumentException("endIndex (" + endIndex + ") < 0");
        }
        final int[] offsets = initOffsets();
        if (beginIndex >= offsets.length) {
            throw new IllegalArgumentException("beginIndex (" + beginIndex + ") >= " + offsets.length);
        }
        if (endIndex > offsets.length) {
            throw new IllegalArgumentException("endIndex (" + endIndex + ") >= " + offsets.length);
        }
        final String subPath;
        if (endIndex == offsets.length) {
            subPath = path.substring(offsets[beginIndex]);
        } else {
            subPath = path.substring(offsets[beginIndex], offsets[endIndex] - 1);
        }
        return new SzsPath(fileSystem, subPath, true);
    }

    @Override
    public boolean startsWith(Path other) {
        if (other.getFileSystem() != fileSystem) {
            return false;
        }
        final SzsPath o = (SzsPath)other;
        if (o.isAbsolute() != isAbsolute()) {
            return false;
        }
        if (!path.startsWith(o.path)) {
            return false;
        }
        final int oLength = o.path.length();
        return oLength == path.length() || o.path.charAt(oLength - 1) == '/' || path.charAt(oLength) == '/';
    }

    @Override
    public boolean endsWith(Path other) {
        if (other.getFileSystem() != fileSystem) {
            return false;
        }
        final SzsPath o = (SzsPath)other;
        final int oLength = o.path.length();
        final int length = path.length();
        if ((o.isAbsolute() && (!isAbsolute() || oLength != length)) || length < oLength) {
            return false;
        }
        if (!path.endsWith(o.path)) {
            return false;
        }
        return length == oLength || path.charAt(length - oLength) == '/';
    }

    public String normalizeToString() {
        if (normalized != null) {
            return normalized;
        }
        if (path.equals(".")) {
            return normalized = "";
        }
        final int[] offsets = initOffsets();
        if (offsets.length < 2) {
            return normalized = path;
        }
        final StringBuilder result = new StringBuilder(isAbsolute() ? "/" : "");
        for (int i = 1; i < offsets.length + 1; i++) {
            final String part = i < offsets.length
                ? path.substring(offsets[i - 1], offsets[i] - 1)
                : path.substring(offsets[i - 1]);
            if (part.equals(".")) {
                continue;
            }
            if (part.equals("..") && result.length() > 2) {
                final int delPartIndex = result.lastIndexOf("/", result.length() - 2) + 1;
                if (delPartIndex != result.length() - 3 || result.charAt(delPartIndex) != '.' || result.charAt(delPartIndex + 1) != '.') {
                    result.setLength(result.lastIndexOf("/", result.length() - 2) + 1);
                    continue;
                }
            }
            result.append(part).append('/');
        }
        result.setLength(result.length() - 1);
        return normalized = result.toString();
    }

    @Override
    public SzsPath normalize() {
        //noinspection StringEquality
        return normalizeToString() == path ? this : new SzsPath(fileSystem, normalized, true);
    }

    @Override
    public Path resolve(Path other) {
        if (!(other instanceof SzsPath o)) {
            throw new ProviderMismatchException();
        }
        if (path.isEmpty() || o.isAbsolute()) {
            return o;
        }
        if (o.path.isEmpty()) {
            return this;
        }
        return new SzsPath(fileSystem, path + '/' + o.path, true);
    }

    @Override
    public Path relativize(Path other) {
        if (!(other instanceof SzsPath o)) {
            throw new ProviderMismatchException();
        }
        final int nameCount = getNameCount();
        final int oNameCount = o.getNameCount();
        int sameStart = 0;
        for (int i = 0, l = Math.min(nameCount, oNameCount); i < l; i++) {
            if (getSubPath(i).equals(o.getSubPath(i))) {
                sameStart++;
            } else {
                break;
            }
        }

        final StringBuilder result = new StringBuilder();
        if (sameStart < nameCount) {
            result.append("../".repeat(nameCount - sameStart));
        }
        if (oNameCount > sameStart) {
            result.append(o.path.substring(o.offsets[sameStart]));
        } else if (sameStart < nameCount) {
            result.setLength(result.length() - 1);
        }
        return new SzsPath(fileSystem, result.toString(), true);
    }

    private static int decode(char c) {
        if ((c >= '0') && (c <= '9'))
            return c - '0';
        if ((c >= 'a') && (c <= 'f'))
            return c - 'a' + 10;
        if ((c >= 'A') && (c <= 'F'))
            return c - 'A' + 10;
        assert false;
        return -1;
    }

    private static String decodeUri(String s) {
        if (s == null)
            return null;
        int n = s.length();
        if (n == 0)
            return s;
        if (s.indexOf('%') < 0)
            return s;

        StringBuilder sb = new StringBuilder(n);
        byte[] bb = new byte[n];
        boolean betweenBrackets = false;

        for (int i = 0; i < n;) {
            char c = s.charAt(i);
            if (c == '[') {
                betweenBrackets = true;
            } else if (betweenBrackets && c == ']') {
                betweenBrackets = false;
            }
            if (c != '%' || betweenBrackets ) {
                sb.append(c);
                i++;
                continue;
            }
            int nb = 0;
            while (c == '%') {
                assert (n - i >= 2);
                bb[nb++] = (byte)(((decode(s.charAt(++i)) & 0xf) << 4) |
                    (decode(s.charAt(++i)) & 0xf));
                if (++i >= n) {
                    break;
                }
                c = s.charAt(i);
            }
            sb.append(new String(bb, 0, nb, UTF_8));
        }
        return sb.toString();
    }

    @Override
    public URI toUri() {
        try {
            return new URI(
                fileSystem.provider().getScheme(),
                decodeUri(fileSystem.getPath().toUri().toString()) + "!" + toAbsolutePath(),
                null
            );
        } catch (URISyntaxException e) {
            throw new AssertionError(e);
        }
    }

    @Override
    public Path toAbsolutePath() {
        return isAbsolute() ? this : new SzsPath(fileSystem, '/' + path, true);
    }

    @Override
    public Path toRealPath(LinkOption... options) throws IOException {
        final Path result = toAbsolutePath().normalize();
        fileSystem.provider().checkAccess(result);
        return result;
    }

    @Override
    public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers) throws IOException {
        throw new ProviderMismatchException();
    }

    @Override
    public int compareTo(Path other) {
        if (!(other instanceof SzsPath o)) {
            throw new ProviderMismatchException();
        }
        return path.compareTo(o.path);
    }

    @Override
    public String toString() {
        return path;
    }

    public SeekableByteChannel newByteChannel(
        Set<? extends OpenOption> options, FileAttribute<?>... attrs
    ) throws NoSuchFileException {
        return fileSystem.newByteChannel(this, options, attrs);
    }

    public DirectoryStream<Path> newDirectoryStream(DirectoryStream.Filter<? super Path> filter) throws NotDirectoryException {
        return fileSystem.newDirectoryStream(this, filter);
    }

    public SzsFileStore getFileStore() throws NoSuchFileException {
        return fileSystem.getFileStore(this);
    }

    public boolean exists() {
        return fileSystem.exists(this);
    }

    public BasicFileAttributes readAttributes() throws NoSuchFileException {
        return fileSystem.readAttributes(this);
    }
}
