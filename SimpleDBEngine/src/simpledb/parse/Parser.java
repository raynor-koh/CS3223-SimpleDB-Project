package simpledb.parse;

import java.util.*;

import simpledb.query.*;
import simpledb.record.*;

/**
 * The SimpleDB parser.
 * @author Edward Sciore
 */
public class Parser {
   private Lexer lex;
   
   public Parser(String s) {
      lex = new Lexer(s);
   }
   
// Methods for parsing predicates, terms, expressions, constants, and fields
   
   public String field() {
      return lex.eatId();
   }
   
   public Constant constant() {
      if (lex.matchStringConstant())
         return new Constant(lex.eatStringConstant());
      else
         return new Constant(lex.eatIntConstant());
   }
   
   public Expression expression() {
      if (lex.matchId())
         return new Expression(field());
      else
         return new Expression(constant());
   }
   
   public Term term() {
      Expression lhs = expression();
      String operator = lex.eatOpr();
      Expression rhs = expression();
      return new Term(lhs, operator, rhs);
   }
   
   public Predicate predicate() {
      Predicate pred = new Predicate(term());
      if (lex.matchKeyword("and")) {
         lex.eatKeyword("and");
         pred.conjoinWith(predicate());
      }
      return pred;
   }
   
// Methods for parsing queries
   
   public QueryData query() {
      lex.setClause("SELECT");
      lex.eatKeyword("select");
      boolean distinct = false;
      if (lex.matchKeyword("distinct")) {
         lex.eatKeyword("distinct");
         distinct = true;
      }
      List<SelectItem> selectItems = selectItemList();
      lex.setClause("FROM");
      lex.eatKeyword("from");
      Collection<String> tables = tableList();
      Predicate pred = new Predicate();
      if (lex.matchKeyword("where")) {
         lex.setClause("WHERE");
         lex.eatKeyword("where");
         pred = predicate();
      }
      List<String> groupFields = new ArrayList<String>();
      if (lex.matchKeyword("group")) {
         lex.setClause("GROUP BY");
         lex.eatKeyword("group");
         lex.eatKeyword("by");
         groupFields = fieldList();
      }
      List<String> orderFields = new ArrayList<String>();
      List<Boolean> orderDirections = new ArrayList<Boolean>();
      if (lex.matchKeyword("order")) {
         lex.setClause("ORDER BY");
         lex.eatKeyword("order");
         lex.eatKeyword("by");
         orderList(orderFields, orderDirections);
      }
      requireEnd();
      return QueryData.withSelectItems(distinct, selectItems, tables, pred,
                                       groupFields, orderFields,
                                       orderDirections);
   }

   /**
    * Parses an EXPLAIN command and returns the query whose plan is requested.
    */
   public QueryData explain() {
      lex.setClause("EXPLAIN");
      lex.eatKeyword("explain");
      return query();
   }

   private void requireEnd() {
      if (!lex.matchEnd())
         throw lex.syntaxError("end of statement");
   }
   
   private List<SelectItem> selectItemList() {
      List<SelectItem> L = new ArrayList<SelectItem>();
      L.add(selectItem());
      if (lex.matchDelim(',')) {
         lex.eatDelim(',');
         L.addAll(selectItemList());
      }
      return L;
   }

   private SelectItem selectItem() {
      if (lex.matchId())
         return new SelectItem(field());

      SelectItem.Aggregate aggregate;
      if (lex.matchKeyword("count"))
         aggregate = SelectItem.Aggregate.COUNT;
      else if (lex.matchKeyword("sum"))
         aggregate = SelectItem.Aggregate.SUM;
      else if (lex.matchKeyword("avg"))
         aggregate = SelectItem.Aggregate.AVG;
      else if (lex.matchKeyword("min"))
         aggregate = SelectItem.Aggregate.MIN;
      else if (lex.matchKeyword("max"))
         aggregate = SelectItem.Aggregate.MAX;
      else
         throw lex.syntaxError("SELECT field or aggregate");

      lex.eatKeyword(aggregate.name().toLowerCase());
      lex.eatDelim('(');
      String argument = null;
      if (lex.matchDelim('*')) {
         if (aggregate != SelectItem.Aggregate.COUNT)
            throw lex.syntaxError("field argument for " + aggregate.name());
         lex.eatDelim('*');
      }
      else
         argument = field();
      lex.eatDelim(')');
      return new SelectItem(aggregate, argument);
   }
   
   private Collection<String> tableList() {
      Collection<String> L = new ArrayList<String>();
      L.add(lex.eatId());
      if (lex.matchDelim(',')) {
         lex.eatDelim(',');
         L.addAll(tableList());
      }
      return L;
   }

   /**
    * Parses a comma-separated ORDER BY list. Each field defaults to
    * ascending order unless it is followed by DESC.
    */
   private void orderList(List<String> fields,
                          List<Boolean> directions) {
      fields.add(field());
      boolean ascending = true;
      if (lex.matchKeyword("asc"))
         lex.eatKeyword("asc");
      else if (lex.matchKeyword("desc")) {
         lex.eatKeyword("desc");
         ascending = false;
      }
      directions.add(ascending);

      if (lex.matchDelim(',')) {
         lex.eatDelim(',');
         orderList(fields, directions);
      }
   }
   
// Methods for parsing the various update commands
   
