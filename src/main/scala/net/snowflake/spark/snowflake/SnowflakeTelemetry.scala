package net.snowflake.spark.snowflake

import java.io.{PrintWriter, StringWriter}
import java.sql.Connection

import net.snowflake.client.jdbc.telemetry.{Telemetry, TelemetryClient}
import org.apache.spark.sql.catalyst.plans.logical._
import org.slf4j.LoggerFactory
import net.snowflake.client.jdbc.internal.fasterxml.jackson.databind.ObjectMapper
import net.snowflake.client.jdbc.internal.fasterxml.jackson.databind.node.ObjectNode
import net.snowflake.client.jdbc.telemetryOOB.{TelemetryEvent, TelemetryService}
import net.snowflake.spark.snowflake.DefaultJDBCWrapper.DataBaseOperations
import net.snowflake.spark.snowflake.TelemetryTypes.TelemetryTypes
import org.apache.spark.{SparkEnv, TaskContext}

object SnowflakeTelemetry {

  private val TELEMETRY_SOURCE = "spark_connector"
  private val TELEMETRY_OOB_NAME_PREFIX = "spark"

  private var logs: List[(ObjectNode, Long)] = Nil // data and timestamp
  private val logger = LoggerFactory.getLogger(getClass)
  private val mapper = new ObjectMapper()

  private var hasClientInfoSent = false
  private[snowflake] val MB = 1024 * 1024

  private[snowflake] var output: ObjectNode = _

  // Enable OOB (out-of-band) telemetry message service
  TelemetryService.enable()

  // This is used to send OOB telemetry without connection.
  // TelemetryService.getInstance returns a thread local instance
  // and TelemetryService internally may use TelemetryService.getInstance
  // to get instance and use it directly. So it doesn't work to cache
  // a TelemetryService object as a singleton in SnowflakeTelemetry.
  private def getOobTelemetryService(): TelemetryService = {
      val service = TelemetryService.getInstance
      service.setDeployment(TelemetryService.TELEMETRY_SERVER_DEPLOYMENT.PROD)
      service
  }

  // The client info telemetry message is only sent one time.
  def sendClientInfoTelemetryIfNotYet(extraValues: Map[String, String],
                                      conn: Connection): Unit = {
    if (!hasClientInfoSent) {
      val metric = Utils.getClientInfoJson()
      for ((key, value) <- extraValues) {
        metric.put(key, value)
      }
      addLog(
        (TelemetryTypes.SPARK_CLIENT_INFO, metric),
        System.currentTimeMillis()
      )
      send(conn.getTelemetry)
      hasClientInfoSent = true
    }
  }

  def addLog(log: ((TelemetryTypes, ObjectNode), Long)): Unit = {
    logger.debug(s"""
        |Telemetry Output
        |Type: ${log._1._1}
        |Data: ${log._1._2.toString}
      """.stripMargin)

    this.synchronized {
      output = mapper.createObjectNode()
      output.put("type", log._1._1.toString)
      output.put("source", TELEMETRY_SOURCE)
      output.set("data", log._1._2)
      logs = (output, log._2) :: logs
    }
  }

