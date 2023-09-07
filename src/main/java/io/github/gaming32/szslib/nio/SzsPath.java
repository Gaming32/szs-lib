package io.github.gaming32.szslib.nio;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;

public class SzsPath implements Path {
    static final String[] EMPTY_PATH = new String[0];

    private final SzsFileSystem fileSystem;
    private final String[] path;
    private final boolean absolute;
    private SzsPath normalized;

    SzsPath(SzsFileSystem fileSystem, String[] path, boolean absolute) {
        this.fileSystem = fileSystem;
        this.path = path; // Caller is expected to copy if necessary!
        this.absolute = absolute;
    }

    SzsPath(SzsFileSystem fileSystem, String singlePart, boolean absolute) {
        this(fileSystem, new String[] {singlePart}, absolute);
    }

    SzsPath(SzsFileSystem fileSystem, String path) {
        this.fileSystem = fileSystem;
        final String[] parts = path.split("/+");
        if (parts.length == 0) {
            this.path = EMPTY_PATH;
            this.absolute = true;
        } else if (parts.length == 1 && parts[0].isEmpty()) {
            this.path = parts;
            this.absolute = false;
        } else if (parts[0].isEmpty()) {
            this.path = Arrays.copyOfRange(parts, 1, parts.length);
            this.absolute = true;
        } else {
            this.path = parts;
            this.absolute = false;
        }
    }

    @Override
    public SzsFileSystem getFileSystem() {
        return fileSystem;
    }

    String[] getParts() {
        return path;
    }

    @Override
    public boolean isAbsolute() {
        return absolute;
    }

    @Override
    public Path getRoot() {
        return fileSystem.getRootPath();
    }

    @Override
    public Path getFileName() {
        return path.length == 0 ? null : new SzsPath(fileSystem, path[path.length - 1], false);
    }

    @Override
    public Path getParent() {
        if (path.length == 0) {
            return null;
        }
        if (path.length == 1) {
            return absolute ? new SzsPath(fileSystem, EMPTY_PATH, true) : null;
        }
        return new SzsPath(fileSystem, Arrays.copyOf(path, path.length - 1), absolute);
    }

    @Override
    public int getNameCount() {
        return path.length;
    }

    @Override
    public Path getName(int index) {
        if (index < 0) {
            throw new IllegalArgumentException("index (" + index + ") < 0");
        }
        if (index >= path.length) {
            throw new IllegalArgumentException("index (" + index + ") >= " + path.length);
        }
        return new SzsPath(fileSystem, path[index], false);
    }

    @Override
    public Path subpath(int beginIndex, int endIndex) {
        if (beginIndex < 0) {
            throw new IllegalArgumentException("beginIndex (" + beginIndex + ") < 0");
        }
        if (endIndex < 0) {
            throw new IllegalArgumentException("endIndex (" + endIndex + ") < 0");
        }
        if (beginIndex >= path.length) {
            throw new IllegalArgumentException("beginIndex (" + beginIndex + ") >= " + path.length);
        }
        if (endIndex > path.length) {
            throw new IllegalArgumentException("endIndex (" + endIndex + ") >= " + path.length);
        }
        return new SzsPath(fileSystem, Arrays.copyOfRange(path, beginIndex, endIndex), false);
    }

    @Override
    public boolean startsWith(Path other) {
        if (other.getFileSystem() != fileSystem) {
            return false;
        }
        final SzsPath o = (SzsPath)other;
        if (o.absolute != absolute || o.path.length > path.length) {
            return false;
        }
        for (int i = 0; i < o.path.length; i++) {
            if (!path[i].equals(o.path[i])) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean endsWith(Path other) {
        if (other.getFileSystem() != fileSystem) {
            return false;
        }
        final SzsPath o = (SzsPath)other;
        final int oLength = o.path.length;
        final int length = path.length;
        if ((o.absolute && (!absolute || oLength != length)) || length < oLength) {
            return false;
        }
        for (int i = oLength - 1, j = length - 1; i >= 0; i--, j--) {
            if (!path[j].equals(o.path[i])) {
                return false;
            }
        }
        return true;
    }

    @Override
    public SzsPath normalize() {
        if (normalized != null) {
            return normalized;
        }
        if (path.length < 2) {
            return normalized = this;
        }

        boolean found = false;
        for (final String part : path) {
            // Fast path: do nothing if none of the parts are special
            if (part.equals(".") || part.equals("..")) {
                found = true;
                break;
            }
        }
        if (!found) {
            return normalized = this;
        }

        final List<String> result = new ArrayList<>(path.length);
        for (int i = 0; i < path.length; i++) {
            final String part = path[i];
            if (part.equals(".")) {
                continue; // Simply skip
            }
            if (part.equals("..") && i > 0) {
                final int lastIndex = result.size() - 1;
                if (!result.get(lastIndex).equals("..")) {
                    result.remove(lastIndex);
                } else {
                    result.add("..");
                }
                continue;
            }
            result.add(part);
        }
        if (result.size() == path.length) {
            return normalized = this;
        }
        return normalized = new SzsPath(fileSystem, result.toArray(String[]::new), absolute);
    }

    @Override
    public Path resolve(Path other) {
        if (!(other instanceof SzsPath o)) {
            throw new ProviderMismatchException();
        }
        if ((path.length == 1 && path[0].isEmpty()) || o.absolute) {
            return o;
        }
        if (o.path.length == 1 && path[0].isEmpty()) {
            return this;
        }
        final String[] parts = new String[path.length + o.path.length];
        System.arraycopy(path, 0, parts, 0, path.length);
        System.arraycopy(o.path, 0, parts, path.length, o.path.length);
        return new SzsPath(fileSystem, parts, absolute);
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
            if (path[i].equals(o.path[i])) {
                sameStart++;
            } else {
                break;
            }
        }

        final List<String> result = new ArrayList<>();
        for (int i = 0; i < nameCount - sameStart; i++) {
            result.add("..");
        }
        //noinspection ManualArrayToCollectionCopy
        for (int i = sameStart; i < oNameCount; i++) {
            result.add(o.path[i]);
        }
        return new SzsPath(fileSystem, result.toArray(String[]::new), false);
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
        if (absolute) {
            return this;
        }
        if (path.length == 1 && path[0].isEmpty()) {
            return fileSystem.getRootPath();
        }
        return new SzsPath(fileSystem, path, true);
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
        return Arrays.compare(path, o.path);
    }

    @Override
    public String toString() {
        return (absolute ? "/" : "") + String.join("/", path);
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
