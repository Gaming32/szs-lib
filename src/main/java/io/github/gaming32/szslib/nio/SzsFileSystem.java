package io.github.gaming32.szslib.nio;

import io.github.gaming32.szslib.SzsDetector;
import io.github.gaming32.szslib.decompressed.DecompressedSzsFile;
import io.github.gaming32.szslib.u8.U8File;
import io.github.gaming32.szslib.yaz0.Yaz0InputStream;
import org.apache.commons.compress.utils.IOUtils;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardOpenOption;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.regex.Pattern;

public class SzsFileSystem extends FileSystem {
    private static final String GLOB_SYNTAX = "glob";
    private static final String REGEX_SYNTAX = "regex";

    private final SzsFileSystemProvider provider;
    private final Path path;
    private final DecompressedSzsFile file;
    private final DecompressedSzsFile.DirectoryNode root;
    private final int toPathChop;

    private final SzsPath rootPath = new SzsPath(this, SzsPath.EMPTY_PATH, true);

    SzsFileSystem(SzsFileSystemProvider provider, Path path, Map<String, ?> env) throws IOException {
        this.provider = provider;
        this.path = path;

        try (final InputStream is = new BufferedInputStream(Files.newInputStream(path))) {
            this.file = openFile(is);
        }

        DecompressedSzsFile.DirectoryNode root = file.getRoot();
        while (root.getChild(".") instanceof DecompressedSzsFile.DirectoryNode dir) {
            root = dir;
        }
        this.root = root;
        toPathChop = root.getParents().length + 1;
    }

    private static DecompressedSzsFile openFile(InputStream is) throws IOException {
        is.mark(4);
        final SzsDetector.Format format = SzsDetector.getFormat(is);
        is.reset();
        if (format == null) {
            final byte[] magic = new byte[4];
            IOUtils.readFully(is, magic);
            throw new IOException("Unknown SZS format: " + new String(magic, StandardCharsets.ISO_8859_1));
        }
        return switch (format) {
            case Yaz0 -> {
                try (InputStream innerStream = new BufferedInputStream(new Yaz0InputStream(is))) {
                    yield openFile(innerStream);
                }
            }
            case U8 -> U8File.fromInputStream(is);
            default -> throw new IOException("Unsupported SZS format: " + format);
        };
    }

    @Override
    public SzsFileSystemProvider provider() {
        return provider;
    }

    public Path getPath() {
        return path;
    }

    @Override
    public void close() throws IOException {
        file.close();
    }

    @Override
    public boolean isOpen() {
        return file.isOpen();
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String getSeparator() {
        return "/";
    }

    public SzsPath getRootPath() {
        return rootPath;
    }

    @Override
    public Iterable<Path> getRootDirectories() {
        return Collections.singleton(rootPath);
    }

    @Override
    public Iterable<FileStore> getFileStores() {
        final List<FileStore> result = new ArrayList<>();
        final Queue<DecompressedSzsFile.TreeNode> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            final DecompressedSzsFile.TreeNode node = queue.remove();
            result.add(new SzsFileStore(node, toPath(node)));
            if (node instanceof DecompressedSzsFile.DirectoryNode dir) {
                queue.addAll(dir.getChildren().values());
            }
        }
        return result;
    }

    @Override
    public Set<String> supportedFileAttributeViews() {
        return Collections.singleton("basic");
    }

    @Override
    public Path getPath(String first, String... more) {
        if (more.length == 0) {
            return new SzsPath(this, first);
        }
        final StringBuilder result = new StringBuilder(first);
        for (final String sub : more) {
            if (sub.startsWith("/")) {
                result.setLength(0);
                result.append(sub);
            } else {
                result.append('/').append(sub);
            }
        }
        return new SzsPath(this, result.toString());
    }

    @Override
    public PathMatcher getPathMatcher(String syntaxAndInput) {
        int pos = syntaxAndInput.indexOf(':');
        if (pos <= 0) {
            throw new IllegalArgumentException();
        }
        String syntax = syntaxAndInput.substring(0, pos);
        String input = syntaxAndInput.substring(pos + 1);
        String expr;
        if (syntax.equalsIgnoreCase(GLOB_SYNTAX)) {
            expr = GlobUtils.toRegexPattern(input);
        } else {
            if (syntax.equalsIgnoreCase(REGEX_SYNTAX)) {
                expr = input;
            } else {
                throw new UnsupportedOperationException("Syntax '" + syntax + "' not recognized");
            }
        }
        final Pattern pattern = Pattern.compile(expr);
        return (path) -> pattern.matcher(path.toString()).matches();
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        throw new UnsupportedOperationException("getUserPrincipalLookupService");
    }

