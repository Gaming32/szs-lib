package io.github.gaming32.szslib.sarc;

import io.github.gaming32.szslib.SzsDetector;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

// https://mk8.tockdom.com/wiki/SARC_(File_Format)
public class SARCFile {
    private static final int SFAT_MAGIC = 0x53464154;
    private static final int SFNT_MAGIC = 0x53464E54;

    private final Map<String, SARCNode> nodes;
    private final byte[] fileData;

    private SARCFile(InputStream is) throws IOException {
        if (readInt(is, true) != SzsDetector.SARC_MAGIC) {
            throw new IOException("Invalid SARC magic");
        }
        is.skipNBytes(2);
        final int bom = readShort(is, true);
        final boolean bigEndian = switch (bom) {
            case 0xFEFF -> true;
            case 0xFFFE -> false;
            default -> throw new IOException("Invalid SARC BOM: 0x" + Integer.toHexString(bom));
        };
        final int fileSize = readInt(is, bigEndian);
        final int beginningOfData = readInt(is, bigEndian);
        final int version = readShort(is, bigEndian);
        if (version != 0x100) {
            throw new IOException("Unknown SARC version: " + version);
        }
        is.skipNBytes(2);

        if (readInt(is, true) != SFAT_MAGIC) {
            throw new IOException("Invalid SFAT magic");
        }
        is.skipNBytes(2);
        final int nodeCount = readShort(is, bigEndian);
        final int hashKey = readInt(is, bigEndian);

        record PartialNode(int hash, int nameOffset, int beginData, int endData) {
        }
        final PartialNode[] partialNodes = new PartialNode[nodeCount];
        int maxEndData = 0;
        for (int i = 0; i < nodeCount; i++) {
            final int hash = readInt(is, bigEndian);
            final int attrs = readInt(is, bigEndian);
            if ((attrs & 0x01000000) == 0) {
                // Unknown filename. Skip.
                is.skipNBytes(8);
                continue;
            }
            final int beginData = readInt(is, bigEndian);
            final int endData = readInt(is, bigEndian);
            partialNodes[i] = new PartialNode(hash, (attrs & 0xFFFF) << 2, beginData, endData);
            maxEndData = Math.max(maxEndData, endData);
        }

        if (readInt(is, true) != SFNT_MAGIC) {
            throw new IOException("Invalid SFNT magic");
        }
        is.skipNBytes(4);

        nodes = new LinkedHashMap<>(nodeCount);
        final byte[] nameTable = is.readNBytes(beginningOfData - 40 - 16 * nodeCount);
        final int[] nameEndOffset = {0};
        for (final PartialNode node : partialNodes) {
            if (node == null) continue;
            final int readHash = hashFileName(nameTable, node.nameOffset, hashKey, nameEndOffset);
            final String filename = new String(
                nameTable,
                node.nameOffset,
                nameEndOffset[0] - node.nameOffset,
                StandardCharsets.ISO_8859_1
            );
            if (readHash != node.hash) {
                throw new IOException("Hash of filename " + filename + " does not match header: " + readHash + " != " + node.hash);
            }
            nodes.put(filename, new SARCNode(node.beginData, node.endData));
        }

        fileData = is.readNBytes(maxEndData);
        if (fileData.length < maxEndData) {
            throw new IOException("Input too short to fill " + maxEndData + " bytes of file data");
        }

        is.skipNBytes(fileSize - 40 - 16L * nodeCount - maxEndData - beginningOfData);
    }

    public static SARCFile open(InputStream is) throws IOException {
        return new SARCFile(new DataInputStream(is));
    }

    private static int readInt(InputStream is, boolean bigEndian) throws IOException {
        final int value = is.read() | (is.read() << 8) | (is.read() << 16) | (is.read() << 24);
        return bigEndian ? Integer.reverseBytes(value) : value;
    }

    private static int readShort(InputStream is, boolean bigEndian) throws IOException {
        final int value = is.read() | (is.read() << 8);
        return bigEndian ? Short.reverseBytes((short)value) & 0xffff : value;
    }

    private static int hashFileName(byte[] nameTable, int start, int key, int[] outOffset) {
        int result = 0;
        int i;
        for (i = start; i < nameTable.length; i++) {
            final int b = nameTable[i] & 0xff;
            if (b == 0) break;
            result = result * key + b;
        }
        outOffset[0] = i;
        return result;
    }

    @Nullable
    public InputStream getInputStream(String filename) {
        final SARCNode node = nodes.get(filename);
        if (node == null) {
            return null;
        }
        return new ByteArrayInputStream(fileData, node.beginData, node.endData - node.beginData);
    }

    public Set<String> listFiles() {
        return nodes.keySet();
    }

    private record SARCNode(int beginData, int endData) {
    }
}