  // Send an OOB telemetry message without connection.
  // This should be used for Spark Executor.
  def sendTelemetryOOB(sfurl: String,
                       senderClass: String,
                       operation: String,
                       retryCount: Int,
                       maxRetryCount: Int,
                       success: Boolean,
                       useProxy: Boolean,
                       queryID: Option[String],
                       exception: Option[Exception],
                       config: Option[ObjectNode]): Unit =
  {
    val metric: ObjectNode = mapper.createObjectNode()
    metric.put(TelemetryOOBFields.SPARK_CONNECTOR_VERSION, Utils.VERSION)
    metric.put(TelemetryOOBFields.SFURL, sfurl)
    metric.put(TelemetryOOBFields.SENDER_CLASS, senderClass)
    metric.put(TelemetryOOBFields.OPERATION, operation)
    metric.put(TelemetryOOBFields.RETRY_COUNT, retryCount)
    metric.put(TelemetryOOBFields.MAX_RETRY_COUNT, maxRetryCount)
    metric.put(TelemetryOOBFields.SUCCESS, success)
    metric.put(TelemetryOOBFields.USE_PROXY, useProxy)
    metric.put(TelemetryOOBFields.QUERY_ID, queryID.getOrElse("NA"))
    if (exception.isDefined) {
      metric.put(TelemetryOOBFields.EXCEPTION_CLASS_NAME, exception.get.getClass.toString)
      metric.put(TelemetryOOBFields.EXCEPTION_MESSAGE, exception.get.getMessage)
      val stringWriter = new StringWriter
      exception.get.printStackTrace(new PrintWriter(stringWriter))
      metric.put(TelemetryOOBFields.EXCEPTION_STACKTRACE, stringWriter.toString)
    } else {
      metric.put(TelemetryOOBFields.EXCEPTION_CLASS_NAME, "NA")
      metric.put(TelemetryOOBFields.EXCEPTION_MESSAGE, "NA")
      metric.put(TelemetryOOBFields.EXCEPTION_STACKTRACE, "NA")
    }
    if (config.isDefined) {
      metric.set(TelemetryOOBFields.CONFIG, config.get)
    }

    // The constructor of TelemetryEvent.LogBuilder uses
    // TelemetryService.getInstance to retrieve the thread safe instance
    // and use it. So getOobTelemetryService() needs to be called before
    // creating the LogBuilder object to make sure TelemetryService has
    // been setup.
    val oobTelemetryService = getOobTelemetryService()
    val logBuilder: TelemetryEvent.LogBuilder = new TelemetryEvent.LogBuilder
    val log: TelemetryEvent = logBuilder
      .withName(s"${TELEMETRY_OOB_NAME_PREFIX}_${operation}_$senderClass")
      .withValue(metric.toString)
      // Below are standard OOB tags
      .withTag(TelemetryOOBTags.CONNECTION_STRING, s"https://$sfurl:443")
      .withTag(TelemetryOOBTags.CTX_ACCOUNT,
        sfurl.substring(0, sfurl.indexOf(".")))
      .withTag(TelemetryOOBTags.CTX_HOST,
        sfurl.substring(sfurl.indexOf(".") + 1))
      .withTag(TelemetryOOBTags.CTX_PORT, "443")
      .withTag(TelemetryOOBTags.CTX_PROTOCAL, "https")
      .withTag(TelemetryOOBTags.CTX_USER, "fake_spark_user")
      // Below are spark connector specific tags
      .withTag(TelemetryOOBTags.SPARK_CONNECTOR_VERSION, Utils.VERSION)
      .withTag(TelemetryOOBTags.SENDER_CLASS_NAME, senderClass)
      .withTag(TelemetryOOBTags.OPERATION, operation)
      .build

    logger.info(s"Send OOB Telemetry message: $senderClass $operation")

    // Send OOB telemetry message.
    oobTelemetryService.report(log)
  }

  def send(telemetry: Telemetry): Unit = {
    var curLogs: List[(ObjectNode, Long)] = Nil
    this.synchronized {
      curLogs = logs
      logs = Nil
    }
    curLogs.foreach {
      case (log, timestamp) =>
        logger.debug(s"""
             |Send Telemetry
             |timestamp:$timestamp
             |log:${log.toString}"
           """.stripMargin)
        telemetry.asInstanceOf[TelemetryClient].addLogToBatch(log, timestamp)
    }
    telemetry.sendBatchAsync()
  }

  /**
    * Put the pushdown failure telemetry message to internal cache.
    * The message will be sent later in batch.
    *
    * @param plan The logical plan to include the unsupported operations
    * @param exception The pushdown unsupported exception
    */
  def addPushdownFailMessage(plan: LogicalPlan,
                             exception: SnowflakePushdownUnsupportedException)
  : Unit = {
    logger.info(
      s"""Pushdown fails because of operation: ${exception.unsupportedOperation}
         | message: ${exception.getMessage}
         | isKnown: ${exception.isKnownUnsupportedOperation}
           """.stripMargin)

    // Don't send telemetry message for known unsupported operations.
    if (exception.isKnownUnsupportedOperation) {
      return
    }

    val metric: ObjectNode = mapper.createObjectNode()
    metric.put(TelemetryPushdownFailFields.SPARK_CONNECTOR_VERSION, Utils.VERSION)
    metric.put(TelemetryPushdownFailFields.UNSUPPORTED_OPERATION, exception.unsupportedOperation)
    metric.put(TelemetryPushdownFailFields.EXCEPTION_MESSAGE, exception.getMessage)
    metric.put(TelemetryPushdownFailFields.EXCEPTION_DETAILS, exception.details)

    SnowflakeTelemetry.addLog(
      (TelemetryTypes.SPARK_PUSHDOWN_FAIL, metric),
      System.currentTimeMillis()
    )
  }

