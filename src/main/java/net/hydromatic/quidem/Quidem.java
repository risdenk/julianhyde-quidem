/*
 * Licensed to Julian Hyde under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership. Julian Hyde
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.hydromatic.quidem;

import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

/**
 * Runs a SQL script.
 */
public class Quidem {
  private static final Ordering<String[]> ORDERING =
      Ordering.natural().nullsLast().lexicographical().onResultOf(
          new Function<String[], Iterable<Comparable>>() {
            public Iterable<Comparable> apply(String[] input) {
              return Arrays.<Comparable>asList(input);
            }
          });

  public static final boolean DEBUG =
    "true".equals(System.getProperties().getProperty("quidem.debug"));

  /** Default value for {@link #setStackLimit(int)}. */
  private static final int DEFAULT_MAX_STACK_LENGTH = 16384;

  /** The empty environment. Returns null for all variables. */
  public static final Function<String, Object> EMPTY_ENV =
      new Function<String, Object>() {
        public Object apply(String name) {
          return null;
        }
      };

  /** The empty environment. Returns null for all database names. */
  public static final ConnectionFactory EMPTY_CONNECTION_FACTORY =
      new ChainingConnectionFactory(ImmutableList.<ConnectionFactory>of());

  private final BufferedReader reader;
  private final PrintWriter writer;
  private final Map<Property, Object> map = new HashMap<Property, Object>();
  /** Result set from SQL statement just executed. */
  private ResultSet resultSet;
  /** Whether to sort result set before printing. */
  private boolean sort;
  private SQLException resultSetException;
  private final List<String> lines = new ArrayList<String>();
  private String pushedLine;
  private final StringBuilder buf = new StringBuilder();
  private Connection connection;
  private Connection refConnection;
  private final ConnectionFactory connectionFactory;
  private boolean execute = true;
  private boolean skip = false;
  private int stackLimit = DEFAULT_MAX_STACK_LENGTH;
  private final Function<String, Object> env;

  /** Creates a Quidem interpreter with an empty environment and empty
   * connection factory. */
  public Quidem(Reader reader, Writer writer) {
    this(reader, writer, EMPTY_ENV, EMPTY_CONNECTION_FACTORY);
  }

  /** Creates a Quidem interpreter. */
  public Quidem(Reader reader, Writer writer, Function<String, Object> env,
      ConnectionFactory connectionFactory) {
    if (reader instanceof BufferedReader) {
      this.reader = (BufferedReader) reader;
    } else {
      this.reader = new BufferedReader(reader);
    }
    if (writer instanceof PrintWriter) {
      this.writer = (PrintWriter) writer;
    } else {
      this.writer = new PrintWriter(writer);
    }
    this.connectionFactory = connectionFactory;
    this.map.put(Property.OUTPUTFORMAT, OutputFormat.CSV);
    this.env = env;
  }

  /** Entry point from the operating system command line.
   *
   * <p>Calls {@link System#exit(int)} with the following status codes:
   * <ul>
   *   <li>0: success</li>
   *   <li>1: invalid arguments</li>
   *   <li>2: help</li>
   * </ul>
   *
   * @param args Command-line arguments
   */
  public static void main(String[] args) {
    final PrintWriter out = new PrintWriter(System.out);
    final PrintWriter err = new PrintWriter(System.err);
    final int code = Launcher.main2(out, err, Arrays.asList(args));
    System.exit(code);
  }

  private void close() throws SQLException {
    if (connection != null) {
      Connection c = connection;
      connection = null;
      c.close();
    }
    if (refConnection != null) {
      Connection c = refConnection;
      refConnection = null;
      c.close();
    }
  }

  /** Executes the commands from the input, writing to the output,
   * then closing both input and output. */
  public void execute() {
    try {
      Command command = new Parser().parse();
      try {
        command.execute(execute);
        close();
      } catch (Exception e) {
        throw new RuntimeException(
            "Error while executing command " + command, e);
      } catch (AssertionError e) {
        throw new RuntimeException(
            "Error while executing command " + command, e);
      }
    } finally {
      try {
        reader.close();
      } catch (IOException e) {
        e.printStackTrace();
      }
      writer.close();
      try {
        close();
      } catch (SQLException e) {
        // ignore
      }
    }
  }

  Command of(List<Command> commands) {
    return commands.size() == 1
        ? commands.get(0)
        : new CompositeCommand(ImmutableList.copyOf(commands));
  }

