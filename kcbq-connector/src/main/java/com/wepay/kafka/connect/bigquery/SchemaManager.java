package com.wepay.kafka.connect.bigquery;


import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryException;
import com.google.cloud.bigquery.Clustering;
import com.google.cloud.bigquery.Field;
import com.google.cloud.bigquery.LegacySQLTypeName;
import com.google.cloud.bigquery.StandardTableDefinition;
import com.google.cloud.bigquery.TableId;
import com.google.cloud.bigquery.TableInfo;
import com.google.cloud.bigquery.TimePartitioning;
import com.google.cloud.bigquery.TimePartitioning.Type;
import com.google.common.annotations.VisibleForTesting;
import com.wepay.kafka.connect.bigquery.api.SchemaRetriever;
import com.wepay.kafka.connect.bigquery.config.BigQuerySinkConfig;
import com.wepay.kafka.connect.bigquery.convert.KafkaDataBuilder;
import com.wepay.kafka.connect.bigquery.convert.SchemaConverter;
import com.wepay.kafka.connect.bigquery.exception.BigQueryConnectException;
import com.wepay.kafka.connect.bigquery.utils.TableNameUtils;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.sink.SinkRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Class for managing Schemas of BigQuery tables (creating and updating).
 */
public class SchemaManager {

  private static final Logger logger = LoggerFactory.getLogger(SchemaManager.class);

  private final SchemaRetriever schemaRetriever;
  private final SchemaConverter<com.google.cloud.bigquery.Schema> schemaConverter;
  private final BigQuery bigQuery;
  private final boolean allowNewBQFields;
  private final boolean allowBQRequiredFieldRelaxation;
  private final boolean allowSchemaUnionization;
  private final Optional<String> kafkaKeyFieldName;
  private final Optional<String> kafkaDataFieldName;
  private final Optional<String> timestampPartitionFieldName;
  private final Optional<List<String>> clusteringFieldName;
  private final boolean intermediateTables;
  private final ConcurrentMap<TableId, Object> tableCreateLocks;
  private final ConcurrentMap<TableId, Object> tableUpdateLocks;
  private final ConcurrentMap<TableId, com.google.cloud.bigquery.Schema> schemaCache;

  /**
   * @param schemaRetriever Used to determine the Kafka Connect Schema that should be used for a
   *                        given table.
   * @param schemaConverter Used to convert Kafka Connect Schemas into BigQuery format.
   * @param bigQuery Used to communicate create/update requests to BigQuery.
   * @param allowNewBQFields If set to true, allows new fields to be added to BigQuery Schema.
   * @param allowBQRequiredFieldRelaxation If set to true, allows changing field mode from REQUIRED to NULLABLE
   * @param allowSchemaUnionization If set to true, allows existing and new schemas to be unionized
   * @param kafkaKeyFieldName The name of kafka key field to be used in BigQuery.
   *                          If set to null, Kafka Key Field will not be included in BigQuery.
   * @param kafkaDataFieldName The name of kafka data field to be used in BigQuery.
   *                           If set to null, Kafka Data Field will not be included in BigQuery.
   * @param timestampPartitionFieldName The name of the field to use for column-based time
   *                                    partitioning in BigQuery.
   *                                    If set to null, ingestion time-based partitioning will be
   *                                    used instead.
   * @param clusteringFieldName
   */
  public SchemaManager(
      SchemaRetriever schemaRetriever,
      SchemaConverter<com.google.cloud.bigquery.Schema> schemaConverter,
      BigQuery bigQuery,
      boolean allowNewBQFields,
      boolean allowBQRequiredFieldRelaxation,
      boolean allowSchemaUnionization,
      Optional<String> kafkaKeyFieldName,
      Optional<String> kafkaDataFieldName,
      Optional<String> timestampPartitionFieldName,
      Optional<List<String>> clusteringFieldName) {
    this(
        schemaRetriever,
        schemaConverter,
        bigQuery,
        allowNewBQFields,
        allowBQRequiredFieldRelaxation,
        allowSchemaUnionization,
        kafkaKeyFieldName,
        kafkaDataFieldName,
        timestampPartitionFieldName,
        clusteringFieldName,
        false,
        new ConcurrentHashMap<>(),
        new ConcurrentHashMap<>(),
        new ConcurrentHashMap<>());
  }

