# Java I/O vs NIO — Deep Dive Guide

> A complete, in-depth comparison of Java's classic `java.io` and modern `java.nio` packages — architecture, internals, use cases, and real-world examples.

---

## 📚 Table of Contents

1. [Overview & History](#1-overview--history)
2. [Core Architecture Differences](#2-core-architecture-differences)
3. [Java IO — Deep Dive](#3-java-io--deep-dive)
   - [Stream Hierarchy](#31-stream-hierarchy)
   - [Byte Streams](#32-byte-streams)
   - [Character Streams](#33-character-streams)
   - [Buffered Streams](#34-buffered-streams)
   - [Data & Object Streams](#35-data--object-streams)
   - [PrintStream & PrintWriter](#36-printstream--printwriter)
4. [Java NIO — Deep Dive](#4-java-nio--deep-dive)
   - [Core Concepts: Buffer, Channel, Selector](#41-core-concepts-buffer-channel-selector)
   - [Buffers in Detail](#42-buffers-in-detail)
   - [Channels in Detail](#43-channels-in-detail)
   - [Selectors & Non-Blocking I/O](#44-selectors--non-blocking-io)
5. [Java NIO.2 (Java 7+) — Deep Dive](#5-java-nio2-java-7--deep-dive)
   - [Path & Paths](#51-path--paths)
   - [Files Utility Class](#52-files-utility-class)
   - [FileSystem & FileSystems](#53-filesystem--filesystems)
   - [WatchService — File Watching](#54-watchservice--file-watching)
   - [AsynchronousFileChannel](#55-asynchronousfilechannel)
6. [Blocking vs Non-Blocking vs Async](#6-blocking-vs-non-blocking-vs-async)
7. [Performance Benchmarks & Analysis](#7-performance-benchmarks--analysis)
8. [Real-World Use Cases](#8-real-world-use-cases)
9. [Common Pitfalls & Best Practices](#9-common-pitfalls--best-practices)
10. [Quick Decision Guide](#10-quick-decision-guide)
11. [Comparison Summary Table](#11-comparison-summary-table)

---

## 1. Overview & History

| Version | Package         | Released | Key Addition                          |
|---------|-----------------|----------|---------------------------------------|
| Java 1  | `java.io`       | 1996     | Stream-based, blocking I/O            |
| Java 4  | `java.nio`      | 2002     | Buffers, Channels, Selectors          |
| Java 7  | `java.nio.file` | 2011     | NIO.2: Path, Files, WatchService      |
| Java 11 | `java.nio`      | 2018     | `Files.readString()`, `writeString()` |

### The Problem `java.io` Solved
Before Java I/O, developers dealt with raw OS system calls. `java.io` introduced an elegant stream abstraction that treated all data sources (files, networks, memory) uniformly as a flow of bytes.

### The Problem `java.nio` Solved
`java.io` has one fundamental bottleneck: **every read/write blocks the calling thread**. For a server handling 10,000 simultaneous connections, that means 10,000 threads — which is expensive and doesn't scale. NIO introduced non-blocking I/O so a **single thread** can manage thousands of connections.

---

## 2. Core Architecture Differences

```
┌─────────────────────────────────────────────────────────────────┐
│                      JAVA I/O MODEL                             │
│                                                                 │
│  Thread ──► InputStream/OutputStream ──► OS Kernel ──► Disk    │
│              (BLOCKING — thread waits)                          │
│                                                                 │
│  Each connection = 1 thread                                     │
└─────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────┐
│                      JAVA NIO MODEL                             │
│                                                                 │
│               ┌── Channel A ──┐                                 │
│  Thread ──► Selector ── Channel B ──► OS Kernel ──► Disk/Net   │
│               └── Channel C ──┘                                 │
│              (NON-BLOCKING — thread polls readiness)            │
│                                                                 │
│  1 thread can manage N channels                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Fundamental Philosophical Difference

| Aspect            | java.io                            | java.nio                                  |
|-------------------|------------------------------------|-------------------------------------------|
| Data abstraction  | **Stream** (sequential bytes/chars)| **Buffer** (block of data in memory)      |
| Access pattern    | Sequential only                    | Random access via position/limit/capacity |
| Thread model      | One thread per connection          | One thread, many channels (via Selector)  |
| I/O mode          | Always blocking                    | Can be blocking or non-blocking           |
| Direction         | Unidirectional (in OR out)         | Bidirectional (read AND write)            |
| OS interaction    | Copies data through JVM            | Can use OS-level zero-copy (mmap)         |

---

## 3. Java IO — Deep Dive

### 3.1 Stream Hierarchy

```
java.io
│
├── InputStream (abstract)
│   ├── FileInputStream
│   ├── ByteArrayInputStream
│   ├── PipedInputStream
│   ├── FilterInputStream
│   │   ├── BufferedInputStream     ← adds buffering
│   │   ├── DataInputStream         ← reads primitives
│   │   └── PushbackInputStream
│   └── ObjectInputStream           ← deserialization
│
├── OutputStream (abstract)
│   ├── FileOutputStream
│   ├── ByteArrayOutputStream
│   ├── PipedOutputStream
│   ├── FilterOutputStream
│   │   ├── BufferedOutputStream    ← adds buffering
│   │   ├── DataOutputStream        ← writes primitives
│   │   └── PrintStream             ← System.out
│   └── ObjectOutputStream          ← serialization
│
├── Reader (abstract — character streams)
│   ├── FileReader
│   ├── StringReader
│   ├── BufferedReader              ← readLine() support
│   ├── InputStreamReader           ← bytes → chars (charset)
│   └── CharArrayReader
│
└── Writer (abstract — character streams)
    ├── FileWriter
    ├── StringWriter
    ├── BufferedWriter
    ├── OutputStreamWriter          ← chars → bytes (charset)
    └── PrintWriter
```

---

### 3.2 Byte Streams

```java
import java.io.*;

public class ByteStreamsDemo {

    // ── FileInputStream & FileOutputStream ──────────────────────
    public static void copyFileByte(String src, String dst) throws IOException {
        // try-with-resources auto-closes both streams
        try (FileInputStream  fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dst)) {

            int byteData;
            // read() returns -1 at EOF; each call = 1 system call (SLOW)
            while ((byteData = fis.read()) != -1) {
                fos.write(byteData);
            }
        }
    }

    // Better: read/write in chunks (byte array)
    public static void copyFileChunk(String src, String dst) throws IOException {
        try (FileInputStream  fis = new FileInputStream(src);
             FileOutputStream fos = new FileOutputStream(dst)) {

            byte[] buffer = new byte[8192]; // 8 KB chunks
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                fos.write(buffer, 0, bytesRead); // write only actual bytes read
            }
        }
    }

    // ── ByteArrayInputStream — reading from in-memory bytes ─────
    public static void byteArrayExample() throws IOException {
        byte[] data = {72, 101, 108, 108, 111}; // "Hello" in ASCII

        try (ByteArrayInputStream bais = new ByteArrayInputStream(data)) {
            int b;
            while ((b = bais.read()) != -1) {
                System.out.print((char) b); // Hello
            }
        }
    }

    // ── ByteArrayOutputStream — writing to in-memory buffer ─────
    public static byte[] captureBytes() throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            baos.write("Hello".getBytes());
            baos.write(", ".getBytes());
            baos.write("World".getBytes());
            return baos.toByteArray(); // get all written bytes
        }
    }

    public static void main(String[] args) throws IOException {
        copyFileByte("source.bin", "dest1.bin");
        copyFileChunk("source.bin", "dest2.bin");
        byteArrayExample();

        byte[] result = captureBytes();
        System.out.println(new String(result)); // Hello, World
    }
}
```

**How `read()` works internally:**
```
Java Thread → fis.read()
           → JVM native method
           → OS system call: read(fd, buffer, 1)
           → Kernel copies 1 byte from disk buffer to JVM heap
           → Thread unblocks
           ← returns byte value (0–255) or -1
```

---

### 3.3 Character Streams

```java
import java.io.*;
import java.nio.charset.StandardCharsets;

public class CharacterStreamsDemo {

    // ── FileReader / FileWriter — simple char I/O ────────────────
    public static void writeText(String path) throws IOException {
        // FileWriter uses platform default charset — RISKY
        try (FileWriter fw = new FileWriter(path)) {
            fw.write("Hello, 世界!\n"); // may corrupt non-ASCII on some platforms
            fw.write("Java I/O");
        }
    }

    // ── OutputStreamWriter — explicit charset control ────────────
    public static void writeTextSafe(String path) throws IOException {
        try (OutputStreamWriter osw = new OutputStreamWriter(
                new FileOutputStream(path), StandardCharsets.UTF_8)) {
            osw.write("Hello, 世界!\n"); // safe — explicit UTF-8
            osw.write(new char[]{'J','a','v','a'}, 0, 4);
        }
    }

    // ── InputStreamReader — decode bytes to chars ────────────────
    public static void readTextSafe(String path) throws IOException {
        try (InputStreamReader isr = new InputStreamReader(
                new FileInputStream(path), StandardCharsets.UTF_8)) {
            char[] cbuf = new char[1024];
            int charsRead;
            while ((charsRead = isr.read(cbuf)) != -1) {
                System.out.print(new String(cbuf, 0, charsRead));
            }
        }
    }

    public static void main(String[] args) throws IOException {
        writeTextSafe("output.txt");
        readTextSafe("output.txt");
    }
}
```

> ⚠️ **Always specify charset explicitly.** `new FileReader(path)` and `new FileWriter(path)` use the platform's default charset, which differs between Windows (Cp1252), Linux (UTF-8), and macOS (UTF-8). This causes silent data corruption on non-ASCII text.

---

### 3.4 Buffered Streams

```java
import java.io.*;

public class BufferedStreamsDemo {

    // WITHOUT buffering — each write is a system call
    public static void writeUnbuffered(String path) throws IOException {
        try (FileWriter fw = new FileWriter(path)) {
            for (int i = 0; i < 100_000; i++) {
                fw.write("Line " + i + "\n"); // 100,000 system calls!
            }
        }
    }

    // WITH buffering — data accumulates in 8KB buffer, then flushed
    public static void writeBuffered(String path) throws IOException {
        // BufferedWriter wraps FileWriter — decorator pattern
        try (BufferedWriter bw = new BufferedWriter(new FileWriter(path), 16384)) {
            // 16384 = custom 16KB buffer size
            for (int i = 0; i < 100_000; i++) {
                bw.write("Line " + i);
                bw.newLine(); // OS-appropriate line separator
            }
            // bw.flush() called automatically on close
        }
    }

    // BufferedReader — readLine() is the killer feature
    public static void readLines(String path) throws IOException {
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(new FileInputStream(path), "UTF-8"))) {

            String line;
            int lineNumber = 0;
            while ((line = br.readLine()) != null) {
                lineNumber++;
                System.out.printf("%4d: %s%n", lineNumber, line);
            }
        }
    }

    // Lines as Stream (Java 8+)
    public static void readAsStream(String path) throws IOException {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            long count = br.lines()
                           .filter(line -> line.contains("error"))
                           .count();
            System.out.println("Error lines: " + count);
        }
    }

    public static void main(String[] args) throws IOException {
        long start, end;

        start = System.currentTimeMillis();
        writeUnbuffered("unbuffered.txt");
        end = System.currentTimeMillis();
        System.out.println("Unbuffered: " + (end - start) + "ms");

        start = System.currentTimeMillis();
        writeBuffered("buffered.txt");
        end = System.currentTimeMillis();
        System.out.println("Buffered:   " + (end - start) + "ms");
        // Buffered is typically 10x–100x faster
    }
}
```

**How buffering works:**
```
Without BufferedWriter:
  write("Line 0\n") → system call → kernel
  write("Line 1\n") → system call → kernel
  ... 100,000 system calls

With BufferedWriter (8 KB buffer):
  write("Line 0\n") → buffer[0..6]
  write("Line 1\n") → buffer[7..13]
  ...
  buffer fills at 8192 bytes → ONE system call → kernel
  ... ~12 total system calls for 100,000 lines
```

---

### 3.5 Data & Object Streams

```java
import java.io.*;

// ── DataInputStream / DataOutputStream ──────────────────────────
public class DataStreamsDemo {

    public static void writeData(String path) throws IOException {
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(path)))) {
            dos.writeInt(42);
            dos.writeDouble(3.14159);
            dos.writeBoolean(true);
            dos.writeUTF("Hello, Data!"); // length-prefixed UTF-8 string
            dos.writeLong(System.currentTimeMillis());
        }
    }

    public static void readData(String path) throws IOException {
        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(new FileInputStream(path)))) {
            // MUST read in EXACT same order as written
            int i     = dis.readInt();
            double d  = dis.readDouble();
            boolean b = dis.readBoolean();
            String s  = dis.readUTF();
            long ts   = dis.readLong();
            System.out.printf("int=%d, double=%.5f, bool=%b, str=%s, ts=%d%n", i, d, b, s, ts);
        }
    }
}

// ── ObjectInputStream / ObjectOutputStream — Serialization ──────
public class SerializationDemo {

    // Must implement Serializable
    static class Employee implements Serializable {
        private static final long serialVersionUID = 1L; // version stamp

        private String name;
        private int id;
        private double salary;
        private transient String password; // transient = NOT serialized

        public Employee(String name, int id, double salary, String password) {
            this.name = name; this.id = id;
            this.salary = salary; this.password = password;
        }

        @Override
        public String toString() {
            return String.format("Employee{name='%s', id=%d, salary=%.2f, password='%s'}",
                                 name, id, salary, password);
        }
    }

    public static void serialize(Employee emp, String path) throws IOException {
        try (ObjectOutputStream oos = new ObjectOutputStream(
                new BufferedOutputStream(new FileOutputStream(path)))) {
            oos.writeObject(emp);
        }
    }

    public static Employee deserialize(String path) throws IOException, ClassNotFoundException {
        try (ObjectInputStream ois = new ObjectInputStream(
                new BufferedInputStream(new FileInputStream(path)))) {
            return (Employee) ois.readObject();
        }
    }

    public static void main(String[] args) throws Exception {
        Employee emp = new Employee("Alice", 101, 75000.00, "secret123");
        System.out.println("Before: " + emp);

        serialize(emp, "employee.ser");

        Employee restored = deserialize("employee.ser");
        System.out.println("After:  " + restored);
        // password will be null — transient field not serialized
    }
}
```

---

### 3.6 PrintStream & PrintWriter

```java
import java.io.*;

public class PrintStreamsDemo {
    public static void main(String[] args) throws IOException {

        // PrintStream — System.out is a PrintStream
        PrintStream ps = new PrintStream(new FileOutputStream("output.txt"), true, "UTF-8");
        ps.println("Hello, PrintStream!");
        ps.printf("Name: %-10s Age: %3d%n", "Alice", 30);
        ps.format("PI = %.4f%n", Math.PI);
        ps.close();

        // PrintWriter — preferred for text output (char-based)
        try (PrintWriter pw = new PrintWriter(
                new BufferedWriter(new FileWriter("writer_output.txt")))) {
            pw.println("Hello, PrintWriter!");
            pw.printf("%.2f%n", 99.999);
            // Note: PrintWriter silently suppresses exceptions!
            // Always check pw.checkError() after writes
            if (pw.checkError()) {
                System.err.println("PrintWriter encountered an error");
            }
        }

        // Redirect System.out to file
        PrintStream originalOut = System.out;
        try (PrintStream fileOut = new PrintStream("console_capture.txt")) {
            System.setOut(fileOut);
            System.out.println("This goes to file, not console");
        }
        System.setOut(originalOut); // restore
        System.out.println("Back to console");
    }
}
```

---

## 4. Java NIO — Deep Dive

### 4.1 Core Concepts: Buffer, Channel, Selector

```
┌────────────────────────────────────────────────────────┐
│                  NIO CORE CONCEPTS                     │
│                                                        │
│  ┌──────────┐    read/write     ┌───────────────────┐  │
│  │          │ ◄──────────────── │                   │  │
│  │  Buffer  │                   │     Channel       │  │
│  │(ByteBuffer│ ──────────────── ►│  (FileChannel,   │  │
│  │  etc.)   │    read/write     │  SocketChannel)  │  │
│  └──────────┘                   └─────────┬─────────┘  │
│                                           │            │
│  ┌──────────────────────────────────────┐ │            │
│  │           Selector                   │◄┘            │
│  │  (monitors multiple channels for     │              │
│  │   readiness — select/epoll/kqueue)   │              │
│  └──────────────────────────────────────┘              │
└────────────────────────────────────────────────────────┘
```

---

### 4.2 Buffers in Detail

A Buffer is a **fixed-size container** backed by an array with four key pointers:

```
Initial state after allocate(10):
  position=0, limit=10, capacity=10

  ┌───┬───┬───┬───┬───┬───┬───┬───┬───┬───┐
  │ 0 │ 0 │ 0 │ 0 │ 0 │ 0 │ 0 │ 0 │ 0 │ 0 │
  └───┴───┴───┴───┴───┴───┴───┴───┴───┴───┘
  ↑                                       ↑
  position=0                          limit=capacity=10

After put("Hello".getBytes()) — writing 5 bytes:
  position=5, limit=10, capacity=10

  ┌───┬───┬───┬───┬───┬───┬───┬───┬───┬───┐
  │ H │ e │ l │ l │ o │ 0 │ 0 │ 0 │ 0 │ 0 │
  └───┴───┴───┴───┴───┴───┴───┴───┴───┴───┘
                  ↑                       ↑
              position=5             limit=10

After flip() — switch from write mode to read mode:
  position=0, limit=5, capacity=10

  ┌───┬───┬───┬───┬───┬───┬───┬───┬───┬───┐
  │ H │ e │ l │ l │ o │ 0 │ 0 │ 0 │ 0 │ 0 │
  └───┴───┴───┴───┴───┴───┴───┴───┴───┴───┘
  ↑               ↑
  position=0   limit=5

After get() reads all — position moves to limit:
  position=5, limit=5 → remaining()=0

After clear() — ready to write again:
  position=0, limit=10 (data still there, but will be overwritten)

After rewind() — re-read from beginning:
  position=0, limit unchanged
```

```java
import java.nio.*;
import java.nio.channels.*;
import java.io.*;

public class BufferDemo {

    public static void main(String[] args) {

        // ── Allocating Buffers ────────────────────────────────
        ByteBuffer heapBuffer   = ByteBuffer.allocate(1024);       // JVM heap
        ByteBuffer directBuffer = ByteBuffer.allocateDirect(1024); // OS native memory
        // Direct buffer: no copy needed between OS and JVM — better for channels
        // Heap buffer: faster to allocate, easier for GC

        // ── Writing to Buffer ─────────────────────────────────
        ByteBuffer buf = ByteBuffer.allocate(16);
        buf.put((byte) 'H');
        buf.put((byte) 'e');
        buf.put((byte) 'l');
        buf.put((byte) 'l');
        buf.put((byte) 'o');
        buf.putInt(42);       // 4 bytes
        buf.putDouble(3.14);  // 8 bytes — would overflow 16-byte buffer with above!

        System.out.printf("After writes → pos=%d, lim=%d, cap=%d%n",
            buf.position(), buf.limit(), buf.capacity());

        // ── flip() — switch to read mode ──────────────────────
        buf.flip();
        System.out.printf("After flip() → pos=%d, lim=%d%n",
            buf.position(), buf.limit());

        // ── Reading from Buffer ───────────────────────────────
        while (buf.hasRemaining()) {
            System.out.print((char) buf.get());
        }

        // ── Mark and Reset ────────────────────────────────────
        ByteBuffer b2 = ByteBuffer.allocate(10);
        b2.put(new byte[]{1, 2, 3, 4, 5});
        b2.flip();
        b2.get(); // read 1
        b2.get(); // read 2
        b2.mark(); // mark position at 2
        b2.get(); // read 3
        b2.get(); // read 4
        b2.reset(); // back to marked position (2)
        System.out.println("After reset: " + b2.get()); // 3 again

        // ── compact() — keep unread data, ready for more writes ──
        ByteBuffer b3 = ByteBuffer.allocate(10);
        b3.put(new byte[]{1,2,3,4,5,6,7,8,9,10});
        b3.flip();
        b3.get(); b3.get(); b3.get(); // read 3 bytes
        b3.compact(); // shift remaining 7 bytes to start; position=7, limit=10
        // Now can write 3 more bytes at end

        // ── Buffer Types ──────────────────────────────────────
        IntBuffer    ib = IntBuffer.allocate(10);
        LongBuffer   lb = LongBuffer.allocate(10);
        FloatBuffer  fb = FloatBuffer.allocate(10);
        DoubleBuffer db = DoubleBuffer.allocate(10);
        CharBuffer   cb = CharBuffer.allocate(10);
        // All share the same API: position, limit, capacity, flip, clear, rewind

        // ── Wrapping existing arrays ──────────────────────────
        byte[] arr = {10, 20, 30, 40, 50};
        ByteBuffer wrapped = ByteBuffer.wrap(arr);
        // Changes to wrapped affect arr and vice versa — shared backing array

        // ── Slicing ───────────────────────────────────────────
        ByteBuffer original = ByteBuffer.allocate(10);
        original.position(2);
        original.limit(8);
        ByteBuffer slice = original.slice(); // pos=0, lim=6, cap=6; shares data
    }
}
```

---

### 4.3 Channels in Detail

```java
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;

public class ChannelsDemo {

    // ── FileChannel — reading ─────────────────────────────────────
    public static String readWithChannel(String path) throws IOException {
        try (FileChannel channel = FileChannel.open(
                Paths.get(path), StandardOpenOption.READ)) {

            ByteBuffer buffer = ByteBuffer.allocate((int) channel.size());
            channel.read(buffer);   // read entire file into buffer
            buffer.flip();          // switch to read mode

            return StandardCharsets.UTF_8.decode(buffer).toString();
        }
    }

    // ── FileChannel — writing ─────────────────────────────────────
    public static void writeWithChannel(String path, String content) throws IOException {
        try (FileChannel channel = FileChannel.open(
                Paths.get(path),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {

            ByteBuffer buffer = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
            while (buffer.hasRemaining()) {
                channel.write(buffer); // write() may not write all bytes at once
            }
        }
    }

    // ── FileChannel — random access ───────────────────────────────
    public static void randomAccess(String path) throws IOException {
        try (FileChannel channel = FileChannel.open(
                Paths.get(path), StandardOpenOption.READ, StandardOpenOption.WRITE)) {

            // Read from specific position
            ByteBuffer buf = ByteBuffer.allocate(10);
            channel.read(buf, 5); // read 10 bytes starting at offset 5
            buf.flip();
            System.out.println("Bytes at offset 5: " + buf.remaining());

            // Write at specific position
            ByteBuffer writeBuf = ByteBuffer.wrap("PATCHED".getBytes());
            channel.write(writeBuf, 0); // overwrite at offset 0

            // Get/set position
            System.out.println("Current position: " + channel.position());
            channel.position(0);        // seek to beginning
        }
    }

    // ── FileChannel.transferTo — ZERO COPY ───────────────────────
    // OS-level data transfer: no JVM heap involvement!
    public static void zeroCopyCopy(String src, String dst) throws IOException {
        try (FileChannel source = FileChannel.open(Paths.get(src), StandardOpenOption.READ);
             FileChannel target = FileChannel.open(Paths.get(dst),
                     StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {

            long size = source.size();
            long transferred = 0;
            while (transferred < size) {
                transferred += source.transferTo(transferred, size - transferred, target);
            }
        }
        // Equivalent to Linux sendfile() syscall — extremely fast for large files
    }

    // ── Memory-Mapped Files — mmap ────────────────────────────────
    public static void memoryMappedFile(String path) throws IOException {
        try (FileChannel channel = FileChannel.open(
                Paths.get(path), StandardOpenOption.READ, StandardOpenOption.WRITE)) {

            // Map entire file into memory — virtual address space mapping
            MappedByteBuffer mappedBuf = channel.map(
                FileChannel.MapMode.READ_WRITE,
                0,              // start offset
                channel.size()  // map size
            );

            // Read as if in memory — no explicit read() calls needed
            byte firstByte = mappedBuf.get(0);

            // Write — changes go directly to file (OS manages flushing)
            mappedBuf.put(0, (byte) 'X');
            mappedBuf.force(); // explicit flush to disk

            // Ideal for: huge files, databases, shared memory between processes
        }
    }

    // ── Scatter / Gather I/O ─────────────────────────────────────
    public static void scatterGatherExample(String path) throws IOException {
        try (FileChannel channel = FileChannel.open(Paths.get(path), StandardOpenOption.READ)) {

            // Scatter read: fill multiple buffers in one system call
            ByteBuffer header  = ByteBuffer.allocate(128);
            ByteBuffer body    = ByteBuffer.allocate(1024);
            ByteBuffer trailer = ByteBuffer.allocate(64);

            ByteBuffer[] buffers = {header, body, trailer};
            channel.read(buffers); // kernel fills them in order — ONE syscall

            header.flip();  body.flip();  trailer.flip();
            System.out.println("Header bytes: " + header.remaining());
        }

        try (FileChannel channel = FileChannel.open(
                Paths.get(path), StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {

            // Gather write: write multiple buffers in one system call
            ByteBuffer header  = ByteBuffer.wrap("HEADER\n".getBytes());
            ByteBuffer body    = ByteBuffer.wrap("BODY CONTENT\n".getBytes());
            ByteBuffer trailer = ByteBuffer.wrap("TRAILER\n".getBytes());

            channel.write(new ByteBuffer[]{header, body, trailer}); // ONE syscall
        }
    }
}
```

---

### 4.4 Selectors & Non-Blocking I/O

This is NIO's most powerful feature — multiplexing many channels on one thread:

```java
import java.io.*;
import java.net.*;
import java.nio.*;
import java.nio.channels.*;
import java.util.*;

public class SelectorDemo {

    // Non-blocking server that handles multiple clients on ONE thread
    public static void nonBlockingServer(int port) throws IOException {
        // 1. Open selector (backed by epoll/kqueue/select depending on OS)
        Selector selector = Selector.open();

        // 2. Open server channel and configure non-blocking
        ServerSocketChannel serverChannel = ServerSocketChannel.open();
        serverChannel.configureBlocking(false); // KEY — non-blocking mode
        serverChannel.bind(new InetSocketAddress(port));

        // 3. Register channel with selector; interested in ACCEPT events
        serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        System.out.println("Server started on port " + port);

        ByteBuffer buffer = ByteBuffer.allocate(1024);

        while (true) {
            // 4. selector.select() BLOCKS until at least one channel is ready
            //    selector.select(1000) — blocks max 1 second
            //    selector.selectNow() — returns immediately (0 if nothing ready)
            int readyChannels = selector.select();
            if (readyChannels == 0) continue;

            // 5. Get the ready selection keys
            Set<SelectionKey> selectedKeys = selector.selectedKeys();
            Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

            while (keyIterator.hasNext()) {
                SelectionKey key = keyIterator.next();
                keyIterator.remove(); // IMPORTANT: remove from set manually

                if (key.isAcceptable()) {
                    // New client connection
                    ServerSocketChannel server = (ServerSocketChannel) key.channel();
                    SocketChannel clientChannel = server.accept(); // non-blocking accept
                    if (clientChannel != null) {
                        clientChannel.configureBlocking(false);
                        // Register client channel for READ events
                        clientChannel.register(selector, SelectionKey.OP_READ);
                        System.out.println("Connected: " + clientChannel.getRemoteAddress());
                    }

                } else if (key.isReadable()) {
                    // Data available to read from client
                    SocketChannel clientChannel = (SocketChannel) key.channel();
                    buffer.clear();
                    int bytesRead = clientChannel.read(buffer);

                    if (bytesRead == -1) {
                        // Client disconnected
                        System.out.println("Disconnected: " + clientChannel.getRemoteAddress());
                        key.cancel();
                        clientChannel.close();
                    } else {
                        buffer.flip();
                        String message = StandardCharsets.UTF_8.decode(buffer).toString().trim();
                        System.out.println("Received: " + message);

                        // Echo back to client
                        buffer.rewind();
                        clientChannel.write(buffer);

                        // If we want to write more data later, register for WRITE
                        // key.interestOps(SelectionKey.OP_WRITE);
                    }

                } else if (key.isWritable()) {
                    // Channel is ready to accept writes
                    SocketChannel clientChannel = (SocketChannel) key.channel();
                    // Write pending data, then switch back to READ
                    key.interestOps(SelectionKey.OP_READ);
                }
            }
        }
    }
}
```

**How Selector maps to OS mechanisms:**
```
Java Selector
    │
    ├── Linux   → epoll (scalable, O(1) for n connections)
    ├── macOS   → kqueue
    ├── Windows → IOCP (I/O Completion Ports)
    └── Others  → select/poll (O(n), legacy fallback)
```

**Selection Keys & Interest Ops:**
```
SelectionKey.OP_ACCEPT  = 16  (0b10000) — server ready to accept connection
SelectionKey.OP_CONNECT = 8   (0b01000) — client connection established
SelectionKey.OP_READ    = 1   (0b00001) — channel has data to read
SelectionKey.OP_WRITE   = 4   (0b00100) — channel ready to accept writes

// Multiple interests:
channel.register(selector, SelectionKey.OP_READ | SelectionKey.OP_WRITE);

// Attach custom objects to keys
key.attach(new ClientSession(clientId));
ClientSession session = (ClientSession) key.attachment();
```

---

## 5. Java NIO.2 (Java 7+) — Deep Dive

NIO.2 (JSR 203) is a complete overhaul of the file system API. It doesn't replace NIO channels/buffers — it adds a much better file system abstraction.

### 5.1 Path & Paths

```java
import java.nio.file.*;

public class PathDemo {
    public static void main(String[] args) {
        // Creating Paths
        Path absolute = Paths.get("/home/user/documents/report.pdf");
        Path relative = Paths.get("src", "main", "java", "App.java");
        Path fromURI  = Path.of("file:///tmp/test.txt"); // Java 11+

        // Path components
        System.out.println(absolute.getFileName());   // report.pdf
        System.out.println(absolute.getParent());     // /home/user/documents
        System.out.println(absolute.getRoot());       // /
        System.out.println(absolute.getNameCount());  // 4
        System.out.println(absolute.getName(0));      // home
        System.out.println(absolute.subpath(1, 3));   // user/documents

        // Path operations
        Path base = Paths.get("/home/user");
        Path child = base.resolve("downloads/file.txt");   // /home/user/downloads/file.txt
        Path sibling = absolute.resolveSibling("other.pdf"); // /home/user/documents/other.pdf

        Path p1 = Paths.get("/home/user/docs");
        Path p2 = Paths.get("/home/user/downloads/file.txt");
        Path relative2 = p1.relativize(p2); // ../downloads/file.txt

        // Normalize removes redundant elements
        Path messy = Paths.get("/home/user/../user/./documents");
        System.out.println(messy.normalize()); // /home/user/documents

        // toAbsolutePath resolves against current working directory
        Path rel = Paths.get("config.properties");
        System.out.println(rel.toAbsolutePath());

        // toRealPath — resolves symlinks AND normalizes (requires file to exist)
        // Path real = rel.toRealPath();

        // Comparing paths
        Path a = Paths.get("a/b/c");
        Path b2 = Paths.get("a/b/c");
        System.out.println(a.equals(b2));     // true
        System.out.println(a.startsWith("a/b")); // true
        System.out.println(a.endsWith("b/c"));   // true

        // Iterating path components
        for (Path component : absolute) {
            System.out.println(component); // home, user, documents, report.pdf
        }
    }
}
```

---

### 5.2 Files Utility Class

```java
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.io.*;
import java.util.*;
import java.util.stream.*;

public class FilesDemo {

    public static void main(String[] args) throws IOException {

        Path dir  = Paths.get("demo_dir");
        Path file = dir.resolve("hello.txt");

        // ── Create ─────────────────────────────────────────────
        Files.createDirectory(dir);            // create single directory
        Files.createDirectories(dir.resolve("a/b/c")); // create all missing parents
        Files.createFile(file);                // create empty file
        Path tempFile = Files.createTempFile("prefix-", ".tmp");
        Path tempDir  = Files.createTempDirectory("myapp-");

        // ── Write ──────────────────────────────────────────────
        Files.writeString(file, "Hello, NIO.2!\n");                      // Java 11+
        Files.writeString(file, "Appended line\n", StandardOpenOption.APPEND);
        Files.write(file, "Another line\n".getBytes(), StandardOpenOption.APPEND);
        Files.write(file, List.of("Line 1", "Line 2", "Line 3"),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE);

        // ── Read ───────────────────────────────────────────────
        String content       = Files.readString(file);                   // Java 11+
        byte[] bytes         = Files.readAllBytes(file);
        List<String> lines   = Files.readAllLines(file, StandardCharsets.UTF_8);

        // Streaming read — memory-efficient for large files
        try (Stream<String> lineStream = Files.lines(file, StandardCharsets.UTF_8)) {
            lineStream.filter(l -> !l.isBlank())
                      .map(String::trim)
                      .forEach(System.out::println);
        }

        // BufferedReader/Writer from Path
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) System.out.println(line);
        }
        try (BufferedWriter bw = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.APPEND)) {
            bw.write("Written by BufferedWriter");
        }

        // ── Copy & Move ────────────────────────────────────────
        Path dest = dir.resolve("copy.txt");
        Files.copy(file, dest, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(file, dest, StandardCopyOption.COPY_ATTRIBUTES);   // preserve metadata
        Files.move(dest, dir.resolve("moved.txt"), StandardCopyOption.REPLACE_EXISTING);
        Files.move(dest, dir.resolve("renamed.txt"), StandardCopyOption.ATOMIC_MOVE); // atomic

        // ── Delete ─────────────────────────────────────────────
        Files.delete(file);                // throws NoSuchFileException if missing
        Files.deleteIfExists(tempFile);    // safe — no exception if missing

        // ── Check & Inspect ────────────────────────────────────
        System.out.println(Files.exists(file));
        System.out.println(Files.notExists(file));
        System.out.println(Files.isDirectory(dir));
        System.out.println(Files.isRegularFile(file));
        System.out.println(Files.isReadable(file));
        System.out.println(Files.isWritable(file));
        System.out.println(Files.isHidden(file));
        System.out.println(Files.size(file));  // bytes
        System.out.println(Files.isSameFile(file, file)); // true — even through symlinks

        // ── Attributes ─────────────────────────────────────────
        BasicFileAttributes attrs = Files.readAttributes(file, BasicFileAttributes.class);
        System.out.println("Created:  " + attrs.creationTime());
        System.out.println("Modified: " + attrs.lastModifiedTime());
        System.out.println("Size:     " + attrs.size());
        System.out.println("Is dir:   " + attrs.isDirectory());

        FileTime newTime = FileTime.fromMillis(System.currentTimeMillis());
        Files.setLastModifiedTime(file, newTime);

        // ── Directory Listing ──────────────────────────────────
        // List immediate children
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path entry : ds) System.out.println(entry);
        }

        // Filtered listing
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.txt")) {
            for (Path entry : ds) System.out.println(entry);
        }

        // As a Stream (Java 8+)
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile).forEach(System.out::println);
        }

        // ── Walking Directory Trees ────────────────────────────
        // Walk with depth limit
        try (Stream<Path> walk = Files.walk(dir, 3)) {
            walk.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(System.out::println);
        }

        // Find with matcher
        try (Stream<Path> found = Files.find(dir, 10,
                (p, a) -> a.isRegularFile() && p.toString().endsWith(".txt"))) {
            found.forEach(System.out::println);
        }

        // FileVisitor — full control over directory traversal
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                System.out.println("File: " + file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                System.out.println("Dir: " + dir);
                return FileVisitResult.CONTINUE;
                // Return SKIP_SUBTREE to skip this directory
                // Return TERMINATE to stop entire walk
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException ex) {
                System.err.println("Failed: " + file + " — " + ex.getMessage());
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
```

---

### 5.3 FileSystem & FileSystems

```java
import java.nio.file.*;
import java.net.URI;
import java.util.*;

public class FileSystemDemo {
    public static void main(String[] args) throws Exception {

        // Default filesystem
        FileSystem fs = FileSystems.getDefault();
        System.out.println("Separator: " + fs.getSeparator());
        for (Path root : fs.getRootDirectories()) System.out.println("Root: " + root);
        for (FileStore store : fs.getFileStores()) {
            System.out.printf("Store: %s — Total: %d GB, Free: %d GB%n",
                store.name(),
                store.getTotalSpace() / (1024*1024*1024),
                store.getUsableSpace() / (1024*1024*1024));
        }

        // Read inside a ZIP file as a filesystem!
        URI zipUri = URI.create("jar:file:/path/to/archive.zip");
        try (FileSystem zipFs = FileSystems.newFileSystem(zipUri, Map.of("create", "false"))) {
            Path insideZip = zipFs.getPath("/README.txt");
            String content = Files.readString(insideZip);
            System.out.println(content);

            // List contents of ZIP
            try (Stream<Path> walk = Files.walk(zipFs.getPath("/"))) {
                walk.forEach(System.out::println);
            }
        }
    }
}
```

---

### 5.4 WatchService — File Watching

```java
import java.nio.file.*;
import static java.nio.file.StandardWatchEventKinds.*;

public class WatchServiceDemo {
    public static void watchDirectory(Path dir) throws Exception {

        WatchService watcher = FileSystems.getDefault().newWatchService();

        // Register directory for specific events
        dir.register(watcher,
            ENTRY_CREATE,   // new file/dir created
            ENTRY_DELETE,   // file/dir deleted
            ENTRY_MODIFY,   // file content modified
            OVERFLOW        // events may have been lost
        );

        System.out.println("Watching: " + dir);

        while (true) {
            // poll() — non-blocking, returns null immediately if no events
            // poll(timeout, unit) — waits up to timeout
            // take() — blocks until an event occurs
            WatchKey key = watcher.take(); // blocking

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();

                if (kind == OVERFLOW) {
                    System.out.println("Event overflow — some events may have been missed");
                    continue;
                }

                @SuppressWarnings("unchecked")
                WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                Path changed = dir.resolve(pathEvent.context());

                if (kind == ENTRY_CREATE) {
                    System.out.println("CREATED: " + changed);
                } else if (kind == ENTRY_DELETE) {
                    System.out.println("DELETED: " + changed);
                } else if (kind == ENTRY_MODIFY) {
                    System.out.println("MODIFIED: " + changed);
                }
            }

            // CRITICAL: reset the key to receive further events
            boolean valid = key.reset();
            if (!valid) {
                System.out.println("Watch key no longer valid — directory deleted?");
                break;
            }
        }
    }

    public static void main(String[] args) throws Exception {
        watchDirectory(Paths.get("."));
    }
}
```

---

### 5.5 AsynchronousFileChannel

```java
import java.nio.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.concurrent.*;

public class AsyncFileChannelDemo {

    // ── Future-based async read ────────────────────────────────
    public static void asyncReadFuture(String path) throws Exception {
        try (AsynchronousFileChannel channel = AsynchronousFileChannel.open(
                Paths.get(path), StandardOpenOption.READ)) {

            ByteBuffer buffer = ByteBuffer.allocate((int) channel.size());
            Future<Integer> future = channel.read(buffer, 0); // read from offset 0

            // Do other work while read happens...
            System.out.println("Doing other work...");

            // Block for result when needed
            int bytesRead = future.get(5, TimeUnit.SECONDS);
            buffer.flip();
            System.out.println("Read " + bytesRead + " bytes: "
                + StandardCharsets.UTF_8.decode(buffer));
        }
    }

    // ── Completion-handler-based async read ────────────────────
    public static void asyncReadCallback(String path) throws Exception {
        AsynchronousFileChannel channel = AsynchronousFileChannel.open(
            Paths.get(path), StandardOpenOption.READ);

        ByteBuffer buffer = ByteBuffer.allocate(1024);
        CountDownLatch latch = new CountDownLatch(1);

        channel.read(buffer, 0, buffer, new CompletionHandler<Integer, ByteBuffer>() {
            @Override
            public void completed(Integer bytesRead, ByteBuffer attachment) {
                attachment.flip();
                System.out.println("Async read done: " + bytesRead + " bytes");
                System.out.println(StandardCharsets.UTF_8.decode(attachment));
                latch.countDown();
                try { channel.close(); } catch (IOException e) { e.printStackTrace(); }
            }

            @Override
            public void failed(Throwable exc, ByteBuffer attachment) {
                System.err.println("Read failed: " + exc.getMessage());
                latch.countDown();
                try { channel.close(); } catch (IOException e) { e.printStackTrace(); }
            }
        });

        latch.await(5, TimeUnit.SECONDS);
    }

    // ── Async write ────────────────────────────────────────────
    public static void asyncWrite(String path, String content) throws Exception {
        try (AsynchronousFileChannel channel = AsynchronousFileChannel.open(
                Paths.get(path),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {

            ByteBuffer buffer = ByteBuffer.wrap(content.getBytes(StandardCharsets.UTF_8));
            Future<Integer> writeFuture = channel.write(buffer, 0);
            System.out.println("Bytes written: " + writeFuture.get());
        }
    }

    public static void main(String[] args) throws Exception {
        asyncWrite("async_test.txt", "Hello from async write!\n");
        asyncReadFuture("async_test.txt");
        asyncReadCallback("async_test.txt");
    }
}
```

---

## 6. Blocking vs Non-Blocking vs Async

```
┌──────────────────────────────────────────────────────────────────────┐
│                    I/O MODELS COMPARED                               │
├──────────────┬───────────────────────────────────────────────────────┤
│              │                                                        │
│  BLOCKING    │  Thread calls read() ──────────────────────► returns  │
│  (java.io)   │  Thread is BLOCKED the entire time                    │
│              │  ░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░             │
│              │                                                        │
├──────────────┼───────────────────────────────────────────────────────┤
│              │                                                        │
│ NON-BLOCKING │  Thread calls read() ──► returns immediately (0)      │
│  (NIO with   │  Thread does other work ──► polls again ──► data!     │
│  Selector)   │  Thread is NEVER blocked (busy-waits or uses select)  │
│              │                                                        │
├──────────────┼───────────────────────────────────────────────────────┤
│              │                                                        │
│ ASYNCHRONOUS │  Thread calls read(callback) ──► returns immediately  │
│  (NIO.2      │  Thread does other work completely                     │
│  Async)      │  OS completes I/O ──► callback invoked on thread pool │
│              │  Thread is NEVER involved in waiting                   │
│              │                                                        │
└──────────────┴───────────────────────────────────────────────────────┘
```

```java
// BLOCKING I/O — thread waits
InputStream is = new FileInputStream("file.txt");
int data = is.read(); // thread blocked here until data arrives

// NON-BLOCKING NIO — returns immediately
SocketChannel channel = SocketChannel.open();
channel.configureBlocking(false);
ByteBuffer buf = ByteBuffer.allocate(1024);
int bytesRead = channel.read(buf); // returns 0 immediately if no data
// -1 = channel closed, 0 = no data yet, >0 = bytes read

// ASYNC NIO.2 — callback on completion
AsynchronousFileChannel afc = AsynchronousFileChannel.open(path, READ);
afc.read(buffer, 0, null, new CompletionHandler<Integer, Void>() {
    public void completed(Integer n, Void v) { /* runs on thread pool */ }
    public void failed(Throwable e, Void v)  { /* error handling */ }
});
// returns immediately; callback fires when OS completes
```

---

## 7. Performance Benchmarks & Analysis

### File Copy — 100 MB file

```java
public class Benchmark {

    // Method 1: IO byte-by-byte — WORST (100M system calls)
    static void copyIO_Single(String src, String dst) throws IOException {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            int b;
            while ((b = in.read()) != -1) out.write(b);
        }
    }
    // Result: ~25,000 ms (25 seconds) for 100MB

    // Method 2: IO buffered chunks
    static void copyIO_Buffered(String src, String dst) throws IOException {
        try (InputStream in  = new BufferedInputStream(new FileInputStream(src), 65536);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(dst), 65536)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }
    // Result: ~250 ms for 100MB

    // Method 3: NIO channel transfer (zero-copy)
    static void copyNIO_Transfer(String src, String dst) throws IOException {
        try (FileChannel in  = FileChannel.open(Paths.get(src), READ);
             FileChannel out = FileChannel.open(Paths.get(dst), CREATE, WRITE)) {
            in.transferTo(0, in.size(), out);
        }
    }
    // Result: ~80 ms for 100MB

    // Method 4: NIO.2 Files.copy
    static void copyNIO2(String src, String dst) throws IOException {
        Files.copy(Paths.get(src), Paths.get(dst), REPLACE_EXISTING);
    }
    // Result: ~85 ms for 100MB (uses transferTo internally)

    // Method 5: Memory-mapped
    static void copyMapped(String src, String dst) throws IOException {
        try (FileChannel in  = FileChannel.open(Paths.get(src), READ);
             FileChannel out = FileChannel.open(Paths.get(dst), CREATE, READ, WRITE)) {
            long size = in.size();
            out.truncate(size);
            MappedByteBuffer inMap  = in.map(READ_ONLY, 0, size);
            MappedByteBuffer outMap = out.map(READ_WRITE, 0, size);
            outMap.put(inMap);
        }
    }
    // Result: ~70 ms for 100MB (best for repeated access to same file)
}
```

### Approximate Performance Rankings (100MB file)

```
Method                    Time      Relative Speed
──────────────────────────────────────────────────
Byte-by-byte IO        ~25,000ms        1x
Chunked IO (64KB buf)    ~250ms       100x
NIO Channel Transfer      ~80ms       312x
NIO.2 Files.copy          ~85ms       294x
Memory-Mapped             ~70ms       357x
```

---

## 8. Real-World Use Cases

### Use Case 1: Log File Tail (NIO WatchService + Files.lines)

```java
public class LogTailer {
    public static void tail(Path logFile) throws Exception {
        WatchService watcher = logFile.getParent().getFileSystem().newWatchService();
        logFile.getParent().register(watcher, ENTRY_MODIFY);

        long[] position = {Files.size(logFile)}; // track read position

        while (true) {
            WatchKey key = watcher.take();
            for (WatchEvent<?> event : key.pollEvents()) {
                if (event.kind() == ENTRY_MODIFY) {
                    try (FileChannel channel = FileChannel.open(logFile, READ)) {
                        long newSize = channel.size();
                        if (newSize > position[0]) {
                            ByteBuffer buf = ByteBuffer.allocate((int)(newSize - position[0]));
                            channel.read(buf, position[0]);
                            buf.flip();
                            System.out.print(StandardCharsets.UTF_8.decode(buf));
                            position[0] = newSize;
                        }
                    }
                }
            }
            key.reset();
        }
    }
}
```

### Use Case 2: Large CSV Processing (Files.lines + Stream)

```java
public class LargeCSVProcessor {
    record Record(String name, int age, double salary) {}

    public static void process(Path csv) throws IOException {
        try (Stream<String> lines = Files.lines(csv, StandardCharsets.UTF_8)) {
            Map<String, DoubleSummaryStatistics> statsByDept = lines
                .skip(1) // skip header
                .filter(line -> !line.isBlank())
                .map(line -> line.split(","))
                .filter(parts -> parts.length >= 3)
                .collect(Collectors.groupingBy(
                    parts -> parts[0],
                    Collectors.summarizingDouble(parts -> Double.parseDouble(parts[2]))
                ));

            statsByDept.forEach((dept, stats) ->
                System.out.printf("%-15s avg=%.0f min=%.0f max=%.0f count=%d%n",
                    dept, stats.getAverage(), stats.getMin(), stats.getMax(), stats.getCount()));
        }
        // Streams lazily — only one line in memory at a time!
    }
}
```

### Use Case 3: File Synchronization (FileVisitor)

```java
public class FileSyncer {
    public static void sync(Path source, Path target) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                Path targetDir = target.resolve(source.relativize(dir));
                Files.createDirectories(targetDir);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Path targetFile = target.resolve(source.relativize(file));

                boolean shouldCopy = !Files.exists(targetFile)
                    || Files.size(targetFile) != attrs.size()
                    || !Files.getLastModifiedTime(targetFile).equals(attrs.lastModifiedTime());

                if (shouldCopy) {
                    Files.copy(file, targetFile, REPLACE_EXISTING, COPY_ATTRIBUTES);
                    System.out.println("Synced: " + file.getFileName());
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
```

---

## 9. Common Pitfalls & Best Practices

### ❌ Pitfall 1: Not closing streams
```java
// BAD — resource leak if exception thrown
FileInputStream fis = new FileInputStream("file.txt");
fis.read();
fis.close(); // never reached if read() throws

// GOOD — always use try-with-resources
try (FileInputStream fis = new FileInputStream("file.txt")) {
    fis.read();
} // auto-closed even if exception
```

### ❌ Pitfall 2: Using FileReader without charset
```java
// BAD — uses platform default charset (breaks on different OS)
BufferedReader br = new BufferedReader(new FileReader("data.txt"));

// GOOD — always specify charset
BufferedReader br = new BufferedReader(
    new InputStreamReader(new FileInputStream("data.txt"), StandardCharsets.UTF_8));

// BEST (Java 11+)
BufferedReader br = Files.newBufferedReader(Paths.get("data.txt"), StandardCharsets.UTF_8);
```

### ❌ Pitfall 3: Forgetting to flip() buffer
```java
// BAD — reads nothing because position is at end after writing
ByteBuffer buf = ByteBuffer.allocate(10);
buf.put("Hello".getBytes());
channel.write(buf); // writes 0 bytes! position == limit

// GOOD — always flip() before reading/writing from buffer
buf.flip();
channel.write(buf); // writes 5 bytes correctly
```

### ❌ Pitfall 4: Forgetting to reset WatchKey
```java
// BAD — no more events after first batch
WatchKey key = watcher.take();
// process events...
// forgot key.reset() — key becomes invalid, no more events!

// GOOD
WatchKey key = watcher.take();
for (WatchEvent<?> event : key.pollEvents()) { /* process */ }
boolean valid = key.reset(); // CRITICAL
if (!valid) break; // directory was deleted
```

### ❌ Pitfall 5: Loading entire large file into memory
```java
// BAD — OutOfMemoryError for 10GB file
List<String> lines = Files.readAllLines(Paths.get("huge.log"));

// GOOD — lazy streaming
try (Stream<String> lines = Files.lines(Paths.get("huge.log"))) {
    lines.filter(l -> l.contains("ERROR")).forEach(System.out::println);
}
```

### ❌ Pitfall 6: Ignoring partial writes in NIO
```java
// BAD — channel.write() may not write all bytes in one call
channel.write(buffer);

// GOOD — loop until buffer is fully written
while (buffer.hasRemaining()) {
    channel.write(buffer);
}
```

### ✅ Best Practices Summary

```
1. Always use try-with-resources for all I/O resources
2. Always specify charset explicitly (StandardCharsets.UTF_8)
3. Use BufferedReader/Writer for text; BufferedInputStream/OutputStream for bytes
4. Use Files.copy() / transferTo() for file copies — never manual byte loops
5. Use Files.lines() (streaming) instead of readAllLines() for large files
6. For large file processing: NIO channels + direct buffers
7. For configuration/small files: Files.readString() / Files.writeString() (Java 11+)
8. For network servers with many clients: NIO Selector with non-blocking channels
9. For background file operations: AsynchronousFileChannel
10. Prefer Path/Files (NIO.2) over File (java.io) for new code
```

---

## 10. Quick Decision Guide

```
What are you doing?
│
├── Reading/writing a small file (< 10MB)?
│   └── Files.readString() / Files.writeString()  [NIO.2, Java 11+]
│
├── Reading a large file line by line?
│   └── Files.lines(path) with Stream pipeline     [NIO.2]
│
├── Copying files?
│   └── Files.copy() or FileChannel.transferTo()   [NIO.2 / NIO]
│
├── Processing binary data / structured records?
│   └── FileChannel + ByteBuffer                   [NIO]
│
├── Need random access / seeking in file?
│   └── FileChannel with position()                [NIO]
│
├── Very large file (>1GB), reading repeatedly?
│   └── MappedByteBuffer (memory-mapped)            [NIO]
│
├── Watch directory for changes?
│   └── WatchService                               [NIO.2]
│
├── Non-blocking network server?
│   └── Selector + SocketChannel                   [NIO]
│
├── Background file I/O without blocking threads?
│   └── AsynchronousFileChannel                    [NIO.2]
│
└── Legacy code / simple scripts?
    └── BufferedReader + BufferedWriter             [java.io]
```

---

## 11. Comparison Summary Table

| Feature                    | java.io                      | java.nio (Channels)          | java.nio.file (NIO.2)        |
|----------------------------|------------------------------|------------------------------|------------------------------|
| **Introduced**             | Java 1 (1996)                | Java 4 (2002)                | Java 7 (2011)                |
| **Primary abstraction**    | Stream                       | Buffer + Channel             | Path + Files                 |
| **I/O model**              | Blocking only                | Blocking or Non-Blocking     | Blocking + Async             |
| **Thread per connection**  | Yes (required)               | No (Selector)                | No (CompletionHandler)       |
| **Random file access**     | RandomAccessFile             | FileChannel.position()       | Via FileChannel              |
| **Charset handling**       | Manual (error-prone)         | Manual                       | Built-in, explicit           |
| **Directory traversal**    | File.listFiles() (limited)   | N/A                          | Files.walk(), walkFileTree() |
| **File watching**          | Not supported                | Not supported                | WatchService                 |
| **Zero-copy transfer**     | No                           | transferTo() / transferFrom()| Files.copy() uses it         |
| **Memory-mapped files**    | No                           | MappedByteBuffer             | Via FileChannel              |
| **Scatter/gather I/O**     | No                           | Yes                          | No                           |
| **Exception handling**     | Checked IOException          | Checked IOException          | Checked IOException          |
| **File metadata/attrs**    | Basic (File class)           | N/A                          | Rich (BasicFileAttributes)   |
| **Symbolic link support**  | No                           | No                           | Yes (LinkOption)             |
| **ZIP as filesystem**      | No                           | No                           | Yes (FileSystems)            |
| **Best for**               | Simple text I/O, legacy code | Network servers, large files | Modern file operations       |

---

*Last updated: March 2026 | Covers Java 7 through Java 21*
