package com.facebook.presto.sql.planner;

import com.facebook.presto.metadata.ColumnHandle;
import com.facebook.presto.metadata.ColumnMetadata;
import com.facebook.presto.metadata.FunctionHandle;
import com.facebook.presto.metadata.Metadata;
import com.facebook.presto.metadata.MetadataUtil;
import com.facebook.presto.metadata.QualifiedTableName;
import com.facebook.presto.metadata.TableHandle;
import com.facebook.presto.metadata.TableMetadata;
import com.facebook.presto.sql.analyzer.Analysis;
import com.facebook.presto.sql.analyzer.Field;
import com.facebook.presto.sql.analyzer.FieldOrExpression;
import com.facebook.presto.sql.analyzer.Session;
import com.facebook.presto.sql.analyzer.TupleDescriptor;
import com.facebook.presto.sql.analyzer.Type;
import com.facebook.presto.sql.planner.plan.AggregationNode;
import com.facebook.presto.sql.planner.plan.FilterNode;
import com.facebook.presto.sql.planner.plan.LimitNode;
import com.facebook.presto.sql.planner.plan.PlanNode;
import com.facebook.presto.sql.planner.plan.ProjectNode;
import com.facebook.presto.sql.planner.plan.SortNode;
import com.facebook.presto.sql.planner.plan.TableScanNode;
import com.facebook.presto.sql.planner.plan.TopNNode;
import com.facebook.presto.sql.planner.plan.WindowNode;
import com.facebook.presto.sql.tree.DefaultTraversalVisitor;
import com.facebook.presto.sql.tree.Expression;
import com.facebook.presto.sql.tree.FunctionCall;
import com.facebook.presto.sql.tree.QualifiedName;
import com.facebook.presto.sql.tree.QualifiedNameReference;
import com.facebook.presto.sql.tree.Query;
import com.facebook.presto.sql.tree.SortItem;
import com.facebook.presto.util.IterableTransformer;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.facebook.presto.sql.tree.FunctionCall.argumentsGetter;
import static com.facebook.presto.sql.tree.SortItem.sortKeyGetter;

