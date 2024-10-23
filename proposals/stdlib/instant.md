Instant and Clock.
=================

* **Type**: Standard Library API proposal
* **Author**: Dmitry Khalanskiy
* **Contributors**: Ilya Gorbunov, Roman Elizarov, Vsevolod Tolstopyatov
* **Status**: pending
* **Prototype**: available in `kotlinx-datetime`

Summary
-------

This proposal aims to introduce facilities for obtaining the current system time
into the Kotlin Standard Library.

Additionally, it defines some interactions with `kotlin.time.Duration`, an
existing API for representing some number of seconds.

The API described here is available in `kotlinx-datetime` 0.6.0 in the form of
the `Instant` class and the `Clock` interface.
The goal of this proposal is to move this widely applicable functionality into
the Standard Library.

Overview
--------

### `Instant`

`Instant` denotes a moment in time.
Examples:

* The moment clocks showed 2024-08-05, 14:34:31 in New York.
* The moment Kotlin 2.0 was made available on Maven Central.
* The moment corresponding to the Unix timestamp `1722863508.231529`.
* The moment corresponding to the ISO 8601 string `2024-08-05T14:34:31+02:00`.

(Technical detail, irrelevant for the higher-level picture:
as described in the "Leap second handling" section,
ISO 8601 strings, as well as Unix timestamps and wall clocks do not
always correspond to a single specific moment).

Non-examples:

* "2024-08-05, 14:34:31" is not an `Instant`,
  because people in Tokyo and Morocco would not observe this on their clocks
  simultaneously.
* "2024-08-05 UTC" is not an `Instant`, as it's too coarse.
  We define `Instant` values to have a precision of several seconds.

### `Clock`

A `Clock` implementation is a way to obtain the current `Instant`.
The `Clock.System` default implementation, available on all platforms, queries
the system clock (the one also used to display the current date and time in the
corner of your screen).
`Clock` does not provide the current time zone, and so it is not enough on its
own for displaying the current time to the user: the Unix timestamp as I'm
writing this is 1724156421, and my clocks (in `Europe/Berlin`) show 14:20,
but in Massachusetts, the clocks show 08:20 for the exact same Unix timestamp.

Motivation and use cases
------------------------

### Why Instant and Clock are useful

#### Exchanging information about moments in time

`Instant` is widespread in web APIs.
The typical interaction for exchanging information about when some event took
place is:

* The server sends the `Instant` representing the moment in time.
* The client queries the current system time zone.
* The client calculates which date and time it is in the current system time
  zone at the given `Instant`.
* The client displays the resulting date and time.

**Example**: "the user was last online at 15:32" is calculated like this.

For the backend, `Instant` is enough to support this use case.
For the frontend, additionally, time zone information is needed.

#### Logging events

For logging, typically, the `Instant` is included and not the local datetime.
This makes it possible to merge logs across several computers, and if their
clocks are not too out of sync, a consistent view of event ordering can be
restored, regardless of which time zones these computers were configured to use.

**Example**: log entries in Linux store the number of milliseconds since the
start of the Unix epoch, and these can be represented as `Instant` values:

```sh
$ journalctl -o json | jq .__REALTIME_TIMESTAMP | head -n1
"1715603477525420"
```

**Example**: filesystems typically store information about when files were
created, modified, and/or accessed.
This information is stored as `Instant` values.

#### Determining time intervals between events

**Example**: "This user was last online three minutes ago".

**Example**: listing all files that were modified in the last 24 hours.

These calculations require knowing the current moment in time (accessed via
`Clock.System`) and the moment in time when some event happened.
They do not require knowing the current system time zone: how long ago something
happened does not depend on what exactly clocks said, it only depends on the
actual passage of time. In fact, it's _incorrect_ to take the current time zone
into account because of situations like the following:

* A user logs in at 09:14.
* At 10:00, because of DST transitions, clocks are shifted an hour back.
* Then, at 09:01, it's incorrect for the text to say that the user will
  be online in 13 minutes.
  Instead, it should say that the user was online 47 minutes ago.

### Why Instant and Clock are a good fit for the Standard Library

#### Conceptually

`kotlinx-datetime` currently provides two disjoint sets of functionality:

* Working with time without taking calendars and time zones into account:
  determining the current `Instant`, finding the number of seconds between
  `Instant` values, finding an `Instant` that's a given number of seconds
  later than the given one...
* Working with calendars and time zones:
  determining the `Instant` that is a day or a month later than the given one,
  obtaining the date and time of an `Instant` in the user's time zone...

The calendar- and timezone-agnostic operations involve
`kotlinx.datetime.Instant`, `kotlinx.datetime.Clock`,
and `kotlin.time.Duration`, whereas
calendar- and timezone-aware operations are supported by a multitude of
entities:

* `kotlinx.datetime.LocalDateTime` is the representation of an unspecified
  `Instant` in some unspecified time zone (example: "2024-08-05 16:01").
* `kotlinx.datetime.LocalDate` and `kotlinx.datetime.LocalTime` are the date and
  the time-of-day parts of a `LocalDateTime`, respectively.
* `kotlinx.datetime.DateTimeUnit` describes a time measurement unit:
  "2 days", "a month", "15 minutes"...
* `kotlinx.datetime.DateTimePeriod` defines a compound period of time:
  "a year and two days", "a week and two minutes".
  `kotlinx.datetime.DatePeriod` is a subclass of `DateTimePeriod` that
  only includes date-based components.
  There is no `kotlinx.datetime.TimePeriod` because its role is completely
  fulfilled by `kotlin.time.Duration`.
* `kotlinx.datetime.TimeZone` establishes the correspondence between
  `Instant` and `LocalDateTime` values: what the clocks in the given time zone
  display at any given `Instant`.
* `kotlinx.datetime.UtcOffset` describes the difference between `LocalDateTime`
  values observed in the given time zone and the UTC time zone.

All entities aside from `Instant` and `Clock` are *date*-aware, whereas
`Instant`, `Clock`, and `kotlin.time.Duration` are only *time*-aware, which
makes them a better fit for the `time` package and not `datetime`.

#### Pragmatically

Excessive reliance on `LocalDateTime` is a significant source of bugs:
logging events as clock readings in the current system timezone, for example,
will lead to an inconsistent view if several sources with different time zones
are used.

`LocalDateTime` values are also subject to DST transitions: events when clocks
are shifted an hour forward or backward.
If clocks are shifted backward, we observe an hour's worth of events happening
with an earlier `LocalDateTime` than what we already observed,
whereas `Instant` values are much more reliably increasing
(though this is also not strictly guaranteed, as clocks can be adjusted to
compensate for drifting).

This makes date- and timezone-aware APIs delicate and useful either for
specialized calculations or for displaying time information to the end user.

On the other hand, the need for `Instant` is ubiquitous and universal.
`Instant` is a class with straightforward semantics.
Of the use cases listed for `Instant`, almost none require timezone awareness,
so the `Instant` API does not benefit much from being in the same library as
the other `kotlinx.datetime` classes.

**Example**: the Okio library only uses `kotlinx.datetime` for `Instant` and
`Clock`.

**Example**: `Instant` is referenced more often than any of the `Local*`
classes: <https://grep.app/search?q=kotlinx.datetime.Instant> has about 800,
and <https://grep.app/search?q=kotlinx.datetime.Local> about 550 hits as of
writing.

Proposal
--------

The proposal is to remove the `Instant` class and the `Clock` interface
from the `kotlinx-datetime` library and add them to the Standard Library's
`kotlin.time` package.

