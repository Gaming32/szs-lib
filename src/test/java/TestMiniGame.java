import io.github.gaming32.szslib.sarc.SARCFile;
import io.github.gaming32.szslib.yaz0.Yaz0InputStream;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

public class TestMiniGame {
    public static void main(String[] args) throws IOException {
        final SARCFile file;
        try (InputStream is = new Yaz0InputStream(
            new FileInputStream("C:\\Users\\josia\\AppData\\Roaming\\yuzu\\dump\\0100F8F0000A2000\\romfs\\Etc\\MiniGame.szs")
        )) {
            file = SARCFile.read(is);
            if (is.read() != -1) {
                throw new IllegalStateException();
            }
        }
        System.out.println(file.listFiles());
    }
}
