# Memento Pattern

## Category
**Behavioral Design Pattern**

---

## Intent
Without violating encapsulation, capture and externalize an object's internal state so that the object can be **restored to that state later**.

Also known as: **Token**, **Snapshot**

---

## The Problem It Solves

You're building a text editor. Users need **Ctrl+Z** (Undo). How do you save and restore the document's state without breaking encapsulation?

- If you expose the internal state (fields) of the object for saving, you violate encapsulation.
- If you snapshot the whole object, you may expose private data to outside classes.

Memento Pattern defines a separate **Memento object** that stores state privately. Only the **Originator** (the object being snapshotted) can read the Memento's full content — the **Caretaker** (the undo manager) only stores and returns Mementos, treating them as opaque tokens.

---

## Participants

| Role | Responsibility |
|---|---|
| **Originator** | Creates mementos of its state; restores from mementos |
| **Memento** | Stores the originator's internal state; only originator can access it |
| **Caretaker** | Stores mementos (undo stack); never examines content |

---

## Structure

```
Originator
  ├── state
  ├── save()   → Memento
  └── restore(Memento)

Memento
  └── state (private, only Originator accesses it)

Caretaker
  └── history: Stack<Memento>
  ├── backup()  → originator.save() → push
  └── undo()    → pop → originator.restore()
```

---

## Java Example 1 — Text Editor with Full Undo/Redo

```java
// Memento — immutable snapshot (nested class inside Originator)
public class TextEditor {

    private StringBuilder content;
    private int cursorPosition;
    private String selectedText;

    public TextEditor() {
        this.content      = new StringBuilder();
        this.cursorPosition = 0;
        this.selectedText   = "";
    }

    // ─── Originator operations ────────────────────
    public void type(String text) {
        content.insert(cursorPosition, text);
        cursorPosition += text.length();
    }

    public void delete(int count) {
        int start = Math.max(0, cursorPosition - count);
        content.delete(start, cursorPosition);
        cursorPosition = start;
    }

    public void moveCursor(int position) {
        cursorPosition = Math.max(0, Math.min(position, content.length()));
    }

    public void setSelection(String text) {
        this.selectedText = text;
    }

    public String getContent()   { return content.toString(); }
    public int getCursor()       { return cursorPosition; }

    // ─── Memento support ─────────────────────────
    public EditorMemento save() {
        return new EditorMemento(
                content.toString(),
                cursorPosition,
                selectedText
        );
    }

    public void restore(EditorMemento memento) {
        this.content        = new StringBuilder(memento.getContent());
        this.cursorPosition = memento.getCursorPosition();
        this.selectedText   = memento.getSelectedText();
    }

    public void printState(String label) {
        System.out.printf("[%s] Content: '%s' | Cursor: %d%n",
                label, content, cursorPosition);
    }

    // ─── Memento (nested — only EditorMemento accesses internals) ───
    public static final class EditorMemento {
        private final String content;
        private final int    cursorPosition;
        private final String selectedText;
        private final long   timestamp;

        // Package-private constructor — only EditorMemento can be instantiated from TextEditor
        private EditorMemento(String content, int cursorPosition, String selectedText) {
            this.content        = content;
            this.cursorPosition = cursorPosition;
            this.selectedText   = selectedText;
            this.timestamp      = System.currentTimeMillis();
        }

        // Only TextEditor (the originator) accesses these
        private String getContent()        { return content;        }
        private int    getCursorPosition() { return cursorPosition; }
        private String getSelectedText()   { return selectedText;   }

        public long getTimestamp() { return timestamp; }

        @Override
        public String toString() {
            return String.format("Snapshot[cursor=%d, content='%s']",
                    cursorPosition, content);
        }
    }
}
```

### Caretaker — Undo/Redo Manager

