# 🧩 LLD + S.O.L.I.D + 24 Design Patterns

> A complete reference guide — from foundational concepts to production-ready design — with deep explanations, Java examples, and Mermaid UML class diagrams.

---

## 📖 Reading Order (Recommended)

```
01 → Understand WHAT LLD is and HOW it works
02 → Learn the 5 SOLID Principles (the rules)
03–26 → Apply them through 24 Design Patterns (the tools)
27 → Quick-Reference Summary for fast pattern selection
```

---

## 📘 Foundation

| # | File | What It Covers |
|---|---|---|
| **01** | [LLD Introduction](./01_LLD_Introduction.md) | What is LLD, LLD vs HLD, the full design process, UML basics, Parking Lot end-to-end example, common interview problems |
| **02** | [S.O.L.I.D Principles](./02_SOLID_Principles.md) | All 5 principles — SRP, OCP, LSP, ISP, DIP — each with ❌ violation, ✅ fix, and Mermaid class diagram |

---

## 🏗️ Creational Patterns
*How objects are created.*

| # | Pattern | Core Idea | Key Benefit |
|---|---|---|---|
| 06 | [Factory Method](./06_Factory_Pattern.md) | Subclasses decide which class to instantiate | Decouple client from concrete classes |
| 07 | [Abstract Factory](./07_Abstract_Factory_Pattern.md) | Create families of related objects | Ensure product family consistency |
| 14 | [Singleton](./14_Singleton_Pattern.md) | Ensure only one instance exists | Shared resource — pool, config, logger |
| 15 | [Builder](./15_Builder_Pattern.md) | Step-by-step construction of complex objects | Fluent API; immutable complex objects |
| 16 | [Prototype](./16_Prototype_Pattern.md) | Create objects by cloning a prototype | Fast creation; avoid expensive init |

---

## 🔧 Structural Patterns
*How objects are composed to form larger structures.*

| # | Pattern | Core Idea | Key Benefit |
|---|---|---|---|
| 05 | [Decorator](./05_Decorator_Pattern.md) | Wrap objects to add behavior dynamically | Composition over inheritance; no class explosion |
| 09 | [Proxy](./09_Proxy_Pattern.md) | Surrogate that controls access to real object | Lazy loading, security, caching, logging |
| 12 | [Composite](./12_Composite_Pattern.md) | Treat single objects and groups uniformly | Recursive tree structures; part-whole hierarchies |
| 13 | [Adapter](./13_Adapter_Pattern.md) | Convert incompatible interfaces | Integrate legacy/third-party code without modification |
| 17 | [Bridge](./17_Bridge_Pattern.md) | Separate abstraction from implementation | Independent variation; avoids M×N class explosion |
| 18 | [Façade](./18_Facade_Pattern.md) | Simplified interface to a complex subsystem | Single entry point; hide complexity |
| 19 | [Flyweight](./19_Flyweight_Pattern.md) | Share fine-grained objects to save memory | Handle millions of objects efficiently |

---

## ⚡ Behavioral Patterns
*How objects communicate and interact.*

| # | Pattern | Core Idea | Key Benefit |
|---|---|---|---|
| 03 | [Strategy](./03_Strategy_Pattern.md) | Encapsulate algorithms, make them interchangeable | Eliminate `if-else`; swap algorithms at runtime |
| 04 | [Observer](./04_Observer_Pattern.md) | One-to-many dependency; auto-notify on change | Loose coupling between subject and listeners |
| 08 | [Chain of Responsibility](./08_Chain_of_Responsibility_Pattern.md) | Pass request along a chain until handled | Decouple sender from receiver |
| 10 | [Null Object](./10_Null_Object_Pattern.md) | A "do-nothing" object instead of `null` | Eliminate null checks; prevent NPE |
| 11 | [State](./11_State_Pattern.md) | Object changes behavior when state changes | Replace state conditionals with state classes |
| 20 | [Command](./20_Command_Pattern.md) | Encapsulate a request as an object | Undo/Redo, queuing, logging of operations |
| 21 | [Interpreter](./21_Interpreter_Pattern.md) | Define grammar rules as classes | Evaluate mini-languages, rule engines |
| 22 | [Iterator](./22_Iterator_Pattern.md) | Sequential access without exposing internals | Uniform traversal over any collection |
| 23 | [Mediator](./23_Mediator_Pattern.md) | Centralise complex communications | Reduce M×N coupling to M+N |
| 24 | [Memento](./24_Memento_Pattern.md) | Snapshot and restore object state | Undo/Redo without breaking encapsulation |
| 25 | [Template Method](./25_Template_Method_Pattern.md) | Define skeleton; defer steps to subclasses | Reuse algorithm structure; customise steps |
| 26 | [Visitor](./26_Visitor_Pattern.md) | New operations on structure without modifying it | Add operations without touching element classes |

