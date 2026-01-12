import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;

public class Server {
    public static void main(String[] args) throws IOException {
        int port = 9999;
        String filePath = "largefile.dat"; // ไฟล์ที่จะส่ง

        // สร้างไฟล์ทดสอบขนาดใหญ่ (ถ้ายังไม่มี)
        File file = new File(filePath);
        if (!file.exists()) {
            System.out.println("Test file not found. Please create it first.");
            System.out.println("Example: dd if=/dev/urandom of=largefile.dat bs=1M count=1024");
            return;
        }

        // 1. เปิด ServerSocketChannel เพื่อรอรับการเชื่อมต่อ
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.socket().bind(new InetSocketAddress(port));
        System.out.println("Server started on port " + port + ", waiting for connection...");

        while (true) {
            // 2. ยอมรับการเชื่อมต่อจาก Client
            SocketChannel clientChannel = serverChannel.accept();
            System.out.println("Client connected: " + clientChannel.getRemoteAddress());

            // 3. เริ่มกระบวนการ Zero-copy
            try (FileInputStream fis = new FileInputStream(file);
                 FileChannel fileChannel = fis.getChannel()) {

                long fileSize = fileChannel.size();
                long startTime = System.nanoTime();

                // --- หัวใจของ Zero-copy ---
                // 4. สั่งให้ Kernel ส่งข้อมูลจาก fileChannel ไปยัง clientChannel โดยตรง
                long bytesTransferred = fileChannel.transferTo(0, fileSize, clientChannel);
                // -------------------------

                long endTime = System.nanoTime();
                long durationMs = (endTime - startTime) / 1_000_000;

                System.out.println("File transfer complete.");
                System.out.println("Sent " + bytesTransferred + " bytes in " + durationMs + " ms.");

            } catch (IOException e) {
                System.err.println("Error during transfer: " + e.getMessage());
            } finally {
                // 5. ปิดการเชื่อมต่อกับ Client นี้
                clientChannel.close();
                System.out.println("Client disconnected.");
            }
        }
    }
}
