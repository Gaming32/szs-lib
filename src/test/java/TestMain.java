import io.github.gaming32.szslib.nio.SzsPath;
import io.github.gaming32.szslib.u8.U8File;
import io.github.gaming32.szslib.yaz0.Yaz0InputStream;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;

public class TestMain {
    public static void main(String[] args) throws Exception {
        try (FileSystem fs = FileSystems.newFileSystem(
            new URI("szs:" + TestMain.class.getResource("/Common.szs")), Collections.emptyMap()
        )) {
            Files.walkFileTree(fs.getPath("/"), new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    System.out.println(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    System.out.println("PRE: " + dir);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    System.out.println("POST: " + dir);
                    return FileVisitResult.CONTINUE;
                }
            });
            System.out.println();

            final Path blight = fs.getPath("./lightset/.//../lightset//default.blight/");
            System.out.println(blight.normalize());
            System.out.println(Files.readAttributes(blight, BasicFileAttributes.class));
            try (InputStream is2 = Files.newInputStream(blight)) {
                final MessageDigest digest = MessageDigest.getInstance("SHA-1");
                final byte[] buf = new byte[8192];
                int n;
                while ((n = is2.read(buf)) != -1) {
                    digest.update(buf, 0, n);
                }
                System.out.println(HexFormat.of().formatHex(digest.digest()));
            }
        }
    }

    public static void mainOld(String[] args) throws Exception {
        //noinspection DataFlowIssue
        try (Yaz0InputStream is = new Yaz0InputStream(TestMain.class.getResourceAsStream("/Common.szs"))) {
            try (U8File file = U8File.fromInputStream(is)) {
                file.walk(new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(U8File.TreeNode file, BasicFileAttributes attrs) {
                        System.out.println(file);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult preVisitDirectory(U8File.TreeNode dir, BasicFileAttributes attrs) {
                        System.out.println("PRE: " + dir);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult postVisitDirectory(U8File.TreeNode dir, IOException exc) {
                        System.out.println("POST: " + dir);
                        return FileVisitResult.CONTINUE;
                    }
                });
                System.out.println();

                final U8File.FileNode blight = (U8File.FileNode)file.getNode("./lightset/default.blight");
                System.out.println(blight.getAttributes());
                try (InputStream is2 = blight.openInputStream()) {
                    final MessageDigest digest = MessageDigest.getInstance("SHA-1");
                    final byte[] buf = new byte[8192];
                    int n;
                    while ((n = is2.read(buf)) != -1) {
                        digest.update(buf, 0, n);
                    }
                    System.out.println(HexFormat.of().formatHex(digest.digest()));
                }
            }
        }
    }
}
