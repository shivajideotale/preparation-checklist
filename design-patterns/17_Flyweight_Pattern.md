# Flyweight Pattern

## Category
**Structural Design Pattern**

---

## Intent
Use sharing to efficiently support **large numbers of fine-grained objects**. Instead of creating many objects with duplicated state, share as much data as possible.

---

## The Problem It Solves

You're building a text editor that renders millions of characters on screen. Each character has:
- `char` value (e.g., 'A')
- Font, size, color (shared — many chars use the same font)
- X, Y position (unique — each char is in a different place)

Creating a separate `Character` object for each character in a 1-million-character document wastes enormous memory for duplicated font/color data.

**Flyweight separates:**
- **Intrinsic state** — shared, immutable (font, color, character glyph)
- **Extrinsic state** — unique, passed in per use (position, context)

---

## Structure

```
FlyweightFactory
  ├── cache: Map<key, Flyweight>
  └── getFlyweight(key) → shared Flyweight

Flyweight (interface)
  └── operation(extrinsicState)

ConcreteFlyweight
  ├── intrinsicState (shared)
  └── operation(extrinsicState) { use intrinsicState + extrinsicState }
```

---

## Java Example — Text Editor

### Step 1: Flyweight Interface

```java
public interface CharacterFlyweight {
    // extrinsicState: position where this character is rendered
    void render(int x, int y);
}
```

### Step 2: Concrete Flyweight (shared object)

```java
public class ConcreteCharacter implements CharacterFlyweight {
    // INTRINSIC STATE — shared, immutable
    private final char character;
    private final String fontFamily;
    private final int fontSize;
    private final String color;

    public ConcreteCharacter(char character, String fontFamily,
                             int fontSize, String color) {
        this.character  = character;
        this.fontFamily = fontFamily;
        this.fontSize   = fontSize;
        this.color      = color;

        // Simulate expensive initialization
        System.out.printf("[Flyweight] Created: '%c' | %s | %dpt | %s%n",
                character, fontFamily, fontSize, color);
    }

    @Override
    public void render(int x, int y) {
        // EXTRINSIC STATE — x, y are passed in, not stored
        System.out.printf("  Render '%c' at (%3d, %3d) [%s %dpt %s]%n",
                character, x, y, fontFamily, fontSize, color);
    }

    public char getCharacter() { return character; }
}
```

### Step 3: Flyweight Factory

```java
public class CharacterFactory {
    private Map<String, ConcreteCharacter> cache = new HashMap<>();

    private String buildKey(char c, String font, int size, String color) {
        return c + "-" + font + "-" + size + "-" + color;
    }

    public CharacterFlyweight getCharacter(char c, String font,
                                           int size, String color) {
        String key = buildKey(c, font, size, color);

        if (!cache.containsKey(key)) {
            cache.put(key, new ConcreteCharacter(c, font, size, color));
        } else {
            System.out.printf("[Factory] Reusing cached: '%c'%n", c);
        }

        return cache.get(key);
    }

    public int getCacheSize() { return cache.size(); }

    public void printCacheStats() {
        System.out.println("\n[Cache] Unique flyweight objects: " + cache.size());
        cache.keySet().forEach(k -> System.out.println("  → " + k));
    }
}
```

### Step 4: Text Editor (Client)

```java
public class TextDocument {
    // Each entry: flyweight + extrinsic position
    private record CharPosition(CharacterFlyweight flyweight, int x, int y) {}
    private List<CharPosition> characters = new ArrayList<>();
    private CharacterFactory factory;

    public TextDocument(CharacterFactory factory) {
        this.factory = factory;
    }

    public void addCharacter(char c, String font, int size,
                             String color, int x, int y) {
        CharacterFlyweight fw = factory.getCharacter(c, font, size, color);
        characters.add(new CharPosition(fw, x, y));
    }

    public void render() {
        System.out.println("\n=== Rendering Document ===");
        for (CharPosition cp : characters) {
            cp.flyweight().render(cp.x(), cp.y());
        }
    }

    public int getTotalCharacters() { return characters.size(); }
}

// Client
public class Main {
    public static void main(String[] args) {
        CharacterFactory factory = new CharacterFactory();
        TextDocument doc = new TextDocument(factory);

        System.out.println("=== Adding Characters ===");

        // "HELLO" — multiple H, L characters share flyweights!
        String text  = "HELLO WORLD";
        String font  = "Arial";
        int    size  = 12;
        String color = "Black";
        int x = 10, y = 10;

        for (char c : text.toCharArray()) {
            if (c == ' ') { x += 8; continue; }
            doc.addCharacter(c, font, size, color, x, y);
            x += 10;
        }

        // Some bold red characters — share different flyweights
        doc.addCharacter('H', "Arial", 18, "Red", 10, 30);
        doc.addCharacter('I', "Arial", 18, "Red", 28, 30);

        doc.render();

        System.out.println("\nTotal chars in document : " + doc.getTotalCharacters());
        factory.printCacheStats();
    }
}
```