  private static String pad(String s, int width, boolean right) {
    if (s == null) {
      s = "";
    }
    final int x = width - s.length();
    if (x <= 0) {
      return s;
    }
    final StringBuilder buf = new StringBuilder();
    if (right) {
      buf.append(chars(' ', x)).append(s);
    } else {
      buf.append(s).append(chars(' ', x));
    }
    return buf.toString();
  }

  <E> Iterator<String> stringIterator(final Enumeration<E> enumeration) {
    return new Iterator<String>() {
      public boolean hasNext() {
        return enumeration.hasMoreElements();
      }

      public String next() {
        return enumeration.nextElement().toString();
      }

      public void remove() {
        throw new UnsupportedOperationException();
      }
    };
  }

  private static CharSequence chars(final char c, final int length) {
    return new CharSequence() {
      @Override public String toString() {
        final char[] chars = new char[length];
        Arrays.fill(chars, c);
        return new String(chars);
      }

      public int length() {
        return length;
      }

      public char charAt(int index) {
        return c;
      }

      public CharSequence subSequence(int start, int end) {
        return Quidem.chars(c, end - start);
      }
    };
  }

  /**
   * Sets the maximum number of characters of an error stack to be printed.
   *
   * <p>If negative, does not limit the stack size.
   *
   * <p>The default is {@link #DEFAULT_MAX_STACK_LENGTH}.
   *
   * <p>Useful because it prevents {@code diff} from running out of memory if
   * the error stack is very large. It is preferable to produce a result where
   * you can see the first N characters of each stack trace than to produce
   * no result at all.
   *
   * @param stackLimit Maximum number of characters to print of each stack
   *                      trace
   */
  public void setStackLimit(int stackLimit) {
    this.stackLimit = stackLimit;
  }

  private boolean getBoolean(List<String> names) {
    if (names.size() == 1) {
      if (names.get(0).equals("true")) {
        return true;
      }
      if (names.get(0).equals("false")) {
        return false;
      }
    }
    Function<String, Object> e = env;
    for (int i = 0; i < names.size(); i++) {
      String name = names.get(i);
      final Object value = e.apply(name);
      if (value instanceof Function) {
        //noinspection unchecked
        e = (Function<String, Object>) value;
      } else if (i == names.size() - 1) {
        if (value instanceof Boolean) {
          return (Boolean) value;
        }
        return value != null
            && value.toString().equalsIgnoreCase("true");
      }
    }
    return false;
  }

  /** Returns whether a SQL query is likely to produce results always in the
   * same order.
   *
   * <p>If Quidem believes that the order is deterministic, it does not sort
   * the rows before comparing them.
   *
   * <p>The result is just a guess. Quidem does not understand the finer points
   * of SQL semantics.
   *
   * @param sql SQL query
   * @return Whether the order is likely to be deterministic
   */
  public boolean isProbablyDeterministic(String sql) {
    final String upperSql = sql.toUpperCase();
    if (!upperSql.contains("ORDER BY")) {
      return false;
    }
    final int i = upperSql.lastIndexOf("ORDER BY");
    final String tail = upperSql.substring(i);
    final int closeCount = tail.length() - tail.replace(")", "").length();
    final int openCount = tail.length() - tail.replace("(", "").length();
    if (closeCount > openCount) {
      // The last ORDER BY occurs within parentheses. It is either in a
      // sub-query or in a windowed aggregate. Neither of these make the
      // ordering deterministic.
      return false;
    }
    return true;
  }

  /** Parser. */
  private class Parser {
    final List<Command> commands = new ArrayList<Command>();

    Command parse() {
      for (;;) {
        Command command;
        try {
          command = nextCommand();
        } catch (IOException e) {
          throw new RuntimeException("Error while reading next command", e);
        }
        if (command == null) {
          break;
        }
        commands.add(command);
      }
      return of(commands);
    }

