# 04 — Page Cache & Zero-Copy

> How Kafka achieves extreme throughput by using OS RAM and bypassing the JVM for data transfer.

---

## The OS Page Cache

### What It Is

The Linux OS page cache is a region of physical RAM the kernel uses to cache file contents. When you read or write a file, the kernel caches the data in RAM pages. Subsequent reads of the same data are served from RAM — no disk I/O.

```
Physical Memory layout on a 32 GB Kafka broker:
┌─────────────────────────────────────────────────────────────────┐
│ JVM heap: 6 GB                                                  │
│   - Network receive/send buffers                                 │
│   - Request handler thread stacks                               │
│   - Metadata objects (topic configs, ISR sets, etc.)           │
│   - No message data here (by design)                           │
├─────────────────────────────────────────────────────────────────┤
│ OS Page Cache: ~24 GB  ← Kafka's REAL cache                    │
│   - .log file contents (message data)                           │
│   - .index file contents (memory-mapped)                        │
│   - .timeindex file contents (memory-mapped)                    │
│                                                                 │
│   Managed by the kernel — no GC, no JVM involvement            │
└─────────────────────────────────────────────────────────────────┘
```

### Why Kafka Chose Page Cache Over JVM Heap

#### 1. No Garbage Collection Overhead
A 24 GB JVM heap has significant GC pressure. Stop-the-world GC pauses grow with heap size — a 24 GB heap can produce multi-second pauses with G1GC.

```
JVM heap cache:
  Pause every few minutes: 0ms → 2000ms → resume
  Producers see timeouts, consumers see rebalances, ISR members fall behind

Page cache:
  Zero GC — managed by kernel
  Zero impact on application threads
```

#### 2. Cache Survives JVM Restart
When the Kafka broker JVM restarts, the JVM heap is wiped. The OS page cache is NOT cleared — it persists across JVM restarts.

```
Without page cache strategy:
  Broker restarts after crash
  Kafka JVM cache is empty
  First 30 minutes: every consumer read causes disk I/O
  Gradual warm-up period of heavy disk load

With page cache strategy:
  Broker restarts after crash
  OS page cache still has recent message data
  Consumers served immediately from RAM
  Zero warm-up period
```

#### 3. No Double-Buffering
Any file read already goes through the OS page cache. If Kafka also maintained a JVM heap cache, every byte would be in memory twice.

```
JVM heap + page cache (double-buffering):
  Byte stored in page cache: 1 copy in OS RAM
  Byte also in JVM cache:    1 copy in JVM heap
  Total: 2 copies of same data

Page cache only (Kafka's approach):
  Byte in page cache: 1 copy in OS RAM
  Total: 1 copy
  → 2x more effective use of memory
```

#### 4. OS Prefetching for Sequential Access
The kernel's readahead algorithm is tuned for sequential access. When Kafka reads from a log file, the OS automatically prefetches upcoming pages into page cache before they're needed.

```
Consumer reading sequentially:
  Reads offset 1000 → OS prefetches offsets 1001–1100 into cache
  Reads offset 1001 → already in cache (no disk I/O)
  Reads offset 1002 → already in cache
  ...
  Effective I/O: 1 disk read per ~100 messages (prefetch in chunks)
```

#### 5. Producer-Consumer Locality (The Most Important Benefit)
When a producer writes a message, it lands in page cache. If a consumer reads that message shortly after (typical in real-time systems), it's served from RAM — no disk I/O at all.

```
t=0    Producer sends batch → broker writes to page cache
       Page cache page is now "dirty" (in RAM, not yet on disk)

t=0    Consumer (nearly caught up) sends FetchRequest
       fetchOffset points to the just-written batch
       Broker calls transferTo() → OS checks page cache
       Page IS in cache (just written!) → served from RAM
       No disk I/O. Zero milliseconds disk latency.

t=30s  OS background flush: dirty page cache pages written to disk
       Consumer already received the data — timing is irrelevant for correctness
       (durability comes from replication to 3 brokers, not from fsync)
```

---

## Zero-Copy Transfer (sendfile)

### The Problem: Normal File-to-Socket Data Flow

Without zero-copy, serving a consumer's `FetchRequest` requires four data copies:

```
Step 1: Read data from disk (if not in cache)
  Hardware (disk) ──DMA──► Kernel page cache
  [Data is now in kernel space]

Step 2: Copy from kernel to user space (JVM)
  Kernel page cache ──CPU copy──► JVM heap (byte[])
  [Data is now in JVM space — 2nd copy]

Step 3: Write to socket buffer (kernel space)
  JVM heap ──CPU copy──► Kernel socket buffer
  [Data is back in kernel space — 3rd copy]

Step 4: Send over network
  Kernel socket buffer ──DMA──► NIC (network card)
  [Data leaves the machine]

Total: 4 memory copies, 4 user↔kernel context switches
```

This is wasteful. The data exists in kernel space (page cache) and needs to reach the NIC. Why pass through JVM at all?

### Zero-Copy: The sendfile() Syscall

Linux provides `sendfile(out_fd, in_fd, offset, count)` which transfers data from one file descriptor to another entirely within the kernel.

Java exposes this as `FileChannel.transferTo()`.

```
Step 1: Read data from disk (if not in cache)
  Hardware (disk) ──DMA──► Kernel page cache
  [Data is in kernel space]

Step 2: DMA transfer from page cache to NIC
  Kernel page cache ──DMA──► NIC
  [Data leaves machine — never in JVM, never in socket buffer]
  (Modern NICs support scatter-gather DMA: even page cache copy eliminated)

Total: 2 memory copies (or 1 with scatter-gather), 2 context switches
```

### Kafka Code That Uses Zero-Copy

