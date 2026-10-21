package simpledb.jdbc.network;

import java.sql.*;
import java.time.LocalTime;
import simpledb.jdbc.ResultSetAdapter;

/**
 * An adapter class that wraps RemoteResultSet.
 * Its methods do nothing except transform RemoteExceptions
 * into SQLExceptions.
 * @author Edward Sciore
 */
public class NetworkResultSet extends ResultSetAdapter {
   private RemoteResultSet rrs;
   
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

   public Time getTime(int index) throws SQLException {
      return getTime(columnName(index));
   }

   public <T> T getObject(String label, Class<T> type) throws SQLException {
      if (type == null) throw new SQLException("Requested Java type cannot be null");
      int sqlType = columnType(label);
      try {
         if (type == LocalTime.class && sqlType == Types.TIME)
            return type.cast(LocalTime.parse(getString(label)));
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
      try {
         RemoteMetaData metadata = rrs.getMetaData();
         if (index < 1 || index > metadata.getColumnCount())
            throw new SQLException("Column index out of range: " + index);
         return metadata.getColumnName(index);
      } catch (Exception e) {
         throw new SQLException(e);
      }
   }

   private int columnType(String label) throws SQLException {
      if (label == null) throw new SQLException("Column label cannot be null");
      try {
         RemoteMetaData metadata = rrs.getMetaData();
         for (int i = 1; i <= metadata.getColumnCount(); i++)
            if (metadata.getColumnName(i).equalsIgnoreCase(label))
               return metadata.getColumnType(i);
         throw new SQLException("Unknown column: " + label);
      } catch (Exception e) {
         throw new SQLException(e);
      }
   }
   
   public ResultSetMetaData getMetaData() throws SQLException {
      try {
         RemoteMetaData rmd = rrs.getMetaData();
         return new NetworkMetaData(rmd);
      }
      catch (Exception e) {
         throw new SQLException(e);
      }
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

