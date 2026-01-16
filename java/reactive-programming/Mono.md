# Mono Factory Methods Reference (2026)

This document provides a comprehensive guide to the static factory methods available in [Project Reactor's](https://projectreactor.io) `Mono` class for 2026.

## 🚀 Constant & Immediate Sources
Use these when the data or signal is already available or static.

*   **`Mono.just(T data)`**: Eagerly captures a value and emits it to subscribers.
*   **`Mono.justOrEmpty(Object)`**: Emits a value if present; otherwise completes empty (supports `Optional`).
*   **`Mono.empty()`**: Completes immediately without emitting any items.
*   **`Mono.error(Throwable)`**: Signals an error immediately upon subscription.
*   **`Mono.never()`**: A "infinite" stream that sends no signals whatsoever.

## ⏳ Lazy & Deferred Execution
Use these to ensure logic is only executed when someone subscribes to the stream.

*   **`Mono.defer(Supplier<Mono<T>>)`**: Generates a fresh `Mono` for every subscriber.
*   **`Mono.deferContextual(Function<ContextView, Mono<T>>)`**: Defers creation while providing access to the [Reactive Context](https://projectreactor.ioadvancedFeatures/context.html).
*   **`Mono.fromCallable(Callable<T>)`**: Wraps a synchronous block that returns a value or throws an exception.
*   **`Mono.fromSupplier(Supplier<T>)`**: Wraps a standard Java `Supplier`.
*   **`Mono.fromRunnable(Runnable)`**: Runs a task and completes empty once the task finishes.

## 🌉 Bridging Async Types
Use these to integrate with standard Java concurrency or other [Reactive Streams](https://www.reactive-streams.org) implementations.

*   **`Mono.fromCompletionStage(CompletionStage<T>)`**: Converts any `CompletionStage` to a `Mono`.
*   **`Mono.fromFuture(CompletableFuture<T>)`**: Converts a `CompletableFuture` to a `Mono`.
*   **`Mono.from(Publisher<T>)`**: Standard conversion from any external `Publisher` (e.g., a `Flux` or RxJava `Single`).
*   **`Mono.fromDirect(Publisher<T>)`**: A low-overhead version of `from()` for trusted `Publisher` implementations.

## 🛠️ Programmatic & Specialized
Use these for complex logic or time-based events.

*   **`Mono.create(Consumer<MonoSink<T>>)`**: Bridge for callback-based APIs by manually signaling `success` or `error`.
*   **`Mono.delay(Duration)`**: Emits a long (`0L`) after the specified time delay.
*   **`Mono.using(Supplier<D>, Function<D, Mono<T>>, Consumer<D>)`**: Manages resource lifecycles (open, use, close) automatically.

## 📑 Aggregation & Combination
Use these to orchestrate multiple reactive sources.

*   **`Mono.zip(Mono1, Mono2, ...)`**: Combines multiple results into a single `Tuple`.
*   **`Mono.firstWithValue(Publisher<T>...)`**: Races multiple publishers and returns the first successful value.
*   **`Mono.when(Publisher<?>...)`**: Returns a `Mono<Void>` that completes only after all inputs complete.

---

### Resources
*   [Official Project Reactor Documentation](https://projectreactor.io)
*   [Mono API Javadoc](https://projectreactor.io)
