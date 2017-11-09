/* 
 * Copyright (C) 2016 Stanford University
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package net.ellitron.ldbcsnbimpls.interactive.torc.util;

import net.ellitron.ldbcsnbimpls.interactive.core.SnbEntity;
import net.ellitron.ldbcsnbimpls.interactive.core.SnbRelation;
import net.ellitron.ldbcsnbimpls.interactive.torc.TorcEntity;
import net.ellitron.torc.TorcGraph;
import net.ellitron.torc.TorcVertex;
import net.ellitron.torc.util.UInt128;

import org.apache.tinkerpop.gremlin.structure.T;
import org.apache.tinkerpop.gremlin.structure.Graph;

import org.apache.log4j.Logger;

import org.docopt.Docopt;

import java.io.BufferedReader;
import java.io.File;
import java.io.FilenameFilter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TimeZone;
import java.util.function.Consumer;
import org.apache.tinkerpop.gremlin.structure.VertexProperty;

/**
 * A utility for loading dataset files generated by the LDBC SNB Data
 * Generator[1] into TorcDB[2].
 * <p>
 * TODO:<br>
 * <ul>
 * <li>Make file searching more intelligent.</li>
 * </ul>
 * <p>
 * [1]: git@github.com:ldbc/ldbc_snb_datagen.git<br>
 * [2]: git@github.com:ellitron/torc.git<br>
 *
 * @author Jonathan Ellithorpe (jde@cs.stanford.edu)
 */
public class GraphLoader {

  private static final Logger logger = Logger.getLogger(GraphLoader.class);

  private static final String doc =
      "GraphLoader: A utility for loading dataset files generated by the\n"
      + "LDBC SNB Data Generator into TorcDB. Nodes, props, and edges are\n"
      + "loaded separately using the \"nodes\", \"props\" or \"edges\"\n"
      + "command. Nodes must be loaded first before props or edges can be\n"
      + "loaded.\n"
      + "\n"
      + "Usage:\n"
      + "  GraphLoader [options] nodes SOURCE\n"
      + "  GraphLoader [options] props SOURCE\n"
      + "  GraphLoader [options] edges SOURCE\n"
      + "  GraphLoader (-h | --help)\n"
      + "  GraphLoader --version\n"
      + "\n"
      + "Arguments:\n"
      + "  SOURCE  Directory containing SNB dataset files.\n"
      + "\n"
      + "Options:\n"
      + "  --coordLoc=<loc>  RAMCloud coordinator locator string\n"
      + "                    [default: tcp:host=127.0.0.1,port=12246].\n"
      + "  --masters=<n>     Number of RAMCloud master servers to use to\n"
      + "                    store the graph [default: 1].\n"
      + "  --graphName=<g>   The name to give the graph in RAMCloud\n"
      + "                    [default: graph].\n"
      + "  --numLoaders=<n>  The total number of loader instances loading\n"
      + "                    the graph in parallel [default: 1].\n"
      + "  --loaderIdx=<n>   Among numLoaders instance, which loader this\n"
      + "                    instance represents. Defines the partition of\n"
      + "                    the dataset this loader instance is responsible\n"
      + "                    for loading. Indexes start from 0.\n"
      + "                    [default: 0].\n"
      + "  --numThreads=<n>  The number of threads to use in this loader\n"
      + "                    instance.\n This loader's dataset partition is\n"
      + "                    divided up among this number of threads."
      + "                    [default: 1].\n"
      + "  --txSize=<n>      How many vertices/edges to load in a single\n"
      + "                    transaction. TorcDB transactions are buffered\n"
      + "                    locally before commit, and written in batch at\n"
      + "                    commit time. Setting this number appropriately\n"
      + "                    can therefore help to ammortize the RAMCloud\n"
      + "                    communication costs and increase loading\n"
      + "                    performance, although the right setting will\n"
      + "                    depend on system setup. [default: 128].\n"
      + "  --txRetries=<r>   The number of times to retry a failed\n"
      + "                    transaction before entering randomized backoff\n"
      + "                    mode. Transactions to RAMCloud may fail due to\n"
      + "                    timeouts, or sometimes conflicts on objects\n"
      + "                    (i.e. in the case of multithreaded loading).\n"
      + "                    [default: 10].\n"
      + "  --txBackoff=<t>   When a transaction has failed txRetries times\n"
      + "                    then the loader will wait a randomized amount\n"
      + "                    of time in the range [0,txBackoff], and retry\n"
      + "                    again. If the transaction fails again, the time\n"
      + "                    range is doubled in size (up to a ceiling of\n"
      + "                    txBoffCeil) and the process is repeated. When\n"
      + "                    the transaction succeeds the time range is\n"
      + "                    reset to begin at [0,txBackoff] for future\n"
      + "                    failures. The units of this parameter is in\n"
      + "                    milliseconds. [default: 1000].\n"
      + "  --txBoffCeil=<t>  Defines a ceiling on the time range in which to\n"
      + "                    choose a random sleep time before retrying a\n"
      + "                    transaction. In other words, defines the max\n"
      + "                    time range to be [0,txBoffCeil]. The units of\n"
      + "                    this parameter is in milliseconds.\n"
      + "                    [default: 10000].\n"
      + "  --reportInt=<i>   Number of seconds between reporting status to\n"
      + "                    the screen. [default: 10].\n"
      + "  --reportFmt=<s>   Format options for status report output.\n"
      + "                      L - Total lines processed per second.\n"
      + "                      l - Per thread lines processed per second.\n"
      + "                      F - Total files processed.\n"
      + "                      f - Per thread files processed.\n"
      + "                      X - Total tx failures.\n"
      + "                      x - Per thread tx failures.\n"
      + "                      D - Total disk read bandwidth in MB/s.\n"
      + "                      d - Per thread disk read bandwidth in KB/s.\n"
      + "                      T - Total time elapsed.\n"
      + "                    [default: LFDT].\n"
      + "  -h --help         Show this screen.\n"
      + "  --version         Show version.\n"
      + "\n";

