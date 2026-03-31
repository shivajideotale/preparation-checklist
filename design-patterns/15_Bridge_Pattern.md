# Bridge Pattern

## Category
**Structural Design Pattern**

---

## Intent
Decouple an **abstraction** from its **implementation** so that the two can vary independently.

Also known as: **Handle/Body**

---

## The Problem It Solves

You're building a drawing app. You have shapes (Circle, Square) and rendering engines (OpenGL, DirectX, Metal). Using inheritance:

```
Shape
 ├── OpenGLCircle
 ├── DirectXCircle
 ├── MetalCircle
 ├── OpenGLSquare
 ├── DirectXSquare
 └── MetalSquare
```

For **M shapes** × **N renderers** = **M×N classes**. Adding a new renderer means adding M new classes!

Bridge Pattern separates shapes from rendering engines into two independent hierarchies, connected by a bridge:

```
Shape (abstraction)          Renderer (implementation)
 ├── Circle  ─────bridge──►  ├── OpenGLRenderer
 └── Square                  ├── DirectXRenderer
                             └── MetalRenderer
```

Now: **M + N classes** instead of **M × N**.

---

## Structure

```
Abstraction
  ├── refined abstraction
  └── impl: Implementor
               ├── ConcreteImplementorA
               └── ConcreteImplementorB
```

---

## Java Example — Remote Control + Devices

### Step 1: Implementation Interface

```java
public interface Device {
    boolean isEnabled();
    void enable();
    void disable();
    int getVolume();
    void setVolume(int percent);
    int getChannel();
    void setChannel(int channel);
    String getName();
    void printStatus();
}
```

### Step 2: Concrete Implementations

```java
public class TV implements Device {
    private boolean on = false;
    private int volume = 30;
    private int channel = 1;

    @Override
    public boolean isEnabled() { return on; }

    @Override
    public void enable()  { on = true;  System.out.println("[TV] Turned ON");  }

    @Override
    public void disable() { on = false; System.out.println("[TV] Turned OFF"); }

    @Override
    public int getVolume() { return volume; }

    @Override
    public void setVolume(int percent) {
        this.volume = Math.max(0, Math.min(100, percent));
        System.out.println("[TV] Volume set to " + this.volume);
    }

    @Override
    public int getChannel() { return channel; }

    @Override
    public void setChannel(int channel) {
        this.channel = channel;
        System.out.println("[TV] Channel changed to " + channel);
    }

    @Override
    public String getName() { return "Samsung TV"; }

    @Override
    public void printStatus() {
        System.out.printf("[TV Status] ON: %s | Vol: %d | Ch: %d%n", on, volume, channel);
    }
}

public class Radio implements Device {
    private boolean on = false;
    private int volume = 50;
    private int channel = 1; // FM frequency * 10 e.g. 919 = 91.9 MHz

    @Override
    public boolean isEnabled() { return on; }

    @Override
    public void enable()  { on = true;  System.out.println("[Radio] Turned ON");  }

    @Override
    public void disable() { on = false; System.out.println("[Radio] Turned OFF"); }

    @Override
    public int getVolume() { return volume; }

    @Override
    public void setVolume(int percent) {
        this.volume = Math.max(0, Math.min(100, percent));
        System.out.println("[Radio] Volume set to " + this.volume);
    }

    @Override
    public int getChannel() { return channel; }

    @Override
    public void setChannel(int channel) {
        this.channel = channel;
        System.out.printf("[Radio] Tuned to %.1f MHz%n", channel / 10.0);
    }

    @Override
    public String getName() { return "Sony Radio"; }

    @Override
    public void printStatus() {
        System.out.printf("[Radio Status] ON: %s | Vol: %d | Freq: %.1f MHz%n",
                on, volume, channel / 10.0);
    }
}
```

### Step 3: Abstraction

```java
public abstract class RemoteControl {
    protected Device device; // THE BRIDGE — reference to implementation

    public RemoteControl(Device device) {
        this.device = device;
    }

    public void togglePower() {
        if (device.isEnabled()) {
            device.disable();
        } else {
            device.enable();
        }
    }

    public void volumeUp() {
        device.setVolume(device.getVolume() + 10);
    }

    public void volumeDown() {
        device.setVolume(device.getVolume() - 10);
    }

    public void channelUp() {
        device.setChannel(device.getChannel() + 1);
    }

    public void channelDown() {
        device.setChannel(device.getChannel() - 1);
    }

    public void printStatus() {
        System.out.println("Remote controlling: " + device.getName());
        device.printStatus();
    }
}
```

### Step 4: Refined Abstractions (Extended Remotes)

