import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;

public class CreateDataFile {
    private static final String FILENAME = "data_source.txt";

    private static final int TARGET_SIZE_BYTES = 30 * 1024 * 1024;

    // Đoạn văn bản mẫu
    private static final String CONTENT =
            "Đây là mô hình truyền dữ liệu RUDP (Reliable UDP) đơn giản, " +
                    "được thiết kế để mô phỏng cơ chế độ tin cậy của TCP trên nền UDP. " +
                    "Các tính năng bao gồm: đánh số thứ tự gói tin, cơ chế ACK tích lũy, " +
                    "Hỗ trợ cửa sổ trượt (Go-Back-N/Selective Repeat Hint), và tái truyền " +
                    "khi hết thời gian chờ (RTO) hoặc gặp ACK trùng lặp (Fast Retransmit). " +
                    "Mục đích của file này là cung cấp đủ lượng dữ liệu lớn để kiểm tra " +
                    "hiệu quả của giao thức trên một kênh truyền bị mất mát (lossy channel). " +
                    "Bài tập nhóm 3 Lập Trình Mạng N03 D22CDPM03, 2025.\n";

    public static void main(String[] args) {
        long currentSize = 0;
        String absolutePath = Paths.get("src", FILENAME).toAbsolutePath().toString();

        try (PrintWriter writer = new PrintWriter(new FileWriter(absolutePath, StandardCharsets.UTF_8))) {
            System.out.println("Bắt đầu tạo file: " + FILENAME + " với kích thước mục tiêu " + (TARGET_SIZE_BYTES / (1024 * 1024)) + " MB...");

            while (currentSize < TARGET_SIZE_BYTES) {
                writer.print(CONTENT); // Dùng print thay vì println nếu CONTENT đã có '\n'
                currentSize += CONTENT.getBytes(StandardCharsets.UTF_8).length;
            }

            System.out.println("✅ Đã tạo file thành công.");
            System.out.println("Tên file: " + FILENAME);
            System.out.println("Kích thước cuối ước tính: " + (currentSize / (1024 * 1024.0)) + " MB");
            System.out.println("File được tạo tại: " + absolutePath); // Báo vị trí

        } catch (IOException e) {
            System.err.println("Lỗi khi tạo file: Kiểm tra quyền ghi hoặc đường dẫn.");
            e.printStackTrace();
        }
    }
}