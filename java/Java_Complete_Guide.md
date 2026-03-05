# ☕ Java Complete Guide — A to Z with Examples

> A comprehensive reference covering all important Java topics with detailed explanations and practical code examples.

---

## 📚 Table of Contents

1. [Introduction to Java](#1-introduction-to-java)
2. [Setting Up Java](#2-setting-up-java)
3. [Java Basics — Syntax & Structure](#3-java-basics--syntax--structure)
4. [Data Types & Variables](#4-data-types--variables)
5. [Operators](#5-operators)
6. [Control Flow Statements](#6-control-flow-statements)
7. [Arrays](#7-arrays)
8. [Strings](#8-strings)
9. [Methods / Functions](#9-methods--functions)
10. [Object-Oriented Programming (OOP)](#10-object-oriented-programming-oop)
11. [Inheritance](#11-inheritance)
12. [Polymorphism](#12-polymorphism)
13. [Abstraction](#13-abstraction)
14. [Encapsulation](#14-encapsulation)
15. [Interfaces](#15-interfaces)
16. [Exception Handling](#16-exception-handling)
17. [Collections Framework](#17-collections-framework)
18. [Generics](#18-generics)
19. [Iterators & For-Each](#19-iterators--for-each)
20. [Java I/O (Input/Output)](#20-java-io-inputoutput)
21. [File Handling](#21-file-handling)
22. [Multithreading & Concurrency](#22-multithreading--concurrency)
23. [Lambda Expressions](#23-lambda-expressions)
24. [Stream API](#24-stream-api)
25. [Functional Interfaces](#25-functional-interfaces)
26. [Optional Class](#26-optional-class)
27. [Java 8+ Features](#27-java-8-features)
28. [Design Patterns](#28-design-patterns)
29. [Java Memory Management & Garbage Collection](#29-java-memory-management--garbage-collection)
30. [Best Practices](#30-best-practices)

---

## 1. Introduction to Java

Java is a **high-level, class-based, object-oriented programming language** designed to have as few implementation dependencies as possible. It follows the principle:

> **"Write Once, Run Anywhere (WORA)"**

### Key Features
- **Platform Independent** — Java code compiles to bytecode that runs on any JVM.
- **Object-Oriented** — Everything revolves around classes and objects.
- **Strongly Typed** — Every variable must have a declared type.
- **Automatic Memory Management** — Garbage Collector handles memory.
- **Multithreaded** — Built-in support for concurrent programming.
- **Secure & Robust** — No pointer arithmetic; strong exception handling.

### Java Architecture

```
Java Source Code (.java)
        ↓  [Compiler - javac]
   Bytecode (.class)
        ↓  [JVM - Java Virtual Machine]
  Machine Code (Platform Specific)
```

| Component | Full Form | Role |
|-----------|-----------|------|
| JDK | Java Development Kit | Tools to develop Java programs |
| JRE | Java Runtime Environment | Environment to run Java programs |
| JVM | Java Virtual Machine | Executes bytecode |

---

## 2. Setting Up Java

### Install JDK
Download from: [https://www.oracle.com/java/technologies/downloads/](https://www.oracle.com/java/technologies/downloads/)

### Verify Installation
```bash
java -version
javac -version
```

### Hello World Program
```java
// File: HelloWorld.java
public class HelloWorld {
    public static void main(String[] args) {
        System.out.println("Hello, World!");
    }
}
```

### Compile and Run
```bash
javac HelloWorld.java   # Compiles → HelloWorld.class
java HelloWorld         # Runs the program
```

**Output:**
```
Hello, World!
```

---

## 3. Java Basics — Syntax & Structure

### Program Structure
```java
// Package declaration (optional)
package com.example;

// Import statements
import java.util.Scanner;

// Class definition
public class MyClass {

    // Fields (instance variables)
    int number = 10;

    // Main method — entry point
    public static void main(String[] args) {
        System.out.println("Java Program Running!");
    }

    // Other methods
    public void myMethod() {
        // method body
    }
}
```

### Rules
- Java is **case-sensitive** (`Main` ≠ `main`)
- Every statement ends with a **semicolon** `;`
- Code blocks are enclosed in **curly braces** `{}`
- Class name should match the **filename**
- `main()` method is the **entry point**

### Comments
```java
// Single-line comment

/* 
   Multi-line comment 
*/

/**
 * Javadoc comment
 * @param args command-line arguments
 */
```

---

## 4. Data Types & Variables

### Primitive Data Types

| Type | Size | Default | Range / Description |
|------|------|---------|---------------------|
| `byte` | 1 byte | 0 | -128 to 127 |
| `short` | 2 bytes | 0 | -32,768 to 32,767 |
| `int` | 4 bytes | 0 | -2^31 to 2^31 - 1 |
| `long` | 8 bytes | 0L | -2^63 to 2^63 - 1 |
| `float` | 4 bytes | 0.0f | ~6-7 decimal digits |
| `double` | 8 bytes | 0.0d | ~15 decimal digits |
| `char` | 2 bytes | '\u0000' | Unicode character |
| `boolean` | 1 bit | false | true or false |

### Variable Declaration & Initialization
```java
public class DataTypesDemo {
    public static void main(String[] args) {
        // Integer types
        byte age = 25;
        short year = 2024;
        int population = 1_400_000_000;   // underscores for readability
        long distance = 9_460_730_472_580L; // L suffix for long

        // Floating-point types
        float price = 9.99f;    // f suffix required
        double pi = 3.14159265358979;

        // Character & Boolean
        char grade = 'A';
        boolean isJavaFun = true;

        System.out.println("Age: " + age);
        System.out.println("Year: " + year);
        System.out.println("Pi: " + pi);
        System.out.println("Grade: " + grade);
        System.out.println("Is Java fun? " + isJavaFun);
    }
}
```

### Type Casting
```java
// Widening (automatic) — smaller to larger
int i = 100;
long l = i;       // int → long (automatic)
double d = l;     // long → double (automatic)

// Narrowing (explicit) — larger to smaller
double x = 9.99;
int y = (int) x;  // double → int → y = 9 (data loss!)

System.out.println("Widening: " + d);   // 100.0
System.out.println("Narrowing: " + y);  // 9
```

### `var` (Local Variable Type Inference — Java 10+)
```java
var message = "Hello Java";   // inferred as String
var count = 42;                // inferred as int
var list = new ArrayList<>();  // inferred as ArrayList
```

---

## 5. Operators

### Arithmetic Operators
```java
int a = 10, b = 3;

System.out.println(a + b);   // 13 — Addition
System.out.println(a - b);   // 7  — Subtraction
System.out.println(a * b);   // 30 — Multiplication
System.out.println(a / b);   // 3  — Division (integer)
System.out.println(a % b);   // 1  — Modulus (remainder)
```

### Relational Operators
```java
int x = 5, y = 10;
System.out.println(x == y);   // false
System.out.println(x != y);   // true
System.out.println(x > y);    // false
System.out.println(x < y);    // true
System.out.println(x >= 5);   // true
System.out.println(y <= 10);  // true
```

### Logical Operators
```java
boolean p = true, q = false;
System.out.println(p && q);   // false — AND
System.out.println(p || q);   // true  — OR
System.out.println(!p);       // false — NOT
```

### Assignment & Compound Operators
```java
int n = 10;
n += 5;   // n = 15
n -= 3;   // n = 12
n *= 2;   // n = 24
n /= 4;   // n = 6
n %= 4;   // n = 2
```

### Increment & Decrement
```java
int i = 5;
System.out.println(i++);  // 5 — post-increment (use then increment)
System.out.println(i);    // 6
System.out.println(++i);  // 7 — pre-increment (increment then use)
System.out.println(i--);  // 7 — post-decrement
System.out.println(i);    // 6
```

### Ternary Operator
```java
int age = 20;
String status = (age >= 18) ? "Adult" : "Minor";
System.out.println(status);  // Adult
```

### Bitwise Operators
```java
int a = 5;  // 0101
int b = 3;  // 0011

System.out.println(a & b);   // 1  — AND  (0001)
System.out.println(a | b);   // 7  — OR   (0111)
System.out.println(a ^ b);   // 6  — XOR  (0110)
System.out.println(~a);      // -6 — NOT
System.out.println(a << 1);  // 10 — Left shift
System.out.println(a >> 1);  // 2  — Right shift
```

---

## 6. Control Flow Statements

### if / else if / else
```java
int score = 75;

if (score >= 90) {
    System.out.println("Grade: A");
} else if (score >= 80) {
    System.out.println("Grade: B");
} else if (score >= 70) {
    System.out.println("Grade: C");
} else {
    System.out.println("Grade: F");
}
// Output: Grade: C
```

### switch Statement
```java
int day = 3;
switch (day) {
    case 1: System.out.println("Monday");    break;
    case 2: System.out.println("Tuesday");   break;
    case 3: System.out.println("Wednesday"); break;
    case 4: System.out.println("Thursday");  break;
    case 5: System.out.println("Friday");    break;
    default: System.out.println("Weekend");
}
// Output: Wednesday
```

### Switch Expression (Java 14+)
```java
String dayName = switch (day) {
    case 1 -> "Monday";
    case 2 -> "Tuesday";
    case 3 -> "Wednesday";
    default -> "Unknown";
};
System.out.println(dayName); // Wednesday
```

### for Loop
```java
// Standard for loop
for (int i = 1; i <= 5; i++) {
    System.out.print(i + " ");
}
// Output: 1 2 3 4 5

// Counting down
for (int i = 5; i >= 1; i--) {
    System.out.print(i + " ");
}
// Output: 5 4 3 2 1
```

### while Loop
```java
int count = 1;
while (count <= 5) {
    System.out.print(count + " ");
    count++;
}
// Output: 1 2 3 4 5
```

### do-while Loop
```java
// Executes at least once
int num = 1;
do {
    System.out.print(num + " ");
    num++;
} while (num <= 5);
// Output: 1 2 3 4 5
```

### break, continue, return
```java
// break — exits the loop
for (int i = 0; i < 10; i++) {
    if (i == 5) break;
    System.out.print(i + " ");
}
// Output: 0 1 2 3 4

// continue — skips the current iteration
for (int i = 0; i < 10; i++) {
    if (i % 2 == 0) continue;
    System.out.print(i + " ");
}
// Output: 1 3 5 7 9
```

---

## 7. Arrays

### Declaration & Initialization
```java
// Declare and allocate
int[] numbers = new int[5];
numbers[0] = 10;
numbers[1] = 20;

// Declare and initialize in one line
int[] primes = {2, 3, 5, 7, 11};

// Array length
System.out.println(primes.length);  // 5
```

### Iterating Arrays
```java
int[] scores = {85, 92, 78, 95, 88};

// Using for loop
for (int i = 0; i < scores.length; i++) {
    System.out.println("Score " + i + ": " + scores[i]);
}

// Enhanced for-each loop
for (int score : scores) {
    System.out.print(score + " ");
}
// Output: 85 92 78 95 88
```

### 2D Arrays
```java
int[][] matrix = {
    {1, 2, 3},
    {4, 5, 6},
    {7, 8, 9}
};

// Accessing elements
System.out.println(matrix[1][2]);  // 6

// Iterating 2D array
for (int[] row : matrix) {
    for (int val : row) {
        System.out.printf("%3d", val);
    }
    System.out.println();
}
```

### Arrays Utility Class
```java
import java.util.Arrays;

int[] arr = {5, 2, 8, 1, 9, 3};

Arrays.sort(arr);
System.out.println(Arrays.toString(arr));    // [1, 2, 3, 5, 8, 9]

int idx = Arrays.binarySearch(arr, 5);
System.out.println("Index of 5: " + idx);    // 3

int[] copy = Arrays.copyOf(arr, 4);
System.out.println(Arrays.toString(copy));   // [1, 2, 3, 5]

Arrays.fill(arr, 0);
System.out.println(Arrays.toString(arr));    // [0, 0, 0, 0, 0, 0]
```

---

## 8. Strings

Strings in Java are **immutable** objects of the `String` class.

### Creating Strings
```java
String s1 = "Hello";                   // String literal (pool)
String s2 = new String("Hello");       // Heap object

// String comparison
System.out.println(s1 == s2);          // false (reference comparison)
System.out.println(s1.equals(s2));     // true (content comparison)
System.out.println(s1.equalsIgnoreCase("hello")); // true
```

### Common String Methods
```java
String str = "  Hello, Java World!  ";

System.out.println(str.length());           // 22
System.out.println(str.trim());             // "Hello, Java World!"
System.out.println(str.toUpperCase());      // "  HELLO, JAVA WORLD!  "
System.out.println(str.toLowerCase());      // "  hello, java world!  "
System.out.println(str.contains("Java"));   // true
System.out.println(str.replace("Java", "Python")); // Hello, Python World!
System.out.println(str.indexOf("Java"));    // 9
System.out.println(str.substring(8, 12));   // Java  (index 8 to 11)
System.out.println(str.startsWith("  H")); // true
System.out.println(str.isEmpty());          // false
System.out.println(str.trim().split(", ")[0]); // Hello
```

### String Concatenation & Formatting
```java
String name = "Alice";
int age = 30;

// Concatenation
String msg1 = "Name: " + name + ", Age: " + age;

// String.format
String msg2 = String.format("Name: %s, Age: %d", name, age);

// printf
System.out.printf("Name: %s, Age: %d%n", name, age);

// Text Block (Java 15+)
String json = """
        {
            "name": "Alice",
            "age": 30
        }
        """;
System.out.println(json);
```

### StringBuilder (Mutable String)
```java
// Efficient for repeated modifications
StringBuilder sb = new StringBuilder();
sb.append("Hello");
sb.append(", ");
sb.append("World");
sb.insert(5, " Beautiful");
sb.delete(5, 15);
sb.reverse();

System.out.println(sb.toString());  // dlroW ,olleH

// Performance comparison
// String concatenation in loop is O(n²), StringBuilder is O(n)
StringBuilder result = new StringBuilder();
for (int i = 0; i < 100; i++) {
    result.append(i).append(",");
}
System.out.println(result.toString());
```

---

## 9. Methods / Functions

### Method Syntax
```java
accessModifier returnType methodName(parameters) {
    // method body
    return value; // if not void
}
```

### Method Examples
```java
public class MethodDemo {

    // Method with return value
    public static int add(int a, int b) {
        return a + b;
    }

    // Void method (no return)
    public static void greet(String name) {
        System.out.println("Hello, " + name + "!");
    }

    // Method overloading
    public static double add(double a, double b) {
        return a + b;
    }

    // Varargs (variable arguments)
    public static int sum(int... numbers) {
        int total = 0;
        for (int n : numbers) total += n;
        return total;
    }

    // Recursive method
    public static int factorial(int n) {
        if (n <= 1) return 1;
        return n * factorial(n - 1);
    }

    public static void main(String[] args) {
        System.out.println(add(5, 3));          // 8
        System.out.println(add(2.5, 3.5));      // 6.0
        greet("Alice");                          // Hello, Alice!
        System.out.println(sum(1, 2, 3, 4, 5)); // 15
        System.out.println(factorial(5));        // 120
    }
}
```

### Pass by Value
```java
// Java is ALWAYS pass-by-value
public static void modify(int x) {
    x = 100;  // only local copy changes
}

public static void modifyArray(int[] arr) {
    arr[0] = 100;  // modifies original array (reference copy)
}

int num = 5;
modify(num);
System.out.println(num);  // 5 — unchanged

int[] arr = {1, 2, 3};
modifyArray(arr);
System.out.println(arr[0]);  // 100 — changed (reference)
```

---

## 10. Object-Oriented Programming (OOP)

### Classes and Objects

A **class** is a blueprint. An **object** is an instance of a class.

```java
// Class definition
public class Car {
    // Fields (state)
    String brand;
    String model;
    int year;
    double price;

    // Constructor
    public Car(String brand, String model, int year, double price) {
        this.brand = brand;
        this.model = model;
        this.year = year;
        this.price = price;
    }

    // Default constructor
    public Car() {
        this.brand = "Unknown";
    }

    // Methods (behavior)
    public void start() {
        System.out.println(brand + " " + model + " is starting...");
    }

    public void displayInfo() {
        System.out.printf("Brand: %s | Model: %s | Year: %d | Price: $%.2f%n",
                brand, model, year, price);
    }

    // Getter
    public String getBrand() { return brand; }

    // Setter
    public void setPrice(double price) {
        if (price > 0) this.price = price;
    }
}

// Using the class
public class Main {
    public static void main(String[] args) {
        Car car1 = new Car("Toyota", "Camry", 2023, 25000.00);
        Car car2 = new Car("Honda", "Civic", 2022, 22000.00);

        car1.start();           // Toyota Camry is starting...
        car1.displayInfo();     // Brand: Toyota | Model: Camry | ...
        car2.displayInfo();

        car1.setPrice(26000);
        System.out.println("New price: " + car1.price);
    }
}
```

### Constructors
```java
public class Person {
    String name;
    int age;

    // No-arg constructor
    public Person() {
        name = "Anonymous";
        age = 0;
    }

    // Parameterized constructor
    public Person(String name, int age) {
        this.name = name;
        this.age = age;
    }

    // Copy constructor
    public Person(Person other) {
        this.name = other.name;
        this.age = other.age;
    }

    // Constructor chaining with this()
    public Person(String name) {
        this(name, 18);  // calls parameterized constructor
    }
}
```

### Static Members
```java
public class Counter {
    private static int count = 0;  // shared across all instances
    private int id;

    public Counter() {
        count++;
        this.id = count;
    }

    public static int getCount() {  // static method
        return count;
    }

    public int getId() { return id; }
}

Counter c1 = new Counter();
Counter c2 = new Counter();
Counter c3 = new Counter();
System.out.println(Counter.getCount());  // 3
```

---

## 11. Inheritance

Inheritance allows a class to **acquire properties and methods** of another class using the `extends` keyword.

```java
// Parent class (Superclass)
public class Animal {
    String name;
    int age;

    public Animal(String name, int age) {
        this.name = name;
        this.age = age;
    }

    public void eat() {
        System.out.println(name + " is eating.");
    }

    public void sleep() {
        System.out.println(name + " is sleeping.");
    }

    public String toString() {
        return "Animal[name=" + name + ", age=" + age + "]";
    }
}

// Child class (Subclass)
public class Dog extends Animal {
    String breed;

    public Dog(String name, int age, String breed) {
        super(name, age);  // calls parent constructor
        this.breed = breed;
    }

    // Additional method
    public void bark() {
        System.out.println(name + " says: Woof! Woof!");
    }

    // Method overriding
    @Override
    public void eat() {
        System.out.println(name + " is eating dog food.");
    }

    @Override
    public String toString() {
        return "Dog[name=" + name + ", breed=" + breed + "]";
    }
}

// Grandchild class
public class GuideDog extends Dog {
    String owner;

    public GuideDog(String name, int age, String breed, String owner) {
        super(name, age, breed);
        this.owner = owner;
    }

    public void guide() {
        System.out.println(name + " is guiding " + owner);
    }
}

// Usage
Dog dog = new Dog("Rex", 3, "German Shepherd");
dog.eat();    // Rex is eating dog food.
dog.bark();   // Rex says: Woof! Woof!
dog.sleep();  // Rex is sleeping. (inherited)
System.out.println(dog);  // Dog[name=Rex, breed=German Shepherd]
```

### `super` Keyword
```java
public class Child extends Parent {
    void display() {
        super.display();      // calls parent method
        System.out.println("Child display");
    }

    Child() {
        super();              // calls parent constructor (must be first line)
    }
}
```

### `final` Keyword
```java
final class ImmutableClass { }   // cannot be subclassed

class Parent {
    final void display() { }     // cannot be overridden
}

final int MAX = 100;             // constant value
```

---

## 12. Polymorphism

Polymorphism means **"many forms"** — the same method behaves differently based on the object.

### Compile-time Polymorphism (Method Overloading)
```java
public class Calculator {
    // Same method name, different parameters
    public int add(int a, int b) {
        return a + b;
    }

    public double add(double a, double b) {
        return a + b;
    }

    public int add(int a, int b, int c) {
        return a + b + c;
    }

    public String add(String a, String b) {
        return a + b;
    }
}

Calculator calc = new Calculator();
System.out.println(calc.add(5, 3));          // 8
System.out.println(calc.add(2.5, 1.5));      // 4.0
System.out.println(calc.add(1, 2, 3));       // 6
System.out.println(calc.add("Hello", "!"));  // Hello!
```

### Runtime Polymorphism (Method Overriding)
```java
public class Shape {
    public double area() {
        return 0;
    }
    public void display() {
        System.out.println("Area: " + area());
    }
}

public class Circle extends Shape {
    double radius;
    public Circle(double radius) { this.radius = radius; }

    @Override
    public double area() {
        return Math.PI * radius * radius;
    }
}

public class Rectangle extends Shape {
    double width, height;
    public Rectangle(double w, double h) { width = w; height = h; }

    @Override
    public double area() {
        return width * height;
    }
}

// Polymorphic behavior
Shape[] shapes = {
    new Circle(5),
    new Rectangle(4, 6),
    new Circle(3)
};

for (Shape s : shapes) {
    s.display();  // calls the appropriate area() at runtime
}
/*
Area: 78.53981633974483
Area: 24.0
Area: 28.274333882308138
*/
```

### instanceof Operator
```java
Shape s = new Circle(5);

if (s instanceof Circle c) {        // Java 16+ pattern matching
    System.out.println("Radius: " + c.radius);
} else if (s instanceof Rectangle r) {
    System.out.println("Width: " + r.width);
}
```

---

## 13. Abstraction

Abstraction hides implementation details and **shows only essential features**.

### Abstract Classes
```java
public abstract class Vehicle {
    String brand;
    int speed;

    public Vehicle(String brand, int speed) {
        this.brand = brand;
        this.speed = speed;
    }

    // Abstract method — no implementation
    public abstract void fuelType();

    // Concrete method
    public void move() {
        System.out.println(brand + " is moving at " + speed + " km/h");
    }
}

public class ElectricCar extends Vehicle {
    int batteryCapacity;

    public ElectricCar(String brand, int speed, int battery) {
        super(brand, speed);
        this.batteryCapacity = battery;
    }

    @Override
    public void fuelType() {
        System.out.println(brand + " runs on electricity. Battery: " + batteryCapacity + " kWh");
    }
}

public class PetrolCar extends Vehicle {
    public PetrolCar(String brand, int speed) {
        super(brand, speed);
    }

    @Override
    public void fuelType() {
        System.out.println(brand + " runs on petrol.");
    }
}

// Usage
Vehicle v1 = new ElectricCar("Tesla", 200, 100);
Vehicle v2 = new PetrolCar("BMW", 180);

v1.fuelType();  // Tesla runs on electricity. Battery: 100 kWh
v1.move();      // Tesla is moving at 200 km/h
v2.fuelType();  // BMW runs on petrol.
```

---

## 14. Encapsulation

Encapsulation **wraps data and methods** together and restricts direct access using access modifiers.

```java
public class BankAccount {
    private String accountNumber;
    private String owner;
    private double balance;

    public BankAccount(String accountNumber, String owner, double initialBalance) {
        this.accountNumber = accountNumber;
        this.owner = owner;
        this.balance = initialBalance;
    }

    // Getters
    public String getAccountNumber() { return accountNumber; }
    public String getOwner() { return owner; }
    public double getBalance() { return balance; }

    // Business logic in setters
    public void deposit(double amount) {
        if (amount > 0) {
            balance += amount;
            System.out.printf("Deposited: $%.2f | New Balance: $%.2f%n", amount, balance);
        } else {
            System.out.println("Invalid deposit amount.");
        }
    }

    public void withdraw(double amount) {
        if (amount > 0 && amount <= balance) {
            balance -= amount;
            System.out.printf("Withdrawn: $%.2f | Remaining: $%.2f%n", amount, balance);
        } else {
            System.out.println("Insufficient funds or invalid amount.");
        }
    }
}

// Usage
BankAccount account = new BankAccount("ACC001", "Alice", 1000.0);
account.deposit(500);        // Deposited: $500.00 | New Balance: $1500.00
account.withdraw(200);       // Withdrawn: $200.00 | Remaining: $1300.00
account.withdraw(2000);      // Insufficient funds
System.out.println("Balance: $" + account.getBalance()); // Balance: $1300.0
```

### Access Modifiers

| Modifier | Same Class | Same Package | Subclass | Other Package |
|----------|-----------|--------------|----------|---------------|
| `private` | ✅ | ❌ | ❌ | ❌ |
| `default` | ✅ | ✅ | ❌ | ❌ |
| `protected` | ✅ | ✅ | ✅ | ❌ |
| `public` | ✅ | ✅ | ✅ | ✅ |

---

## 15. Interfaces

An interface defines a **contract** — a set of methods that implementing classes must provide.

```java
// Interface definition
public interface Drawable {
    double PI = 3.14159;  // implicitly public static final

    void draw();          // implicitly public abstract

    // Default method (Java 8+)
    default void description() {
        System.out.println("I am a drawable shape.");
    }

    // Static method (Java 8+)
    static void info() {
        System.out.println("Drawable Interface v1.0");
    }
}

public interface Resizable {
    void resize(double factor);
}

// Implementing multiple interfaces
public class Square implements Drawable, Resizable {
    double side;

    public Square(double side) { this.side = side; }

    @Override
    public void draw() {
        System.out.println("Drawing a square with side: " + side);
    }

    @Override
    public void resize(double factor) {
        side *= factor;
        System.out.println("Resized square, new side: " + side);
    }
}

// Usage
Square sq = new Square(5);
sq.draw();           // Drawing a square with side: 5.0
sq.description();    // I am a drawable shape.
sq.resize(2.0);      // Resized square, new side: 10.0
Drawable.info();     // Drawable Interface v1.0

// Interface as type
Drawable d = new Square(3);
d.draw();
```

### Interface vs Abstract Class

| Feature | Interface | Abstract Class |
|---------|-----------|---------------|
| Instantiation | ❌ | ❌ |
| Multiple inheritance | ✅ | ❌ |
| Constructors | ❌ | ✅ |
| Fields | `public static final` only | Any type |
| Method types | `abstract`, `default`, `static` | Any |

---

## 16. Exception Handling

Exception handling manages **runtime errors** gracefully.

### Exception Hierarchy
```
Throwable
├── Error (JVM errors — don't handle)
│   ├── OutOfMemoryError
│   └── StackOverflowError
└── Exception
    ├── Checked Exceptions (must be handled)
    │   ├── IOException
    │   ├── SQLException
    │   └── FileNotFoundException
    └── RuntimeException (unchecked)
        ├── NullPointerException
        ├── ArrayIndexOutOfBoundsException
        ├── ArithmeticException
        └── NumberFormatException
```

### try-catch-finally
```java
public class ExceptionDemo {
    public static void main(String[] args) {
        try {
            int[] arr = new int[5];
            arr[10] = 50;  // ArrayIndexOutOfBoundsException
        } catch (ArrayIndexOutOfBoundsException e) {
            System.out.println("Array error: " + e.getMessage());
        } catch (Exception e) {
            System.out.println("General error: " + e.getMessage());
        } finally {
            System.out.println("Finally block always executes.");
        }

        // Multiple exceptions in one catch (Java 7+)
        try {
            String s = null;
            s.length();  // NullPointerException
        } catch (NullPointerException | IllegalArgumentException e) {
            System.out.println("Caught: " + e.getClass().getSimpleName());
        }
    }
}
```

### throws and throw
```java
// throws — declares that method may throw exception
public static int divide(int a, int b) throws ArithmeticException {
    if (b == 0) throw new ArithmeticException("Cannot divide by zero");
    return a / b;
}

// Usage
try {
    System.out.println(divide(10, 2));   // 5
    System.out.println(divide(10, 0));   // throws exception
} catch (ArithmeticException e) {
    System.out.println("Error: " + e.getMessage());
}
```

### Custom Exceptions
```java
// Custom checked exception
public class InsufficientFundsException extends Exception {
    private double amount;

    public InsufficientFundsException(double amount) {
        super("Insufficient funds. Shortfall: $" + amount);
        this.amount = amount;
    }

    public double getAmount() { return amount; }
}

// Using custom exception
public void withdraw(double amount) throws InsufficientFundsException {
    if (amount > balance) {
        throw new InsufficientFundsException(amount - balance);
    }
    balance -= amount;
}

// try-with-resources (auto-closes resources)
try (FileReader fr = new FileReader("file.txt");
     BufferedReader br = new BufferedReader(fr)) {
    String line;
    while ((line = br.readLine()) != null) {
        System.out.println(line);
    }
} catch (IOException e) {
    System.out.println("File error: " + e.getMessage());
}
// fr and br are automatically closed
```

---

## 17. Collections Framework

The Java Collections Framework provides **data structures** and algorithms.

```
Collection (Interface)
├── List — ordered, duplicates allowed
│   ├── ArrayList
│   ├── LinkedList
│   └── Vector
├── Set — unordered, no duplicates
│   ├── HashSet
│   ├── LinkedHashSet
│   └── TreeSet
└── Queue
    ├── PriorityQueue
    └── LinkedList

Map (Interface) — key-value pairs
├── HashMap
├── LinkedHashMap
├── TreeMap
└── Hashtable
```

### ArrayList
```java
import java.util.*;

List<String> fruits = new ArrayList<>();
fruits.add("Apple");
fruits.add("Banana");
fruits.add("Cherry");
fruits.add(1, "Mango");       // insert at index 1

System.out.println(fruits);           // [Apple, Mango, Banana, Cherry]
System.out.println(fruits.get(2));    // Banana
System.out.println(fruits.size());    // 4
System.out.println(fruits.contains("Apple")); // true

fruits.remove("Banana");
fruits.remove(0);             // remove by index
System.out.println(fruits);   // [Mango, Cherry]

Collections.sort(fruits);
System.out.println(fruits);   // [Cherry, Mango]
```

### LinkedList
```java
LinkedList<Integer> ll = new LinkedList<>();
ll.add(1);
ll.addFirst(0);    // add at beginning
ll.addLast(2);     // add at end
ll.add(3);

System.out.println(ll.getFirst());  // 0
System.out.println(ll.getLast());   // 3
ll.removeFirst();
ll.removeLast();
System.out.println(ll);             // [1, 2]
```

### HashSet
```java
Set<String> set = new HashSet<>();
set.add("Apple");
set.add("Banana");
set.add("Apple");   // duplicate — ignored
set.add("Cherry");

System.out.println(set);          // [Apple, Cherry, Banana] — unordered
System.out.println(set.size());   // 3
System.out.println(set.contains("Banana")); // true

// TreeSet — sorted
Set<Integer> treeSet = new TreeSet<>(Arrays.asList(5, 2, 8, 1, 9));
System.out.println(treeSet);  // [1, 2, 5, 8, 9]
```

### HashMap
```java
Map<String, Integer> scores = new HashMap<>();
scores.put("Alice", 95);
scores.put("Bob", 82);
scores.put("Charlie", 91);
scores.put("Alice", 98);       // update existing key

System.out.println(scores.get("Bob"));     // 82
System.out.println(scores.containsKey("Charlie")); // true
System.out.println(scores.getOrDefault("Dave", 0)); // 0

// Iterating over HashMap
for (Map.Entry<String, Integer> entry : scores.entrySet()) {
    System.out.println(entry.getKey() + ": " + entry.getValue());
}

// Iterating keys only
for (String key : scores.keySet()) {
    System.out.println(key + " -> " + scores.get(key));
}

scores.remove("Bob");
System.out.println(scores.size());  // 2
```

### Stack & Queue
```java
// Stack (LIFO)
Deque<Integer> stack = new ArrayDeque<>();
stack.push(1);
stack.push(2);
stack.push(3);
System.out.println(stack.pop());   // 3
System.out.println(stack.peek());  // 2

// Queue (FIFO)
Queue<String> queue = new LinkedList<>();
queue.offer("First");
queue.offer("Second");
queue.offer("Third");
System.out.println(queue.poll());  // First
System.out.println(queue.peek());  // Second

// Priority Queue
PriorityQueue<Integer> pq = new PriorityQueue<>();
pq.offer(30);
pq.offer(10);
pq.offer(20);
while (!pq.isEmpty()) {
    System.out.print(pq.poll() + " ");  // 10 20 30 (sorted)
}
```

---

## 18. Generics

Generics provide **type safety** and enable code reuse with different data types.

```java
// Generic class
public class Box<T> {
    private T content;

    public void set(T content) { this.content = content; }
    public T get() { return content; }

    @Override
    public String toString() {
        return "Box[" + content + "]";
    }
}

Box<Integer> intBox = new Box<>();
intBox.set(42);
System.out.println(intBox.get());  // 42

Box<String> strBox = new Box<>();
strBox.set("Hello");
System.out.println(strBox);        // Box[Hello]

// Generic method
public static <T extends Comparable<T>> T findMax(T[] arr) {
    T max = arr[0];
    for (T item : arr) {
        if (item.compareTo(max) > 0) max = item;
    }
    return max;
}

Integer[] nums = {3, 7, 2, 9, 5};
System.out.println(findMax(nums));  // 9

String[] words = {"banana", "apple", "cherry"};
System.out.println(findMax(words)); // cherry

// Bounded type parameters
public <T extends Number> double sum(List<T> list) {
    double total = 0;
    for (T item : list) total += item.doubleValue();
    return total;
}

// Wildcards
public static void printList(List<?> list) {
    for (Object item : list) System.out.print(item + " ");
    System.out.println();
}
```

---

## 19. Iterators & For-Each

### Iterator Interface
```java
import java.util.*;

List<String> names = new ArrayList<>(Arrays.asList("Alice", "Bob", "Charlie", "Dave"));

// Using Iterator
Iterator<String> it = names.iterator();
while (it.hasNext()) {
    String name = it.next();
    if (name.startsWith("C")) {
        it.remove();  // safe removal during iteration
    }
}
System.out.println(names);  // [Alice, Bob, Dave]

// ListIterator (bidirectional)
ListIterator<String> lit = names.listIterator();
while (lit.hasNext()) {
    String name = lit.next();
    lit.set(name.toUpperCase());  // replace element
}
System.out.println(names);  // [ALICE, BOB, DAVE]
```

### Iterable Custom Class
```java
public class NumberRange implements Iterable<Integer> {
    private int start, end;

    public NumberRange(int start, int end) {
        this.start = start;
        this.end = end;
    }

    @Override
    public Iterator<Integer> iterator() {
        return new Iterator<>() {
            int current = start;

            @Override
            public boolean hasNext() { return current <= end; }

            @Override
            public Integer next() { return current++; }
        };
    }
}

// Usage with for-each
for (int n : new NumberRange(1, 5)) {
    System.out.print(n + " ");  // 1 2 3 4 5
}
```

---

## 20. Java I/O (Input/Output)

### Reading User Input
```java
import java.util.Scanner;

Scanner scanner = new Scanner(System.in);

System.out.print("Enter your name: ");
String name = scanner.nextLine();

System.out.print("Enter your age: ");
int age = scanner.nextInt();

System.out.print("Enter your salary: ");
double salary = scanner.nextDouble();

System.out.printf("Hello %s! Age: %d, Salary: $%.2f%n", name, age, salary);

scanner.close();
```

### Console Output
```java
System.out.println("Newline at end");
System.out.print("No newline");
System.out.printf("Formatted: %d, %.2f, %s%n", 42, 3.14, "hello");

// printf format specifiers
System.out.printf("%-10s %5d%n", "Alice", 95);   // left-align
System.out.printf("%-10s %5d%n", "Bob", 82);
/*
Alice         95
Bob           82
*/
```

---

## 21. File Handling

### Writing to a File
```java
import java.io.*;
import java.nio.file.*;

// Using FileWriter (traditional)
try (FileWriter fw = new FileWriter("output.txt");
     BufferedWriter bw = new BufferedWriter(fw)) {
    bw.write("Line 1: Hello, World!");
    bw.newLine();
    bw.write("Line 2: Java File Handling");
} catch (IOException e) {
    e.printStackTrace();
}

// Using NIO (modern approach)
String content = "Hello from NIO!\nJava is awesome!";
Files.writeString(Path.of("output.txt"), content);

// Writing multiple lines
List<String> lines = List.of("First line", "Second line", "Third line");
Files.write(Path.of("lines.txt"), lines);
```

### Reading from a File
```java
// Using BufferedReader
try (BufferedReader br = new BufferedReader(new FileReader("output.txt"))) {
    String line;
    while ((line = br.readLine()) != null) {
        System.out.println(line);
    }
} catch (IOException e) {
    e.printStackTrace();
}

// Using NIO (simple)
String content = Files.readString(Path.of("output.txt"));
System.out.println(content);

// Reading all lines
List<String> lines = Files.readAllLines(Path.of("lines.txt"));
lines.forEach(System.out::println);
```

### File Operations
```java
import java.io.File;

File file = new File("test.txt");
File dir = new File("mydir");

// Check existence
System.out.println(file.exists());       // false
System.out.println(file.isFile());       // false
System.out.println(dir.isDirectory());   // false

// Create
file.createNewFile();
dir.mkdir();
dir.mkdirs();  // creates parent directories too

// File metadata
System.out.println(file.getName());       // test.txt
System.out.println(file.getAbsolutePath());
System.out.println(file.length());        // size in bytes
System.out.println(file.canRead());
System.out.println(file.canWrite());

// List directory contents
String[] files = dir.list();
File[] fileObjects = dir.listFiles();

// Delete
file.delete();
```

---

## 22. Multithreading & Concurrency

### Creating Threads
```java
// Method 1: Extending Thread class
public class MyThread extends Thread {
    private String name;

    public MyThread(String name) { this.name = name; }

    @Override
    public void run() {
        for (int i = 1; i <= 5; i++) {
            System.out.println(name + ": " + i);
            try { Thread.sleep(100); } catch (InterruptedException e) { }
        }
    }
}

// Method 2: Implementing Runnable (preferred)
public class MyRunnable implements Runnable {
    @Override
    public void run() {
        System.out.println("Running in: " + Thread.currentThread().getName());
    }
}

// Starting threads
MyThread t1 = new MyThread("Thread-A");
MyThread t2 = new MyThread("Thread-B");
t1.start();
t2.start();

Thread t3 = new Thread(new MyRunnable());
Thread t4 = new Thread(() -> System.out.println("Lambda thread!"));
t3.start();
t4.start();

// Wait for threads to finish
t1.join();
t2.join();
System.out.println("All threads done!");
```

### Synchronization
```java
public class Counter {
    private int count = 0;

    // Synchronized method — only one thread at a time
    public synchronized void increment() {
        count++;
    }

    public synchronized int getCount() {
        return count;
    }
}

// Synchronized block
public void increment() {
    synchronized (this) {
        count++;
    }
}
```

### ExecutorService (Thread Pool)
```java
import java.util.concurrent.*;

ExecutorService executor = Executors.newFixedThreadPool(3);

for (int i = 1; i <= 10; i++) {
    final int taskId = i;
    executor.submit(() -> {
        System.out.println("Task " + taskId + " executed by " +
                Thread.currentThread().getName());
    });
}

executor.shutdown();
executor.awaitTermination(10, TimeUnit.SECONDS);
```

### Callable & Future
```java
ExecutorService executor = Executors.newSingleThreadExecutor();

Callable<Integer> task = () -> {
    Thread.sleep(1000);
    return 42;
};

Future<Integer> future = executor.submit(task);

System.out.println("Doing other work...");
Integer result = future.get();  // blocks until done
System.out.println("Result: " + result);  // 42

executor.shutdown();
```

---

## 23. Lambda Expressions

Lambdas provide a concise way to write **anonymous functions**.

### Syntax
```java
// Before Lambda
Runnable r = new Runnable() {
    @Override
    public void run() {
        System.out.println("Hello!");
    }
};

// With Lambda
Runnable r = () -> System.out.println("Hello!");

// Lambda with parameters
Comparator<String> comp = (a, b) -> a.compareTo(b);

// Lambda with body
Comparator<String> comp2 = (a, b) -> {
    System.out.println("Comparing " + a + " and " + b);
    return a.compareTo(b);
};
```

### Lambda with Collections
```java
List<String> names = Arrays.asList("Charlie", "Alice", "Bob", "Diana");

// Sort with lambda
names.sort((a, b) -> a.compareTo(b));
names.sort(String::compareTo);  // method reference

// forEach
names.forEach(name -> System.out.println("Hello, " + name));
names.forEach(System.out::println);  // method reference

// removeIf
names.removeIf(name -> name.length() > 5);
System.out.println(names);  // [Alice, Bob]

// replaceAll
names.replaceAll(String::toUpperCase);
System.out.println(names);  // [ALICE, BOB]
```

### Method References
```java
// Static method reference
Function<String, Integer> parse = Integer::parseInt;
System.out.println(parse.apply("42"));  // 42

// Instance method reference on type
Function<String, String> upper = String::toUpperCase;
System.out.println(upper.apply("hello"));  // HELLO

// Instance method reference on object
String greeting = "Hello!";
Supplier<Integer> len = greeting::length;
System.out.println(len.get());  // 6

// Constructor reference
Supplier<ArrayList<String>> listFactory = ArrayList::new;
ArrayList<String> list = listFactory.get();
```

---

## 24. Stream API

Streams enable **functional-style operations** on collections.

```java
import java.util.*;
import java.util.stream.*;

List<Integer> numbers = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);

// filter — keep elements matching predicate
List<Integer> evens = numbers.stream()
    .filter(n -> n % 2 == 0)
    .collect(Collectors.toList());
System.out.println(evens);  // [2, 4, 6, 8, 10]

// map — transform elements
List<Integer> squares = numbers.stream()
    .map(n -> n * n)
    .collect(Collectors.toList());
System.out.println(squares);  // [1, 4, 9, 16, 25, 36, 49, 64, 81, 100]

// reduce — aggregate to single value
int sum = numbers.stream()
    .reduce(0, Integer::sum);
System.out.println("Sum: " + sum);  // 55

// Chaining operations
double result = numbers.stream()
    .filter(n -> n % 2 != 0)   // odd numbers
    .mapToDouble(n -> n * 1.5) // multiply by 1.5
    .sum();
System.out.println(result);  // 37.5

// collect to different types
Map<Boolean, List<Integer>> partitioned = numbers.stream()
    .collect(Collectors.partitioningBy(n -> n % 2 == 0));
System.out.println("Even: " + partitioned.get(true));
System.out.println("Odd:  " + partitioned.get(false));

// String joining
List<String> names = List.of("Alice", "Bob", "Charlie");
String joined = names.stream()
    .collect(Collectors.joining(", ", "[", "]"));
System.out.println(joined);  // [Alice, Bob, Charlie]

// Statistics
IntSummaryStatistics stats = numbers.stream()
    .mapToInt(Integer::intValue)
    .summaryStatistics();
System.out.println("Min: " + stats.getMin());    // 1
System.out.println("Max: " + stats.getMax());    // 10
System.out.println("Avg: " + stats.getAverage()); // 5.5
System.out.println("Count: " + stats.getCount()); // 10

// distinct, sorted, limit, skip
List<Integer> processed = Stream.of(3,1,4,1,5,9,2,6,5,3)
    .distinct()
    .sorted()
    .skip(2)
    .limit(4)
    .collect(Collectors.toList());
System.out.println(processed);  // [3, 4, 5, 6]

// anyMatch, allMatch, noneMatch
boolean anyNegative = numbers.stream().anyMatch(n -> n < 0);  // false
boolean allPositive = numbers.stream().allMatch(n -> n > 0);  // true
boolean noneNegative = numbers.stream().noneMatch(n -> n < 0); // true
```

---

## 25. Functional Interfaces

Java provides built-in **functional interfaces** in `java.util.function`.

```java
import java.util.function.*;

// Predicate<T> — takes T, returns boolean
Predicate<Integer> isEven = n -> n % 2 == 0;
Predicate<Integer> isPositive = n -> n > 0;
Predicate<Integer> isEvenAndPositive = isEven.and(isPositive);
System.out.println(isEven.test(4));                // true
System.out.println(isEvenAndPositive.test(-4));    // false

// Function<T, R> — takes T, returns R
Function<String, Integer> strLen = String::length;
Function<Integer, Integer> doubler = n -> n * 2;
Function<String, Integer> lenDoubled = strLen.andThen(doubler);
System.out.println(lenDoubled.apply("Hello"));     // 10

// BiFunction<T, U, R> — takes T and U, returns R
BiFunction<String, Integer, String> repeat = (s, n) -> s.repeat(n);
System.out.println(repeat.apply("Java! ", 3));      // Java! Java! Java!

// Consumer<T> — takes T, returns void
Consumer<String> printer = System.out::println;
Consumer<String> upperPrinter = s -> System.out.println(s.toUpperCase());
Consumer<String> both = printer.andThen(upperPrinter);
both.accept("hello");  // hello \n HELLO

// Supplier<T> — takes nothing, returns T
Supplier<Double> randomSupplier = Math::random;
System.out.println(randomSupplier.get());

// UnaryOperator<T> — takes T, returns T
UnaryOperator<String> trim = String::trim;
UnaryOperator<String> upper = String::toUpperCase;
UnaryOperator<String> trimAndUpper = trim.andThen(upper);
System.out.println(trimAndUpper.apply("  hello  "));  // HELLO

// BinaryOperator<T> — takes two T, returns T
BinaryOperator<Integer> add = Integer::sum;
BinaryOperator<Integer> max = Integer::max;
System.out.println(add.apply(3, 7));   // 10
System.out.println(max.apply(3, 7));   // 7
```

---

## 26. Optional Class

`Optional<T>` avoids `NullPointerException` by wrapping a potentially null value.

```java
import java.util.Optional;

// Creating Optional
Optional<String> present = Optional.of("Hello");
Optional<String> empty = Optional.empty();
Optional<String> nullable = Optional.ofNullable(null);  // safe null wrapping

// Checking
System.out.println(present.isPresent());  // true
System.out.println(empty.isEmpty());      // true

// Getting value
System.out.println(present.get());                    // Hello
System.out.println(empty.orElse("Default"));          // Default
System.out.println(empty.orElseGet(() -> "Generated")); // Generated

// Transforming
Optional<Integer> length = present.map(String::length);
System.out.println(length.orElse(0));  // 5

// Filtering
Optional<String> filtered = present.filter(s -> s.startsWith("H"));
System.out.println(filtered.isPresent());  // true

// ifPresent
present.ifPresent(s -> System.out.println("Value: " + s)); // Value: Hello

// Real-world example
public Optional<User> findUserById(int id) {
    return users.stream()
        .filter(u -> u.getId() == id)
        .findFirst();
}

Optional<User> user = findUserById(42);
String username = user.map(User::getName).orElse("Guest");
```

---

## 27. Java 8+ Features

### Records (Java 16+)
```java
// Immutable data carrier — auto-generates constructor, getters, equals, hashCode, toString
public record Point(double x, double y) {
    // Custom method
    public double distanceTo(Point other) {
        double dx = this.x - other.x;
        double dy = this.y - other.y;
        return Math.sqrt(dx * dx + dy * dy);
    }
}

Point p1 = new Point(0, 0);
Point p2 = new Point(3, 4);
System.out.println(p1);              // Point[x=0.0, y=0.0]
System.out.println(p1.x());         // 0.0
System.out.println(p1.distanceTo(p2)); // 5.0
```

### Sealed Classes (Java 17+)
```java
// Restricts which classes can extend/implement
public sealed class Shape permits Circle, Rectangle, Triangle { }
public final class Circle extends Shape { double radius; }
public final class Rectangle extends Shape { double width, height; }
public non-sealed class Triangle extends Shape { }  // can be extended further
```

### Pattern Matching (Java 16+)
```java
Object obj = "Hello, World!";

// Old way
if (obj instanceof String) {
    String s = (String) obj;
    System.out.println(s.length());
}

// New way (pattern matching)
if (obj instanceof String s) {
    System.out.println(s.length());  // 13
}

// With switch (Java 21+)
String result = switch (obj) {
    case Integer i -> "Integer: " + i;
    case String s -> "String of length: " + s.length();
    case null -> "Null value";
    default -> "Other: " + obj;
};
```

### CompletableFuture (Async Programming)
```java
import java.util.concurrent.CompletableFuture;

CompletableFuture<String> future = CompletableFuture
    .supplyAsync(() -> {
        // Simulate async work
        return "Hello";
    })
    .thenApply(s -> s + " World")
    .thenApply(String::toUpperCase);

System.out.println(future.get());  // HELLO WORLD

// Combining futures
CompletableFuture<String> f1 = CompletableFuture.supplyAsync(() -> "Hello");
CompletableFuture<String> f2 = CompletableFuture.supplyAsync(() -> " World");

CompletableFuture<String> combined = f1.thenCombine(f2, (a, b) -> a + b);
System.out.println(combined.get());  // Hello World
```

---

## 28. Design Patterns

### Singleton Pattern
```java
public class Singleton {
    private static volatile Singleton instance;

    private Singleton() { }  // private constructor

    public static Singleton getInstance() {
        if (instance == null) {
            synchronized (Singleton.class) {
                if (instance == null) {
                    instance = new Singleton();
                }
            }
        }
        return instance;
    }

    public void doSomething() {
        System.out.println("Singleton instance working!");
    }
}

Singleton s1 = Singleton.getInstance();
Singleton s2 = Singleton.getInstance();
System.out.println(s1 == s2);  // true
```

### Factory Pattern
```java
public interface Notification {
    void send(String message);
}

public class EmailNotification implements Notification {
    public void send(String message) {
        System.out.println("Email: " + message);
    }
}

public class SMSNotification implements Notification {
    public void send(String message) {
        System.out.println("SMS: " + message);
    }
}

public class NotificationFactory {
    public static Notification create(String type) {
        return switch (type.toLowerCase()) {
            case "email" -> new EmailNotification();
            case "sms"   -> new SMSNotification();
            default -> throw new IllegalArgumentException("Unknown type: " + type);
        };
    }
}

Notification n = NotificationFactory.create("email");
n.send("Hello!");  // Email: Hello!
```

### Builder Pattern
```java
public class Pizza {
    private String size;
    private String crust;
    private boolean cheese;
    private boolean pepperoni;
    private boolean mushrooms;

    private Pizza(Builder builder) {
        this.size = builder.size;
        this.crust = builder.crust;
        this.cheese = builder.cheese;
        this.pepperoni = builder.pepperoni;
        this.mushrooms = builder.mushrooms;
    }

    @Override
    public String toString() {
        return String.format("Pizza[size=%s, crust=%s, cheese=%b, pepperoni=%b, mushrooms=%b]",
                size, crust, cheese, pepperoni, mushrooms);
    }

    public static class Builder {
        private String size;
        private String crust = "thin";
        private boolean cheese = false;
        private boolean pepperoni = false;
        private boolean mushrooms = false;

        public Builder(String size) { this.size = size; }
        public Builder crust(String crust) { this.crust = crust; return this; }
        public Builder cheese() { this.cheese = true; return this; }
        public Builder pepperoni() { this.pepperoni = true; return this; }
        public Builder mushrooms() { this.mushrooms = true; return this; }
        public Pizza build() { return new Pizza(this); }
    }
}

Pizza pizza = new Pizza.Builder("Large")
    .crust("thick")
    .cheese()
    .pepperoni()
    .build();
System.out.println(pizza);
```

### Observer Pattern
```java
import java.util.*;

public interface Observer {
    void update(String event);
}

public class EventManager {
    private List<Observer> observers = new ArrayList<>();

    public void subscribe(Observer o) { observers.add(o); }
    public void unsubscribe(Observer o) { observers.remove(o); }

    public void notify(String event) {
        for (Observer o : observers) o.update(event);
    }
}

public class Logger implements Observer {
    public void update(String event) {
        System.out.println("LOG: " + event);
    }
}

EventManager manager = new EventManager();
manager.subscribe(new Logger());
manager.notify("User logged in");   // LOG: User logged in
manager.notify("File saved");       // LOG: File saved
```

---

## 29. Java Memory Management & Garbage Collection

### Memory Areas
```
JVM Memory
├── Heap (Objects & instances live here)
│   ├── Young Generation (new objects)
│   │   ├── Eden Space
│   │   └── Survivor Spaces (S0, S1)
│   └── Old Generation (long-lived objects)
├── Stack (method calls, local variables)
├── Method Area (class metadata, static fields)
├── PC Register (current instruction pointer)
└── Native Method Stack
```

### Garbage Collection
```java
// Java automatically reclaims memory from unreachable objects
// You cannot force GC, but can suggest it:
System.gc();  // just a suggestion, not guaranteed

// Objects become eligible for GC when no references point to them
String s = new String("Hello");
s = null;  // original object is now eligible for GC

// Weak references — collected when GC runs
import java.lang.ref.WeakReference;
WeakReference<String> weakRef = new WeakReference<>(new String("Weak"));
System.out.println(weakRef.get());  // Weak (or null if GC ran)
```

### Memory Best Practices
```java
// ✅ Close resources to avoid memory leaks
try (Connection conn = getConnection()) {
    // use conn
}  // auto-closed

// ✅ Use StringBuilder for string concatenation in loops
StringBuilder sb = new StringBuilder();
for (int i = 0; i < 1000; i++) {
    sb.append(i);  // efficient
}

// ❌ Don't do this — creates 1000 String objects
String result = "";
for (int i = 0; i < 1000; i++) {
    result += i;  // inefficient — O(n²)
}

// ✅ Nullify large objects when done
largeObject = null;  // helps GC

// ✅ Use primitives over wrapper classes when possible
int x = 5;       // 4 bytes on stack
Integer y = 5;   // object on heap — more overhead
```

---

## 30. Best Practices

### Code Style & Naming
```java
// Classes — PascalCase
public class CustomerAccount { }

// Methods & variables — camelCase
String firstName;
int calculateAge() { }

// Constants — UPPER_SNAKE_CASE
static final int MAX_SIZE = 100;
static final String DEFAULT_NAME = "Guest";

// Packages — lowercase.with.dots
package com.company.project.module;
```

### Clean Code Principles
```java
// ✅ Use meaningful names
int daysSinceLastPurchase;   // clear
int d;                        // unclear

// ✅ Small, focused methods
public double calculateTax(double income) {
    return income * getTaxRate(income);
}

// ✅ Avoid magic numbers — use constants
static final double TAX_RATE = 0.20;
double tax = income * TAX_RATE;   // clear
double tax2 = income * 0.20;      // unclear — what is 0.20?

// ✅ Handle exceptions properly
try {
    processData(input);
} catch (IOException e) {
    logger.error("Failed to process data", e);  // log with context
    throw new ServiceException("Processing failed", e);  // don't swallow
}

// ✅ Prefer composition over inheritance
public class Car {
    private Engine engine;  // composition
    private Wheels wheels;
}
```

### SOLID Principles
```java
// S — Single Responsibility: One class, one purpose
public class UserService {
    public User findUser(int id) { /* ... */ }
    public void saveUser(User user) { /* ... */ }
    // Don't add email sending here!
}

// O — Open/Closed: Open for extension, closed for modification
public interface Sorter { void sort(int[] arr); }
public class BubbleSorter implements Sorter { /* ... */ }
public class QuickSorter implements Sorter { /* ... */ }

// L — Liskov Substitution: Subclass should work where parent is expected
// I — Interface Segregation: Many specific interfaces > one large interface
public interface Readable { String read(); }
public interface Writable { void write(String data); }
// Instead of: interface ReadWrite { String read(); void write(String); }

// D — Dependency Inversion: Depend on abstractions, not concretions
public class UserService {
    private final UserRepository repo;  // interface, not concrete class

    public UserService(UserRepository repo) {  // injected
        this.repo = repo;
    }
}
```

### Java Coding Checklist
- ✅ Use `equals()` for String comparison, not `==`
- ✅ Always close streams/connections in `finally` or use try-with-resources
- ✅ Check for `null` before calling methods
- ✅ Use `StringBuilder` for string concatenation in loops
- ✅ Prefer `List`, `Map`, `Set` interfaces over concrete types
- ✅ Override both `equals()` and `hashCode()` together
- ✅ Make fields `private` and expose via getters/setters
- ✅ Use `Optional` instead of returning `null`
- ✅ Prefer immutability — use `final` where possible
- ✅ Write unit tests (JUnit 5)

---

## 📖 Quick Reference Card

```
Data Types:   byte short int long float double char boolean
OOP Pillars:  Encapsulation  Inheritance  Polymorphism  Abstraction
Access:       private → default → protected → public
Collections:  ArrayList LinkedList HashSet TreeSet HashMap TreeMap
Exceptions:   try catch finally throw throws (custom extends Exception)
Streams:      filter map reduce collect forEach anyMatch findFirst
Functional:   Predicate Function Consumer Supplier UnaryOperator
Java 8+:      Lambda  Stream  Optional  Default methods  Method refs
Java 16+:     Records  Pattern matching  Sealed classes
Threads:      Thread Runnable ExecutorService Future CompletableFuture
```

---

## 🔗 Resources

- [Official Java Documentation](https://docs.oracle.com/en/java/)
- [Java SE API Reference](https://docs.oracle.com/en/java/javase/21/docs/api/)
- [OpenJDK](https://openjdk.org/)
- [Effective Java (Book) — Joshua Bloch](https://www.oreilly.com/library/view/effective-java-3rd/9780134686097/)
- [Java Design Patterns](https://java-design-patterns.com/)

---

*📅 Last Updated: 2025 | ☕ Java Version: Java 21 LTS*
