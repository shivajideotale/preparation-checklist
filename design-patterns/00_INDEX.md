# 🧩 Gang of Four — 24 Design Patterns

> A complete reference guide with deep explanations and Java examples.

---

## 📚 Behavioral Patterns
*How objects communicate and interact with each other.*

| # | Pattern | Core Idea | Key Benefit |
|---|---|---|---|
| 01 | [Strategy](./01_Strategy_Pattern.md) | Encapsulate algorithms, make them interchangeable | Eliminate `if-else` chains; swap algorithms at runtime |
| 02 | [Observer](./02_Observer_Pattern.md) | One-to-many dependency; auto-notify on state change | Loose coupling between subject and listeners |
| 06 | [Chain of Responsibility](./06_Chain_of_Responsibility_Pattern.md) | Pass request along a chain until someone handles it | Decouple sender from receiver; dynamic handler sets |
| 08 | [Null Object](./08_Null_Object_Pattern.md) | A "do-nothing" object instead of `null` | Eliminate null checks; prevent `NullPointerException` |
| 09 | [State](./09_State_Pattern.md) | Object changes behavior when internal state changes | Replace state conditionals with state classes |
| 18 | [Command](./18_Command_Pattern.md) | Encapsulate requests as objects | Undo/Redo, queuing, logging of operations |
| 19 | [Interpreter](./19_Interpreter_Pattern.md) | Define grammar rules as classes | Evaluate mini-languages, rule engines, expressions |
| 20 | [Iterator](./20_Iterator_Pattern.md) | Sequential access without exposing internals | Uniform traversal over any collection |
| 21 | [Mediator](./21_Mediator_Pattern.md) | Centralize complex communications | Reduce M×N coupling to M+N |
| 22 | [Memento](./22_Memento_Pattern.md) | Snapshot and restore object state | Undo/Redo without breaking encapsulation |
| 23 | [Template Method](./23_Template_Method_Pattern.md) | Define skeleton; defer steps to subclasses | Reuse algorithm structure; customize steps |
| 24 | [Visitor](./24_Visitor_Pattern.md) | New operations on a structure without modifying it | Add operations without touching element classes |

---

## 🏗️ Creational Patterns
*How objects are created.*

| # | Pattern | Core Idea | Key Benefit |
|---|---|---|---|
| 04 | [Factory Method](./04_Factory_Pattern.md) | Subclasses decide which class to instantiate | Decouple client from concrete classes |
| 05 | [Abstract Factory](./05_Abstract_Factory_Pattern.md) | Create families of related objects | Ensure product family consistency |
| 12 | [Singleton](./12_Singleton_Pattern.md) | Ensure only one instance exists | Global shared resource (pool, config, logger) |
| 13 | [Builder](./13_Builder_Pattern.md) | Step-by-step construction of complex objects | Fluent API; immutable complex objects |
| 14 | [Prototype](./14_Prototype_Pattern.md) | Create objects by cloning a prototype | Fast object creation; avoid expensive init |

---

## 🔧 Structural Patterns
*How objects are composed to form larger structures.*

| # | Pattern | Core Idea | Key Benefit |
|---|---|---|---|
| 03 | [Decorator](./03_Decorator_Pattern.md) | Wrap objects to add behavior dynamically | Composition over inheritance; avoid class explosion |
| 07 | [Proxy](./07_Proxy_Pattern.md) | Surrogate that controls access to real object | Lazy loading, security, caching, logging |
| 10 | [Composite](./10_Composite_Pattern.md) | Treat single objects and groups uniformly | Recursive tree structures; part-whole hierarchies |
| 11 | [Adapter](./11_Adapter_Pattern.md) | Convert incompatible interfaces | Integrate legacy/third-party code without modification |
| 15 | [Bridge](./15_Bridge_Pattern.md) | Separate abstraction from implementation | Independent variation; avoids M×N class explosion |
| 16 | [Façade](./16_Facade_Pattern.md) | Simplified interface to complex subsystem | Single entry point; hide complexity |
| 17 | [Flyweight](./17_Flyweight_Pattern.md) | Share fine-grained objects to save memory | Handle millions of objects efficiently |

---