  /**
   * Packs a unit of loading work for a loader thread to do. Includes a path to
   * the file to load, and what that file represents (either an SNB entity or
   * an SNB relation).
   */
  private static class LoadUnit {

    private SnbEntity entity;
    private SnbRelation relation;
    private Path filePath;
    private boolean isProperties;

    /**
     * Constructor for LoadUnit.
     *
     * @param entity The entity this file pertains to.
     * @param filePath The path to the file.
     * @param isProperties Whether or not this is a file containing properties
     * for the entity.
     */
    public LoadUnit(SnbEntity entity, Path filePath, boolean isProperties) {
      this.entity = entity;
      this.relation = null;
      this.filePath = filePath;
      this.isProperties = isProperties;
    }

    /**
     * Constructor for LoadUnit.
     *
     * @param relation The relations this file pertains to.
     * @param filePath The path to the file.
     */
    public LoadUnit(SnbRelation relation, Path filePath) {
      this.entity = null;
      this.relation = relation;
      this.filePath = filePath;
      this.isProperties = false;
    }

    public boolean isEntity() {
      return entity != null;
    }

    public boolean isProperties() {
      return isProperties;
    }

    public boolean isRelation() {
      return relation != null;
    }

    public SnbEntity getSnbEntity() {
      return entity;
    }

    public SnbRelation getSnbRelation() {
      return relation;
    }

    public Path getFilePath() {
      return filePath;
    }
  }

  /**
   * A set of per-thread loading statistics. Each loader thread continually
   * updates these statistics, while a statistics reporting thread with a
   * reference to each ThreadStats instance in the system regularly prints
   * statistic summaries to the screen.
   */
  private static class ThreadStats {

    /*
     * The total number of lines this thread has successfully processed and
     * loaded into the database.
     */
    public long linesProcessed;

    /*
     * The total number of bytes this thread has read from disk.
     */
    public long bytesReadFromDisk;

    /*
     * The total number of files this thread has successfully processed and
     * loaded into the database.
     */
    public long filesProcessed;

    /*
     * The total number of files this thread has been given to process.
     */
    public long totalFilesToProcess;

    /*
     * Number of times this thread has attempted to commit a transaction but
     * the transaction failed.
     */
    public long txFailures;

    /**
     * Constructor.
     */
    public ThreadStats() {
      this.linesProcessed = 0;
      this.bytesReadFromDisk = 0;
      this.filesProcessed = 0;
      this.totalFilesToProcess = 0;
      this.txFailures = 0;
    }