The specifics of what gets added is given below.

### `Instant` API

```kotlin
/**
 * A moment in time.
 *
 * A point in time must be uniquely identified in a way that is independent of a time zone.
 * For example, `1970-01-01, 00:00:00` does not represent a moment in time since this would happen at different times
 * in different time zones: someone in Tokyo would think it is already `1970-01-01` several hours earlier than someone in
 * Berlin would. To represent such entities, use the `kotlin-time time.
 * In contrast, "the moment the clocks in London first showed 00:00 on Jan 1, 2000" is a specific moment
 * in time, as is "1970-01-01, 00:00:00 UTC+0", so it can be represented as an [Instant].
 *
 * `Instant` uses the UTC-SLS (smeared leap second) time scale. This time scale doesn't contain instants
 * corresponding to leap seconds, but instead "smears" positive and negative leap seconds among the last 1000 seconds
 * of the day when a leap second happens.
 *
 * ### Obtaining the current moment
 *
 * The [Clock] interface is the primary way to obtain the current moment:
 *
 * ```
 * val clock: Clock = Clock.System
 * val instant = clock.now()
 * ```
 *
 * The [Clock.System] implementation uses the platform-specific system clock to obtain the current moment.
 * Note that this clock is not guaranteed to be monotonic, and the user or the system may adjust it at any time,
 * so it should not be used for measuring time intervals.
 * For that, consider using [TimeSource.Monotonic] and [TimeMark] instead of [Clock.System] and [Instant].
 *
 * ### Arithmetic operations
 *
 * The [plus] and [minus] operators can be used to add [Duration]s to and subtract them from an [Instant]:
 *
 * ```
 * Clock.System.now() + 5.seconds // 5 seconds from now
 * ```
 *
 * Also, there is a [minus] operator that returns the [Duration] representing the difference between two instants:
 *
 * ```
 * val kotlinRelease = Instant.parse("2016-02-15T02:00T12:00:00+03:00")
 * val kotlinStableDuration = Clock.System.now() - kotlinRelease
 * ```
 *
 * ### Platform specifics
 *
 * On the JVM, there are `Instant.toJavaInstant()` and `java.time.Instant.toKotlinInstant()`
 * extension functions to convert between `kotlin.time` and `java.time` objects used for the same purpose.
 * Similarly, on the Darwin platforms, there are `Instant.toNSDate()` and `NSDate.toKotlinInstant()`
 * extension functions.
 *
 * ### Construction, serialization, and deserialization
 *
 * [fromEpochSeconds] can be used to construct an instant from the number of seconds since
 * `1970-01-01T00:00:00Z` (the Unix epoch).
 * [epochSeconds] and [nanosecondsOfSecond] can be used to obtain the number of seconds and nanoseconds since the epoch.
 *
 * ```
 * val instant = Instant.fromEpochSeconds(1709898983, 123456789)
 * instant.epochSeconds // 1709898983
 * instant.nanosecondsOfSecond // 123456789
 * ```
 *
 * [fromEpochMilliseconds] allows constructing an instant from the number of milliseconds since the epoch.
 * [toEpochMilliseconds] can be used to obtain the number of milliseconds since the epoch.
 * Note that [Instant] supports nanosecond precision, so converting to milliseconds is a lossy operation.
 *
 * ```
 * val instant1 = Instant.fromEpochSeconds(1709898983, 123456789)
 * instant1.nanosecondsOfSecond // 123456789
 * val milliseconds = instant1.toEpochMilliseconds() // 1709898983123
 * val instant2 = Instant.fromEpochMilliseconds(milliseconds)
 * instant2.nanosecondsOfSecond // 123000000
 * ```
 *
 * [parse] and [toString] methods can be used to obtain an [Instant] from and convert it to a string in the
 * ISO 8601 extended format.
 *
 * ```
 * val instant = Instant.parse("2023-01-02T22:35:01+01:00")
 * instant.toString() // 2023-01-02T21:35:01Z
 * ```
 */
