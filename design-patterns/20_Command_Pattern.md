# Command Pattern

## Category
**Behavioral Design Pattern**

---

## Intent
Encapsulate a request as an object, thereby letting you parameterize clients with different requests, queue or log requests, and support undoable operations.

Also known as: **Action**, **Transaction**

---

## The Problem It Solves

You're building a text editor with Undo/Redo. How do you store "what was done" so you can reverse it?

Without Command Pattern, undo logic is scattered throughout UI event handlers.

Command Pattern encapsulates each action as an object with:
- `execute()` — do the action
- `undo()` — reverse the action

These objects can be stacked, queued, logged, and replayed.

---

## Structure

```
Client → Invoker → Command (interface)
                        ├── execute()
                        └── undo()
                              │
                        ConcreteCommand
                              ├── receiver: Receiver
                              └── execute() { receiver.action() }
```

---

## Java Example — Smart Home Controller

### Step 1: Command Interface

```java
public interface Command {
    void execute();
    void undo();
    String getName();
}
```

### Step 2: Receivers (devices being controlled)

```java
public class Light {
    private String location;
    private boolean on = false;
    private int brightness = 100;

    public Light(String location) { this.location = location; }

    public void on()  { on = true;  System.out.println("[Light] " + location + " ON"); }
    public void off() { on = false; System.out.println("[Light] " + location + " OFF"); }
    public void setBrightness(int level) {
        this.brightness = level;
        System.out.println("[Light] " + location + " brightness: " + level + "%");
    }

    public boolean isOn() { return on; }
    public int getBrightness() { return brightness; }
}

public class AudioSystem {
    private int volume = 0;
    private boolean on = false;

    public void on()            { on = true; System.out.println("[Audio] System ON"); }
    public void off()           { on = false; System.out.println("[Audio] System OFF"); }
    public void setVolume(int v){ volume = v; System.out.println("[Audio] Volume: " + v); }
    public int getVolume()      { return volume; }
    public boolean isOn()       { return on; }
}

public class AirConditioner {
    private int temperature = 24;
    private boolean on = false;

    public void on()               { on = true; System.out.println("[AC] ON"); }
    public void off()              { on = false; System.out.println("[AC] OFF"); }
    public void setTemp(int temp)  { temperature = temp; System.out.println("[AC] Temp: " + temp + "°C"); }
    public int getTemperature()    { return temperature; }
    public boolean isOn()          { return on; }
}
```

### Step 3: Concrete Commands

```java
// Light ON/OFF Commands
public class LightOnCommand implements Command {
    private Light light;

    public LightOnCommand(Light light) { this.light = light; }

    @Override public void execute() { light.on(); }
    @Override public void undo()    { light.off(); }
    @Override public String getName() { return "Light ON"; }
}

public class LightOffCommand implements Command {
    private Light light;
    private int previousBrightness;

    public LightOffCommand(Light light) { this.light = light; }

    @Override
    public void execute() {
        previousBrightness = light.getBrightness();
        light.off();
    }

    @Override
    public void undo() {
        light.on();
        light.setBrightness(previousBrightness);
    }

    @Override
    public String getName() { return "Light OFF"; }
}

// Audio Commands
public class AudioVolumeCommand implements Command {
    private AudioSystem audio;
    private int newVolume;
    private int previousVolume;

    public AudioVolumeCommand(AudioSystem audio, int volume) {
        this.audio     = audio;
        this.newVolume = volume;
    }

    @Override
    public void execute() {
        previousVolume = audio.getVolume();
        if (!audio.isOn()) audio.on();
        audio.setVolume(newVolume);
    }

    @Override
    public void undo() {
        audio.setVolume(previousVolume);
    }

    @Override
    public String getName() { return "Audio Volume → " + newVolume; }
}

// Macro Command — groups multiple commands
public class MacroCommand implements Command {
    private List<Command> commands;
    private String macroName;

    public MacroCommand(String name, Command... commands) {
        this.macroName = name;
        this.commands  = Arrays.asList(commands);
    }

    @Override
    public void execute() {
        System.out.println("[Macro] Executing: " + macroName);
        commands.forEach(Command::execute);
    }

    @Override
    public void undo() {
        System.out.println("[Macro] Undoing: " + macroName);
        // Undo in reverse order
        for (int i = commands.size() - 1; i >= 0; i--) {
            commands.get(i).undo();
        }
    }

    @Override
    public String getName() { return "Macro: " + macroName; }
}
```

### Step 4: Invoker — Remote Control with Undo History