  private[snowflake] def getSystemConfig(): ObjectNode = {
    val metric: ObjectNode = mapper.createObjectNode()
    addSystemConfig(metric)
    // Add versions
    Utils.addClientInfoJson(metric)
  }

  private[snowflake] def addSystemConfig(metric: ObjectNode): ObjectNode = {
    // System basic information
    metric.put(TelemetryConfigFields.OS_NAME, System.getProperty("os.name"))
    val rt = Runtime.getRuntime
    metric.put(TelemetryConfigFields.MAX_MEMORY_IN_MB, rt.maxMemory() / MB)
    metric.put(TelemetryConfigFields.TOTAL_MEMORY_IN_MB, rt.totalMemory() / MB)
    metric.put(TelemetryConfigFields.FREE_MEMORY_IN_MB, rt.freeMemory() / MB)
    metric.put(TelemetryConfigFields.CORES, rt.availableProcessors())

    // Spark configuration may affect spark connector
    val sparkConf = SparkEnv.get.conf
    TelemetryConfigFields.AFFECTED_SPARK_OPTIONS.foreach(
      optionName => {
        if (sparkConf.contains(optionName)) {
          metric.put(optionName, sparkConf.get(optionName))
        }
      }
    )
    metric
  }

  private[snowflake] def getTaskInfo(): ObjectNode = {
    val metric: ObjectNode = mapper.createObjectNode()
    addTaskInfo(metric)
  }

  private[snowflake] def addTaskInfo(metric: ObjectNode): ObjectNode = {
    val task = TaskContext.get()
    if (task != null) {
      metric.put(TelemetryConfigFields.TASK_PARTITION_ID, task.partitionId())
      metric.put(TelemetryConfigFields.TASK_ATTEMPT_ID, task.taskAttemptId())
      metric.put(TelemetryConfigFields.TASK_ATTEMPT_NUMBER, task.attemptNumber())
      metric.put(TelemetryConfigFields.TASK_STAGE_ATTEMPT_NUMBER, task.stageAttemptNumber())
      metric.put(TelemetryConfigFields.TASK_STAGE_ID, task.stageId())
    }
    metric.put(TelemetryConfigFields.THREAD_ID, Thread.currentThread().getId)
    metric
  }

}

object TelemetryTypes extends Enumeration {
  type TelemetryTypes = Value
  val SPARK_PLAN: Value = Value("spark_plan")
  val SPARK_STREAMING: Value = Value("spark_streaming")
  val SPARK_STREAMING_START: Value = Value("spark_streaming_start")
  val SPARK_STREAMING_END: Value = Value("spark_streaming_end")
  val SPARK_EGRESS: Value = Value("spark_egress")
  val SPARK_CLIENT_INFO: Value = Value("spark_client_info")
  val SPARK_PUSHDOWN_FAIL: Value = Value("spark_pushdown_fail")
}

object TelemetryClientInfoFields {
  // Spark connector version
  val SPARK_CONNECTOR_VERSION: String = "spark_connector_version"
  // Spark Version
  val SPARK_VERSION: String = "spark_version"
  // Application name
  val APPLICATION_NAME: String = "application_name"
  // Scala version
  val SCALA_VERSION: String = "scala_version"
  // Java version
  val JAVA_VERSION: String = "java_version"
  // Runtime JDBC version
  val JDBC_VERSION: String = "jdbc_version"
  // Certified JDBC version
  val CERTIFIED_JDBC_VERSION: String = "certified_jdbc_version"
  // Snowflake URL with account name
  val SFURL = "sfurl"
}

