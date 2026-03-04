# Reactive Programming Operators Reference (2026)

A comprehensive guide to the essential operators in [Project Reactor](https://projectreactor.io) used for transforming, filtering, and orchestrating reactive streams.

## 🔄 Transformation
Operators that modify the data or the structure of the stream.

*   **`map(Function)`**: Synchronous 1-to-1 transformation.
*   **`flatMap(Function)`**: Asynchronous 1-to-N transformation; does not preserve order.
*   **`concatMap(Function)`**: Asynchronous 1-to-N transformation; preserves order by processing sequentially.
*   **`flatMapSequential(Function)`**: Eagerly subscribes to inner publishers but queues results to maintain order.
*   **`buffer(int)`**: Groups emitted items into a `List`.
*   **`window(int)`**: Groups emitted items into nested `Flux` windows.

## 🔍 Filtering & Selection
Operators that control which items are passed downstream.

*   **`filter(Predicate)`**: Passes only items that satisfy a condition.
*   **`take(long)`**: Limits the stream to the first N items.
*   **`skip(long)`**: Drops the first N items.
*   **`distinct()`**: Removes all duplicate items.
*   **`distinctUntilChanged()`**: Removes consecutive duplicate items.
*   **`sample(Duration)`**: Picks the latest item within a periodic time window.

## 🔗 Combination
Operators that merge multiple publishers into a single stream.

*   **`zip(Publisher, BiFunction)`**: Combines elements from multiple sources pairwise (1st with 1st, 2nd with 2nd).
*   **`merge(Publisher...)`**: Interleaves emissions from multiple sources as they happen.
*   **`concat(Publisher...)`**: Chains publishers sequentially.
*   **`combineLatest(Publisher...)`**: Emits a value whenever any source emits, using the most recent values from all others.
*   **`switchOnNext(Publisher<Publisher>)`**: Cancels the current inner publisher when a new one arrives.

## 🛡️ Error Handling
Operators to manage and recover from failures.

*   **`onErrorReturn(T)`**: Provides a fallback value on error.
*   **`onErrorResume(Function)`**: Switches to a fallback `Publisher` on error.
*   **`onErrorMap(Function)`**: Translates an exception into another type.
*   **`retry(long)`**: Re-subscribes to the source a fixed number of times.
*   **`retryWhen(Retry)`**: Implements complex retry logic (e.g., [Exponential Backoff](https://projectreactor.io)).

## 🕵️ Side Effects & Debugging
Operators for observing signals without modifying the stream.

*   **`doOnNext(Consumer)`**: Triggered when an item is emitted.
*   **`doOnError(Consumer)`**: Triggered when the stream fails.
*   **`doOnTerminate(Runnable)`**: Triggered when the stream ends (success or error).
*   **`log()`**: Logs all [Reactive Streams](https://www.reactive-streams.org) signals (onNext, onError, onComplete, etc.).
*   **`checkpoint()`**: Aids debugging by marking the assembly site of the operator chain.

## 🧵 Threading & Schedulers
Operators that control the execution context.

*   **`publishOn(Scheduler)`**: Affects all operators **downstream**.
*   **`subscribeOn(Scheduler)`**: Affects the **entire chain** (upstream and downstream) from the point of subscription.

## 🏁 Terminal Operators
Operators used to consume the stream or convert its type.

*   **`block()`**: Blocks the current thread until a value is received (Use with caution).
*   **`collectList()`**: Converts a `Flux<T>` into a `Mono<List<T>>`.
*   **`as(Function)`**: Chains the current `Flux`/`Mono` into a custom transformation function.

---

### Resources
*   [Which operator do I need? (Decision Tree)](https://projectreactor.ioindex.html#which-operator)
*   [Project Reactor Reference Guide](https://projectreactor.io)