```java
public class EditorHistory {
    private Deque<TextEditor.EditorMemento> undoStack = new ArrayDeque<>();
    private Deque<TextEditor.EditorMemento> redoStack = new ArrayDeque<>();
    private TextEditor editor;

    public EditorHistory(TextEditor editor) {
        this.editor = editor;
    }

    public void backup(String actionLabel) {
        TextEditor.EditorMemento snapshot = editor.save();
        undoStack.push(snapshot);
        redoStack.clear(); // new action clears redo history
        System.out.println("  [History] Saved snapshot after: " + actionLabel
                + " → " + snapshot);
    }

    public void undo() {
        if (undoStack.isEmpty()) {
            System.out.println("  [History] Nothing to undo!");
            return;
        }
        // Save current state to redo stack
        redoStack.push(editor.save());
        // Restore previous state
        TextEditor.EditorMemento prev = undoStack.pop();
        editor.restore(prev);
        System.out.println("  [History] UNDO → " + prev);
    }

    public void redo() {
        if (redoStack.isEmpty()) {
            System.out.println("  [History] Nothing to redo!");
            return;
        }
        undoStack.push(editor.save());
        TextEditor.EditorMemento next = redoStack.pop();
        editor.restore(next);
        System.out.println("  [History] REDO → " + next);
    }

    public void printHistory() {
        System.out.println("\n[History] Undo stack: " + undoStack.size() + " states");
        System.out.println("[History] Redo stack: " + redoStack.size() + " states");
    }
}
```

### Client Code

```java
public class Main {
    public static void main(String[] args) {
        TextEditor     editor  = new TextEditor();
        EditorHistory  history = new EditorHistory(editor);

        // Initial state
        history.backup("initial");
        editor.printState("Init");

        // Type "Hello"
        editor.type("Hello");
        history.backup("typed 'Hello'");
        editor.printState("After Hello");

        // Type ", World"
        editor.type(", World");
        history.backup("typed ', World'");
        editor.printState("After ', World'");

        // Type "!"
        editor.type("!");
        history.backup("typed '!'");
        editor.printState("After '!'");

        System.out.println("\n=== Undo ×2 ===");
        history.undo();
        editor.printState("After Undo 1");

        history.undo();
        editor.printState("After Undo 2");

        System.out.println("\n=== Redo ×1 ===");
        history.redo();
        editor.printState("After Redo 1");

        System.out.println("\n=== Type something new (clears redo) ===");
        editor.type(" Java");
        history.backup("typed ' Java'");
        editor.printState("After ' Java'");

        history.redo(); // Nothing to redo now
        history.printHistory();
    }
}
```

### Output

```
  [History] Saved snapshot after: initial → Snapshot[cursor=0, content='']
[Init] Content: '' | Cursor: 0
  [History] Saved snapshot after: typed 'Hello' → Snapshot[cursor=5, content='Hello']
[After Hello] Content: 'Hello' | Cursor: 5
  [History] Saved snapshot after: typed ', World' → Snapshot[cursor=12, content='Hello, World']
[After ', World'] Content: 'Hello, World' | Cursor: 12
  [History] Saved snapshot after: typed '!' → Snapshot[cursor=13, content='Hello, World!']
[After '!'] Content: 'Hello, World!' | Cursor: 13

=== Undo ×2 ===
  [History] UNDO → Snapshot[cursor=12, content='Hello, World']
[After Undo 1] Content: 'Hello, World' | Cursor: 12
  [History] UNDO → Snapshot[cursor=5, content='Hello']
[After Undo 2] Content: 'Hello' | Cursor: 5

=== Redo ×1 ===
  [History] REDO → Snapshot[cursor=12, content='Hello, World']
[After Redo 1] Content: 'Hello, World' | Cursor: 12

=== Type something new (clears redo) ===
  [History] Saved snapshot after: typed ' Java' → Snapshot[cursor=17, content='Hello, World Java']
[After ' Java'] Content: 'Hello, World Java' | Cursor: 17
  [History] Nothing to redo!

[History] Undo stack: 4 states
[History] Redo stack: 0 states
```

---

## Java Example 2 — Game Save System

