---
title: "Optional Class"
tags: ["optional"]
weight: 2
---

Optional is a container object used to contain **not-null** objects. Optional object is used to represent null with absent value. This class has various utility methods to facilitate code to handle values as ‘available’ or ‘not available’ instead of checking null values. It is introduced in Java 8.

#### java.util.Optional<T> Methods

##### Static Methods

- [Optional.empty()](#optionalempty) - It returns an empty Optional object. No value is present for this Optional.
- [Optional.of(T value)](#optionaloft-value) - It returns an Optional with the specified present non-null value.
- [Optional.ofNullable(T value)](#optionalofnullablet-value) - It returns an Optional describing the specified value, if non-null, otherwise returns an empty Optional.

##### Instance Methods

- [Optional.isPresent()]() -
- [Optional.isEmpty()]() -
- [Optional.get()]() -
- [Optional.ifPresent(Consumer<? super T> consumer)]() -
- [Optional.ifPresentOrElse​(Consumer<? super T> action, Runnable emptyAction)]() -
- [Optional.orElse(T other)]() -
- [Optional.orElseGet(Supplier<? extends T> other)]() -
- [Optional.orElseThrow()]() -
- [Optional.orElseThrow(Supplier<? extends X> exceptionSupplier)]() -
- [Optional.map(Function<? super T,? extends U> mapper)]() -

#### Optional.empty()

```java
Optional<String> empty = Optional.empty();

```

#### Optional.of(T value)

```java
Optional<String> empty = Optional.empty();

```

#### Optional.ofNullable(T value)

```java
Optional<String> empty = Optional.empty();

```

#### Other Optional classes

- [java.util.OptionalInt](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/OptionalInt.html)
- [java.util.OptionalDouble](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/OptionalDouble.html)
- [java.util.OptionalLong](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/util/OptionalLong.html)
