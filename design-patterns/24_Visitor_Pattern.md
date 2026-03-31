# Visitor Pattern

## Category
**Behavioral Design Pattern**

---

## Intent
Represent an **operation to be performed on elements** of an object structure. Visitor lets you define a new operation without changing the classes of the elements on which it operates.

---

## The Problem It Solves

You have a complex object structure — an AST (Abstract Syntax Tree), a document, or a shape collection — and you need to add many different operations:
- Export to XML
- Export to JSON
- Calculate area
- Draw to canvas
- Validate

Without Visitor, each new operation requires modifying every element class. With Visitor, you add a new `Visitor` class per operation — the element classes never change.

---

## Double Dispatch

Visitor relies on **double dispatch** — the operation selected depends on:
1. The type of the **Visitor** (which operation)
2. The type of the **Element** (which element)

Java's regular method dispatch (single dispatch) only considers the runtime type of the receiver. Visitor adds a second level of dispatch via `accept()`.

```
element.accept(visitor)
         ↓
visitor.visit(this)  ← now visitor knows the concrete element type!
```

---

## Structure

```
Visitor (interface)
  ├── visit(ConcreteElementA)
  └── visit(ConcreteElementB)

ConcreteVisitor
  ├── visit(ConcreteElementA) { ... operation A on A }
  └── visit(ConcreteElementB) { ... operation B on B }

Element (interface)
  └── accept(Visitor)

ConcreteElementA
  └── accept(Visitor v) { v.visit(this); }

ConcreteElementB
  └── accept(Visitor v) { v.visit(this); }
```

---

## Java Example — Document Structure

### Step 1: Visitor Interface

```java
public interface DocumentVisitor {
    void visit(Heading heading);
    void visit(Paragraph paragraph);
    void visit(Image image);
    void visit(Table table);
    void visit(CodeBlock codeBlock);
}
```

### Step 2: Element Interface

```java
public interface DocumentElement {
    void accept(DocumentVisitor visitor);
    String getContent();
}
```

### Step 3: Concrete Elements

```java
public class Heading implements DocumentElement {
    private String text;
    private int    level; // 1=H1, 2=H2, etc.

    public Heading(String text, int level) {
        this.text  = text;
        this.level = level;
    }

    public int getLevel() { return level; }

    @Override
    public String getContent() { return text; }

    @Override
    public void accept(DocumentVisitor visitor) {
        visitor.visit(this); // double dispatch
    }
}

public class Paragraph implements DocumentElement {
    private String text;

    public Paragraph(String text) { this.text = text; }

    @Override
    public String getContent() { return text; }

    @Override
    public void accept(DocumentVisitor visitor) {
        visitor.visit(this);
    }
}

public class Image implements DocumentElement {
    private String src;
    private String altText;
    private int    widthPx;
    private int    heightPx;

    public Image(String src, String altText, int widthPx, int heightPx) {
        this.src      = src;
        this.altText  = altText;
        this.widthPx  = widthPx;
        this.heightPx = heightPx;
    }

    public String getSrc()     { return src;      }
    public String getAltText() { return altText;  }
    public int getWidth()      { return widthPx;  }
    public int getHeight()     { return heightPx; }

    @Override
    public String getContent() { return altText; }

    @Override
    public void accept(DocumentVisitor visitor) {
        visitor.visit(this);
    }
}

public class Table implements DocumentElement {
    private List<List<String>> rows;
    private List<String>       headers;

    public Table(List<String> headers, List<List<String>> rows) {
        this.headers = headers;
        this.rows    = rows;
    }

    public List<String> getHeaders() { return headers; }
    public List<List<String>> getRows() { return rows; }

    @Override
    public String getContent() { return "Table[" + headers.size() + " cols × " + rows.size() + " rows]"; }

    @Override
    public void accept(DocumentVisitor visitor) {
        visitor.visit(this);
    }
}

public class CodeBlock implements DocumentElement {
    private String code;
    private String language;

    public CodeBlock(String code, String language) {
        this.code     = code;
        this.language = language;
    }

    public String getLanguage() { return language; }

    @Override
    public String getContent() { return code; }

    @Override
    public void accept(DocumentVisitor visitor) {
        visitor.visit(this);
    }
}
```

### Step 4: Concrete Visitors — Different Operations

