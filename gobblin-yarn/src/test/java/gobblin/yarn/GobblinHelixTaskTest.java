/*
 * Copyright (C) 2014-2015 LinkedIn Corp. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the
 * License at  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied.
 */

package gobblin.yarn;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;

import org.apache.avro.Schema;
import org.apache.avro.file.DataFileReader;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.generic.GenericRecord;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.helix.HelixManager;
import org.apache.helix.task.TaskCallbackContext;
import org.apache.helix.task.TaskConfig;
import org.apache.helix.task.TaskResult;
import org.mockito.Mockito;
import org.testng.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import com.google.common.collect.Maps;
import com.google.common.io.Files;

import gobblin.configuration.ConfigurationKeys;
import gobblin.example.simplejson.SimpleJsonConverter;
import gobblin.example.simplejson.SimpleJsonSource;
import gobblin.runtime.AbstractJobLauncher;
import gobblin.runtime.JobState;
import gobblin.runtime.TaskExecutor;
import gobblin.source.workunit.WorkUnit;
import gobblin.util.JobLauncherUtils;
import gobblin.util.SerializationUtils;
import gobblin.writer.AvroDataWriterBuilder;
import gobblin.writer.Destination;
import gobblin.writer.WriterOutputFormat;


/**
 * Unit tests for {@link GobblinHelixTask}.
 *
 * @author ynli
 */
@Test(groups = { "gobblin.yarn" })
public class GobblinHelixTaskTest {

  private static final String JOB_NAME = GobblinHelixTaskTest.class.getSimpleName();
  private static final String JOB_ID = JobLauncherUtils.newJobId(JOB_NAME);
  private static final String TASK_ID = JobLauncherUtils.newTaskId(JOB_ID, 0);

  private static final String REL_WRITER_FILE_PATH = "avro";
  private static final String WRITER_FILE_NAME = "foo.avro";

  private static final String SOURCE_SCHEMA =
      "{\"namespace\":\"example.avro\", \"type\":\"record\", \"name\":\"User\", "
          + "\"fields\":[{\"name\":\"name\", \"type\":\"string\"}, {\"name\":\"favorite_number\",  "
          + "\"type\":\"int\"}, {\"name\":\"favorite_color\", \"type\":\"string\"}]}\n";

  private static final String SOURCE_JSON_DOCS =
      "{\"name\": \"Alyssa\", \"favorite_number\": 256, \"favorite_color\": \"yellow\"}\n"
      + "{\"name\": \"Ben\", \"favorite_number\": 7, \"favorite_color\": \"red\"}\n"
      + "{\"name\": \"Charlie\", \"favorite_number\": 68, \"favorite_color\": \"blue\"}";

  private TaskExecutor taskExecutor;
  private GobblinHelixTaskStateTracker taskStateTracker;
  private GobblinHelixTask gobblinHelixTask;
  private HelixManager helixManager;
  private FileSystem localFs;
  private Path appWorkDir;
  private Path taskOutputDir;

  @BeforeClass
  public void setUp() throws IOException {
    Configuration configuration = new Configuration();
    configuration.setInt(ConfigurationKeys.TASK_EXECUTOR_THREADPOOL_SIZE_KEY, 1);
    this.taskExecutor = new TaskExecutor(configuration);

    this.helixManager = Mockito.mock(HelixManager.class);
    Mockito.when(this.helixManager.getInstanceName()).thenReturn(GobblinHelixTaskTest.class.getSimpleName());
    this.taskStateTracker = new GobblinHelixTaskStateTracker(new Properties(), this.helixManager);

    this.localFs = FileSystem.getLocal(configuration);
    this.appWorkDir = new Path(GobblinHelixTaskTest.class.getSimpleName());
    this.taskOutputDir = new Path(this.appWorkDir, "output");
  }

