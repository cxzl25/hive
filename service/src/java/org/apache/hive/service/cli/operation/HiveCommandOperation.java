/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hive.service.cli.operation;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.hadoop.hive.common.io.SessionStream;
import org.apache.hadoop.hive.metastore.api.Schema;
import org.apache.hadoop.hive.ql.processors.CommandProcessor;
import org.apache.hadoop.hive.ql.processors.CommandProcessorException;
import org.apache.hadoop.hive.ql.processors.CommandProcessorResponse;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.apache.hive.service.ServiceUtils;
import org.apache.hive.service.cli.FetchOrientation;
import org.apache.hive.service.cli.HiveSQLException;
import org.apache.hive.service.cli.OperationState;
import org.apache.hive.service.cli.RowSet;
import org.apache.hive.service.cli.RowSetFactory;
import org.apache.hive.service.cli.TableSchema;
import org.apache.hive.service.cli.session.HiveSession;

/**
 * Executes a HiveCommand
 */
public class HiveCommandOperation extends ExecuteStatementOperation {
  protected final CommandProcessor commandProcessor;
  private TableSchema resultSchema = null;

  /**
   * For processors other than Hive queries (Driver), they output to session.out (a temp file)
   * first and the fetchOne/fetchN/fetchAll functions get the output from pipeIn.
   */
  private BufferedReader resultReader;

  protected HiveCommandOperation(HiveSession parentSession, String statement,
      CommandProcessor commandProcessor, Map<String, String> confOverlay) {
    super(parentSession, statement, confOverlay);
    this.commandProcessor = commandProcessor;
    setupSessionIO(parentSession.getSessionState());
  }

  private void setupSessionIO(SessionState sessionState) {
    try {
      log.info("Putting temp output to file " + sessionState.getTmpOutputFile()
          + " and error output to file " + sessionState.getTmpErrOutputFile());
      sessionState.in = null; // hive server's session input stream is not used
      // open a per-session file in auto-flush mode for writing temp results and tmp error output
      sessionState.out = new SessionStream(
          new FileOutputStream(sessionState.getTmpOutputFile()), true,
          StandardCharsets.UTF_8.name());
      sessionState.err = new SessionStream(
          new FileOutputStream(sessionState.getTmpErrOutputFile()), true,
          StandardCharsets.UTF_8.name());
    } catch (IOException e) {
      log.error("Error in creating temp output file", e);

      // Close file streams to avoid resource leaking
      ServiceUtils.cleanup(log, parentSession.getSessionState().out, parentSession.getSessionState().err);

      try {
        sessionState.in = null;
        sessionState.out =
            new SessionStream(System.out, true, StandardCharsets.UTF_8.name());
        sessionState.err =
            new SessionStream(System.err, true, StandardCharsets.UTF_8.name());
      } catch (UnsupportedEncodingException ee) {
        log.error("Error creating PrintStream", e);
        sessionState.out = null;
        sessionState.err = null;
      }
    }
  }


  private void tearDownSessionIO() {
    ServiceUtils.cleanup(log, parentSession.getSessionState().out, parentSession.getSessionState().err);
  }

  @Override
  public void runInternal() throws HiveSQLException {
    setState(OperationState.RUNNING);
    try {
      String command = getStatement().trim();
      String[] tokens = command.split("\\s");
      String commandArgs = command.substring(tokens[0].length()).trim();

      CommandProcessorResponse response = commandProcessor.run(commandArgs);
      Schema schema = response.getSchema();
      if (schema != null) {
        setHasResultSet(true);
        resultSchema = new TableSchema(schema);
      } else {
        setHasResultSet(false);
        resultSchema = new TableSchema();
      }
      if (response.getMessage() != null) {
        log.info(response.getMessage());
      }
    } catch (CommandProcessorException e) {
      setState(OperationState.ERROR);
      throw toSQLException("Error while processing statement", e, queryState.getQueryId());
    } catch (Exception e) {
      setState(OperationState.ERROR);
      throw new HiveSQLException("Error running query: " + e.toString(), e);
    }
    setState(OperationState.FINISHED);
  }

  /* (non-Javadoc)
   * @see org.apache.hive.service.cli.operation.Operation#close()
   */
  @Override
  public void close() throws HiveSQLException {
    setState(OperationState.CLOSED);
    tearDownSessionIO();
    cleanTmpFile();
    cleanupOperationLog(0);
  }

  /* (non-Javadoc)
   * @see org.apache.hive.service.cli.operation.Operation#getResultSetSchema()
   */
  @Override
  public TableSchema getResultSetSchema() throws HiveSQLException {
    return resultSchema;
  }

  /* (non-Javadoc)
   * @see org.apache.hive.service.cli.operation.Operation#getNextRowSet(org.apache.hive.service.cli.FetchOrientation, long)
   */
  @Override
  public RowSet getNextRowSet(FetchOrientation orientation, long maxRows) throws HiveSQLException {
    validateDefaultFetchOrientation(orientation);
    if (orientation.equals(FetchOrientation.FETCH_FIRST)) {
      resetResultReader();
    }
    List<String> rows = readResults((int) maxRows);
    RowSet rowSet = RowSetFactory.create(resultSchema, getProtocolVersion(), false);

    // cannot do delimited split for some commands like "dfs -cat" that prints the contents of file which may have
    // different delimiter. so we will split only when the resultSchema has more than 1 column
    if (resultSchema != null && resultSchema.getSize() > 1) {
      for (String row : rows) {
        rowSet.addRow(row.split("\\t"));
      }
    } else {
      for (String row : rows) {
        rowSet.addRow(new String[]{row});
      }
    }
    return rowSet;
  }

  /**
   * Reads the temporary results for non-Hive (non-Driver) commands to the
   * resulting List of strings.
   * @param nLines number of lines read at once. If it is <= 0, then read all lines.
   */
  private List<String> readResults(int nLines) throws HiveSQLException {
    if (resultReader == null) {
      SessionState sessionState = getParentSession().getSessionState();
      File tmp = sessionState.getTmpOutputFile();
      try {
        resultReader = new BufferedReader(new FileReader(tmp));
      } catch (FileNotFoundException e) {
        throw new HiveSQLException("File " + tmp + " not found", e);
      }
    }
    List<String> results = new ArrayList<String>();

    for (int i = 0; i < nLines || nLines <= 0; ++i) {
      try {
        String line = resultReader.readLine();
        if (line == null) {
          // reached the end of the result file
          break;
        } else {
          results.add(line);
        }
      } catch (IOException e) {
        throw new HiveSQLException("Unable to read line from file", e);
      }
    }
    return results;
  }

  private void cleanTmpFile() {
    resetResultReader();
    SessionState sessionState = getParentSession().getSessionState();
    sessionState.deleteTmpOutputFile();
    sessionState.deleteTmpErrOutputFile();
  }

  private void resetResultReader() {
    if (resultReader != null) {
      ServiceUtils.cleanup(log, resultReader);
      resultReader = null;
    }
  }

  @Override
  public void cancel(OperationState stateAfterCancel) throws HiveSQLException {
    throw new UnsupportedOperationException("HiveCommandOperation.cancel()");
  }
}
