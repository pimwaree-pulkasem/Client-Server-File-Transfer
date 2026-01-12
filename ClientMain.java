import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;

public class ClientMain {
    private static final String SERVER_IP = "192.168.56.101";
    private static final int SERVER_PORT = 8000;
    private static final String DOWNLOAD_DIR = "downloads";

    public static void main(String[] args) {
        BufferedReader console = new BufferedReader(new InputStreamReader(System.in));
        System.out.println("=== NIO File Transfer Client ===");
        System.out.println("Type 'help' for commands.\n");
        
        while (true) {
            try {
                // อ่านคำสั่งจากผู้ใช้ (help/ list / download / exit)
                System.out.print("> ");
                String line = console.readLine();
                if (line == null) continue;
                line = line.trim();

                // ออกจากโปรแกรม
                if (line.equalsIgnoreCase("exit")) {
                    System.out.println("Exiting...");
                    break;
                // ขอรายการไฟล์จาก server
                } else if (line.equalsIgnoreCase("list")) {
                    listFiles();
                    
                // คำสั่ง download ต้องมีชื่อไฟล์ + mode
                } else if (line.toLowerCase().startsWith("download")) {
                    String[] p = line.split(" ");
                    if (p.length < 3) {
                        System.out.println("Usage: download <filename> <mode>");
                        System.out.println("Mode: zero or normal");
                    } else {
                        downloadFile(p[1], p[2]);
                    }
                // แสดงคำสั่งทั้งหมด    
                } else if (line.equalsIgnoreCase("help")) {
                    printHelp();
                // คำสั่งไม่รู้จัก    
                } else if (!line.isEmpty()) {
                    System.out.println("Unknown command. Type 'help' for list of commands.");
                }
                
            } catch (IOException e) {
                System.err.println("Input error: " + e.getMessage());
            }
        }
    }

    // แสดงคำสั่งที่รองรับ
    static void printHelp() {
        System.out.println("\n=== Available Commands ===");
        System.out.println("  list                   - Show list of files on server");
        System.out.println("  download <file> <mode> - Download file");
        System.out.println("                           zero: use transferFrom() method");
        System.out.println("                           normal: traditional read/write");
        System.out.println("  exit                   - Exit the client");
        System.out.println();
    }

    // ติดต่อ server → ส่งคำสั่ง LIST → รอรับชื่อไฟล์ทีละบรรทัด
    static void listFiles() {
        try (SocketChannel channel = SocketChannel.open()) {
            channel.connect(new InetSocketAddress(SERVER_IP, SERVER_PORT));
            System.out.println("[LIST] Connected to server");

            try {
                writeLine(channel, "LIST"); // ส่งคำสั่ง LIST

                System.out.println("\n=== Files on Server ===");
                String line;
                int count = 0;
                
                // อ่านชื่อไฟล์ทีละบรรทัดจนกว่าจะเจอ END
                while ((line = readLine(channel)) != null) {
                    if ("END".equals(line)) {
                        break;
                    }
                    
                    if (line.startsWith("ERROR")) {
                        System.err.println(line);
                        return;
                    }
                    
                    System.out.println("  " + (++count) + ". " + line);
                }
                
                System.out.println("=== Total: " + count + " files ===\n");
                
            } catch (IOException e) {
                System.err.println("Error during LIST command: " + e.getMessage());
            }
            
        } catch (IOException e) {
            System.err.println("Network error while listing files: " + e.getMessage());
        }
    }

    // โหลดไฟล์จาก server โดยเลือกโหมด zero หรือ normal
    static void downloadFile(String fname, String mode) {
        if (!mode.equals("zero") && !mode.equals("normal")) {
            System.err.println("Invalid mode. Use 'zero' or 'normal'");
            return;
        }
        
        try (SocketChannel channel = SocketChannel.open()) {
            channel.connect(new InetSocketAddress(SERVER_IP, SERVER_PORT));
            System.out.println("[DOWNLOAD] Connected to server");

            try {
                writeLine(channel, "DOWNLOAD " + fname + " " + mode);

                // รอรับ FILESIZE <size>
                String response = readLine(channel);
                if (response == null || !response.startsWith("FILESIZE ")) {
                    System.err.println("Invalid response from server: " + response);
                    return;
                }

                long fileSize = Long.parseLong(response.substring(9).trim());
                System.out.println("[DOWNLOAD] File size: " + formatBytes(fileSize));

                // รอ READY ก่อนเริ่มโหลด
                response = readLine(channel);
                if (!"READY".equals(response)) {
                    System.err.println("Server error: " + response);
                    return;
                }

                // สร้างโฟลเดอร์ downloads ถ้ายังไม่มี
                System.out.println("[DOWNLOAD] Server ready, starting download...");
                System.out.println("[DOWNLOAD] Client Mode: " + mode.toUpperCase());

                File outFile = new File(DOWNLOAD_DIR + "/" + fname);
                outFile.getParentFile().mkdirs();

                long startTime = System.currentTimeMillis();

                // เลือกโหมดดาวน์โหลดจากผู้ใช้
                if (mode.equals("zero")) {
                    downloadZeroCopy(channel, outFile, fileSize); // ใช้ transferFrom()
                } else {
                    downloadNormal(channel, outFile, fileSize);   // ใช้ read/write
                }

                long endTime = System.currentTimeMillis();
                long duration = endTime - startTime;
                double speed = (fileSize / 1024.0 / 1024.0) / (duration / 1000.0);

                // แสดงผลลัพธ์การดาวน์โหลด
                System.out.println("\n[DOWNLOAD] ✅ Completed: " + fname);
                System.out.println("[DOWNLOAD] Time: " + duration + " ms");
                System.out.printf("[DOWNLOAD] Speed: %.2f MB/s\n", speed);
                System.out.println("[DOWNLOAD] Saved to: " + outFile.getAbsolutePath() + "\n");

            } catch (IOException | InterruptedException e) {
                System.err.println("Error during DOWNLOAD: " + e.getMessage());
            }
            
        } catch (IOException e) {
            System.err.println("Network error: " + e.getMessage());
        }
    }

