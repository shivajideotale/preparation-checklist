# Façade Pattern

## Category
**Structural Design Pattern**

---

## Intent
Provide a **simplified interface** to a complex subsystem. A Façade hides the complexity of the subsystem and provides a simple, easy-to-use interface for clients.

---

## The Problem It Solves

You're building a home theater system. To watch a movie, you need to:
1. Turn on projector
2. Set projector to widescreen
3. Turn on amplifier
4. Set amplifier to DVD input
5. Set volume to 7
6. Turn on DVD player
7. Start playing DVD

Without Façade, every client must know all these steps and classes. With Façade, calling `watchMovie("Inception")` does all of that.

---

## Structure

```
Client → Façade
              ├── SubsystemA
              ├── SubsystemB
              ├── SubsystemC
              └── SubsystemD
```

The Façade doesn't add new functionality — it **orchestrates** the subsystem for the client.

---

## Java Example — Home Theater

### Step 1: Subsystem Classes

```java
public class Projector {
    private String name;

    public Projector(String name) { this.name = name; }

    public void on()        { System.out.println("[Projector] " + name + " ON"); }
    public void off()       { System.out.println("[Projector] " + name + " OFF"); }
    public void widescreen(){ System.out.println("[Projector] Mode: WIDESCREEN (16:9)"); }
    public void standard()  { System.out.println("[Projector] Mode: STANDARD (4:3)"); }
}

public class Amplifier {
    public void on()            { System.out.println("[Amplifier] ON"); }
    public void off()           { System.out.println("[Amplifier] OFF"); }
    public void setVolume(int v){ System.out.println("[Amplifier] Volume set to " + v); }
    public void setInput(String i){ System.out.println("[Amplifier] Input: " + i); }
}

public class DVDPlayer {
    private String movie;

    public void on()          { System.out.println("[DVD] Player ON"); }
    public void off()         { System.out.println("[DVD] Player OFF"); }
    public void play(String m){ movie = m; System.out.println("[DVD] Playing: " + m); }
    public void stop()        { System.out.println("[DVD] Stopped: " + movie); }
    public void eject()       { System.out.println("[DVD] Ejected: " + movie); movie = null; }
}

public class Lights {
    private String room;

    public Lights(String room) { this.room = room; }

    public void on()            { System.out.println("[Lights] " + room + " ON"); }
    public void off()           { System.out.println("[Lights] " + room + " OFF"); }
    public void dim(int level)  { System.out.printf("[Lights] %s dimmed to %d%%%n", room, level); }
}

public class PopcornPopper {
    public void on()   { System.out.println("[Popcorn] Popper ON"); }
    public void off()  { System.out.println("[Popcorn] Popper OFF"); }
    public void pop()  { System.out.println("[Popcorn] Popping!"); }
}
```

### Step 2: Façade

```java
public class HomeTheaterFacade {
    // References to all subsystem components
    private Amplifier     amp;
    private DVDPlayer     dvd;
    private Projector     projector;
    private Lights        lights;
    private PopcornPopper popper;

    public HomeTheaterFacade(Amplifier amp, DVDPlayer dvd,
                             Projector projector, Lights lights,
                             PopcornPopper popper) {
        this.amp       = amp;
        this.dvd       = dvd;
        this.projector = projector;
        this.lights    = lights;
        this.popper    = popper;
    }

    // ─── SIMPLIFIED METHODS (Façade interface) ─────────────

    public void watchMovie(String movie) {
        System.out.println("\n====== Get ready to watch " + movie + "! ======");
        popper.on();
        popper.pop();
        lights.dim(10);
        projector.on();
        projector.widescreen();
        amp.on();
        amp.setInput("DVD");
        amp.setVolume(7);
        dvd.on();
        dvd.play(movie);
        System.out.println("======================================\n");
    }

    public void endMovie() {
        System.out.println("\n====== Shutting down theater... ======");
        dvd.stop();
        dvd.eject();
        dvd.off();
        amp.off();
        projector.off();
        lights.on();
        popper.off();
        System.out.println("======================================\n");
    }

    public void listenToMusic(String source) {
        System.out.println("\n====== Music Mode: " + source + " ======");
        lights.dim(30);
        amp.on();
        amp.setInput(source);
        amp.setVolume(5);
        System.out.println("=====================================\n");
    }
}
```

### Step 3: Client Code

