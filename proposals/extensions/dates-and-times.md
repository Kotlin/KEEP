# Dates and Times

* **Type**: Standard Library API proposal
* **Author**: Joseph Ivie (Lightning Kite)
* **Status**: Submitted
* **Prototype**: In progress


## Summary

This is intended to be a simple, not all-encompassing implementation of date and time functionality that leaves room for extension.  Only the most common elements of managing dates and times are written here, and additions to this proposal should be in other proposals.

- `kotlin.time.Date`, representing a particular day, with no concept of time zones.  Internally an inline class holding the number of days since January 1st, 1970.
- `kotlin.time.Time`, representing a particular time during the day, with no concept of time zones.  Internally an inline class holding the number of milliseconds since midnight.
- `kotlin.time.DateTime`, the combination of a date and a time, again with no concept of time zones
- `kotlin.time.TimeStamp`, a standard millisecond UTC time stamp.  Internally an inline class using milliseconds since January 1st, 1970.
- `kotlin.time.Duration`, the difference between two time stamps in milliseconds


## Similar API review

* How the same/similar concept is implemented in other languages/frameworks?

### Java 8 - `java.time`

Java 8 uses the classes `LocalDate`, `LocalTime`, `LocalDateTime`, `Instant`, and `Duration`.

- The `Local` before `Date`, `Time`, and `DateTime` doesn't clearly communicate what they are.  `Local` is a misnomer; they are not local, they are timezone-agnostic.
- `Instant` may be clear, but `TimeStamp` is more searchable/expected.

Java 8 also has a large number of other classes, which deal with time zone functionality.

### Klock

Has lots of functionality already implemented for all platforms, and is written by a JetBrains employee.

Missing any way to represent times without dates and dates without times.

Uses `DateTime` and `DateTimeTz` to differentiate between time stamps in UTC and time stamps in a given time zone.  Has no timezone-ignorant types.


## Use cases

- A clear way to represent times in any project for all platforms

```kotlin
data class TrackedEvent(val name: String, val timeStamp: TimeStamp)
```

- Protocol for communicating between times between different libraries

```kotlin
//Common code for multiplatform UI
expect fun createTimePicker(time: ObservableProperty<Time>):UIElement
```


## Alternatives

- Require users to depend on libraries for date/time functionality
    - Causes fragmentation
- Make all common code stubs for underlying platforms' implementations
    - Removes all efficiencies gained from Kotlin's inline classes
    - Gains some interoperability with platform-specific functions


## Dependencies

Implementation is purely in Kotlin common code, and therefore the only dependencies would be on other things within the Kotlin Common Standard Library.


## Placement

KotlinX preferred for faster updates.


## Reference implementation

See the [time package in *lokalize*](https://github.com/lightningkite/lokalize/tree/master/src/commonMain/kotlin/com/lightningkite/lokalize/time)


## Unresolved questions

- Should inline classes or expect/actual be used?
    - Interoperability VS Efficiency
- Default timezone offset should come from a locale - perhaps another thing to add in the same library?


## Future advancements

- Time zone related functions
- Things for dealing with spans of time and repeating events
- [RFC5545](https://tools.ietf.org/html/rfc5545)


## API Detail

```kotlin
inline class Time(val millisecondsSinceMidnight: Int) : Comparable<Time> {

    constructor(
        hours:Int,
        minutes:Int,
        seconds:Int = 0,
        milliseconds:Int = 0
    )

    companion object {
        fun iso8601(string: String): Time
    }

    val hours:Int
    val minutes:Int
    val seconds:Int
    val milliseconds:Int

    operator fun plus(amount: Duration): Time
    operator fun minus(amount: Duration): Time
    operator fun minus(other: Time): Duration

    fun iso8601(): String
}


inline class Date(val daysSinceEpoch: Int) : Comparable<Date> {

    constructor(
            year: Int,
            month: Month,
            day: Int
    )

    companion object {
        fun iso8601(string: String): Date
    }
    
    val dayOfWeek: DayOfWeek
    fun toNextDayOfWeek(value: DayOfWeek): Date
    fun toDayInSameWeek(value: DayOfWeek): Date
    val dayOfYear: Int
    val dayOfMonth: Int
    val month: Month
    val year: Int

    fun iso8601(): String

    operator fun minus(other: Date): Duration
}


data class DateTime(val date: Date, val time: Time) : Comparable<DateTime> {
    companion object {
        fun iso8601(string: String): DateTime
    }
    fun toTimeStamp(timeZoneOffset: Duration = default) 
    fun iso8601(timeZoneOffset: Duration = default):String

    operator fun plus(amount: Duration): DateTime
    operator fun minus(amount: Duration): DateTime
    operator fun minus(other: DateTime): Duration
}


inline class TimeStamp(val millisecondsSinceEpoch: Long) : Comparable<TimeStamp> {

    constructor(
        date: Date,
        time: Time,
        offset: Duration = default
    )

    companion object {
        fun iso8601(string: String): TimeStamp
        fun now(): TimeStamp
    }

    fun iso8601(): String

    operator fun plus(duration: Duration): TimeStamp
    operator fun minus(duration: Duration): TimeStamp
    operator fun minus(other: TimeStamp): Duration

    fun date(timeZoneOffset: Duration = default): Date
    fun time(timeZoneOffset: Duration = default): Time
    fun dateTime(timeZoneOffset: Duration = default): DateTime
}


enum class DayOfWeek {
    Sunday,
    Monday,
    Tuesday,
    Wednesday,
    Thursday,
    Friday,
    Saturday
}


enum class Month(val days: Int, val daysLeap: Int = days) {
    January(31),
    February(28, 29),
    March(31),
    April(30),
    May(31),
    June(30),
    July(31),
    August(31),
    September(30),
    October(31),
    November(30),
    December(31);
}


```
