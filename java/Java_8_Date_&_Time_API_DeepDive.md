# Java 8 Date & Time API — Deep Dive Guide

> A complete, in-depth reference for `java.time` — Java's modern date/time library introduced in Java 8 (JSR-310). Covers every class, concept, and real-world pattern with working examples.

---

## 📚 Table of Contents

1. [Why java.time? The Problems with Legacy APIs](#1-why-javatime-the-problems-with-legacy-apis)
2. [Package Overview & Class Hierarchy](#2-package-overview--class-hierarchy)
3. [Core Date & Time Classes](#3-core-date--time-classes)
   - [LocalDate](#31-localdate)
   - [LocalTime](#32-localtime)
   - [LocalDateTime](#33-localdatetime)
4. [Time Zones & Offsets](#4-time-zones--offsets)
   - [ZoneId & ZoneOffset](#41-zoneid--zoneoffset)
   - [ZonedDateTime](#42-zoneddatetime)
   - [OffsetDateTime & OffsetTime](#43-offsetdatetime--offsettime)
5. [Machine Time](#5-machine-time)
   - [Instant](#51-instant)
   - [Clock](#52-clock)
6. [Amounts of Time](#6-amounts-of-time)
   - [Duration](#61-duration)
   - [Period](#62-period)
7. [Formatting & Parsing](#7-formatting--parsing)
   - [DateTimeFormatter](#71-datetimeformatter)
   - [Custom Patterns](#72-custom-patterns)
   - [Locale-Aware Formatting](#73-locale-aware-formatting)
8. [Adjusters & Queries](#8-adjusters--queries)
   - [TemporalAdjusters](#81-temporaladjusters)
   - [Custom TemporalAdjuster](#82-custom-temporaladjuster)
   - [TemporalQuery](#83-temporalquery)
9. [Chronologies & Calendar Systems](#9-chronologies--calendar-systems)
10. [Ranges & Boundaries](#10-ranges--boundaries)
11. [Legacy API Bridge](#11-legacy-api-bridge)
12. [Java 9+ Enhancements](#12-java-9-enhancements)
13. [Real-World Patterns & Recipes](#13-real-world-patterns--recipes)
14. [Common Pitfalls](#14-common-pitfalls)
15. [Quick Reference Cheat Sheet](#15-quick-reference-cheat-sheet)

---

## 1. Why java.time? The Problems with Legacy APIs

### Problems with `java.util.Date`

```java
// ── PROBLEM 1: Date is not a date — it's an instant ────────────────
java.util.Date date = new java.util.Date();
// "Date" stores milliseconds since Unix epoch — not year/month/day!
// date.getYear() returns years since 1900 → 2024 - 1900 = 124 ← absurd

// ── PROBLEM 2: All methods are deprecated ──────────────────────────
date.getYear();    // deprecated
date.getMonth();   // deprecated — returns 0-based month (January = 0!)
date.getDay();     // deprecated

// ── PROBLEM 3: Mutable — leads to bugs ─────────────────────────────
void process(Date d) {
    d.setTime(0); // silently mutates the caller's date!
}

// ── PROBLEM 4: No timezone concept ─────────────────────────────────
// Date has no timezone — relies on system default silently
```

### Problems with `java.util.Calendar`

```java
Calendar cal = Calendar.getInstance();
cal.set(2024, 0, 15); // Month 0 = January — off-by-one errors everywhere

cal.add(Calendar.MONTH, 1);       // verbose
cal.get(Calendar.DAY_OF_WEEK);    // 1=Sunday, 2=Monday... confusing

// Still mutable, thread-unsafe, verbose, error-prone
```

### What `java.time` Fixes

| Problem              | Legacy (Date/Calendar)       | java.time                           |
|----------------------|------------------------------|-------------------------------------|
| Immutability         | Mutable — not thread-safe    | All classes immutable & thread-safe |
| Month numbering      | 0-based (January = 0)        | 1-based via `Month` enum            |
| Clarity of purpose   | One class for everything     | Separate class per concept          |
| Timezone handling    | Implicit, error-prone        | Explicit `ZoneId`                   |
| Arithmetic           | Verbose, buggy               | Fluent: `plusDays()`, `minusWeeks()`|
| Parsing/Formatting   | `SimpleDateFormat` not thread-safe | `DateTimeFormatter` immutable  |
| Calendar systems     | Only Gregorian               | ISO, Hijri, Japanese, Thai, etc.    |

---

## 2. Package Overview & Class Hierarchy

```
java.time
│
├── ── HUMAN TIME (calendar-based) ──────────────────────────────────
│   ├── LocalDate          — date only (2024-03-15)
│   ├── LocalTime          — time only (14:30:00)
│   ├── LocalDateTime      — date + time, no timezone (2024-03-15T14:30:00)
│   ├── ZonedDateTime      — date + time + timezone (2024-03-15T14:30:00+05:30[Asia/Kolkata])
│   ├── OffsetDateTime     — date + time + fixed offset (2024-03-15T14:30:00+05:30)
│   └── OffsetTime         — time + fixed offset (14:30:00+05:30)
│
├── ── MACHINE TIME (epoch-based) ───────────────────────────────────
│   ├── Instant            — nanoseconds since 1970-01-01T00:00:00Z
│   └── Clock              — pluggable time source
│
├── ── AMOUNTS OF TIME ──────────────────────────────────────────────
│   ├── Duration           — time-based amount (seconds/nanos): "34.5 seconds"
│   └── Period             — date-based amount (years/months/days): "2 years, 3 months"
│
├── ── FIELDS & UNITS ───────────────────────────────────────────────
│   ├── ChronoField        — enum: YEAR, MONTH_OF_YEAR, DAY_OF_MONTH…
│   ├── ChronoUnit         — enum: DAYS, WEEKS, MONTHS, YEARS, HOURS…
│   └── DayOfWeek / Month  — enums for days and months
│
├── ── FORMATTING ───────────────────────────────────────────────────
│   └── DateTimeFormatter  — thread-safe immutable formatter/parser
│
├── ── ADJUSTERS & QUERIES ──────────────────────────────────────────
│   ├── TemporalAdjuster   — interface: "next Monday", "last day of month"
│   ├── TemporalAdjusters  — factory: predefined adjusters
│   └── TemporalQuery      — interface: extract info from temporal objects
│
└── java.time.chrono       — non-ISO calendar systems (Hijri, Japanese…)
    java.time.format       — formatting internals
    java.time.temporal     — low-level Temporal interfaces
    java.time.zone         — timezone rules & transitions
```

### Core Interfaces (Temporal Hierarchy)

```
Temporal (read + write operations)
  ├── TemporalAccessor (read-only)
  │     Implemented by: LocalDate, LocalTime, LocalDateTime,
  │                     ZonedDateTime, Instant, OffsetDateTime…
  └── Implemented by all date-time classes

TemporalAmount
  ├── Duration
  └── Period

TemporalAdjuster   — transforms a Temporal into another Temporal
TemporalQuery<R>   — extracts a value R from a TemporalAccessor
TemporalField      — a field of a date-time (ChronoField implements this)
TemporalUnit       — a unit of time (ChronoUnit implements this)
```

---

## 3. Core Date & Time Classes

### 3.1 LocalDate

Represents a **date without time and without timezone** — just year, month, day.

```java
import java.time.*;
import java.time.temporal.*;

public class LocalDateDemo {
    public static void main(String[] args) {

        // ── Creation ──────────────────────────────────────────────
        LocalDate today    = LocalDate.now();                  // system clock
        LocalDate specific = LocalDate.of(2024, 3, 15);       // year, month(1-12), day
        LocalDate fromEnum = LocalDate.of(2024, Month.MARCH, 15); // using Month enum
        LocalDate fromOrdinal = LocalDate.ofYearDay(2024, 75);    // 75th day of 2024
        LocalDate fromEpoch   = LocalDate.ofEpochDay(19797);      // days since 1970-01-01
        LocalDate parsed   = LocalDate.parse("2024-03-15");       // ISO format default

        System.out.println("Today:    " + today);
        System.out.println("Specific: " + specific);  // 2024-03-15
        System.out.println("Ordinal:  " + fromOrdinal); // 2024-03-15

        // ── Accessing Fields ──────────────────────────────────────
        LocalDate d = LocalDate.of(2024, 3, 15);
        System.out.println(d.getYear());            // 2024
        System.out.println(d.getMonth());           // MARCH  (enum)
        System.out.println(d.getMonthValue());      // 3      (int, 1-based)
        System.out.println(d.getDayOfMonth());      // 15
        System.out.println(d.getDayOfYear());       // 75
        System.out.println(d.getDayOfWeek());       // FRIDAY (enum)
        System.out.println(d.getDayOfWeek().getValue()); // 5 (Mon=1, Sun=7)
        System.out.println(d.lengthOfMonth());      // 31
        System.out.println(d.lengthOfYear());       // 366
        System.out.println(d.isLeapYear());         // true

        // Low-level field access via ChronoField
        System.out.println(d.get(ChronoField.ALIGNED_WEEK_OF_YEAR)); // 11
        System.out.println(d.get(ChronoField.DAY_OF_WEEK));          // 5

        // ── Arithmetic — returns NEW instance (immutable!) ────────
        LocalDate plus1Week   = d.plusWeeks(1);        // 2024-03-22
        LocalDate plus3Months = d.plusMonths(3);       // 2024-06-15
        LocalDate minus1Year  = d.minusYears(1);       // 2023-03-15
        LocalDate withDay     = d.withDayOfMonth(1);   // 2024-03-01
        LocalDate withMonth   = d.withMonth(12);       // 2024-12-15
        LocalDate withYear    = d.with(ChronoField.YEAR, 2025); // 2025-03-15

        // Using Period for adding
        LocalDate plusPeriod = d.plus(Period.of(1, 2, 10)); // +1y +2m +10d

        // ── Comparison ────────────────────────────────────────────
        LocalDate a = LocalDate.of(2024, 1, 1);
        LocalDate b = LocalDate.of(2024, 6, 15);

        System.out.println(a.isBefore(b));           // true
        System.out.println(a.isAfter(b));            // false
        System.out.println(a.isEqual(b));            // false
        System.out.println(a.compareTo(b));          // negative number

        // ── Range Checks ──────────────────────────────────────────
        LocalDate start = LocalDate.of(2024, 1, 1);
        LocalDate end   = LocalDate.of(2024, 12, 31);
        LocalDate check = LocalDate.of(2024, 6, 15);
        boolean inRange = !check.isBefore(start) && !check.isAfter(end);
        System.out.println("In range: " + inRange); // true

        // ── Conversion ────────────────────────────────────────────
        LocalDateTime atMidnight = d.atStartOfDay();           // 2024-03-15T00:00
        LocalDateTime atTime     = d.atTime(14, 30);           // 2024-03-15T14:30
        LocalDateTime atTimeObj  = d.atTime(LocalTime.NOON);   // 2024-03-15T12:00

        // How many days between two dates?
        long daysBetween = ChronoUnit.DAYS.between(a, b);   // 166
        long weeksBetween = ChronoUnit.WEEKS.between(a, b); // 23

        // ── Min/Max constants ─────────────────────────────────────
        System.out.println(LocalDate.MIN); // -999999999-01-01
        System.out.println(LocalDate.MAX); // +999999999-12-31
    }
}
```

---

### 3.2 LocalTime

Represents a **time of day without date and without timezone**.

```java
import java.time.*;
import java.time.temporal.*;

public class LocalTimeDemo {
    public static void main(String[] args) {

        // ── Creation ──────────────────────────────────────────────
        LocalTime now         = LocalTime.now();
        LocalTime specific    = LocalTime.of(14, 30);             // 14:30:00
        LocalTime withSeconds = LocalTime.of(14, 30, 45);         // 14:30:45
        LocalTime withNanos   = LocalTime.of(14, 30, 45, 123456789); // 14:30:45.123456789
        LocalTime fromNano    = LocalTime.ofNanoOfDay(52_245_000_000_000L); // from nanos since midnight
        LocalTime fromSecond  = LocalTime.ofSecondOfDay(52245);   // from seconds since midnight
        LocalTime parsed      = LocalTime.parse("14:30:45");

        // Useful constants
        System.out.println(LocalTime.MIDNIGHT); // 00:00
        System.out.println(LocalTime.NOON);     // 12:00
        System.out.println(LocalTime.MIN);      // 00:00
        System.out.println(LocalTime.MAX);      // 23:59:59.999999999

        // ── Accessing Fields ──────────────────────────────────────
        LocalTime t = LocalTime.of(14, 30, 45, 500_000_000);
        System.out.println(t.getHour());        // 14
        System.out.println(t.getMinute());      // 30
        System.out.println(t.getSecond());      // 45
        System.out.println(t.getNano());        // 500000000
        System.out.println(t.toSecondOfDay()); // 52245
        System.out.println(t.toNanoOfDay());   // 52245500000000

        // ── Arithmetic (wraps around midnight) ────────────────────
        LocalTime t2 = LocalTime.of(23, 0);
        System.out.println(t2.plusHours(3));    // 02:00 (wraps!)
        System.out.println(t2.plusMinutes(90)); // 00:30
        System.out.println(t2.minusSeconds(3600)); // 22:00

        // ── Truncation ────────────────────────────────────────────
        LocalTime precise = LocalTime.of(14, 35, 47, 999_999_999);
        System.out.println(precise.truncatedTo(ChronoUnit.HOURS));   // 14:00
        System.out.println(precise.truncatedTo(ChronoUnit.MINUTES)); // 14:35
        System.out.println(precise.truncatedTo(ChronoUnit.SECONDS)); // 14:35:47

        // ── Comparison ────────────────────────────────────────────
        LocalTime morning   = LocalTime.of(9, 0);
        LocalTime afternoon = LocalTime.of(15, 0);
        System.out.println(morning.isBefore(afternoon)); // true
        System.out.println(afternoon.isAfter(morning));  // true

        // ── Combination with Date ─────────────────────────────────
        LocalDate date = LocalDate.of(2024, 3, 15);
        LocalDateTime dt = date.atTime(t);                     // combine
        OffsetTime ot    = t.atOffset(ZoneOffset.of("+05:30")); // add offset
    }
}
```

---

### 3.3 LocalDateTime

Represents a **date-time without timezone** — combination of LocalDate + LocalTime.

```java
import java.time.*;
import java.time.temporal.*;

public class LocalDateTimeDemo {
    public static void main(String[] args) {

        // ── Creation ──────────────────────────────────────────────
        LocalDateTime now      = LocalDateTime.now();
        LocalDateTime specific = LocalDateTime.of(2024, 3, 15, 14, 30, 45);
        LocalDateTime fromParts = LocalDateTime.of(
            LocalDate.of(2024, 3, 15),
            LocalTime.of(14, 30, 45)
        );
        LocalDateTime parsed = LocalDateTime.parse("2024-03-15T14:30:45");

        // ── Accessing Fields — combines LocalDate + LocalTime ─────
        LocalDateTime dt = LocalDateTime.of(2024, 3, 15, 14, 30, 45);
        System.out.println(dt.toLocalDate());    // 2024-03-15
        System.out.println(dt.toLocalTime());    // 14:30:45
        System.out.println(dt.getYear());        // 2024
        System.out.println(dt.getMonthValue());  // 3
        System.out.println(dt.getDayOfMonth());  // 15
        System.out.println(dt.getHour());        // 14
        System.out.println(dt.getMinute());      // 30
        System.out.println(dt.getSecond());      // 45

        // ── Arithmetic ────────────────────────────────────────────
        System.out.println(dt.plusDays(10));         // 2024-03-25T14:30:45
        System.out.println(dt.plusHours(3));         // 2024-03-15T17:30:45
        System.out.println(dt.minusWeeks(2));        // 2024-03-01T14:30:45
        System.out.println(dt.withHour(9));          // 2024-03-15T09:30:45
        System.out.println(dt.withDayOfMonth(1));    // 2024-03-01T14:30:45
        System.out.println(dt.truncatedTo(ChronoUnit.HOURS)); // 2024-03-15T14:00

        // ── Adding Duration vs Period ──────────────────────────────
        // Duration: time-based (hours, minutes, seconds)
        System.out.println(dt.plus(Duration.ofHours(5)));   // adds hours
        // Period: date-based (years, months, days)
        System.out.println(dt.plus(Period.ofMonths(2)));    // adds months

        // ── Converting to Zoned / Offset ─────────────────────────
        ZonedDateTime zdt = dt.atZone(ZoneId.of("America/New_York"));
        OffsetDateTime odt = dt.atOffset(ZoneOffset.ofHours(5));

        // ── Comparison ────────────────────────────────────────────
        LocalDateTime dt1 = LocalDateTime.of(2024, 1, 1, 0, 0);
        LocalDateTime dt2 = LocalDateTime.of(2024, 6, 15, 12, 0);
        System.out.println(dt1.isBefore(dt2)); // true
        System.out.println(ChronoUnit.HOURS.between(dt1, dt2)); // 3996
    }
}
```

---

## 4. Time Zones & Offsets

### 4.1 ZoneId & ZoneOffset

```java
import java.time.*;
import java.util.*;

public class ZoneIdDemo {
    public static void main(String[] args) {

        // ── ZoneOffset — a fixed offset from UTC ──────────────────
        ZoneOffset utc        = ZoneOffset.UTC;               // +00:00
        ZoneOffset plus5_30   = ZoneOffset.of("+05:30");      // India
        ZoneOffset minus5     = ZoneOffset.ofHours(-5);       // US Eastern (no DST)
        ZoneOffset plus9_30   = ZoneOffset.ofHoursMinutes(9, 30); // Australia
        System.out.println(plus5_30.getTotalSeconds()); // 19800

        // ── ZoneId — a full timezone with DST rules ───────────────
        ZoneId systemZone = ZoneId.systemDefault();
        ZoneId kolkata    = ZoneId.of("Asia/Kolkata");
        ZoneId newYork    = ZoneId.of("America/New_York");
        ZoneId london     = ZoneId.of("Europe/London");
        ZoneId tokyo      = ZoneId.of("Asia/Tokyo");

        // ZoneOffset IS-A ZoneId — can use where ZoneId expected
        ZoneId fixedZone  = ZoneOffset.ofHours(5); // cast works

        // List all available zone IDs
        Set<String> allZones = ZoneId.getAvailableZoneIds();
        allZones.stream()
                .filter(z -> z.startsWith("Asia/"))
                .sorted()
                .limit(10)
                .forEach(System.out::println);

        // Zone aliases (SHORT_IDS)
        Map<String, String> aliases = ZoneId.SHORT_IDS;
        System.out.println(aliases.get("IST"));  // Asia/Kolkata
        System.out.println(aliases.get("EST"));  // -05:00
        System.out.println(aliases.get("PST"));  // America/Los_Angeles

        // Zone rules — DST transitions
        ZoneId nyZone = ZoneId.of("America/New_York");
        System.out.println(nyZone.getRules().isDaylightSavings(Instant.now()));
        System.out.println(nyZone.getRules().getOffset(Instant.now())); // -05:00 or -04:00
    }
}
```

---

### 4.2 ZonedDateTime

```java
import java.time.*;
import java.time.temporal.*;

public class ZonedDateTimeDemo {
    public static void main(String[] args) {

        // ── Creation ──────────────────────────────────────────────
        ZonedDateTime now      = ZonedDateTime.now();
        ZonedDateTime nowNY    = ZonedDateTime.now(ZoneId.of("America/New_York"));
        ZonedDateTime specific = ZonedDateTime.of(2024, 3, 15, 14, 30, 0, 0,
                                                   ZoneId.of("Asia/Kolkata"));
        ZonedDateTime fromLDT  = LocalDateTime.of(2024, 3, 15, 14, 30)
                                              .atZone(ZoneId.of("Europe/London"));
        ZonedDateTime fromInstant = ZonedDateTime.ofInstant(Instant.now(),
                                                            ZoneId.of("Asia/Tokyo"));
        ZonedDateTime parsed   = ZonedDateTime.parse("2024-03-15T14:30:00+05:30[Asia/Kolkata]");

        // ── Accessing Zone Info ───────────────────────────────────
        ZonedDateTime zdt = ZonedDateTime.of(2024, 7, 15, 14, 30, 0, 0,
                                             ZoneId.of("America/New_York"));
        System.out.println(zdt.getZone());             // America/New_York
        System.out.println(zdt.getOffset());           // -04:00 (DST in July)
        System.out.println(zdt.toLocalDateTime());     // 2024-07-15T14:30
        System.out.println(zdt.toLocalDate());         // 2024-07-15
        System.out.println(zdt.toLocalTime());         // 14:30
        System.out.println(zdt.toInstant());           // machine time equivalent
        System.out.println(zdt.toOffsetDateTime());    // 2024-07-15T14:30-04:00

        // ── Timezone Conversion ───────────────────────────────────
        ZonedDateTime kolkata = ZonedDateTime.of(2024, 3, 15, 14, 30, 0, 0,
                                                  ZoneId.of("Asia/Kolkata"));
        System.out.println(kolkata); // 2024-03-15T14:30+05:30[Asia/Kolkata]

        // Convert to other timezones — same instant, different representation
        ZonedDateTime inLondon  = kolkata.withZoneSameInstant(ZoneId.of("Europe/London"));
        ZonedDateTime inNewYork = kolkata.withZoneSameInstant(ZoneId.of("America/New_York"));
        ZonedDateTime inTokyo   = kolkata.withZoneSameInstant(ZoneId.of("Asia/Tokyo"));

        System.out.println("Kolkata:  " + kolkata);   // 14:30 +05:30
        System.out.println("London:   " + inLondon);  //  09:00 +00:00
        System.out.println("New York: " + inNewYork); //  04:00 -05:00
        System.out.println("Tokyo:    " + inTokyo);   //  17:30 +09:00

        // withZoneSameLocal — keep clock reading, change zone (different instant)
        ZonedDateTime sameLocal = kolkata.withZoneSameLocal(ZoneId.of("America/New_York"));
        System.out.println(sameLocal); // 14:30 -05:00 (different instant!)

        // ── DST Gap & Overlap Handling ────────────────────────────
        // DST GAP: On spring-forward day, 2:30 AM doesn't exist in America/New_York
        // Spring forward: 2024-03-10 at 2:00 AM clocks jump to 3:00 AM
        LocalDateTime gapTime = LocalDateTime.of(2024, 3, 10, 2, 30); // doesn't exist!

        ZonedDateTime inGap = gapTime.atZone(ZoneId.of("America/New_York"));
        System.out.println(inGap); // 2024-03-10T03:30-04:00 — automatically adjusted!

        // DST OVERLAP: On fall-back day, 1:30 AM happens twice in America/New_York
        // Fall back: 2024-11-03 at 2:00 AM clocks go back to 1:00 AM
        LocalDateTime overlapTime = LocalDateTime.of(2024, 11, 3, 1, 30); // exists twice!

        ZonedDateTime earlier = overlapTime.atZone(ZoneId.of("America/New_York"))
                                           .withEarlierOffsetAtOverlap(); // -04:00 (EDT)
        ZonedDateTime later   = overlapTime.atZone(ZoneId.of("America/New_York"))
                                           .withLaterOffsetAtOverlap();   // -05:00 (EST)

        System.out.println("Earlier: " + earlier); // 01:30-04:00
        System.out.println("Later:   " + later);   // 01:30-05:00

        // ── Arithmetic respects DST ───────────────────────────────
        ZonedDateTime beforeDST = ZonedDateTime.of(2024, 3, 9, 12, 0, 0, 0,
                                                    ZoneId.of("America/New_York"));
        // On 2024-03-10, clocks spring forward 1 hour
        System.out.println(beforeDST.plusDays(1));    // only 23 hours elapsed
        System.out.println(beforeDST.plus(Duration.ofDays(1))); // exactly 24 hours
        // plusDays(1) = "same time tomorrow" (DST aware)
        // plus(Duration.ofDays(1)) = "24 hours later" (absolute)
    }
}
```

---

### 4.3 OffsetDateTime & OffsetTime

```java
import java.time.*;

public class OffsetDateTimeDemo {
    public static void main(String[] args) {

        // OffsetDateTime — fixed offset (no DST rules)
        // Best for: timestamps in databases, APIs, logs
        OffsetDateTime now    = OffsetDateTime.now();
        OffsetDateTime odt    = OffsetDateTime.of(2024, 3, 15, 14, 30, 0, 0,
                                                   ZoneOffset.of("+05:30"));
        OffsetDateTime parsed = OffsetDateTime.parse("2024-03-15T14:30:00+05:30");

        System.out.println(odt.getOffset());         // +05:30
        System.out.println(odt.toInstant());         // UTC equivalent
        System.out.println(odt.toLocalDateTime());   // 2024-03-15T14:30
        System.out.println(odt.toZonedDateTime());   // with offset as zone

        // Convert to different offset
        OffsetDateTime inUTC = odt.withOffsetSameInstant(ZoneOffset.UTC);
        System.out.println(inUTC); // 2024-03-15T09:00Z

        // OffsetTime
        OffsetTime ot = OffsetTime.of(14, 30, 0, 0, ZoneOffset.of("+05:30"));
        System.out.println(ot); // 14:30+05:30

        // When to use which?
        // LocalDateTime   — no timezone context needed (birthday, appointment)
        // OffsetDateTime  — precise point-in-time storage (APIs, databases)
        // ZonedDateTime   — scheduling across DST transitions (meetings, alarms)
    }
}
```

---

## 5. Machine Time

### 5.1 Instant

```java
import java.time.*;
import java.time.temporal.*;

public class InstantDemo {
    public static void main(String[] args) {

        // ── Creation ──────────────────────────────────────────────
        Instant now        = Instant.now();                     // current UTC time
        Instant epoch      = Instant.EPOCH;                     // 1970-01-01T00:00:00Z
        Instant fromEpoch  = Instant.ofEpochSecond(1710510600); // from Unix seconds
        Instant withNanos  = Instant.ofEpochSecond(1710510600, 500_000_000); // +500ms
        Instant fromMillis = Instant.ofEpochMilli(1710510600000L);
        Instant parsed     = Instant.parse("2024-03-15T14:30:00Z"); // must be UTC (Z)

        // ── Accessing ─────────────────────────────────────────────
        Instant i = Instant.now();
        System.out.println(i.getEpochSecond()); // seconds since epoch
        System.out.println(i.getNano());        // nanosecond-of-second adjustment
        System.out.println(i.toEpochMilli());   // milliseconds since epoch

        // ── Arithmetic ────────────────────────────────────────────
        Instant later   = i.plusSeconds(3600);        // +1 hour
        Instant earlier = i.minusMillis(5000);        // -5 seconds
        Instant plus    = i.plus(Duration.ofDays(7)); // +1 week
        // NOTE: Cannot use plusDays() — Instant doesn't know about calendar
        // Instant doesn't support: DAYS (use Duration.ofDays), MONTHS, YEARS

        // ── Comparison ────────────────────────────────────────────
        Instant t1 = Instant.ofEpochSecond(1000);
        Instant t2 = Instant.ofEpochSecond(2000);
        System.out.println(t1.isBefore(t2)); // true
        System.out.println(t1.isAfter(t2));  // false
        System.out.println(t2.compareTo(t1)); // positive

        // ── Conversion ────────────────────────────────────────────
        // To human-readable time, combine with ZoneId
        ZonedDateTime readable = i.atZone(ZoneId.of("Asia/Kolkata"));
        OffsetDateTime offset  = i.atOffset(ZoneOffset.UTC);

        // Truncate to second (remove sub-second precision)
        Instant truncated = i.truncatedTo(ChronoUnit.SECONDS);
        System.out.println(truncated); // 2024-03-15T14:30:00Z

        // ── Performance timing ────────────────────────────────────
        Instant start = Instant.now();
        // ... some operation ...
        Instant end   = Instant.now();
        Duration elapsed = Duration.between(start, end);
        System.out.println("Elapsed: " + elapsed.toMillis() + "ms");

        // Even better for timing: System.nanoTime() (wall clock independent)
        long nanoStart = System.nanoTime();
        long nanoEnd   = System.nanoTime();
        System.out.println("Elapsed ns: " + (nanoEnd - nanoStart));

        // ── Min/Max ───────────────────────────────────────────────
        System.out.println(Instant.MIN); // -1000000000-01-01T00:00:00Z
        System.out.println(Instant.MAX); // +1000000000-12-31T23:59:59.999999999Z
    }
}
```

---

### 5.2 Clock

```java
import java.time.*;

public class ClockDemo {
    public static void main(String[] args) {

        // ── Built-in Clocks ───────────────────────────────────────
        Clock systemUTC    = Clock.systemUTC();             // UTC system clock
        Clock systemDefault = Clock.systemDefaultZone();   // system timezone clock
        Clock system       = Clock.system(ZoneId.of("Asia/Tokyo")); // specific zone

        // Use with now() methods
        LocalDate today = LocalDate.now(systemUTC);
        Instant now     = Instant.now(system);

        // ── Fixed Clock — great for testing! ──────────────────────
        Instant fixedInstant = Instant.parse("2024-03-15T10:00:00Z");
        Clock fixedClock = Clock.fixed(fixedInstant, ZoneOffset.UTC);

        // In tests — inject fixed clock to make time deterministic
        LocalDate testDate = LocalDate.now(fixedClock); // always 2024-03-15
        System.out.println(testDate);  // 2024-03-15

        // ── Offset Clock — shifted by Duration ────────────────────
        Clock offsetClock = Clock.offset(Clock.systemUTC(), Duration.ofHours(5));
        System.out.println(Instant.now(offsetClock)); // UTC + 5 hours

        // ── Tick Clocks — reduced precision ───────────────────────
        Clock tickSeconds = Clock.tickSeconds(ZoneOffset.UTC);  // truncated to second
        Clock tickMinutes = Clock.tickMinutes(ZoneOffset.UTC);  // truncated to minute
        Clock tick        = Clock.tick(Clock.systemUTC(), Duration.ofMillis(100)); // 100ms ticks

        // ── Testing Pattern — inject Clock as dependency ───────────
        class OrderService {
            private final Clock clock;

            OrderService(Clock clock) { this.clock = clock; }

            LocalDateTime createOrder() {
                return LocalDateTime.now(clock); // testable!
            }
        }

        // Production
        OrderService prod = new OrderService(Clock.systemDefaultZone());

        // Test — predictable time
        Clock testClock = Clock.fixed(Instant.parse("2024-01-01T00:00:00Z"), ZoneOffset.UTC);
        OrderService test = new OrderService(testClock);
        System.out.println(test.createOrder()); // always 2024-01-01T00:00
    }
}
```

---

## 6. Amounts of Time

### 6.1 Duration

Duration measures **time-based amounts** — seconds and nanoseconds. No concept of months or years.

```java
import java.time.*;
import java.time.temporal.*;

public class DurationDemo {
    public static void main(String[] args) {

        // ── Creation ──────────────────────────────────────────────
        Duration twoHours      = Duration.ofHours(2);
        Duration ninetyMinutes = Duration.ofMinutes(90);
        Duration thirtySeconds = Duration.ofSeconds(30);
        Duration fiveMillis    = Duration.ofMillis(5);
        Duration nanos         = Duration.ofNanos(1_000_000);
        Duration combined      = Duration.of(2, ChronoUnit.HOURS);

        // Parse ISO-8601 duration string
        Duration parsed  = Duration.parse("PT2H30M15.5S"); // 2h 30m 15.5s
        Duration parsed2 = Duration.parse("P1DT2H");       // 1 day 2 hours

        // Between two temporals (Instants or LocalTime/DateTime)
        Instant start = Instant.parse("2024-03-15T10:00:00Z");
        Instant end   = Instant.parse("2024-03-15T12:30:00Z");
        Duration between = Duration.between(start, end); // PT2H30M

        LocalTime t1 = LocalTime.of(9, 0);
        LocalTime t2 = LocalTime.of(17, 30);
        Duration workDay = Duration.between(t1, t2); // PT8H30M

        // ── Accessing Components ──────────────────────────────────
        Duration d = Duration.parse("PT26H30M45.123456789S");
        System.out.println(d.toDays());          // 1
        System.out.println(d.toHours());         // 26 (TOTAL hours)
        System.out.println(d.toMinutes());       // 1590 (TOTAL minutes)
        System.out.println(d.toSeconds());       // 95445 (TOTAL seconds)
        System.out.println(d.toMillis());        // 95445123 (TOTAL millis)
        System.out.println(d.toNanos());         // 95445123456789 (TOTAL nanos)

        // Java 9+ — get individual parts (not total)
        System.out.println(d.toDaysPart());      // 1
        System.out.println(d.toHoursPart());     // 2  (hours part only)
        System.out.println(d.toMinutesPart());   // 30
        System.out.println(d.toSecondsPart());   // 45
        System.out.println(d.toMillisPart());    // 123
        System.out.println(d.toNanosPart());     // 123456789

        // ── Arithmetic ────────────────────────────────────────────
        Duration d2 = Duration.ofHours(3);
        System.out.println(d.plus(d2));          // adds durations
        System.out.println(d.minus(Duration.ofMinutes(30)));
        System.out.println(d.multipliedBy(2));
        System.out.println(d.dividedBy(3));
        System.out.println(d.negated());         // flip sign
        System.out.println(d.abs());             // absolute value

        // ── Checks ────────────────────────────────────────────────
        System.out.println(Duration.ZERO.isZero());      // true
        System.out.println(Duration.ofHours(-1).isNegative()); // true

        // ── Apply to Temporal ─────────────────────────────────────
        LocalDateTime ldt = LocalDateTime.of(2024, 3, 15, 10, 0);
        System.out.println(ldt.plus(Duration.ofHours(2)));   // 2024-03-15T12:00
        System.out.println(ldt.minus(Duration.ofMinutes(30))); // 2024-03-15T09:30

        // ── Format Duration for display ───────────────────────────
        Duration elapsed = Duration.ofSeconds(3725);
        long hours   = elapsed.toHours();
        long minutes = elapsed.toMinutesPart(); // Java 9+
        long seconds = elapsed.toSecondsPart();
        System.out.printf("Elapsed: %02d:%02d:%02d%n", hours, minutes, seconds); // 01:02:05
    }
}
```

---

### 6.2 Period

Period measures **date-based amounts** — years, months, and days.

```java
import java.time.*;
import java.time.temporal.*;

public class PeriodDemo {
    public static void main(String[] args) {

        // ── Creation ──────────────────────────────────────────────
        Period twoYears     = Period.ofYears(2);
        Period threeMonths  = Period.ofMonths(3);
        Period tenDays      = Period.ofDays(10);
        Period twoWeeks     = Period.ofWeeks(2);         // stored as 14 days
        Period combined     = Period.of(1, 6, 15);       // 1 year, 6 months, 15 days
        Period parsed       = Period.parse("P1Y6M15D");

        // Between two LocalDates
        LocalDate birthday  = LocalDate.of(1990, 6, 15);
        LocalDate today     = LocalDate.of(2024, 3, 10);
        Period age          = Period.between(birthday, today);

        System.out.println(age);                // P33Y8M23D
        System.out.println(age.getYears());     // 33
        System.out.println(age.getMonths());    // 8
        System.out.println(age.getDays());      // 23
        System.out.println(age.toTotalMonths()); // 404 (total months only)

        // ── Key Difference from Duration ──────────────────────────
        // Period stores years/months/days SEPARATELY — not converted to seconds
        // Adding 1 month to Jan 31 → Feb 28/29 (not always 30/31 days)
        LocalDate jan31 = LocalDate.of(2024, 1, 31);
        System.out.println(jan31.plus(Period.ofMonths(1))); // 2024-02-29 (2024 is leap)

        // Duration would need to know the number of days in each month — it can't
        // Duration.ofDays(30) added to Jan 31 → Mar 01 (different answer!)
        System.out.println(jan31.plus(Duration.ofDays(30))); // 2024-03-01

        // ── Normalization ─────────────────────────────────────────
        Period unnormalized = Period.of(1, 15, 40); // 1y 15m 40d — unusual
        Period normalized   = unnormalized.normalized(); // converts months: 2y 3m 40d
        // NOTE: days are NOT normalized (months have variable length)
        System.out.println(normalized); // P2Y3M40D

        // ── Arithmetic ────────────────────────────────────────────
        Period p1 = Period.of(1, 3, 10);
        Period p2 = Period.of(0, 6, 20);
        System.out.println(p1.plus(p2));        // P1Y9M30D
        System.out.println(p1.minus(p2));       // P0Y9M-10D (can have negative parts!)
        System.out.println(p1.multipliedBy(2)); // P2Y6M20D
        System.out.println(p1.negated());       // P-1Y-3M-10D
        System.out.println(p1.isNegative());    // false
        System.out.println(p1.isZero());        // false

        // ── Apply to Date ─────────────────────────────────────────
        LocalDate start = LocalDate.of(2024, 1, 1);
        System.out.println(start.plus(Period.of(1, 2, 15)));  // 2025-03-16
        System.out.println(start.minus(Period.ofYears(1)));   // 2023-01-01

        // ── Age calculation with Period ───────────────────────────
        LocalDate dob = LocalDate.of(1995, 8, 20);
        LocalDate now2 = LocalDate.of(2024, 3, 15);
        Period personAge = Period.between(dob, now2);
        System.out.printf("Age: %d years, %d months, %d days%n",
            personAge.getYears(), personAge.getMonths(), personAge.getDays());
    }
}
```

---

## 7. Formatting & Parsing

### 7.1 DateTimeFormatter

```java
import java.time.*;
import java.time.format.*;

public class DateTimeFormatterDemo {
    public static void main(String[] args) {

        LocalDateTime dt = LocalDateTime.of(2024, 3, 15, 14, 30, 45);

        // ── Predefined ISO Formatters ─────────────────────────────
        System.out.println(dt.format(DateTimeFormatter.ISO_LOCAL_DATE));      // 2024-03-15
        System.out.println(dt.format(DateTimeFormatter.ISO_LOCAL_TIME));      // 14:30:45
        System.out.println(dt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)); // 2024-03-15T14:30:45
        System.out.println(dt.format(DateTimeFormatter.ISO_DATE_TIME));       // 2024-03-15T14:30:45

        Instant now = Instant.now();
        System.out.println(DateTimeFormatter.ISO_INSTANT.format(now));     // 2024-03-15T09:00:00Z

        ZonedDateTime zdt = dt.atZone(ZoneId.of("America/New_York"));
        System.out.println(DateTimeFormatter.ISO_ZONED_DATE_TIME.format(zdt));
        // 2024-03-15T14:30:45-04:00[America/New_York]

        System.out.println(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(zdt));
        // 2024-03-15T14:30:45-04:00

        System.out.println(DateTimeFormatter.RFC_1123_DATE_TIME.format(zdt));
        // Fri, 15 Mar 2024 14:30:45 -0400

        System.out.println(DateTimeFormatter.BASIC_ISO_DATE.format(dt));   // 20240315
    }
}
```

### 7.2 Custom Patterns

```java
import java.time.*;
import java.time.format.*;
import java.util.Locale;

public class CustomFormatterDemo {
    public static void main(String[] args) {

        LocalDateTime dt = LocalDateTime.of(2024, 3, 15, 14, 30, 45, 123_456_789);
        ZonedDateTime zdt = dt.atZone(ZoneId.of("Asia/Kolkata"));

        // ── Pattern Letters ───────────────────────────────────────
        //  G  — Era (AD/BC)
        //  u  — Year (e.g., 2024)           y — Year of era
        //  M  — Month number or name        L — Standalone month name
        //  d  — Day of month (1-31)
        //  E  — Day name (Mon)              e — Localized day of week
        //  H  — Hour 0-23                   h — Hour 1-12 (use with a)
        //  m  — Minute                      s — Second
        //  S  — Fraction of second          n — Nanosecond
        //  a  — AM/PM marker
        //  z  — Time zone name (IST)        Z — Offset (-0530)
        //  X  — Offset Z for UTC            x — Offset (no Z for UTC)
        //  V  — Zone ID [Asia/Kolkata]
        //  '  — Text delimiter              '' — Literal single quote

        // Number of letters matters:
        //  M   → 3       (month number)
        //  MM  → 03      (zero-padded)
        //  MMM → Mar     (abbreviated name)
        //  MMMM→ March   (full name)
        //  MMMMM→ M      (narrow name)

        // ── Common Custom Formats ─────────────────────────────────
        format(dt, "dd/MM/yyyy HH:mm:ss");              // 15/03/2024 14:30:45
        format(dt, "MM-dd-yyyy");                       // 03-15-2024
        format(dt, "MMMM dd, yyyy");                    // March 15, 2024
        format(dt, "EEE, MMM d, yyyy");                 // Fri, Mar 15, 2024
        format(dt, "EEEE, MMMM dd, yyyy h:mm a");       // Friday, March 15, 2024 2:30 PM
        format(dt, "yyyy-MM-dd'T'HH:mm:ss");            // 2024-03-15T14:30:45
        format(dt, "yyyyMMdd_HHmmss");                  // 20240315_143045 (filename safe)
        format(dt, "dd MMM yyyy");                      // 15 Mar 2024
        format(dt, "HH:mm");                            // 14:30
        format(dt, "h:mm a");                           // 2:30 PM
        format(dt, "SSS");                              // 123 (milliseconds)
        format(dt, "SSSSSSSSS");                        // 123456789 (nanoseconds)

        // Zone offset formats
        format(zdt, "yyyy-MM-dd HH:mm:ss z");           // 2024-03-15 14:30:45 IST
        format(zdt, "yyyy-MM-dd HH:mm:ss Z");           // 2024-03-15 14:30:45 +0530
        format(zdt, "yyyy-MM-dd HH:mm:ss XXX");         // 2024-03-15 14:30:45 +05:30
        format(zdt, "yyyy-MM-dd HH:mm:ss VV");          // 2024-03-15 14:30:45 Asia/Kolkata

        // ── Parsing ───────────────────────────────────────────────
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss");

        LocalDateTime parsed = LocalDateTime.parse("15/03/2024 14:30:45", fmt);
        System.out.println(parsed); // 2024-03-15T14:30:45

        LocalDate dateParsed = LocalDate.parse("March 15, 2024",
            DateTimeFormatter.ofPattern("MMMM dd, yyyy", Locale.ENGLISH));
        System.out.println(dateParsed); // 2024-03-15

        // ── Builder Pattern — fine-grained control ────────────────
        DateTimeFormatter custom = new DateTimeFormatterBuilder()
            .appendValue(ChronoField.YEAR, 4)
            .appendLiteral('-')
            .appendValue(ChronoField.MONTH_OF_YEAR, 2)
            .appendLiteral('-')
            .appendValue(ChronoField.DAY_OF_MONTH, 2)
            .optionalStart()                          // optional section
                .appendLiteral(' ')
                .appendValue(ChronoField.HOUR_OF_DAY, 2)
                .appendLiteral(':')
                .appendValue(ChronoField.MINUTE_OF_HOUR, 2)
            .optionalEnd()
            .toFormatter();

        System.out.println(custom.format(dt));                         // 2024-03-15 14:30
        System.out.println(LocalDate.parse("2024-03-15", custom));     // 2024-03-15 (no time)
        System.out.println(LocalDateTime.parse("2024-03-15 10:45", custom)); // with time
    }

    static void format(TemporalAccessor t, String pattern) {
        System.out.printf("%-35s → %s%n", pattern,
            DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH).format(t));
    }
}
```

---

### 7.3 Locale-Aware Formatting

```java
import java.time.*;
import java.time.format.*;
import java.util.Locale;

public class LocaleFormatterDemo {
    public static void main(String[] args) {

        LocalDateTime dt = LocalDateTime.of(2024, 3, 15, 14, 30);

        // FormatStyle: FULL, LONG, MEDIUM, SHORT
        Locale[] locales = {Locale.ENGLISH, Locale.FRENCH, Locale.GERMAN,
                            Locale.JAPANESE, new Locale("hi", "IN")};

        for (Locale locale : locales) {
            DateTimeFormatter f = DateTimeFormatter.ofLocalizedDateTime(
                FormatStyle.LONG, FormatStyle.SHORT).withLocale(locale);
            System.out.printf("%-10s → %s%n", locale, f.format(dt));
        }
        // ENGLISH    → March 15, 2024 at 2:30 PM
        // FRENCH     → 15 mars 2024 à 14:30
        // GERMAN     → 15. März 2024 um 14:30
        // JAPANESE   → 2024年3月15日 14:30

        // Date only
        DateTimeFormatter dateOnly = DateTimeFormatter
            .ofLocalizedDate(FormatStyle.FULL)
            .withLocale(Locale.UK);
        System.out.println(dateOnly.format(LocalDate.of(2024, 3, 15)));
        // Friday, 15 March 2024

        // Time only
        DateTimeFormatter timeOnly = DateTimeFormatter
            .ofLocalizedTime(FormatStyle.MEDIUM)
            .withLocale(Locale.US);
        System.out.println(timeOnly.format(LocalTime.of(14, 30, 45)));
        // 2:30:45 PM
    }
}
```

---

## 8. Adjusters & Queries

### 8.1 TemporalAdjusters

```java
import java.time.*;
import java.time.temporal.*;

public class TemporalAdjustersDemo {
    public static void main(String[] args) {

        LocalDate date = LocalDate.of(2024, 3, 15); // Friday, March 15, 2024

        // ── Day-of-week Adjusters ─────────────────────────────────
        System.out.println(date.with(TemporalAdjusters.next(DayOfWeek.MONDAY)));
        // 2024-03-18 (next Monday after March 15)

        System.out.println(date.with(TemporalAdjusters.previous(DayOfWeek.MONDAY)));
        // 2024-03-11 (previous Monday)

        System.out.println(date.with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY)));
        // 2024-03-15 (same day — it IS Friday)

        System.out.println(date.with(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY)));
        // 2024-03-15 (same day)

        // ── Day-of-month Adjusters ────────────────────────────────
        System.out.println(date.with(TemporalAdjusters.firstDayOfMonth()));
        // 2024-03-01

        System.out.println(date.with(TemporalAdjusters.lastDayOfMonth()));
        // 2024-03-31

        System.out.println(date.with(TemporalAdjusters.firstDayOfNextMonth()));
        // 2024-04-01

        System.out.println(date.with(TemporalAdjusters.lastDayOfYear()));
        // 2024-12-31

        System.out.println(date.with(TemporalAdjusters.firstDayOfYear()));
        // 2024-01-01

        System.out.println(date.with(TemporalAdjusters.firstDayOfNextYear()));
        // 2025-01-01

        // ── Day-of-week-in-month Adjusters ────────────────────────
        // e.g., "3rd Thursday of November" (US Thanksgiving)
        System.out.println(LocalDate.of(2024, 11, 1)
            .with(TemporalAdjusters.dayOfWeekInMonth(4, DayOfWeek.THURSDAY)));
        // 2024-11-28 — 4th Thursday of November 2024

        // Last occurrence of a day in month
        System.out.println(date.with(TemporalAdjusters.lastInMonth(DayOfWeek.FRIDAY)));
        // 2024-03-29 — last Friday of March 2024
    }
}
```

---

### 8.2 Custom TemporalAdjuster

```java
import java.time.*;
import java.time.temporal.*;

public class CustomAdjusterDemo {

    // Adjuster: next working day (Mon-Fri, skip weekends)
    static TemporalAdjuster nextWorkingDay() {
        return temporal -> {
            DayOfWeek dow = DayOfWeek.of(temporal.get(ChronoField.DAY_OF_WEEK));
            int daysToAdd = switch (dow) {
                case FRIDAY   -> 3;  // Fri → Mon
                case SATURDAY -> 2;  // Sat → Mon
                default       -> 1;  // add 1 day
            };
            return temporal.plus(daysToAdd, ChronoUnit.DAYS);
        };
    }

    // Adjuster: next quarter start (Jan 1, Apr 1, Jul 1, Oct 1)
    static TemporalAdjuster nextQuarterStart() {
        return temporal -> {
            int month = temporal.get(ChronoField.MONTH_OF_YEAR);
            int nextQuarterMonth = ((month - 1) / 3 + 1) * 3 + 1;
            int yearAdjust = 0;
            if (nextQuarterMonth > 12) { nextQuarterMonth -= 12; yearAdjust = 1; }
            return temporal
                .with(ChronoField.MONTH_OF_YEAR, nextQuarterMonth)
                .with(ChronoField.DAY_OF_MONTH, 1)
                .plus(yearAdjust, ChronoUnit.YEARS);
        };
    }

    // Adjuster: N-th occurrence of a weekday from today
    static TemporalAdjuster nthOccurrenceFromNow(int n, DayOfWeek day) {
        return TemporalAdjusters.dayOfWeekInMonth(n, day);
    }

    public static void main(String[] args) {
        LocalDate friday   = LocalDate.of(2024, 3, 15); // Friday
        LocalDate saturday = LocalDate.of(2024, 3, 16); // Saturday

        System.out.println(friday.with(nextWorkingDay()));   // 2024-03-18 (Monday)
        System.out.println(saturday.with(nextWorkingDay())); // 2024-03-18 (Monday)
        System.out.println(LocalDate.of(2024, 3, 15).with(nextWorkingDay())); // 2024-03-18

        LocalDate inQ1 = LocalDate.of(2024, 2, 20);
        System.out.println(inQ1.with(nextQuarterStart())); // 2024-04-01
        LocalDate inQ4 = LocalDate.of(2024, 11, 15);
        System.out.println(inQ4.with(nextQuarterStart())); // 2025-01-01
    }
}
```

---

### 8.3 TemporalQuery

```java
import java.time.*;
import java.time.temporal.*;

public class TemporalQueryDemo {

    // Custom query: is this a weekend?
    static final TemporalQuery<Boolean> IS_WEEKEND =
        temporal -> {
            DayOfWeek dow = DayOfWeek.from(temporal);
            return dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY;
        };

    // Custom query: what quarter is this?
    static final TemporalQuery<Integer> QUARTER =
        temporal -> (temporal.get(ChronoField.MONTH_OF_YEAR) - 1) / 3 + 1;

    // Custom query: days until year end
    static final TemporalQuery<Long> DAYS_UNTIL_YEAR_END =
        temporal -> {
            LocalDate date = LocalDate.from(temporal);
            LocalDate yearEnd = date.with(TemporalAdjusters.lastDayOfYear());
            return ChronoUnit.DAYS.between(date, yearEnd);
        };

    public static void main(String[] args) {
        LocalDate saturday = LocalDate.of(2024, 3, 16);
        LocalDate monday   = LocalDate.of(2024, 3, 18);

        System.out.println(saturday.query(IS_WEEKEND)); // true
        System.out.println(monday.query(IS_WEEKEND));   // false

        LocalDate febDate = LocalDate.of(2024, 2, 15);
        LocalDate octDate = LocalDate.of(2024, 10, 1);
        System.out.println(febDate.query(QUARTER));     // 1
        System.out.println(octDate.query(QUARTER));     // 4

        System.out.println(LocalDate.of(2024, 3, 15).query(DAYS_UNTIL_YEAR_END)); // 291

        // Built-in queries
        LocalTime time = LocalDate.of(2024, 3, 15).query(TemporalQueries.localDate())
                                                  .atStartOfDay()
                                                  .toLocalTime();
        ZoneId zone = ZonedDateTime.now().query(TemporalQueries.zone());
        ZoneOffset offset = ZonedDateTime.now().query(TemporalQueries.offset());
    }
}
```

---

## 9. Chronologies & Calendar Systems

```java
import java.time.*;
import java.time.chrono.*;
import java.time.format.*;
import java.util.Locale;

public class ChronologyDemo {
    public static void main(String[] args) {

        LocalDate isoDate = LocalDate.of(2024, 3, 15);

        // ── Japanese Imperial Calendar ────────────────────────────
        JapaneseDate japaneseDate = JapaneseDate.from(isoDate);
        System.out.println(japaneseDate);
        // Japanese Reiwa 6-03-15

        System.out.println(japaneseDate.getEra());     // REIWA
        System.out.println(JapaneseEra.values());      // MEIJI, TAISHO, SHOWA, HEISEI, REIWA

        DateTimeFormatter jpFmt = DateTimeFormatter
            .ofPattern("Gy年M月d日", new Locale("ja", "JP", "JP"));
        System.out.println(jpFmt.format(isoDate)); // 令和6年3月15日

        // ── Hijri Calendar (Islamic) ──────────────────────────────
        HijrahDate hijrahDate = HijrahDate.from(isoDate);
        System.out.println(hijrahDate);
        // Hijrah-umalqura AH 1445-09-05

        System.out.println(hijrahDate.get(ChronoField.YEAR));         // 1445
        System.out.println(hijrahDate.get(ChronoField.MONTH_OF_YEAR)); // 9

        // Find next Ramadan start
        HijrahDate ramadanStart = HijrahChronology.INSTANCE
            .date(1446, 9, 1); // 9th month = Ramadan
        System.out.println(LocalDate.from(ramadanStart)); // ISO equivalent

        // ── Thai Buddhist Calendar ────────────────────────────────
        ThaiBuddhistDate thaiDate = ThaiBuddhistDate.from(isoDate);
        System.out.println(thaiDate);
        // ThaiBuddhist BE 2567-03-15 (BE = Buddhist Era, year + 543)

        // ── Minguo Calendar (Taiwan) ──────────────────────────────
        MinguoDate minguoDate = MinguoDate.from(isoDate);
        System.out.println(minguoDate);
        // Minguo ROC 113-03-15 (ROC = Republic of China, year - 1911)

        // ── Generic ChronoLocalDate ───────────────────────────────
        Chronology hijrahChronology = Chronology.of("Islamic");
        ChronoLocalDate genericDate = hijrahChronology.dateNow();
        System.out.println(genericDate.getChronology().getCalendarType()); // islamic-umalqura

        // ── Convert back to ISO ───────────────────────────────────
        LocalDate backToISO = LocalDate.from(japaneseDate); // 2024-03-15
        System.out.println(backToISO);
    }
}
```

---

## 10. Ranges & Boundaries

```java
import java.time.*;
import java.time.temporal.*;

public class RangesDemo {
    public static void main(String[] args) {

        // ── ValueRange — valid range for a field ──────────────────
        LocalDate leapYear    = LocalDate.of(2024, 2, 1);
        LocalDate nonLeapYear = LocalDate.of(2023, 2, 1);

        ValueRange leapFebRange    = leapYear.range(ChronoField.DAY_OF_MONTH);
        ValueRange nonLeapFebRange = nonLeapYear.range(ChronoField.DAY_OF_MONTH);

        System.out.println(leapFebRange);    // 1 - 29
        System.out.println(nonLeapFebRange); // 1 - 28

        // All fields
        System.out.println(leapYear.range(ChronoField.DAY_OF_YEAR));   // 1 - 366
        System.out.println(leapYear.range(ChronoField.MONTH_OF_YEAR)); // 1 - 12
        System.out.println(LocalTime.now().range(ChronoField.HOUR_OF_DAY)); // 0 - 23

        // Check if value is in range
        System.out.println(leapFebRange.isValidIntValue(29));  // true
        System.out.println(leapFebRange.isValidIntValue(30));  // false
        System.out.println(leapFebRange.getMinimum());         // 1
        System.out.println(leapFebRange.getMaximum());         // 29

        // ── Generating Date Sequences ─────────────────────────────
        // All dates in a month
        LocalDate startOfMonth = LocalDate.of(2024, 3, 1);
        LocalDate endOfMonth   = startOfMonth.with(TemporalAdjusters.lastDayOfMonth());

        System.out.println("All Fridays in March 2024:");
        startOfMonth.datesUntil(endOfMonth.plusDays(1)) // Java 9+
                    .filter(d -> d.getDayOfWeek() == DayOfWeek.FRIDAY)
                    .forEach(System.out::println);
        // 2024-03-01, 2024-03-08, 2024-03-15, 2024-03-22, 2024-03-29

        // Generate with step (Java 9+)
        System.out.println("Every Monday:");
        LocalDate nextMonday = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        nextMonday.datesUntil(nextMonday.plusWeeks(5), Period.ofWeeks(1))
                  .forEach(System.out::println);
    }
}
```

---

## 11. Legacy API Bridge

```java
import java.time.*;
import java.time.temporal.*;
import java.util.*;
import java.sql.*;

public class LegacyBridgeDemo {
    public static void main(String[] args) {

        // ── java.util.Date ◄──────────────────────────────────────►
        // Date → Instant
        java.util.Date utilDate = new java.util.Date();
        Instant fromDate = utilDate.toInstant();

        // Instant → Date
        java.util.Date backToDate = java.util.Date.from(Instant.now());

        // Date → LocalDateTime (requires ZoneId)
        LocalDateTime ldt = utilDate.toInstant()
                                    .atZone(ZoneId.systemDefault())
                                    .toLocalDateTime();

        // LocalDateTime → Date
        java.util.Date fromLDT = java.util.Date.from(
            LocalDateTime.of(2024, 3, 15, 14, 30)
                         .atZone(ZoneId.systemDefault())
                         .toInstant()
        );

        // ── java.util.Calendar ◄──────────────────────────────────►
        Calendar cal = Calendar.getInstance();

        // Calendar → ZonedDateTime
        ZonedDateTime zdtFromCal = cal.toInstant()
                                      .atZone(cal.getTimeZone().toZoneId());

        // ZonedDateTime → Calendar
        ZonedDateTime zdt = ZonedDateTime.now();
        Calendar calFromZdt = GregorianCalendar.from(zdt);

        // GregorianCalendar specifically
        GregorianCalendar gc = new GregorianCalendar();
        ZonedDateTime zdtFromGC = gc.toZonedDateTime();
        GregorianCalendar gcFromZdt = GregorianCalendar.from(zdt);

        // ── java.util.TimeZone ◄──────────────────────────────────►
        TimeZone tz = TimeZone.getTimeZone("America/New_York");
        ZoneId zoneId = tz.toZoneId();
        TimeZone back = TimeZone.getTimeZone(zoneId);

        // ── java.sql Types ◄──────────────────────────────────────►
        // java.sql.Date (date only — no time)
        java.sql.Date sqlDate = java.sql.Date.valueOf(LocalDate.of(2024, 3, 15));
        LocalDate localDateFromSql = sqlDate.toLocalDate();

        // java.sql.Time (time only)
        java.sql.Time sqlTime = java.sql.Time.valueOf(LocalTime.of(14, 30, 45));
        LocalTime localTimeFromSql = sqlTime.toLocalTime();

        // java.sql.Timestamp (date + time + nanos)
        java.sql.Timestamp sqlTs = java.sql.Timestamp.valueOf(
            LocalDateTime.of(2024, 3, 15, 14, 30, 45));
        LocalDateTime ldtFromSql = sqlTs.toLocalDateTime();

        // Timestamp from Instant
        java.sql.Timestamp tsFromInstant = java.sql.Timestamp.from(Instant.now());
        Instant instantFromTs = tsFromInstant.toInstant();

        // ── Conversion Summary Table ──────────────────────────────
        // java.util.Date     → Instant:          date.toInstant()
        // Instant            → java.util.Date:   Date.from(instant)
        // Calendar           → Instant:          cal.toInstant()
        // GregorianCalendar  → ZonedDateTime:    gc.toZonedDateTime()
        // ZonedDateTime      → GregorianCalendar: GregorianCalendar.from(zdt)
        // TimeZone           → ZoneId:           tz.toZoneId()
        // ZoneId             → TimeZone:         TimeZone.getTimeZone(zoneId)
        // java.sql.Date      → LocalDate:        sqlDate.toLocalDate()
        // LocalDate          → java.sql.Date:    java.sql.Date.valueOf(localDate)
        // java.sql.Timestamp → LocalDateTime:    ts.toLocalDateTime()
        // LocalDateTime      → java.sql.Timestamp: Timestamp.valueOf(ldt)
    }
}
```

---

## 12. Java 9+ Enhancements

```java
import java.time.*;
import java.time.temporal.*;
import java.util.stream.*;

public class Java9PlusDemo {
    public static void main(String[] args) {

        // ── Java 9: Duration.to*Part() methods ───────────────────
        Duration d = Duration.parse("PT26H30M45.123S");
        System.out.println(d.toDaysPart());     // 1
        System.out.println(d.toHoursPart());    // 2 (hours component only)
        System.out.println(d.toMinutesPart());  // 30
        System.out.println(d.toSecondsPart());  // 45
        System.out.println(d.toMillisPart());   // 123

        // ── Java 9: LocalDate.datesUntil() ───────────────────────
        LocalDate start = LocalDate.of(2024, 1, 1);
        LocalDate end   = LocalDate.of(2024, 1, 8);

        // Daily stream
        start.datesUntil(end).forEach(System.out::println); // Jan 1-7

        // Weekly stream
        start.datesUntil(LocalDate.of(2024, 3, 1), Period.ofWeeks(1))
             .forEach(System.out::println); // every Monday

        // ── Java 11: Files.readString / writeString ───────────────
        // (in java.nio.file, but related to modern java)

        // ── Java 12: LocalDate.of() with ISO week date ───────────
        // Already existed, but Java 12 clarified docs

        // ── Java 16: Period.of from ChronoPeriod ─────────────────

        // ── Java 17+: Pattern matching with date-time ─────────────
        Object obj = LocalDate.now();
        if (obj instanceof LocalDate date) {
            System.out.println("It's a date: " + date.getYear());
        }

        // ── Streams from date ranges ──────────────────────────────
        // Count Mondays in 2024
        long mondays = LocalDate.of(2024, 1, 1)
            .datesUntil(LocalDate.of(2025, 1, 1))
            .filter(d2 -> d2.getDayOfWeek() == DayOfWeek.MONDAY)
            .count();
        System.out.println("Mondays in 2024: " + mondays); // 52

        // Sum of all day-of-month values in March 2024
        int sum = LocalDate.of(2024, 3, 1)
            .datesUntil(LocalDate.of(2024, 4, 1))
            .mapToInt(LocalDate::getDayOfMonth)
            .sum();
        System.out.println("Sum of days: " + sum); // 496 (1+2+...+31)
    }
}
```

---

## 13. Real-World Patterns & Recipes

```java
import java.time.*;
import java.time.format.*;
import java.time.temporal.*;
import java.util.*;
import java.util.stream.*;

public class RealWorldPatterns {

    // ── Recipe 1: Age Calculator ──────────────────────────────────
    public static String calculateAge(LocalDate birthDate) {
        Period age = Period.between(birthDate, LocalDate.now());
        return String.format("%d years, %d months, %d days",
            age.getYears(), age.getMonths(), age.getDays());
    }

    // ── Recipe 2: Business Days Between Two Dates ─────────────────
    public static long businessDaysBetween(LocalDate start, LocalDate end) {
        return start.datesUntil(end)
                    .filter(d -> d.getDayOfWeek() != DayOfWeek.SATURDAY
                              && d.getDayOfWeek() != DayOfWeek.SUNDAY)
                    .count();
    }

    // ── Recipe 3: Add Business Days ───────────────────────────────
    public static LocalDate addBusinessDays(LocalDate date, int days) {
        int added = 0;
        LocalDate result = date;
        while (added < days) {
            result = result.plusDays(1);
            if (result.getDayOfWeek() != DayOfWeek.SATURDAY &&
                result.getDayOfWeek() != DayOfWeek.SUNDAY) {
                added++;
            }
        }
        return result;
    }

    // ── Recipe 4: Meeting Scheduler (Multi-Timezone) ──────────────
    public static void scheduleMeeting() {
        ZonedDateTime nyTime  = ZonedDateTime.of(2024, 3, 20, 10, 0, 0, 0,
                                                  ZoneId.of("America/New_York"));
        ZonedDateTime london  = nyTime.withZoneSameInstant(ZoneId.of("Europe/London"));
        ZonedDateTime kolkata = nyTime.withZoneSameInstant(ZoneId.of("Asia/Kolkata"));
        ZonedDateTime tokyo   = nyTime.withZoneSameInstant(ZoneId.of("Asia/Tokyo"));

        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("EEE MMM dd, HH:mm z");
        System.out.println("New York: " + fmt.format(nyTime));
        System.out.println("London:   " + fmt.format(london));
        System.out.println("Kolkata:  " + fmt.format(kolkata));
        System.out.println("Tokyo:    " + fmt.format(tokyo));
    }

    // ── Recipe 5: Recurring Event Generator ───────────────────────
    public static List<LocalDate> monthlyReminders(LocalDate start, int months) {
        return IntStream.rangeClosed(0, months)
                        .mapToObj(i -> start.plusMonths(i))
                        .map(d -> d.with(TemporalAdjusters.lastDayOfMonth()))
                        .collect(Collectors.toList());
    }

    // ── Recipe 6: Parse Multiple Date Formats ─────────────────────
    static final List<DateTimeFormatter> PARSERS = List.of(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("MM/dd/yyyy"),
        DateTimeFormatter.ofPattern("dd-MM-yyyy"),
        DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH),
        DateTimeFormatter.ofPattern("MMM dd yyyy", Locale.ENGLISH)
    );

    public static Optional<LocalDate> parseFlexible(String input) {
        return PARSERS.stream()
                      .map(fmt -> {
                          try { return LocalDate.parse(input.trim(), fmt); }
                          catch (DateTimeParseException e) { return null; }
                      })
                      .filter(Objects::nonNull)
                      .findFirst();
    }

    // ── Recipe 7: Fiscal Year ─────────────────────────────────────
    public static int getFiscalYear(LocalDate date, Month fiscalYearStart) {
        // If fiscal year starts in April, April 2024 → FY 2024-25 → return 2024
        if (date.getMonth().getValue() >= fiscalYearStart.getValue()) {
            return date.getYear();
        }
        return date.getYear() - 1;
    }

    public static LocalDate getFiscalYearStart(int fy, Month fiscalYearStart) {
        return LocalDate.of(fy, fiscalYearStart, 1);
    }

    // ── Recipe 8: Token Expiry Checker ────────────────────────────
    public static boolean isTokenExpired(Instant issuedAt, Duration validity) {
        return Instant.now().isAfter(issuedAt.plus(validity));
    }

    public static Duration timeUntilExpiry(Instant issuedAt, Duration validity) {
        Instant expiry = issuedAt.plus(validity);
        Duration remaining = Duration.between(Instant.now(), expiry);
        return remaining.isNegative() ? Duration.ZERO : remaining;
    }

    // ── Recipe 9: Date Range Overlap ──────────────────────────────
    public static boolean rangesOverlap(LocalDate start1, LocalDate end1,
                                        LocalDate start2, LocalDate end2) {
        return !end1.isBefore(start2) && !end2.isBefore(start1);
    }

    // ── Recipe 10: Human-Readable Relative Time ───────────────────
    public static String timeAgo(Instant past) {
        Duration gap = Duration.between(past, Instant.now());
        if (gap.toSeconds() < 60)   return gap.toSeconds() + " seconds ago";
        if (gap.toMinutes() < 60)   return gap.toMinutes() + " minutes ago";
        if (gap.toHours() < 24)     return gap.toHours() + " hours ago";
        if (gap.toDays() < 7)       return gap.toDays() + " days ago";
        if (gap.toDays() < 30)      return (gap.toDays() / 7) + " weeks ago";
        if (gap.toDays() < 365)     return (gap.toDays() / 30) + " months ago";
        return (gap.toDays() / 365) + " years ago";
    }

    public static void main(String[] args) {
        System.out.println(calculateAge(LocalDate.of(1990, 6, 15)));
        System.out.println("Business days (Mon–Fri): " +
            businessDaysBetween(LocalDate.of(2024, 3, 1), LocalDate.of(2024, 3, 31)));

        scheduleMeeting();

        System.out.println("Monthly reminders: " +
            monthlyReminders(LocalDate.of(2024, 1, 1), 5));

        System.out.println(parseFlexible("March 15, 2024")); // Optional[2024-03-15]
        System.out.println(parseFlexible("15-03-2024"));     // Optional[2024-03-15]
        System.out.println(parseFlexible("invalid"));        // Optional.empty

        System.out.println("FY (India, April start): " +
            getFiscalYear(LocalDate.of(2024, 3, 31), Month.APRIL)); // 2023

        System.out.println(timeAgo(Instant.now().minusSeconds(7200))); // 2 hours ago
        System.out.println(rangesOverlap(
            LocalDate.of(2024,1,1), LocalDate.of(2024,6,30),
            LocalDate.of(2024,5,1), LocalDate.of(2024,12,31))); // true
    }
}
```

---

## 14. Common Pitfalls

### ❌ Pitfall 1: Ignoring immutability
```java
// BAD — result is discarded!
LocalDate date = LocalDate.of(2024, 1, 1);
date.plusDays(10); // does nothing! Returns new object
System.out.println(date); // still 2024-01-01

// GOOD — capture the result
LocalDate newDate = date.plusDays(10);
System.out.println(newDate); // 2024-01-11
```

### ❌ Pitfall 2: Using LocalDateTime for timestamps
```java
// BAD — no timezone = ambiguous for cross-region systems
LocalDateTime orderTime = LocalDateTime.now();
// "2024-03-15T14:30:00" — which timezone? Unusable across regions

// GOOD — use Instant for timestamps
Instant orderTime = Instant.now(); // always UTC, unambiguous

// Or ZonedDateTime if you need the human-readable display zone
ZonedDateTime orderTime2 = ZonedDateTime.now(ZoneId.of("Asia/Kolkata"));
```

### ❌ Pitfall 3: ZonedDateTime arithmetic ignoring DST
```java
ZoneId nyZone = ZoneId.of("America/New_York");
ZonedDateTime beforeDST = ZonedDateTime.of(2024, 3, 9, 10, 0, 0, 0, nyZone); // -05:00

// plusDays = "same clock time tomorrow" (may not be 24 hours)
ZonedDateTime tomorrowSameTime = beforeDST.plusDays(1);
// On DST spring-forward: 23 actual hours elapsed!

// plus(Duration) = exactly 24 hours of absolute time
ZonedDateTime exactly24Hours = beforeDST.plus(Duration.ofDays(1));
// May show 11:00 AM due to DST gap
```

### ❌ Pitfall 4: Comparing with == instead of equals/isEqual
```java
LocalDate a = LocalDate.of(2024, 3, 15);
LocalDate b = LocalDate.of(2024, 3, 15);

System.out.println(a == b);      // FALSE — different object references
System.out.println(a.equals(b)); // TRUE  — value comparison
System.out.println(a.isEqual(b)); // TRUE  — semantic equality (same instant)
```

### ❌ Pitfall 5: Mixing Period and Duration
```java
// Period is for date math, Duration is for time math
// Applying Duration.ofDays(1) to cross a DST boundary is different from Period.ofDays(1)

LocalDate date = LocalDate.of(2024, 3, 15);
// WRONG — Duration works on time units, not calendar
// date.plus(Duration.ofDays(1)); // compiles but duration has no date fields
// Actually works but confusing — prefer Period for LocalDate

// RIGHT
LocalDate tomorrow = date.plus(Period.ofDays(1)); // clearly date-based
// or simply
LocalDate tomorrow2 = date.plusDays(1);
```

### ❌ Pitfall 6: DateTimeFormatter not reused (performance)
```java
// BAD — formatter created on every call (wasteful but not thread-unsafe)
public String format(LocalDate date) {
    return DateTimeFormatter.ofPattern("dd/MM/yyyy").format(date); // new object each time
}

// GOOD — static final formatter (DateTimeFormatter is thread-safe and immutable)
private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

public String format(LocalDate date) {
    return FMT.format(date);
}
```

### ❌ Pitfall 7: Assuming Month ordinal
```java
// java.util.Calendar — January = 0 (bad old API)
calendar.set(Calendar.MONTH, 0); // January

// java.time — January = 1 (correct)
LocalDate.of(2024, 1, 15);              // January, month value = 1
LocalDate.of(2024, Month.JANUARY, 15); // even clearer with enum
```

### ❌ Pitfall 8: Not handling DateTimeParseException
```java
// BAD — crashes on bad input
LocalDate date = LocalDate.parse(userInput);

// GOOD — handle parse failures
try {
    LocalDate date = LocalDate.parse(userInput,
        DateTimeFormatter.ofPattern("dd/MM/yyyy"));
} catch (DateTimeParseException e) {
    System.out.println("Invalid date: " + e.getParsedString()
        + " at index " + e.getErrorIndex());
}
```

---

## 15. Quick Reference Cheat Sheet

### Class Selection Guide

```
What do you need?                    → Use
────────────────────────────────────────────────────────────────────
Date only (no time, no zone)        → LocalDate
Time only (no date, no zone)        → LocalTime
Date + time (no zone)               → LocalDateTime
Date + time + full timezone (DST)   → ZonedDateTime
Date + time + fixed offset          → OffsetDateTime
UTC machine timestamp               → Instant
Date-based amount (1 year 3 months) → Period
Time-based amount (2h 30m 15s)      → Duration
Custom time source / testing        → Clock
```

### Key Methods Summary

```java
// Creation
LocalDate.now()                     // today
LocalDate.of(2024, 3, 15)          // specific
LocalDate.parse("2024-03-15")       // from string

// Arithmetic (all return new objects)
date.plusDays(n)      date.minusDays(n)
date.plusMonths(n)    date.minusMonths(n)
date.plusYears(n)     date.minusYears(n)
date.plus(period)     date.minus(period)

// Modification
date.withYear(2025)
date.withMonth(6)
date.withDayOfMonth(1)
date.with(TemporalAdjusters.lastDayOfMonth())

// Comparison
date.isBefore(other)
date.isAfter(other)
date.isEqual(other)

// Extraction
date.getYear()        // 2024
date.getMonthValue()  // 3
date.getDayOfMonth()  // 15
date.getDayOfWeek()   // DayOfWeek.FRIDAY

// Between
Period.between(date1, date2)
Duration.between(time1, time2)
ChronoUnit.DAYS.between(date1, date2)

// Formatting
date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
LocalDate.parse("15/03/2024", DateTimeFormatter.ofPattern("dd/MM/yyyy"))

// Zone conversion
localDateTime.atZone(ZoneId.of("Asia/Kolkata"))
zonedDateTime.withZoneSameInstant(ZoneId.of("UTC"))
instant.atZone(ZoneId.systemDefault()).toLocalDateTime()
```

### ISO 8601 Format Reference

```
Date:            2024-03-15
Time:            14:30:45
DateTime:        2024-03-15T14:30:45
With offset:     2024-03-15T14:30:45+05:30
With UTC:        2024-03-15T09:00:00Z
With zone:       2024-03-15T14:30:45+05:30[Asia/Kolkata]
Duration:        PT2H30M15S   (2 hours, 30 minutes, 15 seconds)
Period:          P1Y6M10D     (1 year, 6 months, 10 days)
Combined:        P1DT2H30M    (1 day, 2 hours, 30 minutes)
```

---

*Last updated: March 2026 | Covers Java 8 through Java 21*
