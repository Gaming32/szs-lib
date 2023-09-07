package io.github.gaming32.szslib.nio;

import io.github.gaming32.szslib.decompressed.DecompressedSzsFile;

import java.nio.file.FileStore;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileStoreAttributeView;

public class SzsFileStore extends FileStore {
    private final DecompressedSzsFile.TreeNode node;
    private final SzsPath realPath;

    SzsFileStore(DecompressedSzsFile.TreeNode node, SzsPath realPath) {
        this.node = node;
        this.realPath = realPath;
    }

    @Override
    public String name() {
        return realPath.toString();
    }

    @Override
    public String type() {
        return "szs";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public long getTotalSpace() {
        return node instanceof DecompressedSzsFile.FileNode file ? file.getSize() : 0;
    }

    @Override
    public long getUsableSpace() {
        return 0;
    }

    @Override
    public long getUnallocatedSpace() {
        return 0;
    }

    @Override
    public boolean supportsFileAttributeView(Class<? extends FileAttributeView> type) {
        return type == BasicFileAttributeView.class;
    }

    @Override
    public boolean supportsFileAttributeView(String name) {
        return "basic".equals(name);
    }

    @Override
    public <V extends FileStoreAttributeView> V getFileStoreAttributeView(Class<V> type) {
        return null;
    }

    @Override
    public Object getAttribute(String attribute) {
        return null;
    }
}
