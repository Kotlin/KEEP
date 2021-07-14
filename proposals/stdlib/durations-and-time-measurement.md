# Duration and time measurement API

* **Type**: Standard Library API proposal
* **Author**: Ilya Gorbunov
* **Status**: Experimental in Kotlin 1.3.70
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
Also the elapsed time can be noted not only once, as it is with `measureTime`, but multiple times.

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
inline class Duration internal constructor(internal val value: Long) : Comparable<Duration>
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
a unit of duration. If the unit is known in advance and constant, the functions of `Duration` companion object can 
be used to construct a duration, e.g. `Duration.minutes(1.5)`, `Durations.seconds(30)`, `Duration.milliseconds(500)`. Otherwise, the extension function 
`numericValue.toDuration(unit)` can be used.

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
Duration.days(1) == Duration.hours(24)
Duration.minutes(1.5) == Duration.seconds(90)
```

`compareTo` operation is implemented in a similar way:

```kotlin
Duration.hours(1) < Duration.minutes(65)  // true
Duration.hours(0.5) > Duration.minutes(40) // false
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
   
   - `Duration.days(2).toIsoString() == "PT48H"` 
   - `Duration.seconds(-82850.4).toIsoString() == "-PT23H0M50.400S"`

### DurationUnit

An enum that lists the possible units in which duration can be expressed: `DAYS`, `HOURS`, `MINUTES`, `SECONDS`,
`MILLISECONDS`, `MICROSECONDS`, `NANOSECONDS`.

On JVM it is a typealias to `java.util.concurrent.TimeUnit`.

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
abstract class TimeMark {
    abstract fun elapsedNow(): Duration
    open operator fun plus(duration: Duration): TimeMark = ...
    open operator fun minus(duration: Duration): TimeMark = plus(-duration)

    fun hasPassedNow(): Boolean = elapsedNow() >= Duration.ZERO
    fun hasNotPassedNow(): Boolean = elapsedNow() < Duration.ZERO
}
```

`hasPassedNow` and `hasNotPassedNow` functions are useful for checking whether a deadline or expiration `TimeMark` has been reached:

```kotlin
val timeout: Duration = Duration.minutes(5)
val expirationMark = timeSource.markNow() + timeout
// later
if (expirationMark.hasPassedNow()) {
    // cached data are expired now
}
``` 

A `TimeMark` is not serializable because it isn't possible to restore the captured time point upon deserialization
in a meaningful way.

### Monotonic TimeSource

`TimeSource` has the nested object `Monotonic` that implements `TimeSource` and provides the default source of monotonic 
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

### AbstractLongTimeSource/AbstractDoubleTimeSource

These two abstract classes are provided to make it easy implementing own `TimeSource` 
from a source that returns the current timestamp as a number.

```kotlin
public abstract class AbstractLongTimeSource(protected val unit: DurationUnit) : TimeSource {
    protected abstract fun read(): Long

    override fun markNow(): TimeMark = ...
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

To gather early adoption feedback, the new API was released in the experimental status in the coming Kotlin 1.3.x release. 
To mark the experimental status of this API we provide the annotation `@kotlin.time.ExperimentalTime`.

However, we can't deprecate the existing stable API that is going to be replaced with the new API, such as `measureTimeMillis`, 
before the new API goes stable.

The experimental status of `Duration` and consequently almost all API in `kotlin.time` cannot be lifted without graduating
`Inline classes` feature to stable first.


## Future advancements

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