    private Command nextCommand() throws IOException {
      lines.clear();
      ImmutableList<String> content = ImmutableList.of();
      for (;;) {
        String line = nextLine();
        if (line == null) {
          return null;
        }
        if (line.startsWith("#") || line.isEmpty()) {
          return new CommentCommand(lines);
        }
        if (line.startsWith("!")) {
          line = line.substring(1);
          while (line.startsWith(" ")) {
            line = line.substring(1);
          }
          if (line.startsWith("use")) {
            String[] parts = line.split(" ");
            return new UseCommand(lines, parts[1]);
          }
          if (line.startsWith("ok")) {
            SqlCommand command = previousSqlCommand();
            return new OkCommand(lines, command, content);
          }
          if (line.startsWith("verify")) {
            // "content" may or may not be empty. We ignore it.
            // This allows people to switch between '!ok' and '!verify'.
            SqlCommand command = previousSqlCommand();
            return new VerifyCommand(lines, command);
          }
          if (line.startsWith("update")) {
            SqlCommand command = previousSqlCommand();
            return new UpdateCommand(lines, command, content);
          }
          if (line.startsWith("plan")) {
            SqlCommand command = previousSqlCommand();
            return new ExplainCommand(lines, command, content);
          }
          if (line.startsWith("type")) {
            SqlCommand command = previousSqlCommand();
            return new TypeCommand(lines, command, content);
          }
          if (line.startsWith("error")) {
            SqlCommand command = previousSqlCommand();
            return new ErrorCommand(lines, command, content);
          }
          if (line.startsWith("skip")) {
            return new SkipCommand(lines);
          }
          if (line.startsWith("set outputformat")) {
            String[] parts = line.split(" ");
            final OutputFormat outputFormat =
                OutputFormat.valueOf(parts[2].toUpperCase());
            return new SetCommand(lines, Property.OUTPUTFORMAT, outputFormat);
          }
          if (line.matches("if \\([A-Za-z-][A-Za-z_0-9.]*\\) \\{")) {
            List<String> ifLines = ImmutableList.copyOf(lines);
            lines.clear();
            Command command = new Parser().parse();
            String variable =
                line.substring("if (".length(),
                    line.length() - ") {".length());
            List<String> variables =
                ImmutableList.copyOf(
                    stringIterator(new StringTokenizer(variable, ".")));
            return new IfCommand(ifLines, lines, command, variables);
          }
          if (line.equals("}")) {
            return null;
          }
          throw new RuntimeException("Unknown command: " + line);
        }
        buf.setLength(0);
        boolean last = false;
        for (;;) {
          if (line.endsWith(";")) {
            last = true;
            line = line.substring(0, line.length() - 1);
          }
          buf.append(line).append("\n");
          if (last) {
            break;
          }
          line = nextLine();
          if (line == null) {
            throw new RuntimeException(
                "end of file reached before end of SQL command");
          }
          if (line.startsWith("!") || line.startsWith("#")) {
            pushLine();
            break;
          }
        }
        content = ImmutableList.copyOf(lines);
        lines.clear();
        if (last) {
          String sql = buf.toString();
          return new SqlCommand(content, sql, null);
        }
      }
    }

    private SqlCommand previousSqlCommand() {
      for (int i = commands.size() - 1; i >= 0; i--) {
        Command command = commands.get(i);
        if (command instanceof SqlCommand) {
          return (SqlCommand) command;
        }
      }
      throw new AssertionError("no previous SQL command");
    }

    private void pushLine() {
      if (pushedLine != null) {
        throw new AssertionError("cannot push two lines");
      }
      if (lines.size() == 0) {
        throw new AssertionError("no line has been read");
      }
      pushedLine = lines.get(lines.size() - 1);
      lines.remove(lines.size() - 1);
    }

    private String nextLine() throws IOException {
      String line;
      if (pushedLine != null) {
        line = pushedLine;
        pushedLine = null;
      } else {
        line = reader.readLine();
        if (line == null) {
          return null;
        }
      }
      lines.add(line);
      return line;
    }
  }

  /** Schemes for converting the output of a SQL statement into text. */
  enum OutputFormat {
    CSV {
      @Override public void format(ResultSet resultSet,
          List<String> headerLines, List<String> bodyLines,
          List<String> footerLines, Quidem run) throws Exception {
        final ResultSetMetaData metaData = resultSet.getMetaData();
        final int n = metaData.getColumnCount();
        final StringBuilder buf = new StringBuilder();
        for (int i = 0; i < n; i++) {
          if (i > 0) {
            buf.append(", ");
          }
          buf.append(metaData.getColumnLabel(i + 1));
        }
        headerLines.add(buf.toString());
        buf.setLength(0);
        final List<String> lines = Lists.newArrayList();
        while (resultSet.next()) {
          for (int i = 0; i < n; i++) {
            if (i > 0) {
              buf.append(", ");
            }
            buf.append(resultSet.getString(i + 1));
          }
          lines.add(buf.toString());
          buf.setLength(0);
        }
        if (run.sort) {
          Collections.sort(lines);
        }
        bodyLines.addAll(lines);
      }
    },