object TelemetryConfigFields {
  // System basic information
  val OS_NAME = "os.name"
  val MAX_MEMORY_IN_MB = "max.memory.mb"
  val TOTAL_MEMORY_IN_MB = "total.memory.mb"
  val FREE_MEMORY_IN_MB = "free.memory.mb"
  val CORES = "cores"

  // task information which may be are available on executor.
  val TASK_PARTITION_ID = "task.partition.id"
  val TASK_STAGE_ID = "task.stage.id"
  val TASK_ATTEMPT_ID = "task.attempt.id"
  val TASK_ATTEMPT_NUMBER = "task.attempt.number"
  val TASK_STAGE_ATTEMPT_NUMBER = "task.stage.attemp.number"
  val THREAD_ID = "thread.id"

  // Spark options may affect spark connector.
  val AFFECTED_SPARK_OPTIONS = Set(
    "spark.app.name",
    "spark.app.id",
    "spark.submit.deployMode",
    "spark.jars",
    "spark.master",
    "spark.repl.local.jars",
    // Driver related
    "spark.driver.host",
    "spark.driver.extraJavaOptions",
    "spark.driver.extraClassPath",
    "spark.driver.cores",
    // Executor related
    "spark.executor.cores",
    "spark.executor.instances",
    "spark.executor.extraJavaOptions",
    "spark.executor.extraClassPath",
    "spark.executor.id",
    // Memory related
    "spark.driver.memory",
    "spark.driver.memoryOverhead",
    "spark.executor.memory",
    "spark.executor.memoryOverhead",
    "spark.executor.pyspark.memory",
    "spark.python.worker.memory",
    "spark.memory.fraction",
    "spark.memory.storageFraction",
    "spark.memory.offHeap.enabled",
    "spark.memory.offHeap.size",
    // Execution behavior
    "spark.default.parallelism",
    "spark.dynamicAllocation.enabled",
    "spark.dynamicAllocation.initialExecutors",
    "spark.dynamicAllocation.maxExecutors",
    "spark.dynamicAllocation.minExecutors",
    // Misc
    "spark.sql.ansi.enabled",
    "spark.pyspark.driver.python",
    "spark.pyspark.python",
    "spark.sql.session.timeZone",
  )
}

object TelemetryPushdownFailFields {
  // Spark connector version
  val SPARK_CONNECTOR_VERSION: String = "spark_connector_version"
  // The unsupported operation for pushdown
  val UNSUPPORTED_OPERATION: String = "operation"
  // The error message for the exception
  val EXCEPTION_MESSAGE: String = "message"
  // The details information about the exception
  val EXCEPTION_DETAILS: String = "details"
}

object TelemetryOOBFields {
  // Spark connector version
  val SPARK_CONNECTOR_VERSION: String = "spark_connector_version"
  // The URL to include snowflake account name
  val SFURL: String = "sfurl"
  // The class to send the message
  val SENDER_CLASS: String = "sender"
  // The operation such as read, write
  val OPERATION: String = "operation"
  val RETRY_COUNT: String = "retry"
  val MAX_RETRY_COUNT: String = "max_retry"
  val SUCCESS: String = "success"
  val USE_PROXY: String = "use_proxy"
  val QUERY_ID: String = "queryid"
  val CONFIG: String = "config"
  // Below 3 fields are the Exception details.
  val EXCEPTION_CLASS_NAME: String = "exception"
  val EXCEPTION_MESSAGE: String = "message"
  val EXCEPTION_STACKTRACE: String = "stacktrace"
}

object TelemetryOOBTags {
  // Spark connector version
  val SPARK_CONNECTOR_VERSION: String = "spark_connector_version"
  // The class to send the message
  val SENDER_CLASS_NAME: String = "spark_connector_sender"
  // The operation such as read, write
  val OPERATION: String = "spark_connector_operation"
  // A valid Connection String is necessary.
  val CONNECTION_STRING = "connectionString"
  // Below are optional tags for OOB messages
  val CTX_ACCOUNT = "ctx_account"
  val CTX_HOST = "ctx_host"
  val CTX_PORT = "ctx_port"
  val CTX_PROTOCAL = "ctx_protocol"
  val CTX_USER = "ctx_user"
}
