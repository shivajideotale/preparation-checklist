# Builder Pattern

## Category
**Creational Design Pattern**

---

## Intent
Separate the construction of a complex object from its representation so that the same construction process can create different representations.

---

## The Problem It Solves

You want to create a `Pizza` object with many optional attributes:
- Crust: thin / thick / stuffed
- Size: S / M / L / XL
- Sauce: tomato / pesto / white
- Cheese: mozzarella / cheddar / vegan
- Toppings: pepperoni, olives, mushrooms, jalapeños...

**Problem 1: Telescoping Constructor Anti-Pattern**
```java
// Ugly — which param is which?
Pizza pizza = new Pizza("thin", "L", "tomato", "mozzarella", true, false, true, false, true);
```

**Problem 2: JavaBean with setters (mutable, inconsistent state)**
```java
Pizza pizza = new Pizza();
pizza.setCrust("thin");
pizza.setSize("L");
// Forgot to set sauce — pizza is in incomplete state!
```

**Builder Pattern** provides a fluent, step-by-step construction API that results in an immutable, fully configured object.

---

## Structure

```
Director (optional)
  └── uses ──► Builder (interface)
                    └── ConcreteBuilder
                          ├── buildPartA()
                          ├── buildPartB()
                          └── build() → Product
```

---

## Java Example — Pizza Builder

### Immutable Product

```java
public final class Pizza {
    // All final — immutable after construction
    private final String size;
    private final String crust;
    private final String sauce;
    private final String cheese;
    private final List<String> toppings;
    private final boolean extraCheese;
    private final boolean glutenFree;

    // Private constructor — only Builder can call it
    private Pizza(Builder builder) {
        this.size        = builder.size;
        this.crust       = builder.crust;
        this.sauce       = builder.sauce;
        this.cheese      = builder.cheese;
        this.toppings    = Collections.unmodifiableList(new ArrayList<>(builder.toppings));
        this.extraCheese = builder.extraCheese;
        this.glutenFree  = builder.glutenFree;
    }

    // Getters only — no setters
    public String getSize()         { return size; }
    public String getCrust()        { return crust; }
    public String getSauce()        { return sauce; }
    public String getCheese()       { return cheese; }
    public List<String> getToppings(){ return toppings; }
    public boolean isExtraCheese()  { return extraCheese; }
    public boolean isGlutenFree()   { return glutenFree; }

    @Override
    public String toString() {
        return String.format(
            "🍕 Pizza [%s | %s crust | %s sauce | %s cheese | Toppings: %s | Extra Cheese: %s | GF: %s]",
            size, crust, sauce, cheese, toppings, extraCheese, glutenFree
        );
    }

    // ─────────────── STATIC INNER BUILDER ───────────────
    public static class Builder {
        // Required parameters
        private final String size;

        // Optional parameters with defaults
        private String crust       = "thin";
        private String sauce       = "tomato";
        private String cheese      = "mozzarella";
        private List<String> toppings = new ArrayList<>();
        private boolean extraCheese = false;
        private boolean glutenFree  = false;

        public Builder(String size) {
            if (size == null || size.isBlank()) {
                throw new IllegalArgumentException("Size is required");
            }
            this.size = size;
        }

        // Each method returns 'this' for fluent chaining
        public Builder crust(String crust) {
            this.crust = crust;
            return this;
        }

        public Builder sauce(String sauce) {
            this.sauce = sauce;
            return this;
        }

        public Builder cheese(String cheese) {
            this.cheese = cheese;
            return this;
        }

        public Builder topping(String topping) {
            this.toppings.add(topping);
            return this;
        }

        public Builder toppings(String... toppings) {
            this.toppings.addAll(Arrays.asList(toppings));
            return this;
        }

        public Builder extraCheese() {
            this.extraCheese = true;
            return this;
        }

        public Builder glutenFree() {
            this.glutenFree = true;
            return this;
        }

        // Validation + construction
        public Pizza build() {
            validate();
            return new Pizza(this);
        }

        private void validate() {
            List<String> validSizes = List.of("S", "M", "L", "XL");
            if (!validSizes.contains(size.toUpperCase())) {
                throw new IllegalStateException("Invalid size: " + size);
            }
        }
    }
}
```

### Client Code

```java
public class PizzaShop {
    public static void main(String[] args) {

        // Classic Margherita
        Pizza margherita = new Pizza.Builder("M")
                .crust("thin")
                .sauce("tomato")
                .topping("basil")
                .build();
        System.out.println(margherita);

        // Loaded meat pizza
        Pizza meatLover = new Pizza.Builder("XL")
                .crust("thick")
                .sauce("tomato")
                .cheese("cheddar")
                .toppings("pepperoni", "sausage", "bacon", "ham")
                .extraCheese()
                .build();
        System.out.println(meatLover);

        // Vegan gluten-free pizza
        Pizza vegan = new Pizza.Builder("L")
                .crust("thin")
                .sauce("pesto")
                .cheese("vegan")
                .toppings("mushrooms", "olives", "spinach", "artichokes")
                .glutenFree()
                .build();
        System.out.println(vegan);

        // Quick minimal pizza (uses all defaults)
        Pizza simple = new Pizza.Builder("S").build();
        System.out.println(simple);
    }
}
```