    // Example:
    //
    //  ename | deptno | gender | first_value
    // -------+--------+--------+-------------
    //  Jane  |     10 | F      | Jane
    //  Bob   |     10 | M      | Jane
    // (2 rows)
    PSQL {
      @Override public void format(ResultSet resultSet,
          List<String> headerLines, List<String> bodyLines,
          List<String> footerLines, Quidem run) throws Exception {
        Quidem.format(
            resultSet, headerLines, bodyLines, footerLines, run.sort, false);
      }
    },

    // Example:
    //
    // +-------+--------+--------+-------------+
    // | ename | deptno | gender | first_value |
    // +-------+--------+--------+-------------+
    // | Jane  |     10 | F      | Jane        |
    // | Bob   |     10 | M      | Jane        |
    // +-------+--------+--------+-------------+
    // (2 rows)
    MYSQL {
      @Override public void format(ResultSet resultSet,
          List<String> headerLines, List<String> bodyLines,
          List<String> footerLines, Quidem run) throws Exception {
        Quidem.format(
            resultSet, headerLines, bodyLines, footerLines, run.sort, true);
      }
    };

    public abstract void format(ResultSet resultSet, List<String> headerLines,
        List<String> bodyLines, List<String> footerLines, Quidem run)
        throws Exception;
  }

  private static void format(ResultSet resultSet, List<String> headerLines,
      List<String> bodyLines, List<String> footerLines, boolean sort,
      boolean mysql) throws SQLException {
    final ResultSetMetaData metaData = resultSet.getMetaData();
    final int n = metaData.getColumnCount();
    final int[] widths = new int[n];
    final List<String[]> rows = new ArrayList<String[]>();
    final boolean[] rights = new boolean[n];

    for (int i = 0; i < n; i++) {
      widths[i] = metaData.getColumnLabel(i + 1).length();
    }
    while (resultSet.next()) {
      String[] row = new String[n];
      for (int i = 0; i < n; i++) {
        String value = resultSet.getString(i + 1);
        widths[i] = Math.max(widths[i], value == null ? 0 : value.length());
        row[i] = value;
      }
      rows.add(row);
    }
    for (int i = 0; i < widths.length; i++) {
      switch (metaData.getColumnType(i + 1)) {
      case Types.TINYINT:
      case Types.SMALLINT:
      case Types.INTEGER:
      case Types.BIGINT:
      case Types.FLOAT:
      case Types.REAL:
      case Types.DOUBLE:
      case Types.NUMERIC:
      case Types.DECIMAL:
        rights[i] = true;
      }
    }

    if (sort) {
      Collections.sort(rows, ORDERING);
    }

    // Compute "+-----+---+" (if b)
    // or       "-----+---" (if not b)
    final StringBuilder buf = new StringBuilder();
    for (int i = 0; i < n; i++) {
      buf.append(mysql || i > 0 ? "+" : "");
      buf.append(chars('-', widths[i] + 2));
    }
    buf.append(mysql ? "+" : "");
    String hyphens = flush(buf);

    if (mysql) {
      headerLines.add(hyphens);
    }

    // Print "| FOO | B |"
    // or    "  FOO | B"
    for (int i = 0; i < n; i++) {
      buf.append(i > 0 ? " | " : mysql ? "| " : " ");
      final String label = metaData.getColumnLabel(i + 1);
      buf.append(mysql || i < n - 1 ? pad(label, widths[i], false) : label);
    }
    buf.append(mysql ? " |" : "");
    headerLines.add(flush(buf));
    headerLines.add(hyphens);
    for (String[] row : rows) {
      for (int i = 0; i < n; i++) {
        buf.append(i > 0 ? " | " : mysql ? "| " : " ");
        // don't pad the last field if it is left-justified
        final String s = !mysql && i == n - 1 && !rights[i]
            ? row[i]
            : pad(row[i], widths[i], rights[i]);
        buf.append(s);
      }
      buf.append(mysql ? " |" : "");
      bodyLines.add(flush(buf));
    }
    if (mysql) {
      footerLines.add(hyphens);
    }
    footerLines.add(
        rows.size() == 1 ? "(1 row)" : "(" + rows.size() + " rows)");
    footerLines.add("");
  }

  /** Returns the contents of a StringBuilder and clears it for the next use. */
  private static String flush(StringBuilder buf) {
    final String s = buf.toString();
    buf.setLength(0);
    return s;
  }