  private SchemaManager(
      SchemaRetriever schemaRetriever,
      SchemaConverter<com.google.cloud.bigquery.Schema> schemaConverter,
      BigQuery bigQuery,
      boolean allowNewBQFields,
      boolean allowBQRequiredFieldRelaxation,
      boolean allowSchemaUnionization,
      Optional<String> kafkaKeyFieldName,
      Optional<String> kafkaDataFieldName,
      Optional<String> timestampPartitionFieldName,
      Optional<List<String>> clusteringFieldName,
      boolean intermediateTables,
      ConcurrentMap<TableId, Object> tableCreateLocks,
      ConcurrentMap<TableId, Object> tableUpdateLocks,
      ConcurrentMap<TableId, com.google.cloud.bigquery.Schema> schemaCache) {
    this.schemaRetriever = schemaRetriever;
    this.schemaConverter = schemaConverter;
    this.bigQuery = bigQuery;
    this.allowNewBQFields = allowNewBQFields;
    this.allowBQRequiredFieldRelaxation = allowBQRequiredFieldRelaxation;
    this.allowSchemaUnionization = allowSchemaUnionization;
    this.kafkaKeyFieldName = kafkaKeyFieldName;
    this.kafkaDataFieldName = kafkaDataFieldName;
    this.timestampPartitionFieldName = timestampPartitionFieldName;
    this.clusteringFieldName = clusteringFieldName;
    this.intermediateTables = intermediateTables;
    this.tableCreateLocks = tableCreateLocks;
    this.tableUpdateLocks = tableUpdateLocks;
    this.schemaCache = schemaCache;
  }

  public SchemaManager forIntermediateTables() {
    return new SchemaManager(
        schemaRetriever,
        schemaConverter,
        bigQuery,
        allowNewBQFields,
        allowBQRequiredFieldRelaxation,
        allowSchemaUnionization,
        kafkaKeyFieldName,
        kafkaDataFieldName,
        timestampPartitionFieldName,
        clusteringFieldName,
        true,
        tableCreateLocks,
        tableUpdateLocks,
        schemaCache
    );
  }

  /**
   * Fetch the most recent schema for the given table, assuming it has been created and/or updated
   * over the lifetime of this schema manager.
   * @param table the table to fetch the schema for; may be null
   * @return the latest schema for that table; may be null if the table does not exist or has not
   * been created or updated by this schema manager
   */
  public com.google.cloud.bigquery.Schema cachedSchema(TableId table) {
    return schemaCache.get(table);
  }

  /**
   * Create a new table in BigQuery, if it doesn't already exist. Otherwise, update the existing
   * table to use the most-current schema.
   * @param table The BigQuery table to create,
   * @param records The sink records used to determine the schema.
   */
  public void createOrUpdateTable(TableId table, List<SinkRecord> records) {
    synchronized (lock(tableCreateLocks, table)) {
      if (bigQuery.getTable(table) == null) {
        logger.debug("{} doesn't exist; creating instead of updating", table(table));
        if (createTable(table, records)) {
          return;
        }
      }
    }

    // Table already existed; attempt to update instead
    logger.debug("{} already exists; updating instead of creating", table(table));
    updateSchema(table, records);
  }

  /**
   * Create a new table in BigQuery.
   * @param table The BigQuery table to create.
   * @param records The sink records used to determine the schema.
   * @return whether the table had to be created; if the table already existed, will return false
   */
  public boolean createTable(TableId table, List<SinkRecord> records) {
    synchronized (lock(tableCreateLocks, table)) {
      if (schemaCache.containsKey(table)) {
        // Table already exists; noop
        logger.debug("Skipping create of {} as it should already exist or appear very soon", table(table));
        return false;
      }
      TableInfo tableInfo = getTableInfo(table, records);
      logger.info("Attempting to create {} with schema {}",
          table(table), tableInfo.getDefinition().getSchema());
      try {
        bigQuery.create(tableInfo);
        logger.debug("Successfully created {}", table(table));
        schemaCache.put(table, tableInfo.getDefinition().getSchema());
        return true;
      } catch (BigQueryException e) {
        if (e.getCode() == 409) {
          logger.debug("Failed to create {} as it already exists (possibly created by another task)", table(table));
          schemaCache.put(table, readTableSchema(table));
          return false;
        }
        throw e;
      }
    }
  }

