package simpledb.jdbc.network;

import java.sql.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalTime;
import java.util.Locale;
import simpledb.jdbc.ResultSetAdapter;
import simpledb.record.Schema;

/**
 * An adapter class that wraps RemoteResultSet.
 * Its methods do nothing except transform RemoteExceptions
 * into SQLExceptions.
 * @author Edward Sciore
 */
public class NetworkResultSet extends ResultSetAdapter {
   private RemoteResultSet rrs;
   private RemoteMetaData metadata;
   private Schema columns;
   
   public NetworkResultSet(RemoteResultSet s) {
      rrs = s;
   }
   
   public boolean next() throws SQLException {
      try {
         return rrs.next();
      }
      catch (Exception e) {
         throw new SQLException(e);
      }
   }
   
   public int getInt(String fldname) throws SQLException {
      if (columnType(fldname) == Types.DECIMAL) {
         try { return getBigDecimal(fldname).setScale(0, RoundingMode.DOWN).intValueExact(); }
         catch (ArithmeticException e) { throw new SQLException("Integer conversion overflow: " + fldname, e); }
      }
      try {
         return rrs.getInt(fldname);
      }
      catch (Exception e) {
         throw new SQLException(e);
      }
   }
   
   public String getString(String fldname) throws SQLException {
      try {
         return rrs.getString(fldname);
      }
      catch (Exception e) {
         throw new SQLException(e);
      }
   }

   public Time getTime(String label) throws SQLException {
      LocalTime value = getObject(label, LocalTime.class);
      Time legacy = Time.valueOf(value);
      legacy.setTime(legacy.getTime() + value.getNano() / 1000000);
      return legacy;
   }

   public int getInt(int index) throws SQLException { return getInt(columnName(index)); }

   public String getString(int index) throws SQLException { return getString(columnName(index)); }

   public BigDecimal getBigDecimal(String label) throws SQLException {
      int type = columnType(label);
      if (type != Types.DECIMAL && type != Types.INTEGER)
         throw new SQLException("Column is not numeric: " + label);
      try {
         return type == Types.DECIMAL ? new BigDecimal(rrs.getString(label))
               : BigDecimal.valueOf(rrs.getInt(label));
      } catch (Exception e) {
         throw new SQLException("Cannot read numeric column: " + label, e);
      }
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

   public Object getObject(String label) throws SQLException {
      int type = columnType(label);
      if (type == Types.DECIMAL) return getBigDecimal(label);
      if (type == Types.INTEGER) return getInt(label);
      if (type == Types.TIME) return getObject(label, LocalTime.class);
      return getString(label);
   }

   public Object getObject(int index) throws SQLException { return getObject(columnName(index)); }

   public Time getTime(int index) throws SQLException {
      return getTime(columnName(index));
   }

   public <T> T getObject(String label, Class<T> type) throws SQLException {
      if (type == null) throw new SQLException("Requested Java type cannot be null");
      int sqlType = columnType(label);
      try {
         if (type == LocalTime.class && sqlType == Types.TIME)
            return type.cast(LocalTime.parse(getString(label)));
         if (type == BigDecimal.class) return type.cast(getBigDecimal(label));
         if (type == Time.class) return type.cast(getTime(label));
         if (type == String.class) return type.cast(getString(label));
         throw new SQLException("Unsupported typed object request: " + type.getName());
      } catch (SQLException e) {
         throw e;
      } catch (RuntimeException e) {
         throw new SQLException("Cannot convert column " + label + " to " + type.getName(), e);
      }
   }

   public <T> T getObject(int index, Class<T> type) throws SQLException {
      return getObject(columnName(index), type);
   }

   private String columnName(int index) throws SQLException {
      loadColumns();
      if (index < 1 || index > columns.fields().size())
         throw new SQLException("Column index out of range: " + index);
      return columns.fields().get(index - 1);
   }

   private int columnType(String label) throws SQLException {
      if (label == null) throw new SQLException("Column label cannot be null");
      loadColumns();
      String name = label.toLowerCase(Locale.ROOT);
      if (!columns.hasField(name)) throw new SQLException("Unknown column: " + label);
      return columns.type(name);
   }

   /** Cache types so numeric getters do not fetch remote metadata for every row. */
   private void loadColumns() throws SQLException {
      if (columns != null) return;
      try {
         RemoteMetaData remote = rrs.getMetaData();
         Schema loaded = new Schema();
         int count = remote.getColumnCount();
         for (int i = 1; i <= count; i++)
            loaded.addField(remote.getColumnName(i).toLowerCase(Locale.ROOT), remote.getColumnType(i), 0);
         metadata = remote;
         columns = loaded;
      } catch (Exception e) {
         throw new SQLException(e);
      }
   }
   
   public ResultSetMetaData getMetaData() throws SQLException {
      loadColumns();
      return new NetworkMetaData(metadata);
   }
   
   public void close() throws SQLException {
      try {
         rrs.close();
      }
      catch (Exception e) {
         throw new SQLException(e);
      }
   }
}

