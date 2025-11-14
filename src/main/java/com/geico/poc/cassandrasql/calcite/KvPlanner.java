package com.geico.poc.cassandrasql.calcite;

import org.apache.calcite.config.CalciteConnectionConfig;
import org.apache.calcite.config.CalciteConnectionConfigImpl;
import org.apache.calcite.config.CalciteConnectionProperty;
import org.apache.calcite.jdbc.CalciteSchema;
import org.apache.calcite.plan.*;
import org.apache.calcite.plan.volcano.VolcanoPlanner;
import org.apache.calcite.prepare.CalciteCatalogReader;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.util.ChainedSqlOperatorTable;
import org.apache.calcite.sql.validate.SqlValidator;
import org.apache.calcite.sql.validate.SqlValidatorUtil;
import org.apache.calcite.sql2rel.SqlToRelConverter;
import org.apache.calcite.sql2rel.StandardConvertletTable;
import org.apache.calcite.tools.FrameworkConfig;
import org.apache.calcite.tools.Frameworks;
import org.apache.calcite.tools.RelBuilder;
import com.geico.poc.cassandrasql.kv.SchemaManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Properties;

/**
 * Calcite-based query planner for KV mode.
 * Provides cost-based optimization using Calcite's optimizer framework.
 */
@Component
public class KvPlanner {
    
    private static final Logger log = LoggerFactory.getLogger(KvPlanner.class);
    
    private final SchemaManager schemaManager;
    private final KvSchema kvSchema;
    private final FrameworkConfig config;
    private final SqlParser.Config parserConfig;
    private final RelDataTypeFactory typeFactory;
    
    @Autowired
    public KvPlanner(SchemaManager schemaManager) {
        this.schemaManager = schemaManager;
        this.kvSchema = new KvSchema(schemaManager);
        
        // Configure SQL parser for PostgreSQL compatibility
        this.parserConfig = SqlParser.config()
            .withCaseSensitive(false)
            .withConformance(org.apache.calcite.sql.validate.SqlConformanceEnum.DEFAULT);
        
        // Create Calcite schema
        CalciteSchema rootSchema = CalciteSchema.createRootSchema(false);
        rootSchema.add("public", kvSchema);
        
        // Configure framework
        this.config = Frameworks.newConfigBuilder()
            .defaultSchema(rootSchema.plus())
            .parserConfig(parserConfig)
            .build();
        
        // Create type factory
        this.typeFactory = new org.apache.calcite.jdbc.JavaTypeFactoryImpl();
        
        log.info("KvPlanner initialized with {} tables and KV cost model", kvSchema.getTableMap().size());
    }
    
    /**
     * Parse SQL string to SqlNode AST
     */
    public SqlNode parse(String sql) throws SqlParseException {
        SqlParser parser = SqlParser.create(sql, parserConfig);
        SqlNode sqlNode = parser.parseStmt();
        log.debug("Parsed SQL: {}", sqlNode);
        return sqlNode;
    }
    
    /**
     * Validate SqlNode and convert to RelNode in a single context.
     * This is necessary because SqlToRelConverter needs the validator's scope information.
     */
    public RelNode validateAndConvert(SqlNode sqlNode) {
        // Create schema and catalog
        CalciteSchema rootSchema = CalciteSchema.createRootSchema(false);
        rootSchema.add("public", kvSchema);
        
        CalciteCatalogReader catalogReader = new CalciteCatalogReader(
            rootSchema,
            Collections.singletonList("public"),
            typeFactory,
            createConnectionConfig()
        );
        
        SqlOperatorTable operatorTable = SqlStdOperatorTable.instance();
        
        // Create validator
        SqlValidator validator = SqlValidatorUtil.newValidator(
            operatorTable,
            catalogReader,
            typeFactory,
            SqlValidator.Config.DEFAULT
        );
        
        // Validate (this populates the validator's scope information)
        SqlNode validated = validator.validate(sqlNode);
        log.debug("Validated SQL: {}", validated);
        
        // Create converter with the SAME validator
        RexBuilder rexBuilder = new RexBuilder(typeFactory);
        RelOptPlanner planner = new VolcanoPlanner(KvCostFactory.INSTANCE, null);
        RelOptCluster cluster = RelOptCluster.create(planner, rexBuilder);
        
        SqlToRelConverter.Config converterConfig = SqlToRelConverter.config()
            .withTrimUnusedFields(true)
            .withExpand(false);
        
        SqlToRelConverter converter = new SqlToRelConverter(
            null, // view expander
            validator, // Use the SAME validator that did validation
            catalogReader,
            cluster,
            StandardConvertletTable.INSTANCE,
            converterConfig
        );
        
        // Convert to RelNode
        RelRoot relRoot = converter.convertQuery(validated, false, true);
        log.debug("Logical plan: {}", relRoot.rel);
        return relRoot.rel;
    }
    