public expect class Instant : Comparable<Instant> {

    /**
     * The number of seconds from the epoch instant `1970-01-01T00:00:00Z` rounded down to a [Long] number.
     *
     * The difference between the rounded number of seconds and the actual number of seconds
     * is returned by [nanosecondsOfSecond] property expressed in nanoseconds.
     *
     * Note that this number doesn't include leap seconds added or removed since the epoch.
     *
     * @see fromEpochSeconds
     */
    public val epochSeconds: Long

    /**
     * The number of nanoseconds by which this instant is later than [epochSeconds] from the epoch instant.
     *
     * The value is always non-negative and lies in the range `0..999_999_999`.
     *
     * @see fromEpochSeconds
     */
    public val nanosecondsOfSecond: Int

    /**
     * Returns the number of milliseconds from the epoch instant `1970-01-01T00:00:00Z`.
     *
     * Any fractional part of a millisecond is rounded toward zero to the whole number of milliseconds.
     *
     * If the result does not fit in [Long], returns [Long.MAX_VALUE] for a positive result or [Long.MIN_VALUE] for a negative result.
     *
     * @see fromEpochMilliseconds
     */
    public fun toEpochMilliseconds(): Long

    /**
     * Returns an instant that is the result of adding the specified [duration] to this instant.
     *
     * If the [duration] is positive, the returned instant is later than this instant.
     * If the [duration] is negative, the returned instant is earlier than this instant.
     *
     * The return value is clamped to the boundaries of [Instant] if the result exceeds them.
     *
     * **Pitfall**: [Duration.Companion.days] are multiples of 24 hours, but in some time zones,
     * some days can be shorter or longer because clocks are shifted.
     * Consider using `kotlinx-datetime` for arithmetic operations that take time zone transitions into account.
     */
    public operator fun plus(duration: Duration): Instant

    /**
     * Returns an instant that is the result of subtracting the specified [duration] from this instant.
     *
     * If the [duration] is positive, the returned instant is earlier than this instant.
     * If the [duration] is negative, the returned instant is later than this instant.
     *
     * The return value is clamped to the boundaries of [Instant] if the result exceeds them.
     *
     * **Pitfall**: [Duration.Companion.days] are multiples of 24 hours, but in some time zones,
     * some days can be shorter or longer because clocks are shifted.
     * Consider using `kotlinx-datetime` for arithmetic operations that take time zone transitions into account.
     */
    public operator fun minus(duration: Duration): Instant

    /**
     * Returns the [Duration] between two instants: [other] and `this`.
     *
     * The duration returned is positive if this instant is later than the other,
     * and negative if this instant is earlier than the other.
     *
     * The result is never clamped, but note that for instants that are far apart,
     * the value returned may represent the duration between them inexactly due to the loss of precision.
     *
     * Note that sources of [Instant] values (in particular, [Clock]) are not guaranteed to be in sync with each other
     * or even monotonic, so the result of this operation may be negative even if the other instant was observed later
     * than this one, or vice versa.
     * For measuring time intervals, consider using [TimeSource.Monotonic].
     */
    public operator fun minus(other: Instant): Duration

    /**
     * Compares `this` instant with the [other] instant.
     * Returns zero if this instant represents the same moment as the other (meaning they are equal to one another),
     * a negative number if this instant is earlier than the other,
     * and a positive number if this instant is later than the other.
     */
    public override operator fun compareTo(other: Instant): Int

    /**
     * Converts this instant to the ISO 8601 string representation, for example, `2023-01-02T23:40:57.120Z`.
     *
     * The representation uses the UTC-SLS time scale instead of UTC.
     * In practice, this means that leap second handling will not be readjusted to the UTC.
     * Leap seconds will not be added or skipped, so it is impossible to acquire a string
     * where the component for seconds is 60, and for any day, it's possible to observe 23:59:59.
     *
     * @see parse
     */
    public override fun toString(): String

    public companion object {
        @Deprecated("Use Clock.System.now() instead", ReplaceWith("Clock.System.now()", "kotlin.time.Clock"), level = DeprecationLevel.ERROR)
        public fun now(): Instant

        /**
         * Returns an [Instant] that is [epochMilliseconds] number of milliseconds from the epoch instant `1970-01-01T00:00:00Z`.
         *
         * Note that [Instant] also supports nanosecond precision via [fromEpochSeconds].
         *
         * @see Instant.toEpochMilliseconds
         */
        public fun fromEpochMilliseconds(epochMilliseconds: Long): Instant

        /**
         * Returns an [Instant] that is the [epochSeconds] number of seconds from the epoch instant `1970-01-01T00:00:00Z`
         * and the [nanosecondAdjustment] number of nanoseconds from the whole second.
         *
         * The return value is clamped to the boundaries of [Instant] if the result exceeds them.
         * In any case, it is guaranteed that instants between [DISTANT_PAST] and [DISTANT_FUTURE] can be represented.
         *
         * [fromEpochMilliseconds] is a similar function for when input data only has millisecond precision.
         *
         * @see Instant.epochSeconds
         * @see Instant.nanosecondsOfSecond
         */
        public fun fromEpochSeconds(epochSeconds: Long, nanosecondAdjustment: Long = 0): Instant

        /**
         * Returns an [Instant] that is the [epochSeconds] number of seconds from the epoch instant `1970-01-01T00:00:00Z`
         * and the [nanosecondAdjustment] number of nanoseconds from the whole second.
         *
         * The return value is clamped to the boundaries of [Instant] if the result exceeds them.
         * In any case, it is guaranteed that instants between [DISTANT_PAST] and [DISTANT_FUTURE] can be represented.
         *
         * [fromEpochMilliseconds] is a similar function for when input data only has millisecond precision.
         *
         * @see Instant.epochSeconds
         * @see Instant.nanosecondsOfSecond
         */
        public fun fromEpochSeconds(epochSeconds: Long, nanosecondAdjustment: Int): Instant

        /**
         * Parses an ISO 8601 string that represents an instant (for example, `2020-08-30T18:43:00Z`).
         *
         * Guaranteed to parse all strings that [Instant.toString] produces.
         *
         * Examples of instants in the ISO 8601 format:
         * - `2020-08-30T18:43:00Z`
         * - `2020-08-30T18:43:00.50Z`
         * - `2020-08-30T18:43:00.123456789Z`
         * - `2020-08-30T18:40:00+03:00`
         * - `2020-08-30T18:40:00+03:30:20`
         * * `2020-01-01T23:59:59.123456789+01`
         * * `+12020-01-31T23:59:59Z`
         *
         * See ISO-8601-1:2019, 5.4.2.1b), excluding the format without the offset.
         *
         * The string is considered to represent time on the UTC-SLS time scale instead of UTC.
         * In practice, this means that, even if there is a leap second on the given day, it will not affect how the
         * time is parsed, even if it's in the last 1000 seconds of the day.
         * Instead, even if there is a negative leap second on the given day, 23:59:59 is still considered a valid time.
         * 23:59:60 is invalid on UTC-SLS, so parsing it will fail.
         *
         * @throws IllegalArgumentException if the text cannot be parsed or the boundaries of [Instant] are exceeded.
         *
         * @see Instant.toString for formatting.
         */
        public fun parse(input: CharSequence): Instant

        /**
         * An instant value that is far in the past.
         *
         * [isDistantPast] returns true for this value and all earlier ones.
         */
        public val DISTANT_PAST: Instant

        /**
         * An instant value that is far in the future.
         *
         * [isDistantFuture] returns true for this value and all later ones.
         */
        public val DISTANT_FUTURE: Instant
    }
}

/**
 * Returns true if the instant is [Instant.DISTANT_PAST] or earlier.
 */
public val Instant.isDistantPast: Boolean
    get() = this <= Instant.DISTANT_PAST

/**
 * Returns true if the instant is [Instant.DISTANT_FUTURE] or later.
 */
public val Instant.isDistantFuture: Boolean
    get() = this >= Instant.DISTANT_FUTURE