    // ZERO-COPY ฝั่ง client: ใช้ FileChannel.transferFrom() รับข้อมูลจาก network → file
    static void downloadZeroCopy(SocketChannel channel, File outFile, long fileSize) 
            throws IOException, InterruptedException {
        
        System.out.println("[CLIENT] Using ZERO-COPY mode (transferFrom)");
        
        try (FileChannel outChan = new FileOutputStream(outFile).getChannel()) {
            long bytesTransferred = 0;
            long chunk;
            int retryCount = 0;
            
            // ส่งข้อมูลทีละ chunk โดยไม่ต้องผ่าน user-space buffer
            while (bytesTransferred < fileSize) {
                chunk = outChan.transferFrom(
                    channel,                                        // อ่านจาก socket channel
                    bytesTransferred,                               // ตำแหน่งเริ่มต้นในไฟล์
                    Math.min(65536, fileSize - bytesTransferred) // ขนาดที่จะ transfer (64KB) ต่อรอบ
                );
                
                if (chunk == 0) { // ถ้ายังไม่มี data ก็รอ
                    retryCount++;
                    if (retryCount > 100) {
                        System.err.println("\nConnection timeout or closed");
                        break;
                    }
                    Thread.sleep(10);
                    continue;
                }
                
                retryCount = 0;
                bytesTransferred += chunk;
                printProgress(bytesTransferred, fileSize);
            }
            
            // ตรวจสอบว่ารับครบหรือไม่
            if (bytesTransferred != fileSize) {
                System.err.println("\n[CLIENT] ❌ Incomplete: " + 
                    bytesTransferred + "/" + fileSize + " bytes");
                outFile.delete();
                throw new IOException("Incomplete transfer");
            }
            
            System.out.println("\n[ZERO] ✅ Transfer complete using transferFrom()");
        }
    }

    // NORMAL COPY: อ่านจาก SocketChannel → ByteBuffer → FileChannel
    static void downloadNormal(SocketChannel channel, File outFile, long fileSize) 
            throws IOException, InterruptedException {
        
        System.out.println("[CLIENT] Using NORMAL mode (read/write)");
        
        try (FileChannel outChan = new FileOutputStream(outFile).getChannel()) {
            ByteBuffer buffer = ByteBuffer.allocate(8192); // 8KB buffer
            long bytesTransferred = 0;
            int retryCount = 0;
            
            // ลูปอ่านข้อมูลจาก network ทีละ 8KB
            while (bytesTransferred < fileSize) {
                buffer.clear(); // เตรียม buffer ใหม่
                
                // อ่านจาก network
                int bytesRead = channel.read(buffer);
                
                if (bytesRead == -1) {
                    System.err.println("\nConnection closed unexpectedly");
                    break;
                }
                
                if (bytesRead == 0) { // ถ้ายังไม่มีข้อมูลก็รอ
                    retryCount++;
                    if (retryCount > 100) {
                        System.err.println("\nConnection timeout");
                        break;
                    }
                    Thread.sleep(10);
                    continue;
                }
                
                retryCount = 0;
                buffer.flip(); // เตรียม buffer เพื่อเขียนลงไฟล์
                
                // เขียนจาก buffer → file
                while (buffer.hasRemaining()) {
                    outChan.write(buffer);
                }
                
                bytesTransferred += bytesRead;
                printProgress(bytesTransferred, fileSize);
            }
            
            if (bytesTransferred != fileSize) {
                System.err.println("\n[CLIENT] ❌ Incomplete: " + 
                    bytesTransferred + "/" + fileSize + " bytes");
                outFile.delete();
                throw new IOException("Incomplete transfer");
            }
            
            System.out.println("\n[NORMAL] ✅ Transfer complete using read/write");
        }
    }

    // อ่านข้อความที่จบด้วย '\n' จาก SocketChannel
    static String readLine(SocketChannel channel) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(1); // อ่านทีละ byte
        StringBuilder sb = new StringBuilder(); // เก็บข้อความที่อ่านได้
        
        while (true) {
            buffer.clear();
            int bytesRead = channel.read(buffer);
            
            if (bytesRead == -1) {
                if (sb.length() > 0) {
                    return sb.toString();
                }
                return null;
            }
            
            if (bytesRead == 0) continue;
            
            buffer.flip();
            char c = (char) buffer.get();
            
            if (c == '\n') {
                return sb.toString().replace("\r", "").trim();
            }
            
            sb.append(c);
        }
    }

    // ส่งข้อความ 1 บรรทัดไปยัง server
    static void writeLine(SocketChannel channel, String line) throws IOException {
        String message = line + "\n";
        ByteBuffer buffer = ByteBuffer.wrap(message.getBytes());
        
        // เขียนจนกว่าข้อมูลจะหมด buffer
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    // พิมพ์ progress bar ระหว่างดาวน์โหลด
    static void printProgress(long transferred, long total) {
        int width = 40;
        double pct = (double) transferred / total;
        int done = (int) (pct * width);

        String bar = "[" + "=".repeat(done) + " ".repeat(width - done) + "] "
                + (int)(pct * 100) + "% (" + formatBytes(transferred) + "/" + formatBytes(total) + ")";

        System.out.print("\r" + bar);
    }

    // แปลง byte → KB/MB/GB เพื่อให้อ่านง่าย
    static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }
}
