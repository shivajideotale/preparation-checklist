# Prototype Pattern

## Category
**Creational Design Pattern**

---

## Intent
Specify the kinds of objects to create using a prototypical instance, and create new objects by **copying (cloning)** this prototype.

---

## The Problem It Solves

Creating an object can be expensive: loading data from a database, parsing XML, or complex initialization. If you need many similar objects, building each from scratch is wasteful.

Prototype Pattern lets you create a new object by **copying an existing one** (the prototype) and then tweaking only what's different. It's especially useful when:
- Object initialization is costly
- You need many similar objects with slight variations
- You want to decouple client from concrete classes

---

## Java's Built-in Cloning

Java provides `java.lang.Cloneable` and `Object.clone()`:

```java
public class SomeClass implements Cloneable {
    @Override
    protected Object clone() throws CloneNotSupportedException {
        return super.clone(); // shallow copy
    }
}
```

**Shallow copy**: References inside the object are copied (same memory address).  
**Deep copy**: All nested objects are also cloned (independent copies).

---

## Java Example — Game Character Templates

```java
public abstract class GameCharacter implements Cloneable {
    protected String name;
    protected String characterClass;
    protected int health;
    protected int mana;
    protected int level;
    protected List<String> skills;
    protected Map<String, Integer> attributes;

    public GameCharacter(String name, String characterClass,
                         int health, int mana) {
        this.name           = name;
        this.characterClass = characterClass;
        this.health         = health;
        this.mana           = mana;
        this.level          = 1;
        this.skills         = new ArrayList<>();
        this.attributes     = new HashMap<>();
    }

    // Deep clone — must clone mutable collections!
    @Override
    public GameCharacter clone() {
        try {
            GameCharacter copy = (GameCharacter) super.clone();
            copy.skills     = new ArrayList<>(this.skills);     // deep copy list
            copy.attributes = new HashMap<>(this.attributes);   // deep copy map
            return copy;
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException("Clone failed", e);
        }
    }

    public GameCharacter withName(String name) {
        this.name = name;
        return this;
    }

    public GameCharacter withLevel(int level) {
        this.level = level;
        return this;
    }

    public void addSkill(String skill) { skills.add(skill); }
    public void setAttribute(String attr, int value) { attributes.put(attr, value); }

    @Override
    public String toString() {
        return String.format("%s [%s | HP:%d | MP:%d | Lvl:%d | Skills:%s]",
                name, characterClass, health, mana, level, skills);
    }
}

// Concrete character types
public class Warrior extends GameCharacter {
    public Warrior(String name) {
        super(name, "Warrior", 200, 50);
        addSkill("Slash");
        addSkill("Shield Block");
        setAttribute("Strength", 15);
        setAttribute("Defense",  12);
        System.out.println("[Warrior] Base template created for: " + name);
    }
}

public class Mage extends GameCharacter {
    public Mage(String name) {
        super(name, "Mage", 80, 200);
        addSkill("Fireball");
        addSkill("Frost Nova");
        setAttribute("Intelligence", 20);
        setAttribute("SpellPower",   18);
        System.out.println("[Mage] Base template created for: " + name);
    }
}
```

### Prototype Registry

```java
public class CharacterRegistry {
    private Map<String, GameCharacter> prototypes = new HashMap<>();

    public void register(String key, GameCharacter prototype) {
        prototypes.put(key, prototype);
        System.out.println("[Registry] Registered prototype: " + key);
    }

    public GameCharacter create(String key) {
        GameCharacter proto = prototypes.get(key);
        if (proto == null) throw new IllegalArgumentException("Unknown prototype: " + key);
        System.out.println("[Registry] Cloning prototype: " + key);
        return proto.clone(); // Fast! No new initialization
    }
}
```

### Client Code

```java
public class Main {
    public static void main(String[] args) {
        CharacterRegistry registry = new CharacterRegistry();

        // Create and register base templates (expensive operation once)
        System.out.println("=== Creating base templates ===");
        GameCharacter baseWarrior = new Warrior("BaseWarrior");
        GameCharacter baseMage    = new Mage("BaseMage");

        registry.register("warrior", baseWarrior);
        registry.register("mage",    baseMage);

        System.out.println("\n=== Creating player characters (fast clones) ===");
        // Clone and customize — much faster than new Warrior(...)!
        GameCharacter player1 = registry.create("warrior").withName("Arjun").withLevel(5);
        GameCharacter player2 = registry.create("warrior").withName("Vikram").withLevel(3);
        GameCharacter player3 = registry.create("mage").withName("Aisha").withLevel(7);

        player3.addSkill("Teleport"); // only player3 gets this skill
        player3.setAttribute("SpellPower", 25);

        System.out.println("\n=== Characters ===");
        System.out.println(player1);
        System.out.println(player2);
        System.out.println(player3);

        // Verify deep copy — modifying player3 doesn't affect baseMage
        System.out.println("\nBase Mage skills: " + baseMage.skills); // unchanged!
        System.out.println("Player3  skills: " + player3.skills);
    }
}
```

### Output

```
=== Creating base templates ===
[Warrior] Base template created for: BaseWarrior
[Mage] Base template created for: BaseMage
[Registry] Registered prototype: warrior
[Registry] Registered prototype: mage

=== Creating player characters (fast clones) ===
[Registry] Cloning prototype: warrior
[Registry] Cloning prototype: warrior
[Registry] Cloning prototype: mage

=== Characters ===
Arjun  [Warrior | HP:200 | MP:50  | Lvl:5 | Skills:[Slash, Shield Block]]
Vikram [Warrior | HP:200 | MP:50  | Lvl:3 | Skills:[Slash, Shield Block]]
Aisha  [Mage    | HP:80  | MP:200 | Lvl:7 | Skills:[Fireball, Frost Nova, Teleport]]

Base Mage skills: [Fireball, Frost Nova]
Player3  skills:  [Fireball, Frost Nova, Teleport]
```

---

## Copy Constructor Alternative (Modern Java Style)

```java
public class Configuration {
    private final String host;
    private final int port;
    private final Map<String, String> settings;

    public Configuration(String host, int port) {
        this.host = host;
        this.port = port;
        this.settings = new HashMap<>();
    }

    // Copy constructor — manual deep clone
    public Configuration(Configuration other) {
        this.host     = other.host;
        this.port     = other.port;
        this.settings = new HashMap<>(other.settings); // deep copy
    }

    public Configuration withPort(int port) {
        Configuration copy = new Configuration(this);
        // Can't change final, so use a modified copy
        return new Configuration(this.host, port);
    }
}

// Usage
Configuration prod = new Configuration("prod.db.com", 5432);
Configuration test = new Configuration(prod); // clone
```

---

## Pros and Cons

### ✅ Advantages
- **Performance** — Cloning is faster than creating from scratch
- **Reduces initialization cost** — Complex objects initialized once, cloned many times
- **Decouples client** — Client doesn't need to know concrete class names
- **Dynamic addition** — Add prototypes at runtime

### ❌ Disadvantages
- **Deep clone complexity** — Complex object graphs require careful deep-copying
- **Circular references** — Can cause issues in deep clone
- **Cloning vs constructing** — Not always intuitive which fields to copy

---

## When to Use

✔ When creating new objects is expensive (DB, network, computation)  
✔ When objects need to be created in states similar to an existing object  
✔ When you need many similar objects with slight variations  
✔ When you want to hide concrete classes from the client  

---

## Key Takeaway

> **"Why build when you can copy?"**  
> Prototype Pattern trades object construction for object cloning — ideal when initialization is expensive and you need many similar objects.