```java
// Basic remote
public class BasicRemote extends RemoteControl {
    public BasicRemote(Device device) {
        super(device);
    }
    // Uses all inherited methods from RemoteControl
}

// Advanced remote with extra features
public class AdvancedRemote extends RemoteControl {
    private int savedChannel = 1;

    public AdvancedRemote(Device device) {
        super(device);
    }

    public void mute() {
        System.out.println("[Advanced Remote] Muting " + device.getName());
        device.setVolume(0);
    }

    public void saveChannel() {
        savedChannel = device.getChannel();
        System.out.println("[Advanced Remote] Saved channel: " + savedChannel);
    }

    public void jumpToSaved() {
        System.out.println("[Advanced Remote] Jumping to saved channel: " + savedChannel);
        device.setChannel(savedChannel);
    }

    public void setVolumeTo(int vol) {
        device.setVolume(vol);
    }
}

// Parental control remote
public class ParentalRemote extends RemoteControl {
    private int maxVolume;
    private Set<Integer> blockedChannels = new HashSet<>();

    public ParentalRemote(Device device, int maxVolume) {
        super(device);
        this.maxVolume = maxVolume;
    }

    @Override
    public void volumeUp() {
        if (device.getVolume() >= maxVolume) {
            System.out.println("[Parental] Max volume (" + maxVolume + "%) reached!");
        } else {
            super.volumeUp();
        }
    }

    public void blockChannel(int channel) {
        blockedChannels.add(channel);
        System.out.println("[Parental] Blocked channel " + channel);
    }

    @Override
    public void channelUp() {
        int next = device.getChannel() + 1;
        if (blockedChannels.contains(next)) {
            System.out.println("[Parental] Channel " + next + " is blocked. Skipping...");
            device.setChannel(next + 1);
        } else {
            super.channelUp();
        }
    }
}
```

### Step 5: Client Code

```java
public class Main {
    public static void main(String[] args) {
        System.out.println("=== Basic Remote + TV ===");
        Device tv = new TV();
        RemoteControl basicTVRemote = new BasicRemote(tv);
        basicTVRemote.togglePower();
        basicTVRemote.volumeUp();
        basicTVRemote.channelUp();
        basicTVRemote.printStatus();

        System.out.println("\n=== Advanced Remote + Radio ===");
        Device radio = new Radio();
        AdvancedRemote advancedRadio = new AdvancedRemote(radio);
        advancedRadio.togglePower();
        advancedRadio.device.setChannel(919); // 91.9 MHz
        advancedRadio.saveChannel();
        advancedRadio.mute();
        advancedRadio.jumpToSaved();
        advancedRadio.printStatus();

        System.out.println("\n=== Parental Remote + TV ===");
        Device kidsTV = new TV();
        ParentalRemote parentalRemote = new ParentalRemote(kidsTV, 40);
        parentalRemote.togglePower();
        parentalRemote.blockChannel(2);
        parentalRemote.blockChannel(3);
        parentalRemote.channelUp(); // 1→2 blocked, goes to 3... 3 blocked, goes to 4
        // Volume attempts
        parentalRemote.setVolumeTo(38);
        parentalRemote.volumeUp(); // to 48... blocked at 40
        parentalRemote.printStatus();
    }
}
```

---

## Class Explosion Without Bridge

```
Without Bridge:
  BasicTVRemote         ← 6 classes for 2 remotes × 3 devices
  BasicRadioRemote
  BasicSpeakerRemote
  AdvancedTVRemote
  AdvancedRadioRemote
  AdvancedSpeakerRemote

With Bridge:
  BasicRemote    + TV / Radio / Speaker   ← 2 + 3 = 5 classes
  AdvancedRemote
```

---

## Real-World Java Examples

| Usage | Bridge |
|---|---|
| `java.sql.Driver` | JDBC driver is the implementation; `Connection` is the abstraction |
| `java.util.logging.Handler` | Log handler implementations bridge from Logger abstraction |
| AWT/Swing | `Component` (abstraction) + OS-specific peer (implementation) |
| Spring's `PlatformTransactionManager` | Abstraction over different transaction managers (JPA, JDBC, JTA) |

---

## Pros and Cons

### ✅ Advantages
- **Avoids class explosion** — M + N instead of M × N
- **Independent variation** — Change abstraction and implementation separately
- **Open/Closed** — Add new implementations without changing abstractions
- **Runtime switch** — Can change implementation at runtime

### ❌ Disadvantages
- **Added complexity** — Extra layer of abstraction and indirection
- **Overkill for simple cases** — If no variation expected, just use inheritance

---

## Bridge vs Adapter

| Pattern | Intent |
|---|---|
| **Adapter** | Makes two **existing** incompatible interfaces work together |
| **Bridge** | **Designed upfront** to separate abstraction from implementation |

---

## When to Use

✔ When you want to avoid permanent binding between abstraction and implementation  
✔ When both abstractions and implementations should be extensible via subclassing  
✔ When changes in implementation should not impact clients  
✔ When you have a proliferating class hierarchy  

---

## Key Takeaway

> **"Separate the 'what' from the 'how'."**  
> Bridge Pattern decouples the abstraction hierarchy from the implementation hierarchy — they evolve independently, connected only by the bridge reference.
