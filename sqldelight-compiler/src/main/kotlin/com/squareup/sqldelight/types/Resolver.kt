/*
 * Copyright (C) 2016 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.sqldelight.types

import com.squareup.sqldelight.SqliteParser
import com.squareup.sqldelight.SqlitePluginException
import com.squareup.sqldelight.validation.JoinValidator
import com.squareup.sqldelight.validation.ResultColumnValidator
import com.squareup.sqldelight.validation.SelectOrValuesValidator
import com.squareup.sqldelight.validation.SelectStmtValidator
import com.squareup.sqldelight.validation.SqlDelightValidator
import org.antlr.v4.runtime.ParserRuleContext
import java.util.LinkedHashSet

/**
 * The only job of this class is to return an ordered list of values that any given
 * rule evaluates to. It will perform validation on subqueries to make sure they are well
 * formed before returning the selected columns.
 */
internal class Resolver(
    internal val symbolTable: SymbolTable,
    internal val dependencies: LinkedHashSet<Any> = linkedSetOf<Any>(),
    private val scopedValues: List<Value> = emptyList()
) {
  val currentlyResolvingViews = linkedSetOf<String>()

  internal fun withResolver(with: SqliteParser.With_clauseContext) =
      Resolver(with.cte_table_name().zip(with.select_stmt(), { commonTable, select ->
        commonTable to select
      }).fold(symbolTable, { symbolTable, commonTable ->
        symbolTable + SymbolTable(commonTable, commonTable.first)
      }), dependencies, scopedValues)

  /**
   * Take an insert statement and return the types being inserted.
   */
  fun resolve(
      insertStmt: SqliteParser.Insert_stmtContext,
      availableValues: List<Value>
  ): List<Value> {
    val resolver: Resolver
    if (insertStmt.with_clause() != null) {
      resolver = withResolver(insertStmt.with_clause())
    } else {
      resolver = this
    }

    if (insertStmt.values() != null) {
      return resolver.resolve(insertStmt.values(), availableValues)
    }
    if (insertStmt.select_stmt() != null) {
      return resolver.resolve(insertStmt.select_stmt())
    }
    if (insertStmt.K_DEFAULT() != null) {
      return emptyList()
    }

    throw IllegalStateException("Did not know how to resolve insert statement $insertStmt")
  }

  /**
   * Take a select statement and return the selected columns.
   *
   * If cursorPosition is specified, this function will return the columns available at the cursor
   * position.
   */
  fun resolve(
      selectStmt: SqliteParser.Select_stmtContext,
      cursorPosition: Int = selectStmt.stop.stopIndex
  ): List<Value> {
    val resolver: Resolver
    if (selectStmt.K_WITH() != null) {
      resolver = Resolver(selectStmt.common_table_expression()
          .fold(symbolTable, { symbolTable, commonTable ->
            symbolTable + SymbolTable(commonTable, commonTable)
          }), dependencies, scopedValues)
    } else {
      resolver = this
    }

    val selectedFromFirst = resolver.resolve(selectStmt.select_or_values(0), selectStmt,
        cursorPosition = cursorPosition)

    if (cursorPosition >= selectStmt.select_or_values(0).start.startIndex
        && cursorPosition < selectStmt.select_or_values(0).stop.stopIndex) {
      return selectedFromFirst
    }

    // Resolve other compound select statements and verify they have equivalent columns.
    selectStmt.select_or_values().drop(1).forEach {
      val compoundValues = resolver.resolve(it, cursorPosition = cursorPosition)

      if (cursorPosition >= it.start.startIndex && cursorPosition < it.stop.stopIndex) {
        return compoundValues
      }

      if (compoundValues.size != selectedFromFirst.size) {
        throw SqlitePluginException(it, "Unexpected number of columns in compound statement " +
            "found: ${compoundValues.size} expected: ${selectedFromFirst.size}")
      }
      // TODO: Type checking.
      //for (valueIndex in 0..values.size) {
      //  if (values[valueIndex].type != compoundValues[valueIndex].type) {
      //    throw SqlitePluginException(compoundValues[valueIndex].element, "Incompatible types in " +
      //        "compound statement for column 2 found: ${compoundValues[valueIndex].type} " +
      //        "expected: ${values[valueIndex].type}")
      //  }
      //}
    }

    return selectedFromFirst
  }

  /**
   * Takes a select_or_values rule and returns the columns selected.
   */
  fun resolve(
      selectOrValues: SqliteParser.Select_or_valuesContext,
      parentSelect: SqliteParser.Select_stmtContext? = null,
      cursorPosition: Int = selectOrValues.stop.stopIndex
  ): List<Value> {
    val availableValues: List<Value>
    if (selectOrValues.K_VALUES() != null) {
      // No columns are available, only selected columns are returned.
      SelectOrValuesValidator(this, scopedValues).validate(selectOrValues)
      return resolve(selectOrValues.values())
    } else if (selectOrValues.join_clause() != null) {
      availableValues = resolve(selectOrValues.join_clause())
    } else if (selectOrValues.table_or_subquery().size > 0) {
      availableValues = selectOrValues.table_or_subquery().flatMap { resolve(it) }
    } else {
      throw SqlitePluginException(selectOrValues,
          "Resolver did not know how to handle select or values")
    }

    // Validate the select or values has valid expressions before aliasing/selection.
    SelectOrValuesValidator(this, scopedValues + availableValues).validate(selectOrValues)

    if (parentSelect != null) {
      SelectStmtValidator(this, scopedValues + availableValues).validate(parentSelect)
    }
    return selectOrValues.result_column().flatMap { resolve(it, availableValues) }
  }

  /**
   * Takes a value rule and returns the columns introduced. Validates that any
   * appended values have the same length.
   */
  fun resolve(values: SqliteParser.ValuesContext, availableValues: List<Value> = emptyList()): List<Value> {
    val selected = values.expr().map { resolve(it, availableValues) }
    if (values.values() != null) {
      val joinedValues = resolve(values.values())
      if (joinedValues.size != selected.size) {
        throw SqlitePluginException(values.values(), "Unexpected number of columns in values " +
            "found: ${joinedValues.size} expected: ${selected.size}")
      }
      // TODO: Type check
    }
    return selected
  }

  /**
   * Take in a list of available columns and return a list of selected columns.
   */
  fun resolve(
      resultColumn: SqliteParser.Result_columnContext,
      availableValues: List<Value>
  ): List<Value> {
    // Like joins, the columns available after the select statement may change (due to aliasing)
    // so validation must happen BEFORE aliasing has occurred.
    ResultColumnValidator(this, availableValues).validate(resultColumn)

    if (resultColumn.text.equals("*")) {
      return availableValues
    }
    if (resultColumn.table_name() != null) {
      return availableValues.filter { it.tableName == resultColumn.table_name().text }
    }
    if (resultColumn.expr() != null) {
      var value = resolve(resultColumn.expr(), availableValues)
      if (resultColumn.column_alias() != null) {
        value = Value(value.tableName, resultColumn.column_alias().text, value.type, value.element)
      }
      return listOf(value)
    }
    throw SqlitePluginException(resultColumn, "Resolver did not know how to handle result column")
  }

  /**
   * Takes a list of available values and returns a selected value.
   */
  fun resolve(expression: SqliteParser.ExprContext, availableValues: List<Value>): Value {
    if (expression.column_name() != null) {
      // | ( ( database_name '.' )? table_name '.' )? column_name
      val matchingColumns = availableValues.columns(expression.column_name().text,
          expression.table_name()?.text)
      if (matchingColumns.isEmpty()) {
        throw SqlitePluginException(expression,
            "No column found with name ${expression.column_name().text}")
      }
      if (matchingColumns.size > 1) {
        throw SqlitePluginException(expression,
            "Ambiguous column name ${expression.column_name().text}, " +
                "founds in tables ${matchingColumns.map { it.tableName }}")
      }
      return matchingColumns[0]
    }

    // TODO get the actual type of the expression. Thats gonna be fun. :(
    return Value(null, null, Value.SqliteType.INTEGER, expression)
  }

  /**
   * Take a join rule and return a list of the available columns.
   * Join rules look like
   *   FROM table_a JOIN table_b ON table_a.column_a = table_b.column_a
   */
  fun resolve(joinClause: SqliteParser.Join_clauseContext): List<Value> {
    // Joins are complex because they are in a partial resolution state: They know about
    // values up to the point of this join but not afterward. Because of this, a validation step
    // for joins must happen as part of the resolution step.

    // Grab the values from the initial table or subquery (table_a in javadoc)
    var values = resolve(joinClause.table_or_subquery(0))

    joinClause.table_or_subquery().drop(1).zip(joinClause.join_constraint(), { table, constraint ->
      val resolver = Resolver(symbolTable, dependencies, scopedValues + values)
      val localValues = resolver.resolve(table)
      JoinValidator(resolver, localValues, values + scopedValues).validate(constraint)
      values += localValues
    })
    return values
  }

  /**
   * Take a table or subquery rule and return a list of the selected values.
   */
  fun resolve(tableOrSubquery: SqliteParser.Table_or_subqueryContext): List<Value> {
    var originalColumns: List<Value>
    if (tableOrSubquery.table_name() != null) {
      originalColumns = resolve(tableOrSubquery.table_name())
    } else if (tableOrSubquery.select_stmt() != null) {
      originalColumns = resolve(tableOrSubquery.select_stmt())
    } else if (tableOrSubquery.table_or_subquery().size > 0) {
      originalColumns = tableOrSubquery.table_or_subquery().flatMap { resolve(it) }
    } else if (tableOrSubquery.join_clause() != null) {
      originalColumns = resolve(tableOrSubquery.join_clause())
    } else {
      throw SqlitePluginException(tableOrSubquery,
          "Resolver did not know how to handle table or subquery")
    }

    // Alias the values if an alias was given.
    if (tableOrSubquery.table_alias() != null) {
      originalColumns = originalColumns.map {
        Value(tableOrSubquery.table_alias().text, it.columnName, it.type, it.element)
      }
    }

    return originalColumns
  }

  fun resolve(createTable: SqliteParser.Create_table_stmtContext) =
      createTable.column_def().map { Value(createTable.table_name().text, it) }

  fun resolve(parserRuleContext: ParserRuleContext): List<Value> {
    when (parserRuleContext) {
      is SqliteParser.Table_or_subqueryContext -> return resolve(parserRuleContext)
      is SqliteParser.Join_clauseContext -> return resolve(parserRuleContext)
      is SqliteParser.Select_stmtContext -> return resolve(parserRuleContext)
      is SqliteParser.Select_or_valuesContext -> return resolve(parserRuleContext)
    }
    val tableName = parserRuleContext
    val createTable = symbolTable.tables[tableName.text]
    if (createTable != null) {
      dependencies.add(symbolTable.tableTags.getForValue(tableName.text))
      if (createTable.select_stmt() != null) {
        return resolve(createTable.select_stmt())
      }
      return resolve(createTable)
    }

    val view = symbolTable.views[tableName.text]
    if (view != null) {
      dependencies.add(symbolTable.viewTags.getForValue(tableName.text))
      if (!currentlyResolvingViews.add(view.view_name().text)) {
        val chain = currentlyResolvingViews.joinToString(" -> ")
        throw SqlitePluginException(view.view_name(),
            "Recursive subquery found: $chain -> ${view.view_name().text}")
      }
      val result = resolve(view.select_stmt())
      currentlyResolvingViews.remove(view.view_name().text)
      return result
    }

    val commonTable = symbolTable.commonTables[tableName.text]
    if (commonTable != null) {
      var values = resolve(commonTable.select_stmt())
      if (commonTable.column_name().size > 0) {
        values = commonTable.column_name().flatMap {
          val found = values.columns(it.text, null)
          if (found.size == 0) {
            throw SqlitePluginException(it, "No column found in common table with name ${it.text}")
          }
          found
        }
      }
      return values.map { Value(tableName.text, it.columnName, it.type, it.element) }
    }

    val withTable = symbolTable.withClauses[tableName.text]
    if (withTable != null) {
      return resolve(withTable.second)
    }

    // If table was missing we add a dependency on all future files.
    dependencies.add(SqlDelightValidator.ALL_FILE_DEPENDENCY)

    throw SqlitePluginException(tableName,
        "Cannot find table or view ${tableName.text}")
  }

  fun foreignKeys(foreignTable: SqliteParser.Foreign_tableContext): ForeignKey {
    return ForeignKey.findForeignKeys(foreignTable, symbolTable, resolve(foreignTable))
  }
}