```


All parts of this API have existed in `kotlinx-datetime` for a while now and
have stable, widely used and thoroughly tested implementations.

This API is based on the eponymous API entry in JSR 310 (available in
the 310bp project and the standard library of Java9 and later):
<https://github.com/ThreeTen/threetenbp/blob/b833efe7ac2f1a02c016deb188fd7ce3b124ed2e/src/main/java/org/threeten/bp/Instant.java>.
There are some differences between the behaviors of the two, but they are minor.

What follows is the rationale for every part of the API, along with the
description of differences from Java's `Instant`.

#### The `Comparable` bound

It is often required to find out which event happened earlier.

Some examples of use cases:

* Merging several streams of events into a unified time-ordered view.
* Determining if it's already time to perform some action
  (`Clock.System.now() >= whenToPerformAnAction`).
* Determining if time-based conditions were fulfilled
  (`responseTime < deadline`).

Java provides the `Comparable` type bound, but also
`isAfter` and `isBefore` functions.
We kept just the `Comparable` bound to keep the API skimmable,
and also because `if (Clock.System.now() < start)` seems perfectly unambiguous
and readable.

#### `epochSeconds` + `nanosecondsOfSecond` + `fromEpochSeconds`

The fields `epochSeconds` and `nanosecondsOfSecond`
define what an `Instant` represents.

* `epochSeconds` is the number of seconds since `1970-01-01 00:00:00 GMT`
  (the moment called the start of the Unix epoch).
  It can also be negative: `1969-12-31 23:59:59 GMT` is represented
  as `epochSeconds = -1`.
  It is a `Long` value because an `Int` is only enough to represent years
  up to 2038: <https://en.wikipedia.org/wiki/Year_2038_problem>
* `nanosecondsOfSecond` describes how many nanoseconds *later* the given instant
  than the one defined by just `epochSeconds`.
  For example, `epochSeconds = -1, nanosecondsOfSecond = 1` is the moment
  `999_999_999` nanoseconds earlier than the Unix epoch start, and
  `epochSeconds = 1, nanosecondsOfSecond = 1` is the moment
  `1_000_000_001` nanoseconds later than the Unix epoch start.
  `nanosecondsOfSecond` is always in the range `0..999_999_999`.

`fromEpochSeconds` accepts a pair of `epochSeconds` and `nanosecondsOfSecond`,
but for improved flexibility, it allows `nanosecondsOfSecond` to be outside the
given range. Therefore, the parameter is called `nanosecondAdjustment` there.

##### `div + mod` vs `quot + rem`

We could have defined different semantics for this pair.

* We could allow `nanosecondsOfSecond` to be in the range
  `-999_999_999..999_999_999` and force `epochSeconds` and
  `nanosecondsOfSecond` to have the same sign.
  For example, `epochSeconds = -1, nanosecondsOfSecond = -1` would be
  `1_000_000_001` nanoseconds earlier than the Unix epoch start.
* Another natural idea is to have `nanosecondsOfSecond` in the range
  `0..999_999_999` and just always treat it as if it had the same sign as
  `epochSeconds`, so `epochSeconds = -1, nanosecondsOfSecond = 1` would
  mean `1_000_000_001` nanoseconds before the epoch start.
  Unfortunately, this idea does not work: both `EPOCH - 1.nanoseconds`
  and `EPOCH + 1.nanoseconds` would then have the same representation
  `epochSeconds = 0, nanosecondsOfSecond = 1`. This could be worked around
  by adding a separate field describing the sign of the `Instant` value, but
  then, it would need to be taken into account in all calculations involving
  `epochSeconds` and `nanosecondsOfSecond`, complicating the client code.

Both what we have now and the possibly-negative-`nanosecondsOfSecond` approaches
are mathematically natural: `instant + anotherInstant.epochSeconds.seconds +
anotherInstant.nanosecondsOfSecond.nanoseconds` returns what you expect,
`instant1 < instant2` is implemented lexicographically, and the
`fromEpochSeconds` that we provide will work with either representation as well.
The notable difference arises when it comes to platform interoperability.

* <https://man7.org/linux/man-pages/man3/time_t.3type.html> defines
  the sub-second portion as positive.
* <https://learn.microsoft.com/en-us/windows/win32/api/minwinbase/ns-minwinbase-systemtime>
  defines the sub-second portion as positive.
* Real-life clocks define the time-of-day to always be some positive
  amount of time since the start of the day.

Populating any of these structures is going to be more convenient with the
scheme we chose.

Last but not least, this is also how `java.time` does it.

##### Admissible ranges

We define maximum and minimum `Instant` values to be
`-1000000000-01-01T00:00Z .. +1000000000-12-31T23:59:59.999999999Z`.
This fully replicates the ranges on the JVM and is wide enough to
fulfill every realistic use need.
For example, on Apple's platforms, `Date.distantPast` and `Date.distantFuture`
are defined as `0001-01-01 00:00:00 +0000` and `4001-01-01 00:00:00 +0000`.

The upside of this range is that roundtrips across abritrary usages of
`kotlin.time.Instant` are possible:

* `java.time.Instant.MAX.toKotlinInstant()` obtains `kotlin.time.Instant`
  on the JVM.
* That `kotlin.time.Instant` is sent over the network to a Native or JS
  client.
* The `Instant` gets successfully parsed and sent back.
* On the server, the returned `Instant` gets converted to Java's `Instant`
  without any information being lost.

If we consider these roundtrips to be pointless in practice, there are other
possible candidates for admissible ranges.

###### A million years

`-1000000-01-01T00:00:00Z .. +1000000-12-31T23:59:59.999999999Z` is another
range we've considered.
`10^6` years is still enough to support every need,
and this year range has some upsides:

* With it, the minimum/maximum number of days since the epoch start fits into
  an `Int`.
  Because of this, we can implement `kotlinx.datetime.LocalDate` that supports a
  range close to what `Instant` supports with just an `Int`.
* At the point when the ranges were discussed, Kotlin/JS was a bigger driver of
  decisions than it is now, and this range allowed `Instant` to be represented
  with two JS numbers: one for seconds, one for nanoseconds.
  If Java's range was used instead, the maximum number of seconds would not be
  representable in one JS number without losing precision.
  This would necessitate using `Long` in Kotlin/JS, which doesn't have a
  native feel and is less efficient.

On the other hand, while the roundtrip described above may be too convoluted to
consider a realistic problem, we can't be confident that a much smaller
roundtrip of `java.time.Instant.MAX` to `kotlin.time.Instant` to
`java.time.Instant` will never cause any problems.

###### Different ranges for different platforms

We can keep `10^9` years for the JVM, and `10^6` years for the other platforms.
This approach was taken by `kotlinx-datetime`,
and this didn't cause known problems.

Let's consider different usage scenarios:

* Inside a single common-code Kotlin codebase, `Instant` values with years
  outside `+1000000/-1000000` are unlikely to appear in except by mistake.
  We are explicitly not providing `Instant.MAX` and `Instant.MIN`,
  so users have to be intentionally accessing large values to notice
  that JVM supports them and the rest of the platforms don't.
* In complex multi-codebase scenarios, it can happen that
  `java.time.Instant.MAX.toKotlinInstant()` is sent over the network to,
  for example, JS, and there, parsing/constructing this `Instant` should not
  fail.
  Depending on how the `Instant` is constructed, it can either fail or not:
  since `parse()` throws exceptions when the `Instant` doesn't fit, relying
  on ISO string for this use case will not work, but `fromEpochMilliseconds`
  will, as the "Behavior on overflow" section describes.
  It any case, two neighboring sentinel values,
  like `java.time.Instant.MAX` and `java.time.Instant.MAX - 1.seconds` will
  not be able to sent to another platform without either conflating them or
  throwing at some point.

An additional concern is that `Instant.MAX` and `Instant.MIN`
become error-prone.

If we do decide to add `Instant.MAX` and `Instant.MIN`, the discrepancy between
ranges becomes a more significant issue, because then, these two values may be
used as sentinels whenever `null` is inconventient.
Imagine a JVM server as well as two clients: a Native client, and a JVM client.
This is likely a common combination in KMP, with Android and iOS.

Clients have:

```kotlin
var lastEvent = Instant.MIN