---

## ⚡ Quick Picker — "I need to…"

| Need | Go To |
|---|---|
| Understand how LLD works end-to-end | **01 — LLD Introduction** |
| Learn the foundational design rules | **02 — SOLID Principles** |
| Swap algorithms at runtime | Strategy |
| Notify multiple objects on state change | Observer |
| Add behavior to objects without subclassing | Decorator |
| Create objects without knowing their concrete class | Factory Method |
| Create families of consistent related objects | Abstract Factory |
| Ensure only one instance of a class | Singleton |
| Build complex objects step by step | Builder |
| Clone expensive objects | Prototype |
| Pass a request along a chain of handlers | Chain of Responsibility |
| Control access to an object | Proxy |
| Treat trees and leaves the same way | Composite |
| Make incompatible interfaces work together | Adapter |
| Separate abstraction from implementation | Bridge |
| Simplify a complex subsystem | Façade |
| Handle millions of fine-grained objects | Flyweight |
| Avoid null checks and NullPointerException | Null Object |
| Change behavior based on internal state | State |
| Implement Undo/Redo (action as object) | Command |
| Interpret a simple language or grammar | Interpreter |
| Traverse a collection uniformly | Iterator |
| Centralise communication between many objects | Mediator |
| Save and restore object state (snapshots) | Memento |
| Define algorithm skeleton, subclass fills steps | Template Method |
| Add new operations to a structure without changing it | Visitor |

---

## 🔑 SOLID → Patterns Map

| SOLID Principle | Patterns That Embody It |
|---|---|
| **S — Single Responsibility** | Command, Observer, Strategy |
| **O — Open / Closed** | Strategy, Decorator, Observer, Template Method |
| **L — Liskov Substitution** | Template Method, Composite |
| **I — Interface Segregation** | Strategy, Command, Iterator |
| **D — Dependency Inversion** | Factory Method, Abstract Factory, Strategy, Proxy |

---

## 🛠️ Real-World Java Mapping

| Java API / Framework | Pattern |
|---|---|
| `java.util.Comparator` | Strategy |
| `java.util.Iterator` | Iterator |
| `java.io.InputStream` wrappers | Decorator |
| `java.lang.reflect.Proxy` | Proxy |
| `java.util.Calendar.getInstance()` | Factory Method |
| `Integer.valueOf()` cache (-128 to 127) | Flyweight |
| `java.util.EventListener` | Observer |
| `javax.swing.undo.UndoManager` | Memento |
| `java.lang.Runnable` | Command |
| `HttpServlet.service()` | Template Method |
| `java.awt.Component` hierarchy | Composite |
| Spring `@Autowired` / `ApplicationContext` | Dependency Inversion |
| Spring `@Transactional` / `@Cacheable` | Proxy |
| Spring `@EventListener` | Observer |

---

## 📋 Quick-Reference Summary

| # | File | What It Covers |
|---|---|---|
| **27** | [Pattern Quick-Reference Summary](./27_Pattern_Quick_Reference_Summary.md) | Scenario → Pattern decision guide, anti-pattern warning signs, pattern combination recipes, one-line definitions, full comparison table |

---

*Every file: Intent → Problem → ❌ Violation → ✅ Solution → Full Java Code → Output → Mermaid UML → Real-World Usage → Pros & Cons → When to Use → Key Takeaway*