    @Override
    public WatchService newWatchService() {
        throw new UnsupportedOperationException("newWatchService");
    }

    private DecompressedSzsFile.TreeNode getNode(SzsPath path) {
        if (root.isRoot()) {
            final String pathStr = path.toString();
            try {
                return file.getNode(path.isAbsolute() ? pathStr.substring(1) : pathStr);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        final String[] parts = path.normalize().getParts();
        if (parts.length == 0 || (parts.length == 1 && parts[0].isEmpty())) {
            return root;
        }
        try {
            return root.resolveParts(parts);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private DecompressedSzsFile.FileNode getFileForReading(
        SzsPath path, Set<? extends OpenOption> options
    ) throws NoSuchFileException {
        for (final OpenOption option : options) {
            if (option == null) {
                throw new NullPointerException("option == null");
            }
            if (!(option instanceof StandardOpenOption)) {
                throw new UnsupportedOperationException("option not an instance of StandardOpenOption");
            }
        }
        if (!options.isEmpty() && !options.contains(StandardOpenOption.READ)) {
            throw new UnsupportedOperationException("SzsFileSystem is read only!");
        }
        final DecompressedSzsFile.TreeNode node = getNode(path);
        if (node == null) {
            throw new NoSuchFileException(path.toString(), null, "not found");
        }
        if (node instanceof DecompressedSzsFile.DirectoryNode) {
            throw new NoSuchFileException(path.toString(), null, "is a directory");
        }
        if (!(node instanceof DecompressedSzsFile.FileNode fileNode)) {
            throw new NoSuchFileException(path.toString(), null, "not a regular file");
        }
        return fileNode;
    }

    private SzsPath toPath(DecompressedSzsFile.TreeNode node) {
        if (node == root) {
            return rootPath;
        }
        final DecompressedSzsFile.DirectoryNode[] parents = node.getParents();
        final List<String> result = new ArrayList<>();
        for (int i = toPathChop; i < parents.length; i++) {
            result.add(parents[i].getName());
        }
        result.add(node.getName());
        return new SzsPath(this, result.toArray(String[]::new), true);
    }

    public SeekableByteChannel newByteChannel(
        SzsPath path, Set<? extends OpenOption> options, FileAttribute<?>... attrs
    ) throws NoSuchFileException {
        return getFileForReading(path, options).openChannel();
    }

    public DirectoryStream<Path> newDirectoryStream(
        SzsPath dir, DirectoryStream.Filter<? super Path> filter
    ) throws NotDirectoryException {
        final DecompressedSzsFile.TreeNode node = getNode(dir);
        if (!(node instanceof DecompressedSzsFile.DirectoryNode dirNode)) {
            throw new NotDirectoryException(dir.toString());
        }
        return new DirectoryStream<>() {
            boolean created, closed;

            @Override
            public Iterator<Path> iterator() {
                checkClosed();
                if (created) {
                    throw new IllegalStateException("already called");
                }
                created = true;
                return new Iterator<>() {
                    final Iterator<? extends DecompressedSzsFile.TreeNode> iter = dirNode.getChildren().values().iterator();

                    @Override
                    public boolean hasNext() {
                        checkClosed();
                        return iter.hasNext();
                    }

                    @Override
                    public Path next() {
                        checkClosed();
                        return toPath(iter.next());
                    }
                };
            }

            private void checkClosed() {
                if (closed) {
                    throw new IllegalStateException("closed");
                }
            }

            @Override
            public void close() {
                closed = true;
            }
        };
    }

    public SzsFileStore getFileStore(SzsPath path) throws NoSuchFileException {
        final DecompressedSzsFile.TreeNode node = getNode(path);
        if (node == null) {
            throw new NoSuchFileException(path.toString(), null, "not found");
        }
        return new SzsFileStore(node, toPath(node));
    }

    public boolean exists(SzsPath path) {
        return getNode(path) != null;
    }

    public BasicFileAttributes readAttributes(SzsPath path) throws NoSuchFileException {
        final DecompressedSzsFile.TreeNode node = getNode(path);
        if (node == null) {
            throw new NoSuchFileException(path.toString(), null, "not found");
        }
        return node.getAttributes();
    }
}