  /**
   * Update an existing table in BigQuery.
   * @param table The BigQuery table to update.
   * @param records The sink records used to update the schema.
   */
  public void updateSchema(TableId table, List<SinkRecord> records) {
    synchronized (lock(tableUpdateLocks, table)) {
      TableInfo tableInfo = getTableInfo(table, records);
      if (!schemaCache.containsKey(table)) {
        schemaCache.put(table, readTableSchema(table));
      }

      if (!schemaCache.get(table).equals(tableInfo.getDefinition().getSchema())) {
        logger.info("Attempting to update {} with schema {}",
            table(table), tableInfo.getDefinition().getSchema());
        bigQuery.update(tableInfo);
        logger.debug("Successfully updated {}", table(table));
        schemaCache.put(table, tableInfo.getDefinition().getSchema());
      } else {
        logger.debug("Skipping update of {} since current schema should be compatible", table(table));
      }
    }
  }

  /**
   * Returns the {@link TableInfo} instance of a bigQuery Table
   * @param table The BigQuery table to return the table info
   * @param records The sink records used to determine the schema for constructing the table info
   * @return The resulting BigQuery table information
   */
  private TableInfo getTableInfo(TableId table, List<SinkRecord> records) {
    com.google.cloud.bigquery.Schema proposedSchema;
    String tableDescription;
    try {
      proposedSchema = getAndValidateProposedSchema(table, records);
      tableDescription = getUnionizedTableDescription(records);
    } catch (BigQueryConnectException exception) {
      throw new BigQueryConnectException("Failed to unionize schemas of records for the table " + table, exception);
    }
    return constructTableInfo(table, proposedSchema, tableDescription);
  }

  @VisibleForTesting
  com.google.cloud.bigquery.Schema getAndValidateProposedSchema(
      TableId table, List<SinkRecord> records) {
    com.google.cloud.bigquery.Schema result;
    if (allowSchemaUnionization) {
      List<com.google.cloud.bigquery.Schema> bigQuerySchemas = getSchemasList(table, records);
      result = getUnionizedSchema(bigQuerySchemas);
    } else {
      com.google.cloud.bigquery.Schema existingSchema = readTableSchema(table);
      result = convertRecordSchema(records.get(records.size() - 1));
      if (existingSchema != null) {
        validateSchemaChange(existingSchema, result);
        if (allowBQRequiredFieldRelaxation) {
          result = relaxFieldsWhereNecessary(existingSchema, result);
        }
      }
    }
    return result;
  }

  /**
   * Returns a list of BigQuery schemas of the specified table and the sink records
   * @param table The BigQuery table's schema to add to the list of schemas
   * @param records The sink records' schemas to add to the list of schemas
   * @return List of BigQuery schemas
   */
  private List<com.google.cloud.bigquery.Schema> getSchemasList(TableId table, List<SinkRecord> records) {
    List<com.google.cloud.bigquery.Schema> bigQuerySchemas = new ArrayList<>();
    Optional.ofNullable(readTableSchema(table)).ifPresent(bigQuerySchemas::add);
    for (SinkRecord record : records) {
      bigQuerySchemas.add(convertRecordSchema(record));
    }
    return bigQuerySchemas;
  }

  private com.google.cloud.bigquery.Schema convertRecordSchema(SinkRecord record) {
    Schema kafkaValueSchema = schemaRetriever.retrieveValueSchema(record);
    Schema kafkaKeySchema = kafkaKeyFieldName.isPresent() ? schemaRetriever.retrieveKeySchema(record) : null;
    com.google.cloud.bigquery.Schema result = getBigQuerySchema(kafkaKeySchema, kafkaValueSchema);
    return result;
  }

