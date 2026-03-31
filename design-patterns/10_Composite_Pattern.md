# Composite Pattern

## Category
**Structural Design Pattern**

---

## Intent
Compose objects into **tree structures** to represent part-whole hierarchies. Composite lets clients treat individual objects (leaves) and compositions of objects (composites) uniformly.

---

## The Problem It Solves

You're building a **file system** where:
- A **File** is a single item
- A **Folder** contains files and other folders
- You want to call `getSize()` on both a single file and an entire folder tree

Without Composite Pattern, the client must know whether it's dealing with a File or a Folder:
```java
if (item instanceof File) {
    size = ((File) item).getSize();
} else if (item instanceof Folder) {
    size = calculateFolderSize((Folder) item); // recursive, different code
}
```

With Composite, **both File and Folder implement the same interface** — `getSize()` just works, whether it's a leaf or a whole subtree.

---

## Structure

```
Component (interface/abstract)
  ├── Leaf           → single object, no children
  └── Composite      → has children, delegates to them
        ├── add(Component)
        ├── remove(Component)
        └── getChildren()
```

---

## Java Example — File System

### Step 1: Component Interface

```java
public interface FileSystemItem {
    String getName();
    long getSize();        // in bytes
    void print(String indent);
    
    // Optional: these make sense only for composites
    default void add(FileSystemItem item) {
        throw new UnsupportedOperationException("Cannot add to a leaf");
    }
    default void remove(FileSystemItem item) {
        throw new UnsupportedOperationException("Cannot remove from a leaf");
    }
}
```

### Step 2: Leaf — File

```java
public class File implements FileSystemItem {
    private String name;
    private long size;

    public File(String name, long sizeInBytes) {
        this.name = name;
        this.size = sizeInBytes;
    }

    @Override
    public String getName() { return name; }

    @Override
    public long getSize() { return size; }

    @Override
    public void print(String indent) {
        System.out.printf("%s📄 %-30s [%s]%n",
                indent, name, formatSize(size));
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return (bytes / (1024 * 1024)) + " MB";
    }
}
```

### Step 3: Composite — Folder

```java
public class Folder implements FileSystemItem {
    private String name;
    private List<FileSystemItem> children = new ArrayList<>();

    public Folder(String name) {
        this.name = name;
    }

    @Override
    public void add(FileSystemItem item) {
        children.add(item);
    }

    @Override
    public void remove(FileSystemItem item) {
        children.remove(item);
    }

    @Override
    public String getName() { return name; }

    @Override
    public long getSize() {
        // Recursive — delegates to each child
        return children.stream()
                       .mapToLong(FileSystemItem::getSize)
                       .sum();
    }

    @Override
    public void print(String indent) {
        System.out.printf("%s📁 %-30s [%d items | %s]%n",
                indent, name, children.size(), formatSize(getSize()));
        for (FileSystemItem child : children) {
            child.print(indent + "  │  "); // recurse!
        }
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return (bytes / (1024 * 1024)) + " MB";
    }
}
```

### Step 4: Client Code

```java
public class FileSystem {
    public static void main(String[] args) {
        // Build tree structure
        Folder root = new Folder("root");

        Folder documents = new Folder("Documents");
        documents.add(new File("resume.pdf",        512_000));
        documents.add(new File("cover_letter.docx",  48_000));

        Folder projects = new Folder("Projects");

        Folder javaProject = new Folder("JavaProject");
        javaProject.add(new File("Main.java",          5_000));
        javaProject.add(new File("pom.xml",            2_000));
        javaProject.add(new File("README.md",          3_500));

        Folder pythonProject = new Folder("PythonProject");
        pythonProject.add(new File("main.py",          4_200));
        pythonProject.add(new File("requirements.txt",    400));

        projects.add(javaProject);
        projects.add(pythonProject);

        Folder downloads = new Folder("Downloads");
        downloads.add(new File("ubuntu.iso",   3_200_000_000L));
        downloads.add(new File("movie.mp4",    1_500_000_000L));

        root.add(documents);
        root.add(projects);
        root.add(downloads);
        root.add(new File("notes.txt", 1_200));

        // Client treats everything uniformly — same method call!
        System.out.println("=== File System Tree ===");
        root.print("");

        System.out.println("\n=== Size Checks ===");
        System.out.println("resume.pdf size      : " + new File("resume.pdf", 512_000).getSize() + " bytes");
        System.out.println("JavaProject size     : " + javaProject.getSize() + " bytes");
        System.out.println("Documents size       : " + documents.getSize() + " bytes");
        System.out.println("Total root size      : " + root.getSize() + " bytes");
    }
}
```

### Output

