package io.github.gaming32.szslib.u8;

import io.github.gaming32.szslib.SzsDetector;
import io.github.gaming32.szslib.decompressed.DecompressedSzsFile;
import org.apache.commons.compress.utils.IOUtils;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.*;

/**
 * @apiNote Thread-safety: a {@link U8File} instance is thread-safe, but any archived streams opened for reading using
 * {@link FileNode#openChannel} or {@link FileNode#openInputStream} are not.
 */
public class U8File implements DecompressedSzsFile {
    private SeekableByteChannel channel;
    private TreeNode[] allNodes;
    private DirectoryNode rootNode;

    private WeakReference<Object> channelOwner = new WeakReference<>(null);

    private U8File(SeekableByteChannel channel) throws IOException {
        this.channel = channel;

        try {
            final ByteBuffer header = ByteBuffer.allocate(16);
            channel.position(0);
            IOUtils.readFully(channel, header);
            header.rewind();
            if (header.getInt() != SzsDetector.U8_MAGIC) {
                throw new IOException("Invalid U8 magic");
            }
            final int firstNodeOffset = header.getInt();
            final int treeLen = header.getInt();
            final int dataOffset = header.getInt();

            final ByteBuffer treeBuf = ByteBuffer.allocate(treeLen);
            channel.position(firstNodeOffset);
            IOUtils.readFully(channel, treeBuf);
            treeBuf.position(8);
            final int lastNodeEnd = treeBuf.getInt();
            treeBuf.rewind();

            allNodes = new TreeNode[lastNodeEnd + 1];
            final TreeNode root = readTree(treeBuf, lastNodeEnd * 12, 0);
            if (!(root instanceof DirectoryNode dir)) {
                throw new IOException("Root node not a DirectoryNode!");
            }
            rootNode = dir;
        } catch (IOException e) {
            channel.close();
            throw e;
        }
    }

    private TreeNode readTree(ByteBuffer buffer, int stringPoolOffset, int index) throws IOException {
        final TreeNode node = allNodes[index] = buffer.get() == 0
            ? new FileNode(buffer, index, stringPoolOffset)
            : new DirectoryNode(buffer, index, stringPoolOffset);
        if (node instanceof DirectoryNode directory) {
            int nextIndex = index + 1;
            while (nextIndex < directory.siblingNodeIndex) {
                final TreeNode child = readTree(buffer, stringPoolOffset, nextIndex);
                if (child instanceof DirectoryNode subdir) {
                    nextIndex = subdir.siblingNodeIndex;
                } else if (child instanceof FileNode file) {
                    nextIndex++;
                    file.setParent(directory);
                }
                directory.children.put(child.getName(), child);
            }
        }
        return node;
    }

    public static U8File fromChannel(SeekableByteChannel channel) throws IOException {
        return new U8File(channel);
    }

    public static U8File fromByteArray(byte[] bytes) throws IOException {
        return new U8File(new SeekableInMemoryByteChannel(bytes));
    }

    public static U8File fromInputStream(InputStream is) throws IOException {
        return fromByteArray(is.readAllBytes());
    }

    public static U8File fromPath(Path path) throws IOException {
        return new U8File(Files.newByteChannel(path, StandardOpenOption.READ));
    }

    public static U8File fromFile(File file) throws IOException {
        return fromPath(file.toPath());
    }

    @Override
    public DirectoryNode getRoot() {
        return rootNode;
    }

    public void walk(FileVisitor<TreeNode> visitor) throws IOException {
        rootNode.walk(visitor);
    }

    @Override
    public TreeNode getNode(String path) {
        if (rootNode == null) {
            throw new IllegalStateException("U8File closed");
        }
        return (TreeNode)DecompressedSzsFile.super.getNode(path);
    }

    @Override
    public void close() throws IOException {
        try {
            synchronized (U8File.this) {
                channel.close();
            }
        } finally {
            rootNode = null;
            allNodes = null;
            channel = null;
            channelOwner.clear();
        }
    }

    @Override
    public boolean isOpen() {
        return channel != null;
    }

