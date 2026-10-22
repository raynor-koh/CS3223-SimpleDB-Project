package simpledb.parse;

import java.util.*;
import java.io.*;

/**
 * The lexical analyzer.
 * @author Edward Sciore
 */
public class Lexer {
   private Collection<String> keywords;
   private StreamTokenizer tok;
   private Queue<Boolean> terminatedStrings;
   private String clause = "SQL";
   
   /**
    * Creates a new lexical analyzer for SQL statement s.
    * @param s the SQL statement
    */
   public Lexer(String s) {
      initKeywords();
      terminatedStrings = stringTerminations(s);
      tok = new StreamTokenizer(new StringReader(s));
      tok.ordinaryChar('.');   //disallow "." in identifiers
      // Retain the exact integer text instead of rounding it through a double.
      tok.ordinaryChars('0', '9');
      tok.wordChars('0', '9');
      tok.ordinaryChar('-');
      tok.wordChars('-', '-'); //retain hyphens within existing identifiers
      tok.wordChars('_', '_'); //allow "_" in identifiers
      tok.lowerCaseMode(true); //ids and keywords are converted
      nextToken();
   }
   
//Methods to check the status of the current token
   
   /**
    * Returns true if the current token is
    * the specified delimiter character.
    * @param d a character denoting the delimiter
    * @return true if the delimiter is the current token
    */
   public boolean matchDelim(char d) {
      return d == (char)tok.ttype;
   }
   
   /**
    * Returns true if the current token is an integer.
    * @return true if the current token is an integer
    */
   public boolean matchIntConstant() {
      return tok.ttype == StreamTokenizer.TT_WORD && tok.sval.matches("-?[0-9]+");
   }
   
   /**
    * Returns true if the current token is a string.
    * @return true if the current token is a string
    */
   public boolean matchStringConstant() {
      return '\'' == (char)tok.ttype;
   }
   
   /**
    * Returns true if the current token is the specified keyword.
    * @param w the keyword string
    * @return true if that keyword is the current token
    */
   public boolean matchKeyword(String w) {
      return tok.ttype == StreamTokenizer.TT_WORD && tok.sval.equals(w);
   }
   
   /**
    * Returns true if the current token is a legal identifier.
    * @return true if the current token is an identifier
    */
   public boolean matchId() {
      if (tok.ttype != StreamTokenizer.TT_WORD) return false;
      char first = tok.sval.charAt(0);
      return first != '-' && (first < '0' || first > '9') && !keywords.contains(tok.sval);
   }

   public boolean matchEnd() { return tok.ttype == StreamTokenizer.TT_EOF; }

   /** Adds the parser's current clause to lexical diagnostics. */
   public void setClause(String clause) { this.clause = clause; }

   public BadSyntaxException syntaxError(String expected) {
      return new BadSyntaxException(clause + " at line " + tok.lineno()
            + ": expected " + expected + ", found " + tokenDescription());
   }

   private String tokenDescription() {
      if (matchEnd()) return "end of input";
      if (tok.ttype == StreamTokenizer.TT_WORD) return "'" + tok.sval + "'";
      if (tok.ttype == '\'' || tok.ttype == '"') return "string literal '" + tok.sval + "'";
      return "'" + (char) tok.ttype + "'";
   }
   
//Methods to "eat" the current token
   
   /**
    * Throws an exception if the current token is not the
    * specified delimiter. 
    * Otherwise, moves to the next token.
    * @param d a character denoting the delimiter
    */
   public void eatDelim(char d) {
      if (!matchDelim(d))
         throw syntaxError("'" + d + "'");
      nextToken();
   }