```
=== File System Tree ===
📁 root                           [2 items | 4694 MB]
  │  📁 Documents                  [2 items | 546 KB]
  │    │  📄 resume.pdf                         [500 KB]
  │    │  📄 cover_letter.docx                  [46 KB]
  │  📁 Projects                   [2 items | 15 KB]
  │    │  📁 JavaProject            [3 items | 10 KB]
  │    │    │  📄 Main.java                      [4 KB]
  │    │    │  📄 pom.xml                        [1 KB]
  │    │    │  📄 README.md                      [3 KB]
  │    │    📁 PythonProject         [2 items | 4 KB]
  │    │    │  📄 main.py                        [4 KB]
  │    │    │  📄 requirements.txt               [0 KB]
  │  📁 Downloads                  [2 items | 4493 MB]
  │    │  📄 ubuntu.iso                          [3051 MB]
  │    │  📄 movie.mp4                           [1430 MB]
  │  📄 notes.txt                              [1 KB]

=== Size Checks ===
resume.pdf size      : 512000 bytes
JavaProject size     : 10500 bytes
Documents size       : 560000 bytes
Total root size      : 4700576700 bytes
```

---

## Another Example — Organization Chart

```java
public interface Employee {
    String getName();
    String getRole();
    double getSalary();
    void showDetails(String indent);

    default void add(Employee e) {
        throw new UnsupportedOperationException();
    }
}

// Leaf — individual contributor
public class Developer implements Employee {
    private String name;
    private double salary;

    public Developer(String name, double salary) {
        this.name = name;
        this.salary = salary;
    }

    @Override public String getName()    { return name; }
    @Override public String getRole()    { return "Developer"; }
    @Override public double getSalary()  { return salary; }

    @Override
    public void showDetails(String indent) {
        System.out.printf("%s👤 %s (%s) - ₹%.0f%n", indent, name, getRole(), salary);
    }
}

// Composite — Manager with team members
public class Manager implements Employee {
    private String name;
    private double salary;
    private List<Employee> team = new ArrayList<>();

    public Manager(String name, double salary) {
        this.name = name;
        this.salary = salary;
    }

    @Override
    public void add(Employee e) { team.add(e); }

    @Override public String getName()   { return name; }
    @Override public String getRole()   { return "Manager"; }

    @Override
    public double getSalary() {
        // Total team cost including manager's own salary
        return salary + team.stream().mapToDouble(Employee::getSalary).sum();
    }

    @Override
    public void showDetails(String indent) {
        System.out.printf("%s👔 %s (Manager) - ₹%.0f own | ₹%.0f total team cost%n",
                indent, name, salary, getSalary());
        for (Employee e : team) {
            e.showDetails(indent + "   ");
        }
    }
}

// Usage
Manager cto = new Manager("Vikram (CTO)", 500_000);

Manager backendMgr = new Manager("Rohan (Backend Lead)", 200_000);
backendMgr.add(new Developer("Anya", 120_000));
backendMgr.add(new Developer("Dev",  110_000));

Manager frontendMgr = new Manager("Sneha (Frontend Lead)", 180_000);
frontendMgr.add(new Developer("Arjun", 100_000));

cto.add(backendMgr);
cto.add(frontendMgr);
cto.add(new Developer("Aisha (Direct Report)", 150_000));

cto.showDetails("");
System.out.println("\nTotal org cost: ₹" + cto.getSalary());
```

---

## Real-World Java Examples

| Usage | Details |
|---|---|
| `java.awt.Component` / `Container` | GUI component tree — `JPanel` contains `JButton`, `JLabel` |
| XML/JSON DOM | Element can contain text (leaf) or child elements (composite) |
| `javax.swing.JMenu` | JMenu contains JMenuItem (leaf) and other JMenus (composite) |
| Maven/Gradle build | Module contains modules and tasks |
| HTML DOM | `<div>` contains `<p>`, `<span>`, or more `<div>` |

---

## Pros and Cons

### ✅ Advantages
- **Uniformity** — Clients treat leaves and composites the same way
- **Recursive algorithms** — Naturally expresses tree traversal
- **Easy to add new types** — Add a new leaf type without changing composite
- **Simplifies client code** — No type-checking or special cases

### ❌ Disadvantages
- **Overly general** — Hard to restrict what can be added to a composite
- **Interface design** — Leaf's add/remove make no sense but must be in interface
- **Type safety** — Hard to enforce constraints like "only Files in this folder"

---

## When to Use

✔ When you need to represent part-whole hierarchies (trees)  
✔ When you want clients to ignore the difference between individual objects and compositions  
✔ File systems, UI component trees, org charts, menus, HTML DOM, arithmetic expressions  

---

## Key Takeaway

> **"Treat a leaf and a branch the same way."**  
> Composite Pattern lets you build tree structures where individual items and groups of items respond to the same interface — recursion handles the rest.