class QueryPlanner
        extends DefaultTraversalVisitor<PlanBuilder, Void>
{
    private final Analysis analysis;
    private final SymbolAllocator symbolAllocator;
    private final PlanNodeIdAllocator idAllocator;
    private final Metadata metadata;
    private final Session session;

    QueryPlanner(Analysis analysis, SymbolAllocator symbolAllocator, PlanNodeIdAllocator idAllocator, Metadata metadata, Session session)
    {
        Preconditions.checkNotNull(analysis, "analysis is null");
        Preconditions.checkNotNull(symbolAllocator, "symbolAllocator is null");
        Preconditions.checkNotNull(idAllocator, "idAllocator is null");
        Preconditions.checkNotNull(metadata, "metadata is null");
        Preconditions.checkNotNull(session, "session is null");

        this.analysis = analysis;
        this.symbolAllocator = symbolAllocator;
        this.idAllocator = idAllocator;
        this.metadata = metadata;
        this.session = session;
    }

    @Override
    protected PlanBuilder visitQuery(Query query, Void context)
    {
        PlanBuilder builder = planFrom(query);

        builder = filter(builder, analysis.getWhere(query));
        builder = aggregate(builder, query);
        builder = filter(builder, analysis.getHaving(query));

        builder = window(builder, query);

        List<FieldOrExpression> orderBy = analysis.getOrderByExpressions(query);
        List<FieldOrExpression> outputs = analysis.getOutputExpressions(query);
        builder = project(builder, Iterables.concat(orderBy, outputs));


        builder = distinct(builder, query, outputs, orderBy);
        builder = sort(builder, query);
        builder = project(builder, analysis.getOutputExpressions(query));
        builder = limit(builder, query);

        return builder;
    }

    private PlanBuilder planFrom(Query query)
    {
        RelationPlan relationPlan;

        if (query.getFrom() == null || query.getFrom().isEmpty()) {
            relationPlan = planImplicitTable();
        }
        else {
            relationPlan = new RelationPlanner(analysis, symbolAllocator, idAllocator, metadata, session)
                    .process(Iterables.getOnlyElement(query.getFrom()), null);
        }

        TranslationMap translations = new TranslationMap(relationPlan, analysis);

        // Make field->symbol mapping from underlying relation plan available for translations
        // This makes it possible to rewrite FieldOrExpressions that reference fields from the FROM clause directly
        translations.setFieldMappings(relationPlan.getOutputSymbols());

        return new PlanBuilder(translations, relationPlan.getRoot());
    }

    private RelationPlan planImplicitTable()
    {
        // TODO: replace this with a table-generating operator that produces 1 row with no columns

        QualifiedTableName name = MetadataUtil.createQualifiedTableName(session, QualifiedName.of("dual"));
        TableMetadata tableMetadata = metadata.getTable(name);
        TableHandle table = tableMetadata.getTableHandle().get();

        ImmutableMap.Builder<Symbol, ColumnHandle> columns = ImmutableMap.builder();
        for (ColumnMetadata column : tableMetadata.getColumns()) {
            Symbol symbol = symbolAllocator.newSymbol(column.getName(), Type.fromRaw(column.getType()));
            columns.put(symbol, column.getColumnHandle().get());
        }

        TableScanNode tableScan = new TableScanNode(idAllocator.getNextId(), table, columns.build());

        return new RelationPlan(tableScan, new TupleDescriptor(), ImmutableList.<Symbol>of());
    }

    private PlanBuilder filter(PlanBuilder subPlan, Expression predicate)
    {
        if (predicate == null) {
            return subPlan;
        }

        Expression rewritten = subPlan.rewrite(predicate);
        return new PlanBuilder(subPlan.getTranslations(), new FilterNode(idAllocator.getNextId(), subPlan.getRoot(), rewritten));
    }

    private PlanBuilder project(PlanBuilder subPlan, Iterable<FieldOrExpression> expressions)
    {
        TranslationMap outputTranslations = new TranslationMap(subPlan.getRelationPlan(), analysis);

        ImmutableMap.Builder<Symbol, Expression> projections = ImmutableMap.builder();
        for (FieldOrExpression fieldOrExpression : ImmutableSet.copyOf(expressions)) {
            Symbol symbol;

            if (fieldOrExpression.isFieldReference()) {
                Field field = subPlan.getRelationPlan().getDescriptor().getFields().get(fieldOrExpression.getFieldIndex());
                symbol = symbolAllocator.newSymbol(field);
            }
            else {
                Expression expression = fieldOrExpression.getExpression();
                symbol = symbolAllocator.newSymbol(expression, analysis.getType(expression));
            }

            projections.put(symbol, subPlan.rewrite(fieldOrExpression));
            outputTranslations.put(fieldOrExpression, symbol);
        }

        return new PlanBuilder(outputTranslations, new ProjectNode(idAllocator.getNextId(), subPlan.getRoot(), projections.build()));
    }

    private PlanBuilder aggregate(PlanBuilder subPlan, Query query)
    {
        if (analysis.getAggregates(query).isEmpty() && analysis.getGroupByExpressions(query).isEmpty()) {
            return subPlan;
        }

        Set<FieldOrExpression> arguments = IterableTransformer.on(analysis.getAggregates(query))
                .transformAndFlatten(argumentsGetter())
                .transform(toFieldOrExpression())
                .set();

        // 1. Pre-project all scalar inputs (arguments and non-trivial group by expressions)
        Iterable<FieldOrExpression> inputs = Iterables.concat(analysis.getGroupByExpressions(query), arguments);
        if (!Iterables.isEmpty(inputs)) { // avoid an empty projection if the only aggregation is COUNT (which has no arguments)
            subPlan = project(subPlan, inputs);
        }

        // 2. Aggregate
        ImmutableMap.Builder<Symbol, FunctionCall> aggregationAssignments = ImmutableMap.builder();
        ImmutableMap.Builder<Symbol, FunctionHandle> functions = ImmutableMap.builder();

        // 2.a. Rewrite aggregates in terms of pre-projected inputs
        TranslationMap translations = new TranslationMap(subPlan.getRelationPlan(), analysis);
        for (FunctionCall aggregate : analysis.getAggregates(query)) {
            FunctionCall rewritten = (FunctionCall) subPlan.rewrite(aggregate);
            Symbol newSymbol = symbolAllocator.newSymbol(rewritten, analysis.getType(aggregate));

            aggregationAssignments.put(newSymbol, rewritten);
            translations.put(aggregate, newSymbol);

            functions.put(newSymbol, analysis.getFunctionInfo(aggregate).getHandle());
        }

        // 2.b. Rewrite group by expressions in terms of pre-projected inputs
        ImmutableList.Builder<Symbol> groupBySymbols = ImmutableList.builder();
        for (FieldOrExpression fieldOrExpression : analysis.getGroupByExpressions(query)) {
            Symbol symbol = subPlan.translate(fieldOrExpression);
            groupBySymbols.add(symbol);
            translations.put(fieldOrExpression, symbol);
        }

        return new PlanBuilder(translations, new AggregationNode(idAllocator.getNextId(), subPlan.getRoot(), groupBySymbols.build(), aggregationAssignments.build(), functions.build()));
    }

    private PlanBuilder window(PlanBuilder subPlan, Query node)
    {
        Set<FunctionCall> windowFunctions = ImmutableSet.copyOf(analysis.getWindowFunctions(node));
        if (windowFunctions.isEmpty()) {
            return subPlan;
        }

        for (FunctionCall windowFunction : windowFunctions) {
            // Pre-project inputs
            ImmutableList<Expression> inputs = ImmutableList.<Expression>builder()
                    .addAll(windowFunction.getArguments())
                    .addAll(windowFunction.getWindow().get().getPartitionBy())
                    .addAll(Iterables.transform(windowFunction.getWindow().get().getOrderBy(), sortKeyGetter()))
                    .build();

            subPlan = appendProjections(subPlan, inputs);


            // Rewrite PARTITION BY in terms of pre-projected inputs
            ImmutableList.Builder<Symbol> partitionBySymbols = ImmutableList.builder();
            for (Expression expression : windowFunction.getWindow().get().getPartitionBy()) {
                partitionBySymbols.add(subPlan.translate(expression));
            }

            // Rewrite ORDER BY in terms of pre-projected inputs
            ImmutableList.Builder<Symbol> orderBySymbols = ImmutableList.builder();
            Map<Symbol, SortItem.Ordering> orderings = new HashMap<>();
            for (SortItem item : windowFunction.getWindow().get().getOrderBy()) {
                Symbol symbol = subPlan.translate(item.getSortKey());
                orderBySymbols.add(symbol);
                orderings.put(symbol, item.getOrdering());
            }

            TranslationMap outputTranslations = new TranslationMap(subPlan.getRelationPlan(), analysis);
            outputTranslations.copyMappingsFrom(subPlan.getTranslations());

            ImmutableMap.Builder<Symbol, FunctionCall> assignments = ImmutableMap.builder();
            Map<Symbol, FunctionHandle> functionHandles = new HashMap<>();

            // Rewrite function call in terms of pre-projected inputs
            FunctionCall rewritten = (FunctionCall) subPlan.rewrite(windowFunction);
            Symbol newSymbol = symbolAllocator.newSymbol(rewritten, analysis.getType(windowFunction));

            assignments.put(newSymbol, rewritten);
            outputTranslations.put(windowFunction, newSymbol);

            functionHandles.put(newSymbol, analysis.getFunctionInfo(windowFunction).getHandle());

            // create window node
            subPlan = new PlanBuilder(outputTranslations,
                    new WindowNode(idAllocator.getNextId(), subPlan.getRoot(), partitionBySymbols.build(), orderBySymbols.build(), orderings, assignments.build(), functionHandles));
        }

        return subPlan;
    }

    private PlanBuilder appendProjections(PlanBuilder subPlan, Iterable<Expression> expressions)
    {
        TranslationMap translations = new TranslationMap(subPlan.getRelationPlan(), analysis);
        translations.copyMappingsFrom(subPlan.getTranslations());

        ImmutableMap.Builder<Symbol, Expression> projections = ImmutableMap.builder();

        // add an identity projection for underlying plan
        for (Symbol symbol : subPlan.getRoot().getOutputSymbols()) {
            Expression expression = new QualifiedNameReference(symbol.toQualifiedName());
            projections.put(symbol, expression);
        }

        for (Expression expression : expressions) {
            Symbol symbol = symbolAllocator.newSymbol(expression, analysis.getType(expression));

            projections.put(symbol, translations.rewrite(expression));
            translations.put(expression, symbol);
        }

        return new PlanBuilder(translations, new ProjectNode(idAllocator.getNextId(), subPlan.getRoot(), projections.build()));
    }

    private PlanBuilder distinct(PlanBuilder subPlan, Query query, List<FieldOrExpression> outputs, List<FieldOrExpression> orderBy)
    {
        if (query.getSelect().isDistinct()) {
            Preconditions.checkState(outputs.containsAll(orderBy), "Expected ORDER BY terms to be in SELECT. Broken analysis");

            AggregationNode aggregation = new AggregationNode(idAllocator.getNextId(),
                subPlan.getRoot(),
                subPlan.getRoot().getOutputSymbols(),
                ImmutableMap.<Symbol, FunctionCall>of(),
                ImmutableMap.<Symbol, FunctionHandle>of());

            return new PlanBuilder(subPlan.getTranslations(), aggregation);
        }

        return subPlan;
    }

    private PlanBuilder sort(PlanBuilder subPlan, Query query)
    {
        if (query.getOrderBy().isEmpty()) {
            return subPlan;
        }

        Iterator<SortItem> sortItems = query.getOrderBy().iterator();

        ImmutableList.Builder<Symbol> orderBySymbols = ImmutableList.builder();
        ImmutableMap.Builder<Symbol, SortItem.Ordering> orderings = ImmutableMap.builder();
        for (FieldOrExpression fieldOrExpression : analysis.getOrderByExpressions(query)) {
            Symbol symbol = subPlan.translate(fieldOrExpression);
            orderBySymbols.add(symbol);
            orderings.put(symbol, sortItems.next().getOrdering());
        }

        PlanNode node;
        if (query.getLimit().isPresent()) {
            node = new TopNNode(idAllocator.getNextId(), subPlan.getRoot(), Long.valueOf(query.getLimit().get()), orderBySymbols.build(), orderings.build());
        }
        else {
            node = new SortNode(idAllocator.getNextId(), subPlan.getRoot(), orderBySymbols.build(), orderings.build());
        }

        return new PlanBuilder(subPlan.getTranslations(), node);
    }

    private PlanBuilder limit(PlanBuilder subPlan, Query query)
    {
        if (query.getOrderBy().isEmpty() && query.getLimit().isPresent()) {
            long limit = Long.valueOf(query.getLimit().get());
            return new PlanBuilder(subPlan.getTranslations(), new LimitNode(idAllocator.getNextId(), subPlan.getRoot(), limit));
        }

        return subPlan;
    }

    public static Function<Expression, FieldOrExpression> toFieldOrExpression()
    {
        return new Function<Expression, FieldOrExpression>()
        {
            @Nullable
            @Override
            public FieldOrExpression apply(Expression input)
            {
                return new FieldOrExpression(input);
            }
        };
    }
}