    /**
     * Consumes and returns a comparison operator.
     * Supported operators are: =, <, <=, >, >=, != and <>
     * 
     * Throws an exception if the current token does not begin a valid comparison
     * operator
     * 
     * @return the comparison operator
     */
    public String eatOpr() {
        // Handles = comparison operator
        if (matchDelim('=')) {
            eatDelim('=');
            return "=";
        }

        // Handles <, <= and <> comparison operators
        if (matchDelim('<')) {
            eatDelim('<');

            if (matchDelim('=')) {
                eatDelim('=');
                return "<=";
            }

            if (matchDelim('>')) {
                eatDelim('>');
                return "<>";
            }

            return "<";
        }

        // Handles > and >= comparison operators
        if (matchDelim('>')) {
            eatDelim('>');

            if (matchDelim('=')) {
                eatDelim('=');
                return ">=";
            }

            return ">";
        }

        // Handles != comparison operators
        if (matchDelim('!')) {
            eatDelim('!');

            if (matchDelim('=')) {
                eatDelim('=');
                return "!=";
            }

            throw syntaxError("'=' after '!'");
        }

        throw syntaxError("comparison operator (=, <, <=, >, >=, !=, <>)");
    }
   
   /**
    * Throws an exception if the current token is not 
    * an integer. 
    * Otherwise, returns that integer and moves to the next token.
    * @return the integer value of the current token
    */
   public int eatIntConstant() {
      if (!matchIntConstant())
         throw syntaxError("integer literal");
      String literal = tok.sval;
      int i;
      try { i = Integer.parseInt(literal); }
      catch (NumberFormatException e) {
         throw new BadSyntaxException(clause + ": integer literal out of range: " + literal, e);
      }
      nextToken();
      if (matchDelim('.'))
         throw syntaxError("integer literal without a decimal point");
      return i;
   }
   
   /**
    * Throws an exception if the current token is not 
    * a string. 
    * Otherwise, returns that string and moves to the next token.
    * @return the string value of the current token
    */
   public String eatStringConstant() {
      if (!matchStringConstant())
         throw syntaxError("quoted string literal");
      String s = tok.sval; //constants are not converted to lower case
      nextToken();
      return s;
   }
   
   /**
    * Throws an exception if the current token is not the
    * specified keyword. 
    * Otherwise, moves to the next token.
    * @param w the keyword string
    */
   public void eatKeyword(String w) {
      if (!matchKeyword(w))
         throw syntaxError("keyword '" + w + "'");
      nextToken();
   }
   
   /**
    * Throws an exception if the current token is not 
    * an identifier. 
    * Otherwise, returns the identifier string 
    * and moves to the next token.
    * @return the string value of the current token
    */
   public String eatId() {
      if (!matchId())
         throw syntaxError("identifier");
      String s = tok.sval;
      nextToken();
      return s;
   }
   
   private void nextToken() {
      try {
         tok.nextToken();
         if ((tok.ttype == '\'' || tok.ttype == '"') && !terminatedStrings.remove())
            throw syntaxError("closing quote for string literal");
      }
      catch(IOException e) {
         throw new BadSyntaxException(clause + ": could not read SQL input", e);
      }
   }

   /** StreamTokenizer accepts unclosed quotes; record whether each quote closes.
    * Match its backslash escapes and its default slash-to-end-of-line comments.
    */
   private static Queue<Boolean> stringTerminations(String sql) {
      Queue<Boolean> result = new ArrayDeque<Boolean>();
      for (int i = 0; i < sql.length(); i++) {
         char c = sql.charAt(i);
         if (c == '/') {
            while (i < sql.length() && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') i++;
         } else if (c == '\'' || c == '"') {
            boolean closed = false;
            while (++i < sql.length()) {
               char next = sql.charAt(i);
               if (next == c) { closed = true; break; }
               if (next == '\n' || next == '\r') break;
               if (next == '\\') i++;
            }
            result.add(closed);
            if (!closed) break;
         }
      }
      return result;
   }
   
   private void initKeywords() {
      keywords = Arrays.asList("select", "from", "where", "and",
                               "insert", "into", "values", "delete", "update", "set", "time",
                               "create", "table", "int", "varchar", "view", "as", "index", "on",
                               // index related keywords
                               "using", "hash", "btree",
                               // sorting related keywords
                               "order", "by", "asc", "desc",
                               // projection related keywords
                               "distinct",
                               // plan-display related keywords
                               "explain",
                               // grouping and aggregate related keywords
                               "group", "sum", "count", "avg", "min", "max");
   }
}