```java
public class SmartRemote {
    private Command[] buttons;
    private String[] buttonNames;
    private Deque<Command> history = new ArrayDeque<>();

    public SmartRemote(int buttonCount) {
        buttons     = new Command[buttonCount];
        buttonNames = new String[buttonCount];

        // Default: no-op command (Null Object!)
        Command noOp = new Command() {
            public void execute() {}
            public void undo()    {}
            public String getName() { return "NoOp"; }
        };
        Arrays.fill(buttons, noOp);
    }

    public void setButton(int slot, String name, Command command) {
        buttons[slot]     = command;
        buttonNames[slot] = name;
    }

    public void pressButton(int slot) {
        System.out.printf("%n[Remote] Pressing button %d (%s)%n", slot, buttonNames[slot]);
        buttons[slot].execute();
        history.push(buttons[slot]); // record for undo
    }

    public void pressUndo() {
        if (history.isEmpty()) {
            System.out.println("[Remote] Nothing to undo!");
            return;
        }
        Command last = history.pop();
        System.out.println("\n[Remote] UNDO: " + last.getName());
        last.undo();
    }

    public void printHistory() {
        System.out.println("\n[History] Commands executed:");
        history.forEach(c -> System.out.println("  → " + c.getName()));
    }
}
```

### Step 5: Client Code

```java
public class Main {
    public static void main(String[] args) {
        // Receivers
        Light        livingLight = new Light("Living Room");
        Light        bedroomLight = new Light("Bedroom");
        AudioSystem  audio = new AudioSystem();
        AirConditioner ac  = new AirConditioner();

        // Commands
        LightOnCommand  livingOn    = new LightOnCommand(livingLight);
        LightOffCommand livingOff   = new LightOffCommand(livingLight);
        LightOnCommand  bedroomOn   = new LightOnCommand(bedroomLight);
        AudioVolumeCommand vol30    = new AudioVolumeCommand(audio, 30);
        AudioVolumeCommand vol70    = new AudioVolumeCommand(audio, 70);

        // Macro — "Movie Mode"
        MacroCommand movieMode = new MacroCommand("Movie Mode",
                livingOff,      // dim living room
                bedroomOn,      // bedroom light on
                vol70           // loud audio
        );

        // Invoker — set up remote
        SmartRemote remote = new SmartRemote(5);
        remote.setButton(0, "Living Room ON",  livingOn);
        remote.setButton(1, "Living Room OFF", livingOff);
        remote.setButton(2, "Volume Low",      vol30);
        remote.setButton(3, "Volume High",     vol70);
        remote.setButton(4, "Movie Mode",      movieMode);

        // Use remote
        remote.pressButton(0); // living ON
        remote.pressButton(2); // volume low
        remote.pressButton(4); // movie mode macro

        System.out.println("\n=== UNDO UNDO UNDO ===");
        remote.pressUndo(); // undo movie mode
        remote.pressUndo(); // undo volume
        remote.pressUndo(); // undo living on
    }
}
```

### Output

```
[Remote] Pressing button 0 (Living Room ON)
[Light] Living Room ON

[Remote] Pressing button 2 (Volume Low)
[Audio] System ON
[Audio] Volume: 30

[Remote] Pressing button 4 (Movie Mode)
[Macro] Executing: Movie Mode
[Light] Living Room OFF
[Light] Bedroom ON
[Audio] Volume: 70

=== UNDO UNDO UNDO ===
[Remote] UNDO: Macro: Movie Mode
[Macro] Undoing: Movie Mode
[Audio] Volume: 30
[Light] Bedroom OFF
[Light] Living Room ON
[Audio] Volume: 30

[Remote] UNDO: Audio Volume → 30
[Audio] Volume: 0

[Remote] UNDO: Light ON
[Light] Living Room OFF
```

---

## Real-World Java Examples

| Usage | Command Pattern |
|---|---|
| `java.lang.Runnable` | Command interface — `run()` is `execute()` |
| `java.util.concurrent.Callable` | Command with return value |
| `java.awt.Action` | GUI action with `actionPerformed()` |
| Transaction management | Each DB operation is a Command |
| Game AI | Player actions queued and replayed |

```java
// Runnable IS Command Pattern
Runnable command = () -> System.out.println("Executing!");
// Store, queue, pass around, execute later
ExecutorService executor = Executors.newFixedThreadPool(4);
executor.submit(command); // Invoker executes the command
```

---

## Pros and Cons

### ✅ Advantages
- **Decoupling** — Invoker doesn't know what the command does
- **Undo/Redo** — Store history of commands, replay in reverse
- **Queuing** — Commands can be queued, scheduled, logged
- **Macro commands** — Compose commands into sequences
- **Transactional** — Execute all or none

### ❌ Disadvantages
- **Class proliferation** — One class per command operation
- **Complexity** — Simple actions become wrapped in objects
- **State management** — Undo requires careful state preservation

---

## When to Use

✔ When you need to parameterize operations as objects  
✔ When you need Undo/Redo functionality  
✔ When you want to queue, schedule, or log operations  
✔ When building transaction systems, macro systems, or workflow engines  

---

## Key Takeaway

> **"An action as an object — queueable, undoable, loggable."**  
> Command Pattern turns operations into objects. This gives you the power to store, queue, chain, undo, or replay any action — critical for editors, games, and transactional systems.
