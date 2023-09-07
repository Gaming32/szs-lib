package io.github.gaming32.szslib.decompressed;

import java.io.Closeable;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.*;

public interface DecompressedSzsFile extends Closeable {
    DirectoryNode getRoot();

    boolean isOpen();

    default TreeNode getNode(String path) {
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        return getRoot().resolve(path);
    }

    interface TreeNode {
        FileTime NO_TIME = FileTime.fromMillis(0L);
        DirectoryNode[] NO_PARENTS = new DirectoryNode[0];

        String getName();

        DirectoryNode getParent();

        default boolean isRoot() {
            return this == getParent();
        }

        default DirectoryNode[] getParents() {
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

        default String getFullPath() {
            if (isRoot()) {
                return getName();
            }
            final StringBuilder result = new StringBuilder();
            for (final DirectoryNode parent : getParents()) {
                result.append(parent.getName()).append('/');
            }
            return result.append(getName()).toString();
        }

        default BasicFileAttributes getAttributes() {
            return new BasicFileAttributes() {
                @Override
                public FileTime lastModifiedTime() {
                    return NO_TIME;
                }

                @Override
                public FileTime lastAccessTime() {
                    return NO_TIME;
                }

                @Override
                public FileTime creationTime() {
                    return NO_TIME;
                }

                @Override
                public boolean isRegularFile() {
                    return TreeNode.this instanceof FileNode;
                }

                @Override
                public boolean isDirectory() {
                    return TreeNode.this instanceof DirectoryNode;
                }

                @Override
                public boolean isSymbolicLink() {
                    return false;
                }

                @Override
                public boolean isOther() {
                    return !isRegularFile() && !isDirectory();
                }

                @Override
                public long size() {
                    return TreeNode.this instanceof FileNode file ? file.getSize() : 0L;
                }

                @Override
                public Object fileKey() {
                    return null;
                }

                @Override
                public String toString() {
                    final StringBuilder sb = new StringBuilder(1024);
                    try (Formatter fm = new Formatter(sb)) {
                        fm.format("    creationTime    : %tc%n", creationTime().toMillis());
                        fm.format("    lastAccessTime  : %tc%n", lastAccessTime().toMillis());
                        fm.format("    lastModifiedTime: %tc%n", lastModifiedTime().toMillis());
                        fm.format("    isRegularFile   : %b%n", isRegularFile());
                        fm.format("    isDirectory     : %b%n", isDirectory());
                        fm.format("    isSymbolicLink  : %b%n", isSymbolicLink());
                        fm.format("    isOther         : %b%n", isOther());
                        fm.format("    fileKey         : %s%n", fileKey());
                        fm.format("    size            : %d%n", size());
                    }
                    return sb.toString();
                }
            };
        }

        @Override
        String toString();
    }

    interface DirectoryNode extends TreeNode {
        Map<String, ? extends TreeNode> getChildren();

        TreeNode getChild(String name);

        default TreeNode resolve(String path) {
            TreeNode current = this;
            final String[] parts = path.split("/");
            for (final String part : parts) {
                if (!(current instanceof DirectoryNode dir)) {
                    if (current != null) {
                        throw new IllegalArgumentException(current.getFullPath() + " isn't a directory!");
                    } else {
                        return null;
                    }
                }
                current = dir.getChild(part);
            }
            return current;
        }
    }

    interface FileNode extends TreeNode {
        int getSize();

        SeekableByteChannel openChannel();

        default InputStream openInputStream() {
            return Channels.newInputStream(openChannel());
        }
    }
}