sendToServer(lastEvent)
```

The server has:

```kotlin
val lastEvent = receiveFromClient()
if (lastEvent == Instant.MIN) return null // no event
```

This code will work for the Android client but not for the iOS client, because
`Instant.MIN` will be different.

Strictly speaking, this decision does not block us,
as we can add an annotation like `DelicateApi` to deter people from using
these values as sentinels, but it is still an important point against having
different ranges.

##### Leap second handling

An important question about "the number of seconds since the epoch start" is,
in what sense is it "the number"?

A natural interpretation would be that it's the number of physical seconds that
physically passed since the moment the clocks have showed
`1970-01-01 00:00:00 +0000`.
However, the actual implementation is something different.

<https://en.wikipedia.org/wiki/Unix_time#Leap_seconds> includes
a good explanation of how physical time differs from UTC and from Unix time:
sometimes, so-called "leap seconds" are introduced, analogous to the leap days
that happen on leap years.
When a positive leap second happens, it takes two seconds of physical time
for clocks configured to follow UTC or Unix time to count one second.
When a negative leap second happens, it takes one second of physical time for
clocks to count two seconds.

How can two seconds of physical time fit into one second on the clocks, or
vice versa? There are several approaches:

* UTC says that days with positive leap seconds include one more second,
  so instead of 23:59:59 being succeeded by 00:00:00 of the next day, it is
  succeeded by 23:59:60, which in turn is succeeded by 00:00:00.
* Unix time repeats a second, so 23:59:59.999 is followed by
  23:59:59.000 again.
* UTC-SLS, a system Java claims to use, "smears" the time of that extra second
  across the surrounding seconds:
  after 23:59:59 we see 00:00:00, but this UTC-SLS second takes more than a
  physical second to pass.
  See <https://docs.oracle.com/en%2Fjava%2Fjavase%2F22%2Fdocs%2Fapi%2F%2F/java.base/java/time/Instant.html#time-scale-heading>

In practice, all of this is almost purely theoretical and does not matter.
Quoting Java's docs:

> Implementations of the Java time-scale using the JSR-310 API are not required
> to provide any clock that is sub-second accurate, or that progresses
> monotonically or smoothly.
> Implementations are therefore not required to actually perform the UTC-SLS
> slew or to otherwise be aware of leap seconds.
> JSR-310 does, however, require that implementations must document the approach
> they use when defining a clock representing the current instant.

Conceptually, this means that `Instant` values taken at the exact same physical
moment and obtained from perfectly synchronized but different `Clock`
implementations are allowed to have different representations depending on
whether the `Clock` takes leap seconds into account and performs smearing.

However, practically, the "perfectly synchronized clocks" and "same physical
moment" requirements make this discrepancy impossible to observe unless
someone is actively trying to: this one-second difference between clock readings
occurs much more often due to
[clock drift](https://en.wikipedia.org/wiki/Clock_drift) than it does due to
leap seconds.

<https://github.com/ThreeTen/threeten-extra/tree/4e016340b97cab604114d10e02a672c1e94c6be5/src/main/java/org/threeten/extra/scale>
is an implementation that converts between "UTC `Instant`", a new class, and
a normal UTC-SLS `Instant`, but no one seems to care in the slightest:
<https://grep.app/search?q=UtcInstant&case=true&words=true>.

As such, UTC-SLS is a great choice for a time scale:

* Compatibility with Java.
* No need to handle leap seconds when parsing or formatting (as we would have
  had we chosen the UTC time scale, for example).

#### `toEpochMilliseconds` + `fromEpochMilliseconds`

In addition to converting to and from epoch seconds, occasionally, milliseconds,
microseconds, and nanoseconds are also used.

Millisecond conversion is widely used in Java
<https://grep.app/search?q=ofEpochMilli> and is implemented for
`kotlinx.datetime.Instant`.

A `Long` number can fit:
* About +/- `300` years in nanoseconds (`2^63 / 1_000_000_000`).
* About +/- `300_000` years in microseconds (`2^63 / 1_000_000`).
* About +/- `300_000_000` years in milliseconds (`2^63 / 1_000`).

The specified ranges are smaller than our `Instant` ranges.

Whenever an `Instant` is too big to fit into a `Long` number of milliseconds,
the resulting number is clamped to `Long.MIN_VALUE` or `Long.MAX_VALUE`,
depending on the sign.

The opposite, where the `Long` number of milliseconds is too big to
fit into an `Instant`, can not happen with the currently proposed design,
but if we changed the admissible range of `Instant` values to be narrower,
we would have to follow the rules for `fromEpochSeconds`.

We do not provide conversions to and from microseconds, but it is clear
from analogy with `toEpochMilliseconds` and `fromEpochMilliseconds` how they
should behave if we decide to add them later.

Conversion from nanoseconds can already be used via
`Instant.fromEpochSeconds(epochSeconds = 0, nanosecondAdjustment = epochNanoseconds)`,
but timestamps in nanoseconds seem to be exceedingly rare anyway.

#### `plus`/`minus` a `Duration`

Given an `Instant`, it's possible to obtain an `Instant` that's a given duration
later or earlier:

```kotlin
Instant.fromEpochSeconds(0) + 1.seconds == Instant.fromEpochSeconds(1)
Instant.fromEpochSeconds(0) - 1.seconds == Instant.fromEpochSeconds(-1)
```

Together with `minus(Instant)`, this allows one to write things like

```kotlin
val lastTrainingStart: Instant
val lastTrainingEnd: Instant
val expectedTrainingEnd =
    Clock.System.now() + (lastTrainingEnd - lastTrainingStart)
```

On overflow, the `Instant` is clamped to its boundaries.
See the "Behavior on overflow" section for details.

#### Behavior on overflow

A significant departure from Java is our handling of overflowing `Instant`s.

```kotlin
// Constructing
println(runCatching {
    java.time.Instant.ofEpochSecond(Long.MAX_VALUE)
}) // Failure(java.time.DateTimeException: Instant exceeds minimum or maximum instant)
println(runCatching {
    // As well as kotlin.time.Instant after the migration
    kotlinx.datetime.Instant.fromEpochSeconds(Long.MAX_VALUE)
}) // Success(+1000000000-12-31T23:59:59.999999999Z)

// Arithmetics
println(runCatching {
    java.time.Instant.EPOCH
        .plusSeconds(Long.MAX_VALUE)
}) // Failure(java.time.DateTimeException: Instant exceeds minimum or maximum instant)
println(runCatching {
    // As well as kotlin.time.Instant after the migration
    kotlinx.datetime.Instant.fromEpochMilliseconds(0)
        .plus(kotlin.time.Duration.INFINITE)
}) // Success(+1000000000-12-31T23:59:59.999999999Z)
```

Where Java consistently enforces the Instant boundaries, we instead choose to
clamp the results to `Instant.MAX` or `Instant.MIN`, depending on the sign.

The idea is that when someone has years bigger than `1_000_000_000`, then surely
these values do not actually represent an `Instant` value in the business logic.
Short of a sci-fi fiction writer calculating the number of seconds to give as
input to a time machine to witness the extinction of dinosaurs
66 million years ago first-hand, there are barely any cases where such large
values are meaningful and should be correctly preserved.

Instead, we expect that such large values are simply sentinel `MAX`/`MIN` values
in various systems. Consider this code:

```kotlin
val maxTimestamp = Instant.fromEpochSeconds(MAX_POSTGRES_INTERVAL)

val nextEvent =
    timestamps.map(Instant::fromEpochSeconds)
        filter { it > currentTime }.minOrNull() ?: maxTimestamp

if (nextEvent != maxTimestamp) {
    println("The next event is $nextEvent")
} else {
    println("No events to execute")
}
```

With our approach, this code works properly for all realistic instants, and
for some of the most unrealistic ones it incorrectly returns "no events to
execute" when actually there's actually an available event in a million years.

With Java's approach, this code stays the same when `MAX_POSTGRES_INTERVAL`
fits into an `Instant`, but if it doesn't, it becomes something like this
instead:

```kotlin
val maxTimestamp =
    // this can be simplified to a constant in platform code, but in common
    // code, this is necessary
    if (MAX_POSTGRES_INTERVAL > Instant.MAX.epochSecond)
        Instant.MAX
    else
        Instant.ofEpochSecond(MAX_POSTGRES_INTERVAL)

val nextEvent =
    timestamps.map {
        if (it > Instant.MAX.epochSecond) Instant.MAX
        else if (it < Instant.MIN.epochSecond) Instant.MIN
        else Instant.ofEpochSecond(it)
    }.filter { it > currentTime }.minOrNull() ?: maxTimestamp
```

Given how rare huge instant values are, this pattern is not widely used, but
still, it exists in the wild: <https://grep.app/search?q=Instant.MAX.>

Throwing on overflow would force us to expose `Instant.MAX` and require this
pattern.

#### `minus` an `Instant`

This operation is completely natural and denotes how much time has passed
between two moments according to the system clock:

```kotlin
Instant.fromEpochMilliseconds(5) - Instant.fromEpochMilliseconds(3)
    == 2.milliseconds