  /**
   * Returns a unionized schema from a list of BigQuery schemas
   * @param schemas The list of BigQuery schemas to unionize
   * @return The resulting unionized BigQuery schema
   */
  private com.google.cloud.bigquery.Schema getUnionizedSchema(List<com.google.cloud.bigquery.Schema> schemas) {
    com.google.cloud.bigquery.Schema currentSchema = schemas.get(0);
    com.google.cloud.bigquery.Schema proposedSchema;
    for (int i = 1; i < schemas.size(); i++) {
      proposedSchema = unionizeSchemas(currentSchema, schemas.get(i));
      validateSchemaChange(currentSchema, proposedSchema);
      currentSchema = proposedSchema;
    }
    return currentSchema;
  }

  /**
   * Returns a single unionized BigQuery schema from two BigQuery schemas.
   * @param firstSchema The first BigQuery schema to unionize
   * @param secondSchema The second BigQuery schema to unionize
   * @return The resulting unionized BigQuery schema
   */
  private com.google.cloud.bigquery.Schema unionizeSchemas(
      com.google.cloud.bigquery.Schema firstSchema, com.google.cloud.bigquery.Schema secondSchema) {
    Map<String, Field> firstSchemaFields = schemaFields(firstSchema);
    Map<String, Field> secondSchemaFields = schemaFields(secondSchema);
    Map<String, Field> unionizedSchemaFields = new LinkedHashMap<>();

    firstSchemaFields.forEach((name, firstField) -> {
      Field secondField = secondSchemaFields.get(name);
      if (secondField == null) {
        unionizedSchemaFields.put(name, firstField.toBuilder().setMode(Field.Mode.NULLABLE).build());
      } else if (isFieldRelaxation(firstField, secondField)) {
        unionizedSchemaFields.put(name, secondField);
      } else {
        unionizedSchemaFields.put(name, firstField);
      }
    });

    secondSchemaFields.forEach((name, secondField) -> {
      if (!unionizedSchemaFields.containsKey(name)) {
        unionizedSchemaFields.put(name, secondField.toBuilder().setMode(Field.Mode.NULLABLE).build());
      }
    });
    return com.google.cloud.bigquery.Schema.of(unionizedSchemaFields.values());
  }

  private void validateSchemaChange(
      com.google.cloud.bigquery.Schema existingSchema, com.google.cloud.bigquery.Schema proposedSchema) {
    Map<String, Field> earliestSchemaFields = schemaFields(existingSchema);
    Map<String, Field> proposedSchemaFields = schemaFields(proposedSchema);
    for (Map.Entry<String, Field> entry : proposedSchemaFields.entrySet()) {
      if (!earliestSchemaFields.containsKey(entry.getKey())) {
        if (!isValidFieldAddition(entry.getValue())) {
          throw new BigQueryConnectException("New Field found with the name " + entry.getKey()
              + " Ensure that " + BigQuerySinkConfig.ALLOW_NEW_BIGQUERY_FIELDS_CONFIG + " is true and "
              + BigQuerySinkConfig.ALLOW_BIGQUERY_REQUIRED_FIELD_RELAXATION_CONFIG +
              " is true if " + entry.getKey() + " has mode REQUIRED in order to update the Schema");
        }
      } else if (isFieldRelaxation(earliestSchemaFields.get(entry.getKey()), entry.getValue())) {
        if (!allowBQRequiredFieldRelaxation) {
          throw new BigQueryConnectException( entry.getKey() + " has mode REQUIRED. Set "
              + BigQuerySinkConfig.ALLOW_BIGQUERY_REQUIRED_FIELD_RELAXATION_CONFIG
              + " to true, to change the mode to NULLABLE");
        }
      }
    }
  }

  private boolean isFieldRelaxation(Field currentField, Field proposedField) {
    return currentField.getMode().equals(Field.Mode.REQUIRED)
        && proposedField.getMode().equals(Field.Mode.NULLABLE);
  }