  private String stack(Throwable e) {
    final StringWriter buf = new StringWriter();
    stack(e, buf);
    return buf.toString();
  }

  private void stack(Throwable e, Writer w) {
    if (stackLimit >= 0) {
      w = new LimitWriter(w, stackLimit);
    }
    final PrintWriter pw;
    if (w instanceof PrintWriter) {
      pw = (PrintWriter) w;
    } else {
      pw = new PrintWriter(w);
    }
    e.printStackTrace(pw);
    if (stackLimit >= 0) {
      ((LimitWriter) w).ellipsis(" (stack truncated)\n");
    }
  }

  /** Command. */
  interface Command {
    void execute(boolean execute) throws Exception;
  }

  /** Base class for implementations of Command. */
  abstract class AbstractCommand implements Command {
    protected Command echo(Iterable<String> lines) {
      for (String line : lines) {
        try {
          writer.println(line);
        } catch (Exception e) {
          throw new RuntimeException("Error while writing output", e);
        }
      }
      return this;
    }
  }

  /** Base class for implementations of Command that have one piece of source
   * code. */
  abstract class SimpleCommand extends AbstractCommand {
    protected final ImmutableList<String> lines;

    public SimpleCommand(List<String> lines) {
      this.lines = ImmutableList.copyOf(lines);
    }
  }

  /** Command that sets the current connection. */
  class UseCommand extends SimpleCommand {
    private final String name;

    public UseCommand(List<String> lines, String name) {
      super(lines);
      this.name = name;
    }

    public void execute(boolean execute) throws Exception {
      echo(lines);
      if (connection != null) {
        connection.close();
      }
      if (refConnection != null) {
        refConnection.close();
      }
      connection = connectionFactory.connect(name, false);
      refConnection = connectionFactory.connect(name, true);
    }
  }

  /** Command that executes a SQL statement and checks its result. */
  abstract class CheckResultCommand extends SimpleCommand {
    protected final SqlCommand sqlCommand;
    protected final boolean output;

    public CheckResultCommand(List<String> lines, SqlCommand sqlCommand,
        boolean output) {
      super(lines);
      this.sqlCommand = sqlCommand;
      this.output = output;
    }

    protected abstract List<String> getOutput() throws Exception;

    public void execute(boolean execute) throws Exception {
      if (execute) {
        if (connection == null) {
          throw new RuntimeException("no connection");
        }
        final Statement statement = connection.createStatement();
        if (resultSet != null) {
          resultSet.close();
        }
        try {
          try {
            if (DEBUG) {
              System.out.println("execute: " + this);
            }
            resultSet = null;
            resultSetException = null;
            resultSet = statement.executeQuery(sqlCommand.sql);
            final String sql = sqlCommand.sql;
            sort = !isProbablyDeterministic(sql);
          } catch (SQLException e) {
            resultSetException = e;
          }
          if (resultSet != null) {
            final OutputFormat format =
                (OutputFormat) map.get(Property.OUTPUTFORMAT);
            final List<String> headerLines = new ArrayList<String>();
            final List<String> bodyLines = new ArrayList<String>();
            final List<String> footerLines = new ArrayList<String>();
            format.format(resultSet, headerLines, bodyLines, footerLines,
                Quidem.this);

            // Construct the original body.
            // Strip the header and footer from the actual output.
            // We assume original and actual header have the same line count.
            // Ditto footers.
            final List<String> expectedLines = getOutput();
            final List<String> lines = new ArrayList<String>(expectedLines);
            final List<String> actualLines =
                ImmutableList.<String>builder()
                    .addAll(headerLines)
                    .addAll(bodyLines)
                    .addAll(footerLines)
                    .build();
            for (String line : headerLines) {
              if (!lines.isEmpty()) {
                lines.remove(0);
              }
            }
            for (String line : footerLines) {
              if (!lines.isEmpty()) {
                lines.remove(lines.size() - 1);
              }
            }

            // Print the actual header.
            for (String line : headerLines) {
              if (this.output) {
                writer.println(line);
              }
            }
            // Print all lines that occurred in the actual output ("bodyLines"),
            // but in their original order ("lines").
            for (String line : lines) {
              if (sort) {
                if (bodyLines.remove(line)) {
                  if (this.output) {
                    writer.println(line);
                  }
                }
              } else {
                if (!bodyLines.isEmpty()
                    && bodyLines.get(0).equals(line)) {
                  bodyLines.remove(0);
                  if (this.output) {
                    writer.println(line);
                  }
                }
              }
            }
            // Print lines that occurred in the actual output but not original.
            for (String line : bodyLines) {
              if (this.output) {
                writer.println(line);
              }
            }
            // Print the actual footer.
            for (String line : footerLines) {
              if (this.output) {
                writer.println(line);
              }
            }
            resultSet.close();
            if (!output) {
              if (!actualLines.equals(expectedLines)) {
                final StringWriter buf = new StringWriter();
                final PrintWriter w = new PrintWriter(buf);
                w.println("Reference query returned different results.");
                w.println("expected:");
                for (String line : expectedLines) {
                  w.println(line);
                }
                w.println("actual:");
                for (String line : actualLines) {
                  w.println(line);
                }
                w.close();
                throw new IllegalArgumentException(buf.toString());
              }
            }
          }

          checkResultSet(resultSetException);

          if (resultSet == null && resultSetException == null) {
            throw new AssertionError("neither resultSet nor exception set");
          }
          resultSet = null;
          resultSetException = null;
        } finally {
          statement.close();
        }
      } else {
        if (output) {
          echo(getOutput());
        }
      }
      echo(lines);
    }