```

There is some danger that people will try misusing this operation for measuring
the time it takes to execute code: this `-` is almost never a good candidate
for that, as most `Instant` values are expected to be produced by the system
clock, which is typically neither as precise as `TimeSource.MONOTONIC` nor
actually monotonic.

Still, this is a good fit for measuring time between various events that happen
throughout the day. A monotonic clock is only available while a computer is
running, whereas `Instant` values can be safely serialized and deserialized.

Example:

```kotlin
val breakfastEnded = Instant.parse("2024-08-20T07:34:12Z")
val lunchStarted = Instant.parse("2024-08-20T13:31:51Z")
println("You went without food for ${lunchStarted - breakfastEnded}")
```

#### `toString` and `parse`

`toString` and `parse` use the format described in ISO-8601-1:2019, 5.4.2.1b),
excluding the format without the offset.
The format used is also mostly compatible with
<https://www.rfc-editor.org/rfc/rfc3339#section-5.6>.
Both of these formats are widely used, but also human-readable.

Example: `2024-08-24T16:22:34Z`.

##### Advanced parsing and formatting

There are other important formats for instant values, and also, there are
limitations to the default format and how it can be used:

* People may want to format RFC 1123 strings, like
  `Mon, 30 Jun 2008 11:05:30 -0300`.
* Occasionally, `24:00:00` is used to denote the last moment of the day.
* `23:59:60` can represent positive leap seconds.
* Given an ISO string like `2024-08-20T16:26:15+02:00`, someone may want to
  obtain the `+02:00` part and not just the `Instant` value.
* Someone may want to format an `Instant` with a UTC offset other than 0.

All of this is outside the scope of `kotlin.time.Instant`: inherently,
`kotlin.time.Instant` doesn't "know" anything about days or months or what hour
it is. These concepts are strictly in the (calendar- and timezone-aware)
datetime and not physical time territory.
Therefore, advanced parsing and formatting needs are fulfilled in
`kotlinx-datetime`.
If the Standard Library ever acquires a flexible enough API for defining custom
formats, we may want to revisit this.

##### Leap seconds

A departure from both RFC 3339 and ISO 8601 is that we do not allow parsing
positive leap seconds (like `23:59:60Z`) and do not forbid parsing
`23:59:59Z` even when there is a negative leap second.
The reason for this is our choice of the UTC-SLS time scale: in that time scale,
`Instant` values with `23:59:60Z` do not exist, and `23:59:59Z` never gets
omitted, and instead, the extra and missing seconds are emulated by speeding
the clock up and down.

Java supports parsing `23:59:60Z`, but when the offset is different from `Z`,
it does so incorrectly as of writing, allowing inserting leap seconds at
`23:59:60` regardless of the UTC offset and forbidding the second value `:60`
in all other combinations.

This may be an issue for compatibility: if another system produces
`23:59:60Z`, we will not be able to parse that.
In that case, the user can employ the parsing and formatting API in
`kotlinx-datetime` to decide how they want to handle leap seconds.
It's also worth noting that we didn't find a significant demand for leap
second handling in other parsing and formatting APIs, so this concern is mostly
theoretical.

#### The deprecated `now`

A common way to obtain the current `Instant` on the JVM is to call
`Instant.now()`:

* <https://grep.app/search?q=Instant.now%28%29> is 13000 hits as of writing.
* <https://grep.app/search?q=clock.instant%28%29> is about 1200 hits.
  This isn't exhaustive, but gives an estimate of how often people use
  dependency injection of clocks.

It is not obvious to people who want to obtain the current `Instant` that a
class like `Clock` even exists, so we direct them to it using a deprecation with
a proposed replacement.

#### `DISTANT_PAST` + `DISTANT_FUTURE` + `isDistantPast` + `isDistantFuture`

On the JVM, `Instant.MAX` and `Instant.MIN` are occasionally used as default
values, mostly for the purposes of finding the earliest or the latest instant:

```kotlin
var earliestEvent = java.time.Instant.MAX

fun registerNewEvent(event: java.time.Instant) {
    if (event < earliestEvent) earliestEvent = event
}
```

This is more convenient and has one fewer branch compared to using `null`:

```kotlin
var earliestEvent: java.time.Instant? = null

fun registerNewEvent(event: java.time.Instant) {
    if (earliestEvent == null || event < earliestEvent) earliestEvent = event
}
```

In return, this loses the ability to represent events that are actually
happening at the moment of `Instant.MAX`, but this isn't a huge sacrifice.

For these use cases, we provide `Instant.DISTANT_PAST`,
`Instant.DISTANT_FUTURE`, and ways to check if a given instant has crossed these
boundaries. This can be used for the same use case of maintaining the record of
the least / the biggest encountered value, but without exposing the
implementation-defined limits.

Values of `DISTANT_PAST` and `DISTANT_FUTURE` are the same for all platforms and
can be safely (de)serialized:

```kotlin
public val DISTANT_PAST: Instant // -100001-12-31T23:59:59.999999999Z

public val DISTANT_FUTURE: Instant // +100000-01-01T00:00:00Z
```

#### Implementation on the JVM

With the introduction of `Instant` to the standard library, we can now make it
not just a pure library solution but a compiler-supported one.
In practice, this means that we may map `kotlin.time.Instant` to
`java.time.Instant`.

This would give us several advantages over keeping `Instant` our own class:

* `Instant` automatically becomes `java.io.Serializable`, with its maintanance
  being outside of our concerns.
* Whenever a class with an `Instant` field is used for ORM, the existing
  handling of `java.time.Instant` will automatically work.
* A lot of APIs accept `java.time.Instant` as parameters:
  <https://grep.app/search?q=%28Instant%20>.

Disadvantages also exist:

* As noted above, there are several differences between how methods in
  `java.time.Instant` and `kotlin.time.Instant` work.
  Most notably, several APIs that have the same name behave differently:
  `now` is deprecated, and `parse` does not accept the same set of strings.
  All remaining methods that have different behaviors between Kotlin and Java
  also have slight differences in names.
  For `parse`, this means that `java.time.Instant.parse` and
  `kotlin.time.Instant.parse` would be functions with the exact same interface
  but different behavior.
* Increased complexity of adding new API.

Neutral points:

* If Java adds some new API with a name we already provide but with a different
  behavior, we will need to hide it.
  However, there doesn't seem to be such API at the moment.
* Undesirable interface implementations: `Temporal`, `TemporalAdjuster`.
  However, we do not intend to implement these interfaces on
  `kotlin.time.Instant`, so this is not a compatibility problem.
* Mapping `kotlin.time.Instant` to `java.time.Instant` should not make
  the migration easier, as `kotlinx-datetime` is a multiplatform
  library.

The technical details of how this mapping could be achieved is outside the scope
of this document.
As of writing, a KEEP dedicated to adding atomic values to the Standard Library
is exploring this topic in great depth.

#### `java.io.Serializable`

We intend to add its support to `kotlin.time.Instant`, but the specific
implementation depends on how we proceed with the migration.

We do not have any hard constraints on the implementation.
It is possible that `kotlinx-datetime` releases with its own implementation of
`java.io.Serializable` for `kotlinx.datetime.Instant`, but these formats don't
have to be compatible: whenever someone serializes `kotlinx.datetime.Instant`,
they expect to receive just that on deserialization.
When the code serializing `kotlinx.datetime.Instant` is migrated to
`kotlin.time.Instant`, the old serialization implementation is no longer
relevant.

#### `kotlinx.serialization.Serializable`

The existing serializers can't be moved to the standard library, as the standard
library does not depend on `kotlinx.serialization`.
Keeping them in `kotlinx-datetime` is also incorrect from the perspective of
concerns separation.

Instead, like it is done for other data structures in the standard library,
`kotlinx.serialization` should include a default serializer for
`kotlin.time.Instant` out of the box,
supported by `parse` and `toString`.

`koltinx.serialization` includes additional serializers for standard library
entities in the `kotlinx.serialization.builtins` package, like
<https://kotlinlang.org/api/kotlinx.serialization/kotlinx-serialization-core/kotlinx.serialization.builtins/-long-as-string-serializer/>.
`InstantComponentSerializer` can be added in a
form determined by the `kotlinx.serialization` maintainers.
After that, the corresponding serializer in `kotlinx-datetime` can be deprecated
in favor of the new one with a replacement.

`InstantIso8601Serializer` can be reintroduced in `kotlinx-datetime` with the
new type parameter, but for some time, it seems it will necessarily be
unavailable.

### Additional platform-specific converters

On all platforms, there are entities fulfilling the same purpose as
`Instant`, and for some of them, we provide automatic conversion functions:

```kotlin
// JVM

