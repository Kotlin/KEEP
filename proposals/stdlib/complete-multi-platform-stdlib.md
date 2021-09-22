# Complete multiplatform standard library

- **Type**: Standard Library API proposal
- **Author**: smallshen
- **Status**: Submitted
- **Prototype**: Not started

## Summary

The goal is to develop a cross-platform standard library that provides everything needed for development.

- Network
- UI
- etc...

## Similar API review

### JVM

One of the kotlin backed target, has a complete implementation for a lot of things, UI(AWT, Javafx), network(java socket), file system.

However, JVM is JVM only, it is not what Kotlin's Goal, our developer should not be limited in one platform. if we want to get rid of JVM there is GraalVM, but it is not ideal in the moment. There are limitations, and the UI framework cann't be compile to various platform.

## Existing Kotlin Multiplatform Libraries review

Those projects on the list provide a complete basic library implementation in Kotlin Multiplatform

1. [korlibs (github.com)](https://github.com/korlibs)
2. [caffeine-mgn/pw.binom.io: Kotlin IO Library (github.com)](https://github.com/caffeine-mgn/pw.binom.io)

### Cons

1. No uniformity
2. Developers spend a lot of time on different platforms

## Development  Goal

**The default platform will be native.**

Kotlin now has three backends. JVM, native and wasm.

### Kotlin Native library in JVM

JVM has JNI, it allows use native code inside of JVM.

in Java 9+, it also has Cleaner(provid by JVM) which allows control the GC.

Thus, we can generate class for JVM that will call native code with JNI.

Store the pointer on JVM.

#### Kotlin Native

```kotl
class Example(val name: String) {
	fun printTheName() {
		println(name)
	}
}
```

#### Generated class

```java
public final class Example {
    private long pointer;
    
    public Example(String name) {
        
    }
    
    private native init(String name);
    public native String getName();
}
```

```
// jni
init(thisObj: jobject, name: String) {
	val example = Example(name)
	thisObj -> setpointer(example.ptr)
}
```

### Pointer Management

We can look at Skiko [skiko/Managed.kt at master - JetBrains/skiko (github.com)](https://github.com/JetBrains/skiko/blob/master/skiko/src/ commonMain/kotlin/org/jetbrains/skia/impl/Managed.kt) to see how it is done. The manager in Skiko is very good and it can be used directly in further development for communication between JVM and local.

**This is Only on Java 9+**, for Java 8, we can do it through Object::finalize (need test)

### Conclude

This will make development for multiplatform much easier since they only need to consider native target. The JVM target will handled by the auto generation, and JS target should be supported with wasm.

## Alternatives

Just like JVM, jvm provides sockets, ui, math, database connection etc...

### Network 

Ktor is already a multiplatform library, it can be used directlly in the future.

### UI

[Compose](https://github.com/JetBrains/compose-jb). Compose's compiler plugin and runtime is multiplatform, it is based on [JetBrains/skiko: Kotlin MPP bindings to Skia (github.com)](https://github.com/JetBrains/skiko)

Skiko is moving into wasm(js) and native targets. So the compose with material design on all platform will be supported when skiko is finished.

### File System

[korio/korio/src/commonMain/kotlin/com/soywiz/korio/file at master · korlibs/korio (github.com)](https://github.com/korlibs/korio/tree/master/korio/src/commonMain/kotlin/com/soywiz/korio/file)

[pw.binom.io/file at master · caffeine-mgn/pw.binom.io (github.com)](https://github.com/caffeine-mgn/pw.binom.io/tree/master/file)

### Math

[Korlibs: KorMA (soywiz.com)](https://korlibs.soywiz.com/korma/)

### Database

[pw.binom.io/db/postgresql-async at master · caffeine-mgn/pw.binom.io (github.com)](https://github.com/caffeine-mgn/pw.binom.io/tree/master/db/postgresql-async)



## Naming Format

For IO, the package should be kotlin.io

For ui, the packahge should be kotlin.compose

For network, kotlin.network, kotlin.http