  private boolean isValidFieldAddition(Field newField) {
    return allowNewBQFields && (
        newField.getMode().equals(Field.Mode.NULLABLE) ||
        (newField.getMode().equals(Field.Mode.REQUIRED) && allowBQRequiredFieldRelaxation));
  }

  private com.google.cloud.bigquery.Schema relaxFieldsWhereNecessary(
      com.google.cloud.bigquery.Schema existingSchema,
      com.google.cloud.bigquery.Schema proposedSchema) {
    Map<String, Field> existingSchemaFields = schemaFields(existingSchema);
    Map<String, Field> proposedSchemaFields = schemaFields(proposedSchema);
    List<Field> newSchemaFields = new ArrayList<>();
    for (Map.Entry<String, Field> entry : proposedSchemaFields.entrySet()) {
      if (!existingSchemaFields.containsKey(entry.getKey())) {
        newSchemaFields.add(entry.getValue().toBuilder().setMode(Field.Mode.NULLABLE).build());
      } else {
        newSchemaFields.add(entry.getValue());
      }
    }
    return com.google.cloud.bigquery.Schema.of(newSchemaFields);
  }

  /**
   * Returns a unionized table description from a set of sink records going to the same BigQuery table.
   * @param records The records used to get the unionized table description
   * @return The resulting table description
   */
  private String getUnionizedTableDescription(List<SinkRecord> records) {
    String tableDescription = null;
    for (SinkRecord record : records) {
      Schema kafkaValueSchema = schemaRetriever.retrieveValueSchema(record);
      tableDescription = kafkaValueSchema.doc() != null ? kafkaValueSchema.doc() : tableDescription;
    }
    return tableDescription;
  }

  /**
   * Returns a dictionary providing lookup of each field in the schema by name. The ordering of the
   * fields in the schema is preserved in the returned map.
   * @param schema The BigQuery schema
   * @return A map allowing lookup of schema fields by name
   */
  private Map<String, Field> schemaFields(com.google.cloud.bigquery.Schema schema) {
    Map<String, Field> result = new LinkedHashMap<>();
    schema.getFields().forEach(field -> result.put(field.getName(), field));
    return result;
  }

  // package private for testing.
  TableInfo constructTableInfo(TableId table, com.google.cloud.bigquery.Schema bigQuerySchema, String tableDescription) {
    StandardTableDefinition.Builder builder = StandardTableDefinition.newBuilder()
        .setSchema(bigQuerySchema);

    if (intermediateTables) {
      // Shameful hack: make the table ingestion time-partitioned here so that the _PARTITIONTIME
      // pseudocolumn can be queried to filter out rows that are still in the streaming buffer
      builder.setTimePartitioning(TimePartitioning.of(Type.DAY));
    } else {
      TimePartitioning timePartitioning = TimePartitioning.of(Type.DAY);
      if (timestampPartitionFieldName.isPresent()) {
        timePartitioning = timePartitioning.toBuilder().setField(timestampPartitionFieldName.get()).build();
      }
  
      builder.setTimePartitioning(timePartitioning);

      if (timestampPartitionFieldName.isPresent() && clusteringFieldName.isPresent()) {
        Clustering clustering = Clustering.newBuilder()
            .setFields(clusteringFieldName.get())
            .build();
        builder.setClustering(clustering);
      }
    }

    StandardTableDefinition tableDefinition = builder.build();
    TableInfo.Builder tableInfoBuilder =
        TableInfo.newBuilder(table, tableDefinition);
    if (intermediateTables) {
      tableInfoBuilder.setDescription("Temporary table");
    } else if (tableDescription != null) {
      tableInfoBuilder.setDescription(tableDescription);
    }
    
    return tableInfoBuilder.build();
  }

  private com.google.cloud.bigquery.Schema getBigQuerySchema(Schema kafkaKeySchema, Schema kafkaValueSchema) {
    com.google.cloud.bigquery.Schema valueSchema = schemaConverter.convertSchema(kafkaValueSchema);

    List<Field> schemaFields = intermediateTables
        ? getIntermediateSchemaFields(valueSchema, kafkaKeySchema)
        : getRegularSchemaFields(valueSchema, kafkaKeySchema);

    return com.google.cloud.bigquery.Schema.of(schemaFields);
  }