public fun kotlin.time.Instant.toJavaInstant(): java.time.Instant

public fun java.time.Instant.toKotlinInstant(): kotlin.time.Instant
```

```kotlin
// JS

/**
 * Converts the [Instant] to an instance of JS [Date].
 *
 * The conversion is lossy: JS uses millisecond precision to represent dates,
 * and [Instant] allows for nanosecond resolution.
 */
public fun Instant.toJSDate(): Date

public fun Date.toKotlinInstant(): Instant
```

```kotlin
// Darwin

/**
 * Converts the [Instant] to an instance of [NSDate].
 *
 * The conversion is lossy: Darwin uses millisecond precision to represent dates,
 * and [Instant] allows for nanosecond resolution.
 */
public fun Instant.toNSDate(): NSDate

/**
 * Converts the [NSDate] to the corresponding [Instant].
 *
 * Even though Darwin only uses millisecond precision,
 * it is possible that [date] uses larger resolution,
 * storing microseconds or even nanoseconds.
 * In this case, the sub-millisecond parts of [date]
 * are rounded to the nearest millisecond,
 * given that they are likely to be conversion artifacts.
 */
public fun NSDate.toKotlinInstant(): Instant
```

### `Clock` API

```kotlin
/**
 * A source of [Instant] values.
 *
 * See [Clock.System][Clock.System] for the clock instance that queries the operating system.
 *
 * It is not recommended to use [Clock.System] directly in the implementation. Instead, you can pass a
 * [Clock] explicitly to the necessary functions or classes.
 * This way, tests can be written deterministically by providing custom [Clock] implementations
 * to the system under test.
 */
public interface Clock {
    /**
     * Returns the [Instant] corresponding to the current time, according to this clock.
     *
     * Calling [now] later is not guaranteed to return a larger [Instant].
     * In particular, for [Clock.System], the opposite is completely expected,
     * and it must be taken into account.
     * See the [System] documentation for details.
     *
     * Even though [Instant] is defined to be on the UTC-SLS time scale, which enforces a specific way of handling
     * leap seconds, [now] is not guaranteed to handle leap seconds in any specific way.
     */
    public fun now(): Instant

    /**
     * The [Clock] instance that queries the platform-specific system clock as its source of time knowledge.
     *
     * Successive calls to [now] will not necessarily return increasing [Instant] values, and when they do,
     * these increases will not necessarily correspond to the elapsed time.
     *
     * For example, when using [Clock.System], the following could happen:
     * - [now] returns `2023-01-02T22:35:01Z`.
     * - The system queries the Internet and recognizes that its clock needs adjusting.
     * - [now] returns `2023-01-02T22:32:05Z`.
     *
     * When you need predictable intervals between successive measurements, consider using [TimeSource.Monotonic].
     *
     * For improved testability, you should avoid using [Clock.System] directly in the implementation
     * and pass a [Clock] explicitly instead.
     */
    public object System : Clock {
        override fun now(): Instant = @Suppress("DEPRECATION_ERROR") Instant.now()
    }

    /** A companion object used purely for namespacing. */
    public companion object
}
```

This class mimics Java's
[Clock](https://docs.oracle.com/javase/8/docs/api/java/time/Clock.html),
but without a time zone attached.
This makes the interface a clock in the sense of "system clock",
without the ability to serve as a wall-clock.

`now()` is not a method on `Instant`, even though that would be more convenient,
in order to encourage writing testable code using dependency injection.
If some function in the depths of the system calls `Instant.now()`, it becomes
nondeterministic and difficult to reliably test, whereas passing a `Clock`
to it makes calling `now()` a functionally pure operation.

### Migration process

#### Constraints

The API entries we have to take into account during the migration:

* The `Instant` class itself.
* The `Clock` interface.
* `kotlinx-datetime` functions that accept a `Clock` as a parameter:
  `Clock.todayIn(TimeZone)`, `Clock.asTimeSource()`.
* `kotlinx-datetime` functions that return a `Clock`:
  only `val Clock.System`.
* `kotlinx-datetime` functions that accept an `Instant` as a parameter:
  quite many of them.
* `kotlinx-datetime` functions that return an `Instant`: also many.
* `java.io.Serializable` implementation for `Instant`.
* Third-party functions that accept an `Instant` as a parameter.
* Third-party functions that return an `Instant`.
* Places where `Instant` is set as a type parameter.
  The only first-party example is serializers:
  `InstantIso8601Serializer`, `InstantComponentSerializer`.
* The serial descriptor names in serializers.

There are two additional types of API entries that could require special care
but luckily do not:

* Overridable methods that accept an `Instant`.
* Overridable methods that return an `Instant`.

The only `open class` that `kotlinx-datetime` has is `TimeZone`, but
its constructor is `internal`, so inheriting from it is not supported.

The only `interface` that `kotlinx-datetime` has that mentions `Instant`
is `Clock`.

#### Unavoidable issues

Regardless of the migration path taken,
`kotlinx-datetime` will have to suppress errors to access `MIN` and `MAX`,
which it needs to implement `Instant` parsing.
As discussed in the "Behavior on overflow" and "Admissible ranges" sections,
exposing them just for our internal needs is too high a price.
However, this is not a hard requirement:
`MIN` and `MAX` are only used for comparing to them, and we can check this via
indirect and less efficient means:

```kotlin
fun epochSecondsLaterThanMaxInstant(epochSeconds: Long): Boolean =
   Instant.fromEpochSeconds(epochSeconds) ==
   Instant.fromEpochSeconds(epochSeconds - 1)
