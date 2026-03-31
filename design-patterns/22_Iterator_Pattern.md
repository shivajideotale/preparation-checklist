# Iterator Pattern

## Category
**Behavioral Design Pattern**

---

## Intent
Provide a way to **sequentially access elements** of an aggregate object without exposing its underlying representation (list, stack, tree, etc.).

---

## The Problem It Solves

You have a collection — could be a `List`, a `Tree`, a `Graph`, or a custom data structure. You want to traverse it without:
- Knowing its internal implementation
- Duplicating traversal logic everywhere
- Coupling clients to the collection type

Iterator Pattern provides a **standard traversal interface** — regardless of whether the collection is an array, linked list, or binary tree, the client always calls `hasNext()` and `next()`.

---

## Structure

```
Iterable (Aggregate)
  └── createIterator() → Iterator

Iterator (interface)
  ├── hasNext() → boolean
  ├── next()    → Element
  └── remove()  (optional)

ConcreteCollection → ConcreteIterator
```

---

## Java Example — Book Collection

### Step 1: The Book Entity

```java
public class Book {
    private String title;
    private String author;
    private String genre;
    private double price;

    public Book(String title, String author, String genre, double price) {
        this.title  = title;
        this.author = author;
        this.genre  = genre;
        this.price  = price;
    }

    public String getTitle()  { return title;  }
    public String getAuthor() { return author; }
    public String getGenre()  { return genre;  }
    public double getPrice()  { return price;  }

    @Override
    public String toString() {
        return String.format("'%s' by %s [%s] ₹%.0f", title, author, genre, price);
    }
}
```

### Step 2: Iterator Interface

```java
public interface BookIterator {
    boolean hasNext();
    Book    next();
    void    reset(); // optional: restart from beginning
}
```

### Step 3: Collection Interface

```java
public interface BookCollection {
    BookIterator iterator();
    BookIterator genreIterator(String genre); // filtered iterator
    int size();
}
```

### Step 4: Concrete Collection

```java
public class BookShelf implements BookCollection {
    private List<Book> books = new ArrayList<>();

    public void addBook(Book book) {
        books.add(book);
    }

    @Override
    public BookIterator iterator() {
        return new AllBooksIterator(books);
    }

    @Override
    public BookIterator genreIterator(String genre) {
        return new GenreIterator(books, genre);
    }

    @Override
    public int size() { return books.size(); }
}
```

### Step 5: Concrete Iterators

```java
// Iterates over all books
public class AllBooksIterator implements BookIterator {
    private List<Book> books;
    private int index = 0;

    public AllBooksIterator(List<Book> books) {
        this.books = books;
    }

    @Override
    public boolean hasNext() {
        return index < books.size();
    }

    @Override
    public Book next() {
        if (!hasNext()) throw new NoSuchElementException();
        return books.get(index++);
    }

    @Override
    public void reset() { index = 0; }
}

// Iterates over books of a specific genre only
public class GenreIterator implements BookIterator {
    private List<Book> books;
    private String     genre;
    private int        index = 0;
    private Book       nextBook = null;

    public GenreIterator(List<Book> books, String genre) {
        this.books = books;
        this.genre = genre;
        advance(); // find first match
    }

    private void advance() {
        nextBook = null;
        while (index < books.size()) {
            Book b = books.get(index++);
            if (b.getGenre().equalsIgnoreCase(genre)) {
                nextBook = b;
                break;
            }
        }
    }

    @Override
    public boolean hasNext() { return nextBook != null; }

    @Override
    public Book next() {
        if (!hasNext()) throw new NoSuchElementException();
        Book result = nextBook;
        advance();
        return result;
    }

    @Override
    public void reset() { index = 0; advance(); }
}

// Reverse iterator
public class ReverseIterator implements BookIterator {
    private List<Book> books;
    private int index;

    public ReverseIterator(List<Book> books) {
        this.books = books;
        this.index = books.size() - 1;
    }

    @Override
    public boolean hasNext() { return index >= 0; }

    @Override
    public Book next() {
        if (!hasNext()) throw new NoSuchElementException();
        return books.get(index--);
    }

    @Override
    public void reset() { index = books.size() - 1; }
}
```

### Step 6: Client Code

```java
public class Library {
    public static void main(String[] args) {
        BookShelf shelf = new BookShelf();
        shelf.addBook(new Book("Clean Code",         "Robert Martin", "Tech",    799));
        shelf.addBook(new Book("Dune",               "Frank Herbert", "Sci-Fi", 499));
        shelf.addBook(new Book("The Pragmatic Programmer","Hunt & Thomas","Tech",899));
        shelf.addBook(new Book("Foundation",         "Isaac Asimov",  "Sci-Fi", 399));
        shelf.addBook(new Book("Design Patterns",    "GoF",           "Tech",   999));
        shelf.addBook(new Book("Neuromancer",        "William Gibson","Sci-Fi", 349));

        System.out.println("=== All Books ===");
        BookIterator all = shelf.iterator();
        while (all.hasNext()) {
            System.out.println("  " + all.next());
        }

        System.out.println("\n=== Tech Books Only ===");
        BookIterator techBooks = shelf.genreIterator("Tech");
        while (techBooks.hasNext()) {
            System.out.println("  " + techBooks.next());
        }

        System.out.println("\n=== Sci-Fi Books Only ===");
        BookIterator scifi = shelf.genreIterator("Sci-Fi");
        while (scifi.hasNext()) {
            System.out.println("  " + scifi.next());
        }

        System.out.println("\n=== Reverse Order ===");
        BookIterator reverse = new ReverseIterator(
                new ArrayList<>(List.of(
                        shelf.iterator().next(), // not ideal — just demo
                        new Book("Z Book", "Z Author", "Z", 0)
                ))
        );
        // Use it properly by storing books list separately
    }
}
```