    /**
     * Copy constructor.
     *
     * @param stats ThreadStats object to copy.
     */
    public ThreadStats(ThreadStats stats) {
      this.linesProcessed = stats.linesProcessed;
      this.bytesReadFromDisk = stats.bytesReadFromDisk;
      this.filesProcessed = stats.filesProcessed;
      this.totalFilesToProcess = stats.totalFilesToProcess;
      this.txFailures = stats.txFailures;
    }
  }

  /**
   * A loader thread which takes a set of files to load and loads them
   * sequentially.
   */
  private static class LoaderThread implements Runnable {

    private final Graph graph;
    private final List<LoadUnit> loadList;
    private final int startIndex;
    private final int length;
    private final int txSize;
    private final int txRetries;
    private final int txBackoff;
    private final int txBoffCeil;
    private final ThreadStats stats;

    /*
     * Used for parsing dates in the original dataset files output by the data
     * generator, and converting them to milliseconds since Jan. 1 9170. We
     * store dates in this form in TorcDB.
     */
    private final SimpleDateFormat birthdayDateFormat;
    private final SimpleDateFormat creationDateDateFormat;

    /*
     * Used for generating random backoff times in the event of repeated
     * transaction failures.
     */
    private final Random rand;

    /**
     * Constructor for LoaderThread.
     *
     * @param graph Graph into which to load the files.
     * @param loadList Master list of all files in the dataset. All loader
     * threads have a copy of this.
     * @param startIndex The index in the load list at which this thread's
     * loading work starts.
     * @param length The segment length in the load list for this thread.
     * @param txSize The number of lines to process within a single
     * transaction.
     * @param txRetries The maximum number of times to retry a failed
     * transaction before entering back-off mode.
     * @param txBackoff The time range, in milliseconds, in which to select a
     * random time to back-off in the case of txRetries transaction failures.
     * Time range is doubled upon repeated failures after backing off.
     * @param txBackoff The maximum time range, in milliseconds, in which to
     * select a random time to back-off in the case of txRetries transaction
     * failures.
     * @param stats ThreadStats instance to update with loading statistics
     * info.
     */
    public LoaderThread(Graph graph, List<LoadUnit> loadList, int startIndex,
        int length, int txSize, int txRetries, int txBackoff, int txBoffCeil,
        ThreadStats stats) {
      this.graph = graph;
      this.loadList = loadList;
      this.startIndex = startIndex;
      this.length = length;
      this.txSize = txSize;
      this.txRetries = txRetries;
      this.txBackoff = txBackoff;
      this.txBoffCeil = txBoffCeil;
      this.stats = stats;

      this.birthdayDateFormat =
          new SimpleDateFormat("yyyy-MM-dd");
      this.birthdayDateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
      this.creationDateDateFormat =
          new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ");
      this.creationDateDateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));