   public Object updateCmd() {
      Object result;
      if (lex.matchKeyword("insert"))
         result = insert();
      else if (lex.matchKeyword("delete"))
         result = delete();
      else if (lex.matchKeyword("update"))
         result = modify();
      else
         result = create();
      requireEnd();
      return result;
   }
   
   private Object create() {
      lex.setClause("CREATE");
      lex.eatKeyword("create");
      if (lex.matchKeyword("table"))
         return createTable();
      else if (lex.matchKeyword("view"))
         return createView();
      else
         return createIndex();
   }
   
// Method for parsing delete commands
   
   public DeleteData delete() {
      lex.setClause("DELETE FROM");
      lex.eatKeyword("delete");
      lex.eatKeyword("from");
      String tblname = lex.eatId();
      Predicate pred = new Predicate();
      if (lex.matchKeyword("where")) {
         lex.setClause("WHERE");
         lex.eatKeyword("where");
         pred = predicate();
      }
      return new DeleteData(tblname, pred);
   }
   
// Methods for parsing insert commands
   
   public InsertData insert() {
      lex.setClause("INSERT INTO");
      lex.eatKeyword("insert");
      lex.eatKeyword("into");
      String tblname = lex.eatId();
      lex.eatDelim('(');
      List<String> flds = fieldList();
      lex.eatDelim(')');
      lex.setClause("VALUES");
      lex.eatKeyword("values");
      lex.eatDelim('(');
      List<Constant> vals = constList();
      lex.eatDelim(')');
      return new InsertData(tblname, flds, vals);
   }
   
   private List<String> fieldList() {
      List<String> L = new ArrayList<String>();
      L.add(field());
      if (lex.matchDelim(',')) {
         lex.eatDelim(',');
         L.addAll(fieldList());
      }
      return L;
   }
   
   private List<Constant> constList() {
      List<Constant> L = new ArrayList<Constant>();
      L.add(constant());
      if (lex.matchDelim(',')) {
         lex.eatDelim(',');
         L.addAll(constList());
      }
      return L;
   }
   
// Method for parsing modify commands
   
   public ModifyData modify() {
      lex.setClause("UPDATE");
      lex.eatKeyword("update");
      String tblname = lex.eatId();
      lex.setClause("SET");
      lex.eatKeyword("set");
      String fldname = field();
      lex.eatDelim('=');
      Expression newval = expression();
      Predicate pred = new Predicate();
      if (lex.matchKeyword("where")) {
         lex.setClause("WHERE");
         lex.eatKeyword("where");
         pred = predicate();
      }
      return new ModifyData(tblname, fldname, newval, pred);
   }
   
// Method for parsing create table commands
   
   public CreateTableData createTable() {
      lex.setClause("CREATE TABLE");
      lex.eatKeyword("table");
      String tblname = lex.eatId();
      lex.eatDelim('(');
      Schema sch = fieldDefs();
      lex.eatDelim(')');
      return new CreateTableData(tblname, sch);
   }
   
   private Schema fieldDefs() {
      Schema schema = fieldDef();
      if (lex.matchDelim(',')) {
         lex.eatDelim(',');
         Schema schema2 = fieldDefs();
         schema.addAll(schema2);
      }
      return schema;
   }
   
   private Schema fieldDef() {
      String fldname = field();
      return fieldType(fldname);
   }
   
   private Schema fieldType(String fldname) {
      Schema schema = new Schema();
      if (lex.matchKeyword("time")) {
         lex.eatKeyword("time");
         schema.addTimeField(fldname);
      } else if (lex.matchKeyword("int")) {
         lex.eatKeyword("int");
         schema.addIntField(fldname);
      }
      else {
         lex.eatKeyword("varchar");
         lex.eatDelim('(');
         int strLen = lex.eatIntConstant();
         lex.eatDelim(')');
         schema.addStringField(fldname, strLen);
      }
      return schema;
   }
   
// Method for parsing create view commands
   
   public CreateViewData createView() {
      lex.setClause("CREATE VIEW");
      lex.eatKeyword("view");
      String viewname = lex.eatId();
      lex.eatKeyword("as");
      QueryData qd = query();
      return new CreateViewData(viewname, qd);
   }
   
   
//  Method for parsing create index commands
   
   public CreateIndexData createIndex() {
      lex.setClause("CREATE INDEX");
      lex.eatKeyword("index");
      String idxname = lex.eatId();
      lex.eatKeyword("on");
      String tblname = lex.eatId();
      lex.eatDelim('(');
      String fldname = field();
      lex.eatDelim(')');

      // Handle type of index to use
      if (lex.matchKeyword("using")) {
        lex.eatKeyword("using");

        if (lex.matchKeyword("hash")) {
            lex.eatKeyword("hash");
            return new CreateIndexData(idxname, tblname, fldname, "hash");

        } else if (lex.matchKeyword("btree")){
            lex.eatKeyword("btree");
            return new CreateIndexData(idxname, tblname, fldname, "btree");
        } else {
            throw lex.syntaxError("index type 'hash' or 'btree'");
        }
      }
      return new CreateIndexData(idxname, tblname, fldname);
   }
}
