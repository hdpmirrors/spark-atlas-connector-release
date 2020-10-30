/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hortonworks.spark.atlas.types

import java.io.File
import java.net.{URI, URISyntaxException}

import com.hortonworks.spark.atlas.sql.KafkaTopicInformation
import org.apache.atlas.AtlasConstants
import org.apache.atlas.hbase.bridge.HBaseAtlasHook._
import org.apache.atlas.model.instance.{AtlasEntity, AtlasObjectId}
import org.apache.hadoop.fs.Path
import com.hortonworks.spark.atlas.utils.SparkUtils
import org.apache.spark.sql.catalyst.catalog.{CatalogDatabase, CatalogTable}
import com.hortonworks.spark.atlas.{AtlasUtils, SACAtlasEntityReference, SACAtlasEntityWithDependencies, SACAtlasReferenceable}


object external {
  // External metadata types used to link with external entities

  // ================ File system entities ======================
  val FS_PATH_TYPE_STRING = "fs_path"
  val HDFS_PATH_TYPE_STRING = "hdfs_path"
  val S3_OBJECT_TYPE_STRING = "aws_s3_object"
  val S3_PSEUDO_DIR_TYPE_STRING = "aws_s3_pseudo_dir"
  val S3_BUCKET_TYPE_STRING = "aws_s3_bucket"

  private def isS3Schema(schema: String): Boolean = schema.matches("s3[an]?")

  private def extractS3Entity(uri: URI, fsPath: Path): SACAtlasEntityWithDependencies = {
    val path = Path.getPathWithoutSchemeAndAuthority(fsPath).toString

    val bucketName = uri.getAuthority
    val bucketQualifiedName = s"s3://${bucketName}"
    val dirName = path.replaceFirst("[^/]*$", "")
    val dirQualifiedName = bucketQualifiedName + dirName
    val objectName = path.replaceFirst("^.*/", "")
    val objectQualifiedName = dirQualifiedName + objectName

    // bucket
    val bucketEntity = new AtlasEntity(S3_BUCKET_TYPE_STRING)
    bucketEntity.setAttribute("name", bucketName)
    bucketEntity.setAttribute("qualifiedName", bucketQualifiedName)

    // pseudo dir
    val dirEntity = new AtlasEntity(S3_PSEUDO_DIR_TYPE_STRING)
    dirEntity.setAttribute("name", dirName)
    dirEntity.setAttribute("qualifiedName", dirQualifiedName)
    dirEntity.setAttribute("objectPrefix", dirQualifiedName)
    dirEntity.setAttribute("bucket", AtlasUtils.entityToReference(bucketEntity))

    // object
    val objectEntity = new AtlasEntity(S3_OBJECT_TYPE_STRING)
    objectEntity.setAttribute("name", objectName)
    objectEntity.setAttribute("path", path)
    objectEntity.setAttribute("qualifiedName", objectQualifiedName)
    objectEntity.setAttribute("pseudoDirectory", AtlasUtils.entityToReference(dirEntity))

    // dir entity depends on bucket entity
    val dirEntityWithDeps = new SACAtlasEntityWithDependencies(dirEntity,
      Seq(SACAtlasEntityWithDependencies(bucketEntity)))

    // object entity depends on dir entity
    new SACAtlasEntityWithDependencies(objectEntity, Seq(dirEntityWithDeps))
  }

  def filesToDirEntities(files: Seq[String]): Seq[SACAtlasEntityWithDependencies] = {
    // assume all inputs are files, not directories
    val directories = files.map { file =>
      val uri = resolveURI(file)
      val fsPath = new Path(uri)
      fsPath.getParent.toString
    }.toSet

    directories.map(pathToEntity).toSeq
  }

  def pathToEntity(path: String): SACAtlasEntityWithDependencies = {
    val uri = resolveURI(path)
    val fsPath = new Path(uri)
    if (uri.getScheme == "hdfs") {
      val entity = new AtlasEntity(HDFS_PATH_TYPE_STRING)
      entity.setAttribute("name",
        Path.getPathWithoutSchemeAndAuthority(fsPath).toString.toLowerCase)
      entity.setAttribute("path",
        Path.getPathWithoutSchemeAndAuthority(fsPath).toString.toLowerCase)
      entity.setAttribute("qualifiedName", uri.toString)
      entity.setAttribute(AtlasConstants.CLUSTER_NAME_ATTRIBUTE, uri.getAuthority)

      SACAtlasEntityWithDependencies(entity)
    } else if (isS3Schema(uri.getScheme)) {
      extractS3Entity(uri, fsPath)
    } else {
      val entity = new AtlasEntity(FS_PATH_TYPE_STRING)
      entity.setAttribute("name",
        Path.getPathWithoutSchemeAndAuthority(fsPath).toString.toLowerCase)
      entity.setAttribute("path",
        Path.getPathWithoutSchemeAndAuthority(fsPath).toString.toLowerCase)
      entity.setAttribute("qualifiedName", uri.toString)

      SACAtlasEntityWithDependencies(entity)
    }
  }

