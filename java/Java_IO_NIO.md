# ☕ Java I/O and NIO — Deep Core Concepts

> A comprehensive reference guide covering the fundamentals, internals, and advanced patterns of Java's I/O and NIO systems.

---

## 📑 Table of Contents

1. [Overview & Architecture](#1-overview--architecture)
2. [Java Classic I/O (java.io)](#2-java-classic-io-javaio)
   - [Streams](#21-streams)
   - [Byte Streams](#22-byte-streams)
   - [Character Streams](#23-character-streams)
   - [Buffered Streams](#24-buffered-streams)
   - [Data Streams](#25-data-streams)
   - [Object Streams (Serialization)](#26-object-streams-serialization)
   - [File Class](#27-file-class)
3. [Java NIO (java.nio)](#3-java-nio-javanio)
   - [Buffers](#31-buffers)
   - [Channels](#32-channels)
   - [Selectors](#33-selectors)
   - [Charset & Encoding](#34-charset--encoding)
4. [Java NIO.2 (java.nio.file — Java 7+)](#4-java-nio2-javaniofile--java-7)
   - [Path & Paths](#41-path--paths)
   - [Files Utility Class](#42-files-utility-class)
   - [FileSystem & FileSystems](#43-filesystem--filesystems)
   - [Directory Watching (WatchService)](#44-directory-watching-watchservice)
   - [File Attributes](#45-file-attributes)
5. [Blocking vs Non-Blocking I/O](#5-blocking-vs-non-blocking-io)
6. [Memory-Mapped Files](#6-memory-mapped-files)
7. [Scatter / Gather I/O](#7-scatter--gather-io)
8. [Asynchronous I/O (AIO)](#8-asynchronous-io-aio)
9. [I/O Performance Patterns](#9-io-performance-patterns)
10. [Common Pitfalls & Best Practices](#10-common-pitfalls--best-practices)
11. [Quick Comparison Table](#11-quick-comparison-table)

---

## 1. Overview & Architecture

Java provides three generations of I/O APIs:

```
┌────────────────────────────────────────────────────────────┐
│                       JAVA I/O LAYERS                       │
├──────────────────┬─────────────────┬───────────────────────┤
│   java.io        │   java.nio      │   java.nio.file       │
│   (Java 1.0)     │   (Java 1.4)    │   (Java 7 — NIO.2)    │
│                  │                 │                        │
│  • Stream-based  │  • Buffer-based │  • Path API           │
│  • Blocking      │  • Channel-based│  • Files utility      │
│  • Simple API    │  • Non-blocking │  • WatchService       │
│                  │  • Selectors    │  • Async I/O (AIO)    │
└──────────────────┴─────────────────┴───────────────────────┘
```

### Core Design Philosophy

| Aspect | java.io | java.nio |
|--------|---------|----------|
| **Data unit** | Byte / Character (sequential) | Buffer (block) |
| **Mode** | Blocking (thread waits) | Blocking + Non-blocking |
| **Direction** | Unidirectional (read OR write) | Bidirectional (read AND write) |
| **Scalability** | One thread per connection | One thread, many connections |

---

## 2. Java Classic I/O (`java.io`)

### 2.1 Streams

A **stream** is a sequential flow of data. Java I/O is built around two abstract base classes:

```
                     InputStream (abstract)
                          │
         ┌────────────────┼──────────────────┐
   FileInputStream  ByteArrayInputStream  BufferedInputStream ...

                     OutputStream (abstract)
                          │
         ┌────────────────┼──────────────────┐
  FileOutputStream  ByteArrayOutputStream  BufferedOutputStream ...
```

**Key stream contract methods:**

```java
// InputStream
int  read()                    // reads 1 byte; returns -1 on EOF
int  read(byte[] buf)          // reads into buffer; returns bytes read
int  read(byte[] buf, int off, int len)
long skip(long n)              // skip n bytes
int  available()               // estimate of readable bytes
void close()                   // release resources
void mark(int readLimit)       // mark current position
void reset()                   // reset to last mark
boolean markSupported()

// OutputStream
void write(int b)              // writes 1 byte (low 8 bits of int)
void write(byte[] buf)
void write(byte[] buf, int off, int len)
void flush()                   // force pending bytes to destination
void close()
```

---

### 2.2 Byte Streams

Byte streams operate on **raw 8-bit bytes**. Every `InputStream`/`OutputStream` is a byte stream.

#### FileInputStream / FileOutputStream

```java
// Reading a file byte by byte (inefficient — for illustration)
try (InputStream in = new FileInputStream("input.bin")) {
    int byteVal;
    while ((byteVal = in.read()) != -1) {
        System.out.printf("%02X ", byteVal);
    }
}

// Writing bytes
try (OutputStream out = new FileOutputStream("output.bin", true /* append */)) {
    byte[] data = {0x48, 0x65, 0x6C, 0x6C, 0x6F};  // "Hello"
    out.write(data);
    out.flush();
}
```

#### ByteArrayInputStream / ByteArrayOutputStream

```java
// In-memory byte buffer as a stream source
byte[] source = "Hello, World!".getBytes(StandardCharsets.UTF_8);
try (InputStream in = new ByteArrayInputStream(source)) {
    // treat in-memory bytes like a file
}

// Capture stream output into byte array
try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
    baos.write("Data".getBytes());
    byte[] result = baos.toByteArray();  // extract raw bytes
    String text   = baos.toString("UTF-8");
}
```

#### PipedInputStream / PipedOutputStream

Used for inter-thread communication (producer/consumer pattern):

```java
PipedOutputStream pos = new PipedOutputStream();
PipedInputStream  pis = new PipedInputStream(pos);  // connected pair

Thread producer = new Thread(() -> {
    try (DataOutputStream dos = new DataOutputStream(pos)) {
        dos.writeInt(42);
    } catch (IOException e) { e.printStackTrace(); }
});

Thread consumer = new Thread(() -> {
    try (DataInputStream dis = new DataInputStream(pis)) {
        System.out.println("Received: " + dis.readInt()); // 42
    } catch (IOException e) { e.printStackTrace(); }
});
```

---

### 2.3 Character Streams

Character streams handle **Unicode text** via `Reader` and `Writer`. They use an underlying byte stream + character encoding.

```
                          Reader (abstract)
                               │
     ┌─────────────────────────┼──────────────────────┐
 InputStreamReader         FileReader          BufferedReader
 (bridge: bytes→chars)   (= FileInputStream    (line buffering)
                          + default encoding)
```

#### InputStreamReader — the encoding bridge

```java
// Always specify charset explicitly — never rely on platform default
try (Reader reader = new InputStreamReader(
        new FileInputStream("data.txt"),
        StandardCharsets.UTF_8)) {
    int c;
    while ((c = reader.read()) != -1) {
        System.out.print((char) c);
    }
}
```

#### FileReader / FileWriter

Convenience wrappers; **use with explicit charset (Java 11+)**:

```java
// Java 11+: specify encoding
try (FileReader fr = new FileReader("file.txt", StandardCharsets.UTF_8)) { ... }
try (FileWriter fw = new FileWriter("out.txt", StandardCharsets.UTF_8, true)) { ... }
```

#### BufferedReader — most common for text processing

```java
// Read line by line (classic)
try (BufferedReader br = new BufferedReader(
        new FileReader("data.csv", StandardCharsets.UTF_8))) {
    String line;
    while ((line = br.readLine()) != null) {
        String[] fields = line.split(",");
        // process fields
    }
}

// Java 8+: Stream<String>
try (BufferedReader br = Files.newBufferedReader(Path.of("data.csv"))) {
    br.lines()
      .filter(l -> !l.startsWith("#"))
      .map(String::trim)
      .forEach(System.out::println);
}
```

#### PrintWriter

```java
try (PrintWriter pw = new PrintWriter(
        new BufferedWriter(new FileWriter("log.txt", StandardCharsets.UTF_8)))) {
    pw.printf("Timestamp: %s%n", Instant.now());
    pw.println("Error: something went wrong");
    // NOTE: PrintWriter swallows IOExceptions! Always check pw.checkError()
    if (pw.checkError()) System.err.println("Write error occurred");
}
```

---

### 2.4 Buffered Streams

Without buffering, every `read()`/`write()` call is a **system call** — extremely slow.

```
Without buffer:  read() → syscall → read() → syscall → read() → syscall ...
With buffer:     read() → fill 8KB buffer (1 syscall) → serve from memory ...
```

```java
// Wrap any stream with a buffer
BufferedInputStream  bis = new BufferedInputStream(rawIn,  8192); // 8KB buffer
BufferedOutputStream bos = new BufferedOutputStream(rawOut, 8192);
BufferedReader       br  = new BufferedReader(reader, 8192);
BufferedWriter       bw  = new BufferedWriter(writer, 8192);
```

**Critical rule:** Always call `flush()` or `close()` on output streams — buffered data may not reach the destination otherwise.

```java
// Pattern: write → flush → close
try (BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream("out.bin"))) {
    bos.write(data);
    bos.flush(); // ensure bytes hit the OS buffer
} // close() also flushes
```

---

### 2.5 Data Streams

Read/write Java primitives in a machine-independent binary format:

```java
// Write primitives
try (DataOutputStream dos = new DataOutputStream(
        new BufferedOutputStream(new FileOutputStream("data.bin")))) {
    dos.writeInt(42);          // 4 bytes, big-endian
    dos.writeDouble(3.14159);  // 8 bytes, IEEE 754
    dos.writeUTF("Hello");     // 2-byte length prefix + modified UTF-8
    dos.writeBoolean(true);    // 1 byte (1 = true)
}

// Read back — MUST read in same order and types
try (DataInputStream dis = new DataInputStream(
        new BufferedInputStream(new FileInputStream("data.bin")))) {
    int    i = dis.readInt();
    double d = dis.readDouble();
    String s = dis.readUTF();
    boolean b = dis.readBoolean();
}
```

> ⚠️ `writeUTF` uses a modified UTF-8 that differs from standard UTF-8 for characters outside the BMP. Max string length: 65,535 bytes.

---

### 2.6 Object Streams (Serialization)

Serialize entire Java objects to binary form:

```java
// Class must implement Serializable
public class Person implements Serializable {
    private static final long serialVersionUID = 1L;  // version control
    private String name;
    private int    age;
    private transient String password;  // NOT serialized
}

// Serialize
try (ObjectOutputStream oos = new ObjectOutputStream(
        new BufferedOutputStream(new FileOutputStream("person.ser")))) {
    oos.writeObject(new Person("Alice", 30));
}

// Deserialize
try (ObjectInputStream ois = new ObjectInputStream(
        new BufferedInputStream(new FileInputStream("person.ser")))) {
    Person p = (Person) ois.readObject();
}
```

**Custom serialization:**
```java
public class CustomPerson implements Serializable {
    private static final long serialVersionUID = 1L;
    private String name;

    private void writeObject(ObjectOutputStream oos) throws IOException {
        oos.defaultWriteObject();           // write non-transient fields
        oos.writeUTF(encryptedPassword());  // custom extra data
    }

    private void readObject(ObjectInputStream ois)
            throws IOException, ClassNotFoundException {
        ois.defaultReadObject();
        this.password = decrypt(ois.readUTF());
    }
}
```

> ⚠️ Serialization pitfalls: tight class coupling to binary format, security risks (`readObject` as RCE vector), poor performance. Prefer JSON/Protobuf/Avro for new code.

---

### 2.7 File Class

The legacy `java.io.File` (pre-NIO.2):

```java
File f = new File("/home/user/data.txt");

f.exists();          // file or dir exists?
f.isFile();          // is a regular file?
f.isDirectory();     // is a directory?
f.length();          // size in bytes
f.lastModified();    // epoch millis
f.canRead();         // readable?
f.canWrite();        // writable?

f.createNewFile();   // atomically creates if not exists
f.delete();          // delete file or empty dir
f.renameTo(dest);    // move/rename (not atomic across filesystems!)
f.mkdirs();          // create dir + all parents

// List directory
String[] names = f.list();           // filenames
File[]   files = f.listFiles();      // File objects
File[] filtered = f.listFiles(
    (dir, name) -> name.endsWith(".log")); // filtered
```

> **Prefer NIO.2 `Path`/`Files`** for new code — richer API, better error handling, symlink support.

---

## 3. Java NIO (`java.nio`)

NIO introduces three core abstractions: **Buffers**, **Channels**, and **Selectors**.

```
   Channel ←──────────────────→ Channel
     ↑  ↓                        ↑  ↓
   Buffer                      Buffer
     ↑
   Selector (monitors multiple channels)
```

---

### 3.1 Buffers

A `Buffer` is a **fixed-capacity container** for primitive data with four key properties:

```
  Buffer internals:
  ┌───┬───┬───┬───┬───┬───┬───┬───┬───┬───┐
  │ 0 │ 1 │ 2 │ 3 │ 4 │ 5 │ 6 │ 7 │ 8 │ 9 │  (capacity = 10)
  └───┴───┴───┴───┴───┴───┴───┴───┴───┴───┘
        ↑                   ↑               ↑
      position            limit          capacity
```

| Property | Description |
|----------|-------------|
| `capacity` | Total number of elements (fixed at creation) |
| `limit` | First element that should not be read/written |
| `position` | Next element to be read or written |
| `mark` | Saved position for `reset()` |

**Invariant:** `0 ≤ mark ≤ position ≤ limit ≤ capacity`

#### Buffer lifecycle

```java
ByteBuffer buf = ByteBuffer.allocate(1024);  // capacity=1024, pos=0, limit=1024

// WRITE mode: put data into buffer
buf.put((byte) 'H');
buf.put((byte) 'i');
// pos=2, limit=1024

// Flip: switch from write mode to read mode
buf.flip();
// pos=0, limit=2  ← limit becomes previous position

// READ mode: get data from buffer
byte b1 = buf.get();  // 'H', pos=1
byte b2 = buf.get();  // 'i', pos=2

// Rewind: re-read from beginning
buf.rewind();
// pos=0, limit=2 (unchanged)

// Clear: prepare for new write cycle
buf.clear();
// pos=0, limit=1024 (reset to capacity) — data NOT erased!

// Compact: move unread bytes to front, ready to write more
buf.compact();
// remaining bytes shifted to [0..n], pos=n, limit=capacity
```

#### Buffer types

```java
ByteBuffer    bb  = ByteBuffer.allocate(1024);         // heap memory
ByteBuffer    dbb = ByteBuffer.allocateDirect(1024);   // native/off-heap memory
ShortBuffer   sb  = ShortBuffer.allocate(512);
IntBuffer     ib  = IntBuffer.allocate(256);
LongBuffer    lb  = LongBuffer.allocate(128);
FloatBuffer   fb  = FloatBuffer.allocate(256);
DoubleBuffer  db  = DoubleBuffer.allocate(128);
CharBuffer    cb  = CharBuffer.allocate(512);
```

#### Direct vs Heap Buffers

```java
// Heap buffer  — backed by Java byte array, GC-managed
ByteBuffer heap   = ByteBuffer.allocate(4096);

// Direct buffer — native memory, bypasses JVM heap
// Faster for I/O (no copy between heap and OS), higher allocation cost
ByteBuffer direct = ByteBuffer.allocateDirect(4096);
```

| | Heap Buffer | Direct Buffer |
|---|---|---|
| **Memory** | JVM heap | Native (off-heap) |
| **I/O perf** | Copy needed (heap → native) | Zero-copy path |
| **Allocation** | Fast | Slow (OS call) |
| **GC** | Normal GC | Freed by GC (unpredictable) |
| **Best for** | Short-lived, many small buffers | Long-lived, large I/O |

#### ByteBuffer typed views

```java
ByteBuffer bb = ByteBuffer.allocate(16);
bb.putInt(42).putDouble(3.14).putShort((short) 7).flip();

IntBuffer    ib = bb.asIntBuffer();     // view as ints
DoubleBuffer db = bb.asDoubleBuffer();  // view as doubles
// Views share the underlying buffer data
```

---

### 3.2 Channels

Channels are **bidirectional conduits** for I/O — unlike streams, you can read AND write the same channel.

```
Channel hierarchy:
Channel (interface)
├── ReadableByteChannel     ← can read bytes
├── WritableByteChannel     ← can write bytes
├── ByteChannel             ← both
│   └── SeekableByteChannel ← random access (position/size/truncate)
│       └── FileChannel
├── NetworkChannel
│   └── SelectableChannel
│       ├── SocketChannel         (TCP client)
│       ├── ServerSocketChannel   (TCP server)
│       └── DatagramChannel       (UDP)
└── AsynchronousChannel
    ├── AsynchronousFileChannel
    └── AsynchronousSocketChannel
```

#### FileChannel

```java
// Open via Files (preferred)
try (FileChannel fc = FileChannel.open(
        Path.of("data.bin"),
        StandardOpenOption.READ,
        StandardOpenOption.WRITE,
        StandardOpenOption.CREATE)) {

    // READ: channel → buffer
    ByteBuffer buf = ByteBuffer.allocateDirect(4096);
    int bytesRead;
    while ((bytesRead = fc.read(buf)) != -1) {
        buf.flip();
        while (buf.hasRemaining()) {
            process(buf.get());
        }
        buf.clear();
    }

    // WRITE: buffer → channel
    ByteBuffer data = ByteBuffer.wrap("Hello NIO".getBytes(StandardCharsets.UTF_8));
    while (data.hasRemaining()) {
        fc.write(data);  // may not write all bytes in one call!
    }

    // Random access
    fc.position(100);           // seek to byte 100
    long size = fc.size();      // file size
    fc.truncate(1024);          // truncate to 1024 bytes

    // Force data to disk
    fc.force(true);  // true = also sync metadata (timestamps)
}
```

#### Channel-to-Channel transfer (zero-copy)

```java
try (FileChannel src  = FileChannel.open(Path.of("source.mp4"), READ);
     FileChannel dest = FileChannel.open(Path.of("dest.mp4"),   WRITE, CREATE)) {

    // OS-level zero-copy (sendfile on Linux)
    long transferred = src.transferTo(0, src.size(), dest);
    // or equivalently:
    dest.transferFrom(src, 0, src.size());
}
```

> **Zero-copy** means the data goes `disk → kernel buffer → disk` without passing through user space. Critical for high-throughput file serving.

#### SocketChannel (non-blocking TCP)

```java
// Non-blocking client
SocketChannel client = SocketChannel.open();
client.configureBlocking(false);
client.connect(new InetSocketAddress("example.com", 8080));

while (!client.finishConnect()) {
    // do other work
}

// Non-blocking server
ServerSocketChannel server = ServerSocketChannel.open();
server.bind(new InetSocketAddress(8080));
server.configureBlocking(false);

SocketChannel conn = server.accept();  // returns null if no connection waiting
if (conn != null) {
    conn.configureBlocking(false);
    // register with selector...
}
```

---

### 3.3 Selectors

A `Selector` allows **one thread to multiplex over many channels** — the foundation of scalable NIO servers.

```
         Thread
           │
       Selector
      /    |    \
    Ch1   Ch2   Ch3   ...   (non-blocking channels)
```

#### SelectionKey interest ops

| Op | Constant | Meaning |
|----|----------|---------|
| `OP_CONNECT` | `1` | Channel has finished (or failed) connecting |
| `OP_ACCEPT` | `16` | Server channel ready to accept connection |
| `OP_READ` | `4` | Channel has data to read |
| `OP_WRITE` | `8` | Channel is ready to write (buffer not full) |

```java
Selector selector = Selector.open();

// Register channels with selector
ServerSocketChannel server = ServerSocketChannel.open();
server.bind(new InetSocketAddress(8080));
server.configureBlocking(false);  // MUST be non-blocking for selector
SelectionKey serverKey = server.register(selector, SelectionKey.OP_ACCEPT);

// Event loop
while (true) {
    int readyCount = selector.select();  // blocks until ≥1 channel ready
    // selector.select(1000);           // timeout in ms
    // selector.selectNow();            // non-blocking poll

    if (readyCount == 0) continue;

    Set<SelectionKey> selectedKeys = selector.selectedKeys();
    Iterator<SelectionKey> iter = selectedKeys.iterator();

    while (iter.hasNext()) {
        SelectionKey key = iter.next();
        iter.remove();  // CRITICAL: must remove manually

        if (!key.isValid()) continue;

        if (key.isAcceptable()) {
            ServerSocketChannel srv = (ServerSocketChannel) key.channel();
            SocketChannel client = srv.accept();
            client.configureBlocking(false);
            client.register(selector, SelectionKey.OP_READ,
                            ByteBuffer.allocateDirect(1024)); // attach buffer
        }
        else if (key.isReadable()) {
            SocketChannel ch  = (SocketChannel) key.channel();
            ByteBuffer    buf = (ByteBuffer) key.attachment();
            int read = ch.read(buf);
            if (read == -1) {
                ch.close();  // client disconnected
            } else {
                buf.flip();
                echo(ch, buf);
                buf.compact();
            }
        }
        else if (key.isWritable()) {
            // channel ready to accept writes
        }
    }
}
```

---

### 3.4 Charset & Encoding

```java
// List all available charsets
Charset.availableCharsets().forEach((name, cs) -> System.out.println(name));

Charset utf8  = StandardCharsets.UTF_8;
Charset utf16 = StandardCharsets.UTF_16;
Charset latin = StandardCharsets.ISO_8859_1;

// Encode String → ByteBuffer
CharBuffer  chars = CharBuffer.wrap("Hello ☕");
ByteBuffer  bytes = utf8.encode(chars);

// Decode ByteBuffer → CharBuffer
CharBuffer decoded = utf8.decode(bytes);
String str = decoded.toString();

// Encoder with error handling
CharsetEncoder encoder = utf8.newEncoder()
    .onMalformedInput(CodingErrorAction.REPLACE)
    .onUnmappableCharacter(CodingErrorAction.IGNORE);

CharsetDecoder decoder = utf8.newDecoder()
    .onMalformedInput(CodingErrorAction.REPORT);  // throws on bad bytes
```

---

## 4. Java NIO.2 (`java.nio.file` — Java 7+)

NIO.2 replaces the old `java.io.File` with a vastly more capable API.

---

### 4.1 Path & Paths

```java
// Creation
Path p1 = Path.of("/home/user/docs/file.txt");       // Java 11+
Path p2 = Paths.get("/home/user/docs/file.txt");      // Java 7+
Path p3 = Paths.get("/home", "user", "docs", "file.txt");
Path rel = Path.of("../sibling/file.txt");

// Components
p1.getFileName();          // file.txt
p1.getParent();            // /home/user/docs
p1.getRoot();              // /
p1.getNameCount();         // 4 (home, user, docs, file.txt)
p1.getName(2);             // docs (zero-indexed)
p1.subpath(1, 3);          // user/docs

// Manipulation
p1.resolve("other.txt");           // /home/user/docs/other.txt
p1.resolveSibling("other.txt");    // /home/user/docs/other.txt
p1.relativize(Path.of("/home/user/pics/img.png"));  // ../../pics/img.png
p1.normalize();            // remove ./ and ../
p1.toAbsolutePath();       // make absolute (uses CWD)
p1.toRealPath();           // resolve symlinks + normalize (throws if not exists)
p1.toFile();               // convert to java.io.File (legacy interop)

// Comparison
p1.startsWith("/home");
p1.endsWith("file.txt");
p1.equals(p2);
```

---

### 4.2 Files Utility Class

The `Files` class provides static methods for virtually all file operations.

```java
// ─── Existence & Type ───────────────────────────────────────
Files.exists(path)
Files.notExists(path)
Files.isRegularFile(path)
Files.isDirectory(path)
Files.isSymbolicLink(path)
Files.isReadable(path)
Files.isWritable(path)
Files.isExecutable(path)

// ─── Create ─────────────────────────────────────────────────
Files.createFile(path);                              // atomic create
Files.createDirectory(path);                         // one level
Files.createDirectories(path);                       // all parents
Files.createTempFile(dir, "prefix", ".tmp");
Files.createTempDirectory(dir, "tmpdir");
Files.createSymbolicLink(link, target);
Files.createLink(link, existing);                    // hard link

// ─── Copy & Move ────────────────────────────────────────────
Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING,
                       StandardCopyOption.COPY_ATTRIBUTES);
Files.copy(inputStream, dest);  // stream → file
Files.copy(src, outputStream);  // file → stream
Files.move(src, dest, StandardCopyOption.ATOMIC_MOVE);

// ─── Delete ─────────────────────────────────────────────────
Files.delete(path);             // throws NoSuchFileException
Files.deleteIfExists(path);     // returns boolean

// ─── Read ───────────────────────────────────────────────────
byte[]       bytes = Files.readAllBytes(path);
String       text  = Files.readString(path, StandardCharsets.UTF_8);  // Java 11+
List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
Stream<String> lineStream = Files.lines(path, StandardCharsets.UTF_8); // lazy

BufferedReader br = Files.newBufferedReader(path, StandardCharsets.UTF_8);
InputStream    is = Files.newInputStream(path, StandardOpenOption.READ);

// ─── Write ──────────────────────────────────────────────────
Files.write(path, bytes);
Files.write(path, lines, StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
Files.writeString(path, "Hello", StandardCharsets.UTF_8);  // Java 11+

BufferedWriter bw = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
OutputStream   os = Files.newOutputStream(path, StandardOpenOption.WRITE);

// ─── Metadata ───────────────────────────────────────────────
long   size     = Files.size(path);
Object owner    = Files.getOwner(path);
Set<PosixFilePermission> perms = Files.getPosixFilePermissions(path);
Files.setLastModifiedTime(path, FileTime.fromMillis(System.currentTimeMillis()));

// ─── Directory Listing ──────────────────────────────────────
try (Stream<Path> stream = Files.list(path)) {         // one level
    stream.filter(Files::isRegularFile)
          .forEach(System.out::println);
}
try (Stream<Path> stream = Files.walk(path)) {         // recursive
    stream.filter(p -> p.toString().endsWith(".java"))
          .forEach(System.out::println);
}
try (Stream<Path> stream = Files.find(path, 10,       // maxDepth=10
        (p, attr) -> attr.isRegularFile() && attr.size() > 1024)) {
    stream.forEach(System.out::println);
}
```

---

### 4.3 FileSystem & FileSystems

```java
// Default filesystem (OS)
FileSystem fs = FileSystems.getDefault();

// ZIP/JAR as a filesystem (Java 7+)
URI zipUri = URI.create("jar:file:/path/to/archive.zip");
try (FileSystem zip = FileSystems.newFileSystem(zipUri, Map.of("create", "true"))) {
    Path inside = zip.getPath("/folder/file.txt");
    Files.write(inside, "content".getBytes());
}

// Glob patterns
PathMatcher glob = fs.getPathMatcher("glob:**/*.{java,class}");
PathMatcher regex = fs.getPathMatcher("regex:.*\\.(log|txt)$");

Files.walk(Path.of("."))
     .filter(glob::matches)
     .forEach(System.out::println);
```

---

### 4.4 Directory Watching (WatchService)

Monitor filesystem changes without polling:

```java
WatchService watcher = FileSystems.getDefault().newWatchService();

Path dir = Path.of("/var/log");
dir.register(watcher,
    StandardWatchEventKinds.ENTRY_CREATE,
    StandardWatchEventKinds.ENTRY_MODIFY,
    StandardWatchEventKinds.ENTRY_DELETE,
    StandardWatchEventKinds.OVERFLOW);   // events dropped

// Event loop (run in background thread)
while (true) {
    WatchKey key = watcher.take();  // blocks until events available
    // watcher.poll(5, TimeUnit.SECONDS); // with timeout

    for (WatchEvent<?> event : key.pollEvents()) {
        WatchEvent.Kind<?> kind = event.kind();

        if (kind == StandardWatchEventKinds.OVERFLOW) continue;

        @SuppressWarnings("unchecked")
        WatchEvent<Path> ev   = (WatchEvent<Path>) event;
        Path             name = ev.context();   // relative filename
        Path             full = dir.resolve(name);

        System.out.printf("Event: %-10s → %s%n", kind.name(), full);
    }

    boolean valid = key.reset();  // MUST reset, or no more events
    if (!valid) break;            // directory deleted
}
```

---

### 4.5 File Attributes

```java
// Basic attributes (all filesystems)
BasicFileAttributes basic = Files.readAttributes(path, BasicFileAttributes.class);
basic.creationTime();
basic.lastModifiedTime();
basic.lastAccessTime();
basic.size();
basic.isRegularFile();
basic.isDirectory();
basic.isSymbolicLink();
basic.fileKey();   // unique ID (inode on Unix)

// POSIX attributes (Unix/Linux/macOS)
PosixFileAttributes posix = Files.readAttributes(path, PosixFileAttributes.class);
posix.owner();
posix.group();
posix.permissions();  // Set<PosixFilePermission>

// DOS attributes (Windows)
DosFileAttributes dos = Files.readAttributes(path, DosFileAttributes.class);
dos.isHidden();
dos.isReadOnly();
dos.isSystem();
dos.isArchive();

// Setting permissions (POSIX)
Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwxr-x---");
Files.setPosixFilePermissions(path, perms);

// Set as FileAttribute at creation time
FileAttribute<Set<PosixFilePermission>> attr = PosixFilePermissions.asFileAttribute(perms);
Files.createFile(path, attr);
```

---

## 5. Blocking vs Non-Blocking I/O

### Blocking I/O (java.io / default NIO)

```
Thread 1: ──[ read() waits... ]────────────────[ data ready ]──[ process ]──
Thread 2: ──────[ read() waits... ]──────────────────────────[ data ready ]──
Thread N: ──────────────────[ read() waits... ]──────────────────────────────
```

- Simple programming model
- Each connection requires a thread
- Idle threads consume memory (~512KB stack each)
- Suitable for low-to-medium concurrency

### Non-Blocking I/O (NIO with Selector)

```
Thread:  ──[ select() ]──[ process ready ]──[ select() ]──[ process ready ]──
                 ↕               ↕
           Ch1,Ch2,Ch3     Ch2 readable
```

- One thread handles hundreds/thousands of connections
- More complex programming model (state machines)
- Used by frameworks: Netty, Vert.x, Undertow

```java
// Non-blocking read: returns immediately
channel.configureBlocking(false);
ByteBuffer buf = ByteBuffer.allocateDirect(1024);
int n = channel.read(buf);  // returns 0 if nothing available, -1 if EOF
```

### Comparison

| | Blocking | Non-Blocking |
|---|---|---|
| **Threads** | One per connection | One per CPU core |
| **Memory** | High (thread stacks) | Low |
| **Throughput** | Limited | High (C10K+) |
| **Complexity** | Low | High |
| **Use case** | Server with few clients | High-concurrency server |

---

## 6. Memory-Mapped Files

Map a file (or region) directly into virtual address space — the OS manages paging:

```java
try (FileChannel fc = FileChannel.open(Path.of("large.dat"), READ, WRITE)) {

    // Map entire file into memory
    MappedByteBuffer mbb = fc.map(
        FileChannel.MapMode.READ_WRITE,  // READ_ONLY or PRIVATE also available
        0,           // start offset
        fc.size()    // length to map
    );

    // Read directly — no explicit read() call needed
    int firstInt = mbb.getInt(0);

    // Write directly — OS writes to file asynchronously
    mbb.putInt(0, 42);

    // Force pages to disk immediately
    mbb.force();

    // Check if data is loaded in RAM
    mbb.load();    // hint to load all pages
    mbb.isLoaded();
}
```

**How it works:**
```
Process virtual memory:
   0x0000 ──┬──────────────── code
            ├──────────────── heap
            ├──────────────── mmap region  ←── mapped file
            └──────────────── stack

Page fault on access → OS loads page from file → transparent access
```

**Use cases:**
- Large file random access (databases, indices)
- IPC between processes (shared memory via same file)
- Parsing large binary formats (no explicit buffering needed)

> ⚠️ `MappedByteBuffer` is not released until GC — can prevent file deletion on Windows. Use `Cleaner` API or reflection to force unmap.

---

## 7. Scatter / Gather I/O

Read into multiple buffers (scatter) or write from multiple buffers (gather) in one system call:

```java
try (SocketChannel ch = SocketChannel.open(new InetSocketAddress("host", 80))) {

    // ── SCATTER READ ─────────────────────────────────────
    ByteBuffer header  = ByteBuffer.allocate(128);
    ByteBuffer payload = ByteBuffer.allocateDirect(4096);
    ByteBuffer trailer = ByteBuffer.allocate(16);

    ByteBuffer[] buffers = { header, payload, trailer };
    long totalRead = ch.read(buffers);
    // OS fills header first, then payload, then trailer

    // Process each part
    header.flip();
    String hdrs = StandardCharsets.UTF_8.decode(header).toString();

    // ── GATHER WRITE ─────────────────────────────────────
    ByteBuffer statusLine  = ByteBuffer.wrap("HTTP/1.1 200 OK\r\n".getBytes());
    ByteBuffer responseHdr = ByteBuffer.wrap("Content-Type: text/plain\r\n\r\n".getBytes());
    ByteBuffer body        = ByteBuffer.wrap("Hello!".getBytes());

    ch.write(new ByteBuffer[]{ statusLine, responseHdr, body });
    // Single writev() syscall — more efficient than three separate write() calls
}
```

**Benefit:** Minimizes syscall overhead and enables zero-copy protocol framing.

---

## 8. Asynchronous I/O (AIO)

Java 7+ provides truly async I/O via `AsynchronousFileChannel` and `AsynchronousSocketChannel`.

### Two programming models:

#### 1. Future-based

```java
AsynchronousFileChannel afc = AsynchronousFileChannel.open(
    Path.of("large.txt"), StandardOpenOption.READ);

ByteBuffer buf = ByteBuffer.allocate(1024);
Future<Integer> future = afc.read(buf, 0);  // returns immediately

// Do other work...
doSomethingElse();

// Block when you need the result
int bytesRead = future.get(5, TimeUnit.SECONDS);
buf.flip();
```

#### 2. CompletionHandler-based (callback)

```java
AsynchronousFileChannel afc = AsynchronousFileChannel.open(
    Path.of("large.txt"), StandardOpenOption.READ);

ByteBuffer buf = ByteBuffer.allocate(1024);

afc.read(buf, 0, buf, new CompletionHandler<Integer, ByteBuffer>() {
    @Override
    public void completed(Integer bytesRead, ByteBuffer attachment) {
        attachment.flip();
        // process data on thread pool thread
        String content = StandardCharsets.UTF_8.decode(attachment).toString();
        System.out.println("Read: " + content);
    }

    @Override
    public void failed(Throwable exc, ByteBuffer attachment) {
        System.err.println("Read failed: " + exc.getMessage());
    }
});

// Main thread is free immediately
```

#### Async TCP Server

```java
AsynchronousServerSocketChannel server = AsynchronousServerSocketChannel
    .open()
    .bind(new InetSocketAddress(8080));

server.accept(null, new CompletionHandler<AsynchronousSocketChannel, Void>() {
    @Override
    public void completed(AsynchronousSocketChannel client, Void attachment) {
        server.accept(null, this);  // accept next connection

        ByteBuffer buf = ByteBuffer.allocate(4096);
        client.read(buf, buf, new CompletionHandler<Integer, ByteBuffer>() {
            @Override
            public void completed(Integer bytes, ByteBuffer b) {
                b.flip();
                // echo back
                client.write(b, b, new CompletionHandler<Integer, ByteBuffer>() {
                    @Override public void completed(Integer n, ByteBuffer bb) {}
                    @Override public void failed(Throwable e, ByteBuffer bb) {}
                });
            }
            @Override public void failed(Throwable e, ByteBuffer b) {}
        });
    }

    @Override
    public void failed(Throwable exc, Void attachment) {
        exc.printStackTrace();
    }
});
```

---

## 9. I/O Performance Patterns

### Pattern 1: Always Buffer

```java
// ❌ Unbuffered — one syscall per byte
InputStream  raw  = new FileInputStream("file.txt");
OutputStream rawO = new FileOutputStream("out.txt");

// ✅ Buffered — batch syscalls
InputStream  buf  = new BufferedInputStream(raw, 65536);  // 64KB
OutputStream bufO = new BufferedOutputStream(rawO, 65536);
```

### Pattern 2: Use Direct Buffers for Network/File I/O

```java
// ✅ For sustained high-throughput I/O
ByteBuffer direct = ByteBuffer.allocateDirect(64 * 1024);

// ❌ Heap buffers require extra copy: heap → native → OS
ByteBuffer heap = ByteBuffer.allocate(64 * 1024);
```

### Pattern 3: Channel Transfer for File Copies

```java
// ✅ Zero-copy — OS level transfer
try (FileChannel src  = FileChannel.open(srcPath,  READ);
     FileChannel dest = FileChannel.open(destPath, WRITE, CREATE)) {
    src.transferTo(0, src.size(), dest);
}

// ❌ User-space copy — needless data movement
byte[] buf = new byte[8192];
int n;
while ((n = srcStream.read(buf)) != -1) destStream.write(buf, 0, n);
```

### Pattern 4: Memory Map for Random Access

```java
// ✅ Random access without seek overhead
MappedByteBuffer mmap = fc.map(READ_ONLY, 0, fc.size());
int valueAt4KB = mmap.getInt(4096);      // instant

// ❌ Sequential seek on stream
fis.skip(4096);
DataInputStream dis = new DataInputStream(fis);
int valueAt4KB = dis.readInt();          // slow for many random accesses
```

### Pattern 5: Reuse Buffers

```java
// ✅ Buffer pool — avoid GC pressure
Queue<ByteBuffer> pool = new ConcurrentLinkedQueue<>();

ByteBuffer acquire() {
    ByteBuffer buf = pool.poll();
    return buf != null ? buf : ByteBuffer.allocateDirect(4096);
}

void release(ByteBuffer buf) {
    buf.clear();
    pool.offer(buf);
}
```

---

## 10. Common Pitfalls & Best Practices

### ⚠️ Pitfall 1: Not closing resources

```java
// ❌ Resource leak if exception thrown
FileInputStream fis = new FileInputStream("file.txt");
process(fis);
fis.close();  // never reached if process() throws!

// ✅ Always use try-with-resources (Java 7+)
try (FileInputStream fis = new FileInputStream("file.txt")) {
    process(fis);
}  // auto-closed even on exception
```

### ⚠️ Pitfall 2: Forgetting to flip the buffer

```java
ByteBuffer buf = ByteBuffer.allocate(1024);
channel.read(buf);
// buf.flip();  ← MUST flip before reading!
while (buf.hasRemaining()) {
    process(buf.get());  // reads position 0..limit without flip = WRONG
}
```

### ⚠️ Pitfall 3: Partial writes

```java
// ❌ May not write all bytes in one call
channel.write(buffer);

// ✅ Loop until all written
while (buffer.hasRemaining()) {
    channel.write(buffer);
}
```

### ⚠️ Pitfall 4: Not removing from selectedKeys

```java
while (iter.hasNext()) {
    SelectionKey key = iter.next();
    iter.remove();  // ← MUST remove! Selector never removes automatically
    // process key...
}
```

### ⚠️ Pitfall 5: Relying on platform default charset

```java
// ❌ Platform-dependent — breaks on Windows vs Linux
new FileReader("file.txt");
new FileWriter("out.txt");
"hello".getBytes();

// ✅ Always explicit
new FileReader("file.txt", StandardCharsets.UTF_8);
"hello".getBytes(StandardCharsets.UTF_8);
```

### ⚠️ Pitfall 6: File.renameTo() is not reliable

```java
// ❌ Returns false on failure with no exception; not atomic across filesystems
boolean ok = srcFile.renameTo(destFile);

// ✅ Use Files.move() — throws descriptive exception; ATOMIC_MOVE on same fs
Files.move(srcPath, destPath, StandardCopyOption.ATOMIC_MOVE);
```

### ✅ Best Practice: OpenOptions

```java
// Be explicit about open intent
FileChannel.open(path,
    StandardOpenOption.CREATE,        // create if not exists
    StandardOpenOption.WRITE,
    StandardOpenOption.TRUNCATE_EXISTING,   // overwrite content
    StandardOpenOption.DSYNC            // sync data (not metadata) on write
);
```

---

## 11. Quick Comparison Table

| Feature | `java.io` | `java.nio` | `java.nio.file` |
|---------|-----------|------------|-----------------|
| **API style** | Stream (sequential) | Buffer + Channel | Path + Files |
| **Blocking** | Always | Optional | N/A (meta-API) |
| **Bidirectional** | No | Yes | Yes (via Channel) |
| **Random access** | Limited (skip) | Yes (position) | Yes |
| **Memory map** | No | Yes | Via FileChannel |
| **Async I/O** | No | Yes (AIO) | Yes (AIO) |
| **FS watching** | No | No | Yes (WatchService) |
| **Symlink support** | Partial | Partial | Full |
| **Error detail** | Poor | Medium | Rich (typed exceptions) |
| **Performance** | Medium (buffered) | High | High |
| **Complexity** | Low | High | Medium |

---

## 📚 Further Reading

- [Java Platform SE — java.io](https://docs.oracle.com/en/java/docs/api/java.base/java/io/package-summary.html)
- [Java Platform SE — java.nio](https://docs.oracle.com/en/java/docs/api/java.base/java/nio/package-summary.html)
- [Java Platform SE — java.nio.file](https://docs.oracle.com/en/java/docs/api/java.base/java/nio/file/package-summary.html)
- *Java NIO* by Ron Hitchens (O'Reilly)
- *Java I/O* by Elliotte Rusty Harold (O'Reilly)

---

*Generated for Java 17+ | Last updated: 2026*
