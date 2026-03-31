# Adapter Pattern

## Category
**Structural Design Pattern**

---

## Intent
Convert the interface of a class into another interface that clients expect. Adapter lets classes work together that couldn't otherwise because of incompatible interfaces.

Also known as: **Wrapper**

---

## The Problem It Solves

You've built an app that uses a `MediaPlayer` interface. Now you want to integrate a third-party library `AdvancedVideoPlayer` — but it has a completely different interface. You can't change the third-party code.

Real-world analogy: Your laptop has USB-C ports. Your old USB-A flash drive doesn't fit. You use a **USB-C to USB-A adapter** — the adapter converts one interface to the other without modifying either device.

---

## Structure

### Object Adapter (uses composition — preferred)

```
Client → Target (interface)
              │
           Adapter  ← has-a → Adaptee
           └── request() { adaptee.specificRequest() }
```

### Class Adapter (uses inheritance — requires multiple inheritance, less common in Java)

```
Client → Target (interface)
              │
           Adapter  ← extends → Adaptee
           └── request() { specificRequest() }
```

---

## Java Example 1 — Media Player

### Existing Interface (Target)

```java
// What our application uses
public interface MediaPlayer {
    void play(String audioType, String fileName);
}
```

### Existing Implementation

```java
public class AudioPlayer implements MediaPlayer {

    @Override
    public void play(String audioType, String fileName) {
        if (audioType.equalsIgnoreCase("mp3")) {
            System.out.println("[AudioPlayer] Playing MP3: " + fileName);
        } else {
            System.out.println("[AudioPlayer] ❌ Format not supported: " + audioType);
        }
    }
}
```

### Incompatible Third-Party Library (Adaptee)

```java
// Third-party — we cannot change this interface
public interface AdvancedVideoPlayer {
    void playVlc(String fileName);
    void playMp4(String fileName);
}

public class VlcPlayer implements AdvancedVideoPlayer {
    @Override
    public void playVlc(String fileName) {
        System.out.println("[VlcPlayer] Playing VLC: " + fileName);
    }

    @Override
    public void playMp4(String fileName) {
        System.out.println("[VlcPlayer] ❌ VLC doesn't support MP4");
    }
}

public class Mp4Player implements AdvancedVideoPlayer {
    @Override
    public void playVlc(String fileName) {
        System.out.println("[Mp4Player] ❌ MP4 player doesn't support VLC");
    }

    @Override
    public void playMp4(String fileName) {
        System.out.println("[Mp4Player] Playing MP4: " + fileName);
    }
}
```

### Adapter — Makes AdvancedVideoPlayer work as MediaPlayer

```java
public class MediaAdapter implements MediaPlayer {
    private AdvancedVideoPlayer advancedPlayer;

    public MediaAdapter(String audioType) {
        // Decide which advanced player to wrap
        if (audioType.equalsIgnoreCase("vlc")) {
            advancedPlayer = new VlcPlayer();
        } else if (audioType.equalsIgnoreCase("mp4")) {
            advancedPlayer = new Mp4Player();
        }
    }

    @Override
    public void play(String audioType, String fileName) {
        // Translate MediaPlayer interface → AdvancedVideoPlayer interface
        switch (audioType.toLowerCase()) {
            case "vlc" -> advancedPlayer.playVlc(fileName);
            case "mp4" -> advancedPlayer.playMp4(fileName);
            default    -> System.out.println("❌ Format not supported: " + audioType);
        }
    }
}
```

### Enhanced AudioPlayer Using the Adapter

```java
public class EnhancedAudioPlayer implements MediaPlayer {

    @Override
    public void play(String audioType, String fileName) {
        if (audioType.equalsIgnoreCase("mp3")) {
            System.out.println("[AudioPlayer] Playing MP3: " + fileName);
        } else if (audioType.equalsIgnoreCase("vlc") ||
                   audioType.equalsIgnoreCase("mp4")) {
            // Use adapter to bridge the gap
            MediaAdapter adapter = new MediaAdapter(audioType);
            adapter.play(audioType, fileName);
        } else {
            System.out.println("❌ Unsupported format: " + audioType);
        }
    }
}
```

### Client Code

```java
public class Main {
    public static void main(String[] args) {
        MediaPlayer player = new EnhancedAudioPlayer();

        player.play("mp3", "song.mp3");
        player.play("mp4", "video.mp4");
        player.play("vlc", "movie.vlc");
        player.play("avi", "clip.avi"); // unsupported
    }
}
```

### Output

```
[AudioPlayer] Playing MP3: song.mp3
[Mp4Player] Playing MP4: video.mp4
[VlcPlayer] Playing VLC: movie.vlc
❌ Unsupported format: avi
```

---

## Java Example 2 — Legacy Payment Adapter