    protected void checkResultSet(SQLException resultSetException) {
      if (resultSetException != null) {
        stack(resultSetException, writer);
      }
    }
  }

  /** Command that executes a SQL statement and checks its result. */
  class OkCommand extends CheckResultCommand {
    protected final ImmutableList<String> output;

    public OkCommand(List<String> lines,
        SqlCommand sqlCommand, ImmutableList<String> output) {
      super(lines, sqlCommand, true);
      this.output = output;
    }

    @Override public String toString() {
      return "OkCommand [sql: " + sqlCommand.sql + "]";
    }

    protected List<String> getOutput() {
      return output;
    }
  }

  /** Command that executes a SQL statement and compares the result with a
   * reference database. */
  class VerifyCommand extends CheckResultCommand {
    public VerifyCommand(List<String> lines, SqlCommand sqlCommand) {
      super(lines, sqlCommand, false);
    }

    @Override public String toString() {
      return "VerifyCommand [sql: " + sqlCommand.sql + "]";
    }

    protected List<String> getOutput() throws Exception {
      if (refConnection == null) {
        throw new IllegalArgumentException("no reference connection");
      }
      final Statement statement = refConnection.createStatement();
      final ResultSet resultSet = statement.executeQuery(sqlCommand.sql);
      try {
        final OutputFormat format =
            (OutputFormat) map.get(Property.OUTPUTFORMAT);
        final List<String> headerLines = new ArrayList<String>();
        final List<String> bodyLines = new ArrayList<String>();
        final List<String> footerLines = new ArrayList<String>();
        format.format(resultSet, headerLines, bodyLines, footerLines,
            Quidem.this);
        return ImmutableList.<String>builder()
            .addAll(headerLines)
            .addAll(bodyLines)
            .addAll(footerLines)
            .build();
      } catch (SQLException e) {
        throw new IllegalArgumentException("reference threw", e);
      }
    }
  }

  /** Command that executes a SQL DML command and checks its count. */
  class UpdateCommand extends SimpleCommand {
    private final SqlCommand sqlCommand;
    protected final ImmutableList<String> output;

    public UpdateCommand(List<String> lines, SqlCommand sqlCommand,
        ImmutableList<String> output) {
      super(lines);
      this.sqlCommand = sqlCommand;
      this.output = output;
    }

    @Override public String toString() {
      return "UpdateCommand [sql: " + sqlCommand.sql + "]";
    }

    public void execute(boolean execute) throws Exception {
      if (execute) {
        if (connection == null) {
          throw new RuntimeException("no connection");
        }
        final Statement statement = connection.createStatement();
        if (resultSet != null) {
          resultSet.close();
        }
        try {
          if (DEBUG) {
            System.out.println("execute: " + this);
          }
          resultSet = null;
          resultSetException = null;
          final int updateCount = statement.executeUpdate(sqlCommand.sql);
          writer.println("(" + updateCount
              + ((updateCount == 1) ? " row" : " rows")
              + " modified)");
          final String sql = sqlCommand.sql;
          sort = !isProbablyDeterministic(sql);
        } catch (SQLException e) {
          resultSetException = e;
        } finally {
          statement.close();
        }

        checkResultSet(resultSetException);
        writer.println();

        resultSet = null;
        resultSetException = null;
      } else {
        echo(output);
      }
      echo(lines);
    }

