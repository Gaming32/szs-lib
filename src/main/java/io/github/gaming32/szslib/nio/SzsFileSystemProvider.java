package io.github.gaming32.szslib.nio;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.nio.file.spi.FileSystemProvider;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class SzsFileSystemProvider extends FileSystemProvider {
    private static final String ALL_ATTRS = "size,creationTime,lastAccessTime,lastModifiedTime,isDirectory,isRegularFile,isSymbolicLink,isOther,fileKey";

    final Map<Path, SzsFileSystem> fileSystems = new HashMap<>();

    @Override
    public String getScheme() {
        return "szs";
    }

    private Path uriToPath(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase(getScheme())) {
            throw new IllegalArgumentException("URI scheme is not '" + getScheme() + "'");
        }
        try {
            String spec = uri.getRawSchemeSpecificPart();
            int sep = spec.indexOf("!/");
            if (sep != -1) {
                spec = spec.substring(0, sep);
            }
            return Paths.get(new URI(spec)).toAbsolutePath();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private boolean ensureFile(Path path) {
        try {
            final BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            if (!attrs.isRegularFile()) {
                throw new UnsupportedOperationException();
            }
            return true;
        } catch (IOException ioe) {
            return false;
        }
    }

    @Override
    public SzsFileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException {
        final Path path = uriToPath(uri);
        synchronized (fileSystems) {
            Path realPath = null;
            if (ensureFile(path)) {
                realPath = path.toRealPath();
                if (fileSystems.containsKey(realPath)) {
                    throw new FileSystemAlreadyExistsException();
                }
            }
            final SzsFileSystem fs = new SzsFileSystem(this, path, env);
            if (realPath == null) {
                realPath = path.toRealPath();
            }
            fileSystems.put(realPath, fs);
            return fs;
        }
    }

    @Override
    public FileSystem newFileSystem(Path path, Map<String, ?> env) throws IOException {
        ensureFile(path);
        return new SzsFileSystem(this, path, env);
    }

    @Override
    public FileSystem getFileSystem(URI uri) {
        synchronized (fileSystems) {
            SzsFileSystem fs = null;
            try {
                fs = fileSystems.get(uriToPath(uri).toRealPath());
            } catch (IOException ignored) {
            }
            if (fs == null) {
                throw new FileSystemNotFoundException();
            }
            return fs;
        }
    }

    @Override
    @SuppressWarnings("NullableProblems") // TODO: Add JB annotations
    public Path getPath(URI uri) {
        String spec = uri.getSchemeSpecificPart();
        int sep = spec.indexOf("!/");
        if (sep == -1) {
            throw new IllegalArgumentException("URI: " + uri + " does not contain path info.");
        }
        return getFileSystem(uri).getPath(spec.substring(sep + 1));
    }

    private static SzsPath toSzsPath(Path path) {
        if (path == null) {
            throw new NullPointerException();
        }
        if (!(path instanceof SzsPath szsPath)) {
            throw new ProviderMismatchException();
        }
        return szsPath;
    }

    @Override
    public SeekableByteChannel newByteChannel(
        Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs
    ) throws NoSuchFileException {
        return toSzsPath(path).newByteChannel(options, attrs);
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(
        Path dir, DirectoryStream.Filter<? super Path> filter
    ) throws NotDirectoryException {
        return toSzsPath(dir).newDirectoryStream(filter);
    }

    private static <T> T readOnly() throws UnsupportedOperationException {
        throw new UnsupportedOperationException("SzsFileSystems are read-only");
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws UnsupportedOperationException {
        readOnly();
    }

    @Override
    public void delete(Path path) throws UnsupportedOperationException {
        readOnly();
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) throws UnsupportedOperationException {
        readOnly();
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws UnsupportedOperationException {
        readOnly();
    }

    @Override
    public boolean isSameFile(Path path, Path path2) throws IOException {
        return path.equals(path2) || path.toAbsolutePath().normalize().equals(path2.toAbsolutePath().normalize());
    }

    @Override
    public boolean isHidden(Path path) {
        return false;
    }

    @Override
    public FileStore getFileStore(Path path) throws IOException {
        return toSzsPath(path).getFileStore();
    }

    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        for (final AccessMode mode : modes) {
            if (mode == AccessMode.WRITE || mode == AccessMode.EXECUTE) {
                throw new IOException("Cannot write or execute files in SzsFileSystem");
            }
        }
        if (!toSzsPath(path).exists()) {
            throw new NoSuchFileException(path + " doesn't exist");
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
        if (type != BasicFileAttributeView.class) {
            return null;
        }
        return (V)new BasicFileAttributeView() {
            @Override
            public String name() {
                return "basic";
            }

            @Override
            public BasicFileAttributes readAttributes() throws IOException {
                return SzsFileSystemProvider.this.readAttributes(path, BasicFileAttributes.class, options);
            }

            @Override
            public void setTimes(FileTime lastModifiedTime, FileTime lastAccessTime, FileTime createTime) throws IOException {
                throw new IOException("Read-only filesystem");
            }
        };
    }

    @Override
    @SuppressWarnings("unchecked")
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) throws NoSuchFileException {
        if (type != BasicFileAttributes.class) {
            throw new UnsupportedOperationException(type.toString());
        }
        return (A)toSzsPath(path).readAttributes();
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        String view, attrs;
        final int colonIndex = attributes.indexOf(':');
        if (colonIndex == -1) {
            view = "basic";
            attrs = attributes;
        } else {
            view = attributes.substring(colonIndex);
            attrs = attributes.substring(colonIndex + 1);
        }
        if (!view.equals("basic")) {
            throw new UnsupportedOperationException("Only basic attributes are supported");
        }
        if (attrs.equals("*")) {
            attrs = ALL_ATTRS;
        }
        final BasicFileAttributes obj = toSzsPath(path).readAttributes();
        final Map<String, Object> result = new LinkedHashMap<>();
        for (final String id : attrs.split(":")) {
            result.put(id, switch (id) {
                case "size" -> obj.size();
                case "creationTime" -> obj.creationTime();
                case "lastAccessTime" -> obj.lastAccessTime();
                case "isDirectory" -> obj.isDirectory();
                case "isRegularFile" -> obj.isRegularFile();
                case "isSymbolicLink" -> obj.isSymbolicLink();
                case "isOther" -> obj.isOther();
                case "fileKey" -> obj.fileKey();
                default -> null;
            });
        }
        return result;
    }

    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options) throws IOException {
        throw new IOException("Filesystem is read-only");
    }
}
