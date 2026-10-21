package simpledb.query;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;

/**
 * The class that denotes values stored in the database.
 * @author Edward Sciore
 */
public class Constant implements Comparable<Constant> {
   private Integer ival = null;
   private String  sval = null;
   private Long tval = null;
   private static final DateTimeFormatter TIME_FORMAT = new DateTimeFormatterBuilder()
         .appendPattern("HH:mm:ss")
         .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 1, 6, true).optionalEnd()
         .toFormatter().withResolverStyle(ResolverStyle.STRICT);
   
   public Constant(Integer ival) {
      this.ival = ival;
   }
   
   public Constant(String sval) {
      this.sval = sval;
   }

   private Constant(long microsSinceMidnight, boolean timeValue) {
      if (microsSinceMidnight < 0 || microsSinceMidnight >= 86400000000L)
         throw new IllegalArgumentException("TIME is outside the day: " + microsSinceMidnight);
      this.tval = microsSinceMidnight;
   }

   /** Construct a TIME value from microseconds since midnight. */
   public static Constant fromTimeMicros(long microsSinceMidnight) {
      return new Constant(microsSinceMidnight, true);
   }

   public static Constant time(String text) {
      try {
         LocalTime time = LocalTime.parse(text, TIME_FORMAT);
         if (time.getNano() % 1000 != 0)
            throw new IllegalArgumentException("TIME precision exceeds microseconds: " + text);
         return fromTimeMicros(time.toNanoOfDay() / 1000);
      } catch (DateTimeParseException e) {
         throw new IllegalArgumentException("Invalid TIME literal: " + text, e);
      }
   }
   
   public int asInt() {
      return ival;
   }
   
   public String asString() {
      return sval;
   }

   public long asTimeMicros() {
      return tval;
   }

   public LocalTime asLocalTime() {
      return LocalTime.ofNanoOfDay(tval * 1000);
   }

   public boolean isTime() { return tval != null; }

   public int type() {
      if (tval != null) return java.sql.Types.TIME;
      return ival != null ? java.sql.Types.INTEGER : java.sql.Types.VARCHAR;
   }
   
   public boolean equals(Object obj) {
      if (!(obj instanceof Constant)) return false;
      Constant c = (Constant) obj;
      if (ival != null) return ival.equals(c.ival);
      if (tval != null) return tval.equals(c.tval);
      return sval != null && sval.equals(c.sval);
   }
   
   public int compareTo(Constant c) {
      if (ival != null && c.ival != null) return ival.compareTo(c.ival);
      if (tval != null && c.tval != null) return tval.compareTo(c.tval);
      if (sval != null && c.sval != null) return sval.compareTo(c.sval);
      throw new IllegalArgumentException("Cannot compare constants of different types");
   }
   
   public int hashCode() {
      return (ival != null) ? ival.hashCode() : (tval != null ? tval.hashCode() : sval.hashCode());
   }
   
   public String toString() {
      if (ival != null) return ival.toString();
      if (tval == null) return sval.toString();
      LocalTime time = asLocalTime();
      return time.getNano() == 0
            ? DateTimeFormatter.ofPattern("HH:mm:ss").format(time)
            : TIME_FORMAT.format(time);
   }   
}
