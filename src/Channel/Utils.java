package Channel;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class Utils {
    private static final String BASE_PATH = "src/";

    // read file to byte[]
    // read file to byte[]
    public static byte[] readFile(String path) throws IOException {
        return Files.readAllBytes(Paths.get(BASE_PATH + path));
    }

    // write byte[] to file append
    public static void appendToFile(String path, byte[] data) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(BASE_PATH + path, true)) {
            fos.write(data);
        }
    }

    // truncate / clear file
    public static void clearFile(String path) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(path, false)) {
            // open & close => truncates
        }
    }

    public static int udpChecksum16(byte[] data, int off, int len) {
        long sum = 0;
        int i = off;
        while (len > 1) {
            int word = ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
            sum += word;
            // carry-around
            if ((sum & 0xFFFF0000L) != 0) {
                sum = (sum & 0xFFFFL) + (sum >> 16);
            }
            i += 2;
            len -= 2;
        }
        if (len > 0) {
            int word = (data[i] & 0xFF) << 8;
            sum += word;
            if ((sum & 0xFFFF0000L) != 0) {
                sum = (sum & 0xFFFFL) + (sum >> 16);
            }
        }
        int ch = (int) (~sum & 0xFFFFL);
        return ch;
    }
    public static void log(String s) {
        System.out.println("[" + Thread.currentThread().getName() + "] " + s);
    }
}
