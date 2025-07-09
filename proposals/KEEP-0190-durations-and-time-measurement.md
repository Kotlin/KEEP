# Duration and time measurement API

* **Type**: Standard Library API proposal
* **Author**: Ilya Gorbunov
* **Status**: Experimental since Kotlin 1.3.50, partially stable since 1.6.0
* **Prototype**: Implemented
* **Discussion**: [KEEP-190](https://github.com/Kotlin/KEEP/issues/190)


## Summary

The goal of this proposal is to introduce the following API into the common Kotlin Standard Library:
- the class `Duration` representing the duration of time interval between two instants of time;
- the enum class `DurationUnit` that lists the possible units in which a duration can be expressed;
- the functions `measureTime` and `measureTimedValue` allowing to measure execution time of a code block;
- the interface `TimeSource` abstracting the source of time and allowing to notch a mark on
the time scale and then read the amount of time elapsed from that mark.

## Use cases

#### Measuring code block execution time

The Kotlin Standard Library (for JVM and Native) already contains two functions to measure time that some code block takes 
to execute: `measureTimeMillis` and `measureNanoTime`. They take a function, execute it and return the amount of milliseconds
or nanoseconds elapsed. While it may seem that the difference between them is only in the unit in which the returned value
is expressed, it's not only that, at least in Kotlin/JVM: `measureTimeMillis` uses wall clock to measure time and 
`measureNanoTime` uses a monotonic time source. We had tried to emphasize this difference in the naming, that's why
the second function isn't called `measureTimeNanos`, but that didn't help much: people keep using `measureTimeMillis` because 
they find it more convenient to have the result expressed in milliseconds, 
and not because they need the millisecond accuracy or the wall clock as a source of time.

The proposed function `measureTime` always uses the monotonic time source with the best available precision and accuracy, 
and there's an option to specify another source of time explicitly if that's required. The returned `Duration` value can 
then be shown in desired units and with a desired precision.

Another common missing feature of the existing functions is an ability to get both the result of the code block and the elapsed time.
The proposed `measureTimedValue` function returns `TimedValue<T>` instance, which contains both `T` value and the elapsed `Duration`.


#### Enabling typed API for passing duration values, e.g. timeouts

A lot of existing libraries require expressing durations in their API, for example to take a timeout, time interval or delay value.
There are two common approaches to do that: 
- Provide a single numeric parameter and assume its unit of measure; that assumption is conveyed to the user either with 
the parameter name (`timeoutMs`, `timeoutMillis`) or with its documentation. This way is prone to mistakes of providing
a value expressed in a unit different from the unit that was expected.
- Provide a pair of a numeric parameter and a `TimeUnit` parameter. This way is more explicit about the units, but less
convenient since it requires passing and storing a single entity with two values.

The proposed type `Duration` makes it explicit that a duration value is being expected 
and solves the problem of specifying its unit.

#### Noting time elapsed from a particular point of program in past

While `measureTime` function is convenient to measure time of executing a block of code, there are cases when 
the beginning and end of a time interval measurement cannot be placed in the scope of a function. These cases require
to notch the moment of the beginning, store it somewhere and then calculate the elapsed time from that moment. 
Also, the elapsed time can be noted not only once, as it is with `measureTime`, but multiple times.

The interface `TimeSource` is a basic building block both for implementing `measureTime` and for covering the case above.
It allows to obtain a `TimeMark` that captures the current instant of the time source. That `TimeMark` can be queried later 
for the amount of time elapsed since that instant, potentially multiple times.

#### Noting time remaining to a particular point in future

Timeout dealing cases usually involve taking a `TimeMark` at some point and then verifying whether the elapsed time 
has exceeded the given timeout value. This requires operating two entities: the `TimeMark` and the timeout `Duration`.

Instead, the `TimeMark` can be displaced by the given `Duration` value to get another `TimeMark` representing timeout 
expiration point in the future. The function `elapsedNow` invoked on the latter `TimeMark` will return negative values while the
timeout is not expired, and positive values if it is. For convenience, instead of checking `elapsedNow` function returning
a negative duration one can use `hasPassedNow`/`hasNotPassedNow` functions of the `TimeMark`.
This way timeout can be represented by a single `TimeMark` instead of a `TimeMark` and a `Duration`.

#### Noting time between two time marks

When measuring elapsed time from a single origin point, it's enough to get a `TimeMark` at the origin point 
and then use its `elapsedNow` function.

However, if a use case requires measuring at some point several elapsed time values from several origin points, 
it is hard to do consistently using only the `elapsedNow` function because each its call may return slightly different
value depending on the *current* time in the time source produced these origin time marks. 

```kotlin
val elapsed1 = originMark1.elapsedNow()
val elapsed2 = originMark2.elapsedNow()
// the difference between elapsed1 and elapsed2 depends on the order of calls and 
// on the unpredictable delay between these two calls
```

When, for example, doing several animation calculations, it's important to measure time elapsed from the start
of each animation with regard to the frame rendering time moment consistently, and such unpredictable difference may 
result in unwanted visual artifacts.

To support this case, a time source can return time marks comparable and subtractable with each other, so it is possible
to mark the time moment of a frame once and then calculate all animation elapsed times consistently:

```kotlin
val markNow = timeSource.markNow()
val elapsed1 = markNow - originMark1
val elapsed2 = markNow - originMark2
```

## Similar API review

java.time [Duration](https://docs.oracle.com/javase/8/docs/api/java/time/Duration.html)

* Stores value as `Long` seconds + `Int` nanoseconds
* `toString` returns ISO-8601 representation
* Can be constructed from an integer number of time units: 24h days, hours, minutes, seconds, seconds + nanos, nanoseconds
* Has functions to obtain total integer value expressed in particular unit
* Has functions to obtain integer component values (9+)
* Has arithmetic operations like plus, minus (+ overloads for specific units), multiply and divide by integer, divide by other duration (9+)

.NET [TimeSpan](https://docs.microsoft.com/en-us/dotnet/api/system.timespan?view=netframework-4.7.2)

* Stores duration as a Long number of 100ns ticks, can be negative; therefore its range is ±29222 years
* Default `toString` format is `[d.]hh:mm:ss[.fffffff]` where `d` is the number of 24h whole days, and `f` is a fractional part of second
* Has overloads of `toString` with format and culture
* Can be constructed from a whole number of ticks or double number seconds/minutes/hours/days. 
  Note: in C# integers are implicitly convertible to double, so `TimeSpan.FromHours(2)` works as well.
* Has operators for addition and subtraction with overflow check, but not for multiplication
* Has integer properties to get component values: days, hours, minutes, seconds, milliseconds
* Has double properties to get total duration value expressed in particular units: TotalDays, TotalHours, and so on.

Klock [TimeSpan](https://github.com/korlibs/klock/blob/master/klock/src/commonMain/kotlin/com/soywiz/klock/TimeSpan.kt)

* Stores value as a double number of milliseconds
    * High precision extremely short intervals
    * Low precision extremely long intervals (but up to 142808 years with millisecond precision)
* Has double properties to get total duration value expressed in particular units
* Has arithmetic operations including `TimeSpan.times(Int/Double)` and `TimeSpan.div(TimeSpan)`
* `toString` returns the formatted number of milliseconds + "ms"


## Alternatives

Another approach that was considered is introducing the class `TimeStamp` and the arithmetic operations on it that 
return `Duration`. However, it was concluded to be error-prone because it would be too easy to mix two timestamps taken
from unrelated time sources in a single expression and get nonsense in the result.

**Update:** after considering use cases (see [Noting time between two time marks](#noting-time-between-two-time-marks)), 
we decided to introduce a subtype of `TimeMark`, `ComparableTimeMark`, that allows arithmetic operations 
(subtraction, comparison) on time marks obtained from the same time source, 
even though mixing time marks from different time sources would lead to a runtime exception.

## API details

### Duration

It is proposed to represent duration values with an inline value class `Duration` that wraps a primitive `Long` value. 

```kotlin
value class Duration internal constructor(internal val value: Long) : Comparable<Duration>
```

The property `value` stores a `Long` number encoding either the number of nanoseconds, or the number of milliseconds 
in this time interval.

Such internal representation allows storing durations up to ~146 years with a nanosecond precision and 
durations up to ~146 million years with a millisecond precision.

The `Duration` arithmetic operators working with `Long` underlying values have an efficient implementation 
in Kotlin/JVM and Kotlin/Native, but less efficient in Kotlin/JS, where `Long` values are emulated with a class implementation.

A `Duration` value can be infinite, which is useful for representing infinite timeout values.

A `Duration` value can be negative.

#### Construction

The primary constructor of `Duration` is internal. 
A `Duration` value can be constructed from a numeric value (`Int`, `Long` or `Double`) and 
a unit of duration. If the unit is known in advance at compile time, the extension properties in `Duration.Companion` 
provide the most expressive way to construct a duration, e.g. `1.5.minutes`, `30.seconds`, `500.milliseconds`. 
Otherwise, the extension function `numericValue.toDuration(unit)` can be used.

#### Conversions

A `Duration` value can be retrieved back as a number with:
- the properties `inWholeNanoseconds`, `inWholeSeconds`, `inWholeHours`, etc, that return the duration value rounded to 
  a long number in the specified fixed unit;
- the functions `toDouble(unit)`, `toLong(unit)`, `toInt(unit)` that return a number of the particular `Double`, `Long` or `Int` type
expressed in the unit specified with the parameter `unit`.

#### Operators

`Duration` values support the following operations:

- addition and subtraction: `Duration +- Duration = Duration`
- multiplication by a scalar: `Duration * number = Duration`
- multiplication a scalar by a duration: `number * Duration = Duration` 
- division by a scalar: `Duration / number = Duration`
- division by a duration: `Duration / Duration = Double`
- negation: `-Duration`
- absolute value: `Duration.absoluteValue`
- `isNegative()`, `isPositive()`, `isFinite()`, `isInfinite()` functions

#### Equality and comparison

The equality of Duration is defined so that two duration values representing time intervals of the same length
are equal, no matter which numeric types and duration units were used to construct these durations, e.g.:

```kotlin
1.days == 24.hours
1.5.minutes == 90.seconds
```

`compareTo` operation is implemented in a similar way:

```kotlin
1.hours < 65.minutes  // true
0.5.hours > 40.minutes // false
```

#### Components

`Duration` can be decomposed to integer components in several ways:

```kotlin
inline fun <T> toComponents(action: (days: Long, hours: Int, minutes: Int, seconds: Int, nanoseconds: Int) -> T): T
inline fun <T> toComponents(action: (hours: Long, minutes: Int, seconds: Int, nanoseconds: Int) -> T): T
inline fun <T> toComponents(action: (minutes: Long, seconds: Int, nanoseconds: Int) -> T): T
inline fun <T> toComponents(action: (seconds: Long, nanoseconds: Int) -> T): T
```

The way duration is decomposed into components depends on the chosen overload of `toComponents`. 
The function or lambda [action] is called with the component values and its result of generic type `T` 
becomes the result of a `toComponents` call. 
Thus, `toComponents` can be used either to transform duration components into some other value, or to perform some 
side effect with the components:

```kotlin
// transform
println(duration.toComponents { hours, minutes, _, _ -> "${hours}h:${minutes}m" })

// side effect
val result = buildString {
    duration.toComponents { hours, minutes, _, _ -> 
        append("H").append(hours)
        append("M").append(minutes)
    }
}
```

When the highest order component doesn't fit in the `Long` data type range, it is clamped in that range.

#### String representation

Duration provides three ways it can be represented as string.

1. The default `toString()` represents a duration as a combination of hours, minutes, and seconds in
human-readable form, for example, `1h 0m 45.677s`.
    * the seconds component can have a fractional part
    * leading and traling zero components are omitted
    * a duration less than a second is represented in an appropriate sub-second unit: `1.23ms`, `455us`, `5ns`
    * a negative duration that has multiple components has them parenthesized and 
      prefixed with a single `-` sign: `-(1h 23m 45s)`
    * `ZERO` duration is represented as `0s`
    * an infinite duration is represented as `Infinity` without unit and with optional `-` sign.

2. The operation `toString(unit, decimals = number)` allows to represent a duration in a fixed unit with a fixed number 
of decimal places, with the exceptions:
    * values greater than 1e+14 in the specified unit are represented in scientific notation
    * the maximum `decimals` value supported is 12, specifying greater values leads to the same result as for `decimals = 12`
    * the infinite duration is represented as `Infinity` without unit and with optional `-` sign

3. The operation `toIsoString()` returns an ISO-8601 compatible string representation of a duration. Only `H`, `M` and `S` components are used.
   For example:
   
   - `2.days.toIsoString() == "PT48H"` 
   - `(-82850.4).seconds.toIsoString() == "-PT23H0M50.400S"`

### DurationUnit

An enum that lists the possible units in which duration can be expressed: `DAYS`, `HOURS`, `MINUTES`, `SECONDS`,
`MILLISECONDS`, `MICROSECONDS`, `NANOSECONDS`.

### TimeSource and TimeMark

`TimeSource` is an interface with a single method:

```kotlin
interface TimeSource {
    fun markNow(): TimeMark
}
```

In turn `TimeMark` provides the operation `elapsedNow` that returns a `Duration` of an interval elapsed at the current moment
since that mark was taken.
Additionally, it has two operators `+` and `-` allowing to get another `TimeMark` displaced from this one by the given duration.

```kotlin
interface TimeMark {
    fun elapsedNow(): Duration
    operator fun plus(duration: Duration): TimeMark = ...
    operator fun minus(duration: Duration): TimeMark = plus(-duration)

    fun hasPassedNow(): Boolean = elapsedNow() >= Duration.ZERO
    fun hasNotPassedNow(): Boolean = elapsedNow() < Duration.ZERO
}
```

`hasPassedNow` and `hasNotPassedNow` functions are useful for checking whether a deadline or expiration `TimeMark` has been reached:

```kotlin
val timeout: Duration = 5.minutes
val expirationMark = timeSource.markNow() + timeout
// later
if (expirationMark.hasPassedNow()) {
    // cached data are expired now
}
``` 

Instances of `TimeMark` are usually not serializable because it isn't possible to restore the captured time point upon deserialization
in a meaningful way.

### Comparable time marks

`ComparableTimeMark` interface extends the `TimeMark` interface with the functions to compare two time marks with each other
and to calculate time elapsed between them.

```kotlin
interface ComparableTimeMark : TimeMark, Comparable<ComparableTimeMark> {
    abstract override operator fun plus(duration: Duration): ComparableTimeMark
    open override operator fun minus(duration: Duration): ComparableTimeMark = plus(-duration)
    operator fun minus(other: ComparableTimeMark): Duration
    override operator fun compareTo(other: ComparableTimeMark): Int = (this - other) compareTo (Duration.ZERO)

    override fun equals(other: Any?): Boolean
    override fun hashCode(): Int
}
```

In order to represent a time source from which comparable time marks can be obtained, a specialized time source interface is introduced:

```kotlin
interface TimeSource {
    interface WithComparableMarks : TimeSource {
        override fun markNow(): ComparableTimeMark
    }
}
```

The comparison `timeMark1 < timeMark2` returns true if `timeMark1` represents the moment earlier than `timeMark2`.
If the time marks were obtained from different time sources, both comparison and the `minus` operator throw an `IllegalArgumentException`.

Comparable time marks also implement structural equality contract with the `equals` and `hashCode` functions consistent with
the `compareTo` operator, so that if `timeMark1 == timeMark2`, then `timeMark1 compareTo timeMark2 == 0`.
However, the equality operator doesn't throw an exception when time marks are from different time sources, it just returns `false`.

#### Alternatives considered

- Instead of introducing separate interfaces for comparable time marks and time sources returning them, introduce functions
  in the `TimeMark` base interface.
  - Comparing time marks is not always needed, but supporting it in the base interface would complicate all `TimeSource`
    implementations.
  
- Instead of introducing specialized time source for comparable time marks, parametrize the base `TimeSource` interface 
  with a generic time mark type, then `TimeSource<*>`, `TimeSource<TimeMark>`, and `TimeSource<ComparableTimeMark>` types
  can be used.

  ```kotlin
  interface TimeSource<out M : TimeMark> {
      fun markNow(): M
  }
  ```
  - We do not expect many different parametrizations of `TimeSource` interface, so dealing with pesky `<>` brackets in 
    common use cases would be tedious.
  - Parametrization only affects the return type of one function, so it doesn't bring much value compared to covariant
    override in a more specialized interface.

### Monotonic TimeSource

`TimeSource` has the nested object `Monotonic` that implements `TimeSource.WithComparableMarks` and provides the default source of monotonic 
time in the platform.

Different platforms provide different sources of monotonic time:

- Kotlin/JVM: [`System.nanoTime()`](https://docs.oracle.com/javase/8/docs/api/java/lang/System.html#nanoTime--)
- Kotlin/JS (Node.js): [`process.hrtime()`](https://nodejs.org/api/process.html#process_process_hrtime_time)
- Kotlin/JS (Browser): [`window.performance.now()`](https://www.w3.org/TR/hr-time/#sec-performance) or `Date.now()`
- Kotlin/Native: [`std::chrono::high_resolution_clock`](https://en.cppreference.com/w/cpp/chrono/high_resolution_clock), 
  falling back to [`std::chrono::steady_clock`](https://en.cppreference.com/w/cpp/chrono/steady_clock) 
  in case if the high resolution clock is not steady.

In Android environment a better way to measure time intervals is to use [`SystemClock.elapsedRealtimeNanos`](https://developer.android.com/reference/android/os/SystemClock.html#elapsedRealtimeNanos())
rather than `System.nanoTime()` because the former continues running when the device goes into a deep sleep. 
However, `elapsedRealtimeNanos` cannot be called directly from the standard library as it doesn't have a dependency
on the Android platform. This can be solved by providing an extension point in the standard library and providing 
a specialized implementation in another Android-targeted library. Should this API graduate to stable, such an implementation
could be provided in [Android KTX](https://developer.android.com/kotlin/ktx).

### Specialized value class-based TimeMark

A plain implementation of the `TimeMark` interface is usually a class that encapsulates a time reading value obtained from
a time source and a reference to the time source used to calculate the elapsed time.
Using such implementation of `TimeMark` to note the elapsed time involves allocation of an object for that class:

```kotlin

val mark = timeSource.markNow() // allocating a time mark instance
...
val elapsed = mark.elapsedNow()
```

The cost of this allocation may be negligible in some cases, but in others it can pose additional memory pressure 
if the measured block of code is executed often.

It can be noted that if the time source is a singleton object, such as `TimeSource.Monotonic`, its time mark can avoid 
storing a reference to it and can hold just a single property with a time reading value. 
This allows to make such time mark an inline value class:

```kotlin
public interface TimeSource {
    public object Monotonic : TimeSource.WithComparableMarks {

        override fun markNow(): ValueTimeMark = ...


        public value class ValueTimeMark internal constructor(internal val reading: ValueTimeMarkReading) : ComparableTimeMark {
            override fun elapsedNow(): Duration = ...
            override fun plus(duration: Duration): ValueTimeMark = ...
            override fun minus(duration: Duration): ValueTimeMark = ...
            override fun hasPassedNow(): Boolean = !elapsedNow().isNegative()
            override fun hasNotPassedNow(): Boolean = elapsedNow().isNegative()

            override fun minus(other: ValueTimeMark): Duration = ...
            operator fun compareTo(other: ValueTimeMark): Int = ...
        }
    }
}
```

Since the `Monotonic` object is specialized to return a value class, `ValueTimeMark`, and that value class overrides all operations 
of the `TimeMark` interface, specializing them to return another `ValueTimeMark` as necessary, the combination of
`markNow` + `elapsedNow` calls on the _monotonic_ time source now operates with the underlying value of type 
`ValueTimeMarkReading` and doesn't involve additional allocations. The latter is a platform specific type to represent
a time reading obtained from the time source. For example, on JVM it's just a type alias to `Long`.

Note that this optimization is possible only when it's known statically that the time source returns `ValueTimeMark`,
and thus working with `TimeSource.Monotonic` through its `TimeSource` interface still involves boxing of its time marks.

The function `measureTime` without a `TimeSource` also benefits from that, as it obtains a time mark from the default monotonic 
time source.

`ValueTimeMark` is a `ComparableTimeMark`, thus it allows comparing it with other `ValueTimeMark` values.

### AbstractLongTimeSource

This abstract class is provided to make it easy implementing own `TimeSource` 
from a source that returns the current timestamp as an integer number.

```kotlin
public abstract class AbstractLongTimeSource(protected val unit: DurationUnit) : TimeSource.WithComparableMarks {
    protected abstract fun read(): Long

    override fun markNow(): ComparableTimeMark = ...
}
```

To implement a time source all one needs to do is to extend one of these abstract classes, specifying the time unit in the 
super constructor call, and to provide an implementation of the abstract function `read` that returns the current reading 
of the time source.

### measureTime and measureTimedValue

The function `measureTime` is designated to replace both `measureTimeMillis` and `measureNanoTime`.
It takes a block function of type `() -> Unit`, executes it and returns the duration of elapsed time interval.

```kotlin
inline fun measureTime(block: () -> Unit): Duration
```

It has a contract stating that `block` is invoked exactly once, and due to it's being inline it is possible to call 
suspend functions in the lambda passed to `block`.

It uses `TimeSource.Monotonic` as a source of time by default. Another `TimeSource` implementation can be specified as a receiver 
of the following overload of `measureTime`:

```kotlin
inline fun TimeSource.measureTime(block: () -> Unit): Duration
```

`measureTimedValue` returns both the result of `block` execution and the elapsed time as a simple `TimedValue<T>` data class:

```kotlin
inline fun <T> measureTimedValue(block: () -> T): TimedValue<T>
inline fun <T> TimeSource.measureTimedValue(block: () -> T): TimedValue<T>

data class TimedValue<T>(val value: T, val duration: Duration)
```

## Dependencies

- The class `Duration` heavily depends on [Inline classes](https://github.com/kotlin/KEEP/blob/master/proposals/inline-classes.md) 
language feature for its efficient implementation.

- To measure elapsed time it is crucial to have a time source in a target platform. Shall a platform have no
monotonic time source, a wall clock can be used instead as a resort. If the platform has no time source 
at all, the API depending on it should fail with an exception.

## Placement and experimental status

It's proposed to provide this API in the common Kotlin Standard Library in a new package: `kotlin.time`.

To gather early adoption feedback, the new API was released in the experimental status in Kotlin 1.3.50.
The `Duration` class, `DurationUnit` enum, and related top-level functions and extensions became stable since Kotlin 1.6.0.
To mark the experimental status of this API we provide the annotation `@kotlin.time.ExperimentalTime`.

However, we can't deprecate the existing stable API that is going to be replaced with the new API, such as `measureTimeMillis`, 
before the new API goes stable.

## Future advancements

### Context-sensitive resolution of extensions in the `Duration.Companion`

Currently, the extensions for constructing a duration from a number in a fixed unit, e.g. `Int.seconds`, are placed inside 
the companion object of the `Duration` class. This makes it possible to use them either with an explicit import, like 
`import kotlin.time.Duration.Companion.seconds`, or when the `Duration` companion object presents as a receiver in scope:

```kotlin
val d = with(Duration) { 1.minutes + 40.seconds }
```

We presume that if the [context-sensitive resolution, KT-16768](https://youtrack.jetbrains.com/issue/KT-16768) was implemented, the compiler would resolve 
an expression in a context where `Duration` type is expected, e.g. `val d: Duration = 10.seconds`, 
by looking into the static scope of `Duration` first, namely in its companion object. 
There it would find a suitable extension method and use it even without an import (or maybe with an import of `kotlin.time.Duration` class itself).

Then we could also provide a "copy constructor" function `fun Duration(value: Duration) = value` that just sets an expected
type for its parameter allowing to construct durations without long imports as following:

```kotlin
val d = Duration(1.minutes + 40.seconds)
```

### `WallClock` extending `TimeSource` interface

Originally there was a proposal of having `(Wall)Clock` entity that extends `TimeSource` interface with an operation 
that returns the current instant of the world time (and date). However, we admitted that it would be more clear
to have that entity separate in order not to pollute its member scope with `TimeSource` members, and provide a wrapping
extension `.asTimeSource()` if necessary.

### Low-level timestamp providing functions

In addition to the object `TimeSource.Monotonic`, two lower level functions/properties can be provided to fetch the current time reading
of the system monotonic clock:

```kotlin
val systemTimeStamp: Long
   get() = ...
val systemTimeStampFrequency: Long
   get() = ...
```

They shall return the value of some system-wide ticking counter and its frequency in Hz respectively.