### Output

```
=== Adding Characters ===
[Flyweight] Created: 'H' | Arial | 12pt | Black
[Flyweight] Created: 'E' | Arial | 12pt | Black
[Flyweight] Created: 'L' | Arial | 12pt | Black
[Factory] Reusing cached: 'L'
[Flyweight] Created: 'O' | Arial | 12pt | Black
[Flyweight] Created: 'W' | Arial | 12pt | Black
[Factory] Reusing cached: 'O'
[Flyweight] Created: 'R' | Arial | 12pt | Black
[Factory] Reusing cached: 'L'
[Flyweight] Created: 'D' | Arial | 12pt | Black
[Flyweight] Created: 'H' | Arial | 18pt | Red
[Flyweight] Created: 'I' | Arial | 18pt | Red

=== Rendering Document ===
  Render 'H' at ( 10,  10) [Arial 12pt Black]
  Render 'E' at ( 20,  10) [Arial 12pt Black]
  Render 'L' at ( 30,  10) [Arial 12pt Black]
  Render 'L' at ( 40,  10) [Arial 12pt Black]
  Render 'O' at ( 50,  10) [Arial 12pt Black]
  ...

Total chars in document : 13
[Cache] Unique flyweight objects: 10
  → H-Arial-12-Black
  → E-Arial-12-Black
  → L-Arial-12-Black
  → O-Arial-12-Black
  ...
```

---

## Memory Impact Analysis

```
WITHOUT Flyweight (1 million 'A' characters):
  1,000,000 objects × (char + String fontFamily + int size + String color)
  = ~1,000,000 × ~200 bytes = ~200 MB

WITH Flyweight:
  1 flyweight object for 'A-Arial-12-Black'
  1,000,000 records × (reference + int x + int y)
  = 1 object × ~200 bytes + 1,000,000 × ~20 bytes = ~20 MB
```

---

## Real-World Java Examples

| Usage | Flyweight |
|---|---|
| `String.intern()` | String pool — same literal string shared in memory |
| `Integer.valueOf(-128 to 127)` | Cached Integer objects shared |
| `Boolean.TRUE / Boolean.FALSE` | Only two Boolean objects exist |
| Java `Font` objects | Shared glyph data in rendering engines |
| `java.awt.Color` constants | `Color.RED`, `Color.BLUE` are flyweights |

```java
// Java Integer cache — Flyweight in action!
Integer a = Integer.valueOf(100); // cached
Integer b = Integer.valueOf(100); // same object from cache
System.out.println(a == b); // true (same reference)

Integer c = Integer.valueOf(200); // NOT cached (> 127)
Integer d = Integer.valueOf(200);
System.out.println(c == d); // false (different objects)
```

---

## Intrinsic vs Extrinsic State

```java
// Flyweight stores INTRINSIC state only
class BulletFlyweight {
    // Intrinsic (shared across all bullets of this type)
    private final String type;     // "pistol", "rifle", "shotgun"
    private final int    damage;
    private final String sprite;

    void render(int x, int y, double angle) {
        // x, y, angle = EXTRINSIC state (passed in, not stored)
        System.out.printf("Bullet [%s] at (%d,%d) angle=%.1f°%n", type, x, y, angle);
    }
}

// Context object stores extrinsic state + flyweight reference
class BulletContext {
    private BulletFlyweight flyweight; // shared
    private int x, y;                 // unique
    private double angle;             // unique

    void render() { flyweight.render(x, y, angle); }
}
```

---

## Pros and Cons

### ✅ Advantages
- **Massive memory savings** — Share data across thousands/millions of objects
- **Performance** — Fewer GC cycles, less memory allocation
- **Scalability** — Enables large-scale simulations (particles, characters, tiles)

### ❌ Disadvantages
- **Complexity** — Must separate intrinsic/extrinsic state clearly
- **Runtime cost** — Extrinsic state must be computed/passed each time
- **Not intuitive** — Flyweight objects look "incomplete" without extrinsic state
- **Thread safety** — Shared flyweights must be immutable or carefully synchronized

---

## When to Use

✔ When an application uses a huge number of similar objects  
✔ When storage costs are high due to object quantity  
✔ When most object state can be made extrinsic  
✔ Game development (particles, tiles, enemies), text rendering, GUI components  

---

## Key Takeaway

> **"Share what's common, pass what's unique."**  
> Flyweight splits object state into shared (intrinsic) and contextual (extrinsic) parts — creating one shared object per unique configuration instead of millions of identical copies.