    private static String readString(ByteBuffer buffer) {
        if (!buffer.hasArray()) {
            final StringBuilder result = new StringBuilder();
            while (true) {
                final byte b = buffer.get();
                if (b == 0) break;
                if (b < 0) {
                    throw new IllegalArgumentException("Cannot decode 0x" + Integer.toHexString(b & 0xff) + " with ASCII");
                }
                result.append((char)b);
            }
            return result.toString();
        }
        final int start = buffer.position();
        //noinspection StatementWithEmptyBody
        while (buffer.get() != 0) {
            // Just advances the buffer
        }
        return new String(
            buffer.array(),
            buffer.arrayOffset() + start,
            buffer.position() - start - 1,
            StandardCharsets.US_ASCII
        );
    }

    public abstract sealed class TreeNode implements DecompressedSzsFile.TreeNode permits DirectoryNode, FileNode {
        private static final DirectoryNode[] NO_PARENTS = new DirectoryNode[0];

        private final int nodeIndex;
        private final String name;

        protected TreeNode(ByteBuffer buffer, int nodeIndex, int stringPoolOffset) {
            this.nodeIndex = nodeIndex;
            final int nameOffset = ((buffer.getShort() & 0xffff) << 8) | (buffer.get() & 0xff);
            buffer.mark();
            buffer.position(stringPoolOffset + nameOffset);
            name = readString(buffer);
            buffer.reset();
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public abstract DirectoryNode getParent();

        public abstract FileVisitResult walk(FileVisitor<TreeNode> visitor) throws IOException;

        @Override
        public DirectoryNode[] getParents() {
            DirectoryNode last = getParent();
            if (this == last) {
                return NO_PARENTS;
            }
            final List<DirectoryNode> parents = new ArrayList<>();
            while (last != last.getParent()) {
                parents.add(last);
                last = last.getParent();
            }
            parents.add(last);
            Collections.reverse(parents);
            return parents.toArray(DirectoryNode[]::new);
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + "[" + getFullPath() + "]";
        }
    }

    public final class DirectoryNode extends TreeNode implements DecompressedSzsFile.DirectoryNode {
        private final int parentNodeIndex;
        private final int siblingNodeIndex;
        private Map<String, TreeNode> children = new LinkedHashMap<>();

        private DirectoryNode(ByteBuffer buffer, int nodeIndex, int stringPoolOffset) throws IOException {
            super(buffer, nodeIndex, stringPoolOffset);
            parentNodeIndex = buffer.getInt();
            if (parentNodeIndex < 0 || parentNodeIndex >= allNodes.length) {
                throw new IOException("Invalid parentNodeIndex (out of bounds): " + parentNodeIndex);
            }
            if (!(allNodes[parentNodeIndex] instanceof DirectoryNode) && parentNodeIndex != nodeIndex) {
                throw new IOException("Invalid parentNodeIndex: parent node isn't a directory");
            }
            siblingNodeIndex = buffer.getInt();
            if (siblingNodeIndex < 0 || siblingNodeIndex > allNodes.length) {
                throw new IOException("Invalid siblingNodeIndex (out of bounds): " + siblingNodeIndex);
            }
        }

        // This will return itself if it's the root
        @Override
        public DirectoryNode getParent() {
            return (DirectoryNode)allNodes[parentNodeIndex];
        }

        @Override
        public FileVisitResult walk(FileVisitor<TreeNode> visitor) throws IOException {
            final FileVisitResult first = visitor.preVisitDirectory(this, getAttributes());
            if (first == FileVisitResult.TERMINATE || first == FileVisitResult.SKIP_SIBLINGS) {
                return first;
            }
            if (first == FileVisitResult.SKIP_SUBTREE) {
                return FileVisitResult.CONTINUE;
            }

            for (final TreeNode child : children.values()) {
                final FileVisitResult sub = child.walk(visitor);
                if (sub == FileVisitResult.TERMINATE) {
                    return sub;
                }
                if (sub == FileVisitResult.SKIP_SIBLINGS) break;
            }

            final FileVisitResult last = visitor.postVisitDirectory(this, null);
            if (last == FileVisitResult.SKIP_SIBLINGS) {
                return FileVisitResult.CONTINUE;
            }
            return last;
        }

