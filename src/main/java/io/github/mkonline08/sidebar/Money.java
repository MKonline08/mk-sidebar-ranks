package io.github.mkonline08.sidebar;

import java.math.BigDecimal;
import java.util.Locale;

/** Integer cents: no rounding drift and no negative, infinite or exponential amounts. */
final class Money {
    static final long LIMIT=9_000_000_000_000L;
    private Money() {}
    static long parse(String text) {
        if(!text.matches("[0-9]{1,11}(\\.[0-9]{1,2})?"))throw new IllegalArgumentException("Use a positive amount, such as 50 or 50.25.");
        long value=new BigDecimal(text).movePointRight(2).longValueExact();
        if(value<=0||value>LIMIT)throw new IllegalArgumentException("Amount is outside the supported range.");
        return value;
    }
    static String format(long cents){return String.format(Locale.ROOT,"$%,d.%02d",cents/100,Math.abs(cents%100));}
}