    protected void checkResultSet(SQLException resultSetException) {
      if (resultSetException != null) {
        stack(resultSetException, writer);
      }
    }
  }

  /** Command that executes a SQL statement and checks that it throws a given
   * error. */
  class ErrorCommand extends OkCommand {
    public ErrorCommand(List<String> lines, SqlCommand sqlCommand,
        ImmutableList<String> output) {
      super(lines, sqlCommand, output);
    }

    @Override protected void checkResultSet(SQLException resultSetException) {
      if (resultSetException == null) {
        writer.println("Expected error, but SQL command did not give one");
        return;
      }
      if (!output.isEmpty()) {
        final String actual = squash(stack(resultSetException));
        final String expected = squash(concat(output));
        if (actual.contains(expected)) {
          // They gave an expected error, and the actual error does not match.
          // Print the actual error. This will cause a diff.
          for (String line : output) {
            writer.println(line);
          }
          return;
        }
      }
      super.checkResultSet(resultSetException);
    }

    private String squash(String s) {
      return s.replace("\r\n", "\n") // convert line endings to linux
          .replaceAll("[ \t]+", " ") // convert tabs & multiple spaces to spaces
          .replaceAll("\n ", "\n") // remove spaces at start of lines
          .replaceAll("^ ", "") // or at start of string
          .replaceAll(" \n", "\n") // remove spaces at end of lines
          .replaceAll(" $", "\n"); // or at end of string
    }

    private String concat(List<String> lines) {
      final StringBuilder buf = new StringBuilder();
      for (String line : lines) {
        buf.append(line).append("\n");
      }
      return buf.toString();
    }
  }

  /** Command that prints the plan for the current query. */
  class ExplainCommand extends SimpleCommand {
    private final SqlCommand sqlCommand;
    private final ImmutableList<String> content;

    public ExplainCommand(List<String> lines,
        SqlCommand sqlCommand,
        ImmutableList<String> content) {
      super(lines);
      this.sqlCommand = sqlCommand;
      this.content = content;
    }

    @Override public String toString() {
      return "ExplainCommand [sql: " + sqlCommand.sql + "]";
    }

    public void execute(boolean execute) throws Exception {
      if (execute) {
        final Statement statement = connection.createStatement();
        try {
          final ResultSet resultSet =
              statement.executeQuery("explain plan for " + sqlCommand.sql);
          try {
            final StringBuilder buf = new StringBuilder();
            while (resultSet.next()) {
              final String line = resultSet.getString(1);
              buf.append(line);
              if (!line.endsWith("\n")) {
                buf.append("\n");
              }
            }
            if (buf.length() == 0) {
              throw new AssertionError("explain returned 0 records");
            }
            writer.print(buf);
            writer.flush();
          } finally {
            resultSet.close();
          }
        } finally {
          statement.close();
        }
      } else {
        echo(content);
      }
      echo(lines);
    }
  }

  /** Command that prints the row type for the current query. */
  class TypeCommand extends SimpleCommand {
    private final SqlCommand sqlCommand;
    private final ImmutableList<String> content;

    public TypeCommand(List<String> lines,
        SqlCommand sqlCommand,
        ImmutableList<String> content) {
      super(lines);
      this.sqlCommand = sqlCommand;
      this.content = content;
    }

    @Override public String toString() {
      return "TypeCommand [sql: " + sqlCommand.sql + "]";
    }

    public void execute(boolean execute) throws Exception {
      if (execute) {
        final PreparedStatement statement =
            connection.prepareStatement(sqlCommand.sql);
        try {
          final ResultSetMetaData metaData = statement.getMetaData();
          final StringBuilder buf = new StringBuilder();
          for (int i = 1, n = metaData.getColumnCount(); i <= n; i++) {
            final String label = metaData.getColumnLabel(i);
            final String typeName = metaData.getColumnTypeName(i);
            buf.append(label)
                .append(' ')
                .append(typeName);
            final int precision = metaData.getPrecision(i);
            if (precision > 0) {
              buf.append("(")
                  .append(precision);
              final int scale = metaData.getScale(i);
              if (scale > 0) {
                buf.append(", ")
                    .append(scale);
              }
              buf.append(")");
            }
            final int nullable = metaData.isNullable(i);
            if (nullable == DatabaseMetaData.columnNoNulls) {
              buf.append(" NOT NULL");
            }
            buf.append("\n");
          }
          writer.print(buf);
          writer.flush();
        } finally {
          statement.close();
        }
      } else {
        echo(content);
      }
      echo(lines);
    }
  }

