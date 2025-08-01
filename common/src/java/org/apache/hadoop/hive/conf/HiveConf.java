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

package org.apache.hadoop.hive.conf;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hive.common.FileUtils;
import org.apache.hadoop.hive.common.ZooKeeperHiveHelper;
import org.apache.hadoop.hive.common.classification.InterfaceAudience;
import org.apache.hadoop.hive.common.classification.InterfaceAudience.LimitedPrivate;
import org.apache.hadoop.hive.common.classification.InterfaceStability;
import org.apache.hadoop.hive.common.type.TimestampTZUtil;
import org.apache.hadoop.hive.conf.Validator.PatternSet;
import org.apache.hadoop.hive.conf.Validator.RangeValidator;
import org.apache.hadoop.hive.conf.Validator.RatioValidator;
import org.apache.hadoop.hive.conf.Validator.SizeValidator;
import org.apache.hadoop.hive.conf.Validator.StringSet;
import org.apache.hadoop.hive.conf.Validator.TimeValidator;
import org.apache.hadoop.hive.conf.Validator.WritableDirectoryValidator;
import org.apache.hadoop.hive.ql.parse.SemanticException;
import org.apache.hadoop.hive.shims.ShimLoader;
import org.apache.hadoop.hive.shims.Utils;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapreduce.lib.input.CombineFileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hive.common.HiveCompat;
import org.apache.hive.common.util.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.security.auth.login.LoginException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hive Configuration.
 */
public class HiveConf extends Configuration {
  protected String hiveJar;
  protected Properties origProp;
  protected String auxJars;
  private static final Logger LOG = LoggerFactory.getLogger(HiveConf.class);
  private static boolean loadMetastoreConfig = false;
  private static boolean loadHiveServer2Config = false;
  private static URL hiveDefaultURL = null;
  private static URL hiveSiteURL = null;
  private static URL hiveMetastoreSiteUrl = null;
  private static URL metastoreSiteUrl = null;
  private static URL hiveServer2SiteUrl = null;

  private static byte[] confVarByteArray = null;

  private static final Map<String, ConfVars> vars = new HashMap<String, ConfVars>();
  private static final Map<String, ConfVars> metaConfs = new HashMap<String, ConfVars>();
  private final List<String> restrictList = new ArrayList<String>();
  private final Set<String> hiddenSet = new HashSet<String>();
  private final Set<String> lockedSet = new HashSet<>();
  private final List<String> rscList = new ArrayList<>();

  private Pattern modWhiteListPattern = null;
  private static final int LOG_PREFIX_LENGTH = 64;

  public enum ResultFileFormat {
    INVALID_FORMAT {
      @Override
        public String toString() {
          return "invalid result file format";
        }
    },
    TEXTFILE {
      @Override
        public String toString() {
          return "TextFile";
        }
    },
    SEQUENCEFILE {
      @Override
        public String toString() {
          return "SequenceFile";
        }
    },
    RCFILE {
      @Override
        public String toString() {
          return "RCfile";
        }
    },
    LLAP {
      @Override
        public String toString() {
          return "Llap";
        }
    };

    public static ResultFileFormat getInvalid() {
      return INVALID_FORMAT;
    }

    public static EnumSet<ResultFileFormat> getValidSet() {
      return EnumSet.complementOf(EnumSet.of(getInvalid()));
    }

    public static ResultFileFormat from(String value) {
      try {
        return valueOf(value.toUpperCase());
      } catch (Exception e) {
        return getInvalid();
      }
    }
  }

  public ResultFileFormat getResultFileFormat() {
    return ResultFileFormat.from(this.getVar(ConfVars.HIVE_QUERY_RESULT_FILEFORMAT));
  }

  public interface EncoderDecoder<K, V> {
    V encode(K key);
    K decode(V value);
  }

  public static class URLEncoderDecoder implements EncoderDecoder<String, String> {
    @Override
    public String encode(String key) {
      try {
        return URLEncoder.encode(key, StandardCharsets.UTF_8.name());
      } catch (UnsupportedEncodingException e) {
        return key;
      }
    }

    @Override
    public String decode(String value) {
      try {
        return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
      } catch (UnsupportedEncodingException e) {
        return value;
      }
    }
  }
  public static class EncoderDecoderFactory {
    public static final URLEncoderDecoder URL_ENCODER_DECODER = new URLEncoderDecoder();
  }

  static {
    ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
    if (classLoader == null) {
      classLoader = HiveConf.class.getClassLoader();
    }

    hiveDefaultURL = classLoader.getResource("hive-default.xml");

    // Look for hive-site.xml on the CLASSPATH and log its location if found.
    hiveSiteURL = findConfigFile(classLoader, "hive-site.xml", true);
    hiveMetastoreSiteUrl = findConfigFile(classLoader, "hivemetastore-site.xml", true);
    hiveServer2SiteUrl = findConfigFile(classLoader, "hiveserver2-site.xml", true);
    metastoreSiteUrl = findConfigFile(classLoader, "metastore-site.xml", true);

    for (ConfVars confVar : ConfVars.values()) {
      vars.put(confVar.varname, confVar);
    }

    Set<String> llapDaemonConfVarsSetLocal = new LinkedHashSet<>();
    populateLlapDaemonVarsSet(llapDaemonConfVarsSetLocal);
    llapDaemonVarsSet = Collections.unmodifiableSet(llapDaemonConfVarsSetLocal);
  }

  private static URL findConfigFile(ClassLoader classLoader, String name, boolean doLog) {
    URL result = classLoader.getResource(name);
    if (result == null) {
      String confPath = System.getenv("HIVE_CONF_DIR");
      result = checkConfigFile(new File(confPath, name));
      if (result == null) {
        String homePath = System.getenv("HIVE_HOME");
        String nameInConf = "conf" + File.separator + name;
        result = checkConfigFile(new File(homePath, nameInConf));
        if (result == null) {
          try {
            // Handle both file:// and jar:<url>!{entry} in the case of shaded hive libs
            URL sourceUrl = HiveConf.class.getProtectionDomain().getCodeSource().getLocation();
            URI jarUri = sourceUrl.getProtocol().equalsIgnoreCase("jar") ? new URI(sourceUrl.getPath()) : sourceUrl.toURI();
            // From the jar file, the parent is /lib folder
            File parent = new File(jarUri).getParentFile();
            if (parent != null) {
              result = checkConfigFile(new File(parent.getParentFile(), nameInConf));
            }
          } catch (Throwable e) {
            LOG.info("Cannot get jar URI", e);
            System.err.println("Cannot get jar URI: " + e.getMessage());
          }
        }
      }
    }
    if (doLog)  {
      LOG.info("Found configuration file {}", result);
    }
    return result;
  }

  private static URL checkConfigFile(File f) {
    try {
      return (f.exists() && f.isFile()) ? f.toURI().toURL() : null;
    } catch (Throwable e) {
      LOG.info("Error looking for config {}", f, e);
      System.err.println("Error looking for config " + f + ": " + e.getMessage());
      return null;
    }
  }




  @InterfaceAudience.Private
  public static final String PREFIX_LLAP = "llap.";
  @InterfaceAudience.Private
  public static final String PREFIX_HIVE_LLAP = "hive.llap.";

  /**
   * Metastore related options that the db is initialized against. When a conf
   * var in this is list is changed, the metastore instance for the CLI will
   * be recreated so that the change will take effect.
   */
  public static final HiveConf.ConfVars[] metaVars = {
      HiveConf.ConfVars.METASTORE_WAREHOUSE,
      HiveConf.ConfVars.REPL_DIR,
      HiveConf.ConfVars.METASTORE_URIS,
      HiveConf.ConfVars.METASTORE_SELECTION,
      HiveConf.ConfVars.METASTORE_SERVER_PORT,
      HiveConf.ConfVars.METASTORE_THRIFT_CONNECTION_RETRIES,
      HiveConf.ConfVars.METASTORE_THRIFT_FAILURE_RETRIES,
      HiveConf.ConfVars.METASTORE_CLIENT_CONNECT_RETRY_DELAY,
      HiveConf.ConfVars.METASTORE_CLIENT_SOCKET_TIMEOUT,
      HiveConf.ConfVars.METASTORE_CLIENT_SOCKET_LIFETIME,
      HiveConf.ConfVars.METASTORE_PWD,
      HiveConf.ConfVars.METASTORE_CONNECT_URL_HOOK,
      HiveConf.ConfVars.METASTORE_CONNECT_URL_KEY,
      HiveConf.ConfVars.METASTORE_SERVER_MIN_THREADS,
      HiveConf.ConfVars.METASTORE_SERVER_MAX_THREADS,
      HiveConf.ConfVars.METASTORE_INT_ORIGINAL,
      HiveConf.ConfVars.METASTORE_INT_ARCHIVED,
      HiveConf.ConfVars.METASTORE_INT_EXTRACTED,
      HiveConf.ConfVars.METASTORE_KERBEROS_KEYTAB_FILE,
      HiveConf.ConfVars.METASTORE_KERBEROS_PRINCIPAL,
      HiveConf.ConfVars.METASTORE_USE_THRIFT_SASL,
      HiveConf.ConfVars.METASTORE_TOKEN_SIGNATURE,
      HiveConf.ConfVars.METASTORE_CACHE_PINOBJTYPES,
      HiveConf.ConfVars.METASTORE_CONNECTION_POOLING_TYPE,
      HiveConf.ConfVars.METASTORE_VALIDATE_TABLES,
      HiveConf.ConfVars.METASTORE_DATANUCLEUS_INIT_COL_INFO,
      HiveConf.ConfVars.METASTORE_VALIDATE_COLUMNS,
      HiveConf.ConfVars.METASTORE_VALIDATE_CONSTRAINTS,
      HiveConf.ConfVars.METASTORE_STORE_MANAGER_TYPE,
      HiveConf.ConfVars.METASTORE_AUTO_CREATE_ALL,
      HiveConf.ConfVars.METASTORE_TRANSACTION_ISOLATION,
      HiveConf.ConfVars.METASTORE_CACHE_LEVEL2,
      HiveConf.ConfVars.METASTORE_CACHE_LEVEL2_TYPE,
      HiveConf.ConfVars.METASTORE_IDENTIFIER_FACTORY,
      HiveConf.ConfVars.METASTORE_PLUGIN_REGISTRY_BUNDLE_CHECK,
      HiveConf.ConfVars.METASTORE_AUTHORIZATION_STORAGE_AUTH_CHECKS,
      HiveConf.ConfVars.METASTORE_BATCH_RETRIEVE_MAX,
      HiveConf.ConfVars.METASTORE_EVENT_LISTENERS,
      HiveConf.ConfVars.METASTORE_TRANSACTIONAL_EVENT_LISTENERS,
      HiveConf.ConfVars.METASTORE_EVENT_CLEAN_FREQ,
      HiveConf.ConfVars.METASTORE_EVENT_EXPIRY_DURATION,
      HiveConf.ConfVars.METASTORE_EVENT_MESSAGE_FACTORY,
      HiveConf.ConfVars.METASTORE_FILTER_HOOK,
      HiveConf.ConfVars.METASTORE_RAW_STORE_IMPL,
      HiveConf.ConfVars.METASTORE_END_FUNCTION_LISTENERS,
      HiveConf.ConfVars.METASTORE_PART_INHERIT_TBL_PROPS,
      HiveConf.ConfVars.METASTORE_BATCH_RETRIEVE_OBJECTS_MAX,
      HiveConf.ConfVars.METASTORE_INIT_HOOKS,
      HiveConf.ConfVars.METASTORE_PRE_EVENT_LISTENERS,
      HiveConf.ConfVars.HMS_HANDLER_ATTEMPTS,
      HiveConf.ConfVars.HMS_HANDLER_INTERVAL,
      HiveConf.ConfVars.HMS_HANDLER_FORCE_RELOAD_CONF,
      HiveConf.ConfVars.METASTORE_PARTITION_NAME_WHITELIST_PATTERN,
      HiveConf.ConfVars.METASTORE_ORM_RETRIEVE_MAPNULLS_AS_EMPTY_STRINGS,
      HiveConf.ConfVars.METASTORE_DISALLOW_INCOMPATIBLE_COL_TYPE_CHANGES,
      HiveConf.ConfVars.USERS_IN_ADMIN_ROLE,
      HiveConf.ConfVars.HIVE_AUTHORIZATION_MANAGER,
      HiveConf.ConfVars.HIVE_TXN_MANAGER,
      HiveConf.ConfVars.HIVE_TXN_TIMEOUT,
      HiveConf.ConfVars.HIVE_TXN_OPERATIONAL_PROPERTIES,
      HiveConf.ConfVars.HIVE_TXN_HEARTBEAT_THREADPOOL_SIZE,
      HiveConf.ConfVars.HIVE_TXN_MAX_OPEN_BATCH,
      HiveConf.ConfVars.HIVE_TXN_RETRYABLE_SQLEX_REGEX,
      HiveConf.ConfVars.HIVE_METASTORE_STATS_NDV_TUNER,
      HiveConf.ConfVars.HIVE_METASTORE_STATS_NDV_DENSITY_FUNCTION,
      HiveConf.ConfVars.METASTORE_AGGREGATE_STATS_CACHE_ENABLED,
      HiveConf.ConfVars.METASTORE_AGGREGATE_STATS_CACHE_SIZE,
      HiveConf.ConfVars.METASTORE_AGGREGATE_STATS_CACHE_MAX_PARTITIONS,
      HiveConf.ConfVars.METASTORE_AGGREGATE_STATS_CACHE_FPP,
      HiveConf.ConfVars.METASTORE_AGGREGATE_STATS_CACHE_MAX_VARIANCE,
      HiveConf.ConfVars.METASTORE_AGGREGATE_STATS_CACHE_TTL,
      HiveConf.ConfVars.METASTORE_AGGREGATE_STATS_CACHE_MAX_WRITER_WAIT,
      HiveConf.ConfVars.METASTORE_AGGREGATE_STATS_CACHE_MAX_READER_WAIT,
      HiveConf.ConfVars.METASTORE_AGGREGATE_STATS_CACHE_MAX_FULL,
      HiveConf.ConfVars.METASTORE_AGGREGATE_STATS_CACHE_CLEAN_UNTIL,
      HiveConf.ConfVars.METASTORE_FASTPATH,
      HiveConf.ConfVars.METASTORE_HBASE_FILE_METADATA_THREADS,
      HiveConf.ConfVars.METASTORE_WM_DEFAULT_POOL_SIZE
      };

  /**
   * User configurable Metastore vars
   */
  static final HiveConf.ConfVars[] metaConfVars = {
      HiveConf.ConfVars.METASTORE_TRY_DIRECT_SQL,
      HiveConf.ConfVars.METASTORE_TRY_DIRECT_SQL_DDL,
      HiveConf.ConfVars.METASTORE_CLIENT_SOCKET_TIMEOUT,
      HiveConf.ConfVars.METASTORE_PARTITION_NAME_WHITELIST_PATTERN,
      HiveConf.ConfVars.METASTORE_CAPABILITY_CHECK,
      HiveConf.ConfVars.METASTORE_DISALLOW_INCOMPATIBLE_COL_TYPE_CHANGES
  };

  static {
    for (ConfVars confVar : metaConfVars) {
      metaConfs.put(confVar.varname, confVar);
    }
  }

  public static final String HIVE_LLAP_DAEMON_SERVICE_PRINCIPAL_NAME = "hive.llap.daemon.service.principal";
  public static final String HIVE_SERVER2_AUTHENTICATION_LDAP_USERMEMBERSHIPKEY_NAME =
      "hive.server2.authentication.ldap.userMembershipKey";

  /**
   * dbVars are the parameters can be set per database. If these
   * parameters are set as a database property, when switching to that
   * database, the HiveConf variable will be changed. The change of these
   * parameters will effectively change the DFS and MapReduce clusters
   * for different databases.
   */
  public static final HiveConf.ConfVars[] dbVars = {
    HiveConf.ConfVars.HADOOP_BIN,
    HiveConf.ConfVars.METASTORE_WAREHOUSE,
    HiveConf.ConfVars.SCRATCH_DIR
  };

  /**
   * encoded parameter values are ;-) encoded.  Use decoder to get ;-) decoded string
   */
  static final HiveConf.ConfVars[] ENCODED_CONF = {
      ConfVars.HIVE_QUERY_STRING
  };

  /**
   * Variables used by LLAP daemons.
   * TODO: Eventually auto-populate this based on prefixes. The conf variables
   * will need to be renamed for this.
   */
  private static final Set<String> llapDaemonVarsSet;

  private static void populateLlapDaemonVarsSet(Set<String> llapDaemonVarsSetLocal) {
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_IO_ENABLED.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_IO_MEMORY_MODE.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_ALLOCATOR_MIN_ALLOC.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_ALLOCATOR_MAX_ALLOC.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_ALLOCATOR_ARENA_COUNT.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_IO_MEMORY_MAX_SIZE.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_ALLOCATOR_DIRECT.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_USE_LRFU.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_LRFU_LAMBDA.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_LRFU_BP_WRAPPER_SIZE.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_CACHE_ALLOW_SYNTHETIC_FILEID.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_IO_USE_FILEID_PATH.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_IO_DECODING_METRICS_PERCENTILE_INTERVALS.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_ORC_ENABLE_TIME_COUNTERS.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_IO_THREADPOOL_SIZE.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_KERBEROS_PRINCIPAL.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_KERBEROS_KEYTAB_FILE.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_ZKSM_ZK_CONNECTION_STRING.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_ZKSM_ZK_CONNECTION_SSL_ENABLED.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_ZKSM_ZK_CONNECTION_SSL_KEYSTORE_LOCATION.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_ZKSM_ZK_CONNECTION_SSL_KEYSTORE_PASSWORD.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_ZKSM_ZK_CONNECTION_SSL_TRUSTSTORE_LOCATION.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_ZKSM_ZK_CONNECTION_SSL_TRUSTSTORE_PASSWORD.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_SECURITY_ACL.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_MANAGEMENT_ACL.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_SECURITY_ACL_DENY.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_MANAGEMENT_ACL_DENY.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_DELEGATION_TOKEN_LIFETIME.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_MANAGEMENT_RPC_PORT.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_WEB_AUTO_AUTH.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_DAEMON_RPC_NUM_HANDLERS.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_DAEMON_WORK_DIRS.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_DAEMON_YARN_SHUFFLE_PORT.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_DAEMON_YARN_CONTAINER_MB.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_DAEMON_SHUFFLE_DIR_WATCHER_ENABLED.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_DAEMON_AM_LIVENESS_HEARTBEAT_INTERVAL_MS.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_DAEMON_AM_LIVENESS_CONNECTION_TIMEOUT_MS.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_DAEMON_AM_LIVENESS_CONNECTION_SLEEP_BETWEEN_RETRIES_MS.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_DAEMON_NUM_EXECUTORS.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_DAEMON_RPC_PORT.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_DAEMON_MEMORY_PER_INSTANCE_MB.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_DAEMON_XMX_HEADROOM.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_DAEMON_VCPUS_PER_INSTANCE.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_DAEMON_NUM_FILE_CLEANER_THREADS.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_FILE_CLEANUP_DELAY_SECONDS.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_DAEMON_SERVICE_HOSTS.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_DAEMON_SERVICE_REFRESH_INTERVAL.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_ALLOW_PERMANENT_FNS.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_DAEMON_DOWNLOAD_PERMANENT_FNS.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_DAEMON_TASK_SCHEDULER_WAIT_QUEUE_SIZE.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_DAEMON_WAIT_QUEUE_COMPARATOR_CLASS_NAME.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_DAEMON_TASK_SCHEDULER_ENABLE_PREEMPTION.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_DAEMON_TASK_PREEMPTION_METRICS_INTERVALS.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_DAEMON_WEB_PORT.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_DAEMON_WEB_SSL.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_DAEMON_CONTAINER_ID.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_VALIDATE_ACLS.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_DAEMON_LOGGER.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_DAEMON_AM_USE_FQDN.varname);
    llapDaemonVarsSetLocal.add(ConfVars.LLAP_IO_PATH_CACHE_SIZE.varname);
  }

  /**
   * Get a set containing configuration parameter names used by LLAP Server instances
   * @return an unmodifiable set containing llap ConfVars
   */
  public static final Set<String> getLlapDaemonConfVars() {
    return llapDaemonVarsSet;
  }


  /**
   * ConfVars.
   *
   * These are the default configuration properties for Hive. Each HiveConf
   * object is initialized as follows:
   *
   * 1) Hadoop configuration properties are applied.
   * 2) ConfVar properties with non-null values are overlayed.
   * 3) hive-site.xml properties are overlayed.
   * 4) System Properties and Manual Overrides are overlayed.
   *
   * WARNING: think twice before adding any Hadoop configuration properties
   * with non-null values to this list as they will override any values defined
   * in the underlying Hadoop configuration.
   */
  public static enum ConfVars {
    MSC_CACHE_ENABLED("hive.metastore.client.cache.v2.enabled", true,
            "This property enables a Caffeine Cache for Metastore client"),
    MSC_CACHE_MAX_SIZE("hive.metastore.client.cache.v2.maxSize", "1Gb", new SizeValidator(),
            "Set the maximum size (number of bytes) of the metastore client cache (DEFAULT: 1GB). " +
                    "Only in effect when the cache is enabled"),
    MSC_CACHE_RECORD_STATS("hive.metastore.client.cache.v2.recordStats", false,
            "This property enables recording metastore client cache stats in DEBUG logs"),
    // QL execution stuff
    DFS_XATTR_ONLY_SUPPORTED_ON_RESERVED_NAMESPACE("dfs.xattr.supported.only.on.reserved.namespace", false,
      "DFS supports xattr only on Reserved Name space (/.reserved/raw)"),
    SCRIPT_WRAPPER("hive.exec.script.wrapper", null, ""),
    PLAN("hive.exec.plan", "", ""),
    STAGING_DIR("hive.exec.stagingdir", ".hive-staging",
        "Directory name that will be created inside table locations in order to support HDFS encryption. " +
        "This is replaces ${hive.exec.scratchdir} for query results with the exception of read-only tables. " +
        "In all cases ${hive.exec.scratchdir} is still used for other temporary files, such as job plans."),
    SCRATCH_DIR("hive.exec.scratchdir", "/tmp/hive",
        "HDFS root scratch dir for Hive jobs which gets created with write all (733) permission. " +
        "For each connecting user, an HDFS scratch dir: ${hive.exec.scratchdir}/<username> is created, " +
        "with ${hive.scratch.dir.permission}."),
    REPL_DIR("hive.repl.rootdir","/user/${system:user.name}/repl/",
        "HDFS root dir for all replication dumps."),
    REPL_CM_ENABLED("hive.repl.cm.enabled", false,
        "Turn on ChangeManager, so delete files will go to cmrootdir."),
    REPL_CM_DIR("hive.repl.cmrootdir","/user/${system:user.name}/cmroot/",
        "Root dir for ChangeManager, used for deleted files."),
    REPL_CM_RETAIN("hive.repl.cm.retain","10d",
        new TimeValidator(TimeUnit.DAYS),
        "Time to retain removed files in cmrootdir."),
    REPL_CM_ENCRYPTED_DIR("hive.repl.cm.encryptionzone.rootdir", ".cmroot",
            "Root dir for ChangeManager if encryption zones are enabled, used for deleted files."),
    REPL_CM_FALLBACK_NONENCRYPTED_DIR("hive.repl.cm.nonencryptionzone.rootdir",
            "",
            "Root dir for ChangeManager for non encrypted paths if hive.repl.cmrootdir is encrypted."),
    REPL_CM_INTERVAL("hive.repl.cm.interval","3600s",
        new TimeValidator(TimeUnit.SECONDS),
        "Interval for cmroot cleanup thread."),
    REPL_HA_DATAPATH_REPLACE_REMOTE_NAMESERVICE("hive.repl.ha.datapath.replace.remote.nameservice", false,
            "When HDFS is HA enabled and both source and target clusters are configured with same nameservice name," +
                    "enable this flag and provide a new unique logical name for representing the remote cluster " +
                    "nameservice using config " + "'hive.repl.ha.datapath.replace.remote.nameservice.name'."),
    REPL_HA_DATAPATH_REPLACE_REMOTE_NAMESERVICE_NAME("hive.repl.ha.datapath.replace.remote.nameservice.name", null,
            "When HDFS is HA enabled and both source and target clusters are configured with same nameservice name, " +
                    "use this config to provide a unique logical name for nameservice on the remote cluster (should " +
                    "be different from nameservice name on the local cluster)"),
    REPL_FUNCTIONS_ROOT_DIR("hive.repl.replica.functions.root.dir","/user/${system:user.name}/repl/functions/",
        "Root directory on the replica warehouse where the repl sub-system will store jars from the primary warehouse"),
    REPL_APPROX_MAX_LOAD_TASKS("hive.repl.approx.max.load.tasks", 10000,
        "Provide an approximation of the maximum number of tasks that should be executed before \n"
            + "dynamically generating the next set of tasks. The number is approximate as Hive \n"
            + "will stop at a slightly higher number, the reason being some events might lead to a \n"
            + "task increment that would cross the specified limit."),
    REPL_BATCH_INCREMENTAL_EVENTS("hive.repl.batch.incremental.events", true,
            "Dump events in batches during incremental phase of repl dump"),
    REPL_PARTITIONS_DUMP_PARALLELISM("hive.repl.partitions.dump.parallelism",100,
        "Number of threads that will be used to dump partition data information during repl dump."),
    REPL_TABLE_DUMP_PARALLELISM("hive.repl.table.dump.parallelism", 15,
            "Number of threads that will be used to dump table data information during repl dump."),
    REPL_RUN_DATA_COPY_TASKS_ON_TARGET("hive.repl.run.data.copy.tasks.on.target", true,
            "Indicates whether replication should run data copy tasks during repl load operation."),
    REPL_DUMP_METADATA_ONLY("hive.repl.dump.metadata.only", false,
        "Indicates whether replication dump only metadata information or data + metadata. \n"
          + "This config makes hive.repl.include.external.tables config ineffective."),
    REPL_RETAIN_PREV_DUMP_DIR("hive.repl.retain.prev.dump.dir", false,
            "If this is set to false, then all previously used dump-directories will be deleted after repl-dump. " +
             "If true, a number of latest dump-directories specified by hive.repl.retain.prev.dump.dir.count will be retained"),
    REPL_RETAIN_PREV_DUMP_DIR_COUNT("hive.repl.retain.prev.dump.dir.count", 3,
            "Indicates maximum number of latest previously used dump-directories which would be retained when " +
             "hive.repl.retain.prev.dump.dir is set to true"),
    REPL_RETAIN_CUSTOM_LOCATIONS_FOR_DB_ON_TARGET("hive.repl.retain.custom.db.locations.on.target", true,
            "Indicates if source database has custom warehouse locations, whether that should be retained on target as well"),
    REPL_INCLUDE_MATERIALIZED_VIEWS("hive.repl.include.materialized.views", false,
            "Indicates whether replication of materialized views is enabled."),
    REPL_DUMP_SKIP_IMMUTABLE_DATA_COPY("hive.repl.dump.skip.immutable.data.copy", false,
        "Indicates whether replication dump can skip copyTask and refer to  \n"
            + " original path instead. This would retain all table and partition meta"),
    REPL_DUMP_METADATA_ONLY_FOR_EXTERNAL_TABLE("hive.repl.dump.metadata.only.for.external.table",
            true,
            "Indicates whether external table replication dump only metadata information or data + metadata"),
    REPL_BOOTSTRAP_ACID_TABLES("hive.repl.bootstrap.acid.tables", false,
        "Indicates if repl dump should bootstrap the information about ACID tables along with \n"
            + "incremental dump for replication. It is recommended to keep this config parameter \n"
            + "as false always and should be set to true only via WITH clause of REPL DUMP \n"
            + "command. It should be set to true only once for incremental repl dump on \n"
            + "each of the existing replication policies after enabling acid tables replication."),
    REPL_BOOTSTRAP_DUMP_OPEN_TXN_TIMEOUT("hive.repl.bootstrap.dump.open.txn.timeout", "1h",
        new TimeValidator(TimeUnit.HOURS),
        "Indicates the timeout for all transactions which are opened before triggering bootstrap REPL DUMP. "
            + "If these open transactions are not closed within the timeout value, then REPL DUMP will "
            + "forcefully abort those transactions and continue with bootstrap dump."),
    REPL_BOOTSTRAP_DUMP_ABORT_WRITE_TXN_AFTER_TIMEOUT("hive.repl.bootstrap.dump.abort.write.txn.after.timeout",
      true,
      "Indicates whether to abort write transactions belonging to the db under replication while doing a" +
        " bootstrap dump after the timeout configured by hive.repl.bootstrap.dump.open.txn.timeout. If set to false," +
        " bootstrap dump will fail."),
    //https://hadoop.apache.org/docs/stable/hadoop-project-dist/hadoop-hdfs/TransparentEncryption.html#Running_as_the_superuser
    REPL_ADD_RAW_RESERVED_NAMESPACE("hive.repl.add.raw.reserved.namespace", false,
        "For TDE with same encryption keys on source and target, allow Distcp super user to access \n"
            + "the raw bytes from filesystem without decrypting on source and then encrypting on target."),
    REPL_INCLUDE_EXTERNAL_TABLES("hive.repl.include.external.tables", true,
        "Indicates if repl dump should include information about external tables. It should be \n"
          + "used in conjunction with 'hive.repl.dump.metadata.only' set to false. if 'hive.repl.dump.metadata.only' \n"
          + " is set to true then this config parameter has no effect as external table meta data is flushed \n"
          + " always by default. If this config parameter is enabled on an on-going replication policy which is in\n"
          + " incremental phase, then need to set 'hive.repl.bootstrap.external.tables' to true for the first \n"
          + " repl dump to bootstrap all external tables."),
    REPL_BOOTSTRAP_EXTERNAL_TABLES("hive.repl.bootstrap.external.tables", false,
        "Indicates if repl dump should bootstrap the information about external tables along with incremental \n"
          + "dump for replication. It is recommended to keep this config parameter as false always and should be \n"
          + "set to true only via WITH clause of REPL DUMP command. It should be used in conjunction with \n"
          + "'hive.repl.include.external.tables' when sets to true. If 'hive.repl.include.external.tables' is \n"
          + "set to false, then this config parameter has no effect. It should be set to true only once for \n"
          + "incremental repl dump on each existing replication policy after enabling external tables replication."),
    REPL_EXTERNAL_TABLE_BASE_DIR("hive.repl.replica.external.table.base.dir", null,
        "This is the fully qualified base directory on the target/replica warehouse under which data for "
            + "external tables is stored. This is relative base path and hence prefixed to the source "
            + "external table path on target cluster."),
    REPL_EXTERNAL_WAREHOUSE_SINGLE_COPY_TASK("hive.repl.external.warehouse.single.copy.task",
        false, "Should create single copy task for all the external tables "
        + "within the database default location for external tables, Would require more memory "
        + "for preparing the initial listing, Should be used if the memory "
        + "requirements can be fulfilled. If any specific configuration needs to be passed for these copy task it can"
        + " be specified using the prefix hive.dbpath."),
    REPL_EXTERNAL_WAREHOUSE_SINGLE_COPY_TASK_PATHS("hive.repl.external.warehouse.single.copy.task.paths",
        "", "Comma separated list of paths for which single copy task shall be created for all the external tables "
        + "within the locations Would require more memory for preparing the initial listing, Should be used if the memory "
        + "requirements can be fulfilled. If the directory contains data not part of the database, that data would "
        + "also get copied, so only locations which contains tables only belonging to the same database should be "
        + "provided. This has no effect in case of table level replication or if hive.repl.bootstrap.external.tables "
        + "isn't enabled. If any specific configuration needs to be passed for these copy task it can be specified "
        + "using the prefix hive.dbpath."),
    REPL_INCLUDE_AUTHORIZATION_METADATA("hive.repl.include.authorization.metadata", false,
            "This configuration will enable security and authorization related metadata along "
                    + "with the hive data and metadata replication. "),
    REPL_AUTHORIZATION_PROVIDER_SERVICE("hive.repl.authorization.provider.service", "ranger",
            "This configuration will define which service will provide the security and authorization "
                    + "related metadata that needs to be replicated along "
                    + "with the hive data and metadata replication. Set the configuration "
                    + "hive.repl.include.authorization.metadata to false to disable "
                    + "security policies being replicated "),
    REPL_RANGER_HANDLE_DENY_POLICY_TARGET("hive.repl.handle.ranger.deny.policy",
            false,
            "Indicates whether ranger deny policy for target database should be handled automatically by hive or not."),
    HIVE_REPL_FAILOVER_START("hive.repl.failover.start",false,
            "A replication policy level config to indicate if user wants to initiate fail-over " +
                    "to replicate the database in reverse direction."),
    HIVE_REPL_CLEAR_DANGLING_TXNS_ON_TARGET("hive.repl.clear.dangling.txns.on.target", true,
                                                    "Indicates whether to clear dangling transactions on the target during replication."),
    REPL_RANGER_ADD_DENY_POLICY_TARGET("hive.repl.ranger.target.deny.policy",
      true,
      "This configuration will add a deny policy on the target database for all users except hive"
        + " to avoid any update to the target database. Effective only if hive.repl.handle.ranger.deny.policy is set" +
              "to true."),
    REPL_RANGER_CLIENT_READ_TIMEOUT("hive.repl.ranger.client.read.timeout", "300s",
            new TimeValidator(TimeUnit.SECONDS), "Ranger client read timeout for Ranger REST API calls."),
    REPL_INCLUDE_ATLAS_METADATA("hive.repl.include.atlas.metadata", false,
            "Indicates if Atlas metadata should be replicated along with Hive data and metadata or not."),
    REPL_ATLAS_ENDPOINT("hive.repl.atlas.endpoint", null,
            "Atlas endpoint of the current cluster hive database is getting replicated from/to."),
    REPL_ATLAS_REPLICATED_TO_DB("hive.repl.atlas.replicatedto", null,
            "Target hive database name Atlas metadata of source hive database is being replicated to."),
    REPL_ATLAS_CLIENT_READ_TIMEOUT("hive.repl.atlas.client.read.timeout", "7200s",
            new TimeValidator(TimeUnit.SECONDS), "Atlas client read timeout for Atlas REST API calls."),
    REPL_EXTERNAL_CLIENT_CONNECT_TIMEOUT("hive.repl.external.client.connect.timeout", "10s",
            new TimeValidator(TimeUnit.SECONDS), "Client connect timeout for REST API calls to external service."),
    REPL_SOURCE_CLUSTER_NAME("hive.repl.source.cluster.name", null,
            "Name of the source cluster for the replication."),
    REPL_TARGET_CLUSTER_NAME("hive.repl.target.cluster.name", null,
            "Name of the target cluster for the replication."),
    REPL_RETRY_INTIAL_DELAY("hive.repl.retry.initial.delay", "60s",
      new TimeValidator(TimeUnit.SECONDS),
      "Initial Delay before retry starts."),
    REPL_RETRY_BACKOFF_COEFFICIENT("hive.repl.retry.backoff.coefficient", 1.2f,
      "The backoff coefficient for exponential retry delay between retries. " +
        "Previous Delay * Backoff Coefficient will determine the next retry interval"),
    REPL_RETRY_JITTER("hive.repl.retry.jitter", "30s", new TimeValidator(TimeUnit.SECONDS),
      "A random jitter to be applied to avoid all retries happening at the same time."),
    REPL_RETRY_MAX_DELAY_BETWEEN_RETRIES("hive.repl.retry.max.delay.between.retries", "60m",
      new TimeValidator(TimeUnit.MINUTES),
      "Maximum allowed retry delay in minutes after including exponential backoff. " +
        "If this limit is reached, retry will continue with this retry duration."),
    REPL_RETRY_TOTAL_DURATION("hive.repl.retry.total.duration", "24h",
      new TimeValidator(TimeUnit.HOURS),
      "Total allowed retry duration in hours inclusive of all retries. Once this is exhausted, " +
        "the policy instance will be marked as failed and will need manual intervention to restart."),
    REPL_COPY_FILE_LIST_ITERATOR_RETRY("hive.repl.copy.file.list.iterator.retry", true,
            "Determines whether writes happen with retry upon encountering filesystem errors for data-copy \n"
            + "iterator files. It should be disabled when we do not want retry on a per-line basis while writing \n"
            + "to the files and in cases when flushing capabilities are not available on the stream. If disabled, then retry \n"
            + "is only attempted during file creation, not for errors encountered while writing entries."),
    REPL_LOAD_PARTITIONS_BATCH_SIZE("hive.repl.load.partitions.batch.size", 10000,
      "Provide the maximum number of partitions of a table that will be batched together during  \n"
        + "repl load. All the partitions in a batch will make a single metastore call to update the metadata. \n"
        + "The data for these partitions will be copied before copying the metadata batch. "),
    REPL_LOAD_PARTITIONS_WITH_DATA_COPY_BATCH_SIZE("hive.repl.load.partitions.with.data.copy.batch.size",1000,
      "Provide the maximum number of partitions of a table that will be batched together during  \n"
        + "repl load. All the partitions in a batch will make a single metastore call to update the metadata. \n"
        + "The data for these partitions will be copied before copying the metadata batch. "),
    REPL_PARALLEL_COPY_TASKS("hive.repl.parallel.copy.tasks",100,
      "Provide the maximum number of parallel copy operation(distcp or regular copy) launched for a table  \n"
        + "or partition. This will create at max 100 threads which will run copy in parallel for the data files at \n"
        + " table or partition level. If hive.exec.parallel \n"
        + "is set to true then max worker threads created for copy can be hive.exec.parallel.thread.number(determines \n"
        + "number of copy tasks in parallel) * hive.repl.parallel.copy.tasks "),
    REPL_SNAPSHOT_DIFF_FOR_EXTERNAL_TABLE_COPY("hive.repl.externaltable.snapshotdiff.copy",
        false,"Use snapshot diff for copying data from source to "
        + "destination cluster for external table in distcp. If true it uses snapshot based distcp for all the paths "
        + "configured as part of hive.repl.external.warehouse.single.copy.task along with the external warehouse "
        + "default location."),
    REPL_SNAPSHOT_OVERWRITE_TARGET_FOR_EXTERNAL_TABLE_COPY("hive.repl.externaltable.snapshot.overwrite.target",
        true,"If this is enabled, in case the target is modified, when using snapshot for external table"
        + "data copy, the target data is overwritten and the modifications are removed and the copy is again "
        + "attempted using the snapshot based approach. If disabled, the replication will fail in case the target is "
        + "modified."),
    REPL_STATS_TOP_EVENTS_COUNTS("hive.repl.stats.events.count", 5,
        "Number of topmost expensive events that needs to be maintained per event type for the replication statistics." +
                " Maximum permissible limit is 10."),
    LOCAL_SCRATCH_DIR("hive.exec.local.scratchdir",
        "${system:java.io.tmpdir}" + File.separator + "${system:user.name}",
        "Local scratch space for Hive jobs"),
    DOWNLOADED_RESOURCES_DIR("hive.downloaded.resources.dir",
        "${system:java.io.tmpdir}" + File.separator + "${hive.session.id}_resources",
        "Temporary local directory for added resources in the remote file system."),
    SCRATCH_DIR_PERMISSION("hive.scratch.dir.permission", "700",
        "The permission for the user specific scratch directories that get created."),
    SUBMIT_VIA_CHILD("hive.exec.submitviachild", false, ""),
    SUBMIT_LOCAL_TASK_VIA_CHILD("hive.exec.submit.local.task.via.child", true,
        "Determines whether local tasks (typically mapjoin hashtable generation phase) runs in \n" +
        "separate JVM (true recommended) or not. \n" +
        "Avoids the overhead of spawning new JVM, but can lead to out-of-memory issues."),
    SCRIPT_ERROR_LIMIT("hive.exec.script.maxerrsize", 100000,
        "Maximum number of bytes a script is allowed to emit to standard error (per map-reduce task). \n" +
        "This prevents runaway scripts from filling logs partitions to capacity"),
    ALLOW_PARTIAL_CONSUMP("hive.exec.script.allow.partial.consumption", false,
        "When enabled, this option allows a user script to exit successfully without consuming \n" +
        "all the data from the standard input."),
    STREAM_REPORTER_PREFIX("stream.stderr.reporter.prefix", "reporter:",
        "Streaming jobs that log to standard error with this prefix can log counter or status information."),
    STREAM_REPORTER_ENABLED("stream.stderr.reporter.enabled", true,
        "Enable consumption of status and counter messages for streaming jobs."),
    ORC_COMPRESS("hive.exec.orc.default.compress", "ZLIB", "Define the default compression codec for ORC file. " +
            "ZLIB is the default value in hive until ZSTD which is default from orc 2.x is tested"),
    COMPRESS_RESULT("hive.exec.compress.output", false,
        "This controls whether the final outputs of a query (to a local/HDFS file or a Hive table) is compressed. \n" +
        "The compression codec and other options are determined from Hadoop config variables mapred.output.compress*"),
    COMPRESS_INTERMEDIATE("hive.exec.compress.intermediate", false,
        "This controls whether intermediate files produced by Hive between multiple map-reduce jobs are compressed. \n" +
        "The compression codec and other options are determined from Hadoop config variables mapred.output.compress*"),
    COMPRESS_INTERMEDIATE_CODEC("hive.intermediate.compression.codec", "", ""),
    COMPRESS_INTERMEDIATE_TYPE("hive.intermediate.compression.type", "", ""),
    BYTES_PER_REDUCER("hive.exec.reducers.bytes.per.reducer", (long) (256 * 1000 * 1000),
        "size per reducer.The default is 256Mb, i.e if the input size is 1G, it will use 4 reducers."),
    MAX_REDUCERS("hive.exec.reducers.max", 1009,
        "max number of reducers will be used. If the one specified in the configuration parameter mapred.reduce.tasks is\n" +
        "negative, Hive will use this one as the max number of reducers when automatically determine number of reducers."),
    PRE_EXEC_HOOKS("hive.exec.pre.hooks", "",
        "Comma-separated list of pre-execution hooks to be invoked for each statement. \n" +
        "A pre-execution hook is specified as the name of a Java class which implements the \n" +
        "org.apache.hadoop.hive.ql.hooks.ExecuteWithHookContext interface."),
    POST_EXEC_HOOKS("hive.exec.post.hooks", "",
        "Comma-separated list of post-execution hooks to be invoked for each statement. \n" +
        "A post-execution hook is specified as the name of a Java class which implements the \n" +
        "org.apache.hadoop.hive.ql.hooks.ExecuteWithHookContext interface."),
    ON_FAILURE_HOOKS("hive.exec.failure.hooks", "",
        "Comma-separated list of on-failure hooks to be invoked for each statement. \n" +
        "An on-failure hook is specified as the name of Java class which implements the \n" +
        "org.apache.hadoop.hive.ql.hooks.ExecuteWithHookContext interface."),
    QUERY_REDACTOR_HOOKS("hive.exec.query.redactor.hooks", "",
        "Comma-separated list of hooks to be invoked for each query which can \n" +
        "transform the query before it's placed in the job.xml file. Must be a Java class which \n" +
        "extends from the org.apache.hadoop.hive.ql.hooks.Redactor abstract class."),
    CLIENT_STATS_PUBLISHERS("hive.client.stats.publishers", "",
        "Comma-separated list of statistics publishers to be invoked on counters on each job. \n" +
        "A client stats publisher is specified as the name of a Java class which implements the \n" +
        "org.apache.hadoop.hive.ql.stats.ClientStatsPublisher interface."),
    BASIC_STATS_TASKS_MAX_THREADS_FACTOR("hive.basic.stats.max.threads.factor", 2, "Determines the maximum number of "
        + "threads that can be used for collection of file level statistics. If the value configured is x, then the "
        + "maximum number of threads that can be used is x multiplied by the number of available processors.  A value"
        + " of less than 1, makes stats collection sequential."),
    EXEC_PARALLEL("hive.exec.parallel", false, "Whether to execute jobs in parallel"),
    EXEC_PARALLEL_THREAD_NUMBER("hive.exec.parallel.thread.number", 8,
        "How many jobs at most can be executed in parallel"),
    @Deprecated
    HIVE_SPECULATIVE_EXEC_REDUCERS("hive.mapred.reduce.tasks.speculative.execution", false,
        "(Deprecated) Whether speculative execution for reducers should be turned on. "),
    HIVE_COUNTERS_PULL_INTERVAL("hive.exec.counters.pull.interval", 1000L,
        "The interval with which to poll the JobTracker for the counters the running job. \n" +
        "The smaller it is the more load there will be on the jobtracker, the higher it is the less granular the caught will be."),
    DYNAMIC_PARTITIONING("hive.exec.dynamic.partition", true,
        "Whether or not to allow dynamic partitions in DML/DDL."),
    DYNAMIC_PARTITIONING_MODE("hive.exec.dynamic.partition.mode", "nonstrict",
        new StringSet("strict", "nonstrict"),
        "In strict mode, the user must specify at least one static partition\n" +
        "in case the user accidentally overwrites all partitions.\n" +
        "In nonstrict mode all partitions are allowed to be dynamic."),
    DYNAMIC_PARTITION_MAX_PARTS("hive.exec.max.dynamic.partitions", 1000,
        "Maximum number of dynamic partitions allowed to be created in total."),
    DYNAMIC_PARTITION_MAX_PARTS_PER_NODE("hive.exec.max.dynamic.partitions.pernode", 100,
        "Maximum number of dynamic partitions allowed to be created in each mapper/reducer node."),
    DYNAMIC_PARTITION_CONVERT("hive.exec.dynamic.partition.type.conversion", true,
        "Whether to check and cast a dynamic partition column before creating the partition " +
        "directory. For example, if partition p is type int and we insert string '001', then if " +
        "this value is true, directory p=1 will be created; if false, p=001"),
    MAX_CREATED_FILES("hive.exec.max.created.files", 100000L,
        "Maximum number of HDFS files created by all mappers/reducers in a MapReduce job."),
    DEFAULT_PARTITION_NAME("hive.exec.default.partition.name", "__HIVE_DEFAULT_PARTITION__",
        "The default partition name in case the dynamic partition column value is null/empty string or any other values that cannot be escaped. \n" +
        "This value must not contain any special character used in HDFS URI (e.g., ':', '%', '/' etc). \n" +
        "The user has to be aware that the dynamic partition value should not contain this value to avoid confusions."),
    DEFAULT_ZOOKEEPER_PARTITION_NAME("hive.lockmgr.zookeeper.default.partition.name", "__HIVE_DEFAULT_ZOOKEEPER_PARTITION__", ""),

    // Whether to show a link to the most failed task + debugging tips
    SHOW_JOB_FAIL_DEBUG_INFO("hive.exec.show.job.failure.debug.info", true,
        "If a job fails, whether to provide a link in the CLI to the task with the\n" +
        "most failures, along with debugging hints if applicable."),
    JOB_DEBUG_CAPTURE_STACKTRACES("hive.exec.job.debug.capture.stacktraces", true,
        "Whether or not stack traces parsed from the task logs of a sampled failed task \n" +
        "for each failed job should be stored in the SessionState"),
    JOB_DEBUG_TIMEOUT("hive.exec.job.debug.timeout", 30000, ""),
    TASKLOG_DEBUG_TIMEOUT("hive.exec.tasklog.debug.timeout", 20000, ""),
    OUTPUT_FILE_EXTENSION("hive.output.file.extension", null,
        "String used as a file extension for output files. \n" +
        "If not set, defaults to the codec extension for text files (e.g. \".gz\"), or no extension otherwise."),

    HIVE_LOAD_DATA_USE_NATIVE_API("hive.load.data.use.native.api", true,
        "Whether to use a native APIs for load queries to non-native table(like iceberg), if false uses a Tez job for" +
            " load queries"),

    HIVE_IN_TEST("hive.in.test", false, "internal usage only, true in test mode", true),
    HIVE_IN_TEST_SSL("hive.in.ssl.test", false, "internal usage only, true in SSL test mode", true),
    // TODO: this needs to be removed; see TestReplicationScenarios* comments.
    HIVE_IN_TEST_REPL("hive.in.repl.test", false, "internal usage only, true in replication test mode", true),
    HIVE_IN_TEST_IDE("hive.in.ide.test", false, "internal usage only, true if test running in ide",
        true),
    HIVE_TESTING_SHORT_LOGS("hive.testing.short.logs", false,
        "internal usage only, used only in test mode. If set true, when requesting the " +
        "operation logs the short version (generated by LogDivertAppenderForTest) will be " +
        "returned"),
    HIVE_TESTING_REMOVE_LOGS("hive.testing.remove.logs", true,
        "internal usage only, used only in test mode. If set false, the operation logs, and the " +
        "operation log directory will not be removed, so they can be found after the test runs."),

    HIVE_TEST_LOAD_HOSTNAMES("hive.test.load.hostnames", "",
        "Specify host names for load testing. (e.g., \"host1,host2,host3\"). Leave it empty if no " +
        "load generation is needed (eg. for production)."),
    HIVE_TEST_LOAD_INTERVAL("hive.test.load.interval", "10ms", new TimeValidator(TimeUnit.MILLISECONDS),
        "The interval length used for load and idle periods in milliseconds."),
    HIVE_TEST_LOAD_UTILIZATION("hive.test.load.utilization", 0.2f,
        "Specify processor load utilization between 0.0 (not loaded on all threads) and 1.0 " +
        "(fully loaded on all threads). Comparing this with a random value the load generator creates " +
        "hive.test.load.interval length active loops or idle periods"),

    HIVE_IN_TEZ_TEST("hive.in.tez.test", false, "internal use only, true when in testing tez",
        true),
    HIVE_MAPJOIN_TESTING_NO_HASH_TABLE_LOAD("hive.mapjoin.testing.no.hash.table.load", false, "internal use only, true when in testing map join",
        true),
    HIVE_ADDITIONAL_PARTIAL_MASKS_PATTERN("hive.qtest.additional.partial.mask.pattern", "",
        "internal use only, used in only qtests. Provide additional partial masks pattern" +
         "for qtests as a ',' separated list"),
    HIVE_ADDITIONAL_PARTIAL_MASKS_REPLACEMENT_TEXT("hive.qtest.additional.partial.mask.replacement.text", "",
        "internal use only, used in only qtests. Provide additional partial masks replacement" +
        "text for qtests as a ',' separated list"),

    HIVE_IN_REPL_TEST_FILES_SORTED("hive.in.repl.test.files.sorted", false,
      "internal usage only, set to true if the file listing is required in sorted order during bootstrap load", true),

    LOCAL_MODE_AUTO("hive.exec.mode.local.auto", false,
        "Let Hive determine whether to run in local mode automatically"),
    LOCAL_MODE_MAX_BYTES("hive.exec.mode.local.auto.inputbytes.max", 134217728L,
        "When hive.exec.mode.local.auto is true, input bytes should less than this for local mode."),
    LOCAL_MODE_MAX_INPUT_FILES("hive.exec.mode.local.auto.input.files.max", 4,
        "When hive.exec.mode.local.auto is true, the number of tasks should less than this for local mode."),

    DROP_IGNORES_NON_EXISTENT("hive.exec.drop.ignorenonexistent", true,
        "Do not report an error if DROP TABLE/VIEW/Index/Function specifies a nonexistent table/view/function"),

    HIVE_IGNORE_MAPJOIN_HINT("hive.ignore.mapjoin.hint", true, "Ignore the mapjoin hint"),

    HIVE_CONF_LOCKED_LIST("hive.conf.locked.list", "", "Comma separated " +
            "list of configuration options which are locked and can not be changed at runtime. Warning is logged and the " +
            "change is ignored when user try to set these configs during runtime"),

    HIVE_FILE_MAX_FOOTER("hive.file.max.footer", 100,
        "maximum number of lines for footer user can define for a table file"),

    HIVE_RESULTSET_USE_UNIQUE_COLUMN_NAMES("hive.resultset.use.unique.column.names", true,
        "Make column names unique in the result set by qualifying column names with table alias if needed.\n" +
        "Table alias will be added to column names for queries of type \"select *\" or \n" +
        "if query explicitly uses table alias \"select r1.x..\"."),

    HIVE_PROTO_EVENTS_QUEUE_CAPACITY("hive.hook.proto.queue.capacity", 64,
      "Queue capacity for the proto events logging threads."),
    HIVE_PROTO_EVENTS_BASE_PATH("hive.hook.proto.base-directory", "",
            "Base directory into which the proto event messages are written by HiveProtoLoggingHook."),
    HIVE_PROTO_EVENTS_ROLLOVER_CHECK_INTERVAL("hive.hook.proto.rollover-interval", "600s",
            new TimeValidator(TimeUnit.SECONDS, 0L, true, 3600 * 24L, true),
            "Frequency at which the file rollover check is triggered."),
    HIVE_PROTO_EVENTS_CLEAN_FREQ("hive.hook.proto.events.clean.freq", "1d",
            new TimeValidator(TimeUnit.DAYS),
            "Frequency at which timer task runs to purge expired proto event files."),
    HIVE_PROTO_EVENTS_TTL("hive.hook.proto.events.ttl", "7d",
            new TimeValidator(TimeUnit.DAYS),
            "Time-To-Live (TTL) of proto event files before cleanup."),
    HIVE_PROTO_FILE_PER_EVENT("hive.hook.proto.file.per.event", false,
      "Whether each proto event has to be written to separate file. " +
        "(Use this for FS that does not hflush immediately like S3A)"),

    // Hadoop Configuration Properties
    // Properties with null values are ignored and exist only for the purpose of giving us
    // a symbolic name to reference in the Hive source code. Properties with non-null
    // values will override any values set in the underlying Hadoop configuration.
    HADOOP_BIN("hadoop.bin.path", findHadoopBinary(), "", true),
    YARN_BIN("yarn.bin.path", findYarnBinary(), "", true),
    MAPRED_BIN("mapred.bin.path", findMapRedBinary(), "", true),
    HIVE_FS_HAR_IMPL("fs.har.impl", "org.apache.hadoop.hive.shims.HiveHarFileSystem",
        "The implementation for accessing Hadoop Archives. Note that this won't be applicable to Hadoop versions less than 0.20"),
    MAPRED_MAX_SPLIT_SIZE(FileInputFormat.SPLIT_MAXSIZE, 256000000L, "", true),
    MAPRED_MIN_SPLIT_SIZE(FileInputFormat.SPLIT_MINSIZE, 1L, "", true),
    MAPRED_MIN_SPLIT_SIZE_PER_NODE(CombineFileInputFormat.SPLIT_MINSIZE_PERNODE, 1L, "", true),
    MAPRED_MIN_SPLIT_SIZE_PER_RACK(CombineFileInputFormat.SPLIT_MINSIZE_PERRACK, 1L, "", true),
    // The number of reduce tasks per job. Hadoop sets this value to 1 by default
    // By setting this property to -1, Hive will automatically determine the correct
    // number of reducers.
    HADOOP_NUM_REDUCERS("mapreduce.job.reduces", -1, "", true),

    // Metastore stuff. Be sure to update HiveConf.metaVars when you add something here!
    METASTORE_DB_TYPE("hive.metastore.db.type", "DERBY", new StringSet("DERBY", "ORACLE", "MYSQL", "MSSQL", "POSTGRES"),
        "Type of database used by the metastore. Information schema & JDBCStorageHandler depend on it."),
    /**
     * @deprecated Use MetastoreConf.WAREHOUSE
     */
    @Deprecated
    METASTORE_WAREHOUSE("hive.metastore.warehouse.dir", "/user/hive/warehouse",
        "location of default database for the warehouse"),

    HIVE_METASTORE_WAREHOUSE_EXTERNAL("hive.metastore.warehouse.external.dir", null,
        "Default location for external tables created in the warehouse. " +
        "If not set or null, then the normal warehouse location will be used as the default location."),

    /**
     * @deprecated Use MetastoreConf.THRIFT_URIS
     */
    @Deprecated
    METASTORE_URIS("hive.metastore.uris", "",
        "Thrift URI for the remote metastore. Used by metastore client to connect to remote metastore."),

    /**
     * @deprecated Use MetastoreConf.THRIFT_URI_SELECTION
     */
    @Deprecated
    METASTORE_SELECTION("hive.metastore.uri.selection", "RANDOM",
        new StringSet("SEQUENTIAL", "RANDOM"),
        "Determines the selection mechanism used by metastore client to connect to remote " +
            "metastore.  SEQUENTIAL implies that the first valid metastore from the URIs specified " +
            "as part of hive.metastore.uris will be picked.  RANDOM implies that the metastore " +
            "will be picked randomly"),
    /**
     * @deprecated Use MetastoreConf.CAPABILITY_CHECK
     */
    @Deprecated
    METASTORE_CAPABILITY_CHECK("hive.metastore.client.capability.check", true,
        "Whether to check client capabilities for potentially breaking API usage."),
    METASTORE_CLIENT_CAPABILITIES("hive.metastore.client.capabilities", "", "Capabilities possessed by HiveServer"),
    METASTORE_CLIENT_CACHE_ENABLED("hive.metastore.client.cache.enabled", false,
      "Whether to enable metastore client cache"),
    METASTORE_CLIENT_CACHE_EXPIRY_TIME("hive.metastore.client.cache.expiry.time", "120s",
      new TimeValidator(TimeUnit.SECONDS), "Expiry time for metastore client cache"),
    METASTORE_CLIENT_CACHE_INITIAL_CAPACITY("hive.metastore.client.cache.initial.capacity", 50,
      "Initial capacity for metastore client cache"),
    METASTORE_CLIENT_CACHE_MAX_CAPACITY("hive.metastore.client.cache.max.capacity", 50,
      "Max capacity for metastore client cache"),
    METASTORE_CLIENT_CACHE_STATS_ENABLED("hive.metastore.client.cache.stats.enabled", false,
      "Whether to enable metastore client cache stats"),
    METASTORE_FASTPATH("hive.metastore.fastpath", false,
        "Used to avoid all of the proxies and object copies in the metastore.  Note, if this is " +
            "set, you MUST use a local metastore (hive.metastore.uris must be empty) otherwise " +
            "undefined and most likely undesired behavior will result"),
    /**
     * @deprecated Use MetastoreConf.FS_HANDLER_THREADS_COUNT
     */
    @Deprecated
    METASTORE_FS_HANDLER_THREADS_COUNT("hive.metastore.fshandler.threads", 15,
        "Number of threads to be allocated for metastore handler for fs operations."),
    /**
     * @deprecated Use MetastoreConf.FILE_METADATA_THREADS
     */
    @Deprecated
    METASTORE_HBASE_FILE_METADATA_THREADS("hive.metastore.hbase.file.metadata.threads", 1,
        "Number of threads to use to read file metadata in background to cache it."),

    /**
     * @deprecated Use MetastoreConf.URI_RESOLVER
     */
    @Deprecated
    METASTORE_URI_RESOLVER("hive.metastore.uri.resolver", "",
        "If set, fully qualified class name of resolver for hive metastore uri's"),

    /**
     * @deprecated Use MetastoreConf.THRIFT_CONNECTION_RETRIES
     */
    @Deprecated
    METASTORE_THRIFT_CONNECTION_RETRIES("hive.metastore.connect.retries", 3,
        "Number of retries while opening a connection to metastore"),
    /**
     * @deprecated Use MetastoreConf.THRIFT_FAILURE_RETRIES
     */
    @Deprecated
    METASTORE_THRIFT_FAILURE_RETRIES("hive.metastore.failure.retries", 1,
        "Number of retries upon failure of Thrift metastore calls"),
    /**
     * @deprecated Use MetastoreConf.SERVER_PORT
     */
    @Deprecated
    METASTORE_SERVER_PORT("hive.metastore.port", 9083, "Hive metastore listener port"),
    /**
     * @deprecated Use MetastoreConf.CLIENT_CONNECT_RETRY_DELAY
     */
    @Deprecated
    METASTORE_CLIENT_CONNECT_RETRY_DELAY("hive.metastore.client.connect.retry.delay", "1s",
        new TimeValidator(TimeUnit.SECONDS),
        "Number of seconds for the client to wait between consecutive connection attempts"),
    /**
     * @deprecated Use MetastoreConf.CLIENT_SOCKET_TIMEOUT
     */
    @Deprecated
    METASTORE_CLIENT_SOCKET_TIMEOUT("hive.metastore.client.socket.timeout", "600s",
        new TimeValidator(TimeUnit.SECONDS),
        "MetaStore Client socket timeout in seconds"),
    /**
     * @deprecated Use MetastoreConf.CLIENT_SOCKET_LIFETIME
     */
    @Deprecated
    METASTORE_CLIENT_SOCKET_LIFETIME("hive.metastore.client.socket.lifetime", "0s",
        new TimeValidator(TimeUnit.SECONDS),
        "MetaStore Client socket lifetime in seconds. After this time is exceeded, client\n" +
        "reconnects on the next MetaStore operation. A value of 0s means the connection\n" +
        "has an infinite lifetime."),
    /**
     * @deprecated Use MetastoreConf.PWD
     */
    @Deprecated
    METASTORE_PWD("javax.jdo.option.ConnectionPassword", "mine",
        "password to use against metastore database"),
    /**
     * @deprecated Use MetastoreConf.CONNECT_URL_HOOK
     */
    @Deprecated
    METASTORE_CONNECT_URL_HOOK("hive.metastore.ds.connection.url.hook", "",
        "Name of the hook to use for retrieving the JDO connection URL. If empty, the value in javax.jdo.option.ConnectionURL is used"),
    /**
     * @deprecated Use MetastoreConf.MULTITHREADED
     */
    @Deprecated
    METASTORE_MULTI_THREADED("javax.jdo.option.Multithreaded", true,
        "Set this to true if multiple threads access metastore through JDO concurrently."),
    /**
     * @deprecated Use MetastoreConf.CONNECT_URL_KEY
     */
    @Deprecated
    METASTORE_CONNECT_URL_KEY("javax.jdo.option.ConnectionURL",
        "jdbc:derby:;databaseName=metastore_db;create=true",
        "JDBC connect string for a JDBC metastore.\n" +
        "To use SSL to encrypt/authenticate the connection, provide database-specific SSL flag in the connection URL.\n" +
        "For example, jdbc:postgresql://myhost/db?ssl=true for postgres database."),
    /**
     * @deprecated Use MetastoreConf.DBACCESS_SSL_PROPS
     */
    @Deprecated
    METASTORE_DBACCESS_SSL_PROPS("hive.metastore.dbaccess.ssl.properties", "",
           "Comma-separated SSL properties for metastore to access database when JDO connection URL\n" +
           "enables SSL access. e.g. javax.net.ssl.trustStore=/tmp/truststore,javax.net.ssl.trustStorePassword=pwd."),
    /**
     * @deprecated Use MetastoreConf.HMS_HANDLER_ATTEMPTS
     */
    @Deprecated
    HMS_HANDLER_ATTEMPTS("hive.hmshandler.retry.attempts", 10,
        "The number of times to retry a HMSHandler call if there were a connection error."),
    /**
     * @deprecated Use MetastoreConf.HMS_HANDLER_INTERVAL
     */
    @Deprecated
    HMS_HANDLER_INTERVAL("hive.hmshandler.retry.interval", "2000ms",
        new TimeValidator(TimeUnit.MILLISECONDS), "The time between HMSHandler retry attempts on failure."),
    /**
     * @deprecated Use MetastoreConf.HMS_HANDLER_FORCE_RELOAD_CONF
     */
    @Deprecated
    HMS_HANDLER_FORCE_RELOAD_CONF("hive.hmshandler.force.reload.conf", false,
        "Whether to force reloading of the HMSHandler configuration (including\n" +
        "the connection URL, before the next metastore query that accesses the\n" +
        "datastore. Once reloaded, this value is reset to false. Used for\n" +
        "testing only."),
    /**
     * @deprecated Use MetastoreConf.SERVER_MAX_MESSAGE_SIZE
     */
    @Deprecated
    METASTORE_SERVER_MAX_MESSAGE_SIZE("hive.metastore.server.max.message.size", 100*1024*1024L,
        "Maximum message size in bytes a HMS will accept."),
    /**
     * @deprecated Use MetastoreConf.SERVER_MIN_THREADS
     */
    @Deprecated
    METASTORE_SERVER_MIN_THREADS("hive.metastore.server.min.threads", 200,
        "Minimum number of worker threads in the Thrift server's pool."),
    /**
     * @deprecated Use MetastoreConf.SERVER_MAX_THREADS
     */
    @Deprecated
    METASTORE_SERVER_MAX_THREADS("hive.metastore.server.max.threads", 1000,
        "Maximum number of worker threads in the Thrift server's pool."),
    /**
     * @deprecated Use MetastoreConf.WM_DEFAULT_POOL_SIZE
     */
    @Deprecated
    METASTORE_WM_DEFAULT_POOL_SIZE("hive.metastore.wm.default.pool.size", 4,
        "The size of a default pool to create when creating an empty resource plan;\n" +
        "If not positive, no default pool will be created."),

    METASTORE_INT_ORIGINAL("hive.metastore.archive.intermediate.original",
        "_INTERMEDIATE_ORIGINAL",
        "Intermediate dir suffixes used for archiving. Not important what they\n" +
        "are, as long as collisions are avoided"),
    METASTORE_INT_ARCHIVED("hive.metastore.archive.intermediate.archived",
        "_INTERMEDIATE_ARCHIVED", ""),
    METASTORE_INT_EXTRACTED("hive.metastore.archive.intermediate.extracted",
        "_INTERMEDIATE_EXTRACTED", ""),
    /**
     * @deprecated Use MetastoreConf.KERBEROS_KEYTAB_FILE
     */
    @Deprecated
    METASTORE_KERBEROS_KEYTAB_FILE("hive.metastore.kerberos.keytab.file", "",
        "The path to the Kerberos Keytab file containing the metastore Thrift server's service principal."),
    /**
     * @deprecated Use MetastoreConf.KERBEROS_PRINCIPAL
     */
    @Deprecated
    METASTORE_KERBEROS_PRINCIPAL("hive.metastore.kerberos.principal",
        "hive-metastore/_HOST@EXAMPLE.COM",
        "The service principal for the metastore Thrift server. \n" +
        "The special string _HOST will be replaced automatically with the correct host name."),
    /**
     * @deprecated Use MetastoreConf.CLIENT_KERBEROS_PRINCIPAL
     */
    @Deprecated
    METASTORE_CLIENT_KERBEROS_PRINCIPAL("hive.metastore.client.kerberos.principal",
        "", // E.g. "hive-metastore/_HOST@EXAMPLE.COM".
        "The Kerberos principal associated with the HA cluster of hcat_servers."),
    /**
     * @deprecated Use MetastoreConf.USE_THRIFT_SASL
     */
    @Deprecated
    METASTORE_USE_THRIFT_SASL("hive.metastore.sasl.enabled", false,
        "If true, the metastore Thrift interface will be secured with SASL. Clients must authenticate with Kerberos."),
    /**
     * @deprecated Use MetastoreConf.USE_THRIFT_FRAMED_TRANSPORT
     */
    @Deprecated
    METASTORE_USE_THRIFT_FRAMED_TRANSPORT("hive.metastore.thrift.framed.transport.enabled", false,
        "If true, the metastore Thrift interface will use TFramedTransport. When false (default) a standard TTransport is used."),
    /**
     * @deprecated Use MetastoreConf.USE_THRIFT_COMPACT_PROTOCOL
     */
    @Deprecated
    METASTORE_USE_THRIFT_COMPACT_PROTOCOL("hive.metastore.thrift.compact.protocol.enabled", false,
        "If true, the metastore Thrift interface will use TCompactProtocol. When false (default) TBinaryProtocol will be used.\n" +
        "Setting it to true will break compatibility with older clients running TBinaryProtocol."),
    /**
     * @deprecated Use MetastoreConf.TOKEN_SIGNATURE
     */
    @Deprecated
    METASTORE_TOKEN_SIGNATURE("hive.metastore.token.signature", "",
        "The delegation token service name to match when selecting a token from the current user's tokens."),
    /**
     * @deprecated Use MetastoreConf.DELEGATION_TOKEN_STORE_CLS
     */
    @Deprecated
    METASTORE_CLUSTER_DELEGATION_TOKEN_STORE_CLS("hive.cluster.delegation.token.store.class",
        "org.apache.hadoop.hive.thrift.MemoryTokenStore",
        "The delegation token store implementation. Set to org.apache.hadoop.hive.thrift.ZooKeeperTokenStore for load-balanced cluster."),
    METASTORE_CLUSTER_DELEGATION_TOKEN_STORE_ZK_CONNECTSTR(
        "hive.cluster.delegation.token.store.zookeeper.connectString", "",
        "The ZooKeeper token store connect string. You can re-use the configuration value\n" +
        "set in hive.zookeeper.quorum, by leaving this parameter unset."),
    METASTORE_CLUSTER_DELEGATION_TOKEN_STORE_ZK_ZNODE(
        "hive.cluster.delegation.token.store.zookeeper.znode", "/hivedelegation",
        "The root path for token store data. Note that this is used by both HiveServer2 and\n" +
        "MetaStore to store delegation Token. One directory gets created for each of them.\n" +
        "The final directory names would have the servername appended to it (HIVESERVER2,\n" +
        "METASTORE)."),
    METASTORE_CLUSTER_DELEGATION_TOKEN_STORE_ZK_ACL(
        "hive.cluster.delegation.token.store.zookeeper.acl", "",
        "ACL for token store entries. Comma separated list of ACL entries. For example:\n" +
        "sasl:hive/host1@MY.DOMAIN:cdrwa,sasl:hive/host2@MY.DOMAIN:cdrwa\n" +
        "Defaults to all permissions for the hiveserver2/metastore process user."),
    /**
     * @deprecated Use MetastoreConf.CACHE_PINOBJTYPES
     */
    @Deprecated
    METASTORE_CACHE_PINOBJTYPES("hive.metastore.cache.pinobjtypes", "Table,StorageDescriptor,SerDeInfo,Partition,Database,Type,FieldSchema,Order",
        "List of comma separated metastore object types that should be pinned in the cache"),
    /**
     * @deprecated Use MetastoreConf.CONNECTION_POOLING_TYPE
     */
    @Deprecated
    METASTORE_CONNECTION_POOLING_TYPE("datanucleus.connectionPoolingType", "HikariCP", new StringSet("DBCP",
      "HikariCP", "NONE"),
        "Specify connection pool library for datanucleus"),
    /**
     * @deprecated Use MetastoreConf.CONNECTION_POOLING_MAX_CONNECTIONS
     */
    @Deprecated
    METASTORE_CONNECTION_POOLING_MAX_CONNECTIONS("datanucleus.connectionPool.maxPoolSize", 10,
      "Specify the maximum number of connections in the connection pool. Note: The configured size will be used by\n" +
        "2 connection pools (TxnHandler and ObjectStore). When configuring the max connection pool size, it is\n" +
        "recommended to take into account the number of metastore instances and the number of HiveServer2 instances\n" +
        "configured with embedded metastore. To get optimal performance, set config to meet the following condition\n"+
        "(2 * pool_size * metastore_instances + 2 * pool_size * HS2_instances_with_embedded_metastore) = \n" +
        "(2 * physical_core_count + hard_disk_count)."),
    // Workaround for DN bug on Postgres:
    // http://www.datanucleus.org/servlet/forum/viewthread_thread,7985_offset
    /**
     * @deprecated Use MetastoreConf.DATANUCLEUS_INIT_COL_INFO
     */
    @Deprecated
    METASTORE_DATANUCLEUS_INIT_COL_INFO("datanucleus.rdbms.initializeColumnInfo", "NONE",
        "initializeColumnInfo setting for DataNucleus; set to NONE at least on Postgres."),
    /**
     * @deprecated Use MetastoreConf.VALIDATE_TABLES
     */
    @Deprecated
    METASTORE_VALIDATE_TABLES("datanucleus.schema.validateTables", false,
        "validates existing schema against code. turn this on if you want to verify existing schema"),
    /**
     * @deprecated Use MetastoreConf.VALIDATE_COLUMNS
     */
    @Deprecated
    METASTORE_VALIDATE_COLUMNS("datanucleus.schema.validateColumns", false,
        "validates existing schema against code. turn this on if you want to verify existing schema"),
    /**
     * @deprecated Use MetastoreConf.VALIDATE_CONSTRAINTS
     */
    @Deprecated
    METASTORE_VALIDATE_CONSTRAINTS("datanucleus.schema.validateConstraints", false,
        "validates existing schema against code. turn this on if you want to verify existing schema"),
    /**
     * @deprecated Use MetastoreConf.STORE_MANAGER_TYPE
     */
    @Deprecated
    METASTORE_STORE_MANAGER_TYPE("datanucleus.storeManagerType", "rdbms", "metadata store type"),
    /**
     * @deprecated Use MetastoreConf.AUTO_CREATE_ALL
     */
    @Deprecated
    METASTORE_AUTO_CREATE_ALL("datanucleus.schema.autoCreateAll", false,
        "Auto creates necessary schema on a startup if one doesn't exist. Set this to false, after creating it once."
        + "To enable auto create also set hive.metastore.schema.verification=false. Auto creation is not "
        + "recommended for production use cases, run schematool command instead." ),
    /**
     * @deprecated Use MetastoreConf.SCHEMA_VERIFICATION
     */
    @Deprecated
    METASTORE_SCHEMA_VERIFICATION("hive.metastore.schema.verification", true,
        "Enforce metastore schema version consistency.\n" +
        "True: Verify that version information stored in is compatible with one from Hive jars.  Also disable automatic\n" +
        "      schema migration attempt. Users are required to manually migrate schema after Hive upgrade which ensures\n" +
        "      proper metastore schema migration. (Default)\n" +
        "False: Warn if the version information stored in metastore doesn't match with one from in Hive jars."),
    /**
     * @deprecated Use MetastoreConf.SCHEMA_VERIFICATION_RECORD_VERSION
     */
    @Deprecated
    METASTORE_SCHEMA_VERIFICATION_RECORD_VERSION("hive.metastore.schema.verification.record.version", false,
      "When true the current MS version is recorded in the VERSION table. If this is disabled and verification is\n" +
      " enabled the MS will be unusable."),
    /**
     * @deprecated Use MetastoreConf.SCHEMA_INFO_CLASS
     */
    @Deprecated
    METASTORE_SCHEMA_INFO_CLASS("hive.metastore.schema.info.class",
        "org.apache.hadoop.hive.metastore.MetaStoreSchemaInfo",
        "Fully qualified class name for the metastore schema information class \n"
        + "which is used by schematool to fetch the schema information.\n"
        + " This class should implement the IMetaStoreSchemaInfo interface"),
    /**
     * @deprecated Use MetastoreConf.DATANUCLEUS_TRANSACTION_ISOLATION
     */
    @Deprecated
    METASTORE_TRANSACTION_ISOLATION("datanucleus.transactionIsolation", "read-committed",
        "Default transaction isolation level for identity generation."),
    /**
     * @deprecated Use MetastoreConf.DATANUCLEUS_CACHE_LEVEL2
     */
    @Deprecated
    METASTORE_CACHE_LEVEL2("datanucleus.cache.level2", false,
        "Use a level 2 cache. Turn this off if metadata is changed independently of Hive metastore server"),
    METASTORE_CACHE_LEVEL2_TYPE("datanucleus.cache.level2.type", "none", ""),
    /**
     * @deprecated Use MetastoreConf.IDENTIFIER_FACTORY
     */
    @Deprecated
    METASTORE_IDENTIFIER_FACTORY("datanucleus.identifierFactory", "datanucleus1",
        "Name of the identifier factory to use when generating table/column names etc. \n" +
        "'datanucleus1' is used for backward compatibility with DataNucleus v1"),
    /**
     * @deprecated Use MetastoreConf.DATANUCLEUS_USE_LEGACY_VALUE_STRATEGY
     */
    @Deprecated
    METASTORE_USE_LEGACY_VALUE_STRATEGY("datanucleus.rdbms.useLegacyNativeValueStrategy", true, ""),
    /**
     * @deprecated Use MetastoreConf.DATANUCLEUS_PLUGIN_REGISTRY_BUNDLE_CHECK
     */
    @Deprecated
    METASTORE_PLUGIN_REGISTRY_BUNDLE_CHECK("datanucleus.plugin.pluginRegistryBundleCheck", "LOG",
        "Defines what happens when plugin bundles are found and are duplicated [EXCEPTION|LOG|NONE]"),
    /**
     * @deprecated Use MetastoreConf.BATCH_RETRIEVE_MAX
     */
    @Deprecated
    METASTORE_BATCH_RETRIEVE_MAX("hive.metastore.batch.retrieve.max", 300,
         new RangeValidator(1, null),
        "Maximum number of objects (tables/partitions) can be retrieved from metastore in one batch. \n" +
        "The higher the number, the less the number of round trips is needed to the Hive metastore server, \n" +
        "but it may also cause higher memory requirement at the client side. Batch value should be greater than 0."),
    /**
     * @deprecated Use MetastoreConf.BATCH_RETRIEVE_OBJECTS_MAX
     */
    @Deprecated
    METASTORE_BATCH_RETRIEVE_OBJECTS_MAX(
        "hive.metastore.batch.retrieve.table.partition.max", 1000,
        "Maximum number of objects that metastore internally retrieves in one batch."),

    /**
     * @deprecated Use MetastoreConf.INIT_HOOKS
     */
    @Deprecated
    METASTORE_INIT_HOOKS("hive.metastore.init.hooks", "",
        "A comma separated list of hooks to be invoked at the beginning of HMSHandler initialization. \n" +
        "An init hook is specified as the name of Java class which extends org.apache.hadoop.hive.metastore.MetaStoreInitListener."),
    /**
     * @deprecated Use MetastoreConf.PRE_EVENT_LISTENERS
     */
    @Deprecated
    METASTORE_PRE_EVENT_LISTENERS("hive.metastore.pre.event.listeners", "",
        "List of comma separated listeners for metastore events."),
    /**
     * @deprecated Use MetastoreConf.EVENT_LISTENERS
     */
    @Deprecated
    METASTORE_EVENT_LISTENERS("hive.metastore.event.listeners", "",
        "A comma separated list of Java classes that implement the org.apache.hadoop.hive.metastore.MetaStoreEventListener" +
            " interface. The metastore event and corresponding listener method will be invoked in separate JDO transactions. " +
            "Alternatively, configure hive.metastore.transactional.event.listeners to ensure both are invoked in same JDO transaction."),

    HIVE_WRITE_NOTIFICATION_MAX_BATCH_SIZE("hive.write.notification.max.batch.size", 1000,
        "Max number of write notification logs sent in a batch "),

    /**
     * @deprecated Use MetastoreConf.TRANSACTIONAL_EVENT_LISTENERS
     */
    @Deprecated
    METASTORE_TRANSACTIONAL_EVENT_LISTENERS("hive.metastore.transactional.event.listeners", "",
        "A comma separated list of Java classes that implement the org.apache.hadoop.hive.metastore.MetaStoreEventListener" +
            " interface. Both the metastore event and corresponding listener method will be invoked in the same JDO transaction."),
    /**
     * @deprecated Use MetastoreConf.NOTIFICATION_SEQUENCE_LOCK_MAX_RETRIES
     */
    @Deprecated
    NOTIFICATION_SEQUENCE_LOCK_MAX_RETRIES("hive.notification.sequence.lock.max.retries", 10,
        "Number of retries required to acquire a lock when getting the next notification sequential ID for entries "
            + "in the NOTIFICATION_LOG table."),
    /**
     * @deprecated Use MetastoreConf.NOTIFICATION_SEQUENCE_LOCK_RETRY_SLEEP_INTERVAL
     */
    @Deprecated
    NOTIFICATION_SEQUENCE_LOCK_RETRY_SLEEP_INTERVAL("hive.notification.sequence.lock.retry.sleep.interval", 10L,
        new TimeValidator(TimeUnit.SECONDS),
        "Sleep interval between retries to acquire a notification lock as described part of property "
            + NOTIFICATION_SEQUENCE_LOCK_MAX_RETRIES.name()),
    /**
     * @deprecated Use MetastoreConf.EVENT_DB_LISTENER_TTL
     */
    @Deprecated
    METASTORE_EVENT_DB_LISTENER_TTL("hive.metastore.event.db.listener.timetolive", "86400s",
        new TimeValidator(TimeUnit.SECONDS),
        "time after which events will be removed from the database listener queue when repl.cm.enabled \n" +
         "is set to false. When repl.cm.enabled is set to true, repl.event.db.listener.timetolive is used instead"),

    /**
     * @deprecated Use MetastoreConf.EVENT_DB_NOTIFICATION_API_AUTH
     */
    @Deprecated
    METASTORE_EVENT_DB_NOTIFICATION_API_AUTH("hive.metastore.event.db.notification.api.auth", true,
        "Should metastore do authorization against database notification related APIs such as get_next_notification.\n" +
        "If set to true, then only the superusers in proxy settings have the permission"),

    /**
     * @deprecated Use MetastoreConf.AUTHORIZATION_STORAGE_AUTH_CHECKS
     */
    @Deprecated
    METASTORE_AUTHORIZATION_STORAGE_AUTH_CHECKS("hive.metastore.authorization.storage.checks", false,
        "Should the metastore do authorization checks against the underlying storage (usually hdfs) \n" +
        "for operations like drop-partition (disallow the drop-partition if the user in\n" +
        "question doesn't have permissions to delete the corresponding directory\n" +
        "on the storage)."),
    METASTORE_AUTHORIZATION_EXTERNALTABLE_DROP_CHECK("hive.metastore.authorization.storage.check.externaltable.drop", true,
        "Should StorageBasedAuthorization check permission of the storage before dropping external table.\n" +
        "StorageBasedAuthorization already does this check for managed table. For external table however,\n" +
        "anyone who has read permission of the directory could drop external table, which is surprising.\n" +
        "The flag is set to false by default to maintain backward compatibility."),
    /**
     * @deprecated Use MetastoreConf.EVENT_CLEAN_FREQ
     */
    @Deprecated
    METASTORE_EVENT_CLEAN_FREQ("hive.metastore.event.clean.freq", "0s",
        new TimeValidator(TimeUnit.SECONDS),
        "Frequency at which timer task runs to purge expired events in metastore."),
    /**
     * @deprecated Use MetastoreConf.EVENT_EXPIRY_DURATION
     */
    @Deprecated
    METASTORE_EVENT_EXPIRY_DURATION("hive.metastore.event.expiry.duration", "0s",
        new TimeValidator(TimeUnit.SECONDS),
        "Duration after which events expire from events table"),
    /**
     * @deprecated Use MetastoreConf.EVENT_MESSAGE_FACTORY
     */
    @Deprecated
    METASTORE_EVENT_MESSAGE_FACTORY("hive.metastore.event.message.factory",
        "org.apache.hadoop.hive.metastore.messaging.json.gzip.GzipJSONMessageEncoder",
        "Factory class for making encoding and decoding messages in the events generated."),
    /**
     * @deprecated Use MetastoreConf.EXECUTE_SET_UGI
     */
    @Deprecated
    METASTORE_EXECUTE_SET_UGI("hive.metastore.execute.setugi", true,
        "In unsecure mode, setting this property to true will cause the metastore to execute DFS operations using \n" +
        "the client's reported user and group permissions. Note that this property must be set on \n" +
        "both the client and server sides. Further note that its best effort. \n" +
        "If client sets its to true and server sets it to false, client setting will be ignored."),
    /**
     * @deprecated Use MetastoreConf.PARTITION_NAME_WHITELIST_PATTERN
     */
    @Deprecated
    METASTORE_PARTITION_NAME_WHITELIST_PATTERN("hive.metastore.partition.name.whitelist.pattern", "",
        "Partition names will be checked against this regex pattern and rejected if not matched."),
    /**
     * @deprecated Use MetastoreConf.INTEGER_JDO_PUSHDOWN
     */
    @Deprecated
    METASTORE_INTEGER_JDO_PUSHDOWN("hive.metastore.integral.jdo.pushdown", false,
        "Allow JDO query pushdown for integral partition columns in metastore. Off by default. This\n" +
        "improves metastore perf for integral columns, especially if there's a large number of partitions.\n" +
        "However, it doesn't work correctly with integral values that are not normalized (e.g. have\n" +
        "leading zeroes, like 0012). If metastore direct SQL is enabled and works, this optimization\n" +
        "is also irrelevant."),
    /**
     * @deprecated Use MetastoreConf.TRY_DIRECT_SQL
     */
    @Deprecated
    METASTORE_TRY_DIRECT_SQL("hive.metastore.try.direct.sql", true,
        "Whether the Hive metastore should try to use direct SQL queries instead of the\n" +
        "DataNucleus for certain read paths. This can improve metastore performance when\n" +
        "fetching many partitions or column statistics by orders of magnitude; however, it\n" +
        "is not guaranteed to work on all RDBMS-es and all versions. In case of SQL failures,\n" +
        "the metastore will fall back to the DataNucleus, so it's safe even if SQL doesn't\n" +
        "work for all queries on your datastore. If all SQL queries fail (for example, your\n" +
        "metastore is backed by MongoDB), you might want to disable this to save the\n" +
        "try-and-fall-back cost."),
    /**
     * @deprecated Use MetastoreConf.DIRECT_SQL_PARTITION_BATCH_SIZE
     */
    @Deprecated
    METASTORE_DIRECT_SQL_PARTITION_BATCH_SIZE("hive.metastore.direct.sql.batch.size", 0,
        "Batch size for partition and other object retrieval from the underlying DB in direct\n" +
        "SQL. For some DBs like Oracle and MSSQL, there are hardcoded or perf-based limitations\n" +
        "that necessitate this. For DBs that can handle the queries, this isn't necessary and\n" +
        "may impede performance. -1 means no batching, 0 means automatic batching."),
    /**
     * @deprecated Use MetastoreConf.TRY_DIRECT_SQL_DDL
     */
    @Deprecated
    METASTORE_TRY_DIRECT_SQL_DDL("hive.metastore.try.direct.sql.ddl", true,
        "Same as hive.metastore.try.direct.sql, for read statements within a transaction that\n" +
        "modifies metastore data. Due to non-standard behavior in Postgres, if a direct SQL\n" +
        "select query has incorrect syntax or something similar inside a transaction, the\n" +
        "entire transaction will fail and fall-back to DataNucleus will not be possible. You\n" +
        "should disable the usage of direct SQL inside transactions if that happens in your case."),
    /**
     * @deprecated Use MetastoreConf.DIRECT_SQL_MAX_QUERY_LENGTH
     */
    @Deprecated
    METASTORE_DIRECT_SQL_MAX_QUERY_LENGTH("hive.direct.sql.max.query.length", 100, "The maximum\n" +
        " size of a query string (in KB)."),
    /**
     * @deprecated Use MetastoreConf.DIRECT_SQL_MAX_ELEMENTS_IN_CLAUSE
     */
    @Deprecated
    METASTORE_DIRECT_SQL_MAX_ELEMENTS_IN_CLAUSE("hive.direct.sql.max.elements.in.clause", 1000,
        "The maximum number of values in a IN clause. Once exceeded, it will be broken into\n" +
        " multiple OR separated IN clauses."),
    /**
     * @deprecated Use MetastoreConf.DIRECT_SQL_MAX_ELEMENTS_VALUES_CLAUSE
     */
    @Deprecated
    METASTORE_DIRECT_SQL_MAX_ELEMENTS_VALUES_CLAUSE("hive.direct.sql.max.elements.values.clause",
        1000, "The maximum number of values in a VALUES clause for INSERT statement."),
    /**
     * @deprecated Use MetastoreConf.ORM_RETRIEVE_MAPNULLS_AS_EMPTY_STRINGS
     */
    @Deprecated
    METASTORE_ORM_RETRIEVE_MAPNULLS_AS_EMPTY_STRINGS("hive.metastore.orm.retrieveMapNullsAsEmptyStrings",false,
        "Thrift does not support nulls in maps, so any nulls present in maps retrieved from ORM must " +
        "either be pruned or converted to empty strings. Some backing dbs such as Oracle persist empty strings " +
        "as nulls, so we should set this parameter if we wish to reverse that behaviour. For others, " +
        "pruning is the correct behaviour"),
    /**
     * @deprecated Use MetastoreConf.DISALLOW_INCOMPATIBLE_COL_TYPE_CHANGES
     */
    @Deprecated
    METASTORE_DISALLOW_INCOMPATIBLE_COL_TYPE_CHANGES(
        "hive.metastore.disallow.incompatible.col.type.changes", true,
        "If true (default is true), ALTER TABLE operations which change the type of a\n" +
        "column (say STRING) to an incompatible type (say MAP) are disallowed.\n" +
        "RCFile default SerDe (ColumnarSerDe) serializes the values in such a way that the\n" +
        "datatypes can be converted from string to any type. The map is also serialized as\n" +
        "a string, which can be read as a string as well. However, with any binary\n" +
        "serialization, this is not true. Blocking the ALTER TABLE prevents ClassCastExceptions\n" +
        "when subsequently trying to access old partitions.\n" +
        "\n" +
        "Primitive types like INT, STRING, BIGINT, etc., are compatible with each other and are\n" +
        "not blocked.\n" +
        "\n" +
        "See HIVE-4409 for more details."),
    /**
     * @deprecated Use MetastoreConf.LIMIT_PARTITION_REQUEST
     */
    @Deprecated
    METASTORE_LIMIT_PARTITION_REQUEST("hive.metastore.limit.partition.request", -1,
        "This limits the number of partitions that can be requested from the metastore for a given table.\n" +
            "The default value \"-1\" means no limit."),

    NEW_TABLE_DEFAULT_PARA("hive.table.parameters.default", "",
        "Default property values for newly created tables"),
    /**
     * @deprecated With HIVE-25813 table properties of source tables will not be copied over to dest table.
     * see HIVE-25813 jira for details..
     */
    @Deprecated
    DDL_CTL_PARAMETERS_WHITELIST("hive.ddl.createtablelike.properties.whitelist", "",
        "Table Properties to copy over when executing a Create Table Like."),
    /**
     * @deprecated Use MetastoreConf.RAW_STORE_IMPL
     */
    @Deprecated
    METASTORE_RAW_STORE_IMPL("hive.metastore.rawstore.impl", "org.apache.hadoop.hive.metastore.ObjectStore",
        "Name of the class that implements org.apache.hadoop.hive.metastore.rawstore interface. \n" +
        "This class is used to store and retrieval of raw metadata objects such as table, database"),
    /**
     * @deprecated Use MetastoreConf.TXN_STORE_IMPL
     */
    @Deprecated
    METASTORE_TXN_STORE_IMPL("hive.metastore.txn.store.impl",
        "org.apache.hadoop.hive.metastore.txn.CompactionTxnHandler",
        "Name of class that implements org.apache.hadoop.hive.metastore.txn.TxnStore.  This " +
        "class is used to store and retrieve transactions and locks"),
    /**
     * @deprecated Use MetastoreConf.CONNECTION_DRIVER
     */
    @Deprecated
    METASTORE_CONNECTION_DRIVER("javax.jdo.option.ConnectionDriverName", "org.apache.derby.jdbc.EmbeddedDriver",
        "Driver class name for a JDBC metastore"),
    /**
     * @deprecated Use MetastoreConf.MANAGER_FACTORY_CLASS
     */
    @Deprecated
    METASTORE_MANAGER_FACTORY_CLASS("javax.jdo.PersistenceManagerFactoryClass",
        "org.datanucleus.api.jdo.JDOPersistenceManagerFactory",
        "class implementing the jdo persistence"),
    /**
     * @deprecated Use MetastoreConf.EXPRESSION_PROXY_CLASS
     */
    @Deprecated
    METASTORE_EXPRESSION_PROXY_CLASS("hive.metastore.expression.proxy",
        "org.apache.hadoop.hive.ql.optimizer.ppr.PartitionExpressionForMetastore", ""),
    /**
     * @deprecated Use MetastoreConf.DETACH_ALL_ON_COMMIT
     */
    @Deprecated
    METASTORE_DETACH_ALL_ON_COMMIT("javax.jdo.option.DetachAllOnCommit", true,
        "Detaches all objects from session so that they can be used after transaction is committed"),
    /**
     * @deprecated Use MetastoreConf.NON_TRANSACTIONAL_READ
     */
    @Deprecated
    METASTORE_NON_TRANSACTIONAL_READ("javax.jdo.option.NonTransactionalRead", true,
        "Reads outside of transactions"),
    /**
     * @deprecated Use MetastoreConf.CONNECTION_USER_NAME
     */
    @Deprecated
    METASTORE_CONNECTION_USER_NAME("javax.jdo.option.ConnectionUserName", "APP",
        "Username to use against metastore database"),
    /**
     * @deprecated Use MetastoreConf.END_FUNCTION_LISTENERS
     */
    @Deprecated
    METASTORE_END_FUNCTION_LISTENERS("hive.metastore.end.function.listeners", "",
        "List of comma separated listeners for the end of metastore functions."),
    /**
     * @deprecated Use MetastoreConf.PART_INHERIT_TBL_PROPS
     */
    @Deprecated
    METASTORE_PART_INHERIT_TBL_PROPS("hive.metastore.partition.inherit.table.properties", "",
        "List of comma separated keys occurring in table properties which will get inherited to newly created partitions. \n" +
        "* implies all the keys will get inherited."),
    /**
     * @deprecated Use MetastoreConf.FILTER_HOOK
     */
    @Deprecated
    METASTORE_FILTER_HOOK("hive.metastore.filter.hook", "org.apache.hadoop.hive.metastore.DefaultMetaStoreFilterHookImpl",
        "Metastore hook class for filtering the metadata read results. If hive.security.authorization.manager"
        + "is set to instance of HiveAuthorizerFactory, then this value is ignored."),
    FIRE_EVENTS_FOR_DML("hive.metastore.dml.events", false, "If true, the metastore will be asked" +
        " to fire events for DML operations"),
    METASTORE_CLIENT_DROP_PARTITIONS_WITH_EXPRESSIONS("hive.metastore.client.drop.partitions.using.expressions", true,
        "Choose whether dropping partitions with HCatClient pushes the partition-predicate to the metastore, " +
            "or drops partitions iteratively"),

    /**
     * @deprecated Use MetastoreConf.AGGREGATE_STATS_CACHE_ENABLED
     */
    @Deprecated
    METASTORE_AGGREGATE_STATS_CACHE_ENABLED("hive.metastore.aggregate.stats.cache.enabled", false,
        "Whether aggregate stats caching is enabled or not."),
    /**
     * @deprecated Use MetastoreConf.AGGREGATE_STATS_CACHE_SIZE
     */
    @Deprecated
    METASTORE_AGGREGATE_STATS_CACHE_SIZE("hive.metastore.aggregate.stats.cache.size", 10000,
        "Maximum number of aggregate stats nodes that we will place in the metastore aggregate stats cache."),
    /**
     * @deprecated Use MetastoreConf.AGGREGATE_STATS_CACHE_MAX_PARTITIONS
     */
    @Deprecated
    METASTORE_AGGREGATE_STATS_CACHE_MAX_PARTITIONS("hive.metastore.aggregate.stats.cache.max.partitions", 10000,
        "Maximum number of partitions that are aggregated per cache node."),
    /**
     * @deprecated Use MetastoreConf.AGGREGATE_STATS_CACHE_FPP
     */
    @Deprecated
    METASTORE_AGGREGATE_STATS_CACHE_FPP("hive.metastore.aggregate.stats.cache.fpp", (float) 0.01,
        "Maximum false positive probability for the Bloom Filter used in each aggregate stats cache node (default 1%)."),
    /**
     * @deprecated Use MetastoreConf.AGGREGATE_STATS_CACHE_MAX_VARIANCE
     */
    @Deprecated
    METASTORE_AGGREGATE_STATS_CACHE_MAX_VARIANCE("hive.metastore.aggregate.stats.cache.max.variance", (float) 0.01,
        "Maximum tolerable variance in number of partitions between a cached node and our request (default 1%)."),
    /**
     * @deprecated Use MetastoreConf.AGGREGATE_STATS_CACHE_TTL
     */
    @Deprecated
    METASTORE_AGGREGATE_STATS_CACHE_TTL("hive.metastore.aggregate.stats.cache.ttl", "600s", new TimeValidator(TimeUnit.SECONDS),
        "Number of seconds for a cached node to be active in the cache before they become stale."),
    /**
     * @deprecated Use MetastoreConf.AGGREGATE_STATS_CACHE_MAX_WRITER_WAIT
     */
    @Deprecated
    METASTORE_AGGREGATE_STATS_CACHE_MAX_WRITER_WAIT("hive.metastore.aggregate.stats.cache.max.writer.wait", "5000ms",
        new TimeValidator(TimeUnit.MILLISECONDS),
        "Number of milliseconds a writer will wait to acquire the writelock before giving up."),
    /**
     * @deprecated Use MetastoreConf.AGGREGATE_STATS_CACHE_MAX_READER_WAIT
     */
    @Deprecated
    METASTORE_AGGREGATE_STATS_CACHE_MAX_READER_WAIT("hive.metastore.aggregate.stats.cache.max.reader.wait", "1000ms",
        new TimeValidator(TimeUnit.MILLISECONDS),
        "Number of milliseconds a reader will wait to acquire the readlock before giving up."),
    /**
     * @deprecated Use MetastoreConf.AGGREGATE_STATS_CACHE_MAX_FULL
     */
    @Deprecated
    METASTORE_AGGREGATE_STATS_CACHE_MAX_FULL("hive.metastore.aggregate.stats.cache.max.full", (float) 0.9,
        "Maximum cache full % after which the cache cleaner thread kicks in."),
    /**
     * @deprecated Use MetastoreConf.AGGREGATE_STATS_CACHE_CLEAN_UNTIL
     */
    @Deprecated
    METASTORE_AGGREGATE_STATS_CACHE_CLEAN_UNTIL("hive.metastore.aggregate.stats.cache.clean.until", (float) 0.8,
        "The cleaner thread cleans until cache reaches this % full size."),
    /**
     * @deprecated Use MetastoreConf.METRICS_ENABLED
     */
    @Deprecated
    METASTORE_METRICS("hive.metastore.metrics.enabled", false, "Enable metrics on the metastore."),
    // Metastore SSL settings
    /**
     * @deprecated Use MetastoreConf.USE_SSL
     */
    @Deprecated
    HIVE_METASTORE_USE_SSL("hive.metastore.use.SSL", false,
        "Set this to true for using SSL encryption in HMS server."),
    /**
     * @deprecated Use MetastoreConf.SSL_KEYSTORE_PATH
     */
    @Deprecated
    HIVE_METASTORE_SSL_KEYSTORE_PATH("hive.metastore.keystore.path", "",
        "Metastore SSL certificate keystore location."),
    /**
     * @deprecated Use MetastoreConf.SSL_KEYSTORE_PASSWORD
     */
    @Deprecated
    HIVE_METASTORE_SSL_KEYSTORE_PASSWORD("hive.metastore.keystore.password", "",
        "Metastore SSL certificate keystore password."),
    /**
     * @deprecated Use MetastoreConf.SSL_TRUSTSTORE_PATH
     */
    @Deprecated
    HIVE_METASTORE_SSL_TRUSTSTORE_PATH("hive.metastore.truststore.path", "",
        "Metastore SSL certificate truststore location."),
    /**
     * @deprecated Use MetastoreConf.SSL_TRUSTSTORE_PASSWORD
     */
    @Deprecated
    HIVE_METASTORE_SSL_TRUSTSTORE_PASSWORD("hive.metastore.truststore.password", "",
        "Metastore SSL certificate truststore password."),

    // Parameters for exporting metadata on table drop (requires the use of the)
    // org.apache.hadoop.hive.ql.parse.MetaDataExportListener preevent listener
    /**
     * @deprecated Use MetastoreConf.METADATA_EXPORT_LOCATION
     */
    @Deprecated
    METADATA_EXPORT_LOCATION("hive.metadata.export.location", "",
        "When used in conjunction with the org.apache.hadoop.hive.ql.parse.MetaDataExportListener pre event listener, \n" +
        "it is the location to which the metadata will be exported. The default is an empty string, which results in the \n" +
        "metadata being exported to the current user's home directory on HDFS."),
    /**
     * @deprecated Use MetastoreConf.MOVE_EXPORTED_METADATA_TO_TRASH
     */
    @Deprecated
    MOVE_EXPORTED_METADATA_TO_TRASH("hive.metadata.move.exported.metadata.to.trash", true,
        "When used in conjunction with the org.apache.hadoop.hive.ql.parse.MetaDataExportListener pre event listener, \n" +
        "this setting determines if the metadata that is exported will subsequently be moved to the user's trash directory \n" +
        "alongside the dropped table data. This ensures that the metadata will be cleaned up along with the dropped table data."),

    // CLI
    CLI_IGNORE_ERRORS("hive.cli.errors.ignore", false, ""),
    CLI_PRINT_CURRENT_DB("hive.cli.print.current.db", false,
        "Whether to include the current database in the Hive prompt."),
    CLI_PROMPT("hive.cli.prompt", "hive",
        "Command line prompt configuration value. Other hiveconf can be used in this configuration value. \n" +
        "Variable substitution will only be invoked at the Hive CLI startup."),
    /**
     * @deprecated Use MetastoreConf.FS_HANDLER_CLS
     */
    @Deprecated
    HIVE_METASTORE_FS_HANDLER_CLS("hive.metastore.fs.handler.class", "org.apache.hadoop.hive.metastore.HiveMetaStoreFsImpl", ""),

    // Things we log in the jobconf

    // session identifier
    HIVE_SESSION_ID("hive.session.id", "", ""),
    // whether session is running in silent mode or not
    HIVE_SESSION_SILENT("hive.session.silent", false, ""),

    HIVE_LOCAL_TIME_ZONE("hive.local.time.zone", "LOCAL",
        "Sets the time-zone for displaying and interpreting time stamps. If this property value is set to\n" +
        "LOCAL, it is not specified, or it is not a correct time-zone, the system default time-zone will be\n " +
        "used instead. Time-zone IDs can be specified as region-based zone IDs (based on IANA time-zone data),\n" +
        "abbreviated zone IDs, or offset IDs."),

    HIVE_SESSION_HISTORY_ENABLED("hive.session.history.enabled", false,
        "Whether to log Hive query, query plan, runtime statistics etc."),

    HIVE_QUERY_STRING("hive.query.string", "",
        "Query being executed (might be multiple per a session)"),

    HIVE_QUERY_ID("hive.query.id", "",
        "ID for query being executed (might be multiple per a session)"),

    HIVE_QUERY_TAG("hive.query.tag", null, "Tag for the queries in the session. User can kill the queries with the tag " +
        "in another session. Currently there is no tag duplication check, user need to make sure his tag is unique. " +
        "Also 'kill query' needs to be issued to all HiveServer2 instances to proper kill the queries"),

    HIVE_JOBNAME_LENGTH("hive.jobname.length", 50, "max jobname length"),

    // hive jar
    HIVE_JAR("hive.jar.path", "",
        "The location of hive_cli.jar that is used when submitting jobs in a separate jvm."),
    HIVE_AUX_JARS("hive.aux.jars.path", "",
        "The location of the plugin jars that contain implementations of user defined functions and serdes."),

    // reloadable jars
    HIVE_RELOADABLE_JARS("hive.reloadable.aux.jars.path", "",
        "The locations of the plugin jars, which can be a comma-separated folders or jars. Jars can be renewed\n"
        + "by executing reload command. And these jars can be "
            + "used as the auxiliary classes like creating a UDF or SerDe."),

    // hive added files and jars
    HIVE_ADDED_FILES("hive.added.files.path", "", "This an internal parameter."),
    HIVE_ADDED_JARS("hive.added.jars.path", "", "This an internal parameter."),
    HIVE_ADDED_ARCHIVES("hive.added.archives.path", "", "This an internal parameter."),
    HIVE_ADD_FILES_USE_HDFS_LOCATION("hive.resource.use.hdfs.location", true, "Reference HDFS based files/jars directly instead of "
        + "copy to session based HDFS scratch directory, to make distributed cache more useful."),

    HIVE_CURRENT_DATABASE("hive.current.database", "", "Database name used by current session. Internal usage only.", true),

    // for hive script operator
    HIVES_AUTO_PROGRESS_TIMEOUT("hive.auto.progress.timeout", "0s",
        new TimeValidator(TimeUnit.SECONDS),
        "How long to run autoprogressor for the script/UDTF operators.\n" +
        "Set to 0 for forever."),
    HIVE_SCRIPT_AUTO_PROGRESS("hive.script.auto.progress", false,
        "Whether Hive Transform/Map/Reduce Clause should automatically send progress information to TaskTracker \n" +
        "to avoid the task getting killed because of inactivity.  Hive sends progress information when the script is \n" +
        "outputting to stderr.  This option removes the need of periodically producing stderr messages, \n" +
        "but users should be cautious because this may prevent infinite loops in the scripts to be killed by TaskTracker."),
    HIVE_SCRIPT_ID_ENV_VAR("hive.script.operator.id.env.var", "HIVE_SCRIPT_OPERATOR_ID",
        "Name of the environment variable that holds the unique script operator ID in the user's \n" +
        "transform function (the custom mapper/reducer that the user has specified in the query)"),
    HIVE_SCRIPT_TRUNCATE_ENV("hive.script.operator.truncate.env", false,
        "Truncate each environment variable for external script in scripts operator to 20KB (to fit system limits)"),
    HIVE_SCRIPT_ENV_BLACKLIST("hive.script.operator.env.blacklist",
        "hive.txn.valid.txns,hive.txn.tables.valid.writeids,hive.txn.valid.writeids,hive.script.operator.env.blacklist,hive.repl.current.table.write.id",
        "Comma separated list of keys from the configuration file not to convert to environment " +
        "variables when invoking the script operator"),
    HIVE_STRICT_CHECKS_ORDERBY_NO_LIMIT("hive.strict.checks.orderby.no.limit", false,
        "Enabling strict large query checks disallows the following:\n" +
        "  Orderby without limit.\n" +
        "Note that this check currently does not consider data size, only the query pattern."),
    HIVE_STRICT_CHECKS_NO_PARTITION_FILTER("hive.strict.checks.no.partition.filter", false,
        "Enabling strict large query checks disallows the following:\n" +
        "  No partition being picked up for a query against partitioned table.\n" +
        "Note that this check currently does not consider data size, only the query pattern."),
    HIVE_STRICT_CHECKS_TYPE_SAFETY("hive.strict.checks.type.safety", true,
        "Enabling strict type safety checks disallows the following:\n" +
        "  Comparing bigints and strings/(var)chars.\n" +
        "  Comparing bigints and doubles.\n" +
        "  Comparing decimals and strings/(var)chars."),
    HIVE_STRICT_CHECKS_CARTESIAN("hive.strict.checks.cartesian.product", false,
        "Enabling strict Cartesian join checks disallows the following:\n" +
        "  Cartesian product (cross join)."),
    HIVE_STRICT_CHECKS_BUCKETING("hive.strict.checks.bucketing", true,
        "Enabling strict bucketing checks disallows the following:\n" +
        "  Load into bucketed tables."),
    HIVE_STRICT_CHECKS_OFFSET_NO_ORDERBY("hive.strict.checks.offset.no.orderby", false,
        "Enabling strict offset checks disallows the following:\n" +
        "  OFFSET without ORDER BY.\n" +
        "OFFSET is mostly meaningless when a result set doesn't have a total order."),
    HIVE_STRICT_TIMESTAMP_CONVERSION("hive.strict.timestamp.conversion", true,
        "Restricts unsafe numeric to timestamp conversions"),
    HIVE_LOAD_DATA_OWNER("hive.load.data.owner", "",
        "Set the owner of files loaded using load data in managed tables."),

    @Deprecated
    HIVE_MAPRED_MODE("hive.mapred.mode", null,
        "Deprecated; use hive.strict.checks.* settings instead."),
    HIVE_ALIAS("hive.alias", "", ""),
    HIVE_MAPSIDE_AGGREGATE("hive.map.aggr", true, "Whether to use map-side aggregation in Hive Group By queries"),
    HIVE_GROUPBY_SKEW("hive.groupby.skewindata", false, "Whether there is skew in data to optimize group by queries"),
    HIVE_JOIN_SHORTCUT_UNMATCHED_ROWS("hive.join.shortcut.unmatched.rows", true,
        "Enables to shortcut processing of known filtered rows in merge joins. internal use only. may affect correctness"),
    HIVE_JOIN_EMIT_INTERVAL("hive.join.emit.interval", 1000,
        "How many rows in the right-most join operand Hive should buffer before emitting the join result."),
    HIVE_JOIN_CACHE_SIZE("hive.join.cache.size", 25000,
        "How many rows in the joining tables (except the streaming table) should be cached in memory."),
    HIVE_PUSH_RESIDUAL_INNER("hive.join.inner.residual", false,
        "Whether to push non-equi filter predicates within inner joins. This can improve efficiency in "
        + "the evaluation of certain joins, since we will not be emitting rows which are thrown away by "
        + "a Filter operator straight away. However, currently vectorization does not support them, thus "
        + "enabling it is only recommended when vectorization is disabled."),
    HIVE_PTF_RANGECACHE_SIZE("hive.ptf.rangecache.size", 10000,
        "Size of the cache used on reducer side, that stores boundaries of ranges within a PTF " +
        "partition. Used if a query specifies a RANGE type window including an orderby clause." +
        "Set this to 0 to disable this cache."),
    HIVE_PTF_VALUECACHE_SIZE("hive.ptf.valuecache.size", 10000,
        "Size of the cache used on reducer side, that stores calculated values for ranges within a PTF "
            + "partition. Set this to 0 to disable this cache."),
    HIVE_PTF_VALUECACHE_COLLECT_STATISTICS("hive.ptf.valuecache.collect.statistics", false,
        "Whether to collect cache statistics in PTFValueCache. On extremely hot codepaths, this can be expensive, "
            + "so it's disabled by default. It's only for development/debugging purposes, "
            + "execution engine doesn't take advantage of statistics stored in the cache."),
    // CBO related
    HIVE_CBO_ENABLED("hive.cbo.enable", true, "Flag to control enabling Cost Based Optimizations using Calcite framework."),
    HIVE_CBO_FALLBACK_STRATEGY("hive.cbo.fallback.strategy", "NEVER",
        new StringSet(true, "NEVER", "CONSERVATIVE", "ALWAYS"),
        "The strategy defines when Hive fallbacks to legacy optimizer when CBO fails:" 
            + "NEVER, never use the legacy optimizer (all CBO errors are fatal);"
            + "ALWAYS, always use the legacy optimizer (CBO errors are not fatal);"
            + "CONSERVATIVE, use the legacy optimizer only when the CBO error is not related to subqueries and views."), 
    HIVE_CBO_CNF_NODES_LIMIT("hive.cbo.cnf.maxnodes", -1, "When converting to conjunctive normal form (CNF), fail if" +
        "the expression exceeds this threshold; the threshold is expressed in terms of number of nodes (leaves and" +
        "interior nodes). -1 to not set up a threshold."),
    HIVE_CBO_RETPATH_HIVEOP("hive.cbo.returnpath.hiveop", false, "Flag to control calcite plan to hive operator conversion"),
    HIVE_CBO_EXTENDED_COST_MODEL("hive.cbo.costmodel.extended", false, "Flag to control enabling the extended cost model based on"
                                 + "CPU, IO and cardinality. Otherwise, the cost model is based on cardinality."),
    HIVE_CBO_COST_MODEL_CPU("hive.cbo.costmodel.cpu", "0.000001", "Default cost of a comparison"),
    HIVE_CBO_COST_MODEL_NET("hive.cbo.costmodel.network", "150.0", "Default cost of a transferring a byte over network;"
                                                                  + " expressed as multiple of CPU cost"),
    HIVE_CBO_COST_MODEL_LFS_WRITE("hive.cbo.costmodel.local.fs.write", "4.0", "Default cost of writing a byte to local FS;"
                                                                             + " expressed as multiple of NETWORK cost"),
    HIVE_CBO_COST_MODEL_LFS_READ("hive.cbo.costmodel.local.fs.read", "4.0", "Default cost of reading a byte from local FS;"
                                                                           + " expressed as multiple of NETWORK cost"),
    HIVE_CBO_COST_MODEL_HDFS_WRITE("hive.cbo.costmodel.hdfs.write", "10.0", "Default cost of writing a byte to HDFS;"
                                                                 + " expressed as multiple of Local FS write cost"),
    HIVE_CBO_COST_MODEL_HDFS_READ("hive.cbo.costmodel.hdfs.read", "1.5", "Default cost of reading a byte from HDFS;"
                                                                 + " expressed as multiple of Local FS read cost"),
    HIVE_CBO_RULE_EXCLUSION_REGEX("hive.cbo.rule.exclusion.regex", "",
        "Regex over rule descriptions to exclude them from planning. "
            + "The intended usage is to allow to disable rules from problematic queries, it is *not* a performance tuning property. "
            + "The property is experimental, it can be changed or removed without any notice."),
    HIVE_CBO_SHOW_WARNINGS("hive.cbo.show.warnings", true,
         "Toggle display of CBO warnings like missing column stats"),
    HIVE_CBO_STATS_CORRELATED_MULTI_KEY_JOINS("hive.cbo.stats.correlated.multi.key.joins", true,
        "When CBO estimates output rows for a join involving multiple columns, the default behavior assumes" +
            "the columns are independent. Setting this flag to true will cause the estimator to assume" +
            "the columns are correlated."),
    HIVE_CARDINALITY_PRESERVING_JOIN_OPTIMIZATION_FACTOR("hive.cardinality.preserving.join.optimization.factor", 1.0f,
        "Original plan cost multiplier for rewriting when query has tables joined multiple time on primary/unique key and " +
            "projected the majority of columns from these table. This optimization trims fields at root of tree and " +
            "then joins back affected tables at top of tree to get rest of columns. " +
            "Set this to 0.0 to disable this optimization or increase it for more aggressive optimization."),
    AGGR_JOIN_TRANSPOSE("hive.transpose.aggr.join", false, "push aggregates through join"),
    AGGR_JOIN_TRANSPOSE_UNIQUE("hive.transpose.aggr.join.unique", true, "push aggregates through join(s) in "
        + "case data is regrouped on a previously unique column"),
    SEMIJOIN_CONVERSION("hive.optimize.semijoin.conversion", true, "convert group by followed by inner equi join into semijoin"),
    HIVE_COLUMN_ALIGNMENT("hive.order.columnalignment", true, "Flag to control whether we want to try to align" +
        "columns in operators such as Aggregate or Join so that we try to reduce the number of shuffling stages"),

    // materialized views
    HIVE_MATERIALIZED_VIEW_ENABLE_AUTO_REWRITING("hive.materializedview.rewriting", true,
        "Whether to try to rewrite queries using the materialized views enabled for rewriting"),
    HIVE_MATERIALIZED_VIEW_ENABLE_AUTO_REWRITING_SQL("hive.materializedview.rewriting.sql", true,
        "Whether to try to rewrite queries using the materialized views enabled for rewriting by comparing the sql " +
                "query syntax tree with the materialized views query syntax tree"),
    HIVE_MATERIALIZED_VIEW_ENABLE_AUTO_REWRITING_SUBQUERY_SQL("hive.materializedview.rewriting.sql.subquery", true,
        "Whether to try to rewrite sub-queries using the materialized views enabled for rewriting by comparing the sql " +
                "sub-query syntax tree with the materialized views query syntax tree"),
    HIVE_MATERIALIZED_VIEW_REWRITING_SELECTION_STRATEGY("hive.materializedview.rewriting.strategy", "heuristic",
        new StringSet("heuristic", "costbased"),
        "The strategy that should be used to cost and select the materialized view rewriting. \n" +
            "  heuristic: Always try to select the plan using the materialized view if rewriting produced one," +
            "choosing the plan with lower cost among possible plans containing a materialized view\n" +
            "  costbased: Fully cost-based strategy, always use plan with lower cost, independently on whether " +
            "it uses a materialized view or not"),
    HIVE_MATERIALIZED_VIEW_REWRITING_TIME_WINDOW("hive.materializedview.rewriting.time.window", "0min", new TimeValidator(TimeUnit.MINUTES),
        "Time window, specified in seconds, after which outdated materialized views become invalid for automatic query rewriting.\n" +
        "For instance, if more time than the value assigned to the property has passed since the materialized view " +
        "was created or rebuilt, and one of its source tables has changed since, the materialized view will not be " +
        "considered for rewriting. Default value 0 means that the materialized view cannot be " +
        "outdated to be used automatically in query rewriting. Value -1 means to skip this check."),
    HIVE_MATERIALIZED_VIEW_REWRITING_INCREMENTAL("hive.materializedview.rewriting.incremental", false,
        "Whether to try to execute incremental rewritings based on outdated materializations and\n" +
        "current content of tables. Default value of true effectively amounts to enabling incremental\n" +
        "rebuild for the materializations too."),
    HIVE_MATERIALIZED_VIEW_REBUILD_INCREMENTAL("hive.materializedview.rebuild.incremental", true,
        "Whether to try to execute incremental rebuild for the materialized views. Incremental rebuild\n" +
        "tries to modify the original materialization contents to reflect the latest changes to the\n" +
        "materialized view source tables, instead of rebuilding the contents fully. Incremental rebuild\n" +
        "is based on the materialized view algebraic incremental rewriting."),
    HIVE_MATERIALIZED_VIEW_REBUILD_INCREMENTAL_FACTOR("hive.materializedview.rebuild.incremental.factor", 0.1f,
        "The estimated cost of the resulting plan for incremental maintenance of materialization\n" +
        "with aggregations will be multiplied by this value. Reducing the value can be useful to\n" +
        "favour incremental rebuild over full rebuild."),
    HIVE_MATERIALIZED_VIEW_FILE_FORMAT("hive.materializedview.fileformat", "ORC",
        new StringSet("none", "TextFile", "SequenceFile", "RCfile", "ORC", "parquet"),
        "Default file format for CREATE MATERIALIZED VIEW statement"),
    HIVE_MATERIALIZED_VIEW_SERDE("hive.materializedview.serde",
        "org.apache.hadoop.hive.ql.io.orc.OrcSerde", "Default SerDe used for materialized views"),

    HIVE_ENABLE_JDBC_PUSHDOWN("hive.jdbc.pushdown.enable", true,
        "Flag to control enabling pushdown of operators into JDBC connection and subsequent SQL generation\n" +
        "using Calcite"),
    HIVE_ENABLE_JDBC_SAFE_PUSHDOWN("hive.jdbc.pushdown.safe.enable", false,
        "Flag to control enabling pushdown of operators using Calcite that prevent splitting results\n" +
        "retrieval in the JDBC storage handler"),
    HIVE_JDBC_FETCH_THREADS("hive.jdbc.fetch.threads", 1,
        "Controls the number of thread/connections used to fetch results for a JDBC query"),

    // hive.mapjoin.bucket.cache.size has been replaced by hive.smbjoin.cache.row,
    // need to remove by hive .13. Also, do not change default (see SMB operator)
    HIVE_MAPJOIN_BUCKET_CACHE_SIZE("hive.mapjoin.bucket.cache.size", 100, ""),

    HIVE_MAPJOIN_USE_OPTIMIZED_TABLE("hive.mapjoin.optimized.hashtable", true,
        "Whether Hive should use memory-optimized hash table for MapJoin.\n" +
        "Only works on Tez because memory-optimized hashtable cannot be serialized."),
    HIVE_MAPJOIN_OPTIMIZED_TABLE_PROBE_PERCENT("hive.mapjoin.optimized.hashtable.probe.percent",
        (float) 0.5, "Probing space percentage of the optimized hashtable"),
    HIVE_MAPJOIN_PARALEL_HASHTABLE_THREADS("hive.mapjoin.hashtable.load.threads", 2,
        "Number of threads used to load records from a broadcast edge in HT used for MJ"),
    HIVE_USE_HYBRIDGRACE_HASHJOIN("hive.mapjoin.hybridgrace.hashtable", false, "Whether to use hybrid" +
        "grace hash join as the join method for mapjoin. Tez only."),
    HIVE_HYBRIDGRACE_HASHJOIN_MEMCHECK_FREQ("hive.mapjoin.hybridgrace.memcheckfrequency", 1024, "For " +
        "hybrid grace hash join, how often (how many rows apart) we check if memory is full. " +
        "This number should be power of 2."),
    HIVE_HYBRIDGRACE_HASHJOIN_MIN_WB_SIZE("hive.mapjoin.hybridgrace.minwbsize", 524288, "For hybrid grace" +
        "Hash join, the minimum write buffer size used by optimized hashtable. Default is 512 KB."),
    HIVE_HYBRIDGRACE_HASHJOIN_MIN_NUM_PARTITIONS("hive.mapjoin.hybridgrace.minnumpartitions", 16, "For" +
        "Hybrid grace hash join, the minimum number of partitions to create."),
    HIVE_HASHTABLE_WB_SIZE("hive.mapjoin.optimized.hashtable.wbsize", 8 * 1024 * 1024,
        "Optimized hashtable (see hive.mapjoin.optimized.hashtable) uses a chain of buffers to\n" +
        "store data. This is one buffer size. HT may be slightly faster if this is larger, but for small\n" +
        "joins unnecessary memory will be allocated and then trimmed."),
    HIVE_HYBRIDGRACE_HASHJOIN_BLOOMFILTER("hive.mapjoin.hybridgrace.bloomfilter", true, "Whether to " +
        "use BloomFilter in Hybrid grace hash join to minimize unnecessary spilling."),
    HIVE_MAPJOIN_FULL_OUTER("hive.mapjoin.full.outer", true,
        "Whether to use MapJoin for FULL OUTER JOINs."),
    HIVE_TEST_MAPJOINFULLOUTER_OVERRIDE(
        "hive.test.mapjoin.full.outer.override",
        "none", new StringSet("none", "enable", "disable"),
        "internal use only, used to override the hive.mapjoin.full.outer\n" +
        "setting.  Using enable will force it on and disable will force it off.\n" +
        "The default none is do nothing, of course",
        true),
    HIVE_SMBJOIN_CACHE_ROWS("hive.smbjoin.cache.rows", 10000,
        "How many rows with the same key value should be cached in memory per smb joined table."),
    HIVE_GROUPBY_MAP_INTERVAL("hive.groupby.mapaggr.checkinterval", 100000,
        "Number of rows after which size of the grouping keys/aggregation classes is performed"),
    HIVE_MAP_AGGR_HASH_MEMORY("hive.map.aggr.hash.percentmemory", (float) 0.5,
        "Portion of total memory to be used by map-side group aggregation hash table"),
    HIVE_MAPJOIN_FOLLOWEDBY_MAP_AGGR_HASH_MEMORY("hive.mapjoin.followby.map.aggr.hash.percentmemory", (float) 0.3,
        "Portion of total memory to be used by map-side group aggregation hash table, when this group by is followed by map join"),
    HIVE_MAP_AGGR_MEMORY_THRESHOLD("hive.map.aggr.hash.force.flush.memory.threshold", (float) 0.9,
        "The max memory to be used by map-side group aggregation hash table.\n" +
        "If the memory usage is higher than this number, force to flush data"),
    HIVE_MAP_AGGR_HASH_MIN_REDUCTION("hive.map.aggr.hash.min.reduction", (float) 0.99,
        "Hash aggregation will be turned off if the ratio between hash  table size and input rows is bigger than this number. \n" +
        "Set to 1 to make sure hash aggregation is never turned off."),
    HIVE_MAP_AGGR_HASH_MIN_REDUCTION_LOWER_BOUND("hive.map.aggr.hash.min.reduction.lower.bound", (float) 0.4,
        "Lower bound of Hash aggregate reduction filter. See also: hive.map.aggr.hash.min.reduction"),
    HIVE_MAP_AGGR_HASH_FLUSH_SIZE_PERCENT("hive.map.aggr.hash.flush.size.percent", (float) 0.1,
        "Percentage of hash table entries to flush in map-side group aggregation."),
    HIVE_MAP_AGGR_HASH_MIN_REDUCTION_STATS_ADJUST("hive.map.aggr.hash.min.reduction.stats", true,
        "Whether the value for hive.map.aggr.hash.min.reduction should be set statically using stats estimates. \n" +
        "If this is enabled, the default value for hive.map.aggr.hash.min.reduction is only used as an upper-bound\n" +
        "for the value set in the map-side group by operators."),
    HIVE_MULTI_GROUPBY_SINGLE_REDUCER("hive.multigroupby.singlereducer", true,
        "Whether to optimize multi group by query to generate single M/R  job plan. If the multi group by query has \n" +
        "common group by keys, it will be optimized to generate single M/R job."),
    HIVE_MAP_GROUPBY_SORT("hive.map.groupby.sorted", false,
        "If the bucketing/sorting properties of the table exactly match the grouping key, whether to perform \n" +
        "the group by in the mapper by using BucketizedHiveInputFormat. This can only work if the number of files to be\n" +
        "processed is exactly 1. The downside to this is that it limits the number of mappers to the number of files."),
    HIVE_DEFAULT_NULLS_LAST("hive.default.nulls.last", true,
        "Whether to set NULLS LAST as the default null ordering for ASC order and " +
            "NULLS FIRST for DESC order."),
    HIVE_GROUPBY_POSITION_ALIAS("hive.groupby.position.alias", false,
        "Whether to enable using Column Position Alias in Group By"),
    HIVE_ORDERBY_POSITION_ALIAS("hive.orderby.position.alias", true,
        "Whether to enable using Column Position Alias in Order By"),
    @Deprecated
    HIVE_GROUPBY_ORDERBY_POSITION_ALIAS("hive.groupby.orderby.position.alias", false,
        "Whether to enable using Column Position Alias in Group By or Order By (deprecated).\n" +
        "Use " + HIVE_ORDERBY_POSITION_ALIAS.varname + " or " + HIVE_GROUPBY_POSITION_ALIAS.varname + " instead"),
    HIVE_NEW_JOB_GROUPING_SET_CARDINALITY("hive.new.job.grouping.set.cardinality", 30,
        "Whether a new map-reduce job should be launched for grouping sets/rollups/cubes.\n" +
        "For a query like: select a, b, c, count(1) from T group by a, b, c with rollup;\n" +
        "4 rows are created per row: (a, b, c), (a, b, null), (a, null, null), (null, null, null).\n" +
        "This can lead to explosion across map-reduce boundary if the cardinality of T is very high,\n" +
        "and map-side aggregation does not do a very good job. \n" +
        "\n" +
        "This parameter decides if Hive should add an additional map-reduce job. If the grouping set\n" +
        "cardinality (4 in the example above), is more than this value, a new MR job is added under the\n" +
        "assumption that the original group by will reduce the data size."),
    HIVE_GROUPBY_LIMIT_EXTRASTEP("hive.groupby.limit.extrastep", true, "This parameter decides if Hive should \n" +
        "create new MR job for sorting final output"),
    HIVE_OPTIMIZE_GROUPING_SET_THRESHOLD("hive.optimize.grouping.set.threshold", 1_000_000_000L,
        "If # of estimated rows emitted by GroupBy operator with GroupingSet is larger than the configured value, " +
        "then the optimizer inserts an extra shuffle to partitioning input data.\n" +
        "Setting a negative number disables the optimization."),

    // Max file num and size used to do a single copy (after that, distcp is used)
    HIVE_EXEC_COPYFILE_MAXNUMFILES("hive.exec.copyfile.maxnumfiles", 1L,
        "Maximum number of files Hive uses to do sequential HDFS copies between directories." +
        "Distributed copies (distcp) will be used instead for larger numbers of files so that copies can be done faster."),
    HIVE_EXEC_COPYFILE_MAXSIZE("hive.exec.copyfile.maxsize", 32L * 1024 * 1024 /*32M*/,
        "Maximum file size (in bytes) that Hive uses to do single HDFS copies between directories." +
        "Distributed copies (distcp) will be used instead for bigger files so that copies can be done faster."),

    // for hive udtf operator
    HIVE_UDTF_AUTO_PROGRESS("hive.udtf.auto.progress", false,
        "Whether Hive should automatically send progress information to TaskTracker \n" +
        "when using UDTF's to prevent the task getting killed because of inactivity.  Users should be cautious \n" +
        "because this may prevent TaskTracker from killing tasks with infinite loops."),

    HIVE_DEFAULT_FILEFORMAT("hive.default.fileformat", "TextFile", new StringSet("TextFile", "SequenceFile", "RCfile", "ORC", "parquet"),
        "Default file format for CREATE TABLE statement. Users can explicitly override it by CREATE TABLE ... STORED AS [FORMAT]"),
    HIVE_DEFAULT_MANAGED_FILEFORMAT("hive.default.fileformat.managed", "none",
        new StringSet("none", "TextFile", "SequenceFile", "RCfile", "ORC", "parquet"),
        "Default file format for CREATE TABLE statement applied to managed tables only. External tables will be \n" +
        "created with format specified by hive.default.fileformat. Leaving this null will result in using hive.default.fileformat \n" +
        "for all tables."),
    HIVE_DEFAULT_STORAGE_HANDLER("hive.default.storage.handler.class", "",
        "Default storage handler class for CREATE TABLE statements. If this is set to a valid class, a 'CREATE TABLE ... STORED AS ... LOCATION ...' command will " +
        "be equivalent to 'CREATE TABLE ... STORED BY [default.storage.handler.class] LOCATION ...'. Any STORED AS clauses will be ignored, given that STORED BY and STORED AS are " +
        "incompatible within the same command. Users can explicitly override the default class by issuing 'CREATE TABLE ... STORED BY [overriding.storage.handler.class] ...'"),
    HIVE_QUERY_RESULT_FILEFORMAT("hive.query.result.fileformat", ResultFileFormat.SEQUENCEFILE.toString(),
        new StringSet(ResultFileFormat.getValidSet()),
        "Default file format for storing result of the query."),
    HIVE_CHECK_FILEFORMAT("hive.fileformat.check", true, "Whether to check file format or not when loading data files"),

    // default serde for rcfile
    HIVE_DEFAULT_RCFILE_SERDE("hive.default.rcfile.serde",
        "org.apache.hadoop.hive.serde2.columnar.LazyBinaryColumnarSerDe",
        "The default SerDe Hive will use for the RCFile format"),

    HIVE_DEFAULT_SERDE("hive.default.serde",
        "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe",
        "The default SerDe Hive will use for storage formats that do not specify a SerDe."),

    /**
     * @deprecated Use MetastoreConf.SERDES_USING_METASTORE_FOR_SCHEMA
     */
    @Deprecated
    SERDES_USING_METASTORE_FOR_SCHEMA("hive.serdes.using.metastore.for.schema",
        "org.apache.hadoop.hive.ql.io.orc.OrcSerde," +
        "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe," +
        "org.apache.hadoop.hive.serde2.columnar.ColumnarSerDe," +
        "org.apache.hadoop.hive.serde2.MetadataTypedColumnsetSerDe," +
        "org.apache.hadoop.hive.serde2.columnar.LazyBinaryColumnarSerDe," +
        "org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe," +
        "org.apache.hadoop.hive.serde2.lazybinary.LazyBinarySerDe," +
        "org.apache.hadoop.hive.serde2.OpenCSVSerde",
        "SerDes retrieving schema from metastore. This is an internal parameter."),

    @Deprecated
    HIVE_LEGACY_SCHEMA_FOR_ALL_SERDES("hive.legacy.schema.for.all.serdes",
        false,
        "A backward compatibility setting for external metastore users that do not handle \n" +
        SERDES_USING_METASTORE_FOR_SCHEMA.varname + " correctly. This may be removed at any time."),

    HIVE_HISTORY_FILE_LOC("hive.querylog.location",
        "${system:java.io.tmpdir}" + File.separator + "${system:user.name}",
        "Location of Hive run time structured log file"),

    HIVE_LOG_INCREMENTAL_PLAN_PROGRESS("hive.querylog.enable.plan.progress", true,
        "Whether to log the plan's progress every time a job's progress is checked.\n" +
        "These logs are written to the location specified by hive.querylog.location"),

    HIVE_LOG_INCREMENTAL_PLAN_PROGRESS_INTERVAL("hive.querylog.plan.progress.interval", "60000ms",
        new TimeValidator(TimeUnit.MILLISECONDS),
        "The interval to wait between logging the plan's progress.\n" +
        "If there is a whole number percentage change in the progress of the mappers or the reducers,\n" +
        "the progress is logged regardless of this value.\n" +
        "The actual interval will be the ceiling of (this value divided by the value of\n" +
        "hive.exec.counters.pull.interval) multiplied by the value of hive.exec.counters.pull.interval\n" +
        "I.e. if it is not divide evenly by the value of hive.exec.counters.pull.interval it will be\n" +
        "logged less frequently than specified.\n" +
        "This only has an effect if hive.querylog.enable.plan.progress is set to true."),

    HIVE_SCRIPT_SERDE("hive.script.serde", "org.apache.hadoop.hive.serde2.lazy.LazySimpleSerDe",
        "The default SerDe for transmitting input data to and reading output data from the user scripts. "),
    HIVE_SCRIPT_RECORD_READER("hive.script.recordreader",
        "org.apache.hadoop.hive.ql.exec.TextRecordReader",
        "The default record reader for reading data from the user scripts. "),
    HIVE_SCRIPT_RECORD_WRITER("hive.script.recordwriter",
        "org.apache.hadoop.hive.ql.exec.TextRecordWriter",
        "The default record writer for writing data to the user scripts. "),
    HIVE_SCRIPT_ESCAPE("hive.transform.escape.input", false,
        "This adds an option to escape special chars (newlines, carriage returns and\n" +
        "tabs) when they are passed to the user script. This is useful if the Hive tables\n" +
        "can contain data that contains special characters."),
    HIVE_BINARY_RECORD_MAX("hive.binary.record.max.length", 1000,
        "Read from a binary stream and treat each hive.binary.record.max.length bytes as a record. \n" +
        "The last record before the end of stream can have less than hive.binary.record.max.length bytes"),

    HIVE_HADOOP_MAX_MEM("hive.mapred.local.mem", 0, "mapper/reducer memory in local mode"),

    //small table file size
    HIVE_SMALL_TABLES_FILESIZE("hive.mapjoin.smalltable.filesize", 25000000L,
        "The threshold for the input file size of the small tables; if the file size is smaller \n" +
        "than this threshold, it will try to convert the common join into map join"),


    HIVE_SCHEMA_EVOLUTION("hive.exec.schema.evolution", true,
        "Use schema evolution to convert self-describing file format's data to the schema desired by the reader."),

    HIVE_ORC_FORCE_POSITIONAL_SCHEMA_EVOLUTION("orc.force.positional.evolution", true,
      "Whether to use column position based schema evolution or not (as opposed to column name based evolution)"),

    /** Don't use this directly - use AcidUtils! */
    HIVE_TRANSACTIONAL_TABLE_SCAN("hive.transactional.table.scan", false,
        "internal usage only -- do transaction (ACID or insert-only) table scan.", true),

    HIVE_TRANSACTIONAL_NUM_EVENTS_IN_MEMORY("hive.transactional.events.mem", 10000000,
        "Vectorized ACID readers can often load all the delete events from all the delete deltas\n"
        + "into memory to optimize for performance. To prevent out-of-memory errors, this is a rough heuristic\n"
        + "that limits the total number of delete events that can be loaded into memory at once.\n"
        + "Roughly it has been set to 10 million delete events per bucket (~160 MB).\n"),
    FILTER_DELETE_EVENTS("hive.txn.filter.delete.events", true,
        "If true, VectorizedOrcAcidRowBatchReader will compute min/max " +
            "ROW__ID for the split and only load delete events in that range.\n"
    ),
    HIVE_SAMPLE_RANDOM_NUM("hive.sample.seednumber", 0,
        "A number used to percentage sampling. By changing this number, user will change the subsets of data sampled."),

    // test mode in hive mode
    HIVE_TEST_MODE("hive.test.mode", false,
        "Whether Hive is running in test mode. If yes, it turns on sampling and prefixes the output tablename.",
        false),
    HIVE_EXIM_TEST_MODE("hive.exim.test.mode", false,
        "The subset of test mode that only enables custom path handling for ExIm.", false),
    HIVE_TEST_MODE_PREFIX("hive.test.mode.prefix", "test_",
        "In test mode, specifies prefixes for the output table", false),
    HIVE_TEST_MODE_SAMPLE_FREQ("hive.test.mode.samplefreq", 32,
        "In test mode, specifies sampling frequency for table, which is not bucketed,\n" +
        "For example, the following query:\n" +
        "  INSERT OVERWRITE TABLE dest SELECT col1 from src\n" +
        "would be converted to\n" +
        "  INSERT OVERWRITE TABLE test_dest\n" +
        "  SELECT col1 from src TABLESAMPLE (BUCKET 1 out of 32 on rand(1))", false),
    HIVE_TEST_MODE_NOSAMPLE("hive.test.mode.nosamplelist", "",
        "In test mode, specifies comma separated table names which would not apply sampling", false),
    HIVE_TEST_MODE_DUMMY_STAT_AGGR("hive.test.dummystats.aggregator", "", "internal variable for test", false),
    HIVE_TEST_MODE_DUMMY_STAT_PUB("hive.test.dummystats.publisher", "", "internal variable for test", false),
    HIVE_TEST_CURRENT_TIMESTAMP("hive.test.currenttimestamp", null, "current timestamp for test", false),
    HIVE_TEST_MODE_ROLLBACK_TXN("hive.test.rollbacktxn", false, "For testing only.  Will mark every ACID transaction aborted", false),
    HIVE_TEST_MODE_FAIL_COMPACTION("hive.test.fail.compaction", false, "For testing only.  Will cause CompactorMR to fail.", false),
    HIVE_TEST_MODE_FAIL_LOAD_DYNAMIC_PARTITION("hive.test.fail.load.dynamic.partition", false, "For testing only.  Will cause loadDynamicPartition to fail.", false),
    HIVE_TEST_MODE_FAIL_HEARTBEATER("hive.test.fail.heartbeater", false, "For testing only.  Will cause Heartbeater to fail.", false),
    TESTMODE_BUCKET_CODEC_VERSION("hive.test.bucketcodec.version", 1,
      "For testing only.  Will make ACID subsystem write RecordIdentifier.bucketId in specified\n" +
        "format", false),
    HIVE_EXTEND_BUCKET_ID_RANGE("hive.extend.bucketid.range", true,
            "Dynamically allocate some bits from statement id when bucket id overflows. This allows having more than 4096 buckets."),
    HIVE_TEST_MODE_ACID_KEY_IDX_SKIP("hive.test.acid.key.index.skip", false, "For testing only. OrcRecordUpdater will skip "
        + "generation of the hive.acid.key.index", false),
    HIVE_MERGE_MAPFILES("hive.merge.mapfiles", true,
        "Merge small files at the end of a map-only job"),
    HIVE_MERGE_MAPRED_FILES("hive.merge.mapredfiles", false,
        "Merge small files at the end of a map-reduce job"),
    HIVE_MERGE_TEZ_FILES("hive.merge.tezfiles", false, "Merge small files at the end of a Tez DAG"),
    HIVE_MERGE_MAP_FILES_SIZE("hive.merge.size.per.task", (long) (256 * 1000 * 1000),
        "Size of merged files at the end of the job"),
    HIVE_MERGE_MAP_FILES_AVG_SIZE("hive.merge.smallfiles.avgsize", (long) (16 * 1000 * 1000),
        "When the average output file size of a job is less than this number, Hive will start an additional \n" +
        "map-reduce job to merge the output files into bigger files. This is only done for map-only jobs \n" +
        "if hive.merge.mapfiles is true, and for map-reduce jobs if hive.merge.mapredfiles is true."),
    HIVE_MERGE_RCFILE_BLOCK_LEVEL("hive.merge.rcfile.block.level", true, ""),
    HIVE_MERGE_ORC_FILE_STRIPE_LEVEL("hive.merge.orcfile.stripe.level", true,
        "When hive.merge.mapfiles, hive.merge.mapredfiles or hive.merge.tezfiles is enabled\n" +
        "while writing a table with ORC file format, enabling this config will do stripe-level\n" +
        "fast merge for small ORC files. Note that enabling this config will not honor the\n" +
        "padding tolerance config (hive.exec.orc.block.padding.tolerance)."),
    HIVE_ORC_CODEC_POOL("hive.use.orc.codec.pool", false,
        "Whether to use codec pool in ORC. Disable if there are bugs with codec reuse."),
    HIVE_ICEBERG_STATS_SOURCE("hive.iceberg.stats.source", "iceberg",
        "Use stats from iceberg table snapshot for query planning. This has two values metastore and iceberg"),
    HIVE_ICEBERG_EXPIRE_SNAPSHOT_NUMTHREADS("hive.iceberg.expire.snapshot.numthreads", 4,
        "The number of threads to be used for deleting files during expire snapshot. If set to 0 or below it uses the" +
            " default DirectExecutorService"),

    HIVE_ICEBERG_MASK_DEFAULT_LOCATION("hive.iceberg.mask.default.location", false,
        "If this is set to true the URI for auth will have the default location masked with DEFAULT_TABLE_LOCATION"),
    HIVE_ICEBERG_ALLOW_DATAFILES_IN_TABLE_LOCATION_ONLY("hive.iceberg.allow.datafiles.in.table.location.only", false,
        "If this is set to true, then all the data files being read should be withing the table location"),
    HIVE_USE_EXPLICIT_RCFILE_HEADER("hive.exec.rcfile.use.explicit.header", true,
        "If this is set the header for RCFiles will simply be RCF.  If this is not\n" +
        "set the header will be that borrowed from sequence files, e.g. SEQ- followed\n" +
        "by the input and output RCFile formats."),
    HIVE_USE_RCFILE_SYNC_CACHE("hive.exec.rcfile.use.sync.cache", true, ""),

    HIVE_RCFILE_RECORD_INTERVAL("hive.io.rcfile.record.interval", Integer.MAX_VALUE, ""),
    HIVE_RCFILE_COLUMN_NUMBER_CONF("hive.io.rcfile.column.number.conf", 0, ""),
    HIVE_RCFILE_TOLERATE_CORRUPTIONS("hive.io.rcfile.tolerate.corruptions", false, ""),
    HIVE_RCFILE_RECORD_BUFFER_SIZE("hive.io.rcfile.record.buffer.size", 4194304, ""),   // 4M

    PARQUET_MEMORY_POOL_RATIO("parquet.memory.pool.ratio", 0.5f,
        "Maximum fraction of heap that can be used by Parquet file writers in one task.\n" +
        "It is for avoiding OutOfMemory error in tasks. Work with Parquet 1.6.0 and above.\n" +
        "This config parameter is defined in Parquet, so that it does not start with 'hive.'."),
    HIVE_PARQUET_TIMESTAMP_SKIP_CONVERSION("hive.parquet.timestamp.skip.conversion", true,
      "Current Hive implementation of parquet stores timestamps to UTC, this flag allows skipping of the conversion" +
      "on reading parquet files from other tools"),
    HIVE_PARQUET_DATE_PROLEPTIC_GREGORIAN("hive.parquet.date.proleptic.gregorian", false,
        "Should we write date using the proleptic Gregorian calendar instead of the hybrid Julian Gregorian?\n" +
        "Hybrid is the default."),
    HIVE_PARQUET_DATE_PROLEPTIC_GREGORIAN_DEFAULT("hive.parquet.date.proleptic.gregorian.default", false,
      "This value controls whether date type in Parquet files was written using the hybrid or proleptic\n" +
      "calendar. Hybrid is the default."),
    HIVE_PARQUET_TIMESTAMP_LEGACY_CONVERSION_ENABLED("hive.parquet.timestamp.legacy.conversion.enabled", true,
    "Whether to use former Java date/time APIs to convert between timezones when reading timestamps from " +
        "Parquet files. The property has no effect when the file contains explicit metadata about the conversion " +
        "used to write the data; in this case reading conversion is chosen based on the metadata."),
    HIVE_PARQUET_TIMESTAMP_WRITE_LEGACY_CONVERSION_ENABLED("hive.parquet.timestamp.write.legacy.conversion.enabled", false,
        "Whether to use former Java date/time APIs to convert between timezones when writing timestamps in " +
        "Parquet files. Once data are written to the file the effect is permanent (also reflected in the metadata)." +
        "Changing the value of this property affects only new data written to the file."),
    HIVE_PARQUET_INFER_BINARY_AS("hive.parquet.infer.binary.as", "binary", new StringSet("binary", "string"),
        "This setting controls what the parquet binary type gets inferred as by CREATE TABLE LIKE FILE. This is helpful " +
        "since some systems specify the parquet schema for strings as binary."),
    HIVE_AVRO_TIMESTAMP_SKIP_CONVERSION("hive.avro.timestamp.skip.conversion", false,
        "Some older Hive implementations (pre-3.1) wrote Avro timestamps in a UTC-normalized" +
        "manner, while from version 3.1 until now Hive wrote time zone agnostic timestamps. " +
        "Setting this flag to true will treat legacy timestamps as time zone agnostic. Setting " +
        "it to false will treat legacy timestamps as UTC-normalized. This flag will not affect " +
        "timestamps written after this change."),
    HIVE_AVRO_PROLEPTIC_GREGORIAN("hive.avro.proleptic.gregorian", false,
        "Should we write date and timestamp using the proleptic Gregorian calendar instead of the hybrid Julian Gregorian?\n" +
        "Hybrid is the default."),
    HIVE_AVRO_PROLEPTIC_GREGORIAN_DEFAULT("hive.avro.proleptic.gregorian.default", false,
        "This value controls whether date and timestamp type in Avro files was written using the hybrid or proleptic\n" +
        "calendar. Hybrid is the default."),
    HIVE_AVRO_TIMESTAMP_LEGACY_CONVERSION_ENABLED("hive.avro.timestamp.legacy.conversion.enabled", true,
        "Whether to use former Java date/time APIs to convert between timezones when reading timestamps from " +
        "Avro files. The property has no effect when the file contains explicit metadata about the conversion " + 
        "used to write the data; in this case reading conversion is based on the metadata."),
    HIVE_AVRO_TIMESTAMP_WRITE_LEGACY_CONVERSION_ENABLED("hive.avro.timestamp.write.legacy.conversion.enabled", false,
        "Whether to use former Java date/time APIs to convert between timezones when writing timestamps in " +
        "Avro files. Once data are written to the file the effect is permanent (also reflected in the metadata)." +
        "Changing the value of this property affects only new data written to the file."),
    HIVE_INT_TIMESTAMP_CONVERSION_IN_SECONDS("hive.int.timestamp.conversion.in.seconds", false,
        "Boolean/tinyint/smallint/int/bigint value is interpreted as milliseconds during the timestamp conversion.\n" +
        "Set this flag to true to interpret the value as seconds to be consistent with float/double." ),
    HIVE_PARQUET_WRITE_INT64_TIMESTAMP("hive.parquet.write.int64.timestamp", false,
        "Write parquet timestamps as int64/LogicalTypes instead of int96/OriginalTypes. Note:" +
        "Timestamps will be time zone agnostic (NEVER converted to a different time zone)."),
    HIVE_PARQUET_TIMESTAMP_TIME_UNIT("hive.parquet.timestamp.time.unit", "micros",
        new StringSet("nanos", "micros", "millis"),
        "Store parquet int64/LogicalTypes timestamps in this time unit."),

    HIVE_ORC_BASE_DELTA_RATIO("hive.exec.orc.base.delta.ratio", 8, "The ratio of base writer and\n" +
        "delta writer in terms of STRIPE_SIZE and BUFFER_SIZE."),
    HIVE_ORC_DELTA_STREAMING_OPTIMIZATIONS_ENABLED("hive.exec.orc.delta.streaming.optimizations.enabled", false,
      "Whether to enable streaming optimizations for ORC delta files. This will disable ORC's internal indexes,\n" +
        "disable compression, enable fast encoding and disable dictionary encoding."),
    HIVE_ORC_SPLIT_STRATEGY("hive.exec.orc.split.strategy", "HYBRID", new StringSet("HYBRID", "BI", "ETL"),
        "This is not a user level config. BI strategy is used when the requirement is to spend less time in split generation" +
        " as opposed to query execution (split generation does not read or cache file footers)." +
        " ETL strategy is used when spending little more time in split generation is acceptable" +
        " (split generation reads and caches file footers). HYBRID chooses between the above strategies" +
        " based on heuristics."),
    HIVE_ORC_BLOB_STORAGE_SPLIT_SIZE("hive.exec.orc.blob.storage.split.size", 128L * 1024 * 1024,
      "When blob storage is used, BI split strategy does not have block locations for splitting orc files.\n" +
        "In such cases, split generation will use this config to split orc file"),
    HIVE_ORC_WRITER_LLAP_MEMORY_MANAGER_ENABLED("hive.exec.orc.writer.llap.memory.manager.enabled", true,
      "Whether orc writers should use llap-aware memory manager. LLAP aware memory manager will use memory\n" +
        "per executor instead of entire heap memory when concurrent orc writers are involved. This will let\n" +
        "task fragments to use memory within its limit (memory per executor) when performing ETL in LLAP."),

    // hive streaming ingest settings
    HIVE_STREAMING_AUTO_FLUSH_ENABLED("hive.streaming.auto.flush.enabled", true, "Whether to enable memory \n" +
      "monitoring and automatic flushing of open record updaters during streaming ingest. This is an expert level \n" +
      "setting and disabling this may have severe performance impact under memory pressure."),
    HIVE_HEAP_MEMORY_MONITOR_USAGE_THRESHOLD("hive.heap.memory.monitor.usage.threshold", 0.7f,
      "Hive streaming does automatic memory management across all open record writers. This threshold will let the \n" +
        "memory monitor take an action (flush open files) when heap memory usage exceeded this threshold."),
    HIVE_STREAMING_AUTO_FLUSH_CHECK_INTERVAL_SIZE("hive.streaming.auto.flush.check.interval.size", "100Mb",
      new SizeValidator(),
      "Hive streaming ingest has auto flush mechanism to flush all open record updaters under memory pressure.\n" +
        "When memory usage exceed hive.heap.memory.monitor.default.usage.threshold, the auto-flush mechanism will \n" +
        "wait until this size (default 100Mb) of records are ingested before triggering flush."),
    HIVE_CLASSLOADER_SHADE_PREFIX("hive.classloader.shade.prefix", "", "During reflective instantiation of a class\n" +
      "(input, output formats, serde etc.), when classloader throws ClassNotFoundException, as a fallback this\n" +
      "shade prefix will be used before class reference and retried."),

    HIVE_ORC_MS_FOOTER_CACHE_ENABLED("hive.orc.splits.ms.footer.cache.enabled", false,
        "Whether to enable using file metadata cache in metastore for ORC file footers."),
    HIVE_ORC_MS_FOOTER_CACHE_PPD("hive.orc.splits.ms.footer.cache.ppd.enabled", true,
        "Whether to enable file footer cache PPD (hive.orc.splits.ms.footer.cache.enabled\n" +
        "must also be set to true for this to work)."),

    HIVE_ORC_INCLUDE_FILE_FOOTER_IN_SPLITS("hive.orc.splits.include.file.footer", false,
        "If turned on splits generated by orc will include metadata about the stripes in the file. This\n" +
        "data is read remotely (from the client or HS2 machine) and sent to all the tasks."),
    HIVE_ORC_SPLIT_DIRECTORY_BATCH_MS("hive.orc.splits.directory.batch.ms", 0,
        "How long, in ms, to wait to batch input directories for processing during ORC split\n" +
        "generation. 0 means process directories individually. This can increase the number of\n" +
        "metastore calls if metastore metadata cache is used."),
    HIVE_ORC_INCLUDE_FILE_ID_IN_SPLITS("hive.orc.splits.include.fileid", true,
        "Include file ID in splits on file systems that support it."),
    HIVE_ORC_ALLOW_SYNTHETIC_FILE_ID_IN_SPLITS("hive.orc.splits.allow.synthetic.fileid", true,
        "Allow synthetic file ID in splits on file systems that don't have a native one."),
    HIVE_ORC_CACHE_STRIPE_DETAILS_MEMORY_SIZE("hive.orc.cache.stripe.details.mem.size", "256Mb",
        new SizeValidator(), "Maximum size of orc splits cached in the client."),
    /**
     * @deprecated Use HiveConf.HIVE_COMPUTE_SPLITS_NUM_THREADS
     */
    @Deprecated
    HIVE_ORC_COMPUTE_SPLITS_NUM_THREADS("hive.orc.compute.splits.num.threads", 10,
        "How many threads orc should use to create splits in parallel."),
    HIVE_ORC_CACHE_USE_SOFT_REFERENCES("hive.orc.cache.use.soft.references", false,
        "By default, the cache that ORC input format uses to store orc file footer use hard\n" +
        "references for the cached object. Setting this to true can help avoid out of memory\n" +
        "issues under memory pressure (in some cases) at the cost of slight unpredictability in\n" +
        "overall query performance."),
    HIVE_IO_SARG_CACHE_MAX_WEIGHT_MB("hive.io.sarg.cache.max.weight.mb", 10,
        "The max weight allowed for the SearchArgument Cache. By default, the cache allows a max-weight of 10MB, " +
        "after which entries will be evicted."),

    HIVE_LAZYSIMPLE_EXTENDED_BOOLEAN_LITERAL("hive.lazysimple.extended_boolean_literal", false,
        "LazySimpleSerde uses this property to determine if it treats 'T', 't', 'F', 'f',\n" +
        "'1', and '0' as extended, legal boolean literal, in addition to 'TRUE' and 'FALSE'.\n" +
        "The default is false, which means only 'TRUE' and 'FALSE' are treated as legal\n" +
        "boolean literal."),

    HIVE_SKEW_JOIN("hive.optimize.skewjoin", false,
        "Whether to enable skew join optimization. \n" +
        "The algorithm is as follows: At runtime, detect the keys with a large skew. Instead of\n" +
        "processing those keys, store them temporarily in an HDFS directory. In a follow-up map-reduce\n" +
        "job, process those skewed keys. The same key need not be skewed for all the tables, and so,\n" +
        "the follow-up map-reduce job (for the skewed keys) would be much faster, since it would be a\n" +
        "map-join."),
    HIVE_DYNAMIC_PARTITION_HASHJOIN("hive.optimize.dynamic.partition.hashjoin", false,
        "Whether to enable dynamically partitioned hash join optimization. \n" +
        "This setting is also dependent on enabling hive.auto.convert.join"),
    HIVE_CONVERT_JOIN("hive.auto.convert.join", true,
        "Whether Hive enables the optimization about converting common join into mapjoin based on the input file size"),
    HIVE_CONVERT_JOIN_NOCONDITIONALTASK("hive.auto.convert.join.noconditionaltask", true,
        "Whether Hive enables the optimization about converting common join into mapjoin based on the input file size. \n" +
        "If this parameter is on, and the sum of size for n-1 of the tables/partitions for a n-way join is smaller than the\n" +
        "specified size, the join is directly converted to a mapjoin (there is no conditional task)."),
    HIVE_CONVERT_ANTI_JOIN("hive.auto.convert.anti.join", true,
        "Whether Hive enables the optimization about converting join with null filter to anti join."),
    HIVE_CONVERT_JOIN_NOCONDITIONAL_TASK_THRESHOLD("hive.auto.convert.join.noconditionaltask.size",
        10000000L,
        "If hive.auto.convert.join.noconditionaltask is off, this parameter does not take affect. \n" +
        "However, if it is on, and the sum of size for n-1 of the tables/partitions for a n-way join is smaller than this size, \n" +
        "the join is directly converted to a mapjoin(there is no conditional task). The default is 10MB"),
    HIVE_CONVERT_JOIN_USE_NONSTAGED("hive.auto.convert.join.use.nonstaged", false,
        "For conditional joins, if input stream from a small alias can be directly applied to join operator without \n" +
        "filtering or projection, the alias need not to be pre-staged in distributed cache via mapred local task.\n" +
        "Currently, this is not working with vectorization or tez execution engine."),
    HIVE_SKEWJOIN_KEY("hive.skewjoin.key", 100000,
        "Determine if we get a skew key in join. If we see more than the specified number of rows with the same key in join operator,\n" +
        "we think the key as a skew join key. "),
    HIVE_SKEWJOIN_MAPJOIN_NUM_MAP_TASK("hive.skewjoin.mapjoin.map.tasks", 10000,
        "Determine the number of map task used in the follow up map join job for a skew join.\n" +
        "It should be used together with hive.skewjoin.mapjoin.min.split to perform a fine-grained control."),
    HIVE_SKEWJOIN_MAPJOIN_MIN_SPLIT("hive.skewjoin.mapjoin.min.split", 33554432L,
        "Determine the number of map task at most used in the follow up map join job for a skew join by specifying \n" +
        "the minimum split size. It should be used together with hive.skewjoin.mapjoin.map.tasks to perform a fine-grained control."),

    HIVE_SEND_HEARTBEAT("hive.heartbeat.interval", 1000,
        "Send a heartbeat after this interval - used by mapjoin and filter operators"),
    HIVE_LIMIT_MAX_ROW_SIZE("hive.limit.row.max.size", 100000L,
        "When trying a smaller subset of data for simple LIMIT, how much size we need to guarantee each row to have at least."),
    HIVE_LIMIT_OPT_LIMIT_FILE("hive.limit.optimize.limit.file", 10,
        "When trying a smaller subset of data for simple LIMIT, maximum number of fil:wq:es we can sample."),
    HIVE_LIMIT_OPT_ENABLE("hive.limit.optimize.enable", false,
        "Whether to enable to optimization to trying a smaller subset of data for simple LIMIT first."),
    HIVE_LIMIT_OPT_MAX_FETCH("hive.limit.optimize.fetch.max", 50000,
        "Maximum number of rows allowed for a smaller subset of data for simple LIMIT, if it is a fetch query. \n" +
        "Insert queries are not restricted by this limit."),
    HIVE_LIMIT_PUSHDOWN_MEMORY_USAGE("hive.limit.pushdown.memory.usage", 0.1f, new RatioValidator(),
        "The fraction of available memory to be used for buffering rows in Reducesink operator for limit pushdown optimization."),

    HIVE_CONVERT_JOIN_MAX_ENTRIES_HASHTABLE("hive.auto.convert.join.hashtable.max.entries", 21000000L,
        "If hive.auto.convert.join.noconditionaltask is off, this parameter does not take affect. \n" +
        "However, if it is on, and the predicted number of entries in hashtable for a given join \n" +
        "input is larger than this number, the join will not be converted to a mapjoin. \n" +
        "The value \"-1\" means no limit."),
    XPROD_SMALL_TABLE_ROWS_THRESHOLD("hive.xprod.mapjoin.small.table.rows", 1,"Maximum number of rows on build side"
        + " of map join before it switches over to cross product edge"),
    HIVE_CONVERT_JOIN_MAX_SHUFFLE_SIZE("hive.auto.convert.join.shuffle.max.size", 10000000000L,
       "If hive.auto.convert.join.noconditionaltask is off, this parameter does not take affect. \n" +
       "However, if it is on, and the predicted size of the larger input for a given join is greater \n" +
       "than this number, the join will not be converted to a dynamically partitioned hash join. \n" +
       "The value \"-1\" means no limit."),
    HIVE_HASHTABLE_KEY_COUNT_ADJUSTMENT("hive.hashtable.key.count.adjustment", 0.99f,
        "Adjustment to mapjoin hashtable size derived from table and column statistics; the estimate" +
        " of the number of keys is divided by this value. If the value is 0, statistics are not used" +
        "and hive.hashtable.initialCapacity is used instead."),
    HIVE_HASHTABLE_THRESHOLD("hive.hashtable.initialCapacity", 100000, "Initial capacity of " +
        "mapjoin hashtable if statistics are absent, or if hive.hashtable.key.count.adjustment is set to 0"),
    HIVE_HASHTABLE_LOAD_FACTOR("hive.hashtable.loadfactor", (float) 0.75, ""),
    HIVE_HASHTABLE_FOLLOWBY_GBY_MAX_MEMORY_USAGE("hive.mapjoin.followby.gby.localtask.max.memory.usage", (float) 0.55,
        "This number means how much memory the local task can take to hold the key/value into an in-memory hash table \n" +
        "when this map join is followed by a group by. If the local task's memory usage is more than this number, \n" +
        "the local task will abort by itself. It means the data of the small table is too large " +
        "to be held in memory."),
    HIVE_HASHTABLE_MAX_MEMORY_USAGE("hive.mapjoin.localtask.max.memory.usage", (float) 0.90,
        "This number means how much memory the local task can take to hold the key/value into an in-memory hash table. \n" +
        "If the local task's memory usage is more than this number, the local task will abort by itself. \n" +
        "It means the data of the small table is too large to be held in memory."),
    HIVE_HASHTABLE_SCALE("hive.mapjoin.check.memory.rows", (long)100000,
        "The number means after how many rows processed it needs to check the memory usage"),

    HIVE_INPUT_FORMAT("hive.input.format", "org.apache.hadoop.hive.ql.io.CombineHiveInputFormat",
        "The default input format. Set this to HiveInputFormat if you encounter problems with CombineHiveInputFormat."),
    HIVE_TEZ_INPUT_FORMAT("hive.tez.input.format", "org.apache.hadoop.hive.ql.io.HiveInputFormat",
        "The default input format for tez. Tez groups splits in the AM."),

    HIVE_TEZ_CONTAINER_SIZE("hive.tez.container.size", -1,
        "The memory in MB that's used by a Tez task container (TezChild) in Tez container mode. Hive uses this \n"
        + "property to create a Resource object which is accepted by Yarn (and used in TezAM to ask for TezChild \n"
        + "containers). This should be distinguished from the Tez AM's (DAGAppMaster) memory, \n"
        + "which is driven by tez.am.resource.memory.mb! \n"
        + "Also, as Hive takes care of TezChild memory by setting this option, there is no need \n "
        + "to set tez.task.resource.memory.mb differently. \n"
        + "The final -Xmx arg for TezChild process is not equal to this setting, \n "
        + "because Tez considers a heap fraction (80%), so by default: \n"
        + "Xmx = hive.tez.container.size * tez.container.max.java.heap.fraction. \n"
        + "In case of values <= 0, container size falls back to mapreduce.map.memory.mb. \n"
        + "LLAP notes: while generating splits, the needed per-task resource is derived from this option \n"
        + "(refer to HiveSplitGenerator, TezAvailableSlotsCalculator), so even if its value doesn't change the \n"
        + "LLAP daemons' total physical size, it has to be configured properly. In this context \n"
        + "4096 implies that you assume a single task will consume 4096MB from a daemon's shared heap."),
    HIVE_TEZ_CPU_VCORES("hive.tez.cpu.vcores", -1,
        "By default Tez will ask for however many cpus map-reduce is configured to use per container.\n" +
        "This can be used to overwrite."),
    HIVE_TEZ_JAVA_OPTS("hive.tez.java.opts", null,
        "By default Tez will use the Java options from map tasks. This can be used to overwrite."),
    HIVE_TEZ_LOG_LEVEL("hive.tez.log.level", "INFO",
        "The log level to use for tasks executing as part of the DAG.\n" +
        "Used only if hive.tez.java.opts is used to configure Java options."),
    HIVE_TEZ_HS2_USER_ACCESS("hive.tez.hs2.user.access", true,
        "Whether to grant access to the hs2/hive user for queries"),
    HIVE_QUERY_NAME("hive.query.name", null,
        "This named is used by Tez to set the dag name. This name in turn will appear on \n" +
        "the Tez UI representing the work that was done."),
    HIVE_TEZ_JOB_NAME("tez.job.name", "HIVE-%s",
        "This named is used by Tez to set the job name. This name in turn will appear on \n" +
        "the Yarn UI representing the Yarn Application Name. And The job name may be a \n" +
        "Java String.format() string, to which the session ID will be supplied as the single parameter."),

    SYSLOG_INPUT_FORMAT_FILE_PRUNING("hive.syslog.input.format.file.pruning", true,
      "Whether syslog input format should prune files based on timestamp (ts) column in sys.logs table."),
    SYSLOG_INPUT_FORMAT_FILE_TIME_SLICE("hive.syslog.input.format.file.time.slice", "300s",
      new TimeValidator(TimeUnit.SECONDS, 0L, false, Long.MAX_VALUE, false),
      "Files stored in sys.logs typically are chunked with time interval. For example: depending on the\n" +
        "logging library used this represents the flush interval/time slice. \n" +
        "If time slice/flust interval is set to 5 minutes, then the expectation is that the filename \n" +
        "2019-01-02-10-00_0.log represent time range from 10:00 to 10:05.\n" +
        "This time slice should align with the flush interval of the logging library else file pruning may\n" +
        "incorrectly prune files leading to incorrect results from sys.logs table."),

    HIVE_OPTIMIZE_BUCKETING_SORTING("hive.optimize.bucketingsorting", true,
        "Don't create a reducer for enforcing \n" +
        "bucketing/sorting for queries of the form: \n" +
        "insert overwrite table T2 select * from T1;\n" +
        "where T1 and T2 are bucketed/sorted by the same keys into the same number of buckets."),
    HIVE_PARTITIONER("hive.mapred.partitioner", "org.apache.hadoop.hive.ql.io.DefaultHivePartitioner", ""),
    HIVE_ENFORCE_SORT_MERGE_BUCKET_MAPJOIN("hive.enforce.sortmergebucketmapjoin", false,
        "If the user asked for sort-merge bucketed map-side join, and it cannot be performed, should the query fail or not ?"),
    HIVE_ENFORCE_BUCKET_MAPJOIN("hive.enforce.bucketmapjoin", false,
        "If the user asked for bucketed map-side join, and it cannot be performed, \n" +
        "should the query fail or not ? For example, if the buckets in the tables being joined are\n" +
        "not a multiple of each other, bucketed map-side join cannot be performed, and the\n" +
        "query will fail if hive.enforce.bucketmapjoin is set to true."),

    HIVE_SORT_WHEN_BUCKETING("hive.optimize.clustered.sort", true,
        "When this option is true, when a Hive table was created with a clustered by clause, we will also\n" +
        "sort by same value (if sort columns were not specified)"),

    HIVE_ENFORCE_NOT_NULL_CONSTRAINT("hive.constraint.notnull.enforce", true,
        "Should \"IS NOT NULL \" constraint be enforced?"),

    HIVE_AUTO_SORTMERGE_JOIN("hive.auto.convert.sortmerge.join", true,
        "Will the join be automatically converted to a sort-merge join, if the joined tables pass the criteria for sort-merge join."),
    HIVE_AUTO_SORTMERGE_JOIN_REDUCE("hive.auto.convert.sortmerge.join.reduce.side", true,
        "Whether hive.auto.convert.sortmerge.join (if enabled) should be applied to reduce side."),
    HIVE_AUTO_SORTMERGE_JOIN_BIGTABLE_SELECTOR(
        "hive.auto.convert.sortmerge.join.bigtable.selection.policy",
        "org.apache.hadoop.hive.ql.optimizer.AvgPartitionSizeBasedBigTableSelectorForAutoSMJ",
        "The policy to choose the big table for automatic conversion to sort-merge join. \n" +
        "By default, the table with the largest partitions is assigned the big table. All policies are:\n" +
        ". based on position of the table - the leftmost table is selected\n" +
        "org.apache.hadoop.hive.ql.optimizer.LeftmostBigTableSMJ.\n" +
        ". based on total size (all the partitions selected in the query) of the table \n" +
        "org.apache.hadoop.hive.ql.optimizer.TableSizeBasedBigTableSelectorForAutoSMJ.\n" +
        ". based on average size (all the partitions selected in the query) of the table \n" +
        "org.apache.hadoop.hive.ql.optimizer.AvgPartitionSizeBasedBigTableSelectorForAutoSMJ.\n" +
        "New policies can be added in future."),
    HIVE_AUTO_SORTMERGE_JOIN_TOMAPJOIN(
        "hive.auto.convert.sortmerge.join.to.mapjoin", false,
        "If hive.auto.convert.sortmerge.join is set to true, and a join was converted to a sort-merge join, \n" +
        "this parameter decides whether each table should be tried as a big table, and effectively a map-join should be\n" +
        "tried. That would create a conditional task with n+1 children for a n-way join (1 child for each table as the\n" +
        "big table), and the backup task will be the sort-merge join. In some cases, a map-join would be faster than a\n" +
        "sort-merge join, if there is no advantage of having the output bucketed and sorted. For example, if a very big sorted\n" +
        "and bucketed table with few files (say 10 files) are being joined with a very small sorter and bucketed table\n" +
        "with few files (10 files), the sort-merge join will only use 10 mappers, and a simple map-only join might be faster\n" +
        "if the complete small table can fit in memory, and a map-join can be performed."),

    HIVE_SCRIPT_OPERATOR_TRUST("hive.exec.script.trust", false, ""),
    // Optimizer
    HIVE_OPT_INDEX_FILTER("hive.optimize.index.filter", true, "Whether to enable automatic use of indexes"),

    HIVE_OPT_PPD("hive.optimize.ppd", true,
        "Whether to enable predicate pushdown"),
    HIVE_OPT_PPD_WINDOWING("hive.optimize.ppd.windowing", true,
        "Whether to enable predicate pushdown through windowing"),
    HIVE_PPD_RECOGNIZE_TRANSITIVITY("hive.ppd.recognizetransivity", true,
        "Whether to transitively replicate predicate filters over equijoin conditions."),
    HIVE_PPD_RECOGNIZE_COLUMN_EQUALITIES("hive.ppd.recognize.column.equalities", true,
        "Whether we should traverse the join branches to discover transitive propagation opportunities over" +
                " equijoin conditions. \n" +
                "Requires hive.ppd.recognizetransivity to be set to true."),
    HIVE_PPD_REMOVE_DUPLICATE_FILTERS("hive.ppd.remove.duplicatefilters", true,
        "During query optimization, filters may be pushed down in the operator tree. \n" +
        "If this config is true only pushed down filters remain in the operator tree, \n" +
        "and the original filter is removed. If this config is false, the original filter \n" +
        "is also left in the operator tree at the original place."),
    HIVE_POINT_LOOKUP_OPTIMIZER("hive.optimize.point.lookup", true,
         "Whether to transform OR clauses in Filter operators into IN clauses"),
    HIVE_POINT_LOOKUP_OPTIMIZER_MIN("hive.optimize.point.lookup.min", 2,
             "Minimum number of OR clauses needed to transform into IN clauses"),
    HIVEOPT_TRANSFORM_IN_MAXNODES("hive.optimize.transform.in.maxnodes", 16,
        "Maximum number of IN expressions beyond which IN will not be transformed into OR clause"),
    HIVE_COUNT_DISTINCT_OPTIMIZER("hive.optimize.countdistinct", true,
                 "Whether to transform count distinct into two stages"),
   HIVE_PARTITION_COLUMN_SEPARATOR("hive.optimize.partition.columns.separate", true,
            "Extract partition columns from IN clauses"),
    // Constant propagation optimizer
    HIVE_OPT_CONSTANT_PROPAGATION("hive.optimize.constant.propagation", true, "Whether to enable constant propagation optimizer"),
    HIVE_IDENTITY_PROJECT_REMOVER("hive.optimize.remove.identity.project", true, "Removes identity project from operator tree"),
    HIVE_METADATA_ONLY_QUERIES("hive.optimize.metadataonly", false,
        "Whether to eliminate scans of the tables from which no columns are selected. Note\n" +
        "that, when selecting from empty tables with data files, this can produce incorrect\n" +
        "results, so it's disabled by default. It works correctly for normal tables."),
    HIVE_NULL_SCAN_OPTIMIZE("hive.optimize.null.scan", true, "Don't scan relations which are guaranteed to not generate any rows"),
    HIVE_OPT_PPD_STORAGE("hive.optimize.ppd.storage", true,
        "Whether to push predicates down to storage handlers"),
    HIVE_OPT_GROUPBY("hive.optimize.groupby", true,
        "Whether to enable the bucketed group by from bucketed partitions/tables."),
    HIVE_OPT_BUCKET_MAPJOIN("hive.optimize.bucketmapjoin", false,
        "Whether to try bucket mapjoin"),
    HIVE_OPT_SORT_MERGE_BUCKET_MAPJOIN("hive.optimize.bucketmapjoin.sortedmerge", false,
        "Whether to try sorted bucket merge map join"),
    HIVE_OPT_REDUCE_DEDUPLICATION("hive.optimize.reducededuplication", true,
        "Remove extra map-reduce jobs if the data is already clustered by the same key which needs to be used again. \n" +
        "This should always be set to true. Since it is a new feature, it has been made configurable."),
    HIVE_OPT_REDUCE_DEDUPLICATION_MIN_REDUCER("hive.optimize.reducededuplication.min.reducer", 4,
        "Reduce deduplication merges two RSs by moving key/parts/reducer-num of the child RS to parent RS. \n" +
        "That means if reducer-num of the child RS is fixed (order by or forced bucketing) and small, it can make very slow, single MR.\n" +
        "The optimization will be automatically disabled if number of reducers would be less than specified value."),
    HIVE_OPT_JOIN_REDUCE_DEDUPLICATION("hive.optimize.joinreducededuplication", true,
        "Remove extra shuffle/sorting operations after join algorithm selection has been executed. \n" +
        "Currently it only works with Apache Tez. This should always be set to true. \n" +
        "Since it is a new feature, it has been made configurable."),
    HIVE_OPT_SORT_DYNAMIC_PARTITION_THRESHOLD("hive.optimize.sort.dynamic.partition.threshold", 0,
                                "When enabled dynamic partitioning column will be globally sorted.\n" +
                                    "This way we can keep only one record writer open for each partition value\n" +
                                    "in the reducer thereby reducing the memory pressure on reducers.\n" +
                                    "This config has following possible values: \n" +
                                    "\t-1 - This completely disables the optimization. \n" +
                                    "\t1 - This always enable the optimization. \n" +
                                    "\t0 - This makes the optimization a cost based decision. \n" +
                                    "Setting it to any other positive integer will make Hive use this as threshold for number of writers."),


    HIVE_SAMPLING_FOR_ORDERBY("hive.optimize.sampling.orderby", false, "Uses sampling on order-by clause for parallel execution."),
    HIVE_SAMPLING_NUMBER_FOR_ORDERBY("hive.optimize.sampling.orderby.number", 1000, "Total number of samples to be obtained."),
    HIVE_SAMPLING_PERCENT_FOR_ORDERBY("hive.optimize.sampling.orderby.percent", 0.1f, new RatioValidator(),
        "Probability with which a row will be chosen."),
    HIVE_REMOVE_ORDERBY_IN_SUBQUERY("hive.remove.orderby.in.subquery", true,
        "If set to true, order/sort by without limit in sub queries will be removed."),
    HIVE_OPTIMIZE_DISTINCT_REWRITE("hive.optimize.distinct.rewrite", true, "When applicable this "
        + "optimization rewrites distinct aggregates from a single stage to multi-stage "
        + "aggregation. This may not be optimal in all cases. Ideally, whether to trigger it or "
        + "not should be cost based decision. Until Hive formalizes cost model for this, this is config driven."),
    // whether to optimize union followed by select followed by filesink
    // It creates sub-directories in the final output, so should not be turned on in systems
    // where MAPREDUCE-1501 is not present
    HIVE_OPTIMIZE_UNION_REMOVE("hive.optimize.union.remove", false,
        "Whether to remove the union and push the operators between union and the filesink above union. \n" +
        "This avoids an extra scan of the output by union. This is independently useful for union\n" +
        "queries, and specially useful when hive.optimize.skewjoin.compiletime is set to true, since an\n" +
        "extra union is inserted.\n" +
        "\n" +
        "The merge is triggered if either of hive.merge.mapfiles or hive.merge.mapredfiles is set to true.\n" +
        "If the user has set hive.merge.mapfiles to true and hive.merge.mapredfiles to false, the idea was the\n" +
        "number of reducers are few, so the number of files anyway are small. However, with this optimization,\n" +
        "we are increasing the number of files possibly by a big margin. So, we merge aggressively."),
    HIVE_TEZ_UNION_FLATTEN_SUBDIRECTORIES("hive.tez.union.flatten.subdirectories", false,
        "By default, when writing data into a table and UNION ALL is the last step of the query, Hive on Tez will\n" +
        "create a subdirectory for each branch of the UNION ALL. When this property is enabled,\n" +
        "the subdirectories are removed, and the files are renamed and moved to the parent directory"),
    HIVE_OPTIMIZE_MERGE_ADJACENT_UNION_DISTINCT("hive.optimize.merge.adjacent.union.distinct", true,
        "Whether to merge adjacent binary UNION DISTINCT into a single n-ary UNION DISTINCT."),
    HIVE_OPT_CORRELATION("hive.optimize.correlation", false, "exploit intra-query correlations."),

    HIVE_OPTIMIZE_LIMIT_TRANSPOSE("hive.optimize.limittranspose", false,
        "Whether to push a limit through left/right outer join or union. If the value is true and the size of the outer\n" +
        "input is reduced enough (as specified in hive.optimize.limittranspose.reduction), the limit is pushed\n" +
        "to the outer input or union; to remain semantically correct, the limit is kept on top of the join or the union too."),
    HIVE_OPTIMIZE_LIMIT_TRANSPOSE_REDUCTION_PERCENTAGE("hive.optimize.limittranspose.reductionpercentage", 1.0f,
        "When hive.optimize.limittranspose is true, this variable specifies the minimal reduction of the\n" +
        "size of the outer input of the join or input of the union that we should get in order to apply the rule."),
    HIVE_OPTIMIZE_LIMIT_TRANSPOSE_REDUCTION_TUPLES("hive.optimize.limittranspose.reductiontuples", (long) 0,
        "When hive.optimize.limittranspose is true, this variable specifies the minimal reduction in the\n" +
        "number of tuples of the outer input of the join or the input of the union that you should get in order to apply the rule."),

    HIVE_OPTIMIZE_CONSTRAINTS_JOIN("hive.optimize.constraints.join", true, "Whether to use referential constraints\n" +
        "to optimize (remove or transform) join operators"),

    HIVE_OPTIMIZE_SORT_PREDS_WITH_STATS("hive.optimize.filter.preds.sort", true, "Whether to sort conditions in filters\n" +
        "based on estimated selectivity and compute cost"),

    HIVE_OPTIMIZE_REDUCE_WITH_STATS("hive.optimize.filter.stats.reduction", false, "Whether to simplify comparison\n" +
        "expressions in filter operators using column stats"),

    HIVE_OPTIMIZE_SKEWJOIN_COMPILETIME("hive.optimize.skewjoin.compiletime", false,
        "Whether to create a separate plan for skewed keys for the tables in the join.\n" +
        "This is based on the skewed keys stored in the metadata. At compile time, the plan is broken\n" +
        "into different joins: one for the skewed keys, and the other for the remaining keys. And then,\n" +
        "a union is performed for the 2 joins generated above. So unless the same skewed key is present\n" +
        "in both the joined tables, the join for the skewed key will be performed as a map-side join.\n" +
        "\n" +
        "The main difference between this parameter and hive.optimize.skewjoin is that this parameter\n" +
        "uses the skew information stored in the metastore to optimize the plan at compile time itself.\n" +
        "If there is no skew information in the metadata, this parameter will not have any affect.\n" +
        "Both hive.optimize.skewjoin.compiletime and hive.optimize.skewjoin should be set to true.\n" +
        "Ideally, hive.optimize.skewjoin should be renamed as hive.optimize.skewjoin.runtime, but not doing\n" +
        "so for backward compatibility.\n" +
        "\n" +
        "If the skew information is correctly stored in the metadata, hive.optimize.skewjoin.compiletime\n" +
        "would change the query plan to take care of it, and hive.optimize.skewjoin will be a no-op."),
    @Deprecated
    HIVE_OPTIMIZE_REPLACE_DELETE_WITH_TRUNCATE("hive.optimize.delete.all", true,
        "Optimize delete the entire data from table, use truncate instead"),
    HIVE_OPTIMIZE_METADATA_DELETE("hive.optimize.delete.metadata.only", true,
        "Optimize delete using filters provided by the query. " +
        "This uses the metadata of the table provided by table formats like Iceberg."),
    HIVE_OPTIMIZE_LIMIT("hive.optimize.limit", true,
        "Optimize limit by pushing through Left Outer Joins and Selects"),
    HIVE_OPTIMIZE_TOPNKEY("hive.optimize.topnkey", true, "Whether to enable top n key optimizer."),
    HIVE_MAX_TOPN_ALLOWED("hive.optimize.topnkey.max", 128, "Maximum topN value allowed by top n key optimizer.\n" +
        "If the LIMIT is greater than this value then top n key optimization won't be used."),
    HIVE_TOPN_EFFICIENCY_THRESHOLD("hive.optimize.topnkey.efficiency.threshold", 0.8f, "Disable topN key filter if the ratio between forwarded and total rows reaches this limit."),
    HIVE_TOPN_EFFICIENCY_CHECK_BATCHES("hive.optimize.topnkey.efficiency.check.nbatches", 10000, "Check topN key filter efficiency after a specific number of batches."),
    HIVE_TOPN_MAX_NUMBER_OF_PARTITIONS("hive.optimize.topnkey.partitions.max", 64, "Limit the maximum number of partitions used by the top N key operator."),

    HIVE_SHARED_WORK_OPTIMIZATION("hive.optimize.shared.work", true,
        "Whether to enable shared work optimizer. The optimizer finds scan operator over the same table\n" +
        "and follow-up operators in the query plan and merges them if they meet some preconditions. Tez only."),
    HIVE_SHARED_WORK_EXTENDED_OPTIMIZATION("hive.optimize.shared.work.extended", true,
        "Whether to enable shared work extended optimizer. The optimizer tries to merge equal operators\n" +
        "after a work boundary after shared work optimizer has been executed. Requires hive.optimize.shared.work\n" +
        "to be set to true. Tez only."),
    HIVE_SHARED_WORK_SEMIJOIN_OPTIMIZATION("hive.optimize.shared.work.semijoin", false,
        "Whether to enable shared work extended optimizer for semijoins. The optimizer tries to merge\n" +
        "scan operators if one of them reads the full table, even if the other one is the target for\n" +
        "one or more semijoin edges. Tez only."),
    HIVE_SHARED_WORK_MERGE_TS_SCHEMA("hive.optimize.shared.work.merge.ts.schema", true,
        "Whether to enable merging scan operators over the same table but with different schema." +
            "The optimizer tries to merge the scan operators by taking the union of needed columns from " +
            "all scan operators. Requires hive.optimize.shared.work to be set to true. Tez only."),
    HIVE_SHARED_WORK_REUSE_MAPJOIN_CACHE("hive.optimize.shared.work.mapjoin.cache.reuse", true,
        "When shared work optimizer is enabled, whether we should reuse the cache for the broadcast side\n" +
        "of mapjoin operators that share same broadcast input. Requires hive.optimize.shared.work\n" +
        "to be set to true. Tez only."),
    HIVE_SHARED_WORK_DPPUNION_OPTIMIZATION("hive.optimize.shared.work.dppunion", true,
        "Enables dppops unioning. This optimization will enable to merge multiple tablescans with different "
            + "dynamic filters into a single one (with a more complex filter)"),
    HIVE_SHARED_WORK_DPPUNION_MERGE_EVENTOPS("hive.optimize.shared.work.dppunion.merge.eventops", true,
        "Enables DPPUnion to merge EventOperators (right now this is used during DynamicPartitionPruning)"),
    HIVE_SHARED_WORK_DOWNSTREAM_MERGE("hive.optimize.shared.work.downstream.merge", true,
        "Analyzes and merges equiv downstream operators after a successful shared work optimization step."),
    HIVE_SHARED_WORK_PARALLEL_EDGE_SUPPORT("hive.optimize.shared.work.parallel.edge.support", true,
        "Lets the shared work optimizer to create parallel edges in case they are for semijoins or mapjoins."),
    HIVE_SHARED_WORK_MAX_SIBLINGS("hive.optimize.shared.work.max.siblings", -1,
        "The maximum number of operators merged in a single iteration. -1 means infinite"),
    HIVE_REMOVE_SQ_COUNT_CHECK("hive.optimize.remove.sq_count_check", true,
        "Whether to remove an extra join with sq_count_check for scalar subqueries "
            + "with constant group by keys."),

    HIVE_OPTIMIZE_TABLE_PROPERTIES_FROM_SERDE("hive.optimize.update.table.properties.from.serde", false,
        "Whether to update table-properties by initializing tables' SerDe instances during logical-optimization. \n" +
            "By doing so, certain SerDe classes (like AvroSerDe) can pre-calculate table-specific information, and \n" +
            "store it in table-properties, to be used later in the SerDe, while running the job."),

    HIVE_OPTIMIZE_TABLE_PROPERTIES_FROM_SERDE_LIST("hive.optimize.update.table.properties.from.serde.list",
        "org.apache.hadoop.hive.serde2.avro.AvroSerDe",
        "The comma-separated list of SerDe classes that are considered when enhancing table-properties \n" +
            "during logical optimization."),

    HIVE_OPTIMIZE_SCAN_PROBEDECODE("hive.optimize.scan.probedecode", true,
        "Whether to find suitable table scan operators that could reduce the number of decoded rows at runtime by probing extra available information. \n"
            + "The probe side for the row-level filtering is generated either statically in the case of expressions or dynamically for joins"
            + "e.g., use the cached MapJoin hashtable created on the small table side to filter out row columns that are not going "
            + "to be used when reading the large table data. This will result less CPU cycles spent for decoding unused data."),

    HIVE_OPTIMIZE_HMS_QUERY_CACHE_ENABLED("hive.optimize.metadata.query.cache.enabled", true,
        "This property enables caching metadata for repetitive requests on a per-query basis"),

    HIVE_OPTIMIZE_VIEW_CACHE_ENABLED("hive.optimize.view.tables.cache.enabled", true,
        "This property enables caching of views and their underlying tables. The cache in memory may be stale, but "
            + " provides an optimization if it is accurate."),

    // CTE
    @InterfaceStability.Unstable
    HIVE_CTE_SUGGESTER_CLASS("hive.optimize.cte.suggester.class", "",
        "Class for finding and suggesting common table expressions (CTEs) based on a given query. The class must implement the CommonTableExpressionSuggester interface."),
    HIVE_CTE_MATERIALIZE_THRESHOLD("hive.optimize.cte.materialize.threshold", 3,
        "If the number of references to a CTE clause exceeds this threshold, Hive will materialize it\n" +
        "before executing the main query block. -1 will disable this feature."),
    HIVE_CTE_MATERIALIZE_FULL_AGGREGATE_ONLY("hive.optimize.cte.materialize.full.aggregate.only", true,
        "If enabled only CTEs with aggregate output will be pre-materialized. All CTEs otherwise." +
            "Also the number of references to a CTE clause must exceeds the value of " +
            "hive.optimize.cte.materialize.threshold"),

    HIVE_OPTIMIZE_BI_ENABLED("hive.optimize.bi.enabled", false,
        "Enables query rewrites based on approximate functions(sketches)."),

    HIVE_OPTIMIZE_BI_REWRITE_COUNTDISTINCT_ENABLED("hive.optimize.bi.rewrite.countdistinct.enabled",
        true,
        "Enables to rewrite COUNT(DISTINCT(X)) queries to be rewritten to use sketch functions."),
    HIVE_OPTIMIZE_BI_REWRITE_COUNT_DISTINCT_SKETCH("hive.optimize.bi.rewrite.countdistinct.sketch", "hll",
        new StringSet("hll"),
        "Defines which sketch type to use when rewriting COUNT(DISTINCT(X)) expressions. "
            + "Distinct counting can be done with: hll"),
    HIVE_OPTIMIZE_BI_REWRITE_PERCENTILE_DISC_ENABLED("hive.optimize.bi.rewrite.percentile_disc.enabled",
        true,
        "Enables to rewrite PERCENTILE_DISC(X) queries to be rewritten to use sketch functions."),
    HIVE_OPTIMIZE_BI_REWRITE_PERCENTILE_DISC_SKETCH("hive.optimize.bi.rewrite.percentile_disc.sketch", "kll",
        new StringSet("kll"),
        "Defines which sketch type to use when rewriting PERCENTILE_DISC expressions. Options: kll"),
    HIVE_OPTIMIZE_BI_REWRITE_CUME_DIST_ENABLED("hive.optimize.bi.rewrite.cume_dist.enabled",
        true,
        "Enables to rewrite CUME_DIST(X) queries to be rewritten to use sketch functions."),
    HIVE_OPTIMIZE_BI_REWRITE_CUME_DIST_SKETCH("hive.optimize.bi.rewrite.cume_dist.sketch", "kll",
        new StringSet("kll"),
        "Defines which sketch type to use when rewriting CUME_DIST expressions. Options: kll"),
    HIVE_OPTIMIZE_BI_REWRITE_NTILE_ENABLED("hive.optimize.bi.rewrite.ntile.enabled",
        true,
        "Enables to rewrite NTILE(X) queries to be rewritten as sketch functions."),
    HIVE_OPTIMIZE_BI_REWRITE_NTILE_SKETCH("hive.optimize.bi.rewrite.ntile.sketch", "kll",
        new StringSet("kll"),
        "Defines which sketch type to use when rewriting NTILE expressions. Options: kll"),
    HIVE_OPTIMIZE_BI_REWRITE_RANK_ENABLED("hive.optimize.bi.rewrite.rank.enabled",
        true,
        "Enables to rewrite RANK() queries to be rewritten to use sketch functions."),
    HIVE_OPTIMIZE_BI_REWRITE_RANK_SKETCH("hive.optimize.bi.rewrite.rank.sketch", "kll",
        new StringSet("kll"),
        "Defines which sketch type to use when rewriting RANK expressions. Options: kll"),

    // Statistics
    HIVE_STATS_ESTIMATE_STATS("hive.stats.estimate", true,
        "Estimate statistics in absence of statistics."),
    HIVE_STATS_NDV_ESTIMATE_PERC("hive.stats.ndv.estimate.percent", (float)20,
        "This many percentage of rows will be estimated as count distinct in absence of statistics."),
    HIVE_STATS_JOIN_NDV_READJUSTMENT("hive.stats.join.ndv.readjustment", false,
        "Set this to true to use approximation based logic to adjust ndv after join."),
    HIVE_STATS_NUM_NULLS_ESTIMATE_PERC("hive.stats.num.nulls.estimate.percent", (float)5,
        "This many percentage of rows will be estimated as number of nulls in absence of statistics."),
    HIVE_STATS_AUTOGATHER("hive.stats.autogather", true,
        "A flag to gather statistics (only basic) automatically during the INSERT OVERWRITE command."),
    HIVE_STATS_COL_AUTOGATHER("hive.stats.column.autogather", true,
        "A flag to gather column statistics automatically."),
    HIVE_STATS_DBCLASS("hive.stats.dbclass", "fs", new PatternSet("custom", "fs"),
        "The storage that stores temporary Hive statistics. In filesystem based statistics collection ('fs'), \n" +
        "each task writes statistics it has collected in a file on the filesystem, which will be aggregated \n" +
        "after the job has finished. Supported values are fs (filesystem) and custom as defined in StatsSetupConst.java."), // StatsSetupConst.StatDB
    /**
     * @deprecated Use MetastoreConf.STATS_DEFAULT_PUBLISHER
     */
    @Deprecated
    HIVE_STATS_DEFAULT_PUBLISHER("hive.stats.default.publisher", "",
        "The Java class (implementing the StatsPublisher interface) that is used by default if hive.stats.dbclass is custom type."),
    /**
     * @deprecated Use MetastoreConf.STATS_DEFAULT_AGGREGATOR
     */
    @Deprecated
    HIVE_STATS_DEFAULT_AGGREGATOR("hive.stats.default.aggregator", "",
        "The Java class (implementing the StatsAggregator interface) that is used by default if hive.stats.dbclass is custom type."),
    CLIENT_STATS_COUNTERS("hive.client.stats.counters", "",
        "Subset of counters that should be of interest for hive.client.stats.publishers (when one wants to limit their publishing). \n" +
        "Non-display names should be used"),
    //Subset of counters that should be of interest for hive.client.stats.publishers (when one wants to limit their publishing). Non-display names should be used".
    HIVE_STATS_RELIABLE("hive.stats.reliable", false,
        "Whether queries will fail because stats cannot be collected completely accurately. \n" +
        "If this is set to true, reading/writing from/into a partition may fail because the stats\n" +
        "could not be computed accurately."),
    HIVE_STATS_COLLECT_PART_LEVEL_STATS("hive.analyze.stmt.collect.partlevel.stats", true,
        "analyze table T compute statistics for columns. Queries like these should compute partition"
        + "level stats for partitioned table even when no part spec is specified."),
    HIVE_STATS_GATHER_NUM_THREADS("hive.stats.gather.num.threads", 10,
        "Number of threads used by noscan analyze command for partitioned tables.\n" +
        "This is applicable only for file formats that implement StatsProvidingRecordReader (like ORC)."),
    // Collect table access keys information for operators that can benefit from bucketing
    HIVE_STATS_COLLECT_TABLEKEYS("hive.stats.collect.tablekeys", false,
        "Whether join and group by keys on tables are derived and maintained in the QueryPlan.\n" +
        "This is useful to identify how tables are accessed and to determine if they should be bucketed."),
    // Collect column access information
    HIVE_STATS_COLLECT_SCANCOLS("hive.stats.collect.scancols", false,
        "Whether column accesses are tracked in the QueryPlan.\n" +
        "This is useful to identify how tables are accessed and to determine if there are wasted columns that can be trimmed."),
    HIVE_STATS_NDV_ALGO("hive.stats.ndv.algo", "hll", new PatternSet("hll", "fm"),
        "hll and fm stand for HyperLogLog and FM-sketch, respectively for computing ndv."),
    /**
     * @deprecated Use MetastoreConf.STATS_FETCH_BITVECTOR
     */
    @Deprecated
    HIVE_STATS_FETCH_BITVECTOR("hive.stats.fetch.bitvector", false,
        "Whether we fetch bitvector when we compute ndv. Users can turn it off if they want to use old schema"),
    // standard error allowed for ndv estimates for FM-sketch. A lower value indicates higher accuracy and a
    // higher compute cost.
    HIVE_STATS_NDV_ERROR("hive.stats.ndv.error", (float)20.0,
            "The standard error allowed for NDV estimates, expressed in percentage. This provides a tradeoff \n" +
                    "between accuracy and compute cost. A lower value for the error indicates higher accuracy and a \n" +
                    "higher compute cost. (NDV means the number of distinct values.). It only affects the FM-Sketch \n" +
                    "(not the HLL algorithm which is the default), where it computes the number of necessary\n" +
                    " bitvectors to achieve the accuracy."),
    HIVE_STATS_KLL_K_PARAM("hive.stats.kll.k.param", 200,
        "The parameter k affects the accuracy and the size of KLL sketches.\n"
            + "Larger values of k result in smaller values of normalized rank error.\n"
            + "The default of 200 yields approximately an error of 1.65%."),
    HIVE_STATS_KLL_ENABLE("hive.stats.kll.enable", false,
        "Whether to compute KLL sketches to enable histogram statistics."),
    HIVE_STATS_ESTIMATORS_ENABLE("hive.stats.estimators.enable", true,
        "Estimators are able to provide more accurate column statistic infos for UDF results."),

    /**
     * @deprecated Use MetastoreConf.STATS_NDV_TUNER
     */
    @Deprecated
    HIVE_METASTORE_STATS_NDV_TUNER("hive.metastore.stats.ndv.tuner", (float)0.0,
         "Provides a tunable parameter between the lower bound and the higher bound of ndv for aggregate ndv across all the partitions. \n" +
         "The lower bound is equal to the maximum of ndv of all the partitions. The higher bound is equal to the sum of ndv of all the partitions.\n" +
         "Its value should be between 0.0 (i.e., choose lower bound) and 1.0 (i.e., choose higher bound)"),
    /**
     * @deprecated Use MetastoreConf.STATS_NDV_DENSITY_FUNCTION
     */
    @Deprecated
    HIVE_METASTORE_STATS_NDV_DENSITY_FUNCTION("hive.metastore.stats.ndv.densityfunction", false,
        "Whether to use density function to estimate the NDV for the whole table based on the NDV of partitions"),
    HIVE_STATS_KEY_PREFIX("hive.stats.key.prefix", "", "", true), // internal usage only
    // if length of variable length data type cannot be determined this length will be used.
    HIVE_STATS_MAX_VARIABLE_LENGTH("hive.stats.max.variable.length", 100,
        "To estimate the size of data flowing through operators in Hive/Tez(for reducer estimation etc.),\n" +
        "average row size is multiplied with the total number of rows coming out of each operator.\n" +
        "Average row size is computed from average column size of all columns in the row. In the absence\n" +
        "of column statistics, for variable length columns (like string, bytes etc.), this value will be\n" +
        "used. For fixed length columns their corresponding Java equivalent sizes are used\n" +
        "(float - 4 bytes, double - 8 bytes etc.)."),
    // if number of elements in list cannot be determined, this value will be used
    HIVE_STATS_LIST_NUM_ENTRIES("hive.stats.list.num.entries", 10,
        "To estimate the size of data flowing through operators in Hive/Tez(for reducer estimation etc.),\n" +
        "average row size is multiplied with the total number of rows coming out of each operator.\n" +
        "Average row size is computed from average column size of all columns in the row. In the absence\n" +
        "of column statistics and for variable length complex columns like list, the average number of\n" +
        "entries/values can be specified using this config."),
    // if number of elements in map cannot be determined, this value will be used
    HIVE_STATS_MAP_NUM_ENTRIES("hive.stats.map.num.entries", 10,
        "To estimate the size of data flowing through operators in Hive/Tez(for reducer estimation etc.),\n" +
        "average row size is multiplied with the total number of rows coming out of each operator.\n" +
        "Average row size is computed from average column size of all columns in the row. In the absence\n" +
        "of column statistics and for variable length complex columns like map, the average number of\n" +
        "entries/values can be specified using this config."),
    // statistics annotation fetches column statistics for all required columns which can
    // be very expensive sometimes
    HIVE_STATS_FETCH_COLUMN_STATS("hive.stats.fetch.column.stats", true,
        "Annotation of operator tree with statistics information requires column statistics.\n" +
        "Column statistics are fetched from metastore. Fetching column statistics for each needed column\n" +
        "can be expensive when the number of columns is high. This flag can be used to disable fetching\n" +
        "of column statistics from metastore."),
    // in the absence of column statistics, the estimated number of rows/data size that will
    // be emitted from join operator will depend on this factor
    HIVE_STATS_JOIN_FACTOR("hive.stats.join.factor", (float) 1.1,
        "Hive/Tez optimizer estimates the data size flowing through each of the operators. JOIN operator\n" +
        "uses column statistics to estimate the number of rows flowing out of it and hence the data size.\n" +
        "In the absence of column statistics, this factor determines the amount of rows that flows out\n" +
        "of JOIN operator."),
    HIVE_STATS_CORRELATED_MULTI_KEY_JOINS("hive.stats.correlated.multi.key.joins", true,
        "When estimating output rows for a join involving multiple columns, the default behavior assumes" +
        "the columns are independent. Setting this flag to true will cause the estimator to assume" +
        "the columns are correlated."),
    HIVE_STATS_RANGE_SELECTIVITY_UNIFORM_DISTRIBUTION("hive.stats.filter.range.uniform", true,
        "When estimating output rows from a condition, if a range predicate is applied over a column and the\n" +
        "minimum and maximum values for that column are available, assume uniform distribution of values\n" +
        "across that range and scales number of rows proportionally. If this is set to false, default\n" +
        "selectivity value is used."),
    // in the absence of uncompressed/raw data size, total file size will be used for statistics
    // annotation. But the file may be compressed, encoded and serialized which may be lesser in size
    // than the actual uncompressed/raw data size. This factor will be multiplied to file size to estimate
    // the raw data size.
    HIVE_STATS_DESERIALIZATION_FACTOR("hive.stats.deserialization.factor", (float) 10.0,
        "Hive/Tez optimizer estimates the data size flowing through each of the operators. In the absence\n" +
        "of basic statistics like number of rows and data size, file size is used to estimate the number\n" +
        "of rows and data size. Since files in tables/partitions are serialized (and optionally\n" +
        "compressed) the estimates of number of rows and data size cannot be reliably determined.\n" +
        "This factor is multiplied with the file size to account for serialization and compression."),
    HIVE_STATS_IN_CLAUSE_FACTOR("hive.stats.filter.in.factor", (float) 1.0,
        "Currently column distribution is assumed to be uniform. This can lead to overestimation/underestimation\n" +
        "in the number of rows filtered by a certain operator, which in turn might lead to overprovision or\n" +
        "underprovision of resources. This factor is applied to the cardinality estimation of IN clauses in\n" +
        "filter operators."),
    HIVE_STATS_IN_MIN_RATIO("hive.stats.filter.in.min.ratio", 0.0f,
        "Output estimation of an IN filter can't be lower than this ratio"),
    HIVE_STATS_UDTF_FACTOR("hive.stats.udtf.factor", (float) 1.0,
        "UDTFs change the number of rows of the output. A common UDTF is the explode() method that creates\n" +
        "multiple rows for each element in the input array. This factor is applied to the number of\n" +
        "output rows and output size."),
    HIVE_STATS_USE_BITVECTORS("hive.stats.use.bitvectors", false,
              "Enables to use bitvectors for estimating selectivity."),
    HIVE_STATS_MAX_NUM_STATS("hive.stats.max.num.stats", (long) 10000,
        "When the number of stats to be updated is huge, this value is used to control the number of \n" +
        " stats to be sent to HMS for update."),
    HIVE_THRIFT_CLIENT_MAX_MESSAGE_SIZE("hive.thrift.client.max.message.size", "2147483647b",
            new SizeValidator(-1L, true, (long) Integer.MAX_VALUE, true),
            "Thrift client configuration for max message size. 0 or -1 will use the default defined in the Thrift " +
                    "library. The upper limit is 2147483647 bytes."),
    // Concurrency
    HIVE_SUPPORT_CONCURRENCY("hive.support.concurrency", false,
        "Whether Hive supports concurrency control or not. \n" +
        "A ZooKeeper instance must be up and running when using zookeeper Hive lock manager "),
    HIVE_LOCK_MANAGER("hive.lock.manager", "org.apache.hadoop.hive.ql.lockmgr.zookeeper.ZooKeeperHiveLockManager", ""),
    HIVE_LOCK_NUMRETRIES("hive.lock.numretries", 100,
        "The number of times you want to try to get all the locks"),
    HIVE_UNLOCK_NUMRETRIES("hive.unlock.numretries", 10,
        "The number of times you want to retry to do one unlock"),
    HIVE_LOCK_SLEEP_BETWEEN_RETRIES("hive.lock.sleep.between.retries", "60s",
        new TimeValidator(TimeUnit.SECONDS, 0L, false, Long.MAX_VALUE, false),
        "The maximum sleep time between various retries"),
    HIVE_LOCK_MAPRED_ONLY("hive.lock.mapred.only.operation", false,
        "This param is to control whether or not only do lock on queries\n" +
        "that need to execute at least one mapred job."),
    HIVE_LOCK_QUERY_STRING_MAX_LENGTH("hive.lock.query.string.max.length", 1000000,
        "The maximum length of the query string to store in the lock.\n" +
        "The default value is 1000000, since the data limit of a znode is 1MB"),
    HIVE_MM_ALLOW_ORIGINALS("hive.mm.allow.originals", false,
        "Whether to allow original files in MM tables. Conversion to MM may be expensive if\n" +
        "this is set to false, however unless MAPREDUCE-7086 fix is present (hadoop 3.1.1+),\n" +
        "queries that read non-orc MM tables with original files will fail. The default in\n" +
        "Hive 3.0 is false."),
    HIVE_LOCK_FILE_MOVE_MODE("hive.lock.file.move.protect", "all", new StringSet("none", "dp", "all"),
        "During file move operations acquires a SEMI_SHARED lock at the table level."
            + "none:never; dp: only in case of dynamic partitioning operations; all: all table operations"),

    // Zookeeper related configs
    HIVE_ZOOKEEPER_USE_KERBEROS("hive.zookeeper.kerberos.enabled", true,
        "If ZooKeeper is configured for Kerberos authentication. This could be useful when cluster\n" +
        "is kerberized, but Zookeeper is not."),

    HIVE_ZOOKEEPER_QUORUM("hive.zookeeper.quorum", "",
        "List of ZooKeeper servers to talk to. This is needed for: \n" +
        "1. Read/write locks - when hive.lock.manager is set to \n" +
        "org.apache.hadoop.hive.ql.lockmgr.zookeeper.ZooKeeperHiveLockManager, \n" +
        "2. When HiveServer2 supports service discovery via Zookeeper.\n" +
        "3. For delegation token storage if zookeeper store is used, if\n" +
        "hive.cluster.delegation.token.store.zookeeper.connectString is not set\n" +
        "4. LLAP daemon registry service\n" +
        "5. Leader selection for privilege synchronizer"),

    HIVE_ZOOKEEPER_CLIENT_PORT("hive.zookeeper.client.port", "2181",
        "The port of ZooKeeper servers to talk to.\n" +
        "If the list of Zookeeper servers specified in hive.zookeeper.quorum\n" +
        "does not contain port numbers, this value is used."),
    HIVE_ZOOKEEPER_SESSION_TIMEOUT("hive.zookeeper.session.timeout", "120000ms",
        new TimeValidator(TimeUnit.MILLISECONDS),
        "ZooKeeper client's session timeout (in milliseconds). The client is disconnected, and as a result, all locks released, \n" +
        "if a heartbeat is not sent in the timeout."),
    HIVE_ZOOKEEPER_CONNECTION_TIMEOUT("hive.zookeeper.connection.timeout", "15s",
      new TimeValidator(TimeUnit.SECONDS),
      "ZooKeeper client's connection timeout in seconds. Connection timeout * hive.zookeeper.connection.max.retries\n" +
        "with exponential backoff is when curator client deems connection is lost to zookeeper."),
    HIVE_ZOOKEEPER_NAMESPACE("hive.zookeeper.namespace", "hive_zookeeper_namespace",
        "The parent node under which all ZooKeeper nodes are created."),
    HIVE_ZOOKEEPER_CLEAN_EXTRA_NODES("hive.zookeeper.clean.extra.nodes", false,
        "Clean extra nodes at the end of the session."),
    HIVE_ZOOKEEPER_CONNECTION_MAX_RETRIES("hive.zookeeper.connection.max.retries", 3,
        "Max number of times to retry when connecting to the ZooKeeper server."),
    HIVE_ZOOKEEPER_CONNECTION_BASESLEEPTIME("hive.zookeeper.connection.basesleeptime", "1000ms",
        new TimeValidator(TimeUnit.MILLISECONDS),
        "Initial amount of time (in milliseconds) to wait between retries\n" +
        "when connecting to the ZooKeeper server when using ExponentialBackoffRetry policy."),
    HIVE_ZOOKEEPER_SSL_ENABLE("hive.zookeeper.ssl.client.enable", false,
        "Set client to use TLS when connecting to ZooKeeper.  An explicit value overrides any value set via the " +
            "zookeeper.client.secure system property (note the different name).  Defaults to false if neither is set."),
    HIVE_ZOOKEEPER_SSL_KEYSTORE_LOCATION("hive.zookeeper.ssl.keystore.location", "",
        "Keystore location when using a client-side certificate with TLS connectivity to ZooKeeper. " +
            "Overrides any explicit value set via the zookeeper.ssl.keyStore.location " +
            "system property (note the camelCase)."),
    HIVE_ZOOKEEPER_SSL_KEYSTORE_PASSWORD("hive.zookeeper.ssl.keystore.password", "",
        "Keystore password when using a client-side certificate with TLS connectivity to ZooKeeper." +
            "Overrides any explicit value set via the zookeeper.ssl.keyStore.password " +
             "system property (note the camelCase)."),
    HIVE_ZOOKEEPER_SSL_KEYSTORE_TYPE("hive.zookeeper.ssl.keystore.type", "",
        "Keystore type when using a client-side certificate with TLS connectivity to ZooKeeper." +
            "Overrides any explicit value set via the zookeeper.ssl.keyStore.type " +
            "system property (note the camelCase)."),
    HIVE_ZOOKEEPER_SSL_TRUSTSTORE_LOCATION("hive.zookeeper.ssl.truststore.location", "",
        "Truststore location when using a client-side certificate with TLS connectivity to ZooKeeper. " +
            "Overrides any explicit value set via the zookeeper.ssl.trustStore.location" +
            "system property (note the camelCase)."),
    HIVE_ZOOKEEPER_SSL_TRUSTSTORE_PASSWORD("hive.zookeeper.ssl.truststore.password", "",
        "Truststore password when using a client-side certificate with TLS connectivity to ZooKeeper." +
            "Overrides any explicit value set via the zookeeper.ssl.trustStore.password " +
             "system property (note the camelCase)."),
    HIVE_ZOOKEEPER_SSL_TRUSTSTORE_TYPE("hive.zookeeper.ssl.truststore.type", "",
        "Truststore type when using a client-side certificate with TLS connectivity to ZooKeeper." +
            "Overrides any explicit value set via the zookeeper.ssl.trustStore.type " +
            "system property (note the camelCase)."),
    HIVE_ZOOKEEPER_KILLQUERY_ENABLE("hive.zookeeper.killquery.enable", true,
        "Whether enabled kill query coordination with zookeeper, " +
            "when hive.server2.support.dynamic.service.discovery is enabled."),
    HIVE_ZOOKEEPER_KILLQUERY_NAMESPACE("hive.zookeeper.killquery.namespace", "killQueries",
        "When kill query coordination is enabled, uses this namespace for registering queries to kill with zookeeper"),

    // Transactions
    HIVE_TXN_MANAGER("hive.txn.manager",
        "org.apache.hadoop.hive.ql.lockmgr.DummyTxnManager",
        "Set to org.apache.hadoop.hive.ql.lockmgr.DbTxnManager as part of turning on Hive\n" +
        "transactions, which also requires appropriate settings for hive.compactor.initiator.on,hive.compactor.cleaner.on,\n" +
        "hive.compactor.worker.threads, hive.support.concurrency (true),\n" +
        "and hive.exec.dynamic.partition.mode (nonstrict).\n" +
        "The default DummyTxnManager replicates pre-Hive-0.13 behavior and provides\n" +
        "no transactions."),
    HIVE_TXN_EXT_LOCKING_ENABLED("hive.txn.ext.locking.enabled", false,
        "When enabled use standard R/W lock semantics based on hive.txn.strict.locking.mode for external resources,\n" +
        "e.g. INSERT will acquire lock based on hive.txn.strict.locking.mode\n" +
        "(exclusive if it is true, shared if that is false),\n" +
        "SELECT will acquire shared lock based on hive.txn.nonacid.read.locks.\n" +
        "When disabled no locks are acquired for external resources."),
    HIVE_TXN_STRICT_LOCKING_MODE("hive.txn.strict.locking.mode", true, "In strict mode non-ACID\n" +
        "resources use standard R/W lock semantics, e.g. INSERT will acquire exclusive lock.\n" +
        "In nonstrict mode, for non-ACID resources, INSERT will only acquire shared lock, which\n" +
        "allows two concurrent writes to the same partition but still lets lock manager prevent\n" +
        "DROP TABLE etc. when the table is being written to"),
    HIVE_TXN_NONACID_READ_LOCKS("hive.txn.nonacid.read.locks", true,
        "Flag to turn off the read locks for non-ACID tables, when set to false.\n" +
        "Could be exercised to improve the performance of non-ACID tables in clusters where read locking " +
        "is enabled globally to support ACID. Can cause issues with concurrent DDL operations, or slow S3 writes."),
    HIVE_TXN_READ_LOCKS("hive.txn.read.locks", true,
        "Flag to turn off the read locks, when set to false. Although its not recommended, \n" +
        "but in performance critical scenarios this option may be exercised."),
    HIVE_LOCKS_PARTITION_THRESHOLD("hive.locks.max.partitions", -1,
        "Locks the entire table if number of partition locks exceeds user-defined threshold. Disabled by default."),
    TXN_OVERWRITE_X_LOCK("hive.txn.xlock.iow", true,
        "Ensures commands with OVERWRITE (such as INSERT OVERWRITE) acquire Exclusive locks for\n" +
        "transactional tables. This ensures that inserts (w/o overwrite) running concurrently\n" +
        "are not hidden by the INSERT OVERWRITE."),
    TXN_MERGE_INSERT_X_LOCK("hive.txn.xlock.mergeinsert", false,
        "Ensures MERGE INSERT operations acquire EXCLUSIVE / EXCL_WRITE lock for transactional tables.\n" +
        "If enabled, prevents duplicates when MERGE statements are executed in parallel transactions."),
    TXN_WRITE_X_LOCK("hive.txn.xlock.write", false,
        "Manages concurrency levels for ACID resources. Provides better level of query parallelism by enabling " +
        "shared writes and write-write conflict resolution at the commit step." +
        "- If true - exclusive writes are used:\n" +
        "  - INSERT OVERWRITE acquires EXCLUSIVE locks\n" +
        "  - UPDATE/DELETE acquire EXCL_WRITE locks\n" +
        "  - INSERT acquires SHARED_READ locks\n" +
        "- If false - shared writes, transaction is aborted in case of conflicting changes:\n" +
        "  - INSERT OVERWRITE acquires EXCL_WRITE locks\n" +
        "  - INSERT/UPDATE/DELETE acquire SHARED_WRITE locks"),
    HIVE_TXN_STATS_ENABLED("hive.txn.stats.enabled", true,
        "Whether Hive supports transactional stats (accurate stats for transactional tables)"),
    HIVE_TXN_ACID_DIR_CACHE_DURATION("hive.txn.acid.dir.cache.duration",
        120, "Enable dir cache for ACID tables specified in minutes."
        + "0 indicates cache is used as read-only and no additional info would be "
        + "populated. -1 means cache is disabled"),
    HIVE_WRITE_ACID_VERSION_FILE("hive.txn.write.acid.version.file", true,
        "Creates an _orc_acid_version file along with acid files, to store the version data"),

    HIVE_TXN_READONLY_ENABLED("hive.txn.readonly.enabled", false,
        "Enables read-only transaction classification and related optimizations"),

    HIVE_ACID_LOCKLESS_READS_ENABLED("hive.acid.lockless.reads.enabled", false,
        "Enables lockless reads"),
     
    HIVE_ACID_CREATE_TABLE_USE_SUFFIX("hive.acid.createtable.softdelete", false,
        "Enables non-blocking DROP TABLE operation.\n" +
        "If enabled, every table directory would be suffixed with the corresponding table creation txnId."),
    
    HIVE_ACID_TRUNCATE_USE_BASE("hive.acid.truncate.usebase", true,
        "If enabled, truncate for transactional tables will not delete the data directories,\n" +
        "rather create a new base directory with no datafiles."),
    
    HIVE_ACID_DROP_PARTITION_USE_BASE("hive.acid.droppartition.usebase", false,
        "Enables non-blocking DROP PARTITION operation.\n" +
        "If enabled, drop for transactional tables will not delete the data directories,\n" +
        "rather create a new base directory with no datafiles.\")"),

    HIVE_ACID_RENAME_PARTITION_MAKE_COPY("hive.acid.renamepartition.makecopy", false,
      "Enables non-blocking RENAME PARTITION operation.\n" +
        "If enabled, rename for transactional tables will not rename the partition directory,\n" +
        "rather create a copy of it under the new path.\")"),
    
    // Configs having to do with DeltaFilesMetricReporter, which collects lists of most recently active tables
    // with the most number of active/obsolete deltas.
    /**
     * @deprecated use MetastoreConf.METASTORE_DELTAMETRICS_MAX_CACHE_SIZE
     */
    @Deprecated
    HIVE_TXN_ACID_METRICS_MAX_CACHE_SIZE("hive.txn.acid.metrics.max.cache.size", 100,
        new RangeValidator(0, 500),
        "Size of the ACID metrics cache, i.e. max number of partitions and unpartitioned tables with the "
            + "most deltas that will be included in the lists of active, obsolete and small deltas. "
            + "Allowed range is 0 to 500."),
    /**
     * @deprecated use MetastoreConf.METASTORE_DELTAMETRICS_REPORTING_INTERVAL
     */
    @Deprecated
    HIVE_TXN_ACID_METRICS_REPORTING_INTERVAL("hive.txn.acid.metrics.reporting.interval", "30s",
        new TimeValidator(TimeUnit.SECONDS),
        "Reporting period for ACID metrics in seconds."),
    /**
     * @deprecated use MetastoreConf.METASTORE_DELTAMETRICS_DELTA_NUM_THRESHOLD
     */
    @Deprecated
    HIVE_TXN_ACID_METRICS_DELTA_NUM_THRESHOLD("hive.txn.acid.metrics.delta.num.threshold", 100,
        "The minimum number of active delta files a table/partition must have in order to be included in the ACID metrics report."),
    /**
     * @deprecated use MetastoreConf.METASTORE_DELTAMETRICS_OBSOLETE_DELTA_NUM_THRESHOLD
     */
    @Deprecated
    HIVE_TXN_ACID_METRICS_OBSOLETE_DELTA_NUM_THRESHOLD("hive.txn.acid.metrics.obsolete.delta.num.threshold", 100,
        "The minimum number of obsolete delta files a table/partition must have in order to be included in the ACID metrics report."),
    /**
     * @deprecated use MetastoreConf.METASTORE_DELTAMETRICS_DELTA_PCT_THRESHOLD
     */
    @Deprecated
    HIVE_TXN_ACID_METRICS_DELTA_PCT_THRESHOLD("hive.txn.acid.metrics.delta.pct.threshold", 0.01f,
        "Percentage (fractional) size of the delta files relative to the base directory. Deltas smaller than this threshold " +
        "count as small deltas. Default 0.01 = 1%.)"),

    /**
     * @deprecated Use MetastoreConf.TXN_TIMEOUT
     */
    @Deprecated
    HIVE_TXN_TIMEOUT("hive.txn.timeout", "300s", new TimeValidator(TimeUnit.SECONDS),
        "time after which transactions are declared aborted if the client has not sent a heartbeat."),
    /**
     * @deprecated Use MetastoreConf.TXN_HEARTBEAT_THREADPOOL_SIZE
     */
    @Deprecated
    HIVE_TXN_HEARTBEAT_THREADPOOL_SIZE("hive.txn.heartbeat.threadpool.size", 5, "The number of " +
        "threads to use for heartbeating. For Hive CLI, 1 is enough. For HiveServer2, we need a few"),
    TXN_MGR_DUMP_LOCK_STATE_ON_ACQUIRE_TIMEOUT("hive.txn.manager.dump.lock.state.on.acquire.timeout", false,
      "Set this to true so that when attempt to acquire a lock on resource times out, the current state" +
        " of the lock manager is dumped to log file.  This is for debugging.  See also " +
        "hive.lock.numretries and hive.lock.sleep.between.retries."),

    HIVE_TXN_OPERATIONAL_PROPERTIES("hive.txn.operational.properties", 1,
      "1: Enable split-update feature found in the newer version of Hive ACID subsystem\n" +
      "4: Make the table 'quarter-acid' as it only supports insert. But it doesn't require ORC or bucketing.\n" +
      "This is intended to be used as an internal property for future versions of ACID. (See\n" +
        "HIVE-14035 for details.  User sets it tblproperites via transactional_properties.)", true),
    /**
     * @deprecated Use MetastoreConf.MAX_OPEN_TXNS
     */
    @Deprecated
    HIVE_MAX_OPEN_TXNS("hive.max.open.txns", 100000, "Maximum number of open transactions. If \n" +
        "current open transactions reach this limit, future open transaction requests will be \n" +
        "rejected, until this number goes below the limit."),
    /**
     * @deprecated Use MetastoreConf.COUNT_OPEN_TXNS_INTERVAL
     */
    @Deprecated
    HIVE_COUNT_OPEN_TXNS_INTERVAL("hive.count.open.txns.interval", "1s",
        new TimeValidator(TimeUnit.SECONDS), "Time in seconds between checks to count open transactions."),
    /**
     * @deprecated Use MetastoreConf.TXN_MAX_OPEN_BATCH
     */
    @Deprecated
    HIVE_TXN_MAX_OPEN_BATCH("hive.txn.max.open.batch", 1000,
        "Maximum number of transactions that can be fetched in one call to open_txns().\n" +
        "This controls how many transactions streaming agents such as Flume or Storm open\n" +
        "simultaneously. The streaming agent then writes that number of entries into a single\n" +
        "file (per Flume agent or Storm bolt). Thus increasing this value decreases the number\n" +
        "of delta files created by streaming agents. But it also increases the number of open\n" +
        "transactions that Hive has to track at any given time, which may negatively affect\n" +
        "read performance."),
    /**
     * @deprecated Use MetastoreConf.TXN_RETRYABLE_SQLEX_REGEX
     */
    @Deprecated
    HIVE_TXN_RETRYABLE_SQLEX_REGEX("hive.txn.retryable.sqlex.regex", "", "Comma separated list\n" +
        "of regular expression patterns for SQL state, error code, and error message of\n" +
        "retryable SQLExceptions, that's suitable for the metastore DB.\n" +
        "For example: Can't serialize.*,40001$,^Deadlock,.*ORA-08176.*\n" +
        "The string that the regex will be matched against is of the following form, where ex is a SQLException:\n" +
        "ex.getMessage() + \" (SQLState=\" + ex.getSQLState() + \", ErrorCode=\" + ex.getErrorCode() + \")\""),
    /**
     * @deprecated Use MetastoreConf.COMPACTOR_INITIATOR_ON
     */
    @Deprecated
    HIVE_COMPACTOR_INITIATOR_ON("hive.compactor.initiator.on", false,
        "Whether to run the initiator and cleaner threads on this metastore instance or not.\n" +
        "Set this to true on one instance of the Thrift metastore service as part of turning\n" +
        "on Hive transactions. For a complete list of parameters required for turning on\n" +
        "transactions, see hive.txn.manager."),
    /**
     * @deprecated Use MetastoreConf.COMPACTOR_WORKER_THREADS
     */
    @Deprecated
    HIVE_COMPACTOR_WORKER_THREADS("hive.compactor.worker.threads", 0,
        "How many compactor worker threads to run on this metastore instance. Set this to a\n" +
        "positive number on one or more instances of the Thrift metastore service as part of\n" +
        "turning on Hive transactions. For a complete list of parameters required for turning\n" +
        "on transactions, see hive.txn.manager.\n" +
        "Worker threads spawn MapReduce jobs to do compactions. They do not do the compactions\n" +
        "themselves. Increasing the number of worker threads will decrease the time it takes\n" +
        "tables or partitions to be compacted once they are determined to need compaction.\n" +
        "It will also increase the background load on the Hadoop cluster as more MapReduce jobs\n" +
        "will be running in the background."),

    HIVE_COMPACTOR_WORKER_TIMEOUT("hive.compactor.worker.timeout", "86400s",
        new TimeValidator(TimeUnit.SECONDS),
        "Time in seconds after which a compaction job will be declared failed and the\n" +
        "compaction re-queued."),

    HIVE_COMPACTOR_WORKER_SLEEP_TIME("hive.compactor.worker.sleep.time", "10800ms",
        new TimeValidator(TimeUnit.MILLISECONDS),
        "Time in milliseconds for which a worker threads goes into sleep before starting another iteration " +
                "in case of no launched job or error"),

    HIVE_COMPACTOR_WORKER_MAX_SLEEP_TIME("hive.compactor.worker.max.sleep.time", "320000ms",
        new TimeValidator(TimeUnit.MILLISECONDS),
        "Max time in milliseconds for which a worker threads goes into sleep before starting another iteration " +
                "used for backoff in case of no launched job or error"),
    HIVE_COMPACTOR_CHECK_INTERVAL("hive.compactor.check.interval", "300s",
        new TimeValidator(TimeUnit.SECONDS),
        "Time in seconds between checks to see if any tables or partitions need to be\n" +
        "compacted. This should be kept high because each check for compaction requires\n" +
        "many calls against the NameNode.\n" +
        "Decreasing this value will reduce the time it takes for compaction to be started\n" +
        "for a table or partition that requires compaction. However, checking if compaction\n" +
        "is needed requires several calls to the NameNode for each table or partition that\n" +
        "has had a transaction done on it since the last major compaction. So decreasing this\n" +
        "value will increase the load on the NameNode."),

    HIVE_COMPACTOR_INITIATOR_DURATION_UPDATE_INTERVAL("hive.compactor.initiator.duration.update.interval", "60s",
        new TimeValidator(TimeUnit.SECONDS),
        "Time in seconds that drives the update interval of compaction_initiator_duration metric.\n" +
            "Smaller value results in a fine grained metric update.\n" +
            "This updater can be turned off if its value less than or equals to zero.\n"+
            "In this case the above metric will be update only after the initiator completed one cycle.\n" +
            "The hive.compactor.initiator.on must be turned on (true) in-order to enable the Initiator,\n" +
            "otherwise this setting has no effect."),

    HIVE_COMPACTOR_CLEANER_DURATION_UPDATE_INTERVAL("hive.compactor.cleaner.duration.update.interval", "60s",
        new TimeValidator(TimeUnit.SECONDS),
        "Time in seconds that drives the update interval of compaction_cleaner_duration metric.\n" +
            "Smaller value results in a fine grained metric update.\n" +
            "This updater can be turned off if its value less than or equals to zero.\n"+
            "In this case the above metric will be update only after the cleaner completed one cycle."),

    HIVE_COMPACTOR_REQUEST_QUEUE("hive.compactor.request.queue", 1,
        "Enables parallelization of the checkForCompaction operation, that includes many file metadata checks\n" +
        "and may be expensive"),

    HIVE_COMPACTOR_DELTA_NUM_THRESHOLD("hive.compactor.delta.num.threshold", 10,
        "Number of delta directories in a table or partition that will trigger a minor\n" +
        "compaction."),

    HIVE_COMPACTOR_DELTA_PCT_THRESHOLD("hive.compactor.delta.pct.threshold", 0.1f,
        "Percentage (fractional) size of the delta files relative to the base that will trigger\n" +
        "a major compaction. (1.0 = 100%, so the default 0.1 = 10%.)"),
    COMPACTOR_MAX_NUM_DELTA("hive.compactor.max.num.delta", 500, "Maximum number of delta files that " +
      "the compactor will attempt to handle in a single job."),

    HIVE_COMPACTOR_ABORTEDTXN_THRESHOLD("hive.compactor.abortedtxn.threshold", 1000,
        "Number of aborted transactions involving a given table or partition that will trigger\n" +
        "a major compaction / cleanup of aborted directories."),

    HIVE_COMPACTOR_ABORTEDTXN_TIME_THRESHOLD("hive.compactor.aborted.txn.time.threshold", "12h",
        new TimeValidator(TimeUnit.HOURS),
        "Age of table/partition's oldest aborted transaction when compaction / cleanup of aborted directories will be triggered. " +
        "Default time unit is: hours. Set to a negative number to disable."),

    HIVE_COMPACTOR_ACTIVE_DELTA_DIR_THRESHOLD("hive.compactor.active.delta.dir.threshold", 200,
        "If the number of active delta directories under a table/partition passes this threshold, a warning" +
        " message will be logged."),

    HIVE_COMPACTOR_OBSOLETE_DELTA_DIR_THRESHOLD("hive.compactor.obsolete.delta.dir.threshold", 200,
        "If the number of obsolete delta directories under a table/partition passes this threshold, a " +
            "warning message will be logged."),

    HIVE_COMPACTOR_SMALL_DELTA_DIR_THRESHOLD("hive.compactor.small.delta.dir.threshold", 200,
        "If the number of small delta directories under a table/partition passes this threshold, a " +
            "warning message will be logged."),

    HIVE_MERGE_COMPACTION_ENABLED("hive.compaction.merge.enabled", false,
            "Enables merge-based compaction which is a compaction optimization when few ORC delta files are present"),

    /**
     * @deprecated use MetastoreConf.METASTORE_DELTAMETRICS_LOGGER_FREQUENCY
     */
    @Deprecated
    HIVE_COMPACTOR_ACID_METRICS_LOGGER_FREQUENCY(
        "hive.compactor.acid.metrics.logger.frequency",
        "360m", new TimeValidator(TimeUnit.MINUTES),
        "Logging frequency of ACID related metrics. Set this value to 0 to completely turn off logging. " +
            "Default time unit: minutes"),

    HIVE_COMPACTOR_WAIT_TIMEOUT("hive.compactor.wait.timeout", 300000L, "Time out in "
        + "milliseconds for blocking compaction. It's value has to be higher than 2000 milliseconds. "),

    /**
     * @deprecated This config value is honoured by the MR based compaction only.
     * Use the {@link HiveConf.ConfVars#HIVE_COMPACTOR_GATHER_STATS}
     * config instead which is honoured by both the MR and Query based compaction.
     */
    @Deprecated
    HIVE_MR_COMPACTOR_GATHER_STATS("hive.mr.compactor.gather.stats", false, "If set to true MAJOR compaction " +
        "will gather stats if there are stats already associated with the table/partition.\n" +
        "Turn this off to save some resources and the stats are not used anyway.\n" +
        "Works only for MR based compaction, CRUD based compaction uses hive.stats.autogather."),

    HIVE_COMPACTOR_GATHER_STATS("hive.compactor.gather.stats", true, "If set to true MAJOR compaction " +
            "will gather stats if there are stats already associated with the table/partition.\n" +
            "Turn this off to save some resources and the stats are not used anyway.\n" +
            "This is a replacement for the HIVE_MR_COMPACTOR_GATHER_STATS config, and works both for MR and Query based " +
            "compaction."),

    /**
     * @deprecated Use MetastoreConf.COMPACTOR_INITIATOR_FAILED_THRESHOLD
     */
    @Deprecated
    COMPACTOR_INITIATOR_FAILED_THRESHOLD("hive.compactor.initiator.failed.compacts.threshold", 2,
      new RangeValidator(1, 20), "Number of consecutive compaction failures (per table/partition) " +
      "after which automatic compactions will not be scheduled any more.  Note that this must be less " +
      "than hive.compactor.history.retention.failed."),

    HIVE_COMPACTOR_CLEANER_RUN_INTERVAL("hive.compactor.cleaner.run.interval", "5000ms",
        new TimeValidator(TimeUnit.MILLISECONDS), "Time between runs of the cleaner thread"),
    HIVE_COMPACTOR_DELAYED_CLEANUP_ENABLED("hive.compactor.delayed.cleanup.enabled", false,
        "When enabled, cleanup of obsolete files/dirs after compaction can be delayed. This delay \n" +
            " can be configured by hive configuration hive.compactor.cleaner.retention.time.seconds"),
    HIVE_COMPACTOR_CLEANER_RETENTION_TIME("hive.compactor.cleaner.retention.time.seconds", "300s",
        new TimeValidator(TimeUnit.SECONDS), "Time to wait before cleanup of obsolete files/dirs after compaction. \n"
        + "This is the minimum amount of time the system will wait, since it will not clean before all open transactions are committed, that were opened before the compaction"),
    HIVE_COMPACTOR_CLEANER_THREADS_NUM("hive.compactor.cleaner.threads.num", 1,
      "Enables parallelization of the cleaning directories after compaction, that includes many file \n" +
      "related checks and may be expensive"),
    COMPACTOR_JOB_QUEUE("hive.compactor.job.queue", "", "Used to specify name of Hadoop queue to which\n" +
      "Compaction jobs will be submitted.  Set to empty string to let Hadoop choose the queue."),

    TRANSACTIONAL_CONCATENATE_NOBLOCK("hive.transactional.concatenate.noblock", false,
        "Will cause 'alter table T concatenate' to be non-blocking"),
    CONCATENATE_EXTERNAL_TABLE("hive.concatenate.external.table", false,
        "Enable concatenate for external tables. This allows 'alter table `tablename` concatenate' " +
            "on external tables."),

    HIVE_COMPACTOR_COMPACT_MM("hive.compactor.compact.insert.only", true,
        "Whether the compactor should compact insert-only tables. A safety switch."),
    COMPACTOR_CRUD_QUERY_BASED("hive.compactor.crud.query.based", false,
        "Means compaction on full CRUD tables is done via queries. "
        + "Compactions on insert-only tables will always run via queries regardless of the value of this configuration."),
    SPLIT_GROUPING_MODE("hive.split.grouping.mode", "query", new StringSet("query", "compactor"),
        "This is set to compactor from within the query based compactor. This enables the Tez SplitGrouper "
        + "to group splits based on their bucket number, so that all rows from different bucket files "
        + " for the same bucket number can end up in the same bucket file after the compaction."),
    /**
     * @deprecated Use MetastoreConf.COMPACTOR_HISTORY_RETENTION_SUCCEEDED
     */
    @Deprecated
    COMPACTOR_HISTORY_RETENTION_SUCCEEDED("hive.compactor.history.retention.succeeded", 3,
      new RangeValidator(0, 100), "Determines how many successful compaction records will be " +
      "retained in compaction history for a given table/partition."),
    /**
     * @deprecated Use MetastoreConf.COMPACTOR_HISTORY_RETENTION_FAILED
     */
    @Deprecated
    COMPACTOR_HISTORY_RETENTION_FAILED("hive.compactor.history.retention.failed", 3,
      new RangeValidator(0, 100), "Determines how many failed compaction records will be " +
      "retained in compaction history for a given table/partition."),
    /**
     * @deprecated Use MetastoreConf.ACID_HOUSEKEEPER_SERVICE_INTERVAL
     */
    @Deprecated
    COMPACTOR_HISTORY_REAPER_INTERVAL("hive.compactor.history.reaper.interval", "2m",
      new TimeValidator(TimeUnit.MILLISECONDS), "Determines how often compaction history reaper runs"),
    /**
     * @deprecated Use MetastoreConf.ACID_HOUSEKEEPER_SERVICE_INTERVAL
     */
    @Deprecated
    HIVE_TIMEDOUT_TXN_REAPER_INTERVAL("hive.timedout.txn.reaper.interval", "180s",
      new TimeValidator(TimeUnit.MILLISECONDS), "Time interval describing how often the reaper runs"),
    /**
     * @deprecated Use MetastoreConf.ACID_HOUSEKEEPER_SERVICE_INTERVAL
     */
    @Deprecated
    WRITE_SET_REAPER_INTERVAL("hive.writeset.reaper.interval", "60s",
      new TimeValidator(TimeUnit.MILLISECONDS), "Frequency of WriteSet reaper runs"),

    MERGE_CARDINALITY_VIOLATION_CHECK("hive.merge.cardinality.check", true,
      "Set to true to ensure that each SQL Merge statement ensures that for each row in the target\n" +
        "table there is at most 1 matching row in the source table per SQL Specification."),
    SPLIT_UPDATE("hive.split.update", true,
            "If true, SQL Update statement will be rewritten to a multi-insert with 2 branches:\n" +
                    "representing delete of existing row and an insert of the new version of the row.\n" +
                    "Similarly Merge statement will handle WHEN MATCHED UPDATE by splitting it into 2\n" +
                    "branches of a multi-insert. Updating bucketing and partitioning columns should\n" +
                    "only be permitted if this is true."),
    /**
     * @deprecated Use {@link ConfVars#SPLIT_UPDATE} instead.
     */
    OPTIMIZE_ACID_META_COLUMNS("hive.optimize.acid.meta.columns", true,
        "If true, don't decode Acid metadata columns from storage unless" +
        " they are needed."),

    // For Druid storage handler
    HIVE_DRUID_INDEXING_GRANULARITY("hive.druid.indexer.segments.granularity", "DAY",
            new PatternSet("YEAR", "MONTH", "WEEK", "DAY", "HOUR", "MINUTE", "SECOND"),
            "Granularity for the segments created by the Druid storage handler"
    ),
    HIVE_DRUID_MAX_PARTITION_SIZE("hive.druid.indexer.partition.size.max", 5000000,
            "Maximum number of records per segment partition"
    ),
    HIVE_DRUID_MAX_ROW_IN_MEMORY("hive.druid.indexer.memory.rownum.max", 75000,
            "Maximum number of records in memory while storing data in Druid"
    ),
    HIVE_DRUID_BROKER_DEFAULT_ADDRESS("hive.druid.broker.address.default", "localhost:8082",
            "Address of the Druid broker. If we are querying Druid from Hive, this address needs to be\n"
                    +
                    "declared"
    ),
    HIVE_DRUID_COORDINATOR_DEFAULT_ADDRESS("hive.druid.coordinator.address.default", "localhost:8081",
            "Address of the Druid coordinator. It is used to check the load status of newly created segments"
    ),
    HIVE_DRUID_OVERLORD_DEFAULT_ADDRESS("hive.druid.overlord.address.default", "localhost:8090",
        "Address of the Druid overlord. It is used to submit indexing tasks to druid."
    ),
    HIVE_DRUID_SELECT_THRESHOLD("hive.druid.select.threshold", 10000,
        "Takes only effect when hive.druid.select.distribute is set to false. \n" +
        "When we can split a Select query, this is the maximum number of rows that we try to retrieve\n" +
        "per query. In order to do that, we obtain the estimated size for the complete result. If the\n" +
        "number of records of the query results is larger than this threshold, we split the query in\n" +
        "total number of rows/threshold parts across the time dimension. Note that we assume the\n" +
        "records to be split uniformly across the time dimension."),
    HIVE_DRUID_NUM_HTTP_CONNECTION("hive.druid.http.numConnection", 20, "Number of connections used by\n" +
        "the HTTP client."),
    HIVE_DRUID_HTTP_READ_TIMEOUT("hive.druid.http.read.timeout", "PT1M", "Read timeout period for the HTTP\n" +
        "client in ISO8601 format (for example P2W, P3M, PT1H30M, PT0.750S), default is period of 1 minute."),
    HIVE_DRUID_SLEEP_TIME("hive.druid.sleep.time", "PT10S",
            "Sleep time between retries in ISO8601 format (for example P2W, P3M, PT1H30M, PT0.750S), default is period of 10 seconds."
    ),
    HIVE_DRUID_BASE_PERSIST_DIRECTORY("hive.druid.basePersistDirectory", "",
            "Local temporary directory used to persist intermediate indexing state, will default to JVM system property java.io.tmpdir."
    ),
    HIVE_DRUID_ROLLUP("hive.druid.rollup", true, "Whether to rollup druid rows or not."),
    DRUID_SEGMENT_DIRECTORY("hive.druid.storage.storageDirectory", "/druid/segments"
            , "druid deep storage location."),
    DRUID_METADATA_BASE("hive.druid.metadata.base", "druid", "Default prefix for metadata tables"),
    DRUID_METADATA_DB_TYPE("hive.druid.metadata.db.type", "mysql",
            new PatternSet("mysql", "postgresql", "derby"), "Type of the metadata database."
    ),
    DRUID_METADATA_DB_USERNAME("hive.druid.metadata.username", "",
            "Username to connect to Type of the metadata DB."
    ),
    DRUID_METADATA_DB_PASSWORD("hive.druid.metadata.password", "",
            "Password to connect to Type of the metadata DB."
    ),
    DRUID_METADATA_DB_URI("hive.druid.metadata.uri", "",
            "URI to connect to the database (for example jdbc:mysql://hostname:port/DBName)."
    ),
    DRUID_WORKING_DIR("hive.druid.working.directory", "/tmp/workingDirectory",
            "Default hdfs working directory used to store some intermediate metadata"
    ),
    HIVE_DRUID_MAX_TRIES("hive.druid.maxTries", 5, "Maximum number of retries before giving up"),
    HIVE_DRUID_PASSIVE_WAIT_TIME("hive.druid.passiveWaitTimeMs", 30000L,
            "Wait time in ms default to 30 seconds."
    ),
    HIVE_DRUID_BITMAP_FACTORY_TYPE("hive.druid.bitmap.type", "roaring", new PatternSet("roaring", "concise"), "Coding algorithm use to encode the bitmaps"),
    HIVE_DRUID_KERBEROS_ENABLE("hive.druid.kerberos.enable", true,
        "Enable/Disable Kerberos authentication explicitly while connecting to a druid cluster."),
    // For HBase storage handler
    HIVE_HBASE_WAL_ENABLED("hive.hbase.wal.enabled", true,
        "Whether writes to HBase should be forced to the write-ahead log. \n" +
        "Disabling this improves HBase write performance at the risk of lost writes in case of a crash."),
    HIVE_HBASE_GENERATE_HFILES("hive.hbase.generatehfiles", false,
        "True when HBaseStorageHandler should generate hfiles instead of operate against the online table."),
    HIVE_HBASE_SNAPSHOT_NAME("hive.hbase.snapshot.name", null, "The HBase table snapshot name to use."),
    HIVE_HBASE_SNAPSHOT_RESTORE_DIR("hive.hbase.snapshot.restoredir", "/tmp", "The directory in which to " +
        "restore the HBase table snapshot."),
    HIVE_SECURITY_HBASE_URLENCODE_AUTHORIZATION_URI("hive.security.hbase.urlencode.authorization.uri", false,
        "When true it URL encodes the URI generated by HBaseStorageHandler for authorization. The URI consists of the" +
        "HBase table name, column family, etc. and may contain characters that need encoding, such as #. If set to " +
        "true, the corresponding Ranger policies need to be in URL encoded format too."),
    HIVE_RANGER_USE_FULLY_QUALIFIED_URL("hive.ranger.use.fully.qualified.url", true, "When set to true,  fully " +
            "qualified path will be used to validate against ranger url policies. When set to false relative path is used." +
            "Cannot be modified at runtime."),
    // For Kudu storage handler
    HIVE_KUDU_MASTER_ADDRESSES_DEFAULT("hive.kudu.master.addresses.default", "localhost:7050",
        "Comma-separated list of all of the Kudu master addresses.\n" +
            "This value is only used for a given table if the kudu.master_addresses table property is not set."),

    // For har files
    HIVE_ARCHIVE_ENABLED("hive.archive.enabled", false, "Whether archiving operations are permitted"),

    HIVE_FETCH_TASK_CONVERSION("hive.fetch.task.conversion", "more", new StringSet("none", "minimal", "more"),
        "Some select queries can be converted to single FETCH task minimizing latency.\n" +
        "Currently the query should be single sourced not having any subquery and should not have\n" +
        "any aggregations or distincts (which incurs RS), lateral views and joins.\n" +
        "0. none : disable hive.fetch.task.conversion\n" +
        "1. minimal : SELECT STAR, FILTER on partition columns, LIMIT only\n" +
        "2. more    : SELECT, FILTER, LIMIT only (support TABLESAMPLE and virtual columns)"
    ),
    HIVE_FETCH_TASK_CACHING("hive.fetch.task.caching", true,
        "Enabling the caching of the result of fetch tasks eliminates the chance of running into a failing read." +
            " On the other hand, if enabled, the hive.fetch.task.conversion.threshold must be adjusted accordingly. That" +
            " is 200MB by default which must be lowered in case of enabled caching to prevent the consumption of too much memory."),
    HIVE_FETCH_TASK_CONVERSION_THRESHOLD("hive.fetch.task.conversion.threshold", 209715200L,
        "Input threshold for applying hive.fetch.task.conversion. If target table is native, input length\n" +
        "is calculated by summation of file lengths. If it's not native, storage handler for the table\n" +
        "can optionally implement org.apache.hadoop.hive.ql.metadata.InputEstimator interface."),

    HIVE_FETCH_TASK_AGGR("hive.fetch.task.aggr", false,
        "Aggregation queries with no group-by clause (for example, select count(*) from src) execute\n" +
        "final aggregations in single reduce task. If this is set true, Hive delegates final aggregation\n" +
        "stage to fetch task, possibly decreasing the query time."),

    HIVE_OPTIMIZE_METADATA_QUERIES("hive.compute.query.using.stats", true,
        "When set to true Hive will answer a few queries like count(1) purely using stats\n" +
        "stored in metastore. For basic stats collection turn on the config hive.stats.autogather to true.\n" +
        "For more advanced stats collection need to run analyze table queries."),

    // Serde for FetchTask
    HIVE_FETCH_OUTPUT_SERDE("hive.fetch.output.serde", "org.apache.hadoop.hive.serde2.DelimitedJSONSerDe",
        "The SerDe used by FetchTask to serialize the fetch output."),

    HIVE_EXPR_EVALUATION_CACHE("hive.cache.expr.evaluation", true,
        "If true, the evaluation result of a deterministic expression referenced twice or more\n" +
        "will be cached.\n" +
        "For example, in a filter condition like '.. where key + 10 = 100 or key + 10 = 0'\n" +
        "the expression 'key + 10' will be evaluated/cached once and reused for the following\n" +
        "expression ('key + 10 = 0'). Currently, this is applied only to expressions in select\n" +
        "or filter operators."),

    // Hive Variables
    HIVE_VARIABLE_SUBSTITUTE("hive.variable.substitute", true,
        "This enables substitution using syntax like ${var} ${system:var} and ${env:var}."),
    HIVE_VARIABLE_SUBSTITUTE_DEPTH("hive.variable.substitute.depth", 40,
        "The maximum replacements the substitution engine will do."),

    HIVE_CONF_VALIDATION("hive.conf.validation", true,
        "Enables type checking for registered Hive configurations"),

    SEMANTIC_ANALYZER_HOOK("hive.semantic.analyzer.hook", "", ""),
    HIVE_TEST_AUTHORIZATION_SQLSTD_HS2_MODE(
        "hive.test.authz.sstd.hs2.mode", false, "test hs2 mode from .q tests", true),
    HIVE_AUTHORIZATION_ENABLED("hive.security.authorization.enabled", false,
        "enable or disable the Hive client authorization"),
    HIVE_AUTHORIZATION_KERBEROS_USE_SHORTNAME("hive.security.authorization.kerberos.use.shortname", true,
        "use short name in Kerberos cluster"),
    HIVE_AUTHORIZATION_MANAGER("hive.security.authorization.manager",
        "org.apache.hadoop.hive.ql.security.authorization.plugin.sqlstd.SQLStdHiveAuthorizerFactory",
        "The Hive client authorization manager class name. The user defined authorization class should implement \n" +
        "interface org.apache.hadoop.hive.ql.security.authorization.HiveAuthorizationProvider."),
    HIVE_AUTHENTICATOR_MANAGER("hive.security.authenticator.manager",
        "org.apache.hadoop.hive.ql.security.HadoopDefaultAuthenticator",
        "hive client authenticator manager class name. The user defined authenticator should implement \n" +
        "interface org.apache.hadoop.hive.ql.security.HiveAuthenticationProvider."),
    HIVE_METASTORE_AUTHORIZATION_MANAGER("hive.security.metastore.authorization.manager",
        "org.apache.hadoop.hive.ql.security.authorization.DefaultHiveMetastoreAuthorizationProvider",
        "Names of authorization manager classes (comma separated) to be used in the metastore\n" +
        "for authorization. The user defined authorization class should implement interface\n" +
        "org.apache.hadoop.hive.ql.security.authorization.HiveMetastoreAuthorizationProvider.\n" +
        "All authorization manager classes have to successfully authorize the metastore API\n" +
        "call for the command execution to be allowed."),
    HIVE_METASTORE_AUTHORIZATION_AUTH_READS("hive.security.metastore.authorization.auth.reads", true,
        "If this is true, metastore authorizer authorizes read actions on database, table"),
    HIVE_METASTORE_AUTHENTICATOR_MANAGER("hive.security.metastore.authenticator.manager",
        "org.apache.hadoop.hive.ql.security.HadoopDefaultMetastoreAuthenticator",
        "authenticator manager class name to be used in the metastore for authentication. \n" +
        "The user defined authenticator should implement interface org.apache.hadoop.hive.ql.security.HiveAuthenticationProvider."),
    HIVE_AUTHORIZATION_TABLE_USER_GRANTS("hive.security.authorization.createtable.user.grants", "",
        "the privileges automatically granted to some users whenever a table gets created.\n" +
        "An example like \"userX,userY:select;userZ:create\" will grant select privilege to userX and userY,\n" +
        "and grant create privilege to userZ whenever a new table created."),
    HIVE_AUTHORIZATION_TABLE_GROUP_GRANTS("hive.security.authorization.createtable.group.grants",
        "",
        "the privileges automatically granted to some groups whenever a table gets created.\n" +
        "An example like \"groupX,groupY:select;groupZ:create\" will grant select privilege to groupX and groupY,\n" +
        "and grant create privilege to groupZ whenever a new table created."),
    HIVE_AUTHORIZATION_TABLE_ROLE_GRANTS("hive.security.authorization.createtable.role.grants", "",
        "the privileges automatically granted to some roles whenever a table gets created.\n" +
        "An example like \"roleX,roleY:select;roleZ:create\" will grant select privilege to roleX and roleY,\n" +
        "and grant create privilege to roleZ whenever a new table created."),
    HIVE_AUTHORIZATION_TABLE_OWNER_GRANTS("hive.security.authorization.createtable.owner.grants",
        "",
        "The privileges automatically granted to the owner whenever a table gets created.\n" +
        "An example like \"select,drop\" will grant select and drop privilege to the owner\n" +
        "of the table. Note that the default gives the creator of a table no access to the\n" +
        "table (but see HIVE-8067)."),
    HIVE_AUTHORIZATION_TASK_FACTORY("hive.security.authorization.task.factory",
        "org.apache.hadoop.hive.ql.parse.authorization.HiveAuthorizationTaskFactoryImpl",
        "Authorization DDL task factory implementation"),
    HIVE_AUTHORIZATION_TABLES_ON_STORAGEHANDLERS("hive.security.authorization.tables.on.storagehandlers", true,
        "Enables authorization on tables with custom storage handlers as implemented by HIVE-24705. " +
        "Default setting is true. Useful for turning the feature off if the corresponding ranger patch is missing."),
    HIVE_AUTHORIZATION_FUNCTIONS_IN_VIEW("hive.security.authorization.functions.in.view", true,
            "Enable authorization on functions/udfs used within a TABLE/VIEW during a select query. Default " +
         "setting is true When set to false, the udf used in the table/view will not be autiozed during the select query"),

    // if this is not set default value is set during config initialization
    // Default value can't be set in this constructor as it would refer names in other ConfVars
    // whose constructor would not have been called
    HIVE_AUTHORIZATION_SQL_STD_AUTH_CONFIG_WHITELIST(
        "hive.security.authorization.sqlstd.confwhitelist", "",
        "A Java regex. Configurations parameters that match this\n" +
        "regex can be modified by user when SQL standard authorization is enabled.\n" +
        "To get the default value, use the 'set <param>' command.\n" +
        "Note that the hive.conf.restricted.list checks are still enforced after the white list\n" +
        "check"),

    HIVE_AUTHORIZATION_SQL_STD_AUTH_CONFIG_WHITELIST_APPEND(
        "hive.security.authorization.sqlstd.confwhitelist.append", "",
        "2nd Java regex that it would match in addition to\n" +
        "hive.security.authorization.sqlstd.confwhitelist.\n" +
        "Do not include a starting \"|\" in the value. Using this regex instead\n" +
        "of updating the original regex means that you can append to the default\n" +
        "set by SQL standard authorization instead of replacing it entirely."),

    HIVE_CLI_PRINT_HEADER("hive.cli.print.header", false, "Whether to print the names of the columns in query output."),

    HIVE_CLI_PRINT_ESCAPE_CRLF("hive.cli.print.escape.crlf", false,
        "Whether to print carriage returns and line feeds in row output as escaped \\r and \\n"),

    HIVE_CLI_TEZ_INITIALIZE_SESSION("hive.cli.tez.initialize.session", true,
        "When enabled, CLI running with Tez will preemptively open a tez session during start up."),

    HIVE_CLI_TEZ_SESSION_ASYNC("hive.cli.tez.session.async", true, "Whether to start Tez\n" +
        "session in background when running CLI with Tez, allowing CLI to be available earlier. " +
        "If hive.cli.tez.initialize.session is set to false, this value is ignored."),

    HIVE_DISABLE_UNSAFE_EXTERNALTABLE_OPERATIONS("hive.disable.unsafe.external.table.operations", true,
        "Whether to disable certain optimizations and operations on external tables," +
        " on the assumption that data changes by external applications may have negative effects" +
        " on these operations."),

    HIVE_STRICT_MANAGED_TABLES("hive.strict.managed.tables", false,
            "Whether strict managed tables mode is enabled. With this mode enabled, " +
            "only transactional tables (both full and insert-only) are allowed to be created as managed tables"),

    HIVE_EXTERNALTABLE_PURGE_DEFAULT("hive.external.table.purge.default", false,
        "Set to true to set external.table.purge=true on newly created external tables," +
        " which will specify that the table data should be deleted when the table is dropped." +
        " Set to false maintain existing behavior that external tables do not delete data" +
        " when the table is dropped."),

    HIVE_ERROR_ON_EMPTY_PARTITION("hive.error.on.empty.partition", false,
        "Whether to throw an exception if dynamic partition insert generates empty results."),

    HIVE_EXIM_URI_SCHEME_WL("hive.exim.uri.scheme.whitelist", "hdfs,pfile,file,s3,s3a,gs,ofs,o3fs",
        "A comma separated list of acceptable URI schemes for import and export."),
    // temporary variable for testing. This is added just to turn off this feature in case of a bug in
    // deployment. It has not been documented in hive-default.xml intentionally, this should be removed
    // once the feature is stable
    HIVE_EXIM_RESTRICT_IMPORTS_INTO_REPLICATED_TABLES("hive.exim.strict.repl.tables",true,
        "Parameter that determines if 'regular' (non-replication) export dumps can be\n" +
        "imported on to tables that are the target of replication. If this parameter is\n" +
        "set, regular imports will check if the destination table(if it exists) has a " +
        "'repl.last.id' set on it. If so, it will fail."),
    HIVE_REPL_TASK_FACTORY("hive.repl.task.factory",
        "org.apache.hive.hcatalog.api.repl.exim.EximReplicationTaskFactory",
        "Parameter that can be used to override which ReplicationTaskFactory will be\n" +
        "used to instantiate ReplicationTask events. Override for third party repl plugins"),
    REPL_FILTER_TRANSACTIONS("hive.repl.filter.transactions", false,
            "Enable transaction event filtering to save dump space.\n" +
                    "When true, transactions are implicitly opened during REPL DUMP.\n" +
                    "The default setting is false"),
    HIVE_MAPPER_CANNOT_SPAN_MULTIPLE_PARTITIONS("hive.mapper.cannot.span.multiple.partitions", false, ""),
    HIVE_REWORK_MAPREDWORK("hive.rework.mapredwork", false,
        "should rework the mapred work or not.\n" +
        "This is first introduced by SymlinkTextInputFormat to replace symlink files with real paths at compile time."),
    HIVE_IO_EXCEPTION_HANDLERS("hive.io.exception.handlers", "",
        "A list of io exception handler class names. This is used\n" +
        "to construct a list exception handlers to handle exceptions thrown\n" +
        "by record readers"),

    // logging configuration
    HIVE_LOG4J_FILE("hive.log4j.file", "",
        "Hive log4j configuration file.\n" +
        "If the property is not set, then logging will be initialized using hive-log4j2.properties found on the classpath.\n" +
        "If the property is set, the value must be a valid URI (java.net.URI, e.g. \"file:///tmp/my-logging.xml\"), \n" +
        "which you can then extract a URL from and pass to PropertyConfigurator.configure(URL)."),
    HIVE_EXEC_LOG4J_FILE("hive.exec.log4j.file", "",
        "Hive log4j configuration file for execution mode(sub command).\n" +
        "If the property is not set, then logging will be initialized using hive-exec-log4j2.properties found on the classpath.\n" +
        "If the property is set, the value must be a valid URI (java.net.URI, e.g. \"file:///tmp/my-logging.xml\"), \n" +
        "which you can then extract a URL from and pass to PropertyConfigurator.configure(URL)."),
    HIVE_ASYNC_LOG_ENABLED("hive.async.log.enabled", true,
        "Whether to enable Log4j2's asynchronous logging. Asynchronous logging can give\n" +
        " significant performance improvement as logging will be handled in separate thread\n" +
        " that uses LMAX disruptor queue for buffering log messages.\n" +
        " Refer https://logging.apache.org/log4j/2.x/manual/async.html for benefits and\n" +
        " drawbacks."),

    HIVE_LOG_EXPLAIN_OUTPUT("hive.log.explain.output", false,
        "Whether to log explain output for every query.\n"
            + "When enabled, will log EXPLAIN EXTENDED output for the query at INFO log4j log level."),
    HIVE_LOG_EXPLAIN_OUTPUT_TO_CONSOLE("hive.log.explain.output.to.console", false,
        "Weather to make output from hive.log.explain.output log " +
            "to console instead of normal logger"),
    HIVE_LOG_EXPLAIN_OUTPUT_INCLUDE_EXTENDED("hive.log.explain.output.include.extended", true,
        "Weather to include details in explain printed from hive.log.explain.output"),
    HIVE_EXPLAIN_USER("hive.explain.user", true,
        "Whether to show explain result at user level.\n" +
        "When enabled, will log EXPLAIN output for the query at user level. Tez only."),
    HIVE_EXPLAIN_NODE_VISIT_LIMIT("hive.explain.node.visit.limit", 256, new RangeValidator(1, Integer.MAX_VALUE),
        "Maximum number of times an operator/node can be visited during the construction of the EXPLAIN "
            + "output; an error is thrown when the limit is reached. In some cases, the EXPLAIN statement visits (and "
            + "prints) the same node multiple times. The number of visits can become exponential and make the server "
            + "crash or become unresponsive so this limit acts as a safety net to fail-fast the problematic query and "
            + "avoid bringing down the entire server."),
    // prefix used to auto generated column aliases (this should be started with '_')
    HIVE_AUTOGEN_COLUMNALIAS_PREFIX_LABEL("hive.autogen.columnalias.prefix.label", "_c",
        "String used as a prefix when auto generating column alias.\n" +
        "By default the prefix label will be appended with a column position number to form the column alias. \n" +
        "Auto generation would happen if an aggregate function is used in a select clause without an explicit alias."),
    HIVE_AUTOGEN_COLUMNALIAS_PREFIX_INCLUDEFUNCNAME(
        "hive.autogen.columnalias.prefix.includefuncname", false,
        "Whether to include function name in the column alias auto generated by Hive."),
    HIVE_METRICS_CLASS("hive.service.metrics.class",
        "org.apache.hadoop.hive.common.metrics.metrics2.CodahaleMetrics",
        new StringSet(
            "org.apache.hadoop.hive.common.metrics.metrics2.CodahaleMetrics",
            "org.apache.hadoop.hive.common.metrics.LegacyMetrics"),
        "Hive metrics subsystem implementation class."),
    HIVE_CODAHALE_METRICS_REPORTER_CLASSES("hive.service.metrics.codahale.reporter.classes",
        "org.apache.hadoop.hive.common.metrics.metrics2.JsonFileMetricsReporter, " +
            "org.apache.hadoop.hive.common.metrics.metrics2.JmxMetricsReporter",
            "Comma separated list of reporter implementation classes for metric class "
                + "org.apache.hadoop.hive.common.metrics.metrics2.CodahaleMetrics. Overrides "
                + "HIVE_METRICS_REPORTER conf if present"),
    @Deprecated
    HIVE_METRICS_REPORTER("hive.service.metrics.reporter", "",
        "Reporter implementations for metric class "
            + "org.apache.hadoop.hive.common.metrics.metrics2.CodahaleMetrics;" +
        "Deprecated, use HIVE_CODAHALE_METRICS_REPORTER_CLASSES instead. This configuration will be"
            + " overridden by HIVE_CODAHALE_METRICS_REPORTER_CLASSES if present. " +
            "Comma separated list of JMX, CONSOLE, JSON_FILE, HADOOP2"),
    HIVE_METRICS_JSON_FILE_LOCATION("hive.service.metrics.file.location", "/tmp/report.json",
        "For metric class org.apache.hadoop.hive.common.metrics.metrics2.CodahaleMetrics JSON_FILE reporter, the location of local JSON metrics file.  " +
        "This file will get overwritten at every interval."),
    HIVE_METRICS_JSON_FILE_INTERVAL("hive.service.metrics.file.frequency", "5000ms",
        new TimeValidator(TimeUnit.MILLISECONDS),
        "For metric class org.apache.hadoop.hive.common.metrics.metrics2.JsonFileMetricsReporter, " +
        "the frequency of updating JSON metrics file."),
    HIVE_METRICS_HADOOP2_INTERVAL("hive.service.metrics.hadoop2.frequency", "30s",
        new TimeValidator(TimeUnit.SECONDS),
        "For metric class org.apache.hadoop.hive.common.metrics.metrics2.Metrics2Reporter, " +
        "the frequency of updating the HADOOP2 metrics system."),
    HIVE_METRICS_HADOOP2_COMPONENT_NAME("hive.service.metrics.hadoop2.component",
        "hive",
        "Component name to provide to Hadoop2 Metrics system. Ideally 'hivemetastore' for the MetaStore " +
        " and and 'hiveserver2' for HiveServer2."
        ),
    HIVE_PERF_LOGGER("hive.exec.perf.logger", "org.apache.hadoop.hive.ql.log.PerfLogger",
        "The class responsible for logging client side performance metrics. \n" +
        "Must be a subclass of org.apache.hadoop.hive.ql.log.PerfLogger"),
    HIVE_START_CLEANUP_SCRATCHDIR("hive.start.cleanup.scratchdir", false,
        "To cleanup the Hive scratchdir when starting the Hive Server"),
    HIVE_SCRATCH_DIR_LOCK("hive.scratchdir.lock", false,
        "To hold a lock file in scratchdir to prevent to be removed by cleardanglingscratchdir"),
    HIVE_INSERT_INTO_MULTILEVEL_DIRS("hive.insert.into.multilevel.dirs", false,
        "Where to insert into multilevel directories like\n" +
        "\"insert directory '/HIVEFT25686/china/' from table\""),
    HIVE_CTAS_EXTERNAL_TABLES("hive.ctas.external.tables", true,
            "whether CTAS for external tables is allowed"),
    HIVE_INSERT_INTO_EXTERNAL_TABLES("hive.insert.into.external.tables", true,
        "whether insert into external tables is allowed"),
    HIVE_TEMPORARY_TABLE_STORAGE(
        "hive.exec.temporary.table.storage", "default", new StringSet("memory",
         "ssd", "default"), "Define the storage policy for temporary tables." +
         "Choices between memory, ssd and default"),
    HIVE_QUERY_LIFETIME_HOOKS("hive.query.lifetime.hooks", "",
        "A comma separated list of hooks which implement QueryLifeTimeHook. These will be triggered" +
            " before/after query compilation and before/after query execution, in the order specified." +
        "Implementations of QueryLifeTimeHookWithParseHooks can also be specified in this list. If they are" +
        "specified then they will be invoked in the same places as QueryLifeTimeHooks and will be invoked during pre " +
         "and post query parsing"),
    HIVE_DRIVER_RUN_HOOKS("hive.exec.driver.run.hooks", "",
        "A comma separated list of hooks which implement HiveDriverRunHook. Will be run at the beginning " +
        "and end of Driver.run, these will be run in the order specified."),
    HIVE_DDL_OUTPUT_FORMAT("hive.ddl.output.format", null,
        "The data format to use for DDL output.  One of \"text\" (for human\n" +
        "readable text) or \"json\" (for a json object)."),
    HIVE_ENTITY_SEPARATOR("hive.entity.separator", "@",
        "Separator used to construct names of tables and partitions. For example, dbname@tablename@partitionname"),
    HIVE_CAPTURE_TRANSFORM_ENTITY("hive.entity.capture.transform", false,
        "Compiler to capture transform URI referred in the query"),
    HIVE_DISPLAY_PARTITION_COLUMNS_SEPARATELY("hive.display.partition.cols.separately", true,
        "In older Hive version (0.10 and earlier) no distinction was made between\n" +
        "partition columns or non-partition columns while displaying columns in describe\n" +
        "table. From 0.12 onwards, they are displayed separately. This flag will let you\n" +
        "get old behavior, if desired. See, test-case in patch for HIVE-6689."),
    @Deprecated
    HIVE_LINEAGE_INFO("hive.lineage.hook.info.enabled", false,
        "Whether Hive provides lineage information to hooks." +
            "Deprecated: use hive.lineage.statement.filter instead."),
    HIVE_LINEAGE_STATEMENT_FILTER("hive.lineage.statement.filter", "NONE",
        "Whether Hive provides lineage information to hooks for the specified statements only, " +
            "the value is a comma-separated list (ex.: CREATE_MATERIALIZED_VIEW," +
            "CREATE_TABLE,CREATE_TABLE_AS_SELECT). Possible values are: CREATE_TABLE, CREATE_TABLE_AS_SELECT, " +
            "CREATE_VIEW, CREATE_MATERIALIZED_VIEW, LOAD, QUERY, ALL, NONE." +
            " ALL means lineage information is always provided, NONE and empty string means never."),

    HIVE_SSL_PROTOCOL_BLACKLIST("hive.ssl.protocol.blacklist", "SSLv2,SSLv3",
        "SSL Versions to disable for all Hive Servers"),

    HIVE_PRIVILEGE_SYNCHRONIZER("hive.privilege.synchronizer", false,
            "Whether to synchronize privileges from external authorizer periodically in HS2"),
    HIVE_PRIVILEGE_SYNCHRONIZER_INTERVAL("hive.privilege.synchronizer.interval",
        "1800s", new TimeValidator(TimeUnit.SECONDS),
        "Interval to synchronize privileges from external authorizer periodically in HS2"),
    HIVE_DATETIME_FORMATTER("hive.datetime.formatter", "DATETIME",
        new StringSet("DATETIME", "SIMPLE"),
        "The formatter to use for handling datetime values. The possible values are:\n" +
        " * DATETIME: For using java.time.format.DateTimeFormatter\n" +
        " * SIMPLE: For using java.text.SimpleDateFormat (known bugs: HIVE-25458, HIVE-25403, HIVE-25268)\n" +
        "Currently the configuration only affects the behavior of the following SQL functions:\n" +
        " * unix_timestamp(string,[string])\n" + 
        " * from_unixtime\n" + 
        " * date_format\n\n" +
        "The SIMPLE formatter exists purely for compatibility purposes with previous versions of Hive thus its use " +
        "is discouraged. It suffers from known bugs that are unlikely to be fixed in subsequent versions of the product." +
        "Furthermore, using SIMPLE formatter may lead to strange behavior, and unexpected results when combined " +
        "with SQL functions/operators that are using the new DATETIME formatter."),
    HIVE_DATETIME_RESOLVER_STYLE("hive.datetime.formatter.resolver.style", "SMART",
        new StringSet("SMART", "STRICT", "LENIENT"),
        "The style used by the hive.datetime.formatter (only applicable to DATETIME) to resolve dates amd times." +
        "The possible values are STRICT, SMART, and LENIENT and their behavior follows the java.time.format.ResolverStyle API."),
     // HiveServer2 specific configs
    HIVE_SERVER2_CLEAR_DANGLING_SCRATCH_DIR("hive.server2.clear.dangling.scratchdir", false,
        "Clear dangling scratch dir periodically in HS2"),
    HIVE_SERVER2_CLEAR_DANGLING_SCRATCH_DIR_INTERVAL("hive.server2.clear.dangling.scratchdir.interval",
        "1800s", new TimeValidator(TimeUnit.SECONDS),
        "Interval to clear dangling scratch dir periodically in HS2"),
    HIVE_SERVER2_SLEEP_INTERVAL_BETWEEN_START_ATTEMPTS("hive.server2.sleep.interval.between.start.attempts",
        "60s", new TimeValidator(TimeUnit.MILLISECONDS, 0l, true, Long.MAX_VALUE, true),
        "Amount of time to sleep between HiveServer2 start attempts. Primarily meant for tests"),
    HIVE_SERVER2_MAX_START_ATTEMPTS("hive.server2.max.start.attempts", 30L, new RangeValidator(0L, null),
        "Number of times HiveServer2 will attempt to start before exiting. The sleep interval between retries" +
        " is determined by " + ConfVars.HIVE_SERVER2_SLEEP_INTERVAL_BETWEEN_START_ATTEMPTS.varname +
        "\n The default of 30 will keep trying for 30 minutes."),
    HIVE_SERVER2_SUPPORT_DYNAMIC_SERVICE_DISCOVERY("hive.server2.support.dynamic.service.discovery", false,
        "Whether HiveServer2 supports dynamic service discovery for its clients. " +
        "To support this, each instance of HiveServer2 currently uses ZooKeeper to register itself, " +
        "when it is brought up. JDBC/ODBC clients should use the ZooKeeper ensemble: " +
        "hive.zookeeper.quorum in their connection string."),
    HIVE_SERVER2_ZOOKEEPER_NAMESPACE("hive.server2.zookeeper.namespace", "hiveserver2",
        "The parent node in ZooKeeper used by HiveServer2 when supporting dynamic service discovery."),
    HIVE_SERVER2_ZOOKEEPER_PUBLISH_CONFIGS("hive.server2.zookeeper.publish.configs", true,
        "Whether we should publish HiveServer2's configs to ZooKeeper."),
    HIVE_SERVER2_TRUSTED_PROXY_TRUSTHEADER("hive.server2.proxy.trustheader", "", "This config " +
            "indicates whether the connection is authenticated before the requests lands on HiveServer2, So that we can" +
            "avoid the authentication is again in HS2. Default value is empty, if it's value is set to some header say " +
            "'X-Trusted-Proxy-Auth-Header' then we need to look for this header in the connection string, if present " +
            "we directly extract the client name from header."),

    // HiveServer2 global init file location
    HIVE_SERVER2_GLOBAL_INIT_FILE_LOCATION("hive.server2.global.init.file.location", "${env:HIVE_CONF_DIR}",
        "Either the location of a HS2 global init file or a directory containing a .hiverc file. If the \n" +
        "property is set, the value must be a valid path to an init file or directory where the init file is located."),
    HIVE_SERVER2_TRANSPORT_MODE("hive.server2.transport.mode",
        HiveServer2TransportMode.binary.toString(),
        new StringSet(HiveServer2TransportMode.binary.toString(),
            HiveServer2TransportMode.http.toString(), HiveServer2TransportMode.all.toString()),
        "Transport mode of HiveServer2."),
    HIVE_SERVER2_THRIFT_BIND_HOST("hive.server2.thrift.bind.host", "",
        "Bind host on which to run the HiveServer2 Thrift service."),
    HIVE_SERVER2_PARALLEL_COMPILATION("hive.driver.parallel.compilation", false, "Whether to\n" +
        "enable parallel compilation of the queries between sessions and within the same session on HiveServer2. The default is false."),
    HIVE_SERVER2_PARALLEL_COMPILATION_LIMIT("hive.driver.parallel.compilation.global.limit", -1, "Determines the " +
        "degree of parallelism for queries compilation between sessions on HiveServer2. The default is -1."),
    HIVE_SERVER2_COMPILE_LOCK_TIMEOUT("hive.server2.compile.lock.timeout", "0s",
        new TimeValidator(TimeUnit.SECONDS),
        "Number of seconds a request will wait to acquire the compile lock before giving up. " +
        "Setting it to 0s disables the timeout."),
    HIVE_SERVER2_PARALLEL_OPS_IN_SESSION("hive.server2.parallel.ops.in.session", true,
        "Whether to allow several parallel operations (such as SQL statements) in one session."),
    HIVE_SERVER2_MATERIALIZED_VIEWS_REGISTRY_IMPL("hive.server2.materializedviews.registry.impl", "DEFAULT",
        new StringSet("DEFAULT", "DUMMY"),
        "The implementation that we should use for the materialized views registry. \n" +
        "  DEFAULT: Default cache for materialized views\n" +
        "  DUMMY: Do not cache materialized views and hence forward requests to metastore"),
    HIVE_SERVER2_MATERIALIZED_VIEWS_REGISTRY_REFRESH("hive.server2.materializedviews.registry.refresh.period", "1500s",
        new TimeValidator(TimeUnit.SECONDS),
        "Period, specified in seconds, between successive refreshes of the registry to pull new materializations " +
        "from the metastore that may have been created by other HS2 instances."),

    // HiveServer2 WebUI
    HIVE_SERVER2_WEBUI_BIND_HOST("hive.server2.webui.host", "0.0.0.0", "The host address the HiveServer2 WebUI will listen on"),
    HIVE_SERVER2_WEBUI_PORT("hive.server2.webui.port", 10002, "The port the HiveServer2 WebUI will listen on. This can be"
        + "set to 0 or a negative integer to disable the web UI"),
    HIVE_SERVER2_WEBUI_MAX_THREADS("hive.server2.webui.max.threads", 50, "The max HiveServer2 WebUI threads"),
    HIVE_SERVER2_WEBUI_USE_SSL("hive.server2.webui.use.ssl", false,
        "Set this to true for using SSL encryption for HiveServer2 WebUI."),
    HIVE_SERVER2_WEBUI_SSL_KEYSTORE_PATH("hive.server2.webui.keystore.path", "",
        "SSL certificate keystore location for HiveServer2 WebUI."),
    HIVE_SERVER2_WEBUI_SSL_KEYSTORE_PASSWORD("hive.server2.webui.keystore.password", "",
        "SSL certificate keystore password for HiveServer2 WebUI."),
    HIVE_SERVER2_WEBUI_SSL_KEYSTORE_TYPE("hive.server2.webui.keystore.type", "",
        "SSL certificate keystore type for HiveServer2 WebUI."),
    HIVE_SERVER2_WEBUI_SSL_EXCLUDE_CIPHERSUITES("hive.server2.webui.exclude.ciphersuites", "",
        "SSL a list of exclude cipher suite names or regular expressions separated by comma"
        + " for HiveServer2 WebUI."),
    HIVE_SERVER2_WEBUI_SSL_KEYMANAGERFACTORY_ALGORITHM("hive.server2.webui.keymanagerfactory.algorithm",
        "","SSL certificate key manager factory algorithm for HiveServer2 WebUI."),
    HIVE_SERVER2_WEBUI_USE_SPNEGO("hive.server2.webui.use.spnego", false,
        "If true, the HiveServer2 WebUI will be secured with SPNEGO. Clients must authenticate with Kerberos."),
    HIVE_SERVER2_WEBUI_SPNEGO_KEYTAB("hive.server2.webui.spnego.keytab", "",
        "The path to the Kerberos Keytab file containing the HiveServer2 WebUI SPNEGO service principal."),
    HIVE_SERVER2_WEBUI_SPNEGO_PRINCIPAL("hive.server2.webui.spnego.principal",
        "HTTP/_HOST@EXAMPLE.COM", "The HiveServer2 WebUI SPNEGO service principal.\n" +
        "The special string _HOST will be replaced automatically with \n" +
        "the value of hive.server2.webui.host or the correct host name."),
    HIVE_SERVER2_WEBUI_MAX_HISTORIC_QUERIES("hive.server2.webui.max.historic.queries", 25,
        "The maximum number of past queries to show in HiverSever2 WebUI."),
    HIVE_SERVER2_WEBUI_USE_PAM("hive.server2.webui.use.pam", false,
        "If true, the HiveServer2 WebUI will be secured with PAM."),
    HIVE_SERVER2_WEBUI_EXPLAIN_OUTPUT("hive.server2.webui.explain.output", false,
        "When set to true, the EXPLAIN output for every query is displayed"
            + " in the HS2 WebUI / Drilldown / Query Plan tab.\n"),
    HIVE_SERVER2_WEBUI_SHOW_GRAPH("hive.server2.webui.show.graph", false,
        "Set this to true to to display query plan as a graph instead of text in the WebUI. " +
        "Only works with hive.server2.webui.explain.output set to true."),
    HIVE_SERVER2_WEBUI_MAX_GRAPH_SIZE("hive.server2.webui.max.graph.size", 25,
        "Max number of stages graph can display. If number of stages exceeds this, no query" +
        "plan will be shown. Only works when hive.server2.webui.show.graph and " +
        "hive.server2.webui.explain.output set to true."),
    HIVE_SERVER2_WEBUI_SHOW_STATS("hive.server2.webui.show.stats", false,
        "Set this to true to to display statistics for MapReduce tasks in the WebUI. " +
        "Only works when hive.server2.webui.show.graph and hive.server2.webui.explain.output " +
        "set to true."),
    HIVE_SERVER2_WEBUI_ENABLE_CORS("hive.server2.webui.enable.cors", false,
      "Whether to enable cross origin requests (CORS)\n"),
    HIVE_SERVER2_WEBUI_CORS_ALLOWED_ORIGINS("hive.server2.webui.cors.allowed.origins", "*",
      "Comma separated list of origins that are allowed when CORS is enabled.\n"),
    HIVE_SERVER2_WEBUI_CORS_ALLOWED_METHODS("hive.server2.webui.cors.allowed.methods", "GET,POST,DELETE,HEAD",
      "Comma separated list of http methods that are allowed when CORS is enabled.\n"),
    HIVE_SERVER2_WEBUI_CORS_ALLOWED_HEADERS("hive.server2.webui.cors.allowed.headers",
      "X-Requested-With,Content-Type,Accept,Origin",
      "Comma separated list of http headers that are allowed when CORS is enabled.\n"),
    HIVE_SERVER2_WEBUI_XFRAME_ENABLED("hive.server2.webui.xframe.enabled", true,
            "Whether to enable xframe\n"),
    HIVE_SERVER2_WEBUI_XFRAME_VALUE("hive.server2.webui.xframe.value", "SAMEORIGIN",
            "Configuration to allow the user to set the x_frame-options value\n"),
    HIVE_SERVER2_WEBUI_HTTP_COOKIE_MAX_AGE("hive.server2.ui.http.cookie.max.age", "86400s",
        new TimeValidator(TimeUnit.SECONDS),
        "Maximum age in seconds for server side cookie used by HS2 in HTTP mode."),
    HIVE_SERVER2_WEBUI_HTTP_COOKIE_DOMAIN("hive.server2.ui.http.cookie.domain", null,
        "Domain for the HS2 generated cookies"),
    HIVE_SERVER2_WEBUI_HTTP_COOKIE_PATH("hive.server2.ui.http.cookie.path", null,
        "Path for the HS2 generated cookies"),
    HIVE_SERVER2_WEBUI_AUTH_METHOD("hive.server2.webui.auth.method", "NONE",
        new StringSet("NONE", "LDAP"),
        "HS2 WebUI authentication method available to clients to be set at session level.\n" +
            "  NONE: No authentication\n" +
            "  LDAP" ),
    HIVE_SERVER2_SHOW_OPERATION_DRILLDOWN_LINK("hive.server2.show.operation.drilldown.link", false,
        "Whether to show the operation's drilldown link to thrift client.\n"),


    // Tez session settings
    HIVE_SERVER2_ACTIVE_PASSIVE_HA_ENABLE("hive.server2.active.passive.ha.enable", false,
      "Whether HiveServer2 Active/Passive High Availability be enabled when Hive Interactive sessions are enabled." +
        "This will also require hive.server2.support.dynamic.service.discovery to be enabled."),
    HIVE_SERVER2_ACTIVE_PASSIVE_HA_REGISTRY_NAMESPACE("hive.server2.active.passive.ha.registry.namespace",
      "hs2ActivePassiveHA",
      "When HiveServer2 Active/Passive High Availability is enabled, uses this namespace for registering HS2\n" +
        "instances with zookeeper"),
    HIVE_SERVER2_ACTIVE_PASSIVE_HA_HEALTHCHECK_PORT("hive.server2.active.passive.ha.healthcheck.port", 11002, 
        "The port the HiveServer2 ha-healthcheck web app will listen on"),
    HIVE_SERVER2_TEZ_INTERACTIVE_QUEUE("hive.server2.tez.interactive.queue", "",
        "A single YARN queues to use for Hive Interactive sessions. When this is specified,\n" +
        "workload management is enabled and used for these sessions."),
    HIVE_SERVER2_WM_NAMESPACE("hive.server2.wm.namespace", "default",
        "The WM namespace to use when one metastore is used by multiple compute clusters each \n" +
        "with their own workload management. The special value 'default' (the default) will \n" +
        "also include any resource plans created before the namespaces were introduced."),
    HIVE_SERVER2_WM_WORKER_THREADS("hive.server2.wm.worker.threads", 4,
        "Number of worker threads to use to perform the synchronous operations with Tez\n" +
        "sessions for workload management (e.g. opening, closing, etc.)"),
    HIVE_SERVER2_WM_ALLOW_ANY_POOL_VIA_JDBC("hive.server2.wm.allow.any.pool.via.jdbc", false,
        "Applies when a user specifies a target WM pool in the JDBC connection string. If\n" +
        "false, the user can only specify a pool he is mapped to (e.g. make a choice among\n" +
        "multiple group mappings); if true, the user can specify any existing pool."),
    HIVE_SERVER2_WM_POOL_METRICS("hive.server2.wm.pool.metrics", true,
        "Whether per-pool WM metrics should be enabled."),
    HIVE_SERVER2_TEZ_WM_AM_REGISTRY_TIMEOUT("hive.server2.tez.wm.am.registry.timeout", "30s",
        new TimeValidator(TimeUnit.SECONDS),
        "The timeout for AM registry registration, after which (on attempting to use the\n" +
        "session), we kill it and try to get another one."),
    HIVE_SERVER2_WM_DELAYED_MOVE("hive.server2.wm.delayed.move", false,
        "Determines behavior of the wm move trigger when destination pool is full.\n" +
        "If true, the query will run in source pool as long as possible if destination pool is full;\n" +
        "if false, the query will be killed if destination pool is full."),
    HIVE_SERVER2_WM_DELAYED_MOVE_TIMEOUT("hive.server2.wm.delayed.move.timeout", "3600",
        new TimeValidator(TimeUnit.SECONDS),
        "The amount of time a delayed move is allowed to run in the source pool,\n" +
        "when a delayed move session times out, the session is moved to the destination pool.\n" +
        "A value of 0 indicates no timeout"),
    HIVE_SERVER2_WM_DELAYED_MOVE_VALIDATOR_INTERVAL("hive.server2.wm.delayed.move.validator.interval", "60",
        new TimeValidator(TimeUnit.SECONDS),
        "Interval for checking for expired delayed moves."),
    HIVE_SERVER2_TEZ_DEFAULT_QUEUES("hive.server2.tez.default.queues", "",
        "A list of comma separated values corresponding to YARN queues of the same name.\n" +
        "When HiveServer2 is launched in Tez mode, this configuration needs to be set\n" +
        "for multiple Tez sessions to run in parallel on the cluster."),
    HIVE_SERVER2_TEZ_SESSIONS_PER_DEFAULT_QUEUE("hive.server2.tez.sessions.per.default.queue", 1,
        "A positive integer that determines the number of Tez sessions that should be\n" +
        "launched on each of the queues specified by \"hive.server2.tez.default.queues\".\n" +
        "Determines the parallelism on each queue."),
    HIVE_SERVER2_TEZ_INITIALIZE_DEFAULT_SESSIONS("hive.server2.tez.initialize.default.sessions",
        true,
        "This flag is used in HiveServer2 to enable a user to use HiveServer2 without\n" +
        "turning on Tez for HiveServer2. The user could potentially want to run queries\n" +
        "over Tez without the pool of sessions."),
    HIVE_SERVER2_TEZ_SESSIONS_METRICS_COLLECTION_INTERVAL(
        "hive.server2.tez.sessions.metrics.collection.interval", "10s",
        new TimeValidator(TimeUnit.SECONDS),
        "Interval for collecting metrics from Tez sessions."),
    HIVE_SERVER2_TEZ_QUEUE_ACCESS_CHECK("hive.server2.tez.queue.access.check", false,
        "Whether to check user access to explicitly specified YARN queues. " +
          "yarn.resourcemanager.webapp.address must be configured to use this."),
    HIVE_SERVER2_TEZ_SESSION_LIFETIME("hive.server2.tez.session.lifetime", "162h",
        new TimeValidator(TimeUnit.HOURS),
        "The lifetime of the Tez sessions launched by HS2 when default sessions are enabled.\n" +
        "Set to 0 to disable session expiration."),
    HIVE_SERVER2_TEZ_SESSION_LIFETIME_JITTER("hive.server2.tez.session.lifetime.jitter", "3h",
        new TimeValidator(TimeUnit.HOURS),
        "The jitter for Tez session lifetime; prevents all the sessions from restarting at once."),
    HIVE_SERVER2_TEZ_SESSION_MAX_INIT_THREADS("hive.server2.tez.sessions.init.threads", 16,
        "If hive.server2.tez.initialize.default.sessions is enabled, the maximum number of\n" +
        "threads to use to initialize the default sessions."),
    HIVE_SERVER2_TEZ_SESSION_RESTRICTED_CONFIGS("hive.server2.tez.sessions.restricted.configs", "",
    "The configuration settings that cannot be set when submitting jobs to HiveServer2. If\n" +
    "any of these are set to values different from those in the server configuration, an\n" +
    "exception will be thrown."),
    HIVE_SERVER2_TEZ_SESSION_CUSTOM_QUEUE_ALLOWED("hive.server2.tez.sessions.custom.queue.allowed",
      "true", new StringSet("true", "false", "ignore"),
      "Whether Tez session pool should allow submitting queries to custom queues. The options\n" +
      "are true, false (error out), ignore (accept the query but ignore the queue setting)."),
    HIVE_MAPRED_JOB_FOLLOW_TEZ_QUEUE("hive.mapred.job.follow.tez.queue", false,
        "Whether the MR jobs initiated by a query should be enforced to run in the queue denoted by "
            + "'tez.queue.name', e.g. DistCp jobs."),

    // Operation log configuration
    HIVE_SERVER2_LOGGING_OPERATION_ENABLED("hive.server2.logging.operation.enabled", true,
        "When true, HS2 will save operation logs and make them available for clients"),
    HIVE_SERVER2_LOGGING_OPERATION_LOG_LOCATION("hive.server2.logging.operation.log.location",
        "${system:java.io.tmpdir}" + File.separator + "${system:user.name}" + File.separator +
            "operation_logs",
        "Top level directory where operation logs are stored if logging functionality is enabled"),
    HIVE_SERVER2_LOGGING_OPERATION_LEVEL("hive.server2.logging.operation.level", "EXECUTION",
        new StringSet("NONE", "EXECUTION", "PERFORMANCE", "VERBOSE"),
        "HS2 operation logging mode available to clients to be set at session level.\n" +
        "For this to work, hive.server2.logging.operation.enabled should be set to true.\n" +
        "  NONE: Ignore any logging\n" +
        "  EXECUTION: Log completion of tasks\n" +
        "  PERFORMANCE: Execution + Performance logs \n" +
        "  VERBOSE: All logs" ),
    HIVE_SERVER2_OPERATION_LOG_CLEANUP_DELAY("hive.server2.operation.log.cleanup.delay", "300s",
      new TimeValidator(TimeUnit.SECONDS), "When a query is cancelled (via kill query, query timeout or triggers),\n" +
      " operation logs gets cleaned up after this delay"),
    HIVE_SERVER2_OPERATION_LOG_PURGEPOLICY_TIMETOLIVE("hive.server2.operation.log.purgePolicy.timeToLive",
        "60s", new TimeValidator(TimeUnit.SECONDS), 
        "Number of seconds the appender, which has been dynamically created by Log4J framework for the " + 
        "operation log, should survive without having any events sent to it. For more details, check " + 
        "Log4J's IdlePurgePolicy."),
    HIVE_SERVER2_HISTORIC_OPERATION_LOG_ENABLED("hive.server2.historic.operation.log.enabled", false,
        "Keep the operation log for some time until the operation's query info is evicted from QueryInfoCache."),
    HIVE_SERVER2_HISTORIC_OPERATION_LOG_CHECK_INTERVAL("hive.server2.historic.operation.log.check.interval", "15m",
        new TimeValidator(TimeUnit.MILLISECONDS, 3000l, true, null, false),
        "The check interval for cleaning up the historic operation log and session dirs, " +
          "which should be used only if hive.server2.historic.operation.log.enabled is enabled."),
    HIVE_SERVER2_HISTORIC_OPERATION_LOG_FETCH_MAXBYTES("hive.server2.operation.log.fetch.maxBytes", "4Mb",
        new SizeValidator(1L, true, (long)Integer.MAX_VALUE, false),
        "The buffer size for fetching the operation log, " +
          "which should be used only if hive.server2.historic.operation.log.enabled is enabled."),

    // HS2 connections guard rails
    HIVE_SERVER2_LIMIT_CONNECTIONS_PER_USER("hive.server2.limit.connections.per.user", 0,
      "Maximum hive server2 connections per user. Any user exceeding this limit will not be allowed to connect. " +
        "Default=0 does not enforce limits."),
    HIVE_SERVER2_LIMIT_CONNECTIONS_PER_IPADDRESS("hive.server2.limit.connections.per.ipaddress", 0,
      "Maximum hive server2 connections per ipaddress. Any ipaddress exceeding this limit will not be allowed " +
        "to connect. Default=0 does not enforce limits."),
    HIVE_SERVER2_LIMIT_CONNECTIONS_PER_USER_IPADDRESS("hive.server2.limit.connections.per.user.ipaddress", 0,
      "Maximum hive server2 connections per user:ipaddress combination. Any user-ipaddress exceeding this limit will " +
        "not be allowed to connect. Default=0 does not enforce limits."),

    // Enable metric collection for HiveServer2
    HIVE_SERVER2_METRICS_ENABLED("hive.server2.metrics.enabled", false, "Enable metrics on the HiveServer2."),

    // http (over thrift) transport settings
    HIVE_SERVER2_THRIFT_HTTP_PORT("hive.server2.thrift.http.port", 10001,
        "Port number of HiveServer2 Thrift interface when hive.server2.transport.mode is 'http'."),
    HIVE_SERVER2_THRIFT_HTTP_PATH("hive.server2.thrift.http.path", "cliservice",
        "Path component of URL endpoint when in HTTP mode."),
    HIVE_SERVER2_THRIFT_MAX_MESSAGE_SIZE("hive.server2.thrift.max.message.size", 100*1024*1024,
        "Maximum message size in bytes a HS2 server will accept."),
    HIVE_SERVER2_THRIFT_HTTP_MAX_IDLE_TIME("hive.server2.thrift.http.max.idle.time", "1800s",
        new TimeValidator(TimeUnit.MILLISECONDS),
        "Maximum idle time for a connection on the server when in HTTP mode."),
    HIVE_SERVER2_THRIFT_HTTP_WORKER_KEEPALIVE_TIME("hive.server2.thrift.http.worker.keepalive.time", "60s",
        new TimeValidator(TimeUnit.SECONDS),
        "Keepalive time for an idle http worker thread. When the number of workers exceeds min workers, " +
        "excessive threads are killed after this time interval."),
    HIVE_SERVER2_THRIFT_HTTP_REQUEST_HEADER_SIZE("hive.server2.thrift.http.request.header.size", 6*1024,
        "Request header size in bytes, when using HTTP transport mode. Jetty defaults used."),
    HIVE_SERVER2_THRIFT_HTTP_RESPONSE_HEADER_SIZE("hive.server2.thrift.http.response.header.size", 6*1024,
        "Response header size in bytes, when using HTTP transport mode. Jetty defaults used."),
    HIVE_SERVER2_THRIFT_HTTP_COMPRESSION_ENABLED("hive.server2.thrift.http.compression.enabled", true,
        "Enable thrift http compression via Jetty compression support"),

    // Cookie based authentication when using HTTP Transport
    HIVE_SERVER2_THRIFT_HTTP_COOKIE_AUTH_ENABLED("hive.server2.thrift.http.cookie.auth.enabled", true,
        "When true, HiveServer2 in HTTP transport mode, will use cookie based authentication mechanism."),
    HIVE_SERVER2_THRIFT_HTTP_COOKIE_MAX_AGE("hive.server2.thrift.http.cookie.max.age", "86400s",
        new TimeValidator(TimeUnit.SECONDS),
        "Maximum age in seconds for server side cookie used by HS2 in HTTP mode."),
    HIVE_SERVER2_THRIFT_HTTP_COOKIE_DOMAIN("hive.server2.thrift.http.cookie.domain", null,
        "Domain for the HS2 generated cookies"),
    HIVE_SERVER2_THRIFT_HTTP_COOKIE_PATH("hive.server2.thrift.http.cookie.path", null,
        "Path for the HS2 generated cookies"),
    @Deprecated
    HIVE_SERVER2_THRIFT_HTTP_COOKIE_IS_SECURE("hive.server2.thrift.http.cookie.is.secure", true,
        "Deprecated: Secure attribute of the HS2 generated cookie (this is automatically enabled for SSL enabled HiveServer2)."),
    HIVE_SERVER2_THRIFT_HTTP_COOKIE_IS_HTTPONLY("hive.server2.thrift.http.cookie.is.httponly", true,
        "HttpOnly attribute of the HS2 generated cookie."),

    // binary transport settings
    HIVE_SERVER2_THRIFT_PORT("hive.server2.thrift.port", 10000,
        "Port number of HiveServer2 Thrift interface when hive.server2.transport.mode is 'binary'."),
    HIVE_SERVER2_THRIFT_SASL_QOP("hive.server2.thrift.sasl.qop", "auth",
        new StringSet("auth", "auth-int", "auth-conf"),
        "Sasl QOP value; set it to one of following values to enable higher levels of\n" +
        "protection for HiveServer2 communication with clients.\n" +
        "Setting hadoop.rpc.protection to a higher level than HiveServer2 does not\n" +
        "make sense in most situations. HiveServer2 ignores hadoop.rpc.protection in favor\n" +
        "of hive.server2.thrift.sasl.qop.\n" +
        "  \"auth\" - authentication only (default)\n" +
        "  \"auth-int\" - authentication plus integrity protection\n" +
        "  \"auth-conf\" - authentication plus integrity and confidentiality protection\n" +
        "This is applicable only if HiveServer2 is configured to use Kerberos authentication."),
    HIVE_SERVER2_THRIFT_MIN_WORKER_THREADS("hive.server2.thrift.min.worker.threads", 5,
        "Minimum number of Thrift worker threads"),
    HIVE_SERVER2_THRIFT_MAX_WORKER_THREADS("hive.server2.thrift.max.worker.threads", 500,
        "Maximum number of Thrift worker threads"),
    HIVE_SERVER2_THRIFT_WORKER_KEEPALIVE_TIME("hive.server2.thrift.worker.keepalive.time", "60s",
        new TimeValidator(TimeUnit.SECONDS),
        "Keepalive time (in seconds) for an idle worker thread. When the number of workers exceeds min workers, " +
        "excessive threads are killed after this time interval."),

    // Configuration for async thread pool in SessionManager
    HIVE_SERVER2_ASYNC_EXEC_THREADS("hive.server2.async.exec.threads", 100,
        "Number of threads in the async thread pool for HiveServer2"),
    HIVE_SERVER2_ASYNC_EXEC_SHUTDOWN_TIMEOUT("hive.server2.async.exec.shutdown.timeout", "10s",
        new TimeValidator(TimeUnit.SECONDS),
        "How long HiveServer2 shutdown will wait for async threads to terminate."),
    HIVE_SERVER2_ASYNC_EXEC_WAIT_QUEUE_SIZE("hive.server2.async.exec.wait.queue.size", 100,
        "Size of the wait queue for async thread pool in HiveServer2.\n" +
        "After hitting this limit, the async thread pool will reject new requests."),
    HIVE_SERVER2_ASYNC_EXEC_KEEPALIVE_TIME("hive.server2.async.exec.keepalive.time", "10s",
        new TimeValidator(TimeUnit.SECONDS),
        "Time that an idle HiveServer2 async thread (from the thread pool) will wait for a new task\n" +
        "to arrive before terminating"),
    HIVE_SERVER2_ASYNC_EXEC_ASYNC_COMPILE("hive.server2.async.exec.async.compile", false,
        "Whether to enable compiling async query asynchronously. If enabled, it is unknown if the query will have any resultset before compilation completed."),
    HIVE_SERVER2_LONG_POLLING_TIMEOUT("hive.server2.long.polling.timeout", "5000ms",
        new TimeValidator(TimeUnit.MILLISECONDS),
        "Time that HiveServer2 will wait before responding to asynchronous calls that use long polling"),

    HIVE_SESSION_IMPL_CLASSNAME("hive.session.impl.classname", null, "Classname for custom implementation of hive session"),
    HIVE_SESSION_IMPL_WITH_UGI_CLASSNAME("hive.session.impl.withugi.classname", null, "Classname for custom implementation of hive session with UGI"),

    // HiveServer2 auth configuration
    HIVE_SERVER2_AUTHENTICATION("hive.server2.authentication", "NONE",
      new StringSet("NOSASL", "NONE", "LDAP", "KERBEROS", "PAM", "CUSTOM", "SAML", "JWT"),
        "Client authentication types.\n" +
        "  NONE: no authentication check\n" +
        "  LDAP: LDAP/AD based authentication\n" +
        "  KERBEROS: Kerberos/GSSAPI authentication\n" +
        "  CUSTOM: Custom authentication provider\n" +
        "          (Use with property hive.server2.custom.authentication.class)\n" +
        "  PAM: Pluggable authentication module\n" +
        "  NOSASL:  Raw transport\n" +
        "  SAML: SAML 2.0 compliant authentication. This is only supported in http transport mode.\n" +
        "  JWT: JWT based authentication. HS2 expects JWT contains the user name as subject and was signed by an\n" +
        "       asymmetric key. This is only supported in http transport mode."),
    HIVE_SERVER2_TRUSTED_DOMAIN("hive.server2.trusted.domain", "",
        "Specifies the host or a domain to trust connections from. Authentication is skipped " +
        "for any connection coming from a host whose hostname ends with the value of this" +
        " property. If authentication is expected to be skipped for connections from " +
        "only a given host, fully qualified hostname of that host should be specified. By default" +
        " it is empty, which means that all the connections to HiveServer2 are authenticated. " +
        "When it is non-empty, the client has to provide a Hive user name. Any password, if " +
        "provided, will not be used when authentication is skipped."),
    HIVE_SERVER2_TRUSTED_DOMAIN_USE_XFF_HEADER("hive.server2.trusted.domain.use.xff.header", false,
      "When trusted domain authentication is enabled, the clients connecting to the HS2 could pass" +
        "through many layers of proxy. Some proxies append its own ip address to 'X-Forwarded-For' header" +
        "before passing on the request to another proxy or HS2. Some proxies also connect on behalf of client" +
        "and may create a separate connection to HS2 without binding using client IP. For such environments, instead" +
        "of looking at client IP from the request, if this config is set and if 'X-Forwarded-For' is present," +
        "trusted domain authentication will use left most ip address from X-Forwarded-For header."),
    HIVE_SERVER2_ALLOW_USER_SUBSTITUTION("hive.server2.allow.user.substitution", true,
        "Allow alternate user to be specified as part of HiveServer2 open connection request."),
    HIVE_SERVER2_KERBEROS_KEYTAB("hive.server2.authentication.kerberos.keytab", "",
        "Kerberos keytab file for server principal"),
    HIVE_SERVER2_KERBEROS_PRINCIPAL("hive.server2.authentication.kerberos.principal", "",
        "Kerberos server principal"),
    HIVE_SERVER2_CLIENT_KERBEROS_PRINCIPAL("hive.server2.authentication.client.kerberos.principal", "",
        "Kerberos principal used by the HA hive_server2s."),
    HIVE_SERVER2_SPNEGO_KEYTAB("hive.server2.authentication.spnego.keytab", "",
        "keytab file for SPNego principal, optional,\n" +
        "typical value would look like /etc/security/keytabs/spnego.service.keytab,\n" +
        "This keytab would be used by HiveServer2 when Kerberos security is enabled and \n" +
        "HTTP transport mode is used.\n" +
        "This needs to be set only if SPNEGO is to be used in authentication.\n" +
        "SPNego authentication would be honored only if valid\n" +
        "  hive.server2.authentication.spnego.principal\n" +
        "and\n" +
        "  hive.server2.authentication.spnego.keytab\n" +
        "are specified."),
    HIVE_SERVER2_SPNEGO_PRINCIPAL("hive.server2.authentication.spnego.principal", "",
        "SPNego service principal, optional,\n" +
        "typical value would look like HTTP/_HOST@EXAMPLE.COM\n" +
        "SPNego service principal would be used by HiveServer2 when Kerberos security is enabled\n" +
        "and HTTP transport mode is used.\n" +
        "This needs to be set only if SPNEGO is to be used in authentication."),
    HIVE_SERVER2_PLAIN_LDAP_URL("hive.server2.authentication.ldap.url", null,
        "LDAP connection URL(s),\n" +
         "this value could contain URLs to multiple LDAP servers instances for HA,\n" +
         "each LDAP URL is separated by a SPACE character. URLs are used in the \n" +
         " order specified until a connection is successful."),
    HIVE_SERVER2_PLAIN_LDAP_BASEDN("hive.server2.authentication.ldap.baseDN", null, "LDAP base DN"),
    HIVE_SERVER2_PLAIN_LDAP_DOMAIN("hive.server2.authentication.ldap.Domain", null, ""),
    HIVE_SERVER2_PLAIN_LDAP_GROUPDNPATTERN("hive.server2.authentication.ldap.groupDNPattern", null,
        "COLON-separated list of patterns to use to find DNs for group entities in this directory.\n" +
        "Use %s where the actual group name is to be substituted for.\n" +
        "For example: CN=%s,CN=Groups,DC=subdomain,DC=domain,DC=com."),
    HIVE_SERVER2_PLAIN_LDAP_GROUPFILTER("hive.server2.authentication.ldap.groupFilter", null,
        "COMMA-separated list of LDAP Group names (short name not full DNs).\n" +
        "For example: HiveAdmins,HadoopAdmins,Administrators"),
    HIVE_SERVER2_PLAIN_LDAP_USERDNPATTERN("hive.server2.authentication.ldap.userDNPattern", null,
        "COLON-separated list of patterns to use to find DNs for users in this directory.\n" +
        "Use %s where the actual group name is to be substituted for.\n" +
        "For example: CN=%s,CN=Users,DC=subdomain,DC=domain,DC=com."),
    HIVE_SERVER2_PLAIN_LDAP_USERFILTER("hive.server2.authentication.ldap.userFilter", null,
        "COMMA-separated list of LDAP usernames (just short names, not full DNs).\n" +
        "For example: hiveuser,impalauser,hiveadmin,hadoopadmin"),
    HIVE_SERVER2_PLAIN_LDAP_GUIDKEY("hive.server2.authentication.ldap.guidKey", "uid",
        "LDAP attribute name whose values are unique in this LDAP server.\n" +
        "For example: uid or CN."),
    HIVE_SERVER2_PLAIN_LDAP_GROUPMEMBERSHIP_KEY("hive.server2.authentication.ldap.groupMembershipKey", "member",
        "LDAP attribute name on the group object that contains the list of distinguished names\n" +
        "for the user, group, and contact objects that are members of the group.\n" +
        "For example: member, uniqueMember or memberUid"),
    HIVE_SERVER2_PLAIN_LDAP_USERMEMBERSHIP_KEY(HIVE_SERVER2_AUTHENTICATION_LDAP_USERMEMBERSHIPKEY_NAME, null,
        "LDAP attribute name on the user object that contains groups of which the user is\n" +
        "a direct member, except for the primary group, which is represented by the\n" +
        "primaryGroupId.\n" +
        "For example: memberOf"),
    HIVE_SERVER2_PLAIN_LDAP_GROUPCLASS_KEY("hive.server2.authentication.ldap.groupClassKey", "groupOfNames",
        "LDAP attribute name on the group entry that is to be used in LDAP group searches.\n" +
        "For example: group, groupOfNames or groupOfUniqueNames."),
    HIVE_SERVER2_PLAIN_LDAP_CUSTOMLDAPQUERY("hive.server2.authentication.ldap.customLDAPQuery", null,
        "A full LDAP query that LDAP Atn provider uses to execute against LDAP Server.\n" +
        "If this query returns a null resultset, the LDAP Provider fails the Authentication\n" +
        "request, succeeds if the user is part of the resultset." +
        "For example: (&(objectClass=group)(objectClass=top)(instanceType=4)(cn=Domain*)) \n" +
        "(&(objectClass=person)(|(sAMAccountName=admin)(|(memberOf=CN=Domain Admins,CN=Users,DC=domain,DC=com)" +
        "(memberOf=CN=Administrators,CN=Builtin,DC=domain,DC=com))))"),
    HIVE_SERVER2_PLAIN_LDAP_USERSEARCHFILTER("hive.server2.authentication.ldap.userSearchFilter", null,
        "User search filter to be used with baseDN to search for users\n" +
            "For example: (&(uid={0})(objectClass=person))"),
    HIVE_SERVER2_PLAIN_LDAP_GROUPBASEDN("hive.server2.authentication.ldap.groupBaseDN", null,
        "BaseDN for Group Search. This is used in conjunction with hive.server2.authentication.ldap.baseDN\n" +
            "and \n" +
            "request, succeeds if the group is part of the resultset."),
    HIVE_SERVER2_PLAIN_LDAP_GROUPSEARCHFILTER("hive.server2.authentication.ldap.groupSearchFilter", null,
        "Group search filter to be used with baseDN, userSearchFilter, groupBaseDN to search for users in groups\n" +
            "For example: (&(|(memberUid={0})(memberUid={1}))(objectClass=posixGroup))\n"),
    HIVE_SERVER2_PLAIN_LDAP_BIND_USER("hive.server2.authentication.ldap.binddn", null,
        "The user with which to bind to the LDAP server, and search for the full domain name " +
        "of the user being authenticated.\n" +
        "This should be the full domain name of the user, and should have search access across all " +
        "users in the LDAP tree.\n" +
        "If not specified, then the user being authenticated will be used as the bind user.\n" +
        "For example: CN=bindUser,CN=Users,DC=subdomain,DC=domain,DC=com"),
    HIVE_SERVER2_PLAIN_LDAP_BIND_PASSWORD("hive.server2.authentication.ldap.bindpw", null,
        "The password for the bind user, to be used to search for the full name of the user being authenticated.\n" +
        "If the username is specified, this parameter must also be specified."),
    HIVE_SERVER2_CUSTOM_AUTHENTICATION_CLASS("hive.server2.custom.authentication.class", null,
        "Custom authentication class. Used when property\n" +
        "'hive.server2.authentication' is set to 'CUSTOM'. Provided class\n" +
        "must be a proper implementation of the interface\n" +
        "org.apache.hive.service.auth.PasswdAuthenticationProvider. HiveServer2\n" +
        "will call its Authenticate(user, passed) method to authenticate requests.\n" +
        "The implementation may optionally implement Hadoop's\n" +
        "org.apache.hadoop.conf.Configurable class to grab Hive's Configuration object."),
    HIVE_SERVER2_PAM_SERVICES("hive.server2.authentication.pam.services", null,
      "List of the underlying pam services that should be used when auth type is PAM\n" +
      "A file with the same name must exist in /etc/pam.d"),
    // JWT Auth configs
    HIVE_SERVER2_AUTHENTICATION_JWT_JWKS_URL("hive.server2.authentication.jwt.jwks.url", "",
        "URL of the file from where URLBasedJWKSProvider will try to load JWKS if JWT is enabled for the\n" +
        "authentication mode."),
    HIVE_SERVER2_AUTHENTICATION_JWT_JWKS_SKIP_SSL_CERT("hive.server2.authentication.jwt.jwks.skip.ssl.cert", false,
        "When this is enabled, the SSL certificate verification will be skipped.\n" +
        "This is meant to be used in a testing environment only. Do not use in production."),

    // HS2 SAML2.0 configuration
    HIVE_SERVER2_SAML_KEYSTORE_PATH("hive.server2.saml2.keystore.path", "",
        "Keystore path to the saml2 client. This keystore is used to store the\n"
            + " key pair used to sign the authentication requests when hive.server2.saml2.sign.requests\n"
            + " is set to true. If the path doesn't exist, HiveServer2 will attempt to\n"
            + " create a keystore using the default configurations otherwise it will use\n"
            + " the one provided."),
    HIVE_SERVER2_SAML_KEYSTORE_PASSWORD("hive.server2.saml2.keystore.password", "",
        "Password to the keystore used to sign the authentication requests. By default,\n"
            + " this must be set to a non-blank value if the authentication mode is SAML."),
    HIVE_SERVER2_SAML_PRIVATE_KEY_PASSWORD("hive.server2.saml2.private.key.password", "",
        "Password for the private key which is stored in the keystore pointed \n"
            + " by hive.server2.saml2.keystore.path. This key is used to sign the authentication request\n"
            + " if hive.server2.saml2.sign.requests is set to true."),
    HIVE_SERVER2_SAML_IDP_METADATA("hive.server2.saml2.idp.metadata", "",
        "IDP metadata file for the SAML configuration. This metadata file must be\n"
            + " exported from the external identity provider. This is used to validate the SAML assertions\n"
            + " received by HiveServer2."),
    HIVE_SERVER2_SAML_SP_ID("hive.server2.saml2.sp.entity.id", "",
        "Service provider entity id for this HiveServer2. This must match with the\n"
            + " SP id on the external identity provider. If this is not set, HiveServer2 will use the\n"
            + " callback url as the SP id."),
    HIVE_SERVER2_SAML_FORCE_AUTH("hive.server2.saml2.sp.force.auth", "false",
        "This is a boolean configuration which toggles the force authentication\n"
            + " flag in the SAML authentication request. When set to true, the request generated\n"
            + " to the IDP will ask the IDP to force the authentication again."),
    HIVE_SERVER2_SAML_AUTHENTICATION_LIFETIME(
        "hive.server2.saml2.max.authentication.lifetime", "1h",
        "This configuration can be used to set the lifetime of the\n"
            + " authentication response from IDP. Generally the IDP will not ask\n"
            + " you enter credentials if you have a authenticated session with it already.\n"
            + " The IDP will automatically generate an assertion in such a case. This configuration\n"
            + " can be used to set the time limit for such assertions. Assertions which are\n"
            + " older than this value will not be accepted by HiveServer2. The default\n"
            + " is one hour."),
    HIVE_SERVER2_SAML_BLACKLISTED_SIGNATURE_ALGORITHMS(
        "hive.server2.saml2.blacklisted.signature.algorithms", "",
        "Comma separated list of signature algorithm names which are not\n"
            + " allowed by HiveServer2 during validation of the assertions received from IDP"),
    HIVE_SERVER2_SAML_ACS_INDEX("hive.server2.saml2.acs.index", "",
        "This configuration specifies the assertion consumer service (ACS)\n"
            + " index to be sent to the IDP in case it support multiple ACS URLs. This\n"
            + " will also be used to pick the ACS URL from the IDP metadata for validation."),
    HIVE_SERVER2_SAML_CALLBACK_URL("hive.server2.saml2.sp.callback.url", "",
        "Callback URL where SAML responses should be posted. Currently this\n" +
            " must be configured at the same port number as defined by hive.server2.thrift.http.port."),
    HIVE_SERVER2_SAML_WANT_ASSERTIONS_SIGNED("hive.server2.saml2.want.assertions.signed", true,
        "When this configuration is set to true, hive server2 will validate the signature\n"
            + " of the assertions received at the callback url. For security reasons, it is recommended"
            + "that this value should be true."),
    HIVE_SERVER2_SAML_SIGN_REQUESTS("hive.server2.saml2.sign.requests", false,
        "When this configuration is set to true, HiveServer2 will sign the SAML requests\n" +
            " which can be validated by the IDP provider."),
    HIVE_SERVER2_SAML_CALLBACK_TOKEN_TTL("hive.server2.saml2.callback.token.ttl", "30s",
        new TimeValidator(TimeUnit.MILLISECONDS), "Time for which the token issued by\n"
        + "service provider is valid."),
    HIVE_SERVER2_SAML_GROUP_ATTRIBUTE_NAME("hive.server2.saml2.group.attribute.name",
        "", "The attribute name in the SAML assertion which would\n"
        + " be used to compare for the group name matching. By default it is empty\n"
        + " which would allow any authenticated user. If this value is set then\n"
            + " then hive.server2.saml2.group.filter must be set to a non-empty value."),
    HIVE_SERVER2_SAML_GROUP_FILTER("hive.server2.saml2.group.filter", "",
        "Comma separated list of group names which will be allowed when SAML\n"
            + " authentication is enabled."),

    HIVE_SERVER2_ENABLE_DOAS("hive.server2.enable.doAs", true,
        "Setting this property to true will have HiveServer2 execute\n" +
        "Hive operations as the user making the calls to it."),
    HIVE_SERVER2_SERVICE_USERS("hive.server2.service.users", null,
        "Comma separated list of users to have HiveServer2 skip authorization when compiling queries."),
    HIVE_DISTCP_DOAS_USER("hive.distcp.privileged.doAs","hive",
        "This property allows privileged distcp executions done by hive\n" +
        "to run as this user."),
    HIVE_SERVER2_TABLE_TYPE_MAPPING("hive.server2.table.type.mapping", "CLASSIC", new StringSet("CLASSIC", "HIVE"),
        "This setting reflects how HiveServer2 will report the table types for JDBC and other\n" +
        "client implementations that retrieve the available tables and supported table types\n" +
        "  HIVE : Exposes Hive's native table types like MANAGED_TABLE, EXTERNAL_TABLE, VIRTUAL_VIEW\n" +
        "  CLASSIC : More generic types like TABLE and VIEW"),
    HIVE_SERVER2_SESSION_HOOK("hive.server2.session.hook", "", ""),

    // SSL settings
    HIVE_SERVER2_USE_SSL("hive.server2.use.SSL", false,
        "Set this to true for using SSL encryption in HiveServer2."),
    HIVE_SERVER2_SSL_KEYSTORE_PATH("hive.server2.keystore.path", "",
        "SSL certificate keystore location."),
    HIVE_SERVER2_SSL_KEYSTORE_PASSWORD("hive.server2.keystore.password", "",
        "SSL certificate keystore password."),
    HIVE_SERVER2_SSL_KEYSTORE_TYPE("hive.server2.keystore.type", "",
        "SSL certificate keystore type."),
    HIVE_SERVER2_SSL_KEYMANAGERFACTORY_ALGORITHM("hive.server2.keymanagerfactory.algorithm", "",
        "SSL certificate keystore algorithm."),
    HIVE_SERVER2_SSL_HTTP_EXCLUDE_CIPHERSUITES("hive.server2.http.exclude.ciphersuites", "",
        "SSL a list of exclude cipher suite names or regular expressions separated by comma "
        + "for HiveServer2 http server."),
    HIVE_SERVER2_SSL_BINARY_INCLUDE_CIPHERSUITES("hive.server2.binary.include.ciphersuites", "",
        "SSL a list of include cipher suite names separated by colon for HiveServer2 binary Cli Server"),
    HIVE_SERVER2_BUILTIN_UDF_WHITELIST("hive.server2.builtin.udf.whitelist", "",
        "Comma separated list of builtin udf names allowed in queries.\n" +
        "An empty whitelist allows all builtin udfs to be executed. " +
        " The udf black list takes precedence over udf white list"),
    HIVE_SERVER2_BUILTIN_UDF_BLACKLIST("hive.server2.builtin.udf.blacklist", "",
         "Comma separated list of udfs names. These udfs will not be allowed in queries." +
         " The udf black list takes precedence over udf white list"),
     HIVE_ALLOW_UDF_LOAD_ON_DEMAND("hive.allow.udf.load.on.demand", false,
         "Whether enable loading UDFs from metastore on demand; this is mostly relevant for\n" +
         "HS2 and was the default behavior before Hive 1.2. Off by default."),

    HIVE_SERVER2_SESSION_CHECK_INTERVAL("hive.server2.session.check.interval", "15m",
        new TimeValidator(TimeUnit.MILLISECONDS, 3000l, true, null, false),
        "The check interval for session/operation timeout, which can be disabled by setting to zero or negative value."),
    HIVE_SERVER2_CLOSE_SESSION_ON_DISCONNECT("hive.server2.close.session.on.disconnect", true,
      "Session will be closed when connection is closed. Set this to false to have session outlive its parent connection."),
    HIVE_SERVER2_IDLE_SESSION_TIMEOUT("hive.server2.idle.session.timeout", "4h",
        new TimeValidator(TimeUnit.MILLISECONDS),
        "Session will be closed when it's not accessed for this duration, which can be disabled by setting to zero or negative value."),
    HIVE_SERVER2_IDLE_OPERATION_TIMEOUT("hive.server2.idle.operation.timeout", "2h",
        new TimeValidator(TimeUnit.MILLISECONDS),
        "Operation will be closed when it's not accessed for this duration of time, which can be disabled by setting to zero value.\n" +
        "  With positive value, it's checked for operations in terminal state only (FINISHED, CANCELED, CLOSED, ERROR).\n" +
        "  With negative value, it's checked for all of the operations regardless of state."),
    HIVE_SERVER2_IDLE_SESSION_CHECK_OPERATION("hive.server2.idle.session.check.operation", true,
        "Session will be considered to be idle only if there is no activity, and there is no pending operation.\n" +
        " This setting takes effect only if session idle timeout (hive.server2.idle.session.timeout) and checking\n" +
        "(hive.server2.session.check.interval) are enabled."),
    HIVE_SERVER2_THRIFT_CLIENT_RETRY_LIMIT("hive.server2.thrift.client.retry.limit", 1,"Number of retries upon " +
      "failure of Thrift HiveServer2 calls"),
    HIVE_SERVER2_THRIFT_CLIENT_CONNECTION_RETRY_LIMIT("hive.server2.thrift.client.connect.retry.limit", 1,"Number of " +
      "retries while opening a connection to HiveServe2"),
    HIVE_SERVER2_THRIFT_CLIENT_RETRY_DELAY_SECONDS("hive.server2.thrift.client.retry.delay.seconds", "1s",
      new TimeValidator(TimeUnit.SECONDS), "Number of seconds for the HiveServer2 thrift client to wait between " +
      "consecutive connection attempts. Also specifies the time to wait between retrying thrift calls upon failures"),
    HIVE_SERVER2_THRIFT_CLIENT_USER("hive.server2.thrift.client.user", "anonymous","Username to use against thrift" +
      " client"),
    HIVE_SERVER2_THRIFT_CLIENT_PASSWORD("hive.server2.thrift.client.password", "anonymous","Password to use against " +
      "thrift client"),

    // ResultSet serialization settings
    HIVE_SERVER2_THRIFT_RESULTSET_SERIALIZE_IN_TASKS("hive.server2.thrift.resultset.serialize.in.tasks", false,
      "Whether we should serialize the Thrift structures used in JDBC ResultSet RPC in task nodes.\n " +
      "We use SequenceFile and ThriftJDBCBinarySerDe to read and write the final results if this is true."),
    // TODO: Make use of this config to configure fetch size
    HIVE_SERVER2_THRIFT_RESULTSET_MAX_FETCH_SIZE("hive.server2.thrift.resultset.max.fetch.size",
        10000, "Max number of rows sent in one Fetch RPC call by the server to the client."),
    HIVE_SERVER2_THRIFT_RESULTSET_DEFAULT_FETCH_SIZE("hive.server2.thrift.resultset.default.fetch.size", 1000,
        "The number of rows sent in one Fetch RPC call by the server to the client, if not\n" +
        "specified by the client."),
    HIVE_SERVER2_XSRF_FILTER_ENABLED("hive.server2.xsrf.filter.enabled",false,
        "If enabled, HiveServer2 will block any requests made to it over http " +
        "if an X-XSRF-HEADER header is not present"),
    HIVE_SERVER2_CSRF_FILTER_ENABLED("hive.server2.csrf.filter.enabled",false,
        "If enabled, HiveServer2 will block any requests made to it over http " +
            "if an X-CSRF-TOKEN header is not present"),
    HIVE_SECURITY_COMMAND_WHITELIST("hive.security.command.whitelist",
      "set,reset,dfs,add,list,delete,reload,compile,llap,processlist",
        "Comma separated list of non-SQL Hive commands users are authorized to execute"),
    HIVE_SERVER2_JOB_CREDENTIAL_PROVIDER_PATH("hive.server2.job.credential.provider.path", "",
        "If set, this configuration property should provide a comma-separated list of URLs that indicates the type and " +
        "location of providers to be used by hadoop credential provider API. It provides HiveServer2 the ability to provide job-specific " +
        "credential providers for jobs run using Tez, MR execution engines."),
    HIVE_SERVER2_GRACEFUL_STOP_TIMEOUT("hive.server2.graceful.stop.timeout", "1800s",
        new TimeValidator(TimeUnit.SECONDS),
        "Maximum time waiting for live queries being finished and stopping HiveServer2. "
         + "With value not greater than 30s(the overhead to stop HiveServer2), it will not wait for the live queries to be done, "
         + "instead call stop directly to shutdown HiveServer2 gracefully"),
    HIVE_MOVE_FILES_THREAD_COUNT("hive.mv.files.thread", 15, new  SizeValidator(0L, true, 1024L, true), "Number of threads"
         + " used to move files in move task. Set it to 0 to disable multi-threaded file moves. This parameter is also used by"
         + " MSCK to check tables."),
    HIVE_LOAD_DYNAMIC_PARTITIONS_THREAD_COUNT("hive.load.dynamic.partitions.thread", 15,
        new  SizeValidator(1L, true, 1024L, true),
        "Number of threads used to load dynamic partitions."),
    HIVE_LOAD_DYNAMIC_PARTITIONS_SCAN_SPECIFIC_PARTITIONS("hive.load.dynamic.partitions.scan.specific.partitions", true,
        "For the dynamic partitioned tables, scan only the specific partitions using the name from the list"),
    // If this is set all move tasks at the end of a multi-insert query will only begin once all
    // outputs are ready
    HIVE_MULTI_INSERT_MOVE_TASKS_SHARE_DEPENDENCIES(
        "hive.multi.insert.move.tasks.share.dependencies", false,
        "If this is set all move tasks for tables/partitions (not directories) at the end of a\n" +
        "multi-insert query will only begin once the dependencies for all these move tasks have been\n" +
        "met.\n" +
        "Advantages: If concurrency is enabled, the locks will only be released once the query has\n" +
        "            finished, so with this config enabled, the time when the table/partition is\n" +
        "            generated will be much closer to when the lock on it is released.\n" +
        "Disadvantages: If concurrency is not enabled, with this disabled, the tables/partitions which\n" +
        "               are produced by this query and finish earlier will be available for querying\n" +
        "               much earlier.  Since the locks are only released once the query finishes, this\n" +
        "               does not apply if concurrency is enabled."),
    HIVE_HDFS_ENCRYPTION_SHIM_CACHE_ON("hive.hdfs.encryption.shim.cache.on", true,
        "Hive keeps a cache of hdfs encryption shims in SessionState. Each encryption shim in the cache stores a "
            + "FileSystem object. If one of these FileSystems is closed anywhere in the system and HDFS config"
            + "fs.hdfs.impl.disable.cache is false, its encryption shim in the cache will be unusable. "
            + "If this is config set to false, then the encryption shim cache will be disabled."),
    HIVE_INFER_BUCKET_SORT("hive.exec.infer.bucket.sort", false,
        "If this is set, when writing partitions, the metadata will include the bucketing/sorting\n" +
        "properties with which the data was written if any (this will not overwrite the metadata\n" +
        "inherited from the table if the table is bucketed/sorted)"),

    HIVE_INFER_BUCKET_SORT_NUM_BUCKETS_POWER_TWO(
        "hive.exec.infer.bucket.sort.num.buckets.power.two", false,
        "If this is set, when setting the number of reducers for the map reduce task which writes the\n" +
        "final output files, it will choose a number which is a power of two, unless the user specifies\n" +
        "the number of reducers to use using mapred.reduce.tasks.  The number of reducers\n" +
        "may be set to a power of two, only to be followed by a merge task meaning preventing\n" +
        "anything from being inferred.\n" +
        "With hive.exec.infer.bucket.sort set to true:\n" +
        "Advantages:  If this is not set, the number of buckets for partitions will seem arbitrary,\n" +
        "             which means that the number of mappers used for optimized joins, for example, will\n" +
        "             be very low.  With this set, since the number of buckets used for any partition is\n" +
        "             a power of two, the number of mappers used for optimized joins will be the least\n" +
        "             number of buckets used by any partition being joined.\n" +
        "Disadvantages: This may mean a much larger or much smaller number of reducers being used in the\n" +
        "               final map reduce job, e.g. if a job was originally going to take 257 reducers,\n" +
        "               it will now take 512 reducers, similarly if the max number of reducers is 511,\n" +
        "               and a job was going to use this many, it will now use 256 reducers."),

    HIVE_OPT_LIST_BUCKETING("hive.optimize.listbucketing", false,
        "Enable list bucketing optimizer. Default value is false so that we disable it by default."),

    // Allow TCP Keep alive socket option for for HiveServer or a maximum timeout for the socket.
    SERVER_READ_SOCKET_TIMEOUT("hive.server.read.socket.timeout", "10s",
        new TimeValidator(TimeUnit.SECONDS),
        "Timeout for the HiveServer to close the connection if no response from the client. By default, 10 seconds."),

    HIVE_DECODE_PARTITION_NAME("hive.decode.partition.name", false,
        "Whether to show the unquoted partition names in query results."),

    HIVE_EXECUTION_ENGINE("hive.execution.engine", "tez", new StringSet(true, "tez", "mr"),
        "Chooses execution engine. Options are: 'tez' (Tez, default), 'mr' (MapReduce, deprecated). "+
        "MR is a historical engine and is deprecated in Hive 2 line. It may be removed without further warning."),

    HIVE_EXECUTION_MODE("hive.execution.mode", "container", new StringSet("container", "llap"),
        "Chooses whether query fragments will run in container or in llap"),

    HIVE_JAR_DIRECTORY("hive.jar.directory", null,
        "This is the location hive in tez mode will look for to find a site wide \n" +
        "installed hive instance."),
    HIVE_USER_INSTALL_DIR("hive.user.install.directory", "/user/",
        "If hive (in tez mode only) cannot find a usable hive jar in \"hive.jar.directory\", \n" +
        "it will upload the hive jar to \"hive.user.install.directory/user.name\"\n" +
        "and use it to run queries."),
    HIVE_MASKING_ALGO("hive.masking.algo","sha256", "This property is used to indicate whether " +
            "FIPS mode is enabled or not. Value should be sha512 to indicate that FIPS mode is enabled." +
            "Else the value should be sha256. Using this value column masking is being done"),

    // Vectorization enabled
    HIVE_VECTORIZATION_ENABLED("hive.vectorized.execution.enabled", true,
        "This flag should be set to true to enable vectorized mode of query execution.\n" +
        "The default value is true to reflect that our most expected Hive deployment will be using vectorization."),
    HIVE_VECTORIZATION_REDUCE_ENABLED("hive.vectorized.execution.reduce.enabled", true,
        "This flag should be set to true to enable vectorized mode of the reduce-side of query execution.\n" +
        "The default value is true."),
    HIVE_VECTORIZATION_REDUCE_GROUPBY_ENABLED("hive.vectorized.execution.reduce.groupby.enabled", true,
        "This flag should be set to true to enable vectorized mode of the reduce-side GROUP BY query execution.\n" +
        "The default value is true."),
    HIVE_VECTORIZATION_MAPJOIN_NATIVE_ENABLED("hive.vectorized.execution.mapjoin.native.enabled", true,
         "This flag should be set to true to enable native (i.e. non-pass through) vectorization\n" +
         "of queries using MapJoin.\n" +
         "The default value is true."),
    HIVE_VECTORIZATION_MAPJOIN_NATIVE_MULTIKEY_ONLY_ENABLED("hive.vectorized.execution.mapjoin.native.multikey.only.enabled", false,
         "This flag should be set to true to restrict use of native vector map join hash tables to\n" +
         "the MultiKey in queries using MapJoin.\n" +
         "The default value is false."),
    HIVE_VECTORIZATION_MAPJOIN_NATIVE_MINMAX_ENABLED("hive.vectorized.execution.mapjoin.minmax.enabled", false,
         "This flag should be set to true to enable vector map join hash tables to\n" +
         "use max / max filtering for integer join queries using MapJoin.\n" +
         "The default value is false."),
    HIVE_VECTORIZATION_MAPJOIN_NATIVE_OVERFLOW_REPEATED_THRESHOLD("hive.vectorized.execution.mapjoin.overflow.repeated.threshold", -1,
         "The number of small table rows for a match in vector map join hash tables\n" +
         "where we use the repeated field optimization in overflow vectorized row batch for join queries using MapJoin.\n" +
         "A value of -1 means do use the join result optimization.  Otherwise, threshold value can be 0 to maximum integer."),
    HIVE_VECTORIZATION_MAPJOIN_NATIVE_FAST_HASHTABLE_ENABLED("hive.vectorized.execution.mapjoin.native.fast.hashtable.enabled", false,
         "This flag should be set to true to enable use of native fast vector map join hash tables in\n" +
         "queries using MapJoin.\n" +
         "The default value is false."),
    HIVE_VECTORIZATION_GROUPBY_CHECKINTERVAL("hive.vectorized.groupby.checkinterval", 100000,
        "Number of entries added to the group by aggregation hash before a recomputation of average entry size is performed."),
    HIVE_VECTORIZATION_GROUPBY_MAXENTRIES("hive.vectorized.groupby.maxentries", 1000000,
        "Max number of entries in the vector group by aggregation hashtables. \n" +
        "Exceeding this will trigger a flush irrelevant of memory pressure condition."),
    HIVE_VECTORIZATION_GROUPBY_FLUSH_PERCENT("hive.vectorized.groupby.flush.percent", (float) 0.1,
        "Percent of entries in the group by aggregation hash flushed when the memory threshold is exceeded."),
    HIVE_VECTORIZATION_REDUCESINK_NEW_ENABLED("hive.vectorized.execution.reducesink.new.enabled", true,
        "This flag should be set to true to enable the new vectorization\n" +
        "of queries using ReduceSink.\ni" +
        "The default value is true."),
    HIVE_VECTORIZATION_USE_VECTORIZED_INPUT_FILE_FORMAT("hive.vectorized.use.vectorized.input.format", true,
        "This flag should be set to true to enable vectorizing with vectorized input file format capable SerDe.\n" +
        "The default value is true."),
    HIVE_VECTORIZATION_VECTORIZED_INPUT_FILE_FORMAT_EXCLUDES("hive.vectorized.input.format.excludes","",
        "This configuration should be set to fully described input format class names for which \n"
            + " vectorized input format should not be used for vectorized execution."),
    HIVE_VECTORIZATION_USE_VECTOR_DESERIALIZE("hive.vectorized.use.vector.serde.deserialize", true,
        "This flag should be set to true to enable vectorizing rows using vector deserialize.\n" +
        "The default value is true."),
    HIVE_VECTORIZATION_USE_ROW_DESERIALIZE("hive.vectorized.use.row.serde.deserialize", true,
        "This flag should be set to true to enable vectorizing using row deserialize.\n" +
        "The default value is true."),
    HIVE_VECTORIZATION_ROW_DESERIALIZE_INPUTFORMAT_EXCLUDES(
        "hive.vectorized.row.serde.inputformat.excludes",
        "org.apache.parquet.hadoop.ParquetInputFormat,org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat",
        "The input formats not supported by row deserialize vectorization."),
    HIVE_VECTOR_ADAPTOR_USAGE_MODE("hive.vectorized.adaptor.usage.mode", "all", new StringSet("none", "chosen", "all"),
        "Specifies the extent to which the VectorUDFAdaptor will be used for UDFs that do not have a corresponding vectorized class.\n" +
        "0. none   : disable any usage of VectorUDFAdaptor\n" +
        "1. chosen : use VectorUDFAdaptor for a small set of UDFs that were chosen for good performance\n" +
        "2. all    : use VectorUDFAdaptor for all UDFs"
    ),
    HIVE_TEST_VECTOR_ADAPTOR_OVERRIDE("hive.test.vectorized.adaptor.override", false,
        "internal use only, used to force always using the VectorUDFAdaptor.\n" +
        "The default is false, of course",
        true),
    HIVE_VECTORIZATION_PTF_ENABLED("hive.vectorized.execution.ptf.enabled", true,
        "This flag should be set to true to enable vectorized mode of the PTF of query execution.\n" +
        "The default value is true."),

    HIVE_VECTOR_ADAPTOR_CUSTOM_UDF_WHITELIST("hive.vectorized.adaptor.custom.udf.whitelist", "",
        "A comma-separated list of custom UDFs allowed to operate in vectorized mode " +
        "when hive.vectorized.adaptor.usage.mode is set to chosen.\n" +
        "Only Generic UDFs are supported for whitelisting; ensure that each custom UDF class extends GenericUDF"),

    HIVE_VECTORIZATION_PTF_MAX_MEMORY_BUFFERING_BATCH_COUNT("hive.vectorized.ptf.max.memory.buffering.batch.count", 25,
        "Maximum number of vectorized row batches to buffer in memory for PTF\n" +
        "The default value is 25"),
    HIVE_VECTORIZATION_TESTING_REDUCER_BATCH_SIZE("hive.vectorized.testing.reducer.batch.size", -1,
        "internal use only, used for creating small group key vectorized row batches to exercise more logic\n" +
        "The default value is -1 which means don't restrict for testing",
        true),
    HIVE_VECTORIZATION_TESTING_REUSE_SCRATCH_COLUMNS("hive.vectorized.reuse.scratch.columns", true,
         "internal use only. Disable this to debug scratch column state issues",
         true),
    HIVE_VECTORIZATION_COMPLEX_TYPES_ENABLED("hive.vectorized.complex.types.enabled", true,
        "This flag should be set to true to enable vectorization\n" +
        "of expressions with complex types.\n" +
        "The default value is true."),
    HIVE_VECTORIZATION_GROUPBY_COMPLEX_TYPES_ENABLED("hive.vectorized.groupby.complex.types.enabled", true,
        "This flag should be set to true to enable group by vectorization\n" +
        "of aggregations that use complex types.\n" +
        "For example, AVG uses a complex type (STRUCT) for partial aggregation results" +
        "The default value is true."),
    HIVE_VECTORIZATION_ROW_IDENTIFIER_ENABLED("hive.vectorized.row.identifier.enabled", true,
        "This flag should be set to true to enable vectorization of ROW__ID."),
    HIVE_VECTORIZATION_USE_CHECKED_EXPRESSIONS("hive.vectorized.use.checked.expressions", false,
        "This flag should be set to true to use overflow checked vector expressions when available.\n" +
        "For example, arithmetic expressions which can overflow the output data type can be evaluated using\n" +
        " checked vector expressions so that they produce same result as non-vectorized evaluation."),
    HIVE_VECTORIZED_ADAPTOR_SUPPRESS_EVALUATE_EXCEPTIONS(
        "hive.vectorized.adaptor.suppress.evaluate.exceptions", false,
        "This flag should be set to true to suppress HiveException from the generic UDF function\n" +
        "evaluate call and turn them into NULLs. Assume, by default, this is not needed"),
    HIVE_VECTORIZED_INPUT_FORMAT_SUPPORTS_ENABLED(
        "hive.vectorized.input.format.supports.enabled",
        "decimal_64",
        "Which vectorized input format support features are enabled for vectorization.\n" +
        "That is, if a VectorizedInputFormat input format does support \"decimal_64\" for example\n" +
        "this variable must enable that to be used in vectorization"),
    HIVE_VECTORIZED_IF_EXPR_MODE("hive.vectorized.if.expr.mode", "better", new StringSet("adaptor", "good", "better"),
        "Specifies the extent to which SQL IF statements will be vectorized.\n" +
        "0. adaptor: only use the VectorUDFAdaptor to vectorize IF statements\n" +
        "1. good   : use regular vectorized IF expression classes that get good performance\n" +
        "2. better : use vectorized IF expression classes that conditionally execute THEN/ELSE\n" +
        "            expressions for better performance.\n"),
    HIVE_TEST_VECTORIZATION_ENABLED_OVERRIDE("hive.test.vectorized.execution.enabled.override",
        "none", new StringSet("none", "enable", "disable"),
        "internal use only, used to override the hive.vectorized.execution.enabled setting and\n" +
        "turn off vectorization.  The default is false, of course",
        true),
    HIVE_TEST_VECTORIZATION_SUPPRESS_EXPLAIN_EXECUTION_MODE(
        "hive.test.vectorization.suppress.explain.execution.mode", false,
            "internal use only, used to suppress \"Execution mode: vectorized\" EXPLAIN display.\n" +
            "The default is false, of course",
            true),
    HIVE_TEST_VECTORIZER_SUPPRESS_FATAL_EXCEPTIONS(
        "hive.test.vectorizer.suppress.fatal.exceptions", true,
        "internal use only. When false, don't suppress fatal exceptions like\n" +
        "NullPointerException, etc so the query will fail and assure it will be noticed",
        true),
    HIVE_TYPE_CHECK_ON_INSERT("hive.typecheck.on.insert", true, "This property has been extended to control "
        + "whether to check, convert, and normalize partition value to conform to its column type in "
        + "partition operations including but not limited to insert, such as alter, describe etc."),

    HIVE_HADOOP_CLASSPATH("hive.hadoop.classpath", null,
        "For Windows OS, we need to pass HIVE_HADOOP_CLASSPATH Java parameter while starting HiveServer2 \n" +
        "using \"-hiveconf hive.hadoop.classpath=%HIVE_LIB%\"."),

    HIVE_RPC_QUERY_PLAN("hive.rpc.query.plan", false,
        "Whether to send the query plan via local resource or RPC"),
    HIVE_PLAN_MAPWORK_SERIALIZATION_SKIP_PROPERTIES("hive.plan.mapwork.serialization.skip.properties", "",
        "Comma separated list of properties which is not needed in execution time, so can be removed "
            + "from PartitionDesc properties before serialization, config can contain exact strings and regex "
            + "expressions, the regex mode is activated if at least 1 asterisk (*) is present in the current word: "
            + "rawDataSize                exact string match, removes only rawDataSize property"
            + ".*Size                     regex match, removes every property ending with 'Size'"
            + "numRows,impala_.*chunk.*   comma separated and mixed (handles strings and regexes at the same time)"),
    HIVE_AM_SPLIT_GENERATION("hive.compute.splits.in.am", true,
        "Whether to generate the splits locally or in the AM (tez only)"),
    HIVE_SPLITS_AVAILABLE_SLOTS_CALCULATOR_CLASS("hive.splits.available.slots.calculator.class.name",
        "org.apache.hadoop.hive.ql.exec.tez.TezAvailableSlotsCalculator",
        "Class to use for calculating available slots during split generation"),
    HIVE_TEZ_GENERATE_CONSISTENT_SPLITS("hive.tez.input.generate.consistent.splits", true,
        "Whether to generate consistent split locations when generating splits in the AM"),
    HIVE_TEZ_INPUT_FS_SERIALIZATION_THRESHOLD("hive.tez.input.fs.serialization.threshold", 268435456,
        "When the cummulative size of the splits is larger than this (in bytes), HiveSplitGenerator"
            + " will start to serialize splits to tez scratchdir instead of being sent as RPC payloads directly."
            + " Default is 256MB."
            + "-1 disables this feature."),
    HIVE_TEZ_INPUT_FS_SERIALIZATION_THREADS("hive.tez.input.fs.serialization.threads", 8,
        "Number of threads used for serializing split payloads to filesystem."),
    HIVE_PREWARM_ENABLED("hive.prewarm.enabled", false, "Enables container prewarm for Tez(Hadoop 2 only)"),
    HIVE_PREWARM_NUM_CONTAINERS("hive.prewarm.numcontainers", 10, "Controls the number of containers to prewarm for Tez (Hadoop 2 only)"),
    HIVE_STAGE_ID_REARRANGE("hive.stageid.rearrange", "none", new StringSet("none", "idonly", "traverse", "execution"), ""),
    HIVE_EXPLAIN_DEPENDENCY_APPEND_TASK_TYPES("hive.explain.dependency.append.tasktype", false, ""),
    HIVE_USE_GOOGLE_REGEX_ENGINE("hive.use.googleregex.engine",false,"whether to use google regex engine or not, default regex engine is java.util.regex"),

    HIVE_COUNTER_GROUP("hive.counters.group.name", "HIVE",
        "The name of counter group for internal Hive variables (CREATED_FILE, FATAL_ERROR, etc.)"),

    HIVE_QUOTEDID_SUPPORT("hive.support.quoted.identifiers", "column",
        new StringSet("none", "column", "standard"),
        "Whether to use quoted identifier. 'none', 'column', and 'standard' can be used. \n" +
        "  none: Quotation of identifiers and special characters in identifiers are not allowed but regular " +
        "expressions in backticks are supported for column names.\n" +
        "  column: Use the backtick character to quote identifiers having special characters. `col1` " +
        "Use single quotes to quote string literals. 'value' " +
        "Double quotes are also accepted but not recommended." +
        "  standard: SQL standard way to quote identifiers. " +
        "Use double quotes to quote identifiers having special characters \"col1\" " +
        "and single quotes for string literals. 'value'"
    ),
    /**
     * @deprecated Use MetastoreConf.SUPPORT_SPECIAL_CHARACTERS_IN_TABLE_NAMES
     */
    @Deprecated
    HIVE_SUPPORT_SPECICAL_CHARACTERS_IN_TABLE_NAMES("hive.support.special.characters.tablename", true,
        "This flag should be set to true to enable support for special characters in table names.\n"
        + "When it is set to false, only [a-zA-Z_0-9]+ are supported.\n"
        + "The supported special characters are %&'()*+,-./:;<=>?[]_|{}$^!~#@ and space. This flag applies only to"
        + " quoted table names.\nThe default value is true."),
    // This config is temporary and will be deprecated later
    CREATE_TABLE_AS_EXTERNAL("hive.create.as.external.legacy", false,
        "When this flag set to true. it will ignore hive.create.as.acid and hive.create.as.insert.only,"
        + "create external purge table by default."),
    /**
     * Expose MetastoreConf.CREATE_TABLES_AS_ACID in HiveConf
     * so user can set hive.create.as.acid in session level
     */
    CREATE_TABLES_AS_ACID("hive.create.as.acid", false,
        "Whether the eligible tables should be created as full ACID by default. Does \n" +
        "not apply to external tables, the ones using storage handlers, etc."),
    HIVE_CREATE_TABLES_AS_INSERT_ONLY("hive.create.as.insert.only", false,
        "Whether the eligible tables should be created as ACID insert-only by default. Does \n" +
        "not apply to external tables, the ones using storage handlers, etc."),
    HIVE_ACID_DIRECT_INSERT_ENABLED("hive.acid.direct.insert.enabled", true,
        "Enable writing the data files directly to the table's final destination instead of the staging directory."
        + "This optimization only applies on INSERT operations on ACID tables."),
    TXN_CTAS_X_LOCK("hive.txn.xlock.ctas", true,
        "Enables exclusive locking for CTAS operations."),
    // role names are case-insensitive
    USERS_IN_ADMIN_ROLE("hive.users.in.admin.role", "", false,
        "Comma separated list of users who are in admin role for bootstrapping.\n" +
        "More users can be added in ADMIN role later."),

    HIVE_COMPAT("hive.compat", HiveCompat.DEFAULT_COMPAT_LEVEL,
        "Enable (configurable) deprecated behaviors by setting desired level of backward compatibility.\n" +
        "Setting to 0.12:\n" +
        "  Maintains division behavior: int / int = double"),
    HIVE_CONVERT_JOIN_BUCKET_MAPJOIN_TEZ("hive.convert.join.bucket.mapjoin.tez", true,
        "Whether joins can be automatically converted to bucket map joins in hive \n" +
        "when tez is used as the execution engine."),
    HIVE_TEZ_BMJ_USE_SUBCACHE("hive.tez.bmj.use.subcache", true,
        "Use subcache to reuse hashtable across multiple tasks"),
    HIVE_CHECK_CROSS_PRODUCT("hive.exec.check.crossproducts", true,
        "Check if a plan contains a Cross Product. If there is one, output a warning to the Session's console."),
    HIVE_LOCALIZE_RESOURCE_WAIT_INTERVAL("hive.localize.resource.wait.interval", "5000ms",
        new TimeValidator(TimeUnit.MILLISECONDS),
        "Time to wait for another thread to localize the same resource for hive-tez."),
    HIVE_LOCALIZE_RESOURCE_NUM_WAIT_ATTEMPTS("hive.localize.resource.num.wait.attempts", 5,
        "The number of attempts waiting for localizing a resource in hive-tez."),
    TEZ_AUTO_REDUCER_PARALLELISM("hive.tez.auto.reducer.parallelism", false,
        "Turn on Tez' auto reducer parallelism feature. When enabled, Hive will still estimate data sizes\n" +
        "and set parallelism estimates. Tez will sample source vertices' output sizes and adjust the estimates at runtime as\n" +
        "necessary."),
    TEZ_AUTO_REDUCER_PARALLELISM_MIN_THRESHOLD("hive.tez.auto.reducer.parallelism.min.threshold", 1.0f,
        "Hive on Tez disables auto reducer parallelism if # of reducers * hive.tez.min.partition.factor is smaller\n" +
        "than this value. This helps to avoid overhead when the potential impact of auto reducer parallelism is not\n" +
        "significant. This is effective only when hive.tez.auto.reducer.parallelism is true."),
    TEZ_LLAP_MIN_REDUCER_PER_EXECUTOR("hive.tez.llap.min.reducer.per.executor", 0.33f,
        "If above 0, the min number of reducers for auto-parallelism for LLAP scheduling will\n" +
        "be set to this fraction of the number of executors."),
    TEZ_MAPREDUCE_OUTPUT_COMMITTER("hive.tez.mapreduce.output.committer.class",
        "org.apache.tez.mapreduce.committer.MROutputCommitter",
        "Output committer class which should be invoked at the setup/commit lifecycle points of vertex executions."),
    TEZ_MAX_PARTITION_FACTOR("hive.tez.max.partition.factor", 2f,
        "When auto reducer parallelism is enabled this factor will be used to over-partition data in shuffle edges."),
    TEZ_MIN_PARTITION_FACTOR("hive.tez.min.partition.factor", 0.25f,
        "When auto reducer parallelism is enabled this factor will be used to put a lower limit to the number\n" +
        "of reducers that tez specifies."),
    TEZ_OPTIMIZE_BUCKET_PRUNING(
        "hive.tez.bucket.pruning", true,
         "When pruning is enabled, filters on bucket columns will be processed by \n" +
         "filtering the splits against a bitset of included buckets. This needs predicates \n"+
            "produced by hive.optimize.ppd and hive.optimize.index.filter."),
    TEZ_OPTIMIZE_BUCKET_PRUNING_COMPAT(
        "hive.tez.bucket.pruning.compat", true,
        "When pruning is enabled, handle possibly broken inserts due to negative hashcodes.\n" +
        "This occasionally doubles the data scan cost, but is default enabled for safety"),
    TEZ_DYNAMIC_PARTITION_PRUNING(
        "hive.tez.dynamic.partition.pruning", true,
        "When dynamic pruning is enabled, joins on partition keys will be processed by sending\n" +
        "events from the processing vertices to the Tez application master. These events will be\n" +
        "used to prune unnecessary partitions."),
    TEZ_DYNAMIC_PARTITION_PRUNING_EXTENDED("hive.tez.dynamic.partition.pruning.extended", true,
        "Whether we should try to create additional opportunities for dynamic pruning, e.g., considering\n" +
        "siblings that may not be created by normal dynamic pruning logic.\n" +
        "Only works when dynamic pruning is enabled."),
    TEZ_DYNAMIC_PARTITION_PRUNING_MAX_EVENT_SIZE("hive.tez.dynamic.partition.pruning.max.event.size", 1*1024*1024L,
        "Maximum size of events sent by processors in dynamic pruning. If this size is crossed no pruning will take place."),

    TEZ_DYNAMIC_PARTITION_PRUNING_MAX_DATA_SIZE("hive.tez.dynamic.partition.pruning.max.data.size", 100*1024*1024L,
        "Maximum total data size of events in dynamic pruning."),
    TEZ_DYNAMIC_SEMIJOIN_REDUCTION("hive.tez.dynamic.semijoin.reduction", true,
        "When dynamic semijoin is enabled, shuffle joins will perform a leaky semijoin before shuffle. This " +
        "requires hive.tez.dynamic.partition.pruning to be enabled."),
    TEZ_MIN_BLOOM_FILTER_ENTRIES("hive.tez.min.bloom.filter.entries", 1000000L,
            "Bloom filter should be of at min certain size to be effective"),
    TEZ_MAX_BLOOM_FILTER_ENTRIES("hive.tez.max.bloom.filter.entries", 100000000L,
            "Bloom filter should be of at max certain size to be effective"),
    TEZ_BLOOM_FILTER_FACTOR("hive.tez.bloom.filter.factor", (float) 1.0,
            "Bloom filter should be a multiple of this factor with nDV"),
    TEZ_BLOOM_FILTER_MERGE_THREADS("hive.tez.bloom.filter.merge.threads", 1,
        "How many threads are used for merging bloom filters in addition to task's main thread?\n"
            + "-1: sanity check, it will fail if execution hits bloom filter merge codepath\n"
            + " 0: feature is disabled, use only task's main thread for bloom filter merging\n"
            + " 1: recommended value: there is only 1 merger thread (additionally to the task's main thread),"
            + "according perf tests, this can lead to serious improvement \n"),
    TEZ_BIGTABLE_MIN_SIZE_SEMIJOIN_REDUCTION("hive.tez.bigtable.minsize.semijoin.reduction", 100000000L,
            "Big table for runtime filtering should be of atleast this size"),
    TEZ_DYNAMIC_SEMIJOIN_REDUCTION_THRESHOLD("hive.tez.dynamic.semijoin.reduction.threshold", (float) 0.50,
            "Only perform semijoin optimization if the estimated benefit at or above this fraction of the target table"),
    TEZ_DYNAMIC_SEMIJOIN_REDUCTION_MULTICOLUMN(
        "hive.tez.dynamic.semijoin.reduction.multicolumn",
        true,
        "Whether to consider multicolumn semijoin reducers or not.\n"
            + "This should always be set to true. Since it is a new feature, it has been made configurable."),
    TEZ_DYNAMIC_SEMIJOIN_REDUCTION_FOR_MAPJOIN("hive.tez.dynamic.semijoin.reduction.for.mapjoin", false,
            "Use a semi-join branch for map-joins. This may not make it faster, but is helpful in certain join patterns."),
    TEZ_DYNAMIC_SEMIJOIN_REDUCTION_FOR_DPP_FACTOR("hive.tez.dynamic.semijoin.reduction.for.dpp.factor",
            (float) 1.0,
            "The factor to decide if semijoin branch feeds into a TableScan\n" +
            "which has an outgoing Dynamic Partition Pruning (DPP) branch based on number of distinct values."),
    TEZ_SMB_NUMBER_WAVES(
        "hive.tez.smb.number.waves",
        (float) 0.5,
        "The number of waves in which to run the SMB join. Account for cluster being occupied. Ideally should be 1 wave."),
    TEZ_EXEC_SUMMARY(
        "hive.tez.exec.print.summary",
        false,
        "Display breakdown of execution steps, for every query executed by the shell."),
    TEZ_SESSION_EVENTS_SUMMARY(
      "hive.tez.session.events.print.summary",
      "none", new StringSet("none", "text", "json"),
      "Display summary of all tez sessions related events in text or json format"),
    TEZ_EXEC_INPLACE_PROGRESS(
        "hive.tez.exec.inplace.progress",
        true,
        "Updates tez job execution progress in-place in the terminal when hive-cli is used."),
    HIVE_SERVER2_INPLACE_PROGRESS(
        "hive.server2.in.place.progress",
        true,
        "Allows hive server 2 to send progress bar update information. This is currently available"
            + " only if the execution engine is tez."),
    TEZ_DAG_STATUS_CHECK_INTERVAL("hive.tez.dag.status.check.interval", "500ms",
      new TimeValidator(TimeUnit.MILLISECONDS), "Interval between subsequent DAG status invocation."),
    TEZ_CONTAINER_MAX_JAVA_HEAP_FRACTION("hive.tez.container.max.java.heap.fraction", 0.8f,
        "This is to override the tez setting with the same name"),
    TEZ_TASK_SCALE_MEMORY_RESERVE_FRACTION_MIN("hive.tez.task.scale.memory.reserve-fraction.min",
        0.3f, "This is to override the tez setting tez.task.scale.memory.reserve-fraction"),
    TEZ_TASK_SCALE_MEMORY_RESERVE_FRACTION_MAX("hive.tez.task.scale.memory.reserve.fraction.max",
        0.5f, "The maximum fraction of JVM memory which Tez will reserve for the processor"),
    TEZ_TASK_SCALE_MEMORY_RESERVE_FRACTION("hive.tez.task.scale.memory.reserve.fraction",
        -1f, "The customized fraction of JVM memory which Tez will reserve for the processor"),
    TEZ_CARTESIAN_PRODUCT_EDGE_ENABLED("hive.tez.cartesian-product.enabled",
        false, "Use Tez cartesian product edge to speed up cross product"),
    TEZ_SIMPLE_CUSTOM_EDGE_TINY_BUFFER_SIZE_MB("hive.tez.unordered.output.buffer.size.mb", -1,
        "When we have an operation that does not need a large buffer, we use this buffer size for simple custom edge.\n" +
        "Value is an integer. Default value is -1, which means that we will estimate this value from operators in the plan."),
    // The default is different on the client and server, so it's null here.
    LLAP_IO_ENABLED("hive.llap.io.enabled", null, "Whether the LLAP IO layer is enabled."),
    LLAP_IO_CACHE_ONLY("hive.llap.io.cache.only", false, "Whether the query should read from cache only. If set to " +
        "true and a cache miss happens during the read an exception will occur. Primarily used for testing."),
    LLAP_IO_ROW_WRAPPER_ENABLED("hive.llap.io.row.wrapper.enabled", true, "Whether the LLAP IO row wrapper is enabled for non-vectorized queries."),
    LLAP_IO_ACID_ENABLED("hive.llap.io.acid", true, "Whether the LLAP IO layer is enabled for ACID."),
    LLAP_IO_TRACE_SIZE("hive.llap.io.trace.size", "2Mb",
        new SizeValidator(0L, true, (long)Integer.MAX_VALUE, false),
        "The buffer size for a per-fragment LLAP debug trace. 0 to disable."),
    LLAP_IO_TRACE_ALWAYS_DUMP("hive.llap.io.trace.always.dump", false,
        "Whether to always dump the LLAP IO trace (if enabled); the default is on error."),
    LLAP_IO_NONVECTOR_WRAPPER_ENABLED("hive.llap.io.nonvector.wrapper.enabled", true,
        "Whether the LLAP IO layer is enabled for non-vectorized queries that read inputs\n" +
        "that can be vectorized"),
    LLAP_IO_MEMORY_MODE("hive.llap.io.memory.mode", "cache",
        new StringSet("cache", "none"),
        "LLAP IO memory usage; 'cache' (the default) uses data and metadata cache with a\n" +
        "custom off-heap allocator, 'none' doesn't use either (this mode may result in\n" +
        "significant performance degradation)"),
    LLAP_ALLOCATOR_MIN_ALLOC("hive.llap.io.allocator.alloc.min", "4Kb", new SizeValidator(),
        "Minimum allocation possible from LLAP buddy allocator. Allocations below that are\n" +
        "padded to minimum allocation. For ORC, should generally be the same as the expected\n" +
        "compression buffer size, or next lowest power of 2. Must be a power of 2."),
    LLAP_ALLOCATOR_MAX_ALLOC("hive.llap.io.allocator.alloc.max", "16Mb", new SizeValidator(),
        "Maximum allocation possible from LLAP buddy allocator. For ORC, should be as large as\n" +
        "the largest expected ORC compression buffer size. Must be a power of 2."),
    LLAP_ALLOCATOR_ARENA_COUNT("hive.llap.io.allocator.arena.count", 8,
        "Arena count for LLAP low-level cache; cache will be allocated in the steps of\n" +
        "(size/arena_count) bytes. This size must be <= 1Gb and >= max allocation; if it is\n" +
        "not the case, an adjusted size will be used. Using powers of 2 is recommended."),
    LLAP_IO_MEMORY_MAX_SIZE("hive.llap.io.memory.size", "1Gb", new SizeValidator(),
        "Maximum size for IO allocator or ORC low-level cache.", "hive.llap.io.cache.orc.size"),
    LLAP_ALLOCATOR_DIRECT("hive.llap.io.allocator.direct", true,
        "Whether ORC low-level cache should use direct allocation."),
    LLAP_ALLOCATOR_PREALLOCATE("hive.llap.io.allocator.preallocate", true,
            "Whether to preallocate the entire IO memory at init time."),
    LLAP_ALLOCATOR_MAPPED("hive.llap.io.allocator.mmap", false,
        "Whether ORC low-level cache should use memory mapped allocation (direct I/O). \n" +
        "This is recommended to be used along-side NVDIMM (DAX) or NVMe flash storage."),
    LLAP_ALLOCATOR_MAPPED_PATH("hive.llap.io.allocator.mmap.path", "/tmp",
        new WritableDirectoryValidator(),
        "The directory location for mapping NVDIMM/NVMe flash storage into the ORC low-level cache."),
    LLAP_ALLOCATOR_DISCARD_METHOD("hive.llap.io.allocator.discard.method", "both",
        new StringSet("freelist", "brute", "both"),
        "Which method to use to force-evict blocks to deal with fragmentation:\n" +
        "freelist - use half-size free list (discards less, but also less reliable); brute -\n" +
        "brute force, discard whatever we can; both - first try free list, then brute force."),
    LLAP_ALLOCATOR_DEFRAG_HEADROOM("hive.llap.io.allocator.defrag.headroom", "1Mb",
        "How much of a headroom to leave to allow allocator more flexibility to defragment.\n" +
        "The allocator would further cap it to a fraction of total memory."),
    LLAP_ALLOCATOR_MAX_FORCE_EVICTED("hive.llap.io.allocator.max.force.eviction", "16Mb",
        "Fragmentation can lead to some cases where more eviction has to happen to accommodate allocations\n" +
            " This configuration puts a limit on how many bytes to force evict before using Allocator Discard method."
            + " Higher values will allow allocator more flexibility and will lead to better caching."),
    LLAP_TRACK_CACHE_USAGE("hive.llap.io.track.cache.usage", true,
         "Whether to tag LLAP cache contents, mapping them to Hive entities (paths for\n" +
         "partitions and tables) for reporting."),
    LLAP_USE_LRFU("hive.llap.io.use.lrfu", true,
        "Whether ORC low-level cache should use LRFU cache policy instead of default (FIFO)."),
    LLAP_LRFU_LAMBDA("hive.llap.io.lrfu.lambda", 0.1f,
        "Lambda for ORC low-level cache LRFU cache policy. Must be in [0, 1]. 0 makes LRFU\n" +
        "behave like LFU, 1 makes it behave like LRU, values in between balance accordingly.\n" +
        "The meaning of this parameter is the inverse of the number of time ticks (cache\n" +
        " operations, currently) that cause the combined recency-frequency of a block in cache\n" +
        " to be halved."),
    LLAP_LRFU_HOTBUFFERS_PERCENTAGE("hive.llap.io.lrfu.hotbuffers.percentage", 0.10f,
        new RangeValidator(0.0f, 1.0f), "The number specifies the percentage of the cached buffers "
        + "which are considered the most important ones based on the policy."),
    LLAP_LRFU_BP_WRAPPER_SIZE("hive.llap.io.lrfu.bp.wrapper.size", 64, "thread local queue "
        + "used to amortize the lock contention, the idea hear is to try locking as soon we reach max size / 2 "
        + "and block when max queue size reached"),
    LLAP_CACHE_ALLOW_SYNTHETIC_FILEID("hive.llap.cache.allow.synthetic.fileid", true,
        "Whether LLAP cache should use synthetic file ID if real one is not available. Systems\n" +
        "like HDFS, Isilon, etc. provide a unique file/inode ID. On other FSes (e.g. local\n" +
        "FS), the cache would not work by default because LLAP is unable to uniquely track the\n" +
        "files; enabling this setting allows LLAP to generate file ID from the path, size and\n" +
        "modification time, which is almost certain to identify file uniquely. However, if you\n" +
        "use a FS without file IDs and rewrite files a lot (or are paranoid), you might want\n" +
        "to avoid this setting."),
    LLAP_CACHE_DEFAULT_FS_FILE_ID("hive.llap.cache.defaultfs.only.native.fileid", true,
        "Whether LLAP cache should use native file IDs from the default FS only. This is to\n" +
        "avoid file ID collisions when several different DFS instances are in use at the same\n" +
        "time. Disable this check to allow native file IDs from non-default DFS."),
    LLAP_CACHE_ENABLE_ORC_GAP_CACHE("hive.llap.orc.gap.cache", true,
        "Whether LLAP cache for ORC should remember gaps in ORC compression buffer read\n" +
        "estimates, to avoid re-reading the data that was read once and discarded because it\n" +
        "is unneeded. This is only necessary for ORC files written before HIVE-9660."),
    LLAP_CACHE_HYDRATION_STRATEGY_CLASS("hive.llap.cache.hydration.strategy.class", "", "Strategy class for managing the "
        + "llap cache hydration. It's executed when the daemon starts and stops, and gives a chance to save and/or "
        + "load the contents of the llap cache. If left empty the feature is disabled.\n" +
        "The class should implement org.apache.hadoop.hive.llap.LlapCacheHydration interface."),
    LLAP_CACHE_HYDRATION_SAVE_DIR("hive.llap.cache.hydration.save.dir", "/tmp/hive", "Directory to save the llap cache content\n"
        + "info on shutdown, if BasicLlapCacheHydration is used as the hive.llap.cache.hydration.strategy.class."),
    LLAP_IO_USE_FILEID_PATH("hive.llap.io.use.fileid.path", true,
        "Whether LLAP should use fileId (inode)-based path to ensure better consistency for the\n" +
        "cases of file overwrites. This is supported on HDFS. Disabling this also turns off any\n" +
        "cache consistency checks based on fileid comparisons."),
    // Restricted to text for now as this is a new feature; only text files can be sliced.
    LLAP_IO_ENCODE_ENABLED("hive.llap.io.encode.enabled", true,
        "Whether LLAP should try to re-encode and cache data for non-ORC formats. This is used\n" +
        "on LLAP Server side to determine if the infrastructure for that is initialized."),
    LLAP_IO_ENCODE_FORMATS("hive.llap.io.encode.formats",
        "org.apache.hadoop.mapred.TextInputFormat,",
        "The table input formats for which LLAP IO should re-encode and cache data.\n" +
        "Comma-separated list."),
    LLAP_IO_ENCODE_ALLOC_SIZE("hive.llap.io.encode.alloc.size", "256Kb", new SizeValidator(),
        "Allocation size for the buffers used to cache encoded data from non-ORC files. Must\n" +
        "be a power of two between " + LLAP_ALLOCATOR_MIN_ALLOC + " and\n" +
        LLAP_ALLOCATOR_MAX_ALLOC + "."),
    LLAP_IO_ENCODE_VECTOR_SERDE_ENABLED("hive.llap.io.encode.vector.serde.enabled", true,
        "Whether LLAP should use vectorized SerDe reader to read text data when re-encoding."),
    LLAP_IO_ENCODE_VECTOR_SERDE_ASYNC_ENABLED("hive.llap.io.encode.vector.serde.async.enabled",
        true,
        "Whether LLAP should use async mode in vectorized SerDe reader to read text data."),
    LLAP_IO_ENCODE_SLICE_ROW_COUNT("hive.llap.io.encode.slice.row.count", 100000,
        "Row count to use to separate cache slices when reading encoded data from row-based\n" +
        "inputs into LLAP cache, if this feature is enabled."),
    LLAP_IO_ENCODE_SLICE_LRR("hive.llap.io.encode.slice.lrr", true,
        "Whether to separate cache slices when reading encoded data from text inputs via MR\n" +
        "MR LineRecordReader into LLAP cache, if this feature is enabled. Safety flag."),
    LLAP_ORC_ENABLE_TIME_COUNTERS("hive.llap.io.orc.time.counters", true,
        "Whether to enable time counters for LLAP IO layer (time spent in HDFS, etc.)"),
    LLAP_IO_VRB_QUEUE_LIMIT_MAX("hive.llap.io.vrb.queue.limit.max", 50000,
        "The maximum queue size for VRBs produced by a LLAP IO thread when the processing is\n" +
        "slower than the IO. The actual queue size is set per fragment, and is adjusted down\n" +
        "from the base, depending on the schema see LLAP_IO_CVB_BUFFERED_SIZE."),
    LLAP_IO_VRB_QUEUE_LIMIT_MIN("hive.llap.io.vrb.queue.limit.min", 1,
        "The minimum queue size for VRBs produced by a LLAP IO thread when the processing is\n" +
        "slower than the IO (used when determining the size from base size)."),
    LLAP_IO_CVB_BUFFERED_SIZE("hive.llap.io.cvb.memory.consumption.", 1L << 30,
        "The amount of bytes used to buffer CVB between IO and Processor Threads default to 1GB, "
            + "this will be used to compute a best effort queue size for VRBs produced by a LLAP IO thread."),
    LLAP_IO_PROACTIVE_EVICTION_ENABLED("hive.llap.io.proactive.eviction.enabled", true,
        "If true proactive cache eviction is enabled, thus LLAP will proactively evict buffers" +
         " that belong to dropped Hive entities (DBs, tables, partitions, or temp tables."),
    LLAP_IO_PROACTIVE_EVICTION_SWEEP_INTERVAL("hive.llap.io.proactive.eviction.sweep.interval", "5s",
        new TimeValidator(TimeUnit.SECONDS),
        "How frequently (in seconds) LLAP should check for buffers marked for proactive eviction and" +
         "proceed with their eviction."),
    LLAP_IO_PROACTIVE_EVICTION_INSTANT_DEALLOC("hive.llap.io.proactive.eviction.instant.dealloc", false,
        "Experimental feature: when set to true, buffer deallocation will happen as soon as proactive eviction " +
         "notifications are received by the daemon. Sweep phase of proactive eviction will only do the cache policy " +
         "cleanup in this case. This can increase cache hit ratio but might scale bad in a workload that generates " +
         "many proactive eviction events."),
    LLAP_IO_CACHE_DELETEDELTAS("hive.llap.io.cache.deletedeltas", "all", new StringSet("none", "metadata", "all"),
         "When set to 'all' queries that use LLAP IO for execution will also access delete delta files via " +
         "LLAP IO layer and thus they will be fully cached. When set to 'metadata', only the tail of delete deltas " +
         "will be cached. If set to 'none', only the base files and insert deltas will be channeled through LLAP, " +
         "while delete deltas will be accessed directly from their configured FS without caching them. " +
         "This feature only works with ColumnizedDeleteEventRegistry, SortMergedDeleteEventRegistry is not supported."),
    LLAP_IO_PATH_CACHE_SIZE("hive.llap.io.path.cache.size", "10Mb", new SizeValidator(),
        "The amount of the maximum memory allowed to store the file paths."),
    LLAP_IO_SHARE_OBJECT_POOLS("hive.llap.io.share.object.pools", false,
        "Whether to used shared object pools in LLAP IO. A safety flag."),
    LLAP_AUTO_ALLOW_UBER("hive.llap.auto.allow.uber", false,
        "Whether or not to allow the planner to run vertices in the AM."),
    LLAP_AUTO_ENFORCE_TREE("hive.llap.auto.enforce.tree", true,
        "Enforce that all parents are in llap, before considering vertex"),
    LLAP_AUTO_ENFORCE_VECTORIZED("hive.llap.auto.enforce.vectorized", true,
        "Enforce that inputs are vectorized, before considering vertex"),
    LLAP_AUTO_ENFORCE_STATS("hive.llap.auto.enforce.stats", true,
        "Enforce that col stats are available, before considering vertex"),
    LLAP_AUTO_MAX_INPUT("hive.llap.auto.max.input.size", 10*1024*1024*1024L,
        "Check input size, before considering vertex (-1 disables check)"),
    LLAP_AUTO_MAX_OUTPUT("hive.llap.auto.max.output.size", 1*1024*1024*1024L,
        "Check output size, before considering vertex (-1 disables check)"),
    LLAP_SKIP_COMPILE_UDF_CHECK("hive.llap.skip.compile.udf.check", false,
        "Whether to skip the compile-time check for non-built-in UDFs when deciding whether to\n" +
        "execute tasks in LLAP. Skipping the check allows executing UDFs from pre-localized\n" +
        "jars in LLAP; if the jars are not pre-localized, the UDFs will simply fail to load."),
    LLAP_ALLOW_PERMANENT_FNS("hive.llap.allow.permanent.fns", true,
        "Whether LLAP decider should allow permanent UDFs."),
    LLAP_EXECUTION_MODE("hive.llap.execution.mode", "none",
        new StringSet("auto", "none", "all", "map", "only"),
        "Chooses whether query fragments will run in container or in llap"),
    LLAP_IO_ETL_SKIP_FORMAT("hive.llap.io.etl.skip.format", "encode", new StringSet("none", "encode", "all"),
      "For ETL queries, determines whether to skip llap io cache. By default, hive.llap.io.encode.enabled " +
        "will be set to false which disables LLAP IO for text formats. Setting it to 'all' will disable LLAP IO for all" +
        " formats. 'none' will not disable LLAP IO for any formats."),
    LLAP_OBJECT_CACHE_ENABLED("hive.llap.object.cache.enabled", true,
        "Cache objects (plans, hashtables, etc) in llap"),
    LLAP_IO_DECODING_METRICS_PERCENTILE_INTERVALS("hive.llap.io.decoding.metrics.percentiles.intervals", "30",
        "Comma-delimited set of integers denoting the desired rollover intervals (in seconds)\n" +
        "for percentile latency metrics on the LLAP daemon IO decoding time.\n" +
        "hive.llap.queue.metrics.percentiles.intervals"),
    LLAP_IO_THREADPOOL_SIZE("hive.llap.io.threadpool.size", 10,
        "Specify the number of threads to use for low-level IO thread pool."),
    LLAP_IO_ENCODE_THREADPOOL_MULTIPLIER("hive.llap.io.encode.threadpool.multiplier", 2,
        "Used to determine the size of IO encode threadpool by multiplying hive.llap.io.threadpool.size" +
        "with this value. During text table reads a thread from the 'regular' IO thread pool may place a number of" +
        "encode tasks to the threads in the encode pool."),
    LLAP_USE_KERBEROS("hive.llap.kerberos.enabled", true,
        "If LLAP is configured for Kerberos authentication. This could be useful when cluster\n" +
        "is kerberized, but LLAP is not."),
    LLAP_KERBEROS_PRINCIPAL(HIVE_LLAP_DAEMON_SERVICE_PRINCIPAL_NAME, "",
        "The name of the LLAP daemon's service principal."),
    LLAP_KERBEROS_KEYTAB_FILE("hive.llap.daemon.keytab.file", "",
        "The path to the Kerberos Keytab file containing the LLAP daemon's service principal."),
    LLAP_WEBUI_SPNEGO_KEYTAB_FILE("hive.llap.webui.spnego.keytab", "",
        "The path to the Kerberos Keytab file containing the LLAP WebUI SPNEGO principal.\n" +
        "Typical value would look like /etc/security/keytabs/spnego.service.keytab."),
    LLAP_WEBUI_SPNEGO_PRINCIPAL("hive.llap.webui.spnego.principal", "",
        "The LLAP WebUI SPNEGO service principal. Configured similarly to\n" +
        "hive.server2.webui.spnego.principal"),
    LLAP_FS_KERBEROS_PRINCIPAL("hive.llap.task.principal", "",
        "The name of the principal to use to run tasks. By default, the clients are required\n" +
        "to provide tokens to access HDFS/etc."),
    LLAP_FS_KERBEROS_KEYTAB_FILE("hive.llap.task.keytab.file", "",
        "The path to the Kerberos Keytab file containing the principal to use to run tasks.\n" +
        "By default, the clients are required to provide tokens to access HDFS/etc."),
    LLAP_ZKSM_ZK_CONNECTION_STRING("hive.llap.zk.sm.connectionString", "",
        "ZooKeeper connection string for ZooKeeper SecretManager."),
    LLAP_ZKSM_ZK_CONNECTION_SSL_ENABLED("hive.llap.zk.sm.ssl.enabled", false,
            "Secure ZooKeeper connection enabled for ZooKeeper SecretManager."),
    LLAP_ZKSM_ZK_CONNECTION_SSL_KEYSTORE_LOCATION("hive.llap.zk.sm.ssl.keystore.location", "",
            "Keystore location for secure ZooKeeper connection for ZooKeeper SecretManager."),
    LLAP_ZKSM_ZK_CONNECTION_SSL_KEYSTORE_PASSWORD("hive.llap.zk.sm.ssl.keystore.password", "",
            "Keystore password for secure ZooKeeper connection for ZooKeeper SecretManager."),
    LLAP_ZKSM_ZK_CONNECTION_SSL_TRUSTSTORE_LOCATION("hive.llap.zk.sm.ssl.truststore.location", "",
            "Truststore location for secure ZooKeeper connection for ZooKeeper SecretManager."),
    LLAP_ZKSM_ZK_CONNECTION_SSL_TRUSTSTORE_PASSWORD("hive.llap.zk.sm.ssl.truststore.password", "",
            "Truststore password for secure ZooKeeper connection for ZooKeeper SecretManager."),
    LLAP_ZKSM_ZK_SESSION_TIMEOUT("hive.llap.zk.sm.session.timeout", "40s", new TimeValidator(
        TimeUnit.MILLISECONDS), "ZooKeeper session timeout for ZK SecretManager."),
    LLAP_ZK_REGISTRY_USER("hive.llap.zk.registry.user", "",
        "In the LLAP ZooKeeper-based registry, specifies the username in the Zookeeper path.\n" +
        "This should be the hive user or whichever user is running the LLAP daemon."),
    LLAP_ZK_REGISTRY_NAMESPACE("hive.llap.zk.registry.namespace", null,
        "In the LLAP ZooKeeper-based registry, overrides the ZK path namespace. Note that\n" +
        "using this makes the path management (e.g. setting correct ACLs) your responsibility."),
    // Note: do not rename to ..service.acl; Hadoop generates .hosts setting name from this,
    // resulting in a collision with existing hive.llap.daemon.service.hosts and bizarre errors.
    // These are read by Hadoop IPC, so you should check the usage and naming conventions (e.g.
    // ".blocked" is a string hardcoded by Hadoop, and defaults are enforced elsewhere in Hive)
    // before making changes or copy-pasting these.
    LLAP_SECURITY_ACL("hive.llap.daemon.acl", "*", "The ACL for LLAP daemon."),
    LLAP_SECURITY_ACL_DENY("hive.llap.daemon.acl.blocked", "", "The deny ACL for LLAP daemon."),
    LLAP_MANAGEMENT_ACL("hive.llap.management.acl", "*", "The ACL for LLAP daemon management."),
    LLAP_MANAGEMENT_ACL_DENY("hive.llap.management.acl.blocked", "",
        "The deny ACL for LLAP daemon management."),
    LLAP_PLUGIN_ACL("hive.llap.plugin.acl", "*", "The ACL for LLAP plugin AM endpoint."),
    LLAP_PLUGIN_ACL_DENY("hive.llap.plugin.acl.blocked", "",
        "The deny ACL for LLAP plugin AM endpoint."),
    LLAP_REMOTE_TOKEN_REQUIRES_SIGNING("hive.llap.remote.token.requires.signing", "true",
        new StringSet("false", "except_llap_owner", "true"),
        "Whether the token returned from LLAP management API should require fragment signing.\n" +
        "True by default; can be disabled to allow CLI to get tokens from LLAP in a secure\n" +
        "cluster by setting it to true or 'except_llap_owner' (the latter returns such tokens\n" +
        "to everyone except the user LLAP cluster is authenticating under)."),

    LLAP_DELEGATION_TOKEN_LIFETIME("hive.llap.daemon.delegation.token.lifetime", "14d",
         new TimeValidator(TimeUnit.SECONDS),
        "LLAP delegation token lifetime, in seconds if specified without a unit."),
    LLAP_DELEGATION_TOKEN_RENEW_INTERVAL("hive.llap.daemon.delegation.token.renew.interval", "1d",
        new TimeValidator(TimeUnit.SECONDS),
        "LLAP delegation token renew interval, in seconds if specified without a unit."
            + "Tokens are typically renewed in the LlapDaemons by LlapTokenManager currently."),
    LLAP_MANAGEMENT_RPC_PORT("hive.llap.management.rpc.port", 15004,
        "RPC port for LLAP daemon management service."),
    LLAP_WEB_AUTO_AUTH("hive.llap.auto.auth", false,
        "Whether or not to set Hadoop configs to enable auth in LLAP web app."),

    LLAP_DAEMON_RPC_NUM_HANDLERS("hive.llap.daemon.rpc.num.handlers", 5,
      "Number of RPC handlers for LLAP daemon.", "llap.daemon.rpc.num.handlers"),

    LLAP_PLUGIN_RPC_PORT("hive.llap.plugin.rpc.port", 0,
      "Port to use for LLAP plugin rpc server"),
    LLAP_PLUGIN_RPC_NUM_HANDLERS("hive.llap.plugin.rpc.num.handlers", 1,
      "Number of RPC handlers for AM LLAP plugin endpoint."),
    LLAP_HDFS_PACKAGE_DIR("hive.llap.hdfs.package.dir", ".yarn",
      "Package directory on HDFS used for holding collected configuration and libraries" +
      " required for YARN launch. Note: this should be set to the same as yarn.service.base.path"),
    LLAP_DAEMON_WORK_DIRS("hive.llap.daemon.work.dirs", "",
        "Working directories for the daemon. This should not be set if running as a YARN\n" +
        "Service. It must be set when not running on YARN. If the value is set when\n" +
        "running as a YARN Service, the specified value will be used.",
        "llap.daemon.work.dirs"),
    LLAP_DAEMON_YARN_SHUFFLE_PORT("hive.llap.daemon.yarn.shuffle.port", 15551,
      "YARN shuffle port for LLAP-daemon-hosted shuffle.", "llap.daemon.yarn.shuffle.port"),
    LLAP_DAEMON_YARN_CONTAINER_MB("hive.llap.daemon.yarn.container.mb", -1,
      "llap server yarn container size in MB. Used in LlapServiceDriver and package.py", "llap.daemon.yarn.container.mb"),
    LLAP_DAEMON_QUEUE_NAME("hive.llap.daemon.queue.name", null,
        "Queue name within which the llap application will run." +
        " Used in LlapServiceDriver and package.py"),
    // TODO Move the following 2 properties out of Configuration to a constant.
    LLAP_DAEMON_CONTAINER_ID("hive.llap.daemon.container.id", null,
        "ContainerId of a running LlapDaemon. Used to publish to the registry"),
    LLAP_DAEMON_NM_ADDRESS("hive.llap.daemon.nm.address", null,
        "NM Address host:rpcPort for the NodeManager on which the instance of the daemon is running.\n" +
        "Published to the llap registry. Should never be set by users"),
    LLAP_DAEMON_SHUFFLE_DIR_WATCHER_ENABLED("hive.llap.daemon.shuffle.dir.watcher.enabled", false,
      "TODO doc", "llap.daemon.shuffle.dir-watcher.enabled"),
    LLAP_DAEMON_AM_LIVENESS_HEARTBEAT_INTERVAL_MS(
      "hive.llap.daemon.am.liveness.heartbeat.interval.ms", "10000ms",
      new TimeValidator(TimeUnit.MILLISECONDS),
      "Tez AM-LLAP heartbeat interval (milliseconds). This needs to be below the task timeout\n" +
      "interval, but otherwise as high as possible to avoid unnecessary traffic.",
      "llap.daemon.am.liveness.heartbeat.interval-ms"),
    LLAP_DAEMON_AM_LIVENESS_CONNECTION_TIMEOUT_MS(
      "hive.llap.am.liveness.connection.timeout.ms", "10000ms",
      new TimeValidator(TimeUnit.MILLISECONDS),
      "Amount of time to wait on connection failures to the AM from an LLAP daemon before\n" +
      "considering the AM to be dead.", "llap.am.liveness.connection.timeout-millis"),
    LLAP_DAEMON_AM_USE_FQDN("hive.llap.am.use.fqdn", true,
        "Whether to use FQDN of the AM machine when submitting work to LLAP."),
    LLAP_DAEMON_EXEC_USE_FQDN("hive.llap.exec.use.fqdn", true,
      "On non-kerberized clusters, where the hostnames are stable but ip address changes, setting this config\n" +
        " to false will use ip address of llap daemon in execution context instead of FQDN"),
    // Not used yet - since the Writable RPC engine does not support this policy.
    LLAP_DAEMON_AM_LIVENESS_CONNECTION_SLEEP_BETWEEN_RETRIES_MS(
      "hive.llap.am.liveness.connection.sleep.between.retries.ms", "2000ms",
      new TimeValidator(TimeUnit.MILLISECONDS),
      "Sleep duration while waiting to retry connection failures to the AM from the daemon for\n" +
      "the general keep-alive thread (milliseconds).",
      "llap.am.liveness.connection.sleep-between-retries-millis"),
    LLAP_DAEMON_TASK_SCHEDULER_TIMEOUT_SECONDS(
        "hive.llap.task.scheduler.timeout.seconds", "60s",
        new TimeValidator(TimeUnit.SECONDS),
        "Amount of time to wait before failing the query when there are no llap daemons running\n" +
            "(alive) in the cluster.", "llap.daemon.scheduler.timeout.seconds"),
    LLAP_DAEMON_NUM_EXECUTORS("hive.llap.daemon.num.executors", 4,
      "Number of executors to use in LLAP daemon; essentially, the number of tasks that can be\n" +
      "executed in parallel.", "llap.daemon.num.executors"),
    LLAP_MAPJOIN_MEMORY_OVERSUBSCRIBE_FACTOR("hive.llap.mapjoin.memory.oversubscribe.factor", 0.2f,
      "Fraction of memory from hive.auto.convert.join.noconditionaltask.size that can be over subscribed\n" +
        "by queries running in LLAP mode. This factor has to be from 0.0 to 1.0. Default is 20% over subscription.\n"),
    LLAP_MEMORY_OVERSUBSCRIPTION_MAX_EXECUTORS_PER_QUERY("hive.llap.memory.oversubscription.max.executors.per.query",
      -1,
      "Used along with hive.llap.mapjoin.memory.oversubscribe.factor to limit the number of executors from\n" +
        "which memory for mapjoin can be borrowed. Default 3 (from 3 other executors\n" +
        "hive.llap.mapjoin.memory.oversubscribe.factor amount of memory can be borrowed based on which mapjoin\n" +
        "conversion decision will be made). This is only an upper bound. Lower bound is determined by number of\n" +
        "executors and configured max concurrency."),
    LLAP_MAPJOIN_MEMORY_MONITOR_CHECK_INTERVAL("hive.llap.mapjoin.memory.monitor.check.interval", 100000L,
      "Check memory usage of mapjoin hash tables after every interval of this many rows. If map join hash table\n" +
        "memory usage exceeds (hive.auto.convert.join.noconditionaltask.size * hive.hash.table.inflation.factor)\n" +
        "when running in LLAP, tasks will get killed and not retried. Set the value to 0 to disable this feature."),
    LLAP_DAEMON_AM_REPORTER_MAX_THREADS("hive.llap.daemon.am-reporter.max.threads", 4,
        "Maximum number of threads to be used for AM reporter. If this is lower than number of\n" +
        "executors in llap daemon, it would be set to number of executors at runtime.",
        "llap.daemon.am-reporter.max.threads"),
    LLAP_DAEMON_RPC_PORT("hive.llap.daemon.rpc.port", 0, "The LLAP daemon RPC port.",
      "llap.daemon.rpc.port. A value of 0 indicates a dynamic port"),
    LLAP_DAEMON_MEMORY_PER_INSTANCE_MB("hive.llap.daemon.memory.per.instance.mb", 4096,
      "The total amount of memory to use for the executors inside LLAP (in megabytes).",
      "llap.daemon.memory.per.instance.mb"),
    LLAP_DAEMON_XMX_HEADROOM("hive.llap.daemon.xmx.headroom", "5%",
      "The total amount of heap memory set aside by LLAP and not used by the executors. Can\n" +
      "be specified as size (e.g. '512Mb'), or percentage (e.g. '5%'). Note that the latter is\n" +
      "derived from the total daemon XMX, which can be different from the total executor\n" +
      "memory if the cache is on-heap; although that's not the default configuration."),
    LLAP_DAEMON_VCPUS_PER_INSTANCE("hive.llap.daemon.vcpus.per.instance", 4,
      "The total number of vcpus to use for the executors inside LLAP.",
      "llap.daemon.vcpus.per.instance"),
    LLAP_DAEMON_NUM_FILE_CLEANER_THREADS("hive.llap.daemon.num.file.cleaner.threads", 1,
      "Number of file cleaner threads in LLAP.", "llap.daemon.num.file.cleaner.threads"),
    LLAP_FILE_CLEANUP_DELAY_SECONDS("hive.llap.file.cleanup.delay.seconds", "0s",
       new TimeValidator(TimeUnit.SECONDS),
      "How long to delay before cleaning up query files in LLAP (in seconds, for debugging).",
      "llap.file.cleanup.delay-seconds"),
    LLAP_DAEMON_SERVICE_HOSTS("hive.llap.daemon.service.hosts", null,
      "Explicitly specified hosts to use for LLAP scheduling. Useful for testing. By default,\n" +
      "YARN registry is used.", "llap.daemon.service.hosts"),
    LLAP_DAEMON_SERVICE_REFRESH_INTERVAL("hive.llap.daemon.service.refresh.interval.sec", "60s",
       new TimeValidator(TimeUnit.SECONDS),
      "LLAP YARN registry service list refresh delay, in seconds.",
      "llap.daemon.service.refresh.interval"),
    LLAP_DAEMON_COMMUNICATOR_NUM_THREADS("hive.llap.daemon.communicator.num.threads", 10,
      "Number of threads to use in LLAP task communicator in Tez AM.",
      "llap.daemon.communicator.num.threads"),
    LLAP_PLUGIN_CLIENT_NUM_THREADS("hive.llap.plugin.client.num.threads", 10,
        "Number of threads to use in LLAP task plugin client."),
    LLAP_DAEMON_DOWNLOAD_PERMANENT_FNS("hive.llap.daemon.download.permanent.fns", false,
        "Whether LLAP daemon should localize the resources for permanent UDFs."),
    LLAP_TASK_SCHEDULER_AM_COLLECT_DAEMON_METRICS_MS("hive.llap.task.scheduler.am.collect.daemon.metrics.ms", "0ms",
      new TimeValidator(TimeUnit.MILLISECONDS), "Collect llap daemon metrics in the AM every given milliseconds,\n" +
      "so that the AM can use this information, to make better scheduling decisions.\n" +
      "If it's set to 0, then the feature is disabled."),
    LLAP_TASK_SCHEDULER_AM_COLLECT_DAEMON_METRICS_LISTENER(
      "hive.llap.task.scheduler.am.collect.daemon.metrics.listener", "",
      "The listener which is called when new Llap Daemon statistics is received on AM side.\n" +
      "The listener should implement the " +
      "org.apache.hadoop.hive.llap.tezplugins.metrics.LlapMetricsListener interface."),
    LLAP_NODEHEALTHCHECKS_MINTASKS(
      "hive.llap.nodehealthchecks.mintasks", 2000,
      "Specifies the minimum amount of tasks, executed by a particular LLAP daemon, before the health\n" +
      "status of the node is examined."),
    LLAP_NODEHEALTHCHECKS_MININTERVALDURATION(
      "hive.llap.nodehealthchecks.minintervalduration", "300s",
      new TimeValidator(TimeUnit.SECONDS),
      "The minimum time that needs to elapse between two actions that are the correcting results of identifying\n" +
      "an unhealthy node. Even if additional nodes are considered to be unhealthy, no action is performed until\n" +
      "this time interval has passed since the last corrective action."),
    LLAP_NODEHEALTHCHECKS_TASKTIMERATIO(
      "hive.llap.nodehealthchecks.tasktimeratio", 1.5f,
      "LLAP daemons are considered unhealthy, if their average (Map-) task execution time is significantly larger\n" +
      "than the average task execution time of other nodes. This value specifies the ratio of a node to other\n" +
      "nodes, which is considered as threshold for unhealthy. A value of 1.5 for example considers a node to be\n" +
      "unhealthy if its average task execution time is 50% larger than the average of other nodes."),
    LLAP_NODEHEALTHCHECKS_EXECUTORRATIO(
      "hive.llap.nodehealthchecks.executorratio", 2.0f,
      "If an unhealthy node is identified, it is blacklisted only where there is enough free executors to execute\n" +
      "the tasks. This value specifies the ratio of the free executors compared to the blacklisted ones.\n" +
      "A value of 2.0 for example defines that we blacklist an unhealthy node only if we have 2 times more\n" +
      "free executors on the remaining nodes than the unhealthy node."),
    LLAP_NODEHEALTHCHECKS_MAXNODES(
      "hive.llap.nodehealthchecks.maxnodes", 1,
      "The maximum number of blacklisted nodes. If there are at least this number of blacklisted nodes\n" +
      "the listener will not blacklist further nodes even if all the conditions are met."),
    LLAP_TASK_SCHEDULER_AM_REGISTRY_NAME("hive.llap.task.scheduler.am.registry", "llap",
      "AM registry name for LLAP task scheduler plugin to register with."),
    LLAP_TASK_SCHEDULER_AM_REGISTRY_PRINCIPAL("hive.llap.task.scheduler.am.registry.principal", "",
      "The name of the principal used to access ZK AM registry securely."),
    LLAP_TASK_SCHEDULER_AM_REGISTRY_KEYTAB_FILE("hive.llap.task.scheduler.am.registry.keytab.file", "",
      "The path to the Kerberos keytab file used to access ZK AM registry securely."),
    LLAP_TASK_SCHEDULER_NODE_REENABLE_MIN_TIMEOUT_MS(
      "hive.llap.task.scheduler.node.reenable.min.timeout.ms", "200ms",
      new TimeValidator(TimeUnit.MILLISECONDS),
      "Minimum time after which a previously disabled node will be re-enabled for scheduling,\n" +
      "in milliseconds. This may be modified by an exponential back-off if failures persist.",
      "llap.task.scheduler.node.re-enable.min.timeout.ms"),
    LLAP_TASK_SCHEDULER_NODE_REENABLE_MAX_TIMEOUT_MS(
      "hive.llap.task.scheduler.node.reenable.max.timeout.ms", "10000ms",
      new TimeValidator(TimeUnit.MILLISECONDS),
      "Maximum time after which a previously disabled node will be re-enabled for scheduling,\n" +
      "in milliseconds. This may be modified by an exponential back-off if failures persist.",
      "llap.task.scheduler.node.re-enable.max.timeout.ms"),
    LLAP_TASK_SCHEDULER_NODE_DISABLE_BACK_OFF_FACTOR(
      "hive.llap.task.scheduler.node.disable.backoff.factor", 1.5f,
      "Backoff factor on successive blacklists of a node due to some failures. Blacklist times\n" +
      "start at the min timeout and go up to the max timeout based on this backoff factor.",
      "llap.task.scheduler.node.disable.backoff.factor"),
    LLAP_TASK_SCHEDULER_PREEMPT_INDEPENDENT("hive.llap.task.scheduler.preempt.independent", false,
      "Whether the AM LLAP scheduler should preempt a lower priority task for a higher pri one\n" +
      "even if the former doesn't depend on the latter (e.g. for two parallel sides of a union)."),
    LLAP_TASK_SCHEDULER_NUM_SCHEDULABLE_TASKS_PER_NODE(
      "hive.llap.task.scheduler.num.schedulable.tasks.per.node", 0,
      "The number of tasks the AM TaskScheduler will try allocating per node. 0 indicates that\n" +
      "this should be picked up from the Registry. -1 indicates unlimited capacity; positive\n" +
      "values indicate a specific bound.", "llap.task.scheduler.num.schedulable.tasks.per.node"),
    LLAP_TASK_SCHEDULER_LOCALITY_DELAY(
        "hive.llap.task.scheduler.locality.delay", "0ms",
        new TimeValidator(TimeUnit.MILLISECONDS, -1l, true, Long.MAX_VALUE, true),
        "Amount of time to wait before allocating a request which contains location information," +
            " to a location other than the ones requested. Set to -1 for an infinite delay, 0" +
            "for no delay."
    ),
    LLAP_DAEMON_TASK_PREEMPTION_METRICS_INTERVALS(
        "hive.llap.daemon.task.preemption.metrics.intervals", "30,60,300",
        "Comma-delimited set of integers denoting the desired rollover intervals (in seconds)\n" +
        " for percentile latency metrics. Used by LLAP daemon task scheduler metrics for\n" +
        " time taken to kill task (due to preemption) and useful time wasted by the task that\n" +
        " is about to be preempted."
    ),
    LLAP_DAEMON_TASK_SCHEDULER_WAIT_QUEUE_SIZE("hive.llap.daemon.task.scheduler.wait.queue.size",
      10, "LLAP scheduler maximum queue size.", "llap.daemon.task.scheduler.wait.queue.size"),
    LLAP_DAEMON_WAIT_QUEUE_COMPARATOR_CLASS_NAME(
      "hive.llap.daemon.wait.queue.comparator.class.name",
      "org.apache.hadoop.hive.llap.daemon.impl.comparator.ShortestJobFirstComparator",
      "The priority comparator to use for LLAP scheduler priority queue. The built-in options\n" +
      "are org.apache.hadoop.hive.llap.daemon.impl.comparator.ShortestJobFirstComparator and\n" +
      ".....FirstInFirstOutComparator", "llap.daemon.wait.queue.comparator.class.name"),
    LLAP_DAEMON_TASK_SCHEDULER_ENABLE_PREEMPTION(
      "hive.llap.daemon.task.scheduler.enable.preemption", true,
      "Whether non-finishable running tasks (e.g. a reducer waiting for inputs) should be\n" +
      "preempted by finishable tasks inside LLAP scheduler.",
      "llap.daemon.task.scheduler.enable.preemption"),
    LLAP_DAEMON_METRICS_TIMED_WINDOW_AVERAGE_DATA_POINTS(
      "hive.llap.daemon.metrics.timed.window.average.data.points", 0,
      "The number of data points stored for calculating executor metrics timed averages.\n" +
      "Currently used for ExecutorNumExecutorsAvailableAverage and ExecutorNumQueuedRequestsAverage\n" +
      "0 means that average calculation is turned off"),
    LLAP_DAEMON_METRICS_TIMED_WINDOW_AVERAGE_WINDOW_LENGTH(
      "hive.llap.daemon.metrics.timed.window.average.window.length", "1m",
      new TimeValidator(TimeUnit.NANOSECONDS),
      "The length of the time window used for calculating executor metrics timed averages.\n" +
      "Currently used for ExecutorNumExecutorsAvailableAverage and ExecutorNumQueuedRequestsAverage\n"),
    LLAP_DAEMON_METRICS_SIMPLE_AVERAGE_DATA_POINTS(
      "hive.llap.daemon.metrics.simple.average.data.points", 0,
      "The number of data points stored for calculating executor metrics simple averages.\n" +
      "Currently used for AverageQueueTime and AverageResponseTime\n" +
      "0 means that average calculation is turned off"),
    LLAP_TASK_COMMUNICATOR_CONNECTION_TIMEOUT_MS(
      "hive.llap.task.communicator.connection.timeout.ms", "16000ms",
      new TimeValidator(TimeUnit.MILLISECONDS),
      "Connection timeout (in milliseconds) before a failure to an LLAP daemon from Tez AM.",
      "llap.task.communicator.connection.timeout-millis"),
    LLAP_TASK_COMMUNICATOR_LISTENER_THREAD_COUNT(
        "hive.llap.task.communicator.listener.thread-count", 30,
        "The number of task communicator listener threads."),
    LLAP_MAX_CONCURRENT_REQUESTS_PER_NODE("hive.llap.max.concurrent.requests.per.daemon", 12,
        "Maximum number of concurrent requests to one daemon from Tez AM"),
    LLAP_TASK_COMMUNICATOR_CONNECTION_SLEEP_BETWEEN_RETRIES_MS(
      "hive.llap.task.communicator.connection.sleep.between.retries.ms", "2000ms",
      new TimeValidator(TimeUnit.MILLISECONDS),
      "Sleep duration (in milliseconds) to wait before retrying on error when obtaining a\n" +
      "connection to LLAP daemon from Tez AM.",
      "llap.task.communicator.connection.sleep-between-retries-millis"),
    LLAP_TASK_UMBILICAL_SERVER_PORT("hive.llap.daemon.umbilical.port", "0",
      "LLAP task umbilical server RPC port or range of ports to try in case "
          + "the first port is occupied"),
    LLAP_DAEMON_WEB_PORT("hive.llap.daemon.web.port", 15002, "LLAP daemon web UI port.",
      "llap.daemon.service.port"),
    LLAP_DAEMON_WEB_SSL("hive.llap.daemon.web.ssl", false,
      "Whether LLAP daemon web UI should use SSL.", "llap.daemon.service.ssl"),
    LLAP_DAEMON_WEB_XFRAME_ENABLED("hive.llap.daemon.web.xframe.enabled", true,
        "Whether to enable xframe on LLAP daemon webUI\n"),
    LLAP_DAEMON_WEB_XFRAME_VALUE("hive.llap.daemon.web.xframe.value", "SAMEORIGIN",
        "Configuration to allow the user to set the x_frame-options value\n"),
    LLAP_CLIENT_CONSISTENT_SPLITS("hive.llap.client.consistent.splits", true,
        "Whether to setup split locations to match nodes on which llap daemons are running, " +
        "instead of using the locations provided by the split itself. If there is no llap daemon " +
        "running, fall back to locations provided by the split. This is effective only if " +
        "hive.execution.mode is llap"),
    LLAP_SPLIT_LOCATION_PROVIDER_CLASS("hive.llap.split.location.provider.class",
      "org.apache.hadoop.hive.ql.exec.tez.HostAffinitySplitLocationProvider",
      "Split location provider class to use during split generation for LLAP. This class should implement\n" +
        "org.apache.hadoop.mapred.split.SplitLocationProvider interface"),
    LLAP_VALIDATE_ACLS("hive.llap.validate.acls", true,
        "Whether LLAP should reject permissive ACLs in some cases (e.g. its own management\n" +
        "protocol or ZK paths), similar to how ssh refuses a key with bad access permissions."),
    LLAP_DAEMON_OUTPUT_SERVICE_PORT("hive.llap.daemon.output.service.port", 15003,
        "LLAP daemon output service port"),
    LLAP_DAEMON_OUTPUT_STREAM_TIMEOUT("hive.llap.daemon.output.stream.timeout", "120s",
        new TimeValidator(TimeUnit.SECONDS),
        "The timeout for the client to connect to LLAP output service and start the fragment\n" +
        "output after sending the fragment. The fragment will fail if its output is not claimed."),
    LLAP_DAEMON_OUTPUT_SERVICE_SEND_BUFFER_SIZE("hive.llap.daemon.output.service.send.buffer.size",
        128 * 1024, "Send buffer size to be used by LLAP daemon output service"),
    LLAP_DAEMON_OUTPUT_SERVICE_MAX_PENDING_WRITES("hive.llap.daemon.output.service.max.pending.writes",
        8, "Maximum number of queued writes allowed per connection when sending data\n" +
        " via the LLAP output service to external clients."),
    LLAP_EXTERNAL_SPLITS_TEMP_TABLE_STORAGE_FORMAT("hive.llap.external.splits.temp.table.storage.format",
        "orc", new StringSet("default", "text", "orc"),
        "Storage format for temp tables created using LLAP external client"),
    LLAP_EXTERNAL_CLIENT_USE_HYBRID_CALENDAR("hive.llap.external.client.use.hybrid.calendar",
        false,
        "Whether to use hybrid calendar for parsing of data/timestamps."),

    // ====== confs for llap-external-client cloud deployment ======
    LLAP_EXTERNAL_CLIENT_CLOUD_DEPLOYMENT_SETUP_ENABLED(
        "hive.llap.external.client.cloud.deployment.setup.enabled", false,
        "Tells whether to enable additional RPC port, auth mechanism for llap external clients. This is meant"
            + "for cloud based deployments. When true, it has following effects - \n"
            + "1. Enables an extra RPC port on LLAP daemon to accept fragments from external clients. See"
            + "hive.llap.external.client.cloud.rpc.port\n"
            + "2. Uses external hostnames of LLAP in splits, so that clients can submit from outside of cloud. "
            + "Env variable PUBLIC_HOSTNAME should be available on LLAP machines.\n"
            + "3. Uses JWT based authentication for splits to be validated at LLAP. See "
            + "hive.llap.external.client.cloud.jwt.shared.secret.provider"),
    LLAP_EXTERNAL_CLIENT_CLOUD_RPC_PORT("hive.llap.external.client.cloud.rpc.port", 30004,
        "The LLAP daemon RPC port for external clients when llap is running in cloud environment."),
    LLAP_EXTERNAL_CLIENT_CLOUD_OUTPUT_SERVICE_PORT("hive.llap.external.client.cloud.output.service.port", 30005,
                "LLAP output service port when llap is running in cloud environment"),
    LLAP_EXTERNAL_CLIENT_CLOUD_JWT_SHARED_SECRET_PROVIDER(
        "hive.llap.external.client.cloud.jwt.shared.secret.provider",
        "org.apache.hadoop.hive.llap.security.DefaultJwtSharedSecretProvider",
        "Shared secret provider to be used to sign JWT"),
    LLAP_EXTERNAL_CLIENT_CLOUD_JWT_SHARED_SECRET("hive.llap.external.client.cloud.jwt.shared.secret",
        "",
        "The LLAP daemon RPC port for external clients when llap is running in cloud environment. "
            + "Length of the secret should be >= 32 bytes"),
    // ====== confs for llap-external-client cloud deployment ======

    LLAP_ENABLE_GRACE_JOIN_IN_LLAP("hive.llap.enable.grace.join.in.llap", false,
        "Override if grace join should be allowed to run in llap."),

    LLAP_HS2_ENABLE_COORDINATOR("hive.llap.hs2.coordinator.enabled", true,
        "Whether to create the LLAP coordinator; since execution engine and container vs llap\n" +
        "settings are both coming from job configs, we don't know at start whether this should\n" +
        "be created. Default true."),
    LLAP_DAEMON_LOGGER("hive.llap.daemon.logger", Constants.LLAP_LOGGER_NAME_QUERY_ROUTING,
        new StringSet(Constants.LLAP_LOGGER_NAME_QUERY_ROUTING,
            Constants.LLAP_LOGGER_NAME_RFA,
            Constants.LLAP_LOGGER_NAME_CONSOLE),
        "logger used for llap-daemons."),
    LLAP_COLLECT_LOCK_METRICS("hive.llap.lockmetrics.collect", false,
        "Whether lock metrics (wait times, counts) are collected for LLAP "
        + "related locks"),
    LLAP_TASK_TIME_SUMMARY(
        "hive.llap.task.time.print.summary", false,
        "Display queue and runtime of tasks by host for every query executed by the shell."),

    LLAP_LOCAL_DIR_CLEANER_CLEANUP_INTERVAL(
        "hive.llap.local.dir.cleaner.cleanup.interval", "2h", new TimeValidator(TimeUnit.HOURS),
      "Interval by which the LocalDirCleaner service in LLAP daemon checks for stale/old files." +
      "Under normal circumstances, local files are cleaned up properly, so it's not recommended to" +
      "set this more frequent than a couple of hours. Default is 2 hours."),
    LLAP_LOCAL_DIR_CLEANER_FILE_MODIFY_TIME_THRESHOLD("hive.llap.local.dir.cleaner.file.modify.time.threshold", "24h",
        new TimeValidator(TimeUnit.HOURS),
      "Threshold time for LocalDirCleaner: if a regular file's modify time is older than this value, the file gets deleted." +
      "Defaults to 86400s (1 day), which is a reasonable period for a local file to be considered as a stale one."),

    HIVE_TRIGGER_VALIDATION_INTERVAL("hive.trigger.validation.interval", "500ms",
      new TimeValidator(TimeUnit.MILLISECONDS),
      "Interval for validating triggers during execution of a query. Triggers defined in resource plan will get\n" +
        "validated for all SQL operations after every defined interval (default: 500ms) and corresponding action\n" +
        "defined in the trigger will be taken"),

    N_WAY_JOIN_REORDER("hive.reorder.nway.joins", true,
      "Runs reordering of tables within single n-way join (i.e.: picks streamtable)"),
    HIVE_MERGE_NWAY_JOINS("hive.merge.nway.joins", false,
      "Merge adjacent joins into a single n-way join"),
    HIVE_LOG_N_RECORDS("hive.log.every.n.records", 0L, new RangeValidator(0L, null),
      "If value is greater than 0 logs in fixed intervals of size n rather than exponentially."),
    /**
     * @deprecated Use MetastoreConf.MSCK_PATH_VALIDATION
     */
    @Deprecated
    HIVE_MSCK_PATH_VALIDATION("hive.msck.path.validation", "throw",
        new StringSet("throw", "skip", "ignore"), "The approach msck should take with HDFS " +
       "directories that are partition-like but contain unsupported characters. 'throw' (an " +
       "exception) is the default; 'skip' will skip the invalid directories and still repair the" +
       " others; 'ignore' will skip the validation (legacy behavior, causes bugs in many cases)"),
    /**
     * @deprecated Use MetastoreConf.MSCK_REPAIR_BATCH_SIZE
     */
    @Deprecated
    HIVE_MSCK_REPAIR_BATCH_SIZE(
        "hive.msck.repair.batch.size", 3000,
        "Batch size for the msck repair command. If the value is greater than zero,\n "
            + "it will execute batch wise with the configured batch size. In case of errors while\n"
            + "adding unknown partitions the batch size is automatically reduced by half in the subsequent\n"
            + "retry attempt. The default value is 3000 which means it will execute in the batches of 3000."),
    /**
     * @deprecated Use MetastoreConf.MSCK_REPAIR_BATCH_MAX_RETRIES
     */
    @Deprecated
    HIVE_MSCK_REPAIR_BATCH_MAX_RETRIES("hive.msck.repair.batch.max.retries", 4,
        "Maximum number of retries for the msck repair command when adding unknown partitions.\n "
        + "If the value is greater than zero it will retry adding unknown partitions until the maximum\n"
        + "number of attempts is reached or batch size is reduced to 0, whichever is earlier.\n"
        + "In each retry attempt it will reduce the batch size by a factor of 2 until it reaches zero.\n"
        + "If the value is set to zero it will retry until the batch size becomes zero as described above."),
    HIVE_SERVER2_LLAP_CONCURRENT_QUERIES("hive.server2.llap.concurrent.queries", -1,
        "The number of queries allowed in parallel via llap. Negative number implies 'infinite'."),
    HIVE_TEZ_ENABLE_MEMORY_MANAGER("hive.tez.enable.memory.manager", true,
        "Enable memory manager for tez"),
    HIVE_HASH_TABLE_INFLATION_FACTOR("hive.hash.table.inflation.factor", (float) 2.0,
        "Expected inflation factor between disk/in memory representation of hash tables"),
    HIVE_LOG_TRACE_ID("hive.log.trace.id", "",
        "Log tracing id that can be used by upstream clients for tracking respective logs. " +
        "Truncated to " + LOG_PREFIX_LENGTH + " characters. Defaults to use auto-generated session id."),

    HIVE_MM_AVOID_GLOBSTATUS_ON_S3("hive.mm.avoid.s3.globstatus", true,
        "Whether to use listFiles (optimized on S3) instead of globStatus when on S3."),

    // If a parameter is added to the restricted list, add a test in TestRestrictedList.Java
    HIVE_CONF_RESTRICTED_LIST("hive.conf.restricted.list",
        "hive.security.authenticator.manager,hive.security.authorization.manager," +
        "hive.security.metastore.authorization.manager,hive.security.metastore.authenticator.manager," +
        "hive.users.in.admin.role,hive.server2.xsrf.filter.enabled,hive.server2.csrf.filter.enabled,hive.security.authorization.enabled," +
            "hive.distcp.privileged.doAs," +
            "hive.server2.authentication.ldap.baseDN," +
            "hive.server2.authentication.ldap.url," +
            "hive.server2.authentication.ldap.Domain," +
            "hive.server2.authentication.ldap.groupDNPattern," +
            "hive.server2.authentication.ldap.groupFilter," +
            "hive.server2.authentication.ldap.userDNPattern," +
            "hive.server2.authentication.ldap.userFilter," +
            "hive.server2.authentication.ldap.groupMembershipKey," +
            "hive.server2.authentication.ldap.userMembershipKey," +
            "hive.server2.authentication.ldap.groupClassKey," +
            "hive.server2.authentication.ldap.customLDAPQuery," +
            "hive.server2.service.users," +
            "hive.server2.graceful.stop.timeout," +
            "hive.privilege.synchronizer," +
            "hive.privilege.synchronizer.interval," +
            "hive.query.max.length," +
            "hive.druid.broker.address.default," +
            "hive.druid.coordinator.address.default," +
            "hikaricp.," +
            "hadoop.bin.path," +
            "yarn.bin.path," +
            "hive.driver.parallel.compilation.global.limit," +
            "hive.zookeeper.ssl.keystore.location," +
            "hive.zookeeper.ssl.keystore.password," +
            "hive.zookeeper.ssl.keystore.type," +
            "hive.zookeeper.ssl.truststore.location," +
            "hive.zookeeper.ssl.truststore.password," +
            "hive.zookeeper.ssl.truststore.type," +
            "hive.iceberg.allow.datafiles.in.table.location.only," +
            "hive.hook.proto.base-directory," +
            "hive.rewrite.data.policy",
        "Comma separated list of configuration options which are immutable at runtime"),
    HIVE_CONF_HIDDEN_LIST("hive.conf.hidden.list",
        METASTORE_PWD.varname + "," + HIVE_SERVER2_SSL_KEYSTORE_PASSWORD.varname
        + "," + HIVE_SERVER2_WEBUI_SSL_KEYSTORE_PASSWORD.varname
        + "," + DRUID_METADATA_DB_PASSWORD.varname
        // Adding the S3 credentials from Hadoop config to be hidden
        + ",fs.s3.awsAccessKeyId"
        + ",fs.s3.awsSecretAccessKey"
        + ",fs.s3n.awsAccessKeyId"
        + ",fs.s3n.awsSecretAccessKey"
        + ",fs.s3a.access.key"
        + ",fs.s3a.secret.key"
        + ",fs.s3a.proxy.password"
        + ",dfs.adls.oauth2.credential"
        + ",fs.adl.oauth2.credential"
        + ",fs.azure.account.oauth2.client.secret"
        + ",hive.zookeeper.ssl.keystore.location"
        + ",hive.zookeeper.ssl.keystore.password"
        + ",hive.zookeeper.ssl.truststore.location"
        + ",hive.zookeeper.ssl.truststore.password",
        "Comma separated list of configuration options which should not be read by normal user like passwords"),
    HIVE_CONF_INTERNAL_VARIABLE_LIST("hive.conf.internal.variable.list",
        "hive.added.files.path,hive.added.jars.path,hive.added.archives.path",
        "Comma separated list of variables which are used internally and should not be configurable."),
    HIVE_QUERY_MAX_LENGTH("hive.query.max.length", "10Mb", new SizeValidator(), "The maximum" +
            " size of a query string. Enforced after variable substitutions."),
    HIVE_QUERY_TIMEOUT_SECONDS("hive.query.timeout.seconds", "0s",
        new TimeValidator(TimeUnit.SECONDS),
        "Timeout for Running Query in seconds. A nonpositive value means infinite. " +
        "If the query timeout is also set by thrift API call, the smaller one will be taken."),
    HIVE_COMPUTE_SPLITS_NUM_THREADS("hive.compute.splits.num.threads", 10,
            "How many threads Input Format should use to create splits in parallel.",
            HIVE_ORC_COMPUTE_SPLITS_NUM_THREADS.varname),
    HIVE_EXEC_INPUT_LISTING_MAX_THREADS("hive.exec.input.listing.max.threads", 0, new  SizeValidator(0L, true, 1024L, true),
        "Maximum number of threads that Hive uses to list file information from file systems (recommended > 1 for blobstore)."),

    HIVE_QUERY_REEXECUTION_ENABLED("hive.query.reexecution.enabled", true,
        "Enable query reexecutions"),
    HIVE_QUERY_REEXECUTION_STRATEGIES("hive.query.reexecution.strategies",
        "overlay,reoptimize,reexecute_lost_am,dagsubmit,recompile_without_cbo,write_conflict",
        "comma separated list of plugin can be used. If custom plugins, specify the class name:\n"
            + "  overlay: hiveconf subtree 'reexec.overlay' is used as an overlay in case of an execution errors out\n"
            + "  reoptimize: collects operator statistics during execution and recompile the query after a failure\n"
            + "  recompile_without_cbo: recompiles query after a CBO failure\n"
            + "  reexecute_lost_am: reexecutes query if it failed due to tez am node gets decommissioned\n "
            + "  write_conflict: retries the query once if the query failed due to write_conflict\n"
            + "  custom plugins: e.g.\n"
            + "    org.apache.hadoop.hive.ql.reexec.custom.CustomPlugin1"),
    HIVE_QUERY_REEXECUTION_STATS_PERSISTENCE("hive.query.reexecution.stats.persist.scope", "metastore",
        new StringSet("query", "hiveserver", "metastore"),
        "Sets the persistence scope of runtime statistics\n"
            + "  query: runtime statistics are only used during re-execution\n"
            + "  hiveserver: runtime statistics are persisted in the hiveserver - all sessions share it\n"
            + "  metastore: runtime statistics are persisted in the metastore as well"),

    HIVE_TXN_MAX_RETRYSNAPSHOT_COUNT("hive.txn.retrysnapshot.max.count", 5, new RangeValidator(1, 20),
        "Maximum number of snapshot invalidate attempts per request."),

    HIVE_QUERY_MAX_REEXECUTION_COUNT("hive.query.reexecution.max.count", 1,
        "Maximum number of re-executions for a single query."),
    HIVE_QUERY_REEXECUTION_ALWAYS_COLLECT_OPERATOR_STATS("hive.query.reexecution.always.collect.operator.stats", false,
        "If sessionstats are enabled; this option can be used to collect statistics all the time"),
    HIVE_QUERY_REEXECUTION_STATS_CACHE_BATCH_SIZE("hive.query.reexecution.stats.cache.batch.size", -1,
        "If runtime stats are stored in metastore; the maximal batch size per round during load."),
    HIVE_QUERY_REEXECUTION_STATS_CACHE_SIZE("hive.query.reexecution.stats.cache.size", 100_000,
        "Size of the runtime statistics cache. Unit is: OperatorStat entry; a query plan consist ~100."),
    HIVE_QUERY_PLANMAPPER_LINK_RELNODES("hive.query.planmapper.link.relnodes", true,
        "Whether to link Calcite nodes to runtime statistics."),
    HIVE_QUERY_MAX_RECOMPILATION_COUNT("hive.query.recompilation.max.count", 1,
        "Maximum number of re-compilations for a single query."),
    HIVE_SCHEDULED_QUERIES_EXECUTOR_ENABLED("hive.scheduled.queries.executor.enabled", true,
        "Controls whether HS2 will run scheduled query executor."),
    HIVE_SCHEDULED_QUERIES_NAMESPACE("hive.scheduled.queries.namespace", "hive",
        "Sets the scheduled query namespace to be used. New scheduled queries are created in this namespace;"
            + "and execution is also bound to the namespace"),
    HIVE_SCHEDULED_QUERIES_EXECUTOR_IDLE_SLEEP_TIME("hive.scheduled.queries.executor.idle.sleep.time", "60s",
        new TimeValidator(TimeUnit.SECONDS),
        "Time to sleep between querying for the presence of a scheduled query."),
    HIVE_SCHEDULED_QUERIES_EXECUTOR_PROGRESS_REPORT_INTERVAL("hive.scheduled.queries.executor.progress.report.interval",
        "60s",
        new TimeValidator(TimeUnit.SECONDS),
        "While scheduled queries are in flight; "
            + "a background update happens periodically to report the actual state of the query"),
    HIVE_SCHEDULED_QUERIES_CREATE_AS_ENABLED("hive.scheduled.queries.create.as.enabled", true,
        "This option sets the default behaviour of newly created scheduled queries."),
    HIVE_QUERY_HISTORY_ENABLED("hive.query.history.enabled", false,
        "Whether to start QueryHistoryService in HS2."),
    HIVE_QUERY_HISTORY_EXPLAIN_PLAN_ENABLED("hive.query.history.explain.plan.enabled", true,
        "Whether to collect and store explain plan in the query history. Default is true."),
    HIVE_QUERY_HISTORY_EXEC_SUMMARY_ENABLED("hive.query.history.exec.summary.enabled", true,
        "Whether to collect and store execution summary in the query history. Default is true."),
    HIVE_QUERY_HISTORY_BATCH_SIZE("hive.query.history.batch.size", 200,
        "The maximum amount of records held in memory " +
            "before query history service persists them to the target table. " +
            "A small value (like 1-5) will lead to more real-time behavior with the price of small files. " +
            "Set this to 0 to wait for the records to be persisted synchronously (not recommended in production)."),
    HIVE_QUERY_HISTORY_MAX_MEMORY_BYTES("hive.query.history.max.memory.bytes",
        20 * 1024 * 1024, // 20MB
        "The maximum size in bytes the query history queue can grow in the memory before query history service " +
            "persists them to the target table." +
            "Set this to 0 to disable this check (not recommended in production in order to keep HS2's heap under " +
            "control)"),
    HIVE_QUERY_HISTORY_FLUSH_INTERVAL_SECONDS("hive.query.history.flush.interval.seconds",
        60 * 60, // 1h
        "The query history service attempts to flush records from memory to the Iceberg table regardless of the " +
            "current batch size. This ensures that history records are not kept inaccessible to users for extended " +
            "periods. This property defines the interval for this operation. The default value of 1 hour is " +
            "presumably a reasonable tradeoff between generating smaller files and allowing sufficient time for " +
            "records to arrive. Set this to 0 to disable (so to 'force' batch size-based strategies)"),
    HIVE_QUERY_HISTORY_REPOSITORY_CLASS("hive.query.history.repository.class",
        "org.apache.hadoop.hive.ql.queryhistory.repository.IcebergRepository",
        "The class implementing QueryHistoryRepository to be used for persisting Record instances"),
    HIVE_SECURITY_AUTHORIZATION_SCHEDULED_QUERIES_SUPPORTED("hive.security.authorization.scheduled.queries.supported",
        false,
        "Enable this if the configured authorizer is able to handle scheduled query related calls."),
    HIVE_SCHEDULED_QUERIES_MAX_EXECUTORS("hive.scheduled.queries.max.executors", 4, new RangeValidator(1, null),
        "Maximal number of scheduled query executors to allow."),

    HIVE_ASYNC_CLEANUP_SERVICE_THREAD_COUNT("hive.async.cleanup.service.thread.count", 10, new RangeValidator(0, null),
        "Number of threads that run some eventual cleanup operations after queries/sessions close. 0 means cleanup is sync."),
    HIVE_ASYNC_CLEANUP_SERVICE_QUEUE_SIZE("hive.async.cleanup.service.queue.size", 10000, new RangeValidator(10, Integer.MAX_VALUE),
        "Size of the async cleanup queue. If cleanup queue is full, cleanup operations become synchronous. " +
            "Applicable only when number of async cleanup is turned on."),
    HIVE_QUERY_RESULTS_CACHE_ENABLED("hive.query.results.cache.enabled", true,
        "If the query results cache is enabled. This will keep results of previously executed queries " +
        "to be reused if the same query is executed again."),

    HIVE_QUERY_RESULTS_CACHE_NONTRANSACTIONAL_TABLES_ENABLED("hive.query.results.cache.nontransactional.tables.enabled", false,
        "If the query results cache is enabled for queries involving non-transactional tables." +
        "Users who enable this setting should be willing to tolerate some amount of stale results in the cache."),

    HIVE_QUERY_RESULTS_CACHE_WAIT_FOR_PENDING_RESULTS("hive.query.results.cache.wait.for.pending.results", true,
        "Should a query wait for the pending results of an already running query, " +
        "in order to use the cached result when it becomes ready"),

    HIVE_QUERY_RESULTS_CACHE_DIRECTORY("hive.query.results.cache.directory",
        "/tmp/hive/_resultscache_",
        "Location of the query results cache directory. Temporary results from queries " +
        "will be moved to this location."),

    HIVE_QUERY_RESULTS_CACHE_MAX_ENTRY_LIFETIME("hive.query.results.cache.max.entry.lifetime", "3600s",
        new TimeValidator(TimeUnit.SECONDS),
        "Maximum lifetime in seconds for an entry in the query results cache. A nonpositive value means infinite."),

    HIVE_QUERY_RESULTS_CACHE_MAX_SIZE("hive.query.results.cache.max.size",
        (long) 2 * 1024 * 1024 * 1024,
        "Maximum total size in bytes that the query results cache directory is allowed to use on the filesystem."),

    HIVE_QUERY_RESULTS_CACHE_MAX_ENTRY_SIZE("hive.query.results.cache.max.entry.size",
        (long) 10 * 1024 * 1024,
        "Maximum size in bytes that a single query result is allowed to use in the results cache directory"),

    HIVE_NOTFICATION_EVENT_POLL_INTERVAL("hive.notification.event.poll.interval", "60s",
        new TimeValidator(TimeUnit.SECONDS),
        "How often the notification log is polled for new NotificationEvents from the metastore." +
        "A nonpositive value means the notification log is never polled."),

    HIVE_NOTFICATION_EVENT_CONSUMERS("hive.notification.event.consumers",
        "org.apache.hadoop.hive.ql.cache.results.QueryResultsCache$InvalidationEventConsumer",
        "Comma-separated list of class names extending EventConsumer," +
         "to handle the NotificationEvents retrieved by the notification event poll."),

    HIVE_DESCRIBE_PARTITIONED_TABLE_IGNORE_STATS("hive.describe.partitionedtable.ignore.stats", false,
        "Disable partitioned table stats collection for 'DESCRIBE FORMATTED' or 'DESCRIBE EXTENDED' commands."),

    HIVE_SERVER2_ICEBERG_METADATA_GENERATOR_THREADS("hive.server2.iceberg.metadata.generator.threads", 10,
        "Number of threads used to scan partition directories for data files and update/generate iceberg metadata"),

    /* BLOBSTORE section */

    HIVE_BLOBSTORE_SUPPORTED_SCHEMES("hive.blobstore.supported.schemes", "s3,s3a,s3n",
            "Comma-separated list of supported blobstore schemes."),

    HIVE_BLOBSTORE_USE_BLOBSTORE_AS_SCRATCHDIR("hive.blobstore.use.blobstore.as.scratchdir", false,
            "Enable the use of scratch directories directly on blob storage systems (it may cause performance penalties)."),

    HIVE_BLOBSTORE_OPTIMIZATIONS_ENABLED("hive.blobstore.optimizations.enabled", true,
            "This parameter enables a number of optimizations when running on blobstores:\n" +
            "(1) If hive.blobstore.use.blobstore.as.scratchdir is false, force the last Hive job to write to the blobstore.\n" +
            "This is a performance optimization that forces the final FileSinkOperator to write to the blobstore.\n" +
            "See HIVE-15121 for details."),

    HIVE_USE_SCRATCHDIR_FOR_STAGING("hive.use.scratchdir.for.staging", false,
        "Use ${hive.exec.scratchdir} for query results instead of ${hive.exec.stagingdir}.\n" +
            "This stages query results in ${hive.exec.scratchdir} before moving to final destination."),

    HIVE_ADDITIONAL_CONFIG_FILES("hive.additional.config.files", "",
            "The names of additional config files, such as ldap-site.xml," +
                    "tez-site.xml, etc in comma separated list."),

    REWRITE_POLICY("hive.rewrite.data.policy", "DEFAULT", 
        "Defines the rewrite policy, the valid values are those defined in RewritePolicy enum"),

    HIVE_OTEL_METRICS_FREQUENCY_SECONDS("hive.otel.metrics.frequency.seconds", "0s",
        new TimeValidator(TimeUnit.SECONDS),
        "Frequency at which the OTEL Metrics are refreshed, A value of 0 or less disable the feature"),

    HIVE_OTEL_COLLECTOR_ENDPOINT("hive.otel.collector.endpoint", "",
        "The endpoint to send all OTLP traces, metrics, and logs to. Often the address of an OpenTelemetry Collector."
            + " Must be a URL with a scheme of either http or https based on the use of TLS/"),

    HIVE_OTEL_EXPORTER_TIMEOUT("hive.otel.exporter.timeout", "10m", new TimeValidator(TimeUnit.SECONDS),
        "The maximum amount of time allowed for the OTEL exporter to complete an export operation."
            + "If the operation exceeds this duration, it will time out."),

    HIVE_OTEL_RETRY_INITIAL_BACKOFF("hive.otel.retry.initial.backoff", "10s", new TimeValidator(TimeUnit.SECONDS),
        "The initial delay before the first retry attempt in case of a failure in the OTEL exporter."
            + "This value serves as the starting point for the exponential backoff strategy."),

    HIVE_OTEL_RETRY_MAX_BACKOFF("hive.otel.retry.max.backoff", "1m", new TimeValidator(TimeUnit.SECONDS),
        "The maximum time to wait between retries for the OTEL exporter."
            + "This sets an upper limit on the backoff interval, ensuring retries do not exceed this duration even with exponential backoff."),

    HIVE_OTEL_RETRY_BACKOFF_MULTIPLIER("hive.otel.retry.backoff.multiplier", 5f,
        "The multiplier applied to the backoff interval for retries in the OTEL exporter."
            + "This determines how much the backoff interval increases after each failed attempt, following an exponential backoff strategy.");

    public final String varname;
    public final String altName;
    private final String defaultExpr;

    public final String defaultStrVal;
    public final int defaultIntVal;
    public final long defaultLongVal;
    public final float defaultFloatVal;
    public final boolean defaultBoolVal;

    private final Class<?> valClass;
    private final VarType valType;

    private final Validator validator;

    private final String description;

    private final boolean excluded;
    private final boolean caseSensitive;

    ConfVars(String varname, Object defaultVal, String description) {
      this(varname, defaultVal, null, description, true, false, null);
    }

    ConfVars(String varname, Object defaultVal, String description, String altName) {
      this(varname, defaultVal, null, description, true, false, altName);
    }

    ConfVars(String varname, Object defaultVal, Validator validator, String description,
        String altName) {
      this(varname, defaultVal, validator, description, true, false, altName);
    }

    ConfVars(String varname, Object defaultVal, String description, boolean excluded) {
      this(varname, defaultVal, null, description, true, excluded, null);
    }

    ConfVars(String varname, String defaultVal, boolean caseSensitive, String description) {
      this(varname, defaultVal, null, description, caseSensitive, false, null);
    }

    ConfVars(String varname, Object defaultVal, Validator validator, String description) {
      this(varname, defaultVal, validator, description, true, false, null);
    }

    ConfVars(String varname, Object defaultVal, Validator validator, String description, boolean excluded) {
      this(varname, defaultVal, validator, description, true, excluded, null);
    }

    ConfVars(String varname, Object defaultVal, Validator validator, String description,
        boolean caseSensitive, boolean excluded, String altName) {
      this.varname = varname;
      this.validator = validator;
      this.description = description;
      this.defaultExpr = defaultVal == null ? null : String.valueOf(defaultVal);
      this.excluded = excluded;
      this.caseSensitive = caseSensitive;
      this.altName = altName;
      if (defaultVal == null || defaultVal instanceof String) {
        this.valClass = String.class;
        this.valType = VarType.STRING;
        this.defaultStrVal = SystemVariables.substitute((String)defaultVal);
        this.defaultIntVal = -1;
        this.defaultLongVal = -1;
        this.defaultFloatVal = -1;
        this.defaultBoolVal = false;
      } else if (defaultVal instanceof Integer) {
        this.valClass = Integer.class;
        this.valType = VarType.INT;
        this.defaultStrVal = null;
        this.defaultIntVal = (Integer)defaultVal;
        this.defaultLongVal = -1;
        this.defaultFloatVal = -1;
        this.defaultBoolVal = false;
      } else if (defaultVal instanceof Long) {
        this.valClass = Long.class;
        this.valType = VarType.LONG;
        this.defaultStrVal = null;
        this.defaultIntVal = -1;
        this.defaultLongVal = (Long)defaultVal;
        this.defaultFloatVal = -1;
        this.defaultBoolVal = false;
      } else if (defaultVal instanceof Float) {
        this.valClass = Float.class;
        this.valType = VarType.FLOAT;
        this.defaultStrVal = null;
        this.defaultIntVal = -1;
        this.defaultLongVal = -1;
        this.defaultFloatVal = (Float)defaultVal;
        this.defaultBoolVal = false;
      } else if (defaultVal instanceof Boolean) {
        this.valClass = Boolean.class;
        this.valType = VarType.BOOLEAN;
        this.defaultStrVal = null;
        this.defaultIntVal = -1;
        this.defaultLongVal = -1;
        this.defaultFloatVal = -1;
        this.defaultBoolVal = (Boolean)defaultVal;
      } else {
        throw new IllegalArgumentException("Not supported type value " + defaultVal.getClass() +
            " for name " + varname);
      }
    }

    public boolean isType(String value) {
      return valType.isType(value);
    }

    public Validator getValidator() {
      return validator;
    }

    public String validate(String value) {
      return validator == null ? null : validator.validate(value);
    }

    public String validatorDescription() {
      return validator == null ? null : validator.toDescription();
    }

    public String typeString() {
      String type = valType.typeString();
      if (valType == VarType.STRING && validator != null) {
        if (validator instanceof TimeValidator) {
          type += "(TIME)";
        }
      }
      return type;
    }

    public String getRawDescription() {
      return description;
    }

    public String getDescription() {
      String validator = validatorDescription();
      if (validator != null) {
        return validator + ".\n" + description;
      }
      return description;
    }

    public boolean isExcluded() {
      return excluded;
    }

    public boolean isCaseSensitive() {
      return caseSensitive;
    }

    @Override
    public String toString() {
      return varname;
    }

    private static String findHadoopBinary() {
      String val = findHadoopHome();
      // if can't find hadoop home we can at least try /usr/bin/hadoop
      val = (val == null ? File.separator + "usr" : val)
          + File.separator + "bin" + File.separator + "hadoop";
      // Launch hadoop command file on windows.
      return val;
    }

    private static String findYarnBinary() {
      String val = findHadoopHome();
      val = (val == null ? "yarn" : val + File.separator + "bin" + File.separator + "yarn");
      return val;
    }
    private static String findMapRedBinary() {
      String val = findHadoopHome();
      val = (val == null ? "mapred" : val + File.separator + "bin" + File.separator + "mapred");
      return val;
    }

    private static String findHadoopHome() {
      String val = System.getenv("HADOOP_HOME");
      // In Hadoop 1.X and Hadoop 2.X HADOOP_HOME is gone and replaced with HADOOP_PREFIX
      if (val == null) {
        val = System.getenv("HADOOP_PREFIX");
      }
      return val;
    }

    public String getDefaultValue() {
      return valType.defaultValueString(this);
    }

    public String getDefaultExpr() {
      return defaultExpr;
    }

    private Set<String> getValidStringValues() {
      if (validator == null || !(validator instanceof StringSet)) {
        throw new RuntimeException(varname + " does not specify a list of valid values");
      }
      return ((StringSet)validator).getExpected();
    }

    enum VarType {
      STRING {
        @Override
        void checkType(String value) throws Exception { }
        @Override
        String defaultValueString(ConfVars confVar) { return confVar.defaultStrVal; }
      },
      INT {
        @Override
        void checkType(String value) throws Exception { Integer.valueOf(value); }
      },
      LONG {
        @Override
        void checkType(String value) throws Exception { Long.valueOf(value); }
      },
      FLOAT {
        @Override
        void checkType(String value) throws Exception { Float.valueOf(value); }
      },
      BOOLEAN {
        @Override
        void checkType(String value) throws Exception { Boolean.valueOf(value); }
      };

      boolean isType(String value) {
        try { checkType(value); } catch (Exception e) { return false; }
        return true;
      }
      String typeString() { return name().toUpperCase();}
      String defaultValueString(ConfVars confVar) { return confVar.defaultExpr; }
      abstract void checkType(String value) throws Exception;
    }
  }

  /**
   * Writes the default ConfVars out to a byte array and returns an input
   * stream wrapping that byte array.
   *
   * We need this in order to initialize the ConfVar properties
   * in the underling Configuration object using the addResource(InputStream)
   * method.
   *
   * It is important to use a LoopingByteArrayInputStream because it turns out
   * addResource(InputStream) is broken since Configuration tries to read the
   * entire contents of the same InputStream repeatedly without resetting it.
   * LoopingByteArrayInputStream has special logic to handle this.
   */
  private static synchronized InputStream getConfVarInputStream() {
    if (confVarByteArray == null) {
      try {
        // Create a Hadoop configuration without inheriting default settings.
        Configuration conf = new Configuration(false);

        applyDefaultNonNullConfVars(conf);

        ByteArrayOutputStream confVarBaos = new ByteArrayOutputStream();
        conf.writeXml(confVarBaos);
        confVarByteArray = confVarBaos.toByteArray();
      } catch (Exception e) {
        // We're pretty screwed if we can't load the default conf vars
        throw new RuntimeException("Failed to initialize default Hive configuration variables!", e);
      }
    }
    return new LoopingByteArrayInputStream(confVarByteArray);
  }

  @SuppressFBWarnings(value = "NP_NULL_PARAM_DEREF", justification = "Exception before reaching NP")
  public void verifyAndSet(String name, String value) throws IllegalArgumentException {
    if (modWhiteListPattern != null) {
      Matcher wlMatcher = modWhiteListPattern.matcher(name);
      if (!wlMatcher.matches()) {
        throw new IllegalArgumentException("Cannot modify " + name + " at runtime. "
            + "It is not in list of params that are allowed to be modified at runtime");
      }
    }
    if (Iterables.any(restrictList,
        restrictedVar -> name != null && name.startsWith(restrictedVar))) {
      throw new IllegalArgumentException("Cannot modify " + name + " at runtime. It is in the list"
          + " of parameters that can't be modified at runtime or is prefixed by a restricted variable");
    }
    if (isLockedConfig(name)) {
      return;
    }
    String oldValue = name != null ? get(name) : null;
    if (name == null || value == null || !value.equals(oldValue)) {
      // When either name or value is null, the set method below will fail,
      // and throw IllegalArgumentException
      set(name, value);
    }
  }

  public boolean isHiddenConfig(String name) {
    return Iterables.any(hiddenSet, hiddenVar -> name.startsWith(hiddenVar));
  }

  public boolean isLockedConfig(String name) {
    return Iterables.any(lockedSet, lockedVar -> name != null && name.equalsIgnoreCase(lockedVar));
  }

  public static boolean isEncodedPar(String name) {
    for (ConfVars confVar : HiveConf.ENCODED_CONF) {
      ConfVars confVar1 = confVar;
      if (confVar1.varname.equals(name)) {
        return true;
      }
    }
    return false;
  }

  public static int getIntVar(Configuration conf, ConfVars var) {
    assert (var.valClass == Integer.class) : var.varname;
    if (var.altName != null) {
      return conf.getInt(var.varname, conf.getInt(var.altName, var.defaultIntVal));
    }
    return conf.getInt(var.varname, var.defaultIntVal);
  }

  public static void setIntVar(Configuration conf, ConfVars var, int val) {
    assert (var.valClass == Integer.class) : var.varname;
    conf.setInt(var.varname, val);
  }

  public int getIntVar(ConfVars var) {
    return getIntVar(this, var);
  }

  public void setIntVar(ConfVars var, int val) {
    setIntVar(this, var, val);
  }

  public static long getTimeVar(Configuration conf, ConfVars var, TimeUnit outUnit) {
    return toTime(getVar(conf, var), getDefaultTimeUnit(var), outUnit);
  }

  public static void setTimeVar(Configuration conf, ConfVars var, long time, TimeUnit timeunit) {
    assert (var.valClass == String.class) : var.varname;
    conf.set(var.varname, time + stringFor(timeunit));
  }

  public long getTimeVar(ConfVars var, TimeUnit outUnit) {
    return getTimeVar(this, var, outUnit);
  }

  public void setTimeVar(ConfVars var, long time, TimeUnit outUnit) {
    setTimeVar(this, var, time, outUnit);
  }

  public static long getSizeVar(Configuration conf, ConfVars var) {
    return toSizeBytes(getVar(conf, var));
  }

  public long getSizeVar(ConfVars var) {
    return getSizeVar(this, var);
  }

  public static TimeUnit getDefaultTimeUnit(ConfVars var) {
    TimeUnit inputUnit = null;
    if (var.validator instanceof TimeValidator) {
      inputUnit = ((TimeValidator)var.validator).getTimeUnit();
    }
    return inputUnit;
  }

  public static long toTime(String value, TimeUnit inputUnit, TimeUnit outUnit) {
    String[] parsed = parseNumberFollowedByUnit(value.trim());
    return outUnit.convert(Long.parseLong(parsed[0].trim()), unitFor(parsed[1].trim(), inputUnit));
  }

  public static long toSizeBytes(String value) {
    String[] parsed = parseNumberFollowedByUnit(value.trim());
    return Long.parseLong(parsed[0].trim()) * multiplierFor(parsed[1].trim());
  }

  private static String[] parseNumberFollowedByUnit(String value) {
    char[] chars = value.toCharArray();
    int i = 0;
    for (; i < chars.length && (chars[i] == '-' || Character.isDigit(chars[i])); i++) {
    }
    return new String[] {value.substring(0, i), value.substring(i)};
  }

  private static Set<String> daysSet = ImmutableSet.of("d", "D", "day", "DAY", "days", "DAYS");
  private static Set<String> hoursSet = ImmutableSet.of("h", "H", "hour", "HOUR", "hours", "HOURS");
  private static Set<String> minutesSet = ImmutableSet.of("m", "M", "min", "MIN", "mins", "MINS",
    "minute", "MINUTE", "minutes", "MINUTES");
  private static Set<String> secondsSet = ImmutableSet.of("s", "S", "sec", "SEC", "secs", "SECS",
    "second", "SECOND", "seconds", "SECONDS");
  private static Set<String> millisSet = ImmutableSet.of("ms", "MS", "msec", "MSEC", "msecs", "MSECS",
    "millisecond", "MILLISECOND", "milliseconds", "MILLISECONDS");
  private static Set<String> microsSet = ImmutableSet.of("us", "US", "usec", "USEC", "usecs", "USECS",
    "microsecond", "MICROSECOND", "microseconds", "MICROSECONDS");
  private static Set<String> nanosSet = ImmutableSet.of("ns", "NS", "nsec", "NSEC", "nsecs", "NSECS",
    "nanosecond", "NANOSECOND", "nanoseconds", "NANOSECONDS");
  public static TimeUnit unitFor(String unit, TimeUnit defaultUnit) {
    unit = unit.trim().toLowerCase();
    if (unit.isEmpty() || unit.equals("l")) {
      if (defaultUnit == null) {
        throw new IllegalArgumentException("Time unit is not specified");
      }
      return defaultUnit;
    } else if (daysSet.contains(unit)) {
      return TimeUnit.DAYS;
    } else if (hoursSet.contains(unit)) {
      return TimeUnit.HOURS;
    } else if (minutesSet.contains(unit)) {
      return TimeUnit.MINUTES;
    } else if (secondsSet.contains(unit)) {
      return TimeUnit.SECONDS;
    } else if (millisSet.contains(unit)) {
      return TimeUnit.MILLISECONDS;
    } else if (microsSet.contains(unit)) {
      return TimeUnit.MICROSECONDS;
    } else if (nanosSet.contains(unit)) {
      return TimeUnit.NANOSECONDS;
    }
    throw new IllegalArgumentException("Invalid time unit " + unit);
  }


  public static long multiplierFor(String unit) {
    unit = unit.trim().toLowerCase();
    if (unit.isEmpty() || unit.equals("b") || unit.equals("bytes")) {
      return 1;
    } else if (unit.equals("kb")) {
      return 1024;
    } else if (unit.equals("mb")) {
      return 1024*1024;
    } else if (unit.equals("gb")) {
      return 1024*1024*1024;
    } else if (unit.equals("tb")) {
      return 1024L*1024*1024*1024;
    } else if (unit.equals("pb")) {
      return 1024L*1024*1024*1024*1024;
    }
    throw new IllegalArgumentException("Invalid size unit " + unit);
  }

  public static String stringFor(TimeUnit timeunit) {
    switch (timeunit) {
      case DAYS: return "day";
      case HOURS: return "hour";
      case MINUTES: return "min";
      case SECONDS: return "sec";
      case MILLISECONDS: return "msec";
      case MICROSECONDS: return "usec";
      case NANOSECONDS: return "nsec";
    }
    throw new IllegalArgumentException("Invalid timeunit " + timeunit);
  }

  public static long getLongVar(Configuration conf, ConfVars var) {
    assert (var.valClass == Long.class) : var.varname;
    if (var.altName != null) {
      return conf.getLong(var.varname, conf.getLong(var.altName, var.defaultLongVal));
    }
    return conf.getLong(var.varname, var.defaultLongVal);
  }

  public static long getLongVar(Configuration conf, ConfVars var, long defaultVal) {
    if (var.altName != null) {
      return conf.getLong(var.varname, conf.getLong(var.altName, defaultVal));
    }
    return conf.getLong(var.varname, defaultVal);
  }

  public static void setLongVar(Configuration conf, ConfVars var, long val) {
    assert (var.valClass == Long.class) : var.varname;
    conf.setLong(var.varname, val);
  }

  public long getLongVar(ConfVars var) {
    return getLongVar(this, var);
  }

  public void setLongVar(ConfVars var, long val) {
    setLongVar(this, var, val);
  }

  public static float getFloatVar(Configuration conf, ConfVars var) {
    assert (var.valClass == Float.class) : var.varname;
    if (var.altName != null) {
      return conf.getFloat(var.varname, conf.getFloat(var.altName, var.defaultFloatVal));
    }
    return conf.getFloat(var.varname, var.defaultFloatVal);
  }

  public static float getFloatVar(Configuration conf, ConfVars var, float defaultVal) {
    if (var.altName != null) {
      return conf.getFloat(var.varname, conf.getFloat(var.altName, defaultVal));
    }
    return conf.getFloat(var.varname, defaultVal);
  }

  public static void setFloatVar(Configuration conf, ConfVars var, float val) {
    assert (var.valClass == Float.class) : var.varname;
    conf.setFloat(var.varname, val);
  }

  public float getFloatVar(ConfVars var) {
    return getFloatVar(this, var);
  }

  public void setFloatVar(ConfVars var, float val) {
    setFloatVar(this, var, val);
  }

  public static boolean getBoolVar(Configuration conf, ConfVars var) {
    assert (var.valClass == Boolean.class) : var.varname;
    if (var.altName != null) {
      return conf.getBoolean(var.varname, conf.getBoolean(var.altName, var.defaultBoolVal));
    }
    return conf.getBoolean(var.varname, var.defaultBoolVal);
  }

  public static boolean getBoolVar(Configuration conf, ConfVars var, boolean defaultVal) {
    if (var.altName != null) {
      return conf.getBoolean(var.varname, conf.getBoolean(var.altName, defaultVal));
    }
    return conf.getBoolean(var.varname, defaultVal);
  }

  public static void setBoolVar(Configuration conf, ConfVars var, boolean val) {
    assert (var.valClass == Boolean.class) : var.varname;
    conf.setBoolean(var.varname, val);
  }

  public boolean getBoolVar(ConfVars var) {
    return getBoolVar(this, var);
  }

  public void setBoolVar(ConfVars var, boolean val) {
    setBoolVar(this, var, val);
  }

  public static String getVar(Configuration conf, ConfVars var) {
    assert (var.valClass == String.class) : var.varname;
    return var.altName != null ? conf.get(var.varname, conf.get(var.altName, var.defaultStrVal))
      : conf.get(var.varname, var.defaultStrVal);
  }

  public static String getVarWithoutType(Configuration conf, ConfVars var) {
    return var.altName != null ? conf.get(var.varname, conf.get(var.altName, var.defaultExpr))
      : conf.get(var.varname, var.defaultExpr);
  }

  public static String getTrimmedVar(Configuration conf, ConfVars var) {
    assert (var.valClass == String.class) : var.varname;
    if (var.altName != null) {
      return conf.getTrimmed(var.varname, conf.getTrimmed(var.altName, var.defaultStrVal));
    }
    return conf.getTrimmed(var.varname, var.defaultStrVal);
  }

  public static String[] getTrimmedStringsVar(Configuration conf, ConfVars var) {
    assert (var.valClass == String.class) : var.varname;
    String[] result = conf.getTrimmedStrings(var.varname, (String[])null);
    if (result != null) {
      return result;
    }
    if (var.altName != null) {
      result = conf.getTrimmedStrings(var.altName, (String[])null);
      if (result != null) {
        return result;
      }
    }
    return org.apache.hadoop.util.StringUtils.getTrimmedStrings(var.defaultStrVal);
  }

  public static String getVar(Configuration conf, ConfVars var, String defaultVal) {
    String ret = var.altName != null ? conf.get(var.varname, conf.get(var.altName, defaultVal))
      : conf.get(var.varname, defaultVal);
    return ret;
  }

  public static String getVar(Configuration conf, ConfVars var, EncoderDecoder<String, String> encoderDecoder) {
    return encoderDecoder.decode(getVar(conf, var));
  }

  public String getLogIdVar(String defaultValue) {
    String retval = getVar(ConfVars.HIVE_LOG_TRACE_ID);
    if (StringUtils.EMPTY.equals(retval)) {
      LOG.debug("Using the default value passed in for log id: {}", defaultValue);
      retval = defaultValue;
    }
    if (retval.length() > LOG_PREFIX_LENGTH) {
      LOG.warn("The original log id prefix is {} has been truncated to {}", retval,
          retval.substring(0, LOG_PREFIX_LENGTH - 1));
      retval = retval.substring(0, LOG_PREFIX_LENGTH - 1);
    }
    return retval;
  }

  public static void setVar(Configuration conf, ConfVars var, String val) {
    assert (var.valClass == String.class) : var.varname;
    conf.set(var.varname, val, "setVar");
  }
  public static void setVar(Configuration conf, ConfVars var, String val,
    EncoderDecoder<String, String> encoderDecoder) {
    setVar(conf, var, encoderDecoder.encode(val));
  }

  public static ConfVars getConfVars(String name) {
    return vars.get(name);
  }

  public static ConfVars getMetaConf(String name) {
    return metaConfs.get(name);
  }

  public String getVar(ConfVars var) {
    return getVar(this, var);
  }

  public void setVar(ConfVars var, String val) {
    setVar(this, var, val);
  }

  public String getQueryString() {
    return getQueryString(this);
  }

  public static String getQueryString(Configuration conf) {
    return getVar(conf, ConfVars.HIVE_QUERY_STRING, EncoderDecoderFactory.URL_ENCODER_DECODER);
  }

  public static String getQueryId(Configuration conf) {
    return getVar(conf, HiveConf.ConfVars.HIVE_QUERY_ID, "");
  }

  public void setQueryString(String query) {
    setQueryString(this, query);
  }

  public static void setQueryString(Configuration conf, String query) {
    setVar(conf, ConfVars.HIVE_QUERY_STRING, query, EncoderDecoderFactory.URL_ENCODER_DECODER);
  }
  public void logVars(PrintStream ps) {
    for (ConfVars one : ConfVars.values()) {
      ps.println(one.varname + "=" + ((get(one.varname) != null) ? get(one.varname) : ""));
    }
  }

  /**
   * @return a ZooKeeperHiveHelper instance containing the ZooKeeper specifications from the
   * given HiveConf.
   */
  public ZooKeeperHiveHelper getZKConfig() {
    String keyStorePassword = "";
    String trustStorePassword = "";
    if (getBoolVar(ConfVars.HIVE_ZOOKEEPER_SSL_ENABLE)) {
      try {
        keyStorePassword =
            ShimLoader.getHadoopShims().getPassword(this, ConfVars.HIVE_ZOOKEEPER_SSL_KEYSTORE_PASSWORD.varname);
        trustStorePassword =
            ShimLoader.getHadoopShims().getPassword(this, ConfVars.HIVE_ZOOKEEPER_SSL_TRUSTSTORE_PASSWORD.varname);
      } catch (IOException ex) {
        throw new RuntimeException("Failed to read zookeeper configuration passwords", ex);
      }
    }
    return ZooKeeperHiveHelper.builder()
      .quorum(getVar(HiveConf.ConfVars.HIVE_ZOOKEEPER_QUORUM))
      .clientPort(getVar(HiveConf.ConfVars.HIVE_ZOOKEEPER_CLIENT_PORT))
      .serverRegistryNameSpace(getVar(HiveConf.ConfVars.HIVE_SERVER2_ZOOKEEPER_NAMESPACE))
      .connectionTimeout((int) getTimeVar(HiveConf.ConfVars.HIVE_ZOOKEEPER_CONNECTION_TIMEOUT,
          TimeUnit.MILLISECONDS))
      .sessionTimeout((int) getTimeVar(HiveConf.ConfVars.HIVE_ZOOKEEPER_SESSION_TIMEOUT,
          TimeUnit.MILLISECONDS))
      .baseSleepTime((int) getTimeVar(HiveConf.ConfVars.HIVE_ZOOKEEPER_CONNECTION_BASESLEEPTIME,
          TimeUnit.MILLISECONDS))
      .maxRetries(getIntVar(HiveConf.ConfVars.HIVE_ZOOKEEPER_CONNECTION_MAX_RETRIES))
      .sslEnabled(getBoolVar(ConfVars.HIVE_ZOOKEEPER_SSL_ENABLE))
      .keyStoreLocation(getVar(ConfVars.HIVE_ZOOKEEPER_SSL_KEYSTORE_LOCATION))
      .keyStorePassword(keyStorePassword)
      .keyStoreType(getVar(ConfVars.HIVE_ZOOKEEPER_SSL_KEYSTORE_TYPE))
      .trustStoreLocation(getVar(ConfVars.HIVE_ZOOKEEPER_SSL_TRUSTSTORE_LOCATION))
      .trustStorePassword(trustStorePassword)
      .trustStoreType(getVar(ConfVars.HIVE_ZOOKEEPER_SSL_TRUSTSTORE_TYPE)).build();
  }

  public HiveConf() {
    super();
    initialize(this.getClass());
  }

  public HiveConf(Class<?> cls) {
    super();
    initialize(cls);
  }

  public HiveConf(Configuration other, Class<?> cls) {
    super(other);
    initialize(cls);
  }

  /**
   * Copy constructor
   */
  public HiveConf(HiveConf other) {
    super(other);
    hiveJar = other.hiveJar;
    auxJars = other.auxJars;
    origProp = (Properties)other.origProp.clone();
    restrictList.addAll(other.restrictList);
    hiddenSet.addAll(other.hiddenSet);
    lockedSet.addAll(other.lockedSet);
    modWhiteListPattern = other.modWhiteListPattern;
  }

  public Properties getAllProperties() {
    return getProperties(this);
  }

  public static Properties getProperties(Configuration conf) {
    Iterator<Map.Entry<String, String>> iter = conf.iterator();
    Properties p = new Properties();
    while (iter.hasNext()) {
      Map.Entry<String, String> e = iter.next();
      p.setProperty(e.getKey(), e.getValue());
    }
    return p;
  }

  private void initialize(Class<?> cls) {
    hiveJar = (new JobConf(cls)).getJar();

    // preserve the original configuration
    origProp = getAllProperties();

    // Overlay the ConfVars. Note that this ignores ConfVars with null values
    addResource(getConfVarInputStream(), "HiveConf.java");

    // Overlay hive-site.xml if it exists
    if (hiveSiteURL != null) {
      addResource(hiveSiteURL);
    }

    // if embedded metastore is to be used as per config so far
    // then this is considered like the metastore server case
    String msUri = this.getVar(HiveConf.ConfVars.METASTORE_URIS);
    // This is hackery, but having hive-common depend on standalone-metastore is really bad
    // because it will pull all of the metastore code into every module.  We need to check that
    // we aren't using the standalone metastore.  If we are, we should treat it the same as a
    // remote metastore situation.
    if (msUri == null || msUri.isEmpty()) {
      msUri = this.get("metastore.thrift.uris");
    }
    LOG.debug("Found metastore URI of " + msUri);
    if(HiveConfUtil.isEmbeddedMetaStore(msUri)){
      setLoadMetastoreConfig(true);
    }

    // load the legacy hivemetastore-site.xml and metastore-site.xml if this is metastore and file exists
    if (isLoadMetastoreConfig()) {
      if (hiveMetastoreSiteUrl != null) {
        addResource(hiveMetastoreSiteUrl);
      }
      if (metastoreSiteUrl != null) {
        addResource(metastoreSiteUrl);
      }
    }

    // load hiveserver2-site.xml if this is hiveserver2 and file exists
    // metastore can be embedded within hiveserver2, in such cases
    // the conf params in hiveserver2-site.xml will override whats defined
    // in hivemetastore-site.xml/metastore-site.xml
    if (isLoadHiveServer2Config()) {
      // set the hardcoded value first, so anything in hiveserver2-site.xml can override it
      set(ConfVars.METASTORE_CLIENT_CAPABILITIES.varname, "EXTWRITE,EXTREAD,HIVEBUCKET2,HIVEFULLACIDREAD,"
          + "HIVEFULLACIDWRITE,HIVECACHEINVALIDATE,HIVEMANAGESTATS,HIVEMANAGEDINSERTWRITE,HIVEMANAGEDINSERTREAD,"
          + "HIVESQL,HIVEMQT,HIVEONLYMQTWRITE,ACCEPTS_UNMODIFIED_METADATA");

      if (hiveServer2SiteUrl != null) {
        addResource(hiveServer2SiteUrl);
      }
    }

    String val = this.getVar(HiveConf.ConfVars.HIVE_ADDITIONAL_CONFIG_FILES);
    ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

    if (val != null && !val.isEmpty()) {
      String[] configFiles = val.split(",");
      for (String config : configFiles) {
        URL configURL = findConfigFile(classLoader, config, true);
        if (configURL != null) {
          addResource(configURL);
        }
      }
    }
    // Overlay the values of any system properties and manual overrides
    applySystemProperties();

    if ((this.get("hive.metastore.ds.retry.attempts") != null) ||
      this.get("hive.metastore.ds.retry.interval") != null) {
        LOG.warn("DEPRECATED: hive.metastore.ds.retry.* no longer has any effect.  " +
        "Use hive.hmshandler.retry.* instead");
    }

    // if the running class was loaded directly (through eclipse) rather than through a
    // jar then this would be needed
    if (hiveJar == null) {
      hiveJar = this.get(ConfVars.HIVE_JAR.varname);
    }

    if (auxJars == null) {
      auxJars = StringUtils.join(FileUtils.getJarFilesByPath(this.get(ConfVars.HIVE_AUX_JARS.varname), this), ',');
    }

    if (getBoolVar(ConfVars.METASTORE_SCHEMA_VERIFICATION)) {
      setBoolVar(ConfVars.METASTORE_AUTO_CREATE_ALL, false);
    }

    if (getBoolVar(HiveConf.ConfVars.HIVE_CONF_VALIDATION)) {
      List<String> trimmed = new ArrayList<String>();
      for (Map.Entry<String,String> entry : this) {
        String key = entry.getKey();
        if (key == null || !key.startsWith("hive.")) {
          continue;
        }
        ConfVars var = HiveConf.getConfVars(key);
        if (var == null) {
          var = HiveConf.getConfVars(key.trim());
          if (var != null) {
            trimmed.add(key);
          }
        }
        if (var == null) {
          LOG.warn("HiveConf of name {} does not exist", key);
        } else if (!var.isType(entry.getValue())) {
          LOG.warn("HiveConf {} expects {} type value", var.varname, var.typeString());
        }
      }
      for (String key : trimmed) {
        set(key.trim(), getRaw(key));
        unset(key);
      }
    }

    setupSQLStdAuthWhiteList();

    // setup list of conf vars that are not allowed to change runtime
    setupRestrictList();
    hiddenSet.clear();
    hiddenSet.addAll(HiveConfUtil.getHiddenSet(this));

    lockedSet.clear();
    lockedSet.addAll(HiveConfUtil.getLockedSet(this));
  }

  /**
   * If the config whitelist param for sql standard authorization is not set, set it up here.
   */
  private void setupSQLStdAuthWhiteList() {
    String whiteListParamsStr = getVar(ConfVars.HIVE_AUTHORIZATION_SQL_STD_AUTH_CONFIG_WHITELIST);
    if (whiteListParamsStr == null || whiteListParamsStr.trim().isEmpty()) {
      // set the default configs in whitelist
      whiteListParamsStr = getSQLStdAuthDefaultWhiteListPattern();
      setVar(ConfVars.HIVE_AUTHORIZATION_SQL_STD_AUTH_CONFIG_WHITELIST, whiteListParamsStr);
    }
  }

  private static String getSQLStdAuthDefaultWhiteListPattern() {
    // create the default white list from list of safe config params
    // and regex list
    String confVarPatternStr = Joiner.on("|").join(convertVarsToRegex(SQL_STD_AUTH_SAFE_VAR_NAMES));
    String regexPatternStr = Joiner.on("|").join(sqlStdAuthSafeVarNameRegexes);
    return regexPatternStr + "|" + confVarPatternStr + "|QUERY_EXECUTOR";
  }

  /**
   * Obtains the local time-zone ID.
   */
  public ZoneId getLocalTimeZone() {
    String timeZoneStr = getVar(ConfVars.HIVE_LOCAL_TIME_ZONE);
    return TimestampTZUtil.parseTimeZone(timeZoneStr);
  }

  /**
   * @param paramList  list of parameter strings
   * @return list of parameter strings with "." replaced by "\."
   */
  private static String[] convertVarsToRegex(String[] paramList) {
    String[] regexes = new String[paramList.length];
    for(int i=0; i<paramList.length; i++) {
      regexes[i] = paramList[i].replace(".", "\\." );
    }
    return regexes;
  }

  /**
   * Default list of modifiable config parameters for sql standard authorization
   * For internal use only.
   */
  private static final String[] SQL_STD_AUTH_SAFE_VAR_NAMES = new String[] {
      ConfVars.AGGR_JOIN_TRANSPOSE.varname,
      ConfVars.BYTES_PER_REDUCER.varname,
      ConfVars.CLIENT_STATS_COUNTERS.varname,
      ConfVars.CREATE_TABLES_AS_ACID.varname,
      ConfVars.CREATE_TABLE_AS_EXTERNAL.varname,
      ConfVars.DEFAULT_PARTITION_NAME.varname,
      ConfVars.DROP_IGNORES_NON_EXISTENT.varname,
      ConfVars.HIVE_COUNTER_GROUP.varname,
      ConfVars.HIVE_DEFAULT_MANAGED_FILEFORMAT.varname,
      ConfVars.HIVE_ENFORCE_BUCKET_MAPJOIN.varname,
      ConfVars.HIVE_ENFORCE_SORT_MERGE_BUCKET_MAPJOIN.varname,
      ConfVars.HIVE_EXPR_EVALUATION_CACHE.varname,
      ConfVars.HIVE_QUERY_RESULT_FILEFORMAT.varname,
      ConfVars.HIVE_HASHTABLE_LOAD_FACTOR.varname,
      ConfVars.HIVE_HASHTABLE_THRESHOLD.varname,
      ConfVars.HIVE_IGNORE_MAPJOIN_HINT.varname,
      ConfVars.HIVE_LIMIT_MAX_ROW_SIZE.varname,
      ConfVars.HIVE_MAPRED_MODE.varname,
      ConfVars.HIVE_MAPSIDE_AGGREGATE.varname,
      ConfVars.HIVE_OPTIMIZE_METADATA_QUERIES.varname,
      ConfVars.HIVE_VARIABLE_SUBSTITUTE.varname,
      ConfVars.HIVE_VARIABLE_SUBSTITUTE_DEPTH.varname,
      ConfVars.HIVE_AUTOGEN_COLUMNALIAS_PREFIX_INCLUDEFUNCNAME.varname,
      ConfVars.HIVE_AUTOGEN_COLUMNALIAS_PREFIX_LABEL.varname,
      ConfVars.HIVE_CHECK_CROSS_PRODUCT.varname,
      ConfVars.HIVE_CLI_TEZ_SESSION_ASYNC.varname,
      ConfVars.HIVE_COMPAT.varname,
      ConfVars.HIVE_CREATE_TABLES_AS_INSERT_ONLY.varname,
      ConfVars.HIVE_DISPLAY_PARTITION_COLUMNS_SEPARATELY.varname,
      ConfVars.HIVE_ERROR_ON_EMPTY_PARTITION.varname,
      ConfVars.HIVE_EXECUTION_ENGINE.varname,
      ConfVars.HIVE_EXEC_COPYFILE_MAXSIZE.varname,
      ConfVars.HIVE_EXIM_URI_SCHEME_WL.varname,
      ConfVars.HIVE_FILE_MAX_FOOTER.varname,
      ConfVars.HIVE_INSERT_INTO_MULTILEVEL_DIRS.varname,
      ConfVars.HIVE_LOCALIZE_RESOURCE_NUM_WAIT_ATTEMPTS.varname,
      ConfVars.HIVE_MULTI_INSERT_MOVE_TASKS_SHARE_DEPENDENCIES.varname,
      ConfVars.HIVE_QUERY_RESULTS_CACHE_ENABLED.varname,
      ConfVars.HIVE_QUERY_RESULTS_CACHE_WAIT_FOR_PENDING_RESULTS.varname,
      ConfVars.HIVE_QUOTEDID_SUPPORT.varname,
      ConfVars.HIVE_RESULTSET_USE_UNIQUE_COLUMN_NAMES.varname,
      ConfVars.HIVE_STATS_COLLECT_PART_LEVEL_STATS.varname,
      ConfVars.HIVE_SCHEMA_EVOLUTION.varname,
      ConfVars.HIVE_SERVER2_LOGGING_OPERATION_LEVEL.varname,
      ConfVars.HIVE_SERVER2_THRIFT_RESULTSET_SERIALIZE_IN_TASKS.varname,
      ConfVars.HIVE_SUPPORT_SPECICAL_CHARACTERS_IN_TABLE_NAMES.varname,
      ConfVars.JOB_DEBUG_CAPTURE_STACKTRACES.varname,
      ConfVars.JOB_DEBUG_TIMEOUT.varname,
      ConfVars.LLAP_IO_ENABLED.varname,
      ConfVars.LLAP_IO_USE_FILEID_PATH.varname,
      ConfVars.LLAP_DAEMON_SERVICE_HOSTS.varname,
      ConfVars.LLAP_EXECUTION_MODE.varname,
      ConfVars.LLAP_AUTO_ALLOW_UBER.varname,
      ConfVars.LLAP_AUTO_ENFORCE_TREE.varname,
      ConfVars.LLAP_AUTO_ENFORCE_VECTORIZED.varname,
      ConfVars.LLAP_AUTO_ENFORCE_STATS.varname,
      ConfVars.LLAP_AUTO_MAX_INPUT.varname,
      ConfVars.LLAP_AUTO_MAX_OUTPUT.varname,
      ConfVars.LLAP_SKIP_COMPILE_UDF_CHECK.varname,
      ConfVars.LLAP_CLIENT_CONSISTENT_SPLITS.varname,
      ConfVars.LLAP_ENABLE_GRACE_JOIN_IN_LLAP.varname,
      ConfVars.LLAP_ALLOW_PERMANENT_FNS.varname,
      ConfVars.MAX_CREATED_FILES.varname,
      ConfVars.MAX_REDUCERS.varname,
      ConfVars.N_WAY_JOIN_REORDER.varname,
      ConfVars.OUTPUT_FILE_EXTENSION.varname,
      ConfVars.SHOW_JOB_FAIL_DEBUG_INFO.varname,
      ConfVars.TASKLOG_DEBUG_TIMEOUT.varname,
      ConfVars.HIVE_QUERY_ID.varname,
      ConfVars.HIVE_QUERY_TAG.varname,
  };

  /**
   * Default list of regexes for config parameters that are modifiable with
   * sql standard authorization enabled
   */
  static final String [] sqlStdAuthSafeVarNameRegexes = new String [] {
    "hive\\.auto\\..*",
    "hive\\.cbo\\..*",
    "hive\\.convert\\..*",
    "hive\\.druid\\..*",
    "hive\\.exec\\.dynamic\\.partition.*",
    "hive\\.exec\\.max\\.dynamic\\.partitions.*",
    "hive\\.exec\\.compress\\..*",
    "hive\\.exec\\.infer\\..*",
    "hive\\.exec\\.mode.local\\..*",
    "hive\\.exec\\.orc\\..*",
    "hive\\.exec\\.parallel.*",
    "hive\\.explain\\..*",
    "hive\\.fetch.task\\..*",
    "hive\\.groupby\\..*",
    "hive\\.hbase\\..*",
    "hive\\.index\\..*",
    "hive\\.index\\..*",
    "hive\\.intermediate\\..*",
    "hive\\.jdbc\\..*",
    "hive\\.join\\..*",
    "hive\\.limit\\..*",
    "hive\\.log\\..*",
    "hive\\.mapjoin\\..*",
    "hive\\.merge\\..*",
    "hive\\.optimize\\..*",
    "hive\\.materializedview\\..*",
    "hive\\.orc\\..*",
    "hive\\.outerjoin\\..*",
    "hive\\.parquet\\..*",
    "hive\\.ppd\\..*",
    "hive\\.prewarm\\..*",
    "hive\\.query\\.name",
    "hive\\.server2\\.thrift\\.resultset\\.default\\.fetch\\.size",
    "hive\\.server2\\.proxy\\.user",
    "hive\\.skewjoin\\..*",
    "hive\\.smbjoin\\..*",
    "hive\\.stats\\..*",
    "hive\\.strict\\..*",
    "hive\\.tez\\..*",
    "hive\\.vectorized\\..*",
    "hive\\.query\\.reexecution\\..*",
    "hive\\.query\\.exclusive\\.lock",
    "reexec\\.overlay\\..*",
    "fs\\.defaultFS",
    "ssl\\.client\\.truststore\\.location",
    "distcp\\.atomic",
    "distcp\\.ignore\\.failures",
    "distcp\\.preserve\\.status",
    "distcp\\.preserve\\.rawxattrs",
    "distcp\\.sync\\.folders",
    "distcp\\.delete\\.missing\\.source",
    "distcp\\.keystore\\.resource",
    "distcp\\.liststatus\\.threads",
    "distcp\\.max\\.maps",
    "distcp\\.copy\\.strategy",
    "distcp\\.skip\\.crc",
    "distcp\\.copy\\.overwrite",
    "distcp\\.copy\\.append",
    "distcp\\.map\\.bandwidth\\.mb",
    "distcp\\.dynamic\\..*",
    "distcp\\.meta\\.folder",
    "distcp\\.copy\\.listing\\.class",
    "distcp\\.filters\\.class",
    "distcp\\.options\\.skipcrccheck",
    "distcp\\.options\\.m",
    "distcp\\.options\\.numListstatusThreads",
    "distcp\\.options\\.mapredSslConf",
    "distcp\\.options\\.bandwidth",
    "distcp\\.options\\.overwrite",
    "distcp\\.options\\.strategy",
    "distcp\\.options\\.i",
    "distcp\\.options\\.p.*",
    "distcp\\.options\\.update",
    "distcp\\.options\\.delete",
    "mapred\\.map\\..*",
    "mapred\\.reduce\\..*",
    "mapred\\.output\\.compression\\.codec",
    "mapred\\.job\\.queue\\.name",
    "mapred\\.output\\.compression\\.type",
    "mapred\\.min\\.split\\.size",
    "mapreduce\\.job\\.reduce\\.slowstart\\.completedmaps",
    "mapreduce\\.job\\.queuename",
    "mapreduce\\.job\\.tags",
    "mapreduce\\.input\\.fileinputformat\\.split\\.minsize",
    "mapreduce\\.map\\..*",
    "mapreduce\\.reduce\\..*",
    "mapreduce\\.output\\.fileoutputformat\\.compress\\.codec",
    "mapreduce\\.output\\.fileoutputformat\\.compress\\.type",
    "oozie\\..*",
    "tez\\.am\\..*",
    "tez\\.task\\..*",
    "tez\\.task\\-.*",
    "tez\\.runtime\\..*",
    "tez\\.queue\\.name",
    "iceberg\\.mr\\..*"
  };

  //Take care of conf overrides.
  //Includes values in ConfVars as well as underlying configuration properties (ie, hadoop)
  @SuppressFBWarnings(value = "MS_MUTABLE_COLLECTION_PKGPROTECT", justification = "Intended exposure")
  public static final Map<String, String> overrides = new HashMap<String, String>();

  /**
   * Apply system properties to this object if the property name is defined in ConfVars
   * and the value is non-null and not an empty string.
   */
  private void applySystemProperties() {
    Map<String, String> systemProperties = getConfSystemProperties();
    for (Entry<String, String> systemProperty : systemProperties.entrySet()) {
      this.set(systemProperty.getKey(), systemProperty.getValue());
    }
  }

  /**
   * This method returns a mapping from config variable name to its value for all config variables
   * which have been set using System properties
   */
  public static Map<String, String> getConfSystemProperties() {
    Map<String, String> systemProperties = new HashMap<String, String>();

    for (ConfVars oneVar : ConfVars.values()) {
      if (System.getProperty(oneVar.varname) != null) {
        if (System.getProperty(oneVar.varname).length() > 0) {
          systemProperties.put(oneVar.varname, System.getProperty(oneVar.varname));
        }
      }
    }

    for (Map.Entry<String, String> oneVar : overrides.entrySet()) {
      if (overrides.get(oneVar.getKey()) != null) {
        if (overrides.get(oneVar.getKey()).length() > 0) {
          systemProperties.put(oneVar.getKey(), oneVar.getValue());
        }
      }
    }
    return systemProperties;
  }

  /**
   * Overlays ConfVar properties with non-null values
   */
  private static void applyDefaultNonNullConfVars(Configuration conf) {
    for (ConfVars var : ConfVars.values()) {
      String defaultValue = var.getDefaultValue();
      if (defaultValue == null) {
        // Don't override ConfVars with null values
        continue;
      }
      conf.set(var.varname, defaultValue);
    }
  }

  public Properties getChangedProperties() {
    Properties ret = new Properties();
    Properties newProp = getAllProperties();

    for (Object one : newProp.keySet()) {
      String oneProp = (String) one;
      String oldValue = origProp.getProperty(oneProp);
      if (!StringUtils.equals(oldValue, newProp.getProperty(oneProp))) {
        ret.setProperty(oneProp, newProp.getProperty(oneProp));
      }
    }
    return (ret);
  }

  public String getJar() {
    return hiveJar;
  }

  /**
   * @return the auxJars
   */
  public String getAuxJars() {
    return auxJars;
  }

  /**
   * Set the auxiliary jars. Used for unit tests only.
   * @param auxJars the auxJars to set.
   */
  public void setAuxJars(String auxJars) {
    this.auxJars = auxJars;
    setVar(this, ConfVars.HIVE_AUX_JARS, auxJars);
  }

  public URL getHiveDefaultLocation() {
    return hiveDefaultURL;
  }

  public static void setHiveSiteLocation(URL location) {
    hiveSiteURL = location;
  }

  /**
   * @deprecated use setMetastoreSiteLocation(URL location)
   * @param location the Metastore property source
   */
  @Deprecated
  public static void setHivemetastoreSiteUrl(URL location) {
    setMetastoreSiteLocation(location);
  }

  public static URL getHiveSiteLocation() {
    return hiveSiteURL;
  }

  public static void setMetastoreSiteLocation(URL location) {
    metastoreSiteUrl = location;
  }

  public static URL getMetastoreSiteLocation() {
    return metastoreSiteUrl;
  }

  public static URL getHiveServer2SiteLocation() {
    return hiveServer2SiteUrl;
  }

  /**
   * @return the user name set in hadoop.job.ugi param or the current user from System
   * @throws IOException
   */
  public String getUser() throws IOException {
    try {
      UserGroupInformation ugi = Utils.getUGI();
      return ugi.getUserName();
    } catch (LoginException le) {
      throw new IOException(le);
    }
  }

  public static String getColumnInternalName(int pos) {
    return "_col" + pos;
  }

  public static int getPositionFromInternalName(String internalName) {
    Pattern internalPattern = Pattern.compile("_col([0-9]+)");
    Matcher m = internalPattern.matcher(internalName);
    if (!m.matches()){
      return -1;
    } else {
      return Integer.parseInt(m.group(1));
    }
  }

  /**
   * Append comma separated list of config vars to the restrict List
   * @param restrictListStr
   */
  public void addToRestrictList(String restrictListStr) {
    if (restrictListStr == null) {
      return;
    }
    String oldList = this.getVar(ConfVars.HIVE_CONF_RESTRICTED_LIST);
    if (oldList == null || oldList.isEmpty()) {
      this.setVar(ConfVars.HIVE_CONF_RESTRICTED_LIST, restrictListStr);
    } else {
      this.setVar(ConfVars.HIVE_CONF_RESTRICTED_LIST, oldList + "," + restrictListStr);
    }
    setupRestrictList();
  }

  public void addToLockedSet(String lockedListStr) {
    String oldList = this.getVar(ConfVars.HIVE_CONF_LOCKED_LIST);
    if (oldList == null || oldList.isEmpty()) {
      this.setVar(ConfVars.HIVE_CONF_LOCKED_LIST, lockedListStr);
    } else {
      this.setVar(ConfVars.HIVE_CONF_LOCKED_LIST, oldList + "," + lockedListStr);
    }
    String modifiedLockedSet = this.getVar(ConfVars.HIVE_CONF_LOCKED_LIST);
    lockedSet.clear();
    if (modifiedLockedSet != null) {
      for (String entry : modifiedLockedSet.split(",")) {
        lockedSet.add(entry.trim());
      }
    }
  }

  /**
   * Set white list of parameters that are allowed to be modified
   *
   * @param paramNameRegex
   */
  @LimitedPrivate(value = { "Currently only for use by HiveAuthorizer" })
  public void setModifiableWhiteListRegex(String paramNameRegex) {
    if (paramNameRegex == null) {
      return;
    }
    modWhiteListPattern = Pattern.compile(paramNameRegex);
  }

  /**
   * Add the HIVE_CONF_RESTRICTED_LIST values to restrictList,
   * including HIVE_CONF_RESTRICTED_LIST itself
   */
  private void setupRestrictList() {
    String restrictListStr = this.getVar(ConfVars.HIVE_CONF_RESTRICTED_LIST);
    restrictList.clear();
    if (restrictListStr != null) {
      for (String entry : restrictListStr.split(",")) {
        restrictList.add(entry.trim());
      }
    }

    String internalVariableListStr = this.getVar(ConfVars.HIVE_CONF_INTERNAL_VARIABLE_LIST);
    if (internalVariableListStr != null) {
      for (String entry : internalVariableListStr.split(",")) {
        restrictList.add(entry.trim());
      }
    }

    restrictList.add(ConfVars.HIVE_IN_TEST.varname);
    restrictList.add(ConfVars.HIVE_CONF_RESTRICTED_LIST.varname);
    restrictList.add(ConfVars.HIVE_CONF_HIDDEN_LIST.varname);
    restrictList.add(ConfVars.HIVE_CONF_INTERNAL_VARIABLE_LIST.varname);
    restrictList.add(ConfVars.HIVE_CONF_LOCKED_LIST.varname);
  }

  /**
   * Strips hidden config entries from configuration
   */
  public void stripHiddenConfigurations(Configuration conf) {
    HiveConfUtil.stripConfigurations(conf, hiddenSet);
  }

  /**
   * @return true if HS2 webui is enabled
   */
  public boolean isWebUiEnabled() {
    return this.getIntVar(ConfVars.HIVE_SERVER2_WEBUI_PORT) != 0;
  }

  /**
   * @return true if HS2 webui query-info cache is enabled
   */
  public boolean isWebUiQueryInfoCacheEnabled() {
    return isWebUiEnabled() && this.getIntVar(ConfVars.HIVE_SERVER2_WEBUI_MAX_HISTORIC_QUERIES) > 0;
  }

  public static boolean isLoadMetastoreConfig() {
    return loadMetastoreConfig;
  }

  public static void setLoadMetastoreConfig(boolean loadMetastoreConfig) {
    HiveConf.loadMetastoreConfig = loadMetastoreConfig;
  }

  public static boolean isLoadHiveServer2Config() {
    return loadHiveServer2Config;
  }

  public static void setLoadHiveServer2Config(boolean loadHiveServer2Config) {
    HiveConf.loadHiveServer2Config = loadHiveServer2Config;
  }

  public static class StrictChecks {

    private static final String NO_LIMIT_MSG = makeMessage(
        "Order by-s without limit", ConfVars.HIVE_STRICT_CHECKS_ORDERBY_NO_LIMIT);
    public static final String NO_PARTITIONLESS_MSG = makeMessage(
        "Queries against partitioned tables without a partition filter",
        ConfVars.HIVE_STRICT_CHECKS_NO_PARTITION_FILTER);
    private static final String NO_COMPARES_MSG = makeMessage(
        "Unsafe compares between different types", ConfVars.HIVE_STRICT_CHECKS_TYPE_SAFETY);
    private static final String NO_CARTESIAN_MSG = makeMessage(
        "Cartesian products", ConfVars.HIVE_STRICT_CHECKS_CARTESIAN);
    private static final String NO_BUCKETING_MSG = makeMessage(
        "Load into bucketed tables", ConfVars.HIVE_STRICT_CHECKS_BUCKETING);
    private static final String NO_OFFSET_WITHOUT_ORDERBY_MSG = makeMessage(
        "OFFSET without ORDER BY", ConfVars.HIVE_STRICT_CHECKS_OFFSET_NO_ORDERBY);

    private static String makeMessage(String what, ConfVars setting) {
      return what + " are disabled for safety reasons. If you know what you are doing, please set "
          + setting.varname + " to false and make sure that " + ConfVars.HIVE_MAPRED_MODE.varname +
              " is not set to 'strict' to proceed. Note that you may get errors or incorrect " +
              "results if you make a mistake while using some of the unsafe features.";
    }

    public static String checkNoLimit(Configuration conf) {
      return isAllowed(conf, ConfVars.HIVE_STRICT_CHECKS_ORDERBY_NO_LIMIT) ? null : NO_LIMIT_MSG;
    }

    public static String checkNoPartitionFilter(Configuration conf) {
      return isAllowed(conf, ConfVars.HIVE_STRICT_CHECKS_NO_PARTITION_FILTER)
          ? null : NO_PARTITIONLESS_MSG;
    }

    public static String checkTypeSafety(Configuration conf) {
      return isAllowed(conf, ConfVars.HIVE_STRICT_CHECKS_TYPE_SAFETY) ? null : NO_COMPARES_MSG;
    }

    public static String checkCartesian(Configuration conf) {
      return isAllowed(conf, ConfVars.HIVE_STRICT_CHECKS_CARTESIAN) ? null : NO_CARTESIAN_MSG;
    }

    public static String checkBucketing(Configuration conf) {
      return isAllowed(conf, ConfVars.HIVE_STRICT_CHECKS_BUCKETING) ? null : NO_BUCKETING_MSG;
    }

    public static void checkOffsetWithoutOrderBy(Configuration conf) throws SemanticException {
      if (!isAllowed(conf, ConfVars.HIVE_STRICT_CHECKS_OFFSET_NO_ORDERBY)) {
        throw new SemanticException(NO_OFFSET_WITHOUT_ORDERBY_MSG);
      }
    }

    private static boolean isAllowed(Configuration conf, ConfVars setting) {
      String mode = HiveConf.getVar(conf, ConfVars.HIVE_MAPRED_MODE, (String)null);
      return (mode != null) ? !"strict".equals(mode) : !HiveConf.getBoolVar(conf, setting);
    }
  }

  public static String getNonMrEngines() {
    Set<String> engines = new HashSet<>(ConfVars.HIVE_EXECUTION_ENGINE.getValidStringValues());
    engines.remove("mr");
    String validNonMrEngines = String.join(", ", engines);
    LOG.debug("Valid non-MapReduce execution engines: {}", validNonMrEngines);
    return validNonMrEngines;
  }

  public static String generateMrDeprecationWarning() {
    return "Hive-on-MR is deprecated in Hive 2 and may not be available in the future versions. "
        + "Consider using a different execution engine (i.e. " + HiveConf.getNonMrEngines()
        + ") or using Hive 1.X releases.";
  }

  public static String generateDeprecationWarning() {
    return "This config will be deprecated and may not be available in the future "
        + "versions. Please adjust DDL towards the new semantics.";
  }

  private static final Object reverseMapLock = new Object();
  private static HashMap<String, ConfVars> reverseMap = null;

  public static HashMap<String, ConfVars> getOrCreateReverseMap() {
    // This should be called rarely enough; for now it's ok to just lock every time.
    synchronized (reverseMapLock) {
      if (reverseMap != null) {
        return reverseMap;
      }
    }
    HashMap<String, ConfVars> vars = new HashMap<>();
    for (ConfVars val : ConfVars.values()) {
      vars.put(val.varname.toLowerCase(), val);
      if (val.altName != null && !val.altName.isEmpty()) {
        vars.put(val.altName.toLowerCase(), val);
      }
    }
    synchronized (reverseMapLock) {
      if (reverseMap != null) {
        return reverseMap;
      }
      reverseMap = vars;
      return reverseMap;
    }
  }

  public void verifyAndSetAll(Map<String, String> overlay) {
    for (Entry<String, String> entry : overlay.entrySet()) {
      verifyAndSet(entry.getKey(), entry.getValue());
    }
  }

  public Map<String, String> subtree(String string) {
    Map<String, String> ret = new HashMap<>();
    for (Entry<Object, Object> entry : getProps().entrySet()) {
      String key = (String) entry.getKey();
      String value = (String) entry.getValue();
      if (key.startsWith(string)) {
        ret.put(key.substring(string.length() + 1), value);
      }
    }
    return ret;
  }

  public static boolean shouldComputeLineage(HiveConf conf) {
    Collection<String> lineageFilter =
      conf.getTrimmedStringCollection(HiveConf.ConfVars.HIVE_LINEAGE_STATEMENT_FILTER.varname);
    return !(lineageFilter.isEmpty() || lineageFilter.contains("NONE"))
      || conf.getBoolVar(ConfVars.HIVE_LINEAGE_INFO);
  }

  // sync all configs from given conf
  public void syncFromConf(HiveConf conf) {
    Iterator<Map.Entry<String, String>> iter = conf.iterator();
    while (iter.hasNext()) {
      Map.Entry<String, String> e = iter.next();
      set(e.getKey(), e.getValue());
    }
  }
}