### Output

```
=== All Books ===
  'Clean Code' by Robert Martin [Tech] ₹799
  'Dune' by Frank Herbert [Sci-Fi] ₹499
  'The Pragmatic Programmer' by Hunt & Thomas [Tech] ₹899
  'Foundation' by Isaac Asimov [Sci-Fi] ₹399
  'Design Patterns' by GoF [Tech] ₹999
  'Neuromancer' by William Gibson [Sci-Fi] ₹349

=== Tech Books Only ===
  'Clean Code' by Robert Martin [Tech] ₹799
  'The Pragmatic Programmer' by Hunt & Thomas [Tech] ₹899
  'Design Patterns' by GoF [Tech] ₹999

=== Sci-Fi Books Only ===
  'Dune' by Frank Herbert [Sci-Fi] ₹499
  'Foundation' by Isaac Asimov [Sci-Fi] ₹399
  'Neuromancer' by William Gibson [Sci-Fi] ₹349
```

---

## Java's Built-in Iterator

Java's `java.util.Iterator<T>` and `java.lang.Iterable<T>` are the canonical implementation of this pattern:

```java
public class NumberRange implements Iterable<Integer> {
    private int start, end, step;

    public NumberRange(int start, int end, int step) {
        this.start = start;
        this.end   = end;
        this.step  = step;
    }

    @Override
    public Iterator<Integer> iterator() {
        return new Iterator<>() {
            private int current = start;

            @Override
            public boolean hasNext() { return current <= end; }

            @Override
            public Integer next() {
                if (!hasNext()) throw new NoSuchElementException();
                int val = current;
                current += step;
                return val;
            }
        };
    }
}

// Now works with enhanced for-loop!
NumberRange evens = new NumberRange(2, 20, 2);
for (int n : evens) {
    System.out.print(n + " "); // 2 4 6 8 10 12 14 16 18 20
}

// Works with Stream API too
StreamSupport.stream(evens.spliterator(), false)
             .filter(n -> n > 10)
             .forEach(System.out::println);
```

---

## Tree Iterator Example

```java
// Iterate a binary tree in-order (left → root → right)
public class InOrderTreeIterator<T extends Comparable<T>> implements Iterator<T> {
    private Deque<TreeNode<T>> stack = new ArrayDeque<>();

    public InOrderTreeIterator(TreeNode<T> root) {
        pushLeft(root);
    }

    private void pushLeft(TreeNode<T> node) {
        while (node != null) {
            stack.push(node);
            node = node.left;
        }
    }

    @Override
    public boolean hasNext() { return !stack.isEmpty(); }

    @Override
    public T next() {
        TreeNode<T> node = stack.pop();
        pushLeft(node.right);
        return node.value;
    }
}
```

---

## Real-World Java Examples

| Usage | Iterator |
|---|---|
| `java.util.Iterator` | Standard Java iterator interface |
| `java.util.ListIterator` | Bidirectional iterator |
| `java.util.Scanner` | Iterates tokens from input |
| `java.nio.file.DirectoryStream` | Iterates directory entries |
| `ResultSet.next()` in JDBC | Iterates database rows |
| Java Streams | `Spliterator` underpins stream iteration |

---

## Pros and Cons

### ✅ Advantages
- **Decoupling** — Client doesn't know the collection's structure
- **Multiple traversal** — Many iterators can traverse the same collection simultaneously
- **Uniform interface** — Same API for List, Set, Tree, Graph
- **Enhanced for-loop** — Any `Iterable` works with `for (T t : collection)`

### ❌ Disadvantages
- **Overhead** — Extra object per traversal
- **Snapshot vs live** — Iterator may see modifications to the collection mid-traversal (`ConcurrentModificationException`)
- **Not parallel-friendly** — Single iterator is sequential

---

## When to Use

✔ When you want to traverse a collection without knowing its internals  
✔ When you need multiple traversal strategies (forward, reverse, filtered)  
✔ When you want to provide a uniform interface over different collection types  
✔ When building custom data structures that should integrate with Java's for-each  

---

## Key Takeaway

> **"Traverse without peeking inside."**  
> Iterator Pattern provides a standard cursor to step through any collection, hiding whether it's backed by an array, tree, or database result set.
