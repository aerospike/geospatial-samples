/*
 * Copyright 2016 Aerospike, Inc.
 *
 * Portions may be licensed to Aerospike, Inc. under one or more contributor
 * license agreements WHICH ARE COMPATIBLE WITH THE APACHE LICENSE, VERSION 2.0.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.aerospike.delivery.db.aerospike;


import com.aerospike.client.*;
import com.aerospike.client.policy.ClientPolicy;
import com.aerospike.client.policy.ScanPolicy;
import com.aerospike.delivery.*;
import com.aerospike.delivery.db.base.Database;
import com.aerospike.delivery.util.OurExecutor;
import org.apache.commons.cli.CommandLine;

import java.time.Instant;

public class AerospikeDatabase extends Database {

  private Parameters parameters;
  public String namespace;
  public AerospikeClient client;
  private final ClientPolicy clientPolicy;


  public AerospikeDatabase() {
    namespace = "test"; //default
    clientPolicy = new ClientPolicy();
    clientPolicy.failIfNotConnected = true;
  }

  public boolean parseOptions(CommandLine commandLine) {
    parameters = Parameters.parseServerParameters(commandLine);
    if (parameters == null) {
      return false;
    }
    this.namespace = parameters.namespace;
    clientPolicy.user = parameters.user;
    clientPolicy.password = parameters.password;
    clientPolicy.failIfNotConnected = true;
    return true;
  }

  @Override
  public boolean connect() {
    client = new AerospikeClient(clientPolicy, parameters.host, parameters.port);
    if (client.isConnected()) {
      System.out.println(clientPolicy.maxThreads + " client threads.");
      drones = new AerospikeDrones(this);
      jobs = new AerospikeJobs(this);
      Metering.start();
      return true;
    } else {
      return false;
    }
  }

  @Override
  public boolean isConnected() {
    return client != null && client.isConnected();
  }

  @Override
  public void close() {
    super.close();
    if (client.isConnected()) {
      System.out.println("Closing the connection.");
      client.close();
    } else {
      System.err.println("Couldn't close the connection.");
    }
  }

  @Override
  public void clear() {
    System.out.println("Clearing the Aerospike database.");
    jobs.clear();
    drones.clear();
  }

  @Override
  public String databaseType() {
    return "Aerospike database";
  }

  //----------------------------------------------------------------------------------

  void clearSet(String setName) {
    ScanPolicy scanPolicy = new ScanPolicy();
    scanPolicy.timeout = 100;
    try {
      // Scan the entire Set using scanAll(). This will scan each node
      // in the cluster and return the record Digest to the call back object
      ClearScanCallback callback = new ClearScanCallback();
      scanAllWorkaround(scanPolicy, namespace, setName, callback);
      System.out.println("Deleted " + callback.count + " records from set " + setName);
    } catch (AerospikeException e) {
      int resultCode = e.getResultCode();
      System.err.format("scanAll to clear set: %s %s %s\n", setName, ResultCode.getResultString(resultCode), e);
    }
  }


  private class ClearScanCallback implements ScanCallback {
    int count;

    public void scanCallback(Key key, Record record) {
      try {
        client.delete(null, key);
      } catch (AerospikeException e) {
        int resultCode = e.getResultCode();
        System.err.format("delete %s %s\n", ResultCode.getResultString(resultCode), e);
      }
      count++;
    }
  }

  // ------------------------------------------------------------------------------------------------

  // This app does something very unusual that hits a bug in the Java 3.1.8 client.
  // We do two scanAll calls very close together, causing their transaction IDs collide.
  final void scanAllWorkaround(ScanPolicy policy, String namespace, String setName, ScanCallback callback, String... bins)
      throws AerospikeException {
    while (true) {
      try {
        client.scanAll(policy, namespace, setName, callback, bins);
        break;
      } catch (AerospikeException e) {
        int resultCode = e.getResultCode();
//        System.err.format("Retrying scanAll of %-6s %s %s\n", setName, ResultCode.getResultString(resultCode), e);
        if (resultCode != ResultCode.PARAMETER_ERROR) {
          throw e;
        }
        // Keep trying until we're past the client library bug with transaction ID.
      }
    }
  }

  // ------------------------------------------------------------------------------------------------

  static long[] instantToLongs(Instant instant) {
    if (instant == null) return null;
    return new long[] { instant.getEpochSecond(), instant.getNano() };
  }

  static Instant longsToInstant(Record record, String binName) {
    Object value = record.getValue(binName);
    if (value == null) {
      return null;
    }
    long[] parts = (long[]) value;
    return Instant.ofEpochSecond(parts[0], parts[1]);
  }

  // ------------------------------------------------------------------------------------------------

  // for testing
  public static void main(String[] args) {
    OurOptions options = new OurOptions();
    options.doCommandLineOptions("ae-test", args);
    AerospikeDatabase database = new AerospikeDatabase();
    AerospikeJobs jobs = (AerospikeJobs) database.getJobs();
    if (database.connect()) {
      try {
        database.clear();
        jobs.newJob(Job.State.Init);
        jobs.newJob(Job.State.Init);
        jobs.newJob(Job.State.Init);
        getAndPrintJob(jobs, 1);
        getAndPrintJob(jobs, 2);
        getAndPrintJob(jobs, 3);
        jobs.foreach(job -> {
          System.out.println("job " + job);
          return true;
        });
      } catch (Exception e) {
        e.printStackTrace();
      } finally {
        database.close();
      }
    } else {
      System.err.println("Couldn't connect.");
    }
    OurExecutor.instance.shutdownNow();
  }

  private static void getAndPrintJob(AerospikeJobs jobs, int id) {
    Job job = jobs.getJobWhereIdIs(id);
    System.out.printf("got job %s\n", job);
  }

}