  private def qualifiedPath(path: String): Path = {
    val p = new Path(path)
    val fs = p.getFileSystem(SparkUtils.sparkSession.sparkContext.hadoopConfiguration)
    p.makeQualified(fs.getUri, fs.getWorkingDirectory)
  }

  private def resolveURI(path: String): URI = {
    val uri = new URI(path)
    if (uri.getScheme() != null) {
      return uri
    }

    val qUri = qualifiedPath(path).toUri

    // Path.makeQualified always converts the path as absolute path, but for file scheme
    // it provides different prefix.
    // (It provides prefix as "file" instead of "file://", which both are actually valid.)
    // Given we have been providing it as "file://", the logic below changes the scheme of
    // type "file" to "file://".
    if (qUri.getScheme == "file") {
      // make sure to handle if the path has a fragment (applies to yarn
      // distributed cache)
      if (qUri.getFragment() != null) {
        val absoluteURI = new File(qUri.getPath()).getAbsoluteFile().toURI()
        new URI(absoluteURI.getScheme(), absoluteURI.getHost(), absoluteURI.getPath(),
          uri.getFragment())
      } else {
        new File(path).getAbsoluteFile().toURI()
      }
    } else {
      qUri
    }
  }

  // ================ HBase entities ======================
  val HBASE_NAMESPACE_STRING = "hbase_namespace"
  val HBASE_TABLE_STRING = "hbase_table"
  val HBASE_COLUMNFAMILY_STRING = "hbase_column_family"
  val HBASE_COLUMN_STRING = "hbase_column"

  def hbaseTableToEntity(
      cluster: String,
      tableName: String,
      nameSpace: String): SACAtlasEntityWithDependencies = {
    val hbaseEntity = new AtlasEntity(HBASE_TABLE_STRING)
    hbaseEntity.setAttribute("qualifiedName",
      getTableQualifiedName(cluster, nameSpace, tableName))
    hbaseEntity.setAttribute("name", tableName.toLowerCase)
    hbaseEntity.setAttribute(AtlasConstants.CLUSTER_NAME_ATTRIBUTE, cluster)
    hbaseEntity.setAttribute("uri", nameSpace.toLowerCase + ":" + tableName.toLowerCase)

    SACAtlasEntityWithDependencies(hbaseEntity)
  }

  // ================ Kafka entities =======================
  val KAFKA_TOPIC_STRING = "kafka_topic"

  def kafkaToEntity(
      cluster: String,
      topic: KafkaTopicInformation): SACAtlasEntityWithDependencies = {
    val topicName = topic.topicName.toLowerCase
    val clusterName = topic.clusterName match {
      case Some(customName) => customName
      case None => cluster
    }

    val kafkaEntity = new AtlasEntity(KAFKA_TOPIC_STRING)
    kafkaEntity.setAttribute("qualifiedName", topicName + '@' + clusterName)
    kafkaEntity.setAttribute("name", topicName)
    kafkaEntity.setAttribute(AtlasConstants.CLUSTER_NAME_ATTRIBUTE, clusterName)
    kafkaEntity.setAttribute("uri", topicName)
    kafkaEntity.setAttribute("topic", topicName)

    SACAtlasEntityWithDependencies(kafkaEntity)
  }

  // ================== Hive Catalog entities =====================
  val HIVE_TABLE_TYPE_STRING = "hive_table"

  // scalastyle:off
  /**
   * This is based on the logic how Hive Hook defines qualifiedName for Hive DB (borrowed from Apache Atlas v1.1).
   * https://github.com/apache/atlas/blob/release-1.1.0-rc2/addons/hive-bridge/src/main/java/org/apache/atlas/hive/bridge/HiveMetaStoreBridge.java#L833-L841
   *
   * As we cannot guarantee same qualifiedName for temporary table, we just don't support
   * temporary table in SAC.
   */
  // scalastyle:on
  def hiveTableUniqueAttribute(
      cluster: String,
      db: String,
      table: String): String = {
    s"${db.toLowerCase}.${table.toLowerCase}@$cluster"
  }

  def hiveTableToReference(
      tblDefinition: CatalogTable,
      cluster: String,
      mockDbDefinition: Option[CatalogDatabase] = None): SACAtlasReferenceable = {
    val tableDefinition = SparkUtils.getCatalogTableIfExistent(tblDefinition)
    val db = SparkUtils.getDatabaseName(tableDefinition)
    val table = SparkUtils.getTableName(tableDefinition)
    hiveTableToReference(db, table, cluster)
  }

  def hiveTableToReference(
      db: String,
      table: String,
      cluster: String): SACAtlasReferenceable = {
    val qualifiedName = hiveTableUniqueAttribute(cluster, db, table)
    SACAtlasEntityReference(
      new AtlasObjectId(HIVE_TABLE_TYPE_STRING, "qualifiedName", qualifiedName))
  }
}