### Output

```
🍕 Pizza [M | thin crust | tomato sauce | mozzarella cheese | Toppings: [basil] | Extra Cheese: false | GF: false]
🍕 Pizza [XL | thick crust | tomato sauce | cheddar cheese | Toppings: [pepperoni, sausage, bacon, ham] | Extra Cheese: true | GF: false]
🍕 Pizza [L | thin crust | pesto sauce | vegan cheese | Toppings: [mushrooms, olives, spinach, artichokes] | Extra Cheese: false | GF: true]
🍕 Pizza [S | thin crust | tomato sauce | mozzarella cheese | Toppings: [] | Extra Cheese: false | GF: false]
```

---

## Java Example 2 — HTTP Request Builder

```java
public class HttpRequest {
    private final String url;
    private final String method;
    private final Map<String, String> headers;
    private final String body;
    private final int timeoutMs;

    private HttpRequest(Builder b) {
        this.url       = b.url;
        this.method    = b.method;
        this.headers   = Map.copyOf(b.headers);
        this.body      = b.body;
        this.timeoutMs = b.timeoutMs;
    }

    public static class Builder {
        private final String url;
        private String method              = "GET";
        private Map<String, String> headers = new HashMap<>();
        private String body                = null;
        private int timeoutMs              = 5000;

        public Builder(String url) { this.url = url; }

        public Builder method(String method) { this.method = method; return this; }
        public Builder header(String key, String value) { headers.put(key, value); return this; }
        public Builder body(String body) { this.body = body; return this; }
        public Builder timeout(int ms) { this.timeoutMs = ms; return this; }

        public Builder bearerToken(String token) {
            return header("Authorization", "Bearer " + token);
        }

        public Builder jsonContent() {
            return header("Content-Type", "application/json");
        }

        public HttpRequest build() {
            if (body != null && method.equals("GET")) {
                throw new IllegalStateException("GET requests cannot have a body");
            }
            return new HttpRequest(this);
        }
    }

    @Override
    public String toString() {
        return String.format("%s %s | Headers: %s | Body: %s | Timeout: %dms",
                method, url, headers, body, timeoutMs);
    }
}

// Usage
HttpRequest request = new HttpRequest.Builder("https://api.example.com/users")
        .method("POST")
        .bearerToken("eyJhbGciOiJIUzI1NiJ9...")
        .jsonContent()
        .body("{\"name\":\"Rahul\",\"email\":\"rahul@example.com\"}")
        .timeout(10_000)
        .build();

System.out.println(request);
```

---

## Director Pattern (Optional)

When the same construction steps always appear in a fixed order, a **Director** can orchestrate them:

```java
public class PizzaDirector {
    private Pizza.Builder builder;

    public PizzaDirector(Pizza.Builder builder) {
        this.builder = builder;
    }

    // Pre-defined "recipes"
    public Pizza makeMargherita() {
        return builder
                .crust("thin")
                .sauce("tomato")
                .cheese("mozzarella")
                .topping("basil")
                .build();
    }

    public Pizza makeBBQChicken() {
        return builder
                .crust("thick")
                .sauce("BBQ")
                .cheese("cheddar")
                .toppings("chicken", "onions", "peppers")
                .extraCheese()
                .build();
    }
}

// Client
PizzaDirector director = new PizzaDirector(new Pizza.Builder("L"));
Pizza p = director.makeMargherita();
```

---

## Real-World Java Examples

| Library | Builder |
|---|---|
| `StringBuilder` | Classic builder for strings |
| `java.net.http.HttpRequest.newBuilder()` | Java 11+ HTTP client |
| `Stream.Builder` | Builds streams |
| Lombok `@Builder` | Generates builder automatically |
| OkHttp `Request.Builder` | HTTP request builder |
| Gson `GsonBuilder` | Configure Gson parser |
| `AlertDialog.Builder` (Android) | Android dialog construction |

```java
// StringBuilder IS a Builder
String result = new StringBuilder()
        .append("Hello")
        .append(", ")
        .append("World")
        .append("!")
        .toString();

// Java 11 HTTP Client Builder
HttpRequest req = HttpRequest.newBuilder()
        .uri(URI.create("https://api.example.com"))
        .header("Accept", "application/json")
        .timeout(Duration.ofSeconds(10))
        .GET()
        .build();
```

---

## Pros and Cons

### ✅ Advantages
- **Readable** — Fluent API reads like English
- **Immutable objects** — Product can be final after `build()`
- **Validation** — `build()` can enforce business rules
- **No telescoping constructors** — No confusing parameter lists
- **Optional parameters** — Easily support default values

### ❌ Disadvantages
- **Verbose** — More code than a simple constructor
- **Mutable Builder** — Builder itself is mutable (but Product is not)
- **Duplicated fields** — Fields defined in both Builder and Product

---

## When to Use

✔ When constructors would need many parameters (4+ is a rule of thumb)  
✔ When you need optional parameters with defaults  
✔ When you want immutable objects  
✔ When object creation involves multiple steps or validation  
✔ When different configurations of an object are needed  

---

## Key Takeaway

> **"Step by step, fluently."**  
> Builder Pattern separates the "how to build" from the "what is built," providing a readable, flexible, and safe API for constructing complex objects — especially immutable ones.
