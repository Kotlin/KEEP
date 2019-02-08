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
- `Instant` may be clear, but maybe `TimeStamp` is more searchable/expected.

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


## Future advancements

- Time zone related functions
- Things for dealing with spans of time 


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

    operator fun plus(amount: Duration)
    operator fun minus(amount: Duration)
    operator fun minus(other: Time)

    fun iso8601(): String
}


inline class Date(val daysSinceEpoch: Int) : Comparable<Date> {

    constructor(
            year: Year,
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
    val dayOfMonth: Int get() = yearAndDayInYear.dayOfMonth
    val month: Month get() = yearAndDayInYear.month
    val year: Year get() = yearAndDayInYear.year

    fun iso8601(): String

    operator fun minus(other: Date): Duration
}

data class DateTime(val date: Date, val time: Time) : Comparable<DateTime> {
    companion object {
        fun iso8601(string: String): DateTime
    }
    fun toTimeStamp(offset: Duration = default) 
    fun iso8601(offset: Duration = default):String
    operator fun minus(other: DateTime)
}

inline class TimeStamp(val millisecondsSinceEpoch: Long) : Comparable<TimeStamp> {

    companion object {
        fun iso8601(string: String): TimeStamp
    }

    fun iso8601(): String

    operator fun plus(duration: Duration): TimeStamp
    operator fun minus(duration: Duration): TimeStamp
    operator fun minus(other: TimeStamp): Duration
}

fun TimeStamp.date(offset: Duration = default): Date

fun TimeStamp.time(offset: Duration = default): Time =
    Time(((millisecondsSinceEpoch - offset.milliseconds) % TimeConstants.MS_PER_DAY).toInt())

fun TimeStamp.dateTime(offset: Duration = Duration(DefaultLocale.getTimeOffsetMilliseconds())): DateTime = DateTime(date(offset), time(offset))


fun TimeStamp(
    date: Date,
    time: Time,
    offset: Duration = Duration(DefaultLocale.getTimeOffsetMilliseconds())
) = TimeStamp(
    date.daysSinceEpoch * 24L * 60 * 60 * 1000 +
            time.millisecondsSinceMidnight +
            offset.milliseconds
)
```
