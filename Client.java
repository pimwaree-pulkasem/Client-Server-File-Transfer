import java.io.FileOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.SocketChannel;

public class Client {
    public static void main(String[] args) throws IOException {
        // !!! แก้ไข IP Address ตรงนี้ให้เป็นของเครื่อง Server !!!
        String serverHost = "192.168.1.10"; 
        int serverPort = 9999;
        String destinationFile = "received_file.dat"; // ไฟล์ที่จะบันทึก

        // 1. เปิด SocketChannel และเชื่อมต่อไปยัง Server
        SocketChannel socketChannel = SocketChannel.open();
        socketChannel.connect(new InetSocketAddress(serverHost, serverPort));
        System.out.println("Connected to server: " + serverHost);

        // 2. เตรียมไฟล์สำหรับรับข้อมูล
        try (FileOutputStream fos = new FileOutputStream(destinationFile);
             FileChannel fileChannel = fos.getChannel()) {

            long startTime = System.nanoTime();
            
            // --- หัวใจของ Zero-copy (ฝั่งรับ) ---
            // 3. สั่งให้ Kernel รับข้อมูลจาก socketChannel มาเขียนลง fileChannel โดยตรง
            // เราส่ง Long.MAX_VALUE เพราะเราจะรับจนกว่า Server จะปิดการเชื่อมต่อ
            long bytesTransferred = fileChannel.transferFrom(socketChannel, 0, Long.MAX_VALUE);
            // ------------------------------------

            long endTime = System.nanoTime();
            long durationMs = (endTime - startTime) / 1_000_000;

            System.out.println("File reception complete.");
            System.out.println("Received " + bytesTransferred + " bytes in " + durationMs + " ms.");
        
        } catch (IOException e) {
            System.err.println("Error during reception: " + e.getMessage());
        } finally {
            // 4. ปิดการเชื่อมต่อ
            socketChannel.close();
        }
    }
}