```java
public class GameCharacter {

    // Memento
    public static final class SaveState {
        private final int    level;
        private final int    health;
        private final int    mana;
        private final String location;
        private final List<String> inventory;
        private final long   savedAt;

        private SaveState(int level, int health, int mana,
                          String location, List<String> inventory) {
            this.level     = level;
            this.health    = health;
            this.mana      = mana;
            this.location  = location;
            this.inventory = List.copyOf(inventory);
            this.savedAt   = System.currentTimeMillis();
        }

        @Override
        public String toString() {
            return String.format("Save[Lv%d | HP:%d | MP:%d | @%s | Items:%s]",
                    level, health, mana, location, inventory);
        }
    }

    // Originator state
    private int          level     = 1;
    private int          health    = 100;
    private int          mana      = 50;
    private String       location  = "Starter Village";
    private List<String> inventory = new ArrayList<>();

    // Game actions
    public void gainLevel()              { level++; health = 100 + level * 10; mana += 20; }
    public void takeDamage(int dmg)      { health = Math.max(0, health - dmg); }
    public void addItem(String item)     { inventory.add(item); }
    public void travel(String location)  { this.location = location; }

    // Memento support
    public SaveState save()  { return new SaveState(level, health, mana, location, inventory); }

    public void load(SaveState state) {
        this.level     = state.level;
        this.health    = state.health;
        this.mana      = state.mana;
        this.location  = state.location;
        this.inventory = new ArrayList<>(state.inventory);
    }

    public void printStatus() {
        System.out.printf("[Character] Lv%d | HP:%d | MP:%d | @%s | Items:%s%n",
                level, health, mana, location, inventory);
    }
}

// Save Manager (Caretaker)
public class SaveManager {
    private Map<String, GameCharacter.SaveState> slots = new LinkedHashMap<>();

    public void save(String slotName, GameCharacter character) {
        slots.put(slotName, character.save());
        System.out.println("[SaveManager] Saved to slot '" + slotName + "'");
    }

    public void load(String slotName, GameCharacter character) {
        GameCharacter.SaveState state = slots.get(slotName);
        if (state == null) { System.out.println("[SaveManager] No save found: " + slotName); return; }
        character.load(state);
        System.out.println("[SaveManager] Loaded slot '" + slotName + "': " + state);
    }
}

// Usage
GameCharacter hero = new GameCharacter();
SaveManager saves = new SaveManager();

hero.addItem("Sword");
hero.travel("Forest");
saves.save("slot1", hero); // SAVE

hero.gainLevel();
hero.addItem("Shield");
hero.travel("Dungeon");
hero.takeDamage(80);
hero.printStatus();

saves.load("slot1", hero); // LOAD — back to forest!
hero.printStatus();
```

---

## Real-World Java Examples

| Usage | Memento |
|---|---|
| `java.io.Serializable` | Object state saved to stream and restored |
| `javax.swing.undo.UndoManager` | Swing undo/redo system |
| Git commits | Each commit is a memento of repository state |
| Database transactions | Savepoints in transactions |
| Browser history | Each page is a navigation memento |

---

## Pros and Cons

### ✅ Advantages
- **Encapsulation preserved** — Originator's internals not exposed to caretaker
- **Undo/Redo** — Simple implementation of history
- **Snapshots** — Save/restore state at any point
- **Clean code** — State management separated from business logic

### ❌ Disadvantages
- **Memory usage** — Storing many snapshots can consume lots of memory
- **Serialization** — Deep copying complex objects is expensive
- **Caretaker complexity** — Managing lifecycle of many mementos

---

## When to Use

✔ When you need to implement Undo/Redo  
✔ When you need to save checkpoints that can be rolled back  
✔ When direct access to state would violate encapsulation  
✔ Text editors, game save systems, transaction management, configuration snapshots  

---

## Key Takeaway

> **"Snapshot the past, restore the present."**  
> Memento Pattern captures object state in an opaque token that only the originator understands — caretakers store and return the token without peeking inside, preserving encapsulation while enabling full undo/restore.