```java
// Modern payment interface (Target)
public interface ModernPaymentProcessor {
    PaymentResult processPayment(PaymentRequest request);
}

public class PaymentRequest {
    private String cardNumber;
    private double amount;
    private String currency;
    // constructors, getters...
}

public class PaymentResult {
    private boolean success;
    private String transactionId;
    // constructors, getters...
}

// Legacy system interface (Adaptee — cannot be changed)
public class LegacyPaymentSystem {
    public String chargeCard(String cardNum, int amountInCents, String curr) {
        System.out.printf("[Legacy] Charging card %s | Amount: %d cents | Currency: %s%n",
                cardNum, amountInCents, curr);
        return "TXN-" + System.currentTimeMillis(); // returns transaction ID
    }

    public boolean refundCharge(String transactionId) {
        System.out.println("[Legacy] Refunding: " + transactionId);
        return true;
    }
}

// Adapter — wraps legacy, exposes modern interface
public class LegacyPaymentAdapter implements ModernPaymentProcessor {
    private LegacyPaymentSystem legacySystem;

    public LegacyPaymentAdapter(LegacyPaymentSystem legacySystem) {
        this.legacySystem = legacySystem;
    }

    @Override
    public PaymentResult processPayment(PaymentRequest request) {
        // Adapt: double → int cents
        int amountInCents = (int)(request.getAmount() * 100);

        // Adapt: call legacy method
        String txnId = legacySystem.chargeCard(
                request.getCardNumber(),
                amountInCents,
                request.getCurrency()
        );

        // Adapt: String → PaymentResult
        return new PaymentResult(txnId != null, txnId);
    }
}

// Client — only knows ModernPaymentProcessor, unaware of legacy
public class CheckoutService {
    private ModernPaymentProcessor processor;

    public CheckoutService(ModernPaymentProcessor processor) {
        this.processor = processor; // inject real or legacy adapter
    }

    public void checkout(String card, double amount) {
        PaymentRequest request = new PaymentRequest(card, amount, "INR");
        PaymentResult result = processor.processPayment(request);
        System.out.println("Payment " + (result.isSuccess() ? "✅ successful" : "❌ failed")
                + " | TXN: " + result.getTransactionId());
    }
}

// Usage
LegacyPaymentSystem legacy = new LegacyPaymentSystem();
ModernPaymentProcessor adapter = new LegacyPaymentAdapter(legacy);
CheckoutService checkout = new CheckoutService(adapter);
checkout.checkout("4111-1111-1111-1111", 1500.00);
```

### Output

```
[Legacy] Charging card 4111-1111-1111-1111 | Amount: 150000 cents | Currency: INR
Payment ✅ successful | TXN: TXN-1711890234567
```

---

## Java Example 3 — Two-Way Adapter

```java
// Adapt between Celsius and Fahrenheit thermometers
public interface CelsiusThermometer {
    double getTemperatureCelsius();
}

public interface FahrenheitThermometer {
    double getTemperatureFahrenheit();
}

public class FahrenheitThermometerImpl implements FahrenheitThermometer {
    @Override
    public double getTemperatureFahrenheit() { return 98.6; }
}

// Two-way adapter
public class TemperatureAdapter implements CelsiusThermometer {
    private FahrenheitThermometer fahrenheitThermometer;

    public TemperatureAdapter(FahrenheitThermometer f) {
        this.fahrenheitThermometer = f;
    }

    @Override
    public double getTemperatureCelsius() {
        // Convert F → C
        return (fahrenheitThermometer.getTemperatureFahrenheit() - 32) * 5.0 / 9.0;
    }
}

// Usage
FahrenheitThermometer fThermometer = new FahrenheitThermometerImpl();
CelsiusThermometer cThermometer = new TemperatureAdapter(fThermometer);
System.out.printf("Temperature: %.1f°C%n", cThermometer.getTemperatureCelsius());
// Temperature: 37.0°C
```

---

## Real-World Java Examples

| Usage | Adapter |
|---|---|
| `java.util.Arrays.asList()` | Adapts array → `List` |
| `java.io.InputStreamReader` | Adapts `InputStream` (bytes) → `Reader` (chars) |
| `java.io.OutputStreamWriter` | Adapts `OutputStream` → `Writer` |
| `Collections.list(Enumeration)` | Adapts old `Enumeration` → `ArrayList` |
| Spring's `HandlerAdapter` | Adapts different controllers to the DispatcherServlet |

```java
// InputStreamReader IS an Adapter!
// Target: Reader (character-based)
// Adaptee: InputStream (byte-based)
Reader reader = new InputStreamReader(System.in, StandardCharsets.UTF_8);
// Now you can read chars from what was a byte stream
```

---

## Adapter vs Decorator vs Proxy vs Facade

| Pattern | Intent | Changes Interface? |
|---|---|---|
| **Adapter** | Make incompatible interfaces work together | Yes |
| **Decorator** | Add responsibilities to object | No |
| **Proxy** | Control access to object | No |
| **Facade** | Simplify a complex subsystem interface | Simplifies |

---

## Pros and Cons

### ✅ Advantages
- **Integrates incompatible code** — Without modifying existing classes
- **Single Responsibility** — Conversion logic is isolated in adapter
- **Open/Closed** — New adapters for new third-party code without changing client
- **Reuse** — Enables reuse of existing classes with different interfaces

### ❌ Disadvantages
- **Added complexity** — More classes and indirection
- **Performance** — Extra method call overhead
- **Transparency** — Adapter might not expose all features of the adaptee

---

## When to Use

✔ When you want to use an existing class but its interface doesn't match your needs  
✔ When integrating third-party or legacy code you cannot modify  
✔ When you need several existing subclasses to gain new functionality — adapt them  

---

## Key Takeaway

> **"Plug it in — even if the socket shape is wrong."**  
> Adapter bridges two incompatible interfaces without changing either party, enabling collaboration between classes that couldn't otherwise communicate.