```java
// Visitor 1: Export to HTML
public class HTMLExportVisitor implements DocumentVisitor {
    private StringBuilder html = new StringBuilder();

    @Override
    public void visit(Heading heading) {
        int lvl = heading.getLevel();
        html.append(String.format("<h%d>%s</h%d>%n", lvl, heading.getContent(), lvl));
    }

    @Override
    public void visit(Paragraph paragraph) {
        html.append("<p>").append(paragraph.getContent()).append("</p>\n");
    }

    @Override
    public void visit(Image image) {
        html.append(String.format("<img src=\"%s\" alt=\"%s\" width=\"%d\" height=\"%d\"/>%n",
                image.getSrc(), image.getAltText(), image.getWidth(), image.getHeight()));
    }

    @Override
    public void visit(Table table) {
        html.append("<table>\n<tr>");
        table.getHeaders().forEach(h -> html.append("<th>").append(h).append("</th>"));
        html.append("</tr>\n");
        for (List<String> row : table.getRows()) {
            html.append("<tr>");
            row.forEach(cell -> html.append("<td>").append(cell).append("</td>"));
            html.append("</tr>\n");
        }
        html.append("</table>\n");
    }

    @Override
    public void visit(CodeBlock block) {
        html.append(String.format("<pre><code class=\"lang-%s\">%s</code></pre>%n",
                block.getLanguage(), block.getContent()));
    }

    public String getHTML() { return html.toString(); }
}

// Visitor 2: Export to Markdown
public class MarkdownExportVisitor implements DocumentVisitor {
    private StringBuilder md = new StringBuilder();

    @Override
    public void visit(Heading heading) {
        String prefix = "#".repeat(heading.getLevel());
        md.append(prefix).append(" ").append(heading.getContent()).append("\n\n");
    }

    @Override
    public void visit(Paragraph paragraph) {
        md.append(paragraph.getContent()).append("\n\n");
    }

    @Override
    public void visit(Image image) {
        md.append(String.format("![%s](%s)%n%n", image.getAltText(), image.getSrc()));
    }

    @Override
    public void visit(Table table) {
        // Header row
        md.append("| ").append(String.join(" | ", table.getHeaders())).append(" |\n");
        // Separator
        md.append("|").append(" --- |".repeat(table.getHeaders().size())).append("\n");
        // Data rows
        for (List<String> row : table.getRows()) {
            md.append("| ").append(String.join(" | ", row)).append(" |\n");
        }
        md.append("\n");
    }

    @Override
    public void visit(CodeBlock block) {
        md.append("```").append(block.getLanguage()).append("\n")
          .append(block.getContent()).append("\n```\n\n");
    }

    public String getMarkdown() { return md.toString(); }
}

// Visitor 3: Word Count Analyzer
public class WordCountVisitor implements DocumentVisitor {
    private int totalWords   = 0;
    private int headingWords = 0;
    private int paraWords    = 0;
    private int codeWords    = 0;
    private int imageCount   = 0;

    private int countWords(String text) {
        return text.isBlank() ? 0 : text.trim().split("\\s+").length;
    }

    @Override
    public void visit(Heading heading) {
        int w = countWords(heading.getContent());
        headingWords += w;
        totalWords   += w;
    }

    @Override
    public void visit(Paragraph paragraph) {
        int w = countWords(paragraph.getContent());
        paraWords  += w;
        totalWords += w;
    }

    @Override
    public void visit(Image image)    { imageCount++; }

    @Override
    public void visit(Table table) {
        // Count header words
        table.getHeaders().forEach(h -> totalWords += countWords(h));
        table.getRows().forEach(row -> row.forEach(cell -> totalWords += countWords(cell)));
    }

    @Override
    public void visit(CodeBlock block) {
        int w = countWords(block.getContent());
        codeWords  += w;
        totalWords += w;
    }

    public void printStats() {
        System.out.println("=== Document Statistics ===");
        System.out.println("Total words    : " + totalWords);
        System.out.println("Heading words  : " + headingWords);
        System.out.println("Paragraph words: " + paraWords);
        System.out.println("Code words     : " + codeWords);
        System.out.println("Images         : " + imageCount);
    }
}

// Visitor 4: Accessibility Checker
public class AccessibilityVisitor implements DocumentVisitor {
    private List<String> issues = new ArrayList<>();

    @Override
    public void visit(Heading heading) {
        if (heading.getContent().isBlank()) {
            issues.add("⚠️  Empty heading at level " + heading.getLevel());
        }
    }

    @Override
    public void visit(Paragraph paragraph) {
        if (paragraph.getContent().length() > 1000) {
            issues.add("⚠️  Paragraph exceeds 1000 chars — consider splitting");
        }
    }

    @Override
    public void visit(Image image) {
        if (image.getAltText() == null || image.getAltText().isBlank()) {
            issues.add("❌ Image missing alt text: " + image.getSrc());
        }
    }

    @Override
    public void visit(Table table) {
        if (table.getHeaders().isEmpty()) {
            issues.add("❌ Table has no headers — not screen-reader friendly");
        }
    }

    @Override
    public void visit(CodeBlock block) {
        if (block.getLanguage() == null || block.getLanguage().isBlank()) {
            issues.add("⚠️  Code block has no language specified");
        }
    }

    public void printReport() {
        System.out.println("=== Accessibility Report ===");
        if (issues.isEmpty()) {
            System.out.println("✅ No accessibility issues found!");
        } else {
            issues.forEach(System.out::println);
        }
    }
}
```

### Step 5: Document (Object Structure)

```java
public class Document {
    private List<DocumentElement> elements = new ArrayList<>();
    private String title;

    public Document(String title) { this.title = title; }

    public void add(DocumentElement element) { elements.add(element); }