  /** Command that executes a SQL statement. */
  private class SqlCommand extends SimpleCommand {
    private final String sql;

    protected SqlCommand(List<String> lines, String sql, List<String> output) {
      super(lines);
      this.sql = Preconditions.checkNotNull(sql);
    }

    public void execute(boolean execute) throws Exception {
      echo(lines);
    }
  }

  /** Creates a connection for a given name.
   *
   * <p>It is kind of a directory service.
   *
   * <p>Caller must close the connection. */
  public interface ConnectionFactory {
    /** Creates a connection to the named database or reference database.
     *
     * <p>Returns null if the database is not known
     * (except {@link UnsupportedConnectionFactory}.
     *
     * @param name Name of the database
     * @param reference Whether we require a real connection or a reference
     *                  connection
     */
    Connection connect(String name, boolean reference) throws Exception;
  }

  @Deprecated // will be removed before 0.9
  public interface NewConnectionFactory extends ConnectionFactory {
  }

  /** Property whose value may be set. */
  enum Property {
    OUTPUTFORMAT
  }

  /** Command that defines the current SQL statement.
   *
   * @see CheckResultCommand
   * @see ExplainCommand
   * @see UpdateCommand
   */
  class SetCommand extends SimpleCommand {
    private final Property property;
    private final Object value;

    public SetCommand(List<String> lines, Property property, Object value) {
      super(lines);
      this.property = property;
      this.value = value;
    }

    public void execute(boolean execute) throws Exception {
      echo(lines);
      map.put(property, value);
    }
  }

  /** Command that executes a comment. (Does nothing.) */
  class CommentCommand extends SimpleCommand {
    public CommentCommand(List<String> lines) {
      super(lines);
    }

    public void execute(boolean execute) throws Exception {
      echo(lines);
    }
  }

  /** Command that disables execution of a block. */
  class IfCommand extends AbstractCommand {
    private final List<String> ifLines;
    private final List<String> endLines;
    private final Command command;
    private final List<String> variables;

    public IfCommand(List<String> ifLines, List<String> endLines,
        Command command, List<String> variables) {
      this.variables = ImmutableList.copyOf(variables);
      this.ifLines = ImmutableList.copyOf(ifLines);
      this.endLines = ImmutableList.copyOf(endLines);
      this.command = command;
    }

    public void execute(boolean execute) throws Exception {
      echo(ifLines);
      // Switch to a mode where we don't execute, just echo.
      boolean oldExecute = Quidem.this.execute;
      boolean newExecute;
      if (skip) {
        // If "skip" is set, stay in current (disabled) mode.
        newExecute = oldExecute;
      } else {
        // If "enable" is true, stay in the current mode.
        newExecute = getBoolean(variables);
      }
      command.execute(newExecute);
      echo(endLines);
    }
  }

  /** Command that switches to a mode where we skip executing the rest of the
   * input. The input is still printed. */
  class SkipCommand extends SimpleCommand {
    public SkipCommand(List<String> lines) {
      super(lines);
    }

    public void execute(boolean execute) throws Exception {
      echo(lines);
      // Switch to a mode where we don't execute, just echo.
      // Set "skip" so we don't leave that mode.
      skip = true;
      Quidem.this.execute = false;
    }
  }

  /** Command that executes a comment. (Does nothing.) */
  class CompositeCommand extends AbstractCommand {
    private final List<Command> commands;

    public CompositeCommand(List<Command> commands) {
      this.commands = commands;
    }

    public void execute(boolean execute) throws Exception {
      // We handle all RuntimeExceptions, all Exceptions, and a limited number
      // of Errors. If we don't understand an Error (e.g. OutOfMemoryError)
      // then we print it out, then abort.
      for (Command command : commands) {
        boolean abort = false;
        Throwable e = null;
        try {
          command.execute(execute && Quidem.this.execute);
        } catch (RuntimeException e0) {
          e = e0;
        } catch (Exception e0) {
          e = e0;
        } catch (AssertionError e0) {
          // We handle a limited number of errors.
          e = e0;
        } catch (Throwable e0) {
          e = e0;
          abort = true;
        }
        if (e != null) {
          command.execute(false); // echo the command
          writer.println("Error while executing command " + command);
          stack(e, writer);
          if (abort) {
            throw (Error) e;
          }
        }
      }
    }
  }
}

// End Quidem.java
