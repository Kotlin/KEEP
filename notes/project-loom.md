# What does Project Loom mean for Kotlin

* **Type**: Design notes
* **Author**: Nikita Bobko

> Project Loom is to intended to explore,
incubate and deliver Java VM features and APIs built on top of them for the purpose of supporting easy-to-use,
high-throughput lightweight concurrency and new programming models on the Java platform.
>
> Source: https://wiki.openjdk.org/display/loom/Main

So far Project Loom is about the following features:
1. [JEP 444](https://openjdk.org/jeps/444) Virtual Threads. VM feature. Delivered in Java 21
2. [JEP 505](https://openjdk.org/jeps/505) Structured concurrency. Java Stdlib feature. Fifth Preview
3. [JEP 506](https://openjdk.org/jeps/506) Scoped Values. Java Stdlib feature. Planned for Java 25

## Virtual Threads

**Glossary.** *carrier thread* is the thread that virtual threads are run on.

Virtual Threads is the biggest feature.
It's primarly a VM feature, and a very limited set of public APIs are exposed to developers.

The public API only allows two things:
1. To create a Virtual Thread: `Thread.ofVirtual().start {  }`/`Executors.newVirtualThreadPerTaskExecutor()`
2. To check if the current thread is Virtual: `Thread.currentThread().isVirtual`

Continuation is the basic primitive that Virtual Threads are implemented on top of.

```java
import jdk.internal.vm.Continuation;
import jdk.internal.vm.ContinuationScope;

public class ContinuationsExample {
    public static void main(String[] args) {
        var scope = new ContinuationScope("generator");
        var cont = new Continuation(scope, () -> {
            System.out.println("Before yield");
            Continuation.yield(scope);
            System.out.println("After yield");
        });
        System.out.println("Before the first run");
        cont.run();
        System.out.println("Between runs");
        cont.run();
        System.out.println("After the second run");
    }
}
// java --add-exports java.base/jdk.internal.vm=ALL-UNNAMED ContinuationsExample.java
// Before the first run
// Before yield
// Between runs
// After yield
// After the second run
```

Unfortunately for Kotlin, `jdk.internal.vm.Continuation` API is not public.

The current Virtual threads design idea is that they are drop-in replacement for traditional physical threads,
because of that it's not possible to access the carrier thread,
and it's not possible to choose which threads virtual threads are scheduled to run on.

## Structured concurrency

Basically, the feature brings new `StructuredTaskScope` API.