```java
public class Main {
    public static void main(String[] args) {
        // Create subsystem objects
        Amplifier     amp       = new Amplifier();
        DVDPlayer     dvd       = new DVDPlayer();
        Projector     projector = new Projector("Epson 4K");
        Lights        lights    = new Lights("Living Room");
        PopcornPopper popper    = new PopcornPopper();

        // Create façade — client only talks to this
        HomeTheaterFacade theater =
            new HomeTheaterFacade(amp, dvd, projector, lights, popper);

        // ONE line to watch a movie — complexity hidden!
        theater.watchMovie("Inception");

        // ONE line to shut down
        theater.endMovie();

        // ONE line for music
        theater.listenToMusic("Bluetooth");
    }
}
```

### Output

```
====== Get ready to watch Inception! ======
[Popcorn] Popper ON
[Popcorn] Popping!
[Lights] Living Room dimmed to 10%
[Projector] Epson 4K ON
[Projector] Mode: WIDESCREEN (16:9)
[Amplifier] ON
[Amplifier] Input: DVD
[Amplifier] Volume set to 7
[DVD] Player ON
[DVD] Playing: Inception
==========================================

====== Shutting down theater... ======
[DVD] Stopped: Inception
[DVD] Ejected: Inception
[DVD] Player OFF
[Amplifier] OFF
[Projector] Epson 4K OFF
[Lights] Living Room ON
[Popcorn] Popper OFF
======================================
```

---

## Java Example 2 — Email Sending Façade

```java
// Complex subsystem classes
public class SMTPConnection { ... }
public class EmailValidator { ... }
public class TemplateEngine { ... }
public class AttachmentHandler { ... }
public class SpamChecker { ... }

// Façade — simple interface over complex email sending
public class EmailService {
    private SMTPConnection smtp         = new SMTPConnection();
    private EmailValidator validator    = new EmailValidator();
    private TemplateEngine templates    = new TemplateEngine();
    private AttachmentHandler attachments = new AttachmentHandler();
    private SpamChecker spamChecker     = new SpamChecker();

    public boolean sendWelcomeEmail(String to, String username) {
        if (!validator.isValid(to)) return false;
        String body = templates.render("welcome", Map.of("username", username));
        if (spamChecker.isSpam(body)) return false;
        smtp.connect();
        smtp.send(to, "Welcome!", body);
        smtp.disconnect();
        return true;
    }

    public boolean sendOrderConfirmation(String to, Order order) {
        // Complex steps hidden inside
        ...
    }
}

// Client — just calls façade
emailService.sendWelcomeEmail("user@example.com", "Rahul");
```

---

## Real-World Java Examples

| Usage | Façade |
|---|---|
| `java.net.URL` | Wraps DNS lookup, TCP connection, HTTP protocol |
| `java.util.Collections` | Simple methods over complex collection operations |
| Spring's `JdbcTemplate` | Hides Connection, Statement, ResultSet management |
| SLF4J `Logger` | Simple logging API over Log4j/Logback complexity |
| Android's `MediaPlayer` | Wraps codec, audio hardware, buffering |

---

## Façade vs Other Patterns

| Pattern | Difference |
|---|---|
| **Adapter** | Makes incompatible interfaces work; doesn't simplify |
| **Façade** | Simplifies; doesn't change interface compatibility |
| **Mediator** | Centralizes many-to-many communication between colleagues |
| **Proxy** | Same interface, adds control; Façade is a different, simpler interface |

---

## Pros and Cons

### ✅ Advantages
- **Simplicity** — One entry point for complex subsystems
- **Loose coupling** — Client doesn't depend on subsystem internals
- **Layering** — Naturally creates architectural layers
- **Testability** — Can mock the façade in tests

### ❌ Disadvantages
- **God object risk** — Façade can grow too large and do too much
- **Limits power users** — Advanced users may need to bypass the façade
- **Doesn't prevent direct access** — Clients can still call subsystem directly

---

## When to Use

✔ When you need to provide a simple interface to a complex subsystem  
✔ When there are many dependencies between clients and implementation classes  
✔ When you want to layer your subsystems  
✔ When wrapping poorly designed APIs for your own application  

---

## Key Takeaway

> **"One door into a complex building."**  
> Façade Pattern creates a simple front door to a complex system — the door handles routing and coordination, so the visitor doesn't need a map of every room inside.