## ⚡ Quick Pattern Picker

### "I need to…"

| Need | Use Pattern |
|---|---|
| **Swap algorithms at runtime** | Strategy |
| **Notify multiple objects when state changes** | Observer |
| **Add behavior to objects without subclassing** | Decorator |
| **Create objects without knowing their concrete class** | Factory Method |
| **Create families of consistent related objects** | Abstract Factory |
| **Ensure only one instance of a class** | Singleton |
| **Build complex objects step by step** | Builder |
| **Clone expensive objects** | Prototype |
| **Pass requests along a chain** | Chain of Responsibility |
| **Control access to an object** | Proxy |
| **Treat trees and leaves the same way** | Composite |
| **Make incompatible interfaces work together** | Adapter |
| **Separate abstraction from implementation** | Bridge |
| **Simplify a complex subsystem** | Façade |
| **Handle millions of fine-grained objects** | Flyweight |
| **Avoid null checks** | Null Object |
| **Change behavior based on internal state** | State |
| **Implement Undo/Redo (action as object)** | Command |
| **Interpret a simple language or grammar** | Interpreter |
| **Traverse a collection uniformly** | Iterator |
| **Centralize communication between objects** | Mediator |
| **Save/restore object state (Undo/snapshots)** | Memento |
| **Define algorithm skeleton, fill in steps** | Template Method |
| **Add new operations to a structure without changing it** | Visitor |

---

## 🔑 Design Principles Behind All Patterns

| Principle | Meaning | Patterns That Demonstrate It |
|---|---|---|
| **Single Responsibility** | One class = one reason to change | Strategy, Command, Observer |
| **Open/Closed** | Open for extension, closed for modification | Strategy, Decorator, Observer |
| **Liskov Substitution** | Subclasses must be substitutable | Template Method, Composite |
| **Interface Segregation** | Small, specific interfaces | Strategy, Command, Iterator |
| **Dependency Inversion** | Depend on abstractions, not concretions | Factory, Abstract Factory, Strategy |
| **Favor Composition** | Composition over inheritance | Strategy, Decorator, Composite |
| **Program to Interface** | Code to interfaces, not implementations | All patterns |
| **Loose Coupling** | Minimize dependencies between objects | Observer, Mediator, Facade |
| **Hollywood Principle** | "Don't call us, we'll call you" | Template Method, Observer |

---

## 📘 Pattern Relationships

```
Creational
  Factory Method ──extends──► Abstract Factory (family of factories)
  Builder ──────────────────── creates complex objects
  Prototype ────────────────── clone instead of new
  Singleton ────────────────── one factory is often a Singleton

Structural
  Decorator ─── Proxy (both wrap — Decorator adds, Proxy controls)
  Adapter  ─── Facade (both simplify — Adapter converts, Facade hides)
  Composite ─── Iterator (Composite builds tree; Iterator traverses it)
  Composite ─── Visitor  (Composite builds tree; Visitor operates on it)
  Bridge ────── Strategy (both use composition; Bridge is structural)

Behavioral
  Strategy ─── State (both change behavior; State does it internally)
  Command  ─── Memento (Command stores actions; Memento stores state)
  Observer ─── Mediator (both coordinate; Mediator is centralized)
  Template ─── Strategy (inheritance vs composition for algorithms)
  Iterator ─── Visitor  (Iterator traverses; Visitor operates while traversing)
```

---

## 🛠️ Real-World Java Mapping

| Java API | Pattern |
|---|---|
| `java.util.Comparator` | Strategy |
| `java.util.Iterator` | Iterator |
| `java.io.InputStream` wrappers | Decorator |
| `java.lang.reflect.Proxy` | Proxy |
| `java.util.Calendar.getInstance()` | Factory Method |
| `Integer.valueOf()` cache | Flyweight |
| `java.util.Observable` (deprecated) | Observer |
| `javax.swing.undo.UndoManager` | Memento |
| `java.lang.Runnable` | Command |
| `HttpServlet.service()` | Template Method |
| `java.awt.Component` hierarchy | Composite |

---

*All patterns include: Intent → Problem → Structure → Java Example → Real-World Usage → Pros & Cons → When to Use → Key Takeaway*
