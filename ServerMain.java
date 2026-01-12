import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;

public class ServerMain {
    private static final String FILE_DIR = "files";
    private static final int PORT = 8000;
    private static volatile boolean running = true;
    private static final Semaphore fileTransferLimit = new Semaphore(5); // จำกัดจำนวนการดาวน์โหลดพร้อมกันสูงสุด 5

    public static void main(String[] args) {
        // Shutdown Hook โปรแกรมปิดอย่างปลอดภัย
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("\n[SERVER] Shutdown signal received");
            running = false;
        }));

        ExecutorService pool = Executors.newFixedThreadPool(10); // Thread pool รองรับ client พร้อมกันได้ 10 ตัว

        try (ServerSocketChannel serverChannel = ServerSocketChannel.open()) {
            serverChannel.bind(new InetSocketAddress(PORT)); // bind พอร์ต
            serverChannel.configureBlocking(true); // จะ “ค้างรอ (block)” จนกว่าจะมี client มาเชื่อมต่อ
            serverChannel.socket().setSoTimeout(1000); 
            /*รอ client 1 วินาที →
                ถ้ามี client → รับเชื่อมต่อ
                ถ้าไม่มี client → timeout → กลับไปเช็คว่า server ยังต้องทำงานไหม*/
            
            System.out.println("[SERVER] Running on port " + PORT);
            System.out.println(serverChannel.getLocalAddress());
            System.out.println("[SERVER] Zero-copy mode enabled via NIO");

            // loop รับ client ใหม่ตลอดเวลา
            while (running) {
                try {
                    // รอ client เชื่อมต่อ
                    SocketChannel client = serverChannel.accept();
                    System.out.println("[SERVER] New client connected: " + client.getRemoteAddress());
                    
                    // timeout สำหรับ client socket
                    client.socket().setSoTimeout(30000);
                    
                    // ส่งให้ thread pool จัดการ
                    pool.submit(new ClientHandler(client));

                } catch (SocketTimeoutException e) {
                    continue;
                } catch (IOException e) {
                    if (running) {
                        System.err.println("[SERVER ACCEPT ERROR] " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[SERVER STARTUP FAILED] " + e.getMessage());
        } finally {
            System.out.println("[SERVER] Shutting down thread pool.");
            if (pool != null) {
                pool.shutdown(); // หยุดรับงานใหม่
                try {
                    if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                        pool.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    pool.shutdownNow();
                }
            }
        }
    }

    // ClientHandler ทำงานบน thread จาก thread-pool เพื่ออ่านคำสั่งจาก client และตอบกลับ (LIST / DOWNLOAD / ERROR) — แล้วปิดการเชื่อมต่อเมื่อเสร็จ
    static class ClientHandler implements Runnable {
        private SocketChannel client; // ช่องสื่อสารกับ client เป็น NIO channel ใช้ส่ง/รับไบต์แบบ stream

        ClientHandler(SocketChannel client) {
            this.client = client;
        }

        @Override
        public void run() {
            try {
                String command = readLine(client); // อ่านคำสั่งจาก client
                System.out.println("[SERVER] Received: " + command);

                if (command.equals("LIST")) {
                    sendList(client); // ส่งรายชื่อไฟล์
                } else if (command.startsWith("DOWNLOAD")) {
                    handleDownload(command, client); // จัดการโหลดไฟล์
                } else {
                    writeLine(client, "ERROR Unknown command"); // คำสั่งที่ไม่รู้จัก
                }

            } catch (Exception e) {
                System.err.println("[ClientHandler] Error: " + e.getMessage());
                
            } finally {
                try { 
                    client.close(); // ปิดการเชื่อมต่อ
                    System.out.println("[SERVER] Client disconnected");
                } catch (Exception ignored) {}
            }
        }


        String readLine(SocketChannel channel) throws IOException {
            ByteBuffer buffer = ByteBuffer.allocate(256); // buffer ขนาด 256 bytes
            StringBuilder sb = new StringBuilder(); // เก็บข้อความที่อ่านได้
            
            while (true) {
                buffer.clear(); // reset buffer
                int bytesRead = channel.read(buffer); // อ่านจาก socket ลง buffer
                
                // ถ้า client ปิด แต่ยังมีข้อมูลค้าง ให้ return String ที่อ่านมา
                if (bytesRead == -1) {
                    if (sb.length() > 0) return sb.toString();
                    throw new IOException("Connection closed");
                }
                
                // ถ้าไม่มีข้อมูล → loop ต่อ
                if (bytesRead == 0) continue;
                
                buffer.flip(); // สลับ buffer เพื่ออ่าน
                while (buffer.hasRemaining()) {
                    char c = (char) buffer.get(); // อ่านทีละตัว
                    if (c == '\n') { // จบบรรทัด
                        return sb.toString().replace("\r", "").trim();
                    }
                    if (c != '\r') sb.append(c); // สะสมข้อความ
                }
            }
        }

         // ส่งข้อความเป็นบรรทัดเดียวจบด้วย \n ไปยัง client
        void writeLine(SocketChannel channel, String line) throws IOException {
            String message = line + "\n";
            ByteBuffer buffer = ByteBuffer.wrap(message.getBytes());
            
            while (buffer.hasRemaining()) {
                channel.write(buffer); // เขียนลง socket channel
            }
        }

        // ส่งรายชื่อไฟล์ทั้งหมดในโฟลเดอร์ FILE_DIR
        void sendList(SocketChannel channel) throws IOException {
            File dir = new File(FILE_DIR);
            File[] files = dir.listFiles();
            
            if (files == null) {
                writeLine(channel, "ERROR No files");
                return;
            }

            for (File f : files) {
                writeLine(channel, f.getName()); // ส่งชื่อไฟล์ทีละบรรทัด
            }
            writeLine(channel, "END"); // บอก client ว่าส่งครบแล้ว
            
            System.out.println("[LIST] Sent " + files.length + " file names");
        }

        // จัดการคำสั่ง DOWNLOAD <filename> <mode>
        void handleDownload(String cmd, SocketChannel channel) throws Exception {
            String[] parts = cmd.split(" ");
            
            if (parts.length != 3) {
                writeLine(channel, "ERROR Invalid download format");
                return;
            }

            String filename = parts[1];
            String mode = parts[2];

            // ป้องกัน path traversal แบบปลอดภัย
            File file = new File(FILE_DIR, filename).getCanonicalFile();
            File baseDir = new File(FILE_DIR).getCanonicalFile();

            if (!file.getPath().startsWith(baseDir.getPath())) {
                writeLine(channel, "ERROR Invalid file name");
                System.err.println("[SECURITY] Path traversal attempt: " + filename);
                return;
            }

            if (!file.exists()) {
                writeLine(channel, "ERROR File not found");
                return;
            }

            // จำกัดจำนวน concurrent transfer สูงสุด 5 ตัว
            if (!fileTransferLimit.tryAcquire(5, TimeUnit.SECONDS)) {
                writeLine(channel, "ERROR Server busy, try again later");
                return;
            }

            try {
                // ส่งข้อมูล metadata ไป client
                long fileSize = file.length();
                writeLine(channel, "FILESIZE " + fileSize); // แจ้งขนาดไฟล์ให้ client
                writeLine(channel, "READY");  // แจ้งว่าส่งพร้อมแล้ว

                //จับเวลา
                long startTime = System.currentTimeMillis();
                
                if (mode.equals("zero")) {
                    zeroCopy(file, channel); // ส่งแบบ zero-copy
                } else {
                    normalCopy(file, channel); // ส่งแบบปกติ
                }
                
                long endTime = System.currentTimeMillis();
                long duration = endTime - startTime;

                System.out.println("[SERVER] File sent: " + filename + 
                                 " (" + fileSize + " bytes) in " + duration + " ms " +
                                 "using " + mode + " mode");
            } finally {
                fileTransferLimit.release(); // คืนสิทธิ์ semaphore
            }
        }

        // ---------------- ZERO COPY --------------------
        void zeroCopy(File file, SocketChannel channel) throws Exception {
            try (FileChannel fc = new FileInputStream(file).getChannel()) {
                long position = 0; // จำนวน byte ที่ส่งไปแล้ว
                long remaining = fc.size(); // จำนวน bytes ที่ยังไม่ได้ส่ง
                int zeroCount = 0; // ใช้ตรวจว่า transferTo ส่ง 0 bytes ติดต่อกันไหม
                
                System.out.println("[zeroCopy] Starting transfer of " + remaining + " bytes");
                
                while (remaining > 0) {
                    // transferTo → ส่งข้อมูลโดยไม่เข้า user space
                    long transferred = fc.transferTo(position, remaining, channel);
                    
                    if (transferred == 0) {
                        // บางครั้ง transferTo คืน 0 ถ้า kernel busy
                        zeroCount++;
                        if (zeroCount > 100) {
                            throw new IOException("Client disconnected");
                        }
                        Thread.sleep(10);
                        continue;
                    }
                    
                    // reset counter
                    zeroCount = 0; 
                    position += transferred; // ขยับ pointer ในไฟล์
                    remaining -= transferred; // ลดจำนวน byte ที่ยังเหลือ
                     
                }
                
                System.out.println("[zeroCopy] ✅ Transfer complete: " + position + " bytes");
                
            } catch (IOException e) {
                System.err.println("[zeroCopy] ❌ IO error: " + e.getMessage());
                throw e;
            } 
        }

        // --------------- NORMAL COPY -------------------
        void normalCopy(File file, SocketChannel channel) throws Exception {
            try (FileChannel fc = new FileInputStream(file).getChannel()) {
                ByteBuffer buffer = ByteBuffer.allocate(8192);  // 8 KB buffer
                long totalBytes = 0;
                
                System.out.println("[normalCopy] Starting transfer of " + fc.size() + " bytes");
                
                while (fc.read(buffer) != -1) { // อ่านจากไฟล์ลง buffer
                    buffer.flip();  
                    
                    while (buffer.hasRemaining()) {
                        channel.write(buffer); // วนเขียนจน buffer หมดผ่านnetwork
                    }
                    
                    totalBytes += buffer.position(); // นับ byte ที่ส่งไปแล้ว
                    buffer.clear(); // reset buffer สำหรับรอบถัดไป
                    
                }
                
                System.out.println("[normalCopy] ✅ Transfer complete: " + totalBytes + " bytes");
                
            } catch (IOException e) {
                System.err.println("[normalCopy] ❌ IO error: " + e.getMessage());
                throw e;
            } 
        }

    }
}