    // Accept a visitor — applies it to all elements
    public void accept(DocumentVisitor visitor) {
        System.out.println("Visiting document: " + title);
        elements.forEach(e -> e.accept(visitor));
    }
}
```

### Step 6: Client Code

```java
public class Main {
    public static void main(String[] args) {
        // Build document structure
        Document doc = new Document("Java Design Patterns Guide");

        doc.add(new Heading("Introduction", 1));
        doc.add(new Paragraph("Design patterns are reusable solutions to common problems in software design."));
        doc.add(new Image("patterns.png", "UML diagram of patterns", 800, 600));
        doc.add(new Heading("Creational Patterns", 2));
        doc.add(new Paragraph("These patterns deal with object creation mechanisms."));
        doc.add(new Table(
                List.of("Pattern", "Intent"),
                List.of(
                        List.of("Singleton", "Ensure only one instance"),
                        List.of("Builder",   "Construct complex objects step by step"),
                        List.of("Factory",   "Create objects without specifying class")
                )
        ));
        doc.add(new CodeBlock("public class Singleton {\n    private static Singleton instance;\n}", "java"));

        // Visitor 1: Export to HTML
        System.out.println("\n=== HTML Export ===");
        HTMLExportVisitor htmlVisitor = new HTMLExportVisitor();
        doc.accept(htmlVisitor);
        System.out.println(htmlVisitor.getHTML());

        // Visitor 2: Export to Markdown
        System.out.println("\n=== Markdown Export ===");
        MarkdownExportVisitor mdVisitor = new MarkdownExportVisitor();
        doc.accept(mdVisitor);
        System.out.println(mdVisitor.getMarkdown());

        // Visitor 3: Word Count
        System.out.println("\n=== Analysis ===");
        WordCountVisitor wordCounter = new WordCountVisitor();
        doc.accept(wordCounter);
        wordCounter.printStats();

        // Visitor 4: Accessibility
        System.out.println();
        AccessibilityVisitor accessVisitor = new AccessibilityVisitor();
        doc.accept(accessVisitor);
        accessVisitor.printReport();
    }
}
```

### Output (excerpt)

```
=== HTML Export ===
Visiting document: Java Design Patterns Guide
<h1>Introduction</h1>
<p>Design patterns are reusable solutions to common problems in software design.</p>
<img src="patterns.png" alt="UML diagram of patterns" width="800" height="600"/>
<h2>Creational Patterns</h2>
<p>These patterns deal with object creation mechanisms.</p>
<table>
<tr><th>Pattern</th><th>Intent</th></tr>
<tr><td>Singleton</td><td>Ensure only one instance</td></tr>
...
</table>
<pre><code class="lang-java">public class Singleton { ... }</code></pre>

=== Analysis ===
=== Document Statistics ===
Total words    : 42
Heading words  : 4
Paragraph words: 20
Code words     : 6
Images         : 1

=== Accessibility Report ===
✅ No accessibility issues found!
```

---

## Real-World Java Examples

| Usage | Visitor |
|---|---|
| `javax.lang.model.element.ElementVisitor` | Java compiler element tree visitor |
| ASM (bytecode library) | `ClassVisitor`, `MethodVisitor` |
| ANTLR | Generated `ParseTreeVisitor` |
| JavaParser | Visits Java AST nodes |
| Checkstyle/SpotBugs | Rule checkers are visitors over AST |

---

## Visitor vs Related Patterns

| Pattern | Difference |
|---|---|
| **Iterator** | Traverses without operation; Visitor adds operation |
| **Strategy** | Replaces algorithm in one class; Visitor operates across many classes |
| **Composite** | Often used WITH Visitor — Composite builds tree, Visitor operates on it |
| **Command** | Command is an action; Visitor is an action applied to a structure |

---

## Pros and Cons

### ✅ Advantages
- **Open/Closed for operations** — Add new operations without changing elements
- **Single Responsibility** — Each visitor encapsulates one operation
- **Accumulate state** — Visitor can accumulate information across the object structure
- **Separation** — Algorithm separated from the object structure

### ❌ Disadvantages
- **Closed/Open for elements** — Adding new element types requires updating ALL visitors
- **Breaks encapsulation** — Elements must expose enough state for visitors to work
- **Complexity** — Double dispatch is non-obvious
- **Coupling** — Visitor is coupled to concrete element types

---

## When to Use

✔ When you need to perform many distinct and unrelated operations on an object structure  
✔ When you want to avoid polluting element classes with unrelated operations  
✔ When the object structure is stable but operations change frequently  
✔ Compilers (AST operations), document processing, game object systems  

---

## The Double Dispatch Explained

```java
// WITHOUT double dispatch:
void process(Element e, Visitor v) {
    if (e instanceof Heading)   { v.visitHeading((Heading) e); }  // ugly instanceof
    else if (e instanceof Paragraph) { ... }
}

// WITH double dispatch (Visitor Pattern):
// Step 1: element.accept(visitor) → dispatches on element type
element.accept(visitor);
// Inside accept():
// Step 2: visitor.visit(this) → dispatches on visitor type
// Both types are now known — no instanceof needed!
```

---

## Key Takeaway

> **"Add operations to a structure without touching its classes."**  
> Visitor Pattern lets you define new operations on a complex object structure by writing new Visitor classes — the elements remain unchanged, and double dispatch ensures the right visitor method is called for each element type.