        @Override
        public Map<String, TreeNode> getChildren() {
            return Collections.unmodifiableMap(children);
        }

        @Override
        public TreeNode getChild(String name) {
            return children.get(name);
        }

        @Override
        public TreeNode resolve(String path) {
            return (TreeNode)DecompressedSzsFile.DirectoryNode.super.resolve(path);
        }
    }

    public final class FileNode extends TreeNode implements DecompressedSzsFile.FileNode {
        private final int dataOffset;
        private final int size;
        private DirectoryNode parent;

        private FileNode(ByteBuffer buffer, int nodeIndex, int stringPoolOffset) {
            super(buffer, nodeIndex, stringPoolOffset);
            dataOffset = buffer.getInt();
            size = buffer.getInt();
        }

        @Override
        public DirectoryNode getParent() {
            return parent;
        }

        @Override
        public FileVisitResult walk(FileVisitor<TreeNode> visitor) throws IOException {
            final FileVisitResult result = visitor.visitFile(this, getAttributes());
            return result == FileVisitResult.SKIP_SUBTREE ? FileVisitResult.CONTINUE : result;
        }

        private void setParent(DirectoryNode parent) {
            this.parent = parent;
        }

        @Override
        public int getSize() {
            return size;
        }

        @Override
        public SeekableByteChannel openChannel() {
            return new SeekableByteChannel() {
                private boolean closed;
                private long position;

                private void checkClosed() throws ClosedChannelException {
                    if (!isOpen()) {
                        throw new ClosedChannelException();
                    }
                }

                private void refresh() throws IOException {
                    if (channelOwner.refersTo(this)) return;
                    channelOwner = new WeakReference<>(this);
                    channel.position(dataOffset + position);
                }

                @Override
                public int read(ByteBuffer dst) throws IOException {
                    checkClosed();
                    if (position >= size) {
                        return -1;
                    }
                    int limit = -1;
                    if (dst.remaining() > remaining()) {
                        limit = dst.limit();
                        dst.limit(dst.position() + remaining());
                    }
                    int read;
                    try {
                        synchronized (U8File.this) {
                            refresh();
                            read = channel.read(dst);
                        }
                        position += read;
                    } finally {
                        if (limit != -1) {
                            dst.limit(limit);
                        }
                    }
                    return read;
                }

                @Override
                public int write(ByteBuffer src) throws ClosedChannelException {
                    checkClosed();
                    throw new UnsupportedOperationException("write");
                }

                @Override
                public long position() throws ClosedChannelException {
                    checkClosed();
                    return position;
                }

                @Override
                public SeekableByteChannel position(long newPosition) throws ClosedChannelException {
                    checkClosed();
                    if (newPosition < 0) {
                        throw new IllegalArgumentException("newPosition < 0");
                    }
                    position = newPosition;
                    return this;
                }

                @Override
                public long size() throws ClosedChannelException {
                    checkClosed();
                    return size;
                }

                private int remaining() {
                    if (position >= size) {
                        return 0;
                    }
                    return size - (int)position;
                }

                @Override
                public SeekableByteChannel truncate(long size) throws ClosedChannelException {
                    if (size < 0) {
                        throw new IllegalArgumentException("size < 0");
                    }
                    checkClosed();
                    throw new UnsupportedOperationException("truncate");
                }

                @Override
                public boolean isOpen() {
                    if (closed) {
                        return false;
                    }
                    synchronized (U8File.this) {
                        if (channel == null) {
                            closed = true;
                        }
                    }
                    return !closed;
                }

                @Override
                public void close() {
                    final boolean wasClosed = closed;
                    closed = true;
                    if (!wasClosed) {
                        synchronized (U8File.this) {
                            channelOwner.clear();
                        }
                    }
                }
            };
        }
    }
}