```java
// Simplified from Kafka source (FileRecords.java)
public long writeTo(TransferableChannel destChannel, long offset, int length) {
    long position = offset;
    int remaining = length;
    while (remaining > 0) {
        // This becomes sendfile() on Linux
        long written = channel.transferTo(position, remaining, destChannel);
        position += written;
        remaining -= (int) written;
    }
    return length;
}
```

### Performance Impact

```
Benchmark: Serving 200 MB/s of consumer reads

Without zero-copy:
  CPU utilization: ~40% (2 CPU copies at 200 MB/s each = 400 MB/s memory bandwidth)
  Memory bandwidth consumed: 800 MB/s (4 copies × 200 MB/s)

With zero-copy (sendfile):
  CPU utilization: ~5%
  Memory bandwidth consumed: 400 MB/s (2 copies × 200 MB/s)

Zero-copy frees ~35% CPU and halves memory bandwidth usage.
This enables Kafka to saturate a 10 GbE network link on commodity hardware.
```

---

## When Zero-Copy Is Disabled

### SSL/TLS Encryption

When SSL/TLS is enabled, data MUST enter JVM heap for encryption. `sendfile()` cannot be used.

```
SSL data path:
  Page cache → JVM heap (copy #1 — decrypt on receive / encrypt on send)
  JVM heap → SSL engine (encrypt)
  SSL engine → socket buffer → NIC (copy #2)

Result: Falls back to normal 4-copy path
Performance: Expect ~30% throughput reduction vs plaintext
```

For high-throughput clusters with SSL requirement: consider SSL termination at a load balancer with plaintext internal broker traffic.

### Broker-Side Compression Recompression

If `compression.type` on the topic is set to a specific codec (not `producer`) AND the producer used a different codec — the broker must decompress and recompresses:

```
Producer sends: lz4-compressed batch
Topic config:   compression.type=gzip

Broker action: decompress lz4 → JVM heap → compress with gzip → write
               (falls back to 4-copy path for this data)

Fix: Set compression.type=producer (default) to avoid broker-side recompression
```

---

## OS-Level Tuning for Page Cache

### Kernel Page Cache Parameters

```bash
# How aggressively kernel reclaims page cache pages (lower = less aggressive)
# Default 60 — may be too aggressive for Kafka
echo 10 > /proc/sys/vm/swappiness

# Percentage of dirty pages before background flush starts
# Default: 10% (flush starts when 10% RAM is dirty)
echo 80 > /proc/sys/vm/dirty_ratio

# Percentage for background writeback daemon to start
# Default: 5% — increase so OS batches disk writes
echo 20 > /proc/sys/vm/dirty_background_ratio

# How long (centiseconds) data can sit dirty before forced flush
# Default: 3000 (30 seconds)
echo 3000 > /proc/sys/vm/dirty_expire_centisecs

# Writeback thread wakeup interval
echo 500 > /proc/sys/vm/dirty_writeback_centisecs
```

### File System Recommendations

```
# Use ext4 or XFS (XFS preferred for large files)
# Mount options that help Kafka:
  noatime     → don't update access time on read (saves I/O)
  nodiratime  → same for directories
  
# Example /etc/fstab entry:
/dev/sda1 /var/kafka ext4 defaults,noatime,nodiratime 0 0
```

---

## Durability: Replication, Not fsync

Kafka does NOT call fsync per message (by default). OS page cache flushes happen asynchronously. This sounds dangerous but is safe because:

```
Durability model:
  Message written to broker-0 page cache
  Follower broker-1 replicates → in broker-1 page cache
  Follower broker-2 replicates → in broker-2 page cache
  HW advances → producer receives ack (with acks=all)

  Now the message is in 3 separate machines' page caches.
  For data loss: ALL 3 machines must lose power before their OSes flush.
  This is astronomically unlikely.

  If one broker loses power (most common failure):
    OS page cache flushed before power dies? → data on disk → safe
    OS page cache NOT flushed? → data lost from that broker
    But the other 2 brokers still have it → recovery via ISR
```

```properties
# Kafka fsync configs (rarely used — rely on replication instead)
log.flush.interval.messages=9223372036854775807  # Never (default)
log.flush.interval.ms=9223372036854775807        # Never (default)

# If you MUST use fsync (reduces throughput by 50-80%):
log.flush.interval.messages=10000  # fsync every 10000 messages
log.flush.interval.ms=1000         # OR every 1 second
```

---

## Memory-Mapped Index Files

The `.index` and `.timeindex` files are accessed via memory mapping (`FileChannel.map()`), which is a different mechanism from the page cache (though they use the same underlying OS pages).

```
FileChannel.map() creates a MappedByteBuffer:
  - The file contents appear as a region of virtual memory
  - Reads are satisfied from page cache (no syscall)
  - If page not in cache: OS page fault → load from disk → cache it
  - Random access at any index position: ~nanoseconds if cached

vs normal FileChannel.read():
  - Requires a read() syscall for each access
  - System call overhead: ~1 microsecond per call

For Kafka's .index files (thousands of lookups per second):
  Memory mapping saves millions of syscalls per second
```

---

## Summary

| Technique | Saves | When |
|---|---|---|
| Page cache | JVM GC pauses, double-buffering | All reads/writes |
| sendfile() | 2 memory copies + 2 ctx switches | Consumer reads (without SSL) |
| Producer-consumer locality | Disk I/O for recent data | When consumers are caught up |
| OS prefetching | Disk I/O latency for sequential reads | Sequential consumption |
| Memory-mapped indexes | Syscall overhead for index lookups | Every offset lookup |
| Replication (not fsync) | Disk I/O per write | All writes |

Together these explain why a single Kafka broker can handle 1+ million messages per second and saturate a 10 GbE network interface on commodity server hardware.
