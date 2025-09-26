package Channel;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class Utils {
    // read file to byte[]
    public static byte[] readFile(String path) throws IOException {
        return Files.readAllBytes(Paths.get(path));
    }
    // write byte[] to file append
    public static void appendToFile(String path, byte[] data) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(path, true)) {
            fos.write(data);
        }
    }
    // simple CRC16-CCITT (optional)
    public static int crc16(byte[] buf, int off, int len) {
        int crc = 0xFFFF;
        for (int i = off; i < off + len; i++) {
            crc ^= (buf[i] & 0xFF) << 8;
            for (int j = 0; j < 8; j++) {
                if ((crc & 0x8000) != 0) crc = (crc << 1) ^ 0x1021;
                else crc <<= 1;
                crc &= 0xFFFF;
            }
        }
        return crc & 0xFFFF;
    }
    public static void log(String s) {
        System.out.println("[" + Thread.currentThread().getName() + "] " + s);
    }
}