  @Test
  public void testPrepareTask() throws IOException {
    // Serialize the JobState that will be read later in GobblinHelixTask
    Path jobStateFilePath = new Path(appWorkDir, JOB_ID + "." + AbstractJobLauncher.JOB_STATE_FILE_NAME);
    JobState jobState = new JobState();
    jobState.setJobName(JOB_NAME);
    jobState.setJobId(JOB_ID);
    SerializationUtils.serializeState(this.localFs, jobStateFilePath, jobState);

    // Prepare the WorkUnit
    WorkUnit workUnit = WorkUnit.createEmpty();
    prepareWorkUnit(workUnit);

    // Prepare the source Json file
    File sourceJsonFile = new File(this.appWorkDir.toString(), JOB_NAME + ".json");
    createSourceJsonFile(sourceJsonFile);
    workUnit.setProp(SimpleJsonSource.SOURCE_FILE_KEY, sourceJsonFile.getAbsolutePath());

    // Serialize the WorkUnit into a file
    Path workUnitFilePath = new Path(this.appWorkDir, JOB_NAME + ".wu");
    SerializationUtils.serializeState(this.localFs, workUnitFilePath, workUnit);
    Assert.assertTrue(this.localFs.exists(workUnitFilePath));

    // Prepare the GobblinHelixTask
    Map<String, String> taskConfigMap = Maps.newHashMap();
    taskConfigMap.put(GobblinYarnConfigurationKeys.WORK_UNIT_FILE_PATH, workUnitFilePath.toString());
    taskConfigMap.put(ConfigurationKeys.JOB_ID_KEY, JOB_ID);

    TaskConfig taskConfig = new TaskConfig("", taskConfigMap, true);
    TaskCallbackContext taskCallbackContext = Mockito.mock(TaskCallbackContext.class);
    Mockito.when(taskCallbackContext.getTaskConfig()).thenReturn(taskConfig);
    Mockito.when(taskCallbackContext.getManager()).thenReturn(this.helixManager);

    this.gobblinHelixTask = new GobblinHelixTask(taskCallbackContext, this.taskExecutor,
        this.taskStateTracker, this.localFs, this.appWorkDir);
  }

  @Test(dependsOnMethods = "testPrepareTask")
  public void testRun() throws IOException {
    TaskResult taskResult = this.gobblinHelixTask.run();
    System.out.println(taskResult.getInfo());
    Assert.assertEquals(taskResult.getStatus(), TaskResult.Status.COMPLETED);

    File outputAvroFile =
        new File(this.taskOutputDir.toString(), REL_WRITER_FILE_PATH + File.separator + WRITER_FILE_NAME);
    Assert.assertTrue(outputAvroFile.exists());

    Schema schema = new Schema.Parser().parse(SOURCE_SCHEMA);

    try (DataFileReader<GenericRecord> reader =
        new DataFileReader<>(outputAvroFile, new GenericDatumReader<GenericRecord>(schema))) {
      Iterator<GenericRecord> iterator = reader.iterator();

      GenericRecord record = iterator.next();
      Assert.assertEquals(record.get("name").toString(), "Alyssa");

      record = iterator.next();
      Assert.assertEquals(record.get("name").toString(), "Ben");

      record = iterator.next();
      Assert.assertEquals(record.get("name").toString(), "Charlie");

      Assert.assertFalse(iterator.hasNext());
    }
  }

  @AfterClass
  public void tearDown() throws IOException {
    try {
      if (this.localFs.exists(this.appWorkDir)) {
        this.localFs.delete(this.appWorkDir, true);
      }
    } finally {
      this.taskExecutor.stopAsync().awaitTerminated();
      this.taskStateTracker.stopAsync().awaitTerminated();
    }
  }

  private void prepareWorkUnit(WorkUnit workUnit) {
    workUnit.setProp(ConfigurationKeys.TASK_ID_KEY, TASK_ID);
    workUnit.setProp(ConfigurationKeys.CONVERTER_CLASSES_KEY, SimpleJsonConverter.class.getName());
    workUnit.setProp(ConfigurationKeys.WRITER_OUTPUT_FORMAT_KEY, WriterOutputFormat.AVRO.toString());
    workUnit.setProp(ConfigurationKeys.WRITER_DESTINATION_TYPE_KEY, Destination.DestinationType.HDFS.toString());
    workUnit.setProp(ConfigurationKeys.WRITER_STAGING_DIR, this.appWorkDir.toString() + Path.SEPARATOR + "staging");
    workUnit.setProp(ConfigurationKeys.WRITER_OUTPUT_DIR, this.taskOutputDir.toString());
    workUnit.setProp(ConfigurationKeys.WRITER_FILE_NAME, WRITER_FILE_NAME);
    workUnit.setProp(ConfigurationKeys.WRITER_FILE_PATH, REL_WRITER_FILE_PATH);
    workUnit.setProp(ConfigurationKeys.WRITER_BUILDER_CLASS, AvroDataWriterBuilder.class.getName());
    workUnit.setProp(ConfigurationKeys.SOURCE_SCHEMA, SOURCE_SCHEMA);
    workUnit.setProp(ConfigurationKeys.SOURCE_CLASS_KEY, SimpleJsonSource.class.getName());
  }

  private void createSourceJsonFile(File sourceJsonFile) throws IOException {
    Files.createParentDirs(sourceJsonFile);
    Files.write(SOURCE_JSON_DOCS, sourceJsonFile, ConfigurationKeys.DEFAULT_CHARSET_ENCODING);
  }
}
