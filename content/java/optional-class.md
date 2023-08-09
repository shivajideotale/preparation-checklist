---
title: "Optional Class"
tags: ["java", "optional"]
weight: 2
---

Optional is a container object used to contain **not-null** objects. Optional object is used to represent null with absent value. This class has various utility methods to facilitate code to handle values as ‘available’ or ‘not available’ instead of checking null values. It is introduced in Java 8.

#### java.util.Optional Methods

##### Static Methods

- [Optional.empty()](#optionalempty) - used to get an empty instance of this Optional class
- [Optional.of(T value)]() -
- [Optional.ofNullable(T value)]() -

##### Instance Methods

- [Optional.isPresent()]() -
- [Optional.get()]() -
- [Optional.ifPresent(Consumer<? super T> consumer)]() -
- [Optional.ifPresentOrElse​(Consumer<? super T> action, Runnable emptyAction)]() -
- [Optional.map(Function<? super T,? extends U> mapper)]() -
- [Optional.orElse(T other)]() -
- [Optional.orElseGet(Supplier<? extends T> other)]() -
- [Optional.orElseThrow(Supplier<? extends X> exceptionSupplier)]() -

#### Optional.empty()

```java
Optional<String> empty = Optional.empty();

```
