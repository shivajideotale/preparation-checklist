# Flux Factory Methods Reference (2026)

This document provides a comprehensive guide to the static factory methods available in [Project Reactor's](https://projectreactor.io) `Flux` class for 2026.

## 🚀 Simple & Immediate Sources
Use these to wrap existing data structures or static values into reactive streams.

*   **`Flux.just(T... data)`**: Captures provided elements at instantiation and emits them to subscribers.
*   **`Flux.fromArray(T[])`**: Creates a stream from a standard Java array.
*   **`Flux.fromIterable(Iterable<T>)`**: Converts a `List`, `Set`, or other `Iterable` into a `Flux`.
*   **`Flux.fromStream(Supplier<Stream<T>>)`**: Converts a Java `Stream` into a `Flux`. Using the `Supplier` variant is recommended to support multiple subscriptions.
*   **`Flux.range(int start, int count)`**: Emits a sequence of incrementing integers.
*   **`Flux.empty()`**: Completes immediately without emitting items.
*   **`Flux.error(Throwable)`**: Signals an error immediately upon subscription.

## ⏳ Lazy & Deferred Execution
Use these to ensure the stream is created only when a subscription occurs.

*   **`Flux.defer(Supplier<Flux<T>>)`**: Generates a fresh `Flux` instance for every new subscriber.
*   **`Flux.deferContextual(Function<ContextView, Flux<T>>)`**: Provides access to the [Reactive Context](https://projectreactor.ioadvancedFeatures/context.html) during stream creation.

## ⚙️ Programmatic Generation
Use these for custom emission logic, state management, or bridging legacy APIs.

*   **`Flux.generate(Callable<S>, BiFunction<S, SynchronousSink<T>, S>)`**: A synchronous, stateful generator for emitting items one by one.
*   **`Flux.create(Consumer<FluxSink<T>>)`**: An asynchronous generator that supports multiple emissions per round; best for bridging listener-based APIs.
*   **`Flux.push(Consumer<FluxSink<T>>)`**: A simplified version of `create` intended for single-threaded producers.

## 🕒 Time-Based Sources
Use these for periodic events or delayed emissions.

*   **`Flux.interval(Duration)`**: Emits an incrementing `Long` (starting at 0) every period.
*   **`Flux.interval(Duration delay, Duration period)`**: Starts with an initial delay, then emits periodically.

## 🌉 Orchestration & Combination
Use these to join multiple reactive sources into a single stream.

*   **`Flux.from(Publisher<T>)`**: Converts any [Reactive Streams Publisher](https://www.reactive-streams.org) (like a `Mono` or RxJava type) into a `Flux`.
*   **`Flux.concat(Publisher<T>...)`**: Joins publishers sequentially (waits for one to finish before starting the next).
*   **`Flux.merge(Publisher<T>...)`**: Combines publishers by interweaving their emissions eagerly as they arrive.
*   **`Flux.zip(Publisher<T>...)`**: Pairs elements from multiple publishers into `Tuples`.
*   **`Flux.firstWithValue(Publisher<T>...)`**: Picks the first publisher that emits a value and ignores the others.

## 🛠️ Resource Management
*   **`Flux.using(Supplier<D>, Function<D, Publisher<T>>, Consumer<D>)`**: Safely manages resources (e.g., file handles or sockets) by ensuring they are closed after the stream completes or errors.

---

### Resources
*   [Official Project Reactor Documentation](https://projectreactor.io)
*   [Flux API Javadoc](https://projectreactor.io)