  private List<Field> getIntermediateSchemaFields(com.google.cloud.bigquery.Schema valueSchema, Schema kafkaKeySchema) {
    if (kafkaKeySchema == null) {
      throw new BigQueryConnectException(String.format(
          "Cannot create intermediate table without specifying a value for '%s'",
          BigQuerySinkConfig.KAFKA_KEY_FIELD_NAME_CONFIG
      ));
    }

    List<Field> result = new ArrayList<>();

    List<Field> valueFields = new ArrayList<>(valueSchema.getFields());
    if (kafkaDataFieldName.isPresent()) {
      Field kafkaDataField = KafkaDataBuilder.buildKafkaDataField(kafkaDataFieldName.get());
      valueFields.add(kafkaDataField);
    }

    // Wrap the sink record value (and possibly also its Kafka data) in a struct in order to support deletes
    Field wrappedValueField = Field
        .newBuilder(MergeQueries.INTERMEDIATE_TABLE_VALUE_FIELD_NAME, LegacySQLTypeName.RECORD, valueFields.toArray(new Field[0]))
        .setMode(Field.Mode.NULLABLE)
        .build();
    result.add(wrappedValueField);

    com.google.cloud.bigquery.Schema keySchema = schemaConverter.convertSchema(kafkaKeySchema);
    Field kafkaKeyField = Field.newBuilder(MergeQueries.INTERMEDIATE_TABLE_KEY_FIELD_NAME, LegacySQLTypeName.RECORD, keySchema.getFields())
        .setMode(Field.Mode.REQUIRED)
        .build();
    result.add(kafkaKeyField);

    Field iterationField = Field
        .newBuilder(MergeQueries.INTERMEDIATE_TABLE_ITERATION_FIELD_NAME, LegacySQLTypeName.INTEGER)
        .setMode(Field.Mode.REQUIRED)
        .build();
    result.add(iterationField);

    Field partitionTimeField = Field
        .newBuilder(MergeQueries.INTERMEDIATE_TABLE_PARTITION_TIME_FIELD_NAME, LegacySQLTypeName.TIMESTAMP)
        .setMode(Field.Mode.NULLABLE)
        .build();
    result.add(partitionTimeField);

    Field batchNumberField = Field
        .newBuilder(MergeQueries.INTERMEDIATE_TABLE_BATCH_NUMBER_FIELD, LegacySQLTypeName.INTEGER)
        .setMode(Field.Mode.REQUIRED)
        .build();
    result.add(batchNumberField);

    return result;
  }

  private List<Field> getRegularSchemaFields(com.google.cloud.bigquery.Schema valueSchema, Schema kafkaKeySchema) {
    List<Field> result = new ArrayList<>(valueSchema.getFields());

    if (kafkaDataFieldName.isPresent()) {
      Field kafkaDataField = KafkaDataBuilder.buildKafkaDataField(kafkaDataFieldName.get());
      result.add(kafkaDataField);
    }

    if (kafkaKeyFieldName.isPresent()) {
      com.google.cloud.bigquery.Schema keySchema = schemaConverter.convertSchema(kafkaKeySchema);
      Field kafkaKeyField = Field.newBuilder(kafkaKeyFieldName.get(), LegacySQLTypeName.RECORD, keySchema.getFields())
          .setMode(Field.Mode.NULLABLE).build();
      result.add(kafkaKeyField);
    }

    return result;
  }

  private String table(TableId table) {
    return intermediateTables
        ? TableNameUtils.intTable(table)
        : TableNameUtils.table(table);
  }

  private com.google.cloud.bigquery.Schema readTableSchema(TableId table) {
    logger.trace("Reading schema for {}", table(table));
    return Optional.ofNullable(bigQuery.getTable(table))
        .map(t -> t.getDefinition().getSchema())
        .orElse(null);
  }

  private Object lock(ConcurrentMap<TableId, Object> locks, TableId table) {
    return locks.computeIfAbsent(table, t -> new Object());
  }
}
