package com.psiog.perfinsight.common;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/** Inclusive date range used everywhere for reporting and scoring. */
public record Period(LocalDate start, LocalDate end) {

    public Period {
        if (start == null || end == null) throw new BadRequestException("Period start and end are required");
        if (end.isBefore(start)) throw new BadRequestException("Period end must be on/after start");
    }

    public static Period of(LocalDate start, LocalDate end) {
        return new Period(start, end);
    }

    public Instant startInstant() {
        return start.atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    /** Exclusive upper bound. */
    public Instant endInstantExclusive() {
        return end.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
    }

    public boolean contains(LocalDate d) {
        return !d.isBefore(start) && !d.isAfter(end);
    }

    public Period intersect(LocalDate otherStart, LocalDate otherEnd) {
        LocalDate s = otherStart == null || otherStart.isBefore(start) ? start : otherStart;
        LocalDate e = otherEnd == null || otherEnd.isAfter(end) ? end : otherEnd;
        return e.isBefore(s) ? null : new Period(s, e);
    }

    public int workingDays() {
        int n = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            if (isWorkingDay(d)) n++;
        }
        return n;
    }

    public static boolean isWorkingDay(LocalDate d) {
        return d.getDayOfWeek() != DayOfWeek.SATURDAY && d.getDayOfWeek() != DayOfWeek.SUNDAY;
    }
}