    /**
     * Validate SqlNode (type checking, table existence, etc.)
     * Note: For EXPLAIN, use validateAndConvert() instead to maintain scope information.
     */
    public SqlNode validate(SqlNode sqlNode) {
        // Create validator
        CalciteSchema rootSchema = CalciteSchema.createRootSchema(false);
        rootSchema.add("public", kvSchema);
        
        CalciteCatalogReader catalogReader = new CalciteCatalogReader(
            rootSchema,
            Collections.singletonList("public"),
            typeFactory,
            createConnectionConfig()
        );
        
        SqlOperatorTable operatorTable = SqlStdOperatorTable.instance();
        
        SqlValidator validator = SqlValidatorUtil.newValidator(
            operatorTable,
            catalogReader,
            typeFactory,
            SqlValidator.Config.DEFAULT
        );
        
        SqlNode validated = validator.validate(sqlNode);
        log.debug("Validated SQL: {}", validated);
        return validated;
    }
    
    /**
     * Convert SqlNode to RelNode (logical plan)
     * Note: This should only be called after validate() in the same context.
     * For EXPLAIN, use validateAndConvert() instead.
     */
    public RelNode convertToRelNode(SqlNode validated) {
        // This method is kept for backward compatibility but should not be used
        // for EXPLAIN queries as it creates a new validator without scope information
        throw new UnsupportedOperationException(
            "convertToRelNode() cannot be called separately. Use validateAndConvert() instead.");
    }
    
    /**
     * Optimize RelNode using cost-based optimizer with index selection
     */
    public RelNode optimize(RelNode logicalPlan) {
        log.debug("Optimizing plan: {}", logicalPlan);
        
        // For now, return the logical plan as-is
        // The VolcanoPlanner is already locked after conversion, so we can't optimize further
        // TODO: Implement proper optimization in a separate planner instance
        // For EXPLAIN purposes, the index selection will be shown separately
        
        log.debug("Returning logical plan (optimization not yet implemented)");
        return logicalPlan;
    }
    
    /**
     * Add optimization rules to the planner
     */
    private void addOptimizationRules(VolcanoPlanner planner) {
        // Add standard Calcite rules
        planner.addRule(org.apache.calcite.rel.rules.CoreRules.FILTER_INTO_JOIN);
        planner.addRule(org.apache.calcite.rel.rules.CoreRules.FILTER_MERGE);
        planner.addRule(org.apache.calcite.rel.rules.CoreRules.PROJECT_MERGE);
        planner.addRule(org.apache.calcite.rel.rules.CoreRules.PROJECT_REMOVE);
        planner.addRule(org.apache.calcite.rel.rules.CoreRules.JOIN_COMMUTE);
        
        // TODO: Add custom KV-specific rules for index selection
        // For now, Calcite's built-in rules will handle basic optimizations
        
        log.debug("Added {} optimization rules", planner.getRules().size());
    }
    
    /**
     * Full planning pipeline: parse → validate → convert → optimize
     */
    public RelNode plan(String sql) throws SqlParseException {
        log.info("Planning query: {}", sql);
        
        // 1. Parse
        SqlNode sqlNode = parse(sql);
        
        // 2. Validate and convert (must be done together to maintain scope)
        RelNode relNode = validateAndConvert(sqlNode);
        
        // 3. Optimize
        RelNode optimized = optimize(relNode);
        
        log.info("Query planning complete");
        return optimized;
    }
    
    /**
     * Refresh schema (call when tables are added/dropped)
     */
    public void refreshSchema() {
        kvSchema.refreshTables();
        log.info("Schema refreshed");
    }
    
    /**
     * Get the KV schema
     */
    public KvSchema getKvSchema() {
        return kvSchema;
    }
    
    private CalciteConnectionConfig createConnectionConfig() {
        Properties props = new Properties();
        props.setProperty(CalciteConnectionProperty.CASE_SENSITIVE.camelName(), "false");
        props.setProperty(CalciteConnectionProperty.CONFORMANCE.camelName(), "POSTGRESQL");
        return new CalciteConnectionConfigImpl(props);
    }
}

