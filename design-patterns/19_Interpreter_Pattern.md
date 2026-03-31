# Interpreter Pattern

## Category
**Behavioral Design Pattern**

---

## Intent
Given a language, define a representation for its grammar along with an interpreter that uses the representation to interpret sentences in the language.

---

## The Problem It Solves

You need to evaluate simple expressions or commands defined in a custom mini-language: SQL-like queries, mathematical expressions, boolean rule engines, configuration DSLs.

Without Interpreter, you write a complex parser with nested conditionals. Interpreter Pattern models each grammar rule as a class.

---

## Structure

```
AbstractExpression
  ├── TerminalExpression     → leaf rule (variable, literal)
  └── NonTerminalExpression  → composite rule (AND, OR, +, *)
        └── interpret(Context) → recursively evaluates children
```

---

## Java Example — Boolean Rule Engine

```java
// Abstract Expression
public interface Expression {
    boolean interpret(Map<String, Boolean> context);
}

// Terminal Expressions
public class VariableExpression implements Expression {
    private String name;

    public VariableExpression(String name) { this.name = name; }

    @Override
    public boolean interpret(Map<String, Boolean> context) {
        return context.getOrDefault(name, false);
    }

    @Override
    public String toString() { return name; }
}

// Non-Terminal Expressions
public class AndExpression implements Expression {
    private Expression left, right;

    public AndExpression(Expression left, Expression right) {
        this.left  = left;
        this.right = right;
    }

    @Override
    public boolean interpret(Map<String, Boolean> context) {
        return left.interpret(context) && right.interpret(context);
    }

    @Override
    public String toString() { return "(" + left + " AND " + right + ")"; }
}

public class OrExpression implements Expression {
    private Expression left, right;

    public OrExpression(Expression left, Expression right) {
        this.left  = left;
        this.right = right;
    }

    @Override
    public boolean interpret(Map<String, Boolean> context) {
        return left.interpret(context) || right.interpret(context);
    }

    @Override
    public String toString() { return "(" + left + " OR " + right + ")"; }
}

public class NotExpression implements Expression {
    private Expression expression;

    public NotExpression(Expression expression) { this.expression = expression; }

    @Override
    public boolean interpret(Map<String, Boolean> context) {
        return !expression.interpret(context);
    }

    @Override
    public String toString() { return "NOT(" + expression + ")"; }
}

// Client
public class RuleEngine {
    public static void main(String[] args) {
        // Build rule: (isLoggedIn AND (isAdmin OR isPremium)) AND NOT isBanned
        Expression isLoggedIn = new VariableExpression("isLoggedIn");
        Expression isAdmin    = new VariableExpression("isAdmin");
        Expression isPremium  = new VariableExpression("isPremium");
        Expression isBanned   = new VariableExpression("isBanned");

        Expression canAccessPremium = new AndExpression(
                isLoggedIn,
                new AndExpression(
                        new OrExpression(isAdmin, isPremium),
                        new NotExpression(isBanned)
                )
        );

        System.out.println("Rule: " + canAccessPremium);

        // Test different users
        Map<String, Boolean> adminUser = Map.of(
                "isLoggedIn", true, "isAdmin", true, "isPremium", false, "isBanned", false);
        Map<String, Boolean> bannedUser = Map.of(
                "isLoggedIn", true, "isAdmin", false, "isPremium", true, "isBanned", true);
        Map<String, Boolean> guestUser = Map.of(
                "isLoggedIn", false, "isAdmin", false, "isPremium", false, "isBanned", false);

        System.out.println("Admin user can access: "  + canAccessPremium.interpret(adminUser));  // true
        System.out.println("Banned user can access: " + canAccessPremium.interpret(bannedUser)); // false
        System.out.println("Guest user can access: "  + canAccessPremium.interpret(guestUser));  // false
    }
}
```

### Output

```
Rule: (isLoggedIn AND ((isAdmin OR isPremium) AND NOT(isBanned)))
Admin user can access:  true
Banned user can access: false
Guest user can access:  false
```

---

## Math Expression Interpreter

```java
public interface MathExpression {
    int interpret();
}

public class Number implements MathExpression {
    private int value;
    public Number(int value) { this.value = value; }
    @Override public int interpret() { return value; }
}

public class Add implements MathExpression {
    private MathExpression left, right;
    public Add(MathExpression l, MathExpression r) { left = l; right = r; }
    @Override public int interpret() { return left.interpret() + right.interpret(); }
}

public class Multiply implements MathExpression {
    private MathExpression left, right;
    public Multiply(MathExpression l, MathExpression r) { left = l; right = r; }
    @Override public int interpret() { return left.interpret() * right.interpret(); }
}

// (3 + 4) * 2 = 14
MathExpression expr = new Multiply(
        new Add(new Number(3), new Number(4)),
        new Number(2)
);
System.out.println("Result: " + expr.interpret()); // 14
```

---

## Real-World Java Examples

| Usage | Interpreter |
|---|---|
| `java.util.regex.Pattern` | Regex grammar interpreter |
| Spring Expression Language (SpEL) | `#{user.name}` expression interpreter |
| SQL WHERE clauses | Grammar rules for query evaluation |
| JVM bytecode | JVM interprets bytecode grammar |

---

## Pros and Cons

### ✅ Advantages
- **Easy to change grammar** — Add new expression types easily
- **Easy to extend** — New rules = new classes
- **Composable** — Expressions compose recursively

### ❌ Disadvantages
- **Complex grammars** — Too many classes for complex languages (use parser generators instead)
- **Performance** — Deep tree evaluation can be slow
- **Not for large grammars** — Best for simple mini-languages

---

## When to Use

✔ When you need to interpret simple languages, rules, or expressions  
✔ SQL filters, search query parsers, business rule engines, configuration DSLs  
✔ When grammar is simple and performance is not critical  

---

## Key Takeaway

> **"Model grammar as objects, evaluate by composing them."**
