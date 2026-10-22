package simpledb.jdbc.embedded;

import java.sql.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.util.Locale;
import simpledb.record.Schema;
import simpledb.query.Scan;
import simpledb.query.Constant;
import simpledb.plan.Plan;
import simpledb.jdbc.ResultSetAdapter;

/**
 * The embedded implementation of ResultSet.
 * @author Edward Sciore
 */
public class EmbeddedResultSet extends ResultSetAdapter {
   private Scan s;
   private Schema sch;
   private EmbeddedConnection conn;

   /**
    * Creates a Scan object from the specified plan.
    * @param plan the query plan
    * @param conn the connection
    * @throws RemoteException
    */
   public EmbeddedResultSet(Plan plan, EmbeddedConnection conn) throws SQLException {
      s = plan.open();
      sch = plan.schema();
      this.conn = conn;
   }

   /**
    * Moves to the next record in the result set,
    * by moving to the next record in the saved scan.
    */
   public boolean next() throws SQLException {
      try {
         return s.next();
      }
      catch(RuntimeException e) {
         conn.rollback();
         throw new SQLException(e);
      }
   }

   /**
    * Returns the integer value of the specified field,
    * by returning the corresponding value on the saved scan.
    */
   public int getInt(String fldname) throws SQLException {
      String name = columnName(fldname);
      if (sch.type(name) == Types.DECIMAL) {
         try { return getBigDecimal(name).setScale(0, RoundingMode.DOWN).intValueExact(); }
         catch (ArithmeticException e) { throw new SQLException("Integer conversion overflow: " + name, e); }
      }
      try {
         return s.getInt(name);
      }
      catch(RuntimeException e) {
         conn.rollback();
         throw new SQLException(e);
      }
   }

   /**
    * Returns the integer value of the specified field,
    * by returning the corresponding value on the saved scan.
    */
   public String getString(String fldname) throws SQLException {
      String name = columnName(fldname);
      try {
         if (sch.type(name) == Types.TIME || sch.type(name) == Types.DECIMAL)
            return s.getVal(name).toString();
         return s.getString(name);
      }
      catch(RuntimeException e) {
         conn.rollback();
         throw new SQLException(e);
      }
   }

   public Time getTime(String columnLabel) throws SQLException {
      LocalTime value = getObject(columnLabel, LocalTime.class);
      Time legacy = Time.valueOf(value);
      legacy.setTime(legacy.getTime() + value.getNano() / 1000000);
      return legacy;
   }

   public int getInt(int index) throws SQLException { return getInt(columnName(index)); }

   public String getString(int index) throws SQLException { return getString(columnName(index)); }

   public BigDecimal getBigDecimal(String label) throws SQLException {
      String name = columnName(label);
      int type = sch.type(name);
      if (type != Types.DECIMAL && type != Types.INTEGER)
         throw new SQLException("Column is not numeric: " + label);
      Constant value = columnValue(name);
      return type == Types.DECIMAL ? value.asDecimal() : BigDecimal.valueOf(value.asInt());
   }

   public BigDecimal getBigDecimal(int index) throws SQLException {
      return getBigDecimal(columnName(index));
   }

   @Deprecated
   public BigDecimal getBigDecimal(String label, int scale) throws SQLException {
      return getBigDecimal(label).setScale(scale, RoundingMode.HALF_UP);
   }

   @Deprecated
   public BigDecimal getBigDecimal(int index, int scale) throws SQLException {
      return getBigDecimal(columnName(index), scale);
   }

   public double getDouble(String label) throws SQLException { return getBigDecimal(label).doubleValue(); }

   public double getDouble(int index) throws SQLException { return getDouble(columnName(index)); }

   public Time getTime(int columnIndex) throws SQLException {
      return getTime(columnName(columnIndex));
   }

   public <T> T getObject(String columnLabel, Class<T> type) throws SQLException {
      if (type == null) throw new SQLException("Requested Java type cannot be null");
      String name = columnName(columnLabel);
      if (sch.type(name) == java.sql.Types.TIME && type == LocalTime.class)
         return type.cast(columnValue(name).asLocalTime());
      if (type == BigDecimal.class) return type.cast(getBigDecimal(name));
      if (type == Time.class) return type.cast(getTime(name));
      if (type == String.class) return type.cast(getString(name));
      throw new SQLException("Unsupported typed object request: " + type.getName());
   }

   public <T> T getObject(int columnIndex, Class<T> type) throws SQLException {
      return getObject(columnName(columnIndex), type);
   }

   public Object getObject(String columnLabel) throws SQLException {
      String name = columnName(columnLabel);
      Constant value = columnValue(name);
      if (sch.type(name) == java.sql.Types.TIME) return value.asLocalTime();
      if (sch.type(name) == java.sql.Types.INTEGER) return value.asInt();
      if (sch.type(name) == Types.DECIMAL) return value.asDecimal();
      return value.asString();
   }

   public Object getObject(int columnIndex) throws SQLException {
      return getObject(columnName(columnIndex));
   }

   private String columnName(String label) throws SQLException {
      if (label == null) throw new SQLException("Column label cannot be null");
      String name = label.toLowerCase(Locale.ROOT);
      if (!sch.hasField(name)) throw new SQLException("Unknown column: " + label);
      return name;
   }

   private String columnName(int index) throws SQLException {
      if (index < 1 || index > sch.fields().size())
         throw new SQLException("Column index out of range: " + index);
      return sch.fields().get(index - 1);
   }

   private Constant columnValue(String name) throws SQLException {
      try {
         return s.getVal(name);
      } catch (RuntimeException e) {
         conn.rollback();
         throw new SQLException(e);
      }
   }

   /**
    * Returns the result set's metadata,
    * by passing its schema into the EmbeddedMetaData constructor.
    */
   public ResultSetMetaData getMetaData() throws SQLException {
      return new EmbeddedMetaData(sch);
   }

   /**
    * Closes the result set by closing its scan, and commits.
    */
   public void close() throws SQLException {
      s.close();
      conn.commit();
   }
}

