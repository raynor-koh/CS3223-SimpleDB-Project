package simpledb.test;

import simpledb.plan.Planner;
import simpledb.server.SimpleDB;
import simpledb.tx.Transaction;

public class ChangeMajor {
   public static void main(String[] args) {
      try {
         // analogous to the driver
         SimpleDB db = new SimpleDB("studentdb");

         // analogous to the connection
         Transaction tx  = db.newTx();
         Planner planner = db.planner();

         // analogous to the statement
         String cmd = "update STUDENT "
                     + "set MajorId=30 "
                     + "where SName = 'amy'";
         int count = planner.executeUpdate(cmd, tx);
         tx.commit();

         System.out.println(count + " student(s) updated.");
      } catch (Exception e) {
         e.printStackTrace();
      }

   }
}
