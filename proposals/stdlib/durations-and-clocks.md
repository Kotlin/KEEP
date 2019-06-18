# Duration and time measurement API

* **Type**: Standard Library API proposal
* **Author**: Ilya Gorbunov
* **Status**: Experimental in Kotlin 1.3.50
* **Prototype**: Implemented
* **Discussion**: [KEEP-190](https://github.com/Kotlin/KEEP/issues/190)


## Summary

The goal of this proposal is to introduce the following API into the common Kotlin Standard Library:
- the class `Duration` representing the duration of time interval between two instants of time;
- the enum class `DurationUnit` that lists the possible units in which a duration can be expressed;
- the functions `measureTime` and `measureTimedValue` allowing to measure execution time of a code block;
- the interface `Clock` abstracting the source of time and allowing to notch a mark on
the clock and then read the amount of time elapsed from that mark.

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
Also the elapsed time can be noted not only once, as it is with `measureTime`, but multiple times.

The interface `Clock` is a basic building block both for implementing `measureTime` and for covering the case above.
It allows to obtain a `ClockMark` that captures the current instant of the clock. That `ClockMark` can be queried later 
for the amount of time elapsed since that instant, potentially multiple times.

#### Noting time remaining to a particular point in future

Timeout dealing cases usually involve taking a `ClockMark` at some point and then verifying whether the elapsed time 
has exceeded the given timeout value. This requires operating two entities: the `ClockMark` and the timeout `Duration`.

Instead the `ClockMark` can be displaced by the given `Duration` value to get another `ClockMark` representing timeout 
expiration point in future. The function `elapsed` invoked on the latter `ClockMark` will return negative values while the
timeout is not expired, and positive values if it is. This way timeout can be represented by a single `ClockMark` instead 
of `ClockMark` and `Duration`.


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

## API details

### Duration

It is proposed to represent duration values with an inline class `Duration` that wraps a primitive value. 

```kotlin
inline class Duration internal constructor(internal val value: Double) : Comparable<Duration>
```

The property `value` stores a `Double` number denoting the number of nanoseconds in this time interval. 

Such internal representation is a good compromise between having high precision for very small durations and good enough 
precision for very big durations. For example, nanoseconds can be stored precisely for durations up to 104 days, and seconds
for up to 146 years. Though that is not a goal, `Duration` type can store both the Planck time (5.39×10<sup>-44</sup> s) and
the age of the Universe (13.8×10<sup>9</sup> years at the time of writing).

The `Duration` arithmetic operators working with `Double` underlying values have more efficient implementation in Kotlin/JS, where
`Double` values are supported natively and `Long` values are emulated with a class implementation.

A `Duration` value can be infinite, which is useful for representing infinite timeout values.

A `Duration` value can be negative.

#### Construction

The primary constructor of `Duration` is internal. 
A `Duration` value can be constructed from a numeric value (`Int`, `Long` or `Double`) and 
a unit of duration. If the unit is known in advance and constant, the extension properties provide the most expressive 
way to construct a duration, e.g. `1.5.minutes`, `30.seconds`, `500.milliseconds`. Otherwise the extension function 
`numericValue.toDuration(unit)` can be used.

#### Conversions

A `Duration` value can be retrieved back as a number with:
- the properties `inNanoseconds`, `inSeconds`, `inHours`, etc, that return a double duration value expressed in the specified fixed unit;
- the functions `toDouble(unit)`, `toLong(unit)`, `toInt(unit)` that return a number of the particular `Double`, `Long` or `Int` type
expressed in the unit specified with the parameter `unit`;
- two shortcut functions returning a number of milliseconds or nanoseconds as `Long` values: `toLongMilliseconds()`, `toLongNanoseconds()`.

#### Operators

`Duration` values support the following operations:

- addition and subtraction: `Duration +- Duration = Duration`
- multiplication by a scalar: `Duration * number = Duration`
- division by a scalar: `Duration / number = Duration`
- division by a duration: `Duration / Duration = Double`
- negation: `-Duration`
- absolute value: `Duration.absoluteValue`
- `isNegative()`, `isFinite()`, `isInfinite()` functions

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
inline fun <T> toComponents(action: (days: Int, hours: Int, minutes: Int, seconds: Int, nanoseconds: Int) -> T): T
inline fun <T> toComponents(action: (hours: Int, minutes: Int, seconds: Int, nanoseconds: Int) -> T): T
inline fun <T> toComponents(action: (minutes: Int, seconds: Int, nanoseconds: Int) -> T): T
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


In case if the highest order component doesn't fit in the `Int` or `Long` data type, it is clamped to the range of that type.

#### String representation

Duration provides three ways it can be represented as string.

1. The default `toString` implementation finds the unit in which the value is represented in the most readable form in
accordance with the following principles:
    * select the unit so that the result magnitude fits into the range `1..<1000` or is closest to this range
    * round fractions to 3 significant digits: `750`, `75.0`, `7.50`
    * prefer a larger number in more granular unit than a smaller number in less granular one, 
      if the ratio between their scales is not a power of 10. For example, prefer `105m` to `1.75h`, or `40h` to `1.67d`
    * very big durations are represented in scientific notation in days: `3.65e+7d`
    * very small durations are represented in scientific notation in seconds: `5.40e-44s`
    * `ZERO` duration is represented as `0s`
    * the infinite duration is represented as `Infinity` without unit and with optional `-` sign

2. The operation `toString(unit, decimals = number)` allows to represent a duration in a fixed unit with a fixed number 
of decimal places, with the exceptions:
    * values greater than 1e+14 in the specified unit are represented in scientific notation
    * the maximum `decimals` value supported is 12, specifying greater values leads to the same result as for `decimals = 12`
    * the infinite duration is represented as `Infinity` without unit and with optional `-` sign

3. The operation `toIsoString()` returns an ISO-8601 based string representation of a duration. Only `H`, `M` and `S` components are used.
   For example: 
   
   - `2.days.toIsoString() == "PT48H"` 
   - `(-82850.4).seconds.toIsoString() == "-PT23H0M50.400S"`

### DurationUnit

An enum that lists the possible units in which duration can be expressed: `DAYS`, `HOURS`, `MINUTES`, `SECONDS`,
`MILLISECONDS`, `MICROSECONDS`, `NANOSECONDS`.

On JVM it is a typealias to `java.util.concurrent.TimeUnit`.

### Clock and ClockMark

`Clock` is an interface with a single method:

```kotlin
interface Clock {
    fun mark(): ClockMark
}
```

In turn `ClockMark` provides the operation `elapsed` that returns a `Duration` of an interval elapsed since that mark.
Additionally it has two operators `+` and `-` allowing to get another `ClockMark` displaced from this one by the given duration.

```kotlin
abstract class ClockMark {
    abstract fun elapsed(): Duration
    open operator fun plus(duration: Duration): ClockMark = ...
    open operator fun minus(duration: Duration): ClockMark = plus(-duration)
}
```

A `ClockMark` is not serializable because it isn't possible to restore the captured time point upon deserialization
in a meaningful way.

### MonoClock

`MonoClock` is an object implementing `Clock` and providing the default source of monotonic time in a platform.

Different platforms provide different sources of monotonic time:

- Kotlin/JVM: [`System.nanoTime()`](https://docs.oracle.com/javase/8/docs/api/java/lang/System.html#nanoTime--)
- Kotlin/JS (Node.js): [`process.hrtime()`](https://nodejs.org/api/process.html#process_process_hrtime_time)
- Kotlin/JS (Browser): [`window.performance.now()`](https://www.w3.org/TR/hr-time/#sec-performance) or `Date.now()`
- Kotlin/Native: [`std::chrono::high_resolution_clock`](https://en.cppreference.com/w/cpp/chrono/high_resolution_clock)

In Android environment a better way to measure time intervals is to use [`SystemClock.elapsedRealtimeNanos`](https://developer.android.com/reference/android/os/SystemClock.html#elapsedRealtimeNanos())
rather than `System.nanoTime()` because the former continues running when the device goes into a deep sleep. 
However, `elapsedRealtimeNanos` cannot be called directly from the standard library as it doesn't have a dependency
on the Android platform. This can be solved by providing an extension point in the standard library and providing 
a specialized implementation in another Android-targeted library. Should this API graduate to stable, such an implementation
could be provided in [Android KTX](https://developer.android.com/kotlin/ktx).

### AbstractLongClock/AbstractDoubleClock

These are two abstract classes that are provided to make it easy implementing own `Clock` 
having a time source that returns the current timestamp as a number.

```kotlin
public abstract class AbstractLongClock(protected val unit: DurationUnit) : Clock {
    protected abstract fun read(): Long

    override fun mark(): ClockMark = ...
}
```

To implement a clock all one needs to do is to extend one of these abstract classes, specifying the time unit in the 
super constructor call, and to provide an implementation of the abstract function `read` that returns the current reading of the clock.

### measureTime and measureTimedValue

The function `measureTime` is designated to replace both `measureTimeMillis` and `measureNanoTime`.
It takes a block function of type `() -> Unit`, executes it and returns the duration of elapsed time interval.

```kotlin
inline fun measureTime(block: () -> Unit): Duration
```

It has a contract stating that `block` is invoked exactly once, and due to it's being inline it is possible to call 
suspend functions in the lambda passed to `block`.

It uses `MonoClock` as a source of time by default. Another `Clock` implementation can be specified as a receiver 
of the following overload of `measureTime`:

```kotlin
inline fun Clock.measureTime(block: () -> Unit): Duration
```

`measureTimedValue` returns both the result of `block` execution and the elapsed time as a simple `TimedValue<T>` data class:

```kotlin
inline fun <T> measureTimedValue(block: () -> T): TimedValue<T>
inline fun <T> Clock.measureTimedValue(block: () -> T): TimedValue<T>

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

To gather early adoption feedback, the new API can be released in the experimental status in the coming Kotlin 1.3.x release. 
To mark the experimental status of this API we provide the annotation `@kotlin.time.ExperimentalTime`.

However, we can't deprecate the existing stable API, such as `measureTimeMillis`, that is going to be replaced with the new API 
before the new API goes stable.

The experimental status of `Duration` and consequently almost all API in `kotlin.time` cannot be lifted without graduating
`Inline classes` feature to stable first.

## Unresolved questions

* Are the shortcut functions `toLongMilliseconds` and `toLongNanoseconds` short enough for their use cases?
* Do we need symmetrical multiplication operator overloads `number * Duration = Duration`?

## Future advancements

### `WallClock` extending `Clock` interface

`WallClock` interface can extend `Clock` interface with an operation that returns the current instant of the world time (and date).
Such instant values do not lose their meaning after restarting the program or the entire system, and their clock marks can be persisted.
On the other hand such clock is subject to automatic and manual time adjustments, so it is not monotonic, 
therefore measuring elapsed time with such clock can yield a negative duration.

The `ClockMark` returned by a `WallClock` can be potentially serialized, though
it is not clear how to restore the clock reference upon deserialization.

### Low-level timestamp providing functions

In addition to the object `MonoClock`, two lower level functions/properties can be provided to fetch the current time reading
of the system monotonic clock:

```kotlin
val systemTimeStamp: Long
   get() = ...
val systemTimeStampFrequency: Long
  get() = ...
```

They shall return the value of some system-wide ticking counter and its frequency in Hz respectively.

### Platform dependent internal representation of Duration

In some Kotlin/Native target platforms `Long` type can have more efficient implementation than `Double`. 
`Duration` could have another actual implementation for these platforms, having the internal value stored as `Long`.

However, it's unclear how to satisfy the expectations of duration precision at the nanosecond scale then.