      this.rand = new Random();
    }

    @Override
    public void run() {
      // Update this thread's total file load stat.
      stats.totalFilesToProcess = length;

      // Load every file in the range [startIndex, startIndex + length)
      for (int fIndex = startIndex; fIndex < startIndex + length; fIndex++) {
        LoadUnit loadUnit = loadList.get(fIndex);
        Path path = loadUnit.getFilePath();

        BufferedReader inFile;
        try {
          inFile = Files.newBufferedReader(path, StandardCharsets.UTF_8);
        } catch (IOException ex) {
          throw new RuntimeException(String.format("Encountered error opening "
              + "file %s", path.getFileName()));
        }

        // First line of the file contains the column headers.
        String[] fieldNames;
        try {
          fieldNames = inFile.readLine().split("\\|");
        } catch (IOException ex) {
          throw new RuntimeException(String.format("Encountered error reading "
              + "header line of file %s", path.getFileName()));
        }

        Consumer<List<String>> lineGobbler;
        if (loadUnit.isEntity()) {
          SnbEntity snbEntity = loadUnit.getSnbEntity();

          long idSpace = TorcEntity.valueOf(snbEntity).idSpace;
          String vertexLabel = TorcEntity.valueOf(snbEntity).label;

          if (loadUnit.isProperties()) {
            lineGobbler = (List<String> lineBuffer) -> {
              for (int i = 0; i < lineBuffer.size(); i++) {
                String[] fieldValues = lineBuffer.get(i).split("\\|");

                TorcVertex vertex = (TorcVertex) graph.vertices(
                    new UInt128(idSpace, Long.decode(fieldValues[0])))
                    .next();

                for (int j = 1; j < fieldValues.length; j++) {
                  vertex.property(VertexProperty.Cardinality.list,
                      fieldNames[j], fieldValues[j]);
                }
              }
            };
          } else {
            lineGobbler = (List<String> lineBuffer) -> {
              for (int i = 0; i < lineBuffer.size(); i++) {
                /*
                 * Here we parse the line into a map of the entity's
                 * properties. Date-type fields (birthday, creationDate, ...)
                 * need to be converted to the number of milliseconds since
                 * January 1, 1970, 00:00:00 GMT. This is the format expected
                 * to be returned for these fields by LDBC SNB benchmark
                 * queries, although the format in the dataset files are things
                 * like "1989-12-04" and "2010-03-17T23:32:10.447+0000". We
                 * could do this conversion "live" during the benchmark, but
                 * that would detract from the performance numbers' reflection
                 * of true database performance since it would add to the
                 * client-side query processing overhead.
                 *
                 * We also do special processing for array-type fields (emails,
                 * speaks, ...), splitting the field value by the array
                 * separator and creating a property for each of the elements.
                 */
                String[] fieldValues = lineBuffer.get(i).split("\\|");
                Map<Object, Object> propMap = new HashMap<>();
                for (int j = 0; j < fieldValues.length; j++) {
                  try {
                    if (fieldNames[j].equals("id")) {
                      propMap.put(T.id,
                          new UInt128(idSpace, Long.decode(fieldValues[j])));
                    } else if (fieldNames[j].equals("birthday")) {
                      propMap.put(fieldNames[j], String.valueOf(
                          birthdayDateFormat.parse(fieldValues[j])
                          .getTime()));
                    } else if (fieldNames[j].equals("creationDate")) {
                      propMap.put(fieldNames[j], String.valueOf(
                          creationDateDateFormat.parse(fieldValues[j])
                          .getTime()));
                    } else if (fieldNames[j].equals("joinDate")) {
                      propMap.put(fieldNames[j], String.valueOf(
                          creationDateDateFormat.parse(fieldValues[j])
                          .getTime()));
                    } else if (fieldNames[j].equals("emails")
                        || fieldNames[j].equals("speaks")) {
                      String[] elements = fieldValues[j].split(";");
                      for (String elem : elements) {
                        if (elem.length() != 0) {
                          propMap.put(fieldNames[j], elem);
                        }
                      }
                    } else {
                      propMap.put(fieldNames[j], fieldValues[j]);
                    }
                  } catch (Exception ex) {
                    throw new RuntimeException(String.format("Encountered "
                        + "error processing field %s with value %s of line %d "
                        + "in the line buffer. Line: \"%s\"", fieldNames[j],
                        fieldValues[j], i, lineBuffer.get(i)), ex);
                  }
                }

                // Don't forget to add the label!
                propMap.put(T.label, vertexLabel);

                List<Object> keyValues = new ArrayList<>();
                propMap.forEach((key, val) -> {
                  keyValues.add(key);
                  keyValues.add(val);
                });

                graph.addVertex(keyValues.toArray());
              }
            };
          }
        } else {
          SnbRelation snbRelation = loadUnit.getSnbRelation();

          long tailIdSpace = TorcEntity.valueOf(snbRelation.tail).idSpace;
          long headIdSpace = TorcEntity.valueOf(snbRelation.head).idSpace;
          String edgeLabel = snbRelation.name;

          lineGobbler = (List<String> lineBuffer) -> {
            for (int i = 0; i < lineBuffer.size(); i++) {
              String[] fieldValues = lineBuffer.get(i).split("\\|");

              TorcVertex tailVertex = (TorcVertex) graph.vertices(
                  new UInt128(tailIdSpace, Long.decode(fieldValues[0])))
                  .next();
              TorcVertex headVertex = (TorcVertex) graph.vertices(
                  new UInt128(headIdSpace, Long.decode(fieldValues[1])))
                  .next();

              Map<Object, Object> propMap = new HashMap<>();
              for (int j = 2; j < fieldValues.length; j++) {
                try {
                  if (fieldNames[j].equals("creationDate")
                      || fieldNames[j].equals("joinDate")) {
                    propMap.put(fieldNames[j], String.valueOf(
                        creationDateDateFormat.parse(fieldValues[j])
                        .getTime()));
                  } else {
                    propMap.put(fieldNames[j], fieldValues[j]);
                  }
                } catch (Exception ex) {
                  throw new RuntimeException(String.format("Encountered "
                      + "error processing field %s with value %s of line %d "
                      + "in the line buffer. Line: \"%s\"", fieldNames[j],
                      fieldValues[j], i, lineBuffer.get(i)), ex);
                }
              }

              List<Object> keyValues = new ArrayList<>();
              propMap.forEach((key, val) -> {
                keyValues.add(key);
                keyValues.add(val);
              });

              tailVertex.addEdge(edgeLabel, headVertex,
                  keyValues.toArray());

              /*
               * If this is not an undirected edge, then add the reverse edge
               * from head to tail.
               */
              if (!snbRelation.directed) {
                headVertex.addEdge(edgeLabel, tailVertex,
                    keyValues.toArray());
              }
            }
          };
        }

        // Keep track of what lines we're on in this file.
        long localLinesProcessed = 0;

        boolean hasLinesLeft = true;
        while (hasLinesLeft) {
          /*
           * Buffer txSize lines at a time from the input file and keep it
           * around until commit time. If the commit succeeds we can forget
           * about it, otherwise we'll use it again to retry the transaction.
           */
          String line;
          List<String> lineBuffer = new ArrayList<>(txSize);
          try {
            while ((line = inFile.readLine()) != null) {
              lineBuffer.add(line);

              /*
               * Estimate the bytes we've read and add it to the
               * bytes-read-from-disk statistic. The file encoding is UTF-8,
               * which means ASCII characters are encoded with 1 byte, which
               * the vast majority of characters in the file ought to be.
               */
              stats.bytesReadFromDisk += line.length();

              if (lineBuffer.size() == txSize) {
                break;
              }
            }
          } catch (IOException ex) {
            throw new RuntimeException(String.format("Encountered error "
                + "reading lines of file %s", path.getFileName()));
          }

          // Catch when we've read all the lines in the file.
          if (line == null) {
            hasLinesLeft = false;
          }

          /*
           * Parse the lines in the buffer and write them into the database. If
           * the commit fails for any reason, retry the transaction up to
           * txRetries number of times. After that, enter multiplicative
           * backoff mode.
           */
          int txFailCount = 0;
          int backoffMultiplier = 1;
          while (true) {
            try {
              lineGobbler.accept(lineBuffer);
            } catch (Exception ex) {
              throw new RuntimeException(String.format(
                  "Encountered error processing lines in range [%d, %d] of "
                  + "file %s",
                  localLinesProcessed + 2,
                  localLinesProcessed + 1 + lineBuffer.size(),
                  path.getFileName()), ex);
            }

            try {
              graph.tx().commit();
              localLinesProcessed += lineBuffer.size();
              stats.linesProcessed += lineBuffer.size();
              break;
            } catch (Exception e) {
              /*
               * The transaction failed due to either a conflict or a timeout.
               * In this case we want to retry the transaction, but only up to
               * the txRetries limit.
               */
              txFailCount++;
              stats.txFailures++;

              if (txFailCount > txRetries) {
                try {
                  int sleepTimeBound;
                  if (backoffMultiplier * txBackoff < txBoffCeil) {
                    sleepTimeBound = backoffMultiplier * txBackoff;
                    backoffMultiplier *= 2;
                  } else {
                    sleepTimeBound = txBoffCeil;
                  }

                  int sleepTime = rand.nextInt(sleepTimeBound + 1);

                  logger.debug(String.format("Thread %d txFailCount reached %d "
                      + "on lines [%d, %d] of file %s. Sleeping for %dms.",
                      Thread.currentThread().getId(), txFailCount,
                      localLinesProcessed + 2,
                      localLinesProcessed + 1 + lineBuffer.size(),
                      path.getFileName(), sleepTime));

                  Thread.sleep(sleepTime);
                } catch (InterruptedException ex) {
                  // Our slumber has been cut short, most likely due to a
                  // terminate signal. In this case we should terminate.
                  try {
                    inFile.close();
                  } catch (IOException ex1) {
                    throw new RuntimeException(String.format("Encountered "
                        + "error closing file %s", path.getFileName()));
                  }

                  return;
                }
              }
            }
          }
        }

        try {
          inFile.close();
        } catch (IOException ex) {
          throw new RuntimeException(String.format("Encountered error closing "
              + "file %s", path.getFileName()));
        }
        stats.filesProcessed++;
      }
    }
  }

  /**
   * A thread which reports statistics on the loader threads in the system at a
   * set interval. This thread gets information on each thread via a shared
   * ThreadStats object with each thread.
   */
  private static class StatsReporterThread implements Runnable {

    private List<ThreadStats> threadStats;
    private long reportInterval;
    private String formatString;

    /**
     * Constructor for StatsReporterThread.
     *
     * @param threadStats List of all the shared ThreadStats objects, one per
     * thread.
     * @param reportInterval Interval, in seconds, to report statistics to the
     * screen.
     */
    public StatsReporterThread(List<ThreadStats> threadStats,
        long reportInterval, String formatString) {
      this.threadStats = threadStats;
      this.reportInterval = reportInterval;
      this.formatString = formatString;
    }

    @Override
    public void run() {
      try {
//      L - Total lines processed per second.
//      l - Per thread lines processed per second.
//      F - Total files processed per second.
//      f - Per thread files processed per second.
//      D - Total disk read bandwidth in MB/s.
//      d - Per thread disk read bandwidth in KB/s.
//      T - Total time elapsed.

        // Print the column headers.
        String colFormatStr = "%10s";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < threadStats.size(); i++) {
          if (formatString.contains("l")) {
            sb.append(String.format(colFormatStr, i + ".l"));
          }

          if (formatString.contains("f")) {
            sb.append(String.format(colFormatStr, i + ".f"));
          }

          if (formatString.contains("x")) {
            sb.append(String.format(colFormatStr, i + ".x"));
          }

          if (formatString.contains("d")) {
            sb.append(String.format(colFormatStr, i + ".d"));
          }
        }

        if (formatString.contains("L")) {
          sb.append(String.format(colFormatStr, "L"));
        }

        if (formatString.contains("F")) {
          sb.append(String.format(colFormatStr, "F"));
        }

        if (formatString.contains("X")) {
          sb.append(String.format(colFormatStr, "X"));
        }

        if (formatString.contains("D")) {
          sb.append(String.format(colFormatStr, "D"));
        }

        if (formatString.contains("T")) {
          sb.append(String.format(colFormatStr, "T"));
        }

        String headerString = sb.toString();

        System.out.println(headerString);

        // Capture the thread stats now before entering report loop. We'll use
        // this to start reporting diffs right away.
        List<ThreadStats> lastThreadStats = new ArrayList<>();
        for (int i = 0; i < threadStats.size(); i++) {
          lastThreadStats.add(new ThreadStats(threadStats.get(i)));
        }

        long startTime = System.currentTimeMillis();
        while (true) {
          Thread.sleep(reportInterval * 1000l);

          // Time elapsed since beginning loading.
          long timeElapsed = (System.currentTimeMillis() - startTime) / 1000l;

          sb = new StringBuilder();
          long totalCurrLineRate = 0;
          long totalCurrByteRate = 0;
          long totalFilesProcessed = 0;
          long totalTxFailures = 0;
          long totalFilesToProcess = 0;
          for (int i = 0; i < threadStats.size(); i++) {
            ThreadStats lastStats = lastThreadStats.get(i);
            ThreadStats currStats = threadStats.get(i);

            long linesProcessed =
                currStats.linesProcessed - lastStats.linesProcessed;
            long bytesReadFromDisk =
                currStats.bytesReadFromDisk - lastStats.bytesReadFromDisk;

            long currLineRate = linesProcessed / reportInterval;
            long currByteRate = bytesReadFromDisk / reportInterval;

            if (formatString.contains("l")) {
              sb.append(String.format(colFormatStr, currLineRate));
            }

            if (formatString.contains("f")) {
              sb.append(String.format(colFormatStr, String.format("(%d/%d)",
                  currStats.filesProcessed, currStats.totalFilesToProcess)));
            }

            if (formatString.contains("x")) {
              sb.append(String.format(colFormatStr, currStats.txFailures));
            }

            if (formatString.contains("d")) {
              sb.append(String.format(colFormatStr, (currByteRate / 1000l)
                  + "KB/s"));
            }

            totalCurrLineRate += currLineRate;
            totalCurrByteRate += currByteRate;
            totalFilesProcessed += currStats.filesProcessed;
            totalTxFailures += currStats.txFailures;
            totalFilesToProcess += currStats.totalFilesToProcess;
          }

          if (formatString.contains("L")) {
            sb.append(String.format(colFormatStr, totalCurrLineRate));
          }

          if (formatString.contains("F")) {
            sb.append(String.format(colFormatStr, String.format("(%d/%d)",
                totalFilesProcessed, totalFilesToProcess)));
          }

          if (formatString.contains("X")) {
            sb.append(String.format(colFormatStr, totalTxFailures));
          }

          if (formatString.contains("D")) {
            sb.append(String.format(colFormatStr,
                (totalCurrByteRate / 1000000l) + "MB/s"));
          }

          if (formatString.contains("T")) {
            sb.append(String.format(colFormatStr, (timeElapsed / 60l) + "m"));
          }

          System.out.println(sb.toString());

          lastThreadStats.clear();
          for (int i = 0; i < threadStats.size(); i++) {
            lastThreadStats.add(new ThreadStats(threadStats.get(i)));
          }

          // Check if we are done loading. If so, exit.
          if (totalFilesProcessed == totalFilesToProcess) {
            break;
          }
        }
      } catch (InterruptedException ex) {
        // This is fine, we're probably being terminated, in which case we 
        // should just go ahead and terminate.
      }
    }
  }

  public static void main(String[] args)
      throws FileNotFoundException, IOException, ParseException, InterruptedException {
    Map<String, Object> opts =
        new Docopt(doc).withVersion("GraphLoader 1.0").parse(args);

    int numLoaders = Integer.decode((String) opts.get("--numLoaders"));
    int loaderIdx = Integer.decode((String) opts.get("--loaderIdx"));
    int numThreads = Integer.decode((String) opts.get("--numThreads"));
    int txSize = Integer.decode((String) opts.get("--txSize"));
    int txRetries = Integer.decode((String) opts.get("--txRetries"));
    int txBackoff = Integer.decode((String) opts.get("--txBackoff"));
    int txBoffCeil = Integer.decode((String) opts.get("--txBoffCeil"));
    long reportInterval = Long.decode((String) opts.get("--reportInt"));
    String formatString = (String) opts.get("--reportFmt");
    String inputDir = (String) opts.get("SOURCE");

    String command;
    if ((Boolean) opts.get("nodes")) {
      command = "nodes";
    } else if ((Boolean) opts.get("props")) {
      command = "props";
    } else {
      command = "edges";
    }

    System.out.println(String.format(
        "GraphLoader: {coordLoc: %s, masters: %s, graphName: %s, "
        + "numLoaders: %d, loaderIdx: %d, numThreads: %d, txSize: %d, "
        + "txRetries: %d, txBackoff: %d, txBoffCeil: %d, "
        + "reportFmt: %s, inputDir: %s, command: %s}",
        (String) opts.get("--coordLoc"),
        (String) opts.get("--masters"),
        (String) opts.get("--graphName"),
        numLoaders,
        loaderIdx,
        numThreads,
        txSize,
        txRetries,
        txBackoff,
        txBoffCeil,
        formatString,
        inputDir,
        command));

    // Open a new TorcGraph with the supplied configuration.
    Map<String, String> config = new HashMap<>();
    config.put(TorcGraph.CONFIG_COORD_LOCATOR,
        (String) opts.get("--coordLoc"));
    config.put(TorcGraph.CONFIG_GRAPH_NAME,
        (String) opts.get("--graphName"));
    config.put(TorcGraph.CONFIG_NUM_MASTER_SERVERS,
        (String) opts.get("--masters"));

    Graph graph = TorcGraph.open(config);

    /*
     * Construct a list of all the files that need to be loaded. If we are
     * loading nodes then this list will be a list of all the node files that
     * need to be loaded. Otherwise it will be a list of edge files.
     */
    List<LoadUnit> loadList = new ArrayList<>();
    File dir = new File(inputDir);
    if (command.equals("nodes")) {
      for (SnbEntity snbEntity : SnbEntity.values()) {
        File [] fileList = dir.listFiles(new FilenameFilter() {
              @Override
              public boolean accept(File dir, String name) {
                return name.matches(
                    "^" + snbEntity.name + "_[0-9]+_[0-9]+\\.csv");
              }
            });

        if (fileList.length > 0) {
          for (File f : fileList) {
            loadList.add(new LoadUnit(snbEntity, f.toPath(), false));
            System.out.println(String.format("Found file for %s nodes (%s)",
                snbEntity.name, f.getName()));
          }
        } else {
          System.out.println(String.format("Missing files for %s nodes",
              snbEntity.name));
        }
      }

      System.out.println(String.format("Found %d total node files",
          loadList.size()));
    } else if (command.equals("props")) {
      File [] fileList = dir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
              return name.matches(
                  "^person_email_emailaddress_[0-9]+_[0-9]+\\.csv");
            }
          });

      for (File f : fileList) {
        loadList.add(new LoadUnit(SnbEntity.PERSON, f.toPath(), true));
        System.out.println(String.format(
            "Found file for person email properties (%s)", f.getName()));
      }

      fileList = dir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
              return name.matches(
                  "^person_speaks_language_[0-9]+_[0-9]+\\.csv");
            }
          });

      for (File f : fileList) {
        loadList.add(new LoadUnit(SnbEntity.PERSON, f.toPath(), true));
        System.out.println(String.format(
            "Found file for person speaks properties (%s)", f.getName()));
      }

      System.out.println(String.format("Found %d total property files",
          loadList.size()));
    } else {
      for (SnbRelation snbRelation : SnbRelation.values()) {

        String edgeFormatStr;
        if (snbRelation.directed) {
          edgeFormatStr = "(%s)-[%s]->(%s)";
        } else {
          edgeFormatStr = "(%s)-[%s]-(%s)";
        }

        String edgeStr = String.format(edgeFormatStr,
            snbRelation.tail.name,
            snbRelation.name,
            snbRelation.head.name);

        File [] fileList = dir.listFiles(new FilenameFilter() {
              @Override
              public boolean accept(File dir, String name) {
                return name.matches(
                    "^" + snbRelation.tail.name + 
                    "_" + snbRelation.name + 
                    "_" + snbRelation.head.name + 
                    "_[0-9]+_[0-9]+\\.csv");
              }
            });

        if (fileList.length > 0) {
          for (File f : fileList) {
            loadList.add(new LoadUnit(snbRelation, f.toPath()));
            System.out.println(String.format("Found file for %s edges (%s)",
                edgeStr, f.getName()));
          }
        } else {
          System.out.println(String.format("Missing file for %s edges",
              edgeStr));
        }
      }

      System.out.println(String.format("Found %d total edge files",
          loadList.size()));
    }

    /*
     * Calculate the segment of the list that this loader instance is
     * responsible for loading.
     */
    int q = loadList.size() / numLoaders;
    int r = loadList.size() % numLoaders;

    int loadSize;
    int loadOffset;
    if (loaderIdx < r) {
      loadSize = q + 1;
      loadOffset = (q + 1) * loaderIdx;
    } else {
      loadSize = q;
      loadOffset = ((q + 1) * r) + (q * (loaderIdx - r));
    }

    /*
     * Divvy up the load and start the threads.
     */
    List<Thread> threads = new ArrayList<>();
    List<ThreadStats> threadStats = new ArrayList<>(numThreads);
    for (int i = 0; i < numThreads; i++) {
      int qt = loadSize / numThreads;
      int rt = loadSize % numThreads;

      int threadLoadSize;
      int threadLoadOffset;
      if (i < rt) {
        threadLoadSize = qt + 1;
        threadLoadOffset = (qt + 1) * i;
      } else {
        threadLoadSize = qt;
        threadLoadOffset = ((qt + 1) * rt) + (qt * (i - rt));
      }

      threadLoadOffset += loadOffset;

      ThreadStats stats = new ThreadStats();

      threads.add(new Thread(new LoaderThread(graph, loadList,
          threadLoadOffset, threadLoadSize, txSize, txRetries, txBackoff,
          txBoffCeil, stats)));

      threads.get(i).start();

      threadStats.add(stats);
    }

    /*
     * Start stats reporting thread.
     */
    (new Thread(new StatsReporterThread(threadStats, reportInterval,
        formatString))).start();

    /*
     * Join on all the loader threads.
     */
    for (Thread thread : threads) {
      thread.join();
    }
  }
}