```

#### Process 0: break everything

* Standard Library publishes its own `kotlin.time.Instant` and `Clock`.
* `kotlinx-datetime` removes `kotlinx.datetime.Instant` and `Clock`
  and depends on the new release of the Standard Library,
  changing the API entries that used to work on `kotlinx.datetime.Instant`
  to instead use `kotlin.time.Instant`.

That's it, we've migrated.

##### Analysis

`Instant` and `Clock` will be moved to the Standard Library,
but the existing consumers of `kotlinx-datetime` will all be broken,
because basically all of them rely on `kotlinx.datetime.Instant`.

###### First-party entities

`kotlinx-datetime` functions operating on `kotlinx.datetime.Instant` or
`kotlinx.datetime.Clock` immediately stop working after a `kotlinx-datetime`
upgrade.

`kotlinx.datetime.Instant` instances serialized using `java.io.Serializable` will
have to deserialize into `kotlin.time.Instant` instead, as there is nothing else
it can deserialize into.

`Instant` serializers in `kotlinx.datetime.serializers` are also removed.

###### Third-party code

Third-party code using `kotlinx.datetime.Instant` simply breaks and will always
fail with a `ClassNotFoundException` until it is rewritten to use
`kotlin.time.Instant` and a new version is published.
This renders most libraries relying on `kotlinx.datetime.Instant`
(even internally) completely useless overnight.

#### Process 1: pure library solutions against breakage

* Standard Library publishes its own `kotlin.time.Instant` and `Clock`.
* `kotlinx-datetime`:
  - And adds conversion functions between `kotlinx.datetime.Instant`
    and `kotlin.time.Instant`, as well as between `kotlinx.datetime.Clock` and
    `kotlin.time.Clock`. These are public.
  - Deprecates `kotlinx.datetime.Instant` and `kotlinx.datetime.Clock`
    with a warning and a replacement with
    `kotlin.time.Instant` and `kotlin.time.Clock`.
  - Deprecates every function that accepts an `Instant`/`Clock`,
    adds an overload that accepts `kotlin.time.(Instant|Clock)`
    instead. The proposed suggestions involve the new converters.
    Exception: `Clock.asTimeSource` does not get the new overload.
  - For every function that returns but doesn't accept an `Instant`,
    hides it and adds a new function, one that returns `kotlin.time.Instant`,
    but with different platform names.
  - Deprecates the `Instant` serializers with a warning.
  - A new version of `kotlinx-datetime` is published with this.
* One major version of `kotlinx-datetime` later:
  - The hidden API entries are removed.
  - For every API entry with a custom JVM name, we remove these custom names,
    but also add a hidden API entry with that custom name, duplicating the
    normal entry.
  - The deprecation level is raised to `ERROR`.
* One more major release of `kotlinx-datetime` later, we remove the remaining
  hidden entries and the classes.

Converter signatures:

```kotlin
fun kotlinx.datetime.Instant.toStdlibInstant(): kotlin.time.Instant
fun kotlin.time.Instant.toKotlinxDatetimeInstant(): kotlinx.datetime.Instant
fun kotlinx.datetime.Clock.asStdlibClock(): kotlin.time.Clock
fun kotlin.time.Clock.toKotlinxDatetimeClock(): kotlinx.datetime.Clock
```

Example:

```kotlin
// 0.X.0
fun kotlinx.datetime.Instant.toLocalDateTime(): LocalDateTime
fun LocalDateTime.toInstant(): kotlinx.datetime.Instant

// 0.X+1.0
@Deprecated(level = DeprecationLevel.WARNING)
fun kotlinx.datetime.Instant.toLocalDateTime(): LocalDateTime

@Deprecated(level = DeprecationLevel.HIDDEN)
fun LocalDateTime.toInstant(): kotlinx.datetime.Instant

fun kotlin.time.Instant.toLocalDateTime(): LocalDateTime

@JsName("temporary_toInstant") // and other PlatformName annotations
fun LocalDateTime.toInstant(): kotlin.time.Instant

// 0.X+2.0
@Deprecated(level = DeprecationLevel.ERROR)
fun kotlinx.datetime.Instant.toLocalDateTime(): LocalDateTime

fun kotlin.time.Instant.toLocalDateTime(): LocalDateTime

@Deprecated(level = DeprecationLevel.HIDDEN)
fun LocalDateTime.temporary_toInstant(): kotlin.time.Instant

fun LocalDateTime.toInstant(): kotlin.time.Instant

// 0.X+3.0
fun kotlin.time.Instant.toLocalDateTime(): LocalDateTime

fun LocalDateTime.toInstant(): kotlin.time.Instant
```

##### Analysis

As a result of this, both `Instant` and `Clock` will be moved to the Standard
Library, so the main goal will be fulfilled.

###### First-party entities

`kotlinx-datetime` functions operating on `kotlinx.datetime.Instant` or
`kotlinx.datetime.Clock` will keep working,
preserving runtime compatibility for one major release.
Compile-time compatibility will be mostly preserved for one major release
as well, albeit with warnings, except in the case when a value of the
type `kotlin.time.Instant` is now returned but `kotlinx.datetime.Instant`
is explicitly expected:

```kotlin
// works
val instant = LocalDate(2024, 10, 5).atTime(12, 00).toInstant(TimeZone.UTC)
instant.plus(5, DateTimeUnit.DAY).toLocalDateTime(TimeZone.UTC)

// doesn't compile: `toInstant` returns `kotlin.time.Instant`,
// not `kotlinx.datetime.Instant`
val instant: kotlinx.datetime.Instant =
    LocalDate(2024, 10, 5).atTime(12, 00).toInstant(TimeZone.UTC)
instant.plus(5, DateTimeUnit.DAY).toLocalDateTime(TimeZone.UTC)
```

`java.io.Serializable` implementation of `kotlinx.datetime.Instant` will
keep working at runtime, returning `kotlinx.datetime.Instant`.
When code is changed to use `kotlin.time.Instant` instead, that will be
returned instead.

Unfortunately, the `Instant` serializers in `kotlinx.datetime.serializers`
will not work if the class changes.
Serializers may be omitted from the migration process if necessary, as they are
not very popular:

* <https://grep.app/search?q=InstantComponentSerializer> 0 usages.
* <https://grep.app/search?q=InstantIso8601Serializer> a single usage, one that
  we can fix if the `InstantIso8601Serializer` mention is simply omitted.

###### Third-party code

Whenever a third-party library is used that depends on `kotlinx-datetime`,
inconvenience is expected.

If a third-party library returns or accepts a `kotlinx.datetime.HiddenInstant`
(what today is just `kotlinx.datetime.Instant`), that value will be deprecated
in client code. Suggestion to just replace it with `kotlin.time.Instant` will
not work; instead, converter functions will need to be used for compatibility.

#### Process 2: tooling-assisted, but without breakage

* Standard library adds a class with the `JvmName` `kotlinx.datetime.Instant`,
  keeping it forever.
  The publicly visible name for name resolution and documentation purposes is
  `kotlin.time.Instant`.
* The `Instant` in the standard library must additionally have a hidden
  method with the signature
  `parse(CharSequence, kotlinx.datetime.format.DateTimeFormat<*>)`.
  For this, `DateTimeFormat` would need to be put into the standard
  library (it's unclear to me if we can avoid transferring
  `DateTimeFormat.Companion`, but the method it has today doesn't make sense
  without `kotlinx-datetime`).
  Additionally, `internal val Instant.MAX` and `internal val Instant.MIN` need
  to be provided.
* The compiler introduces a special case to exclude the
  `kotlinx.datetime.Instant` class and `kotlinx.datetime.format.DateTimeFormat`
  interface provided by `kotlinx-datetime`, so that
  there are no conflicts in projects that upgrade to the new standard library
  without touching `kotlinx-datetime`.
* A new compiler and standard library release are published.
* A new release of `kotlinx-datetime` is published, where `Instant` is removed,
  the `kotlinx.datetime.Instant` from the standard library is referenced, and
  (optional) `MIN` and `MAX` are no longer used.
* Some time later, `parse(CharSequence, kotlinx.datetime.DateTimeFormat<*>)`
  and (optional) `MIN` and `MAX` can be removed from the standard library.

##### Analysis

This introduces a split Java9 package
(<https://openjdk.org/projects/jigsaw/spec/>): there would be two libraries
providing `kotlinx-datetime`. This may be a major downside.

All code that used to work will continue to:

* Upgrading `kotlinx-datetime` to a new version means that the corresponding
  compiler version must be used, which means a newer standard library.
* All existing references to `kotlinx.datetime.Instant` will keep functioning,
  as there will be a class with all the same methods as the currently existing
  one.
