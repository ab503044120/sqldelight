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
package com.squareup.sqldelight

import com.squareup.javapoet.ClassName
import com.squareup.javapoet.FieldSpec
import com.squareup.javapoet.JavaFile
import com.squareup.javapoet.MethodSpec
import com.squareup.javapoet.NameAllocator
import com.squareup.javapoet.TypeSpec
import com.squareup.sqldelight.model.Table
import com.squareup.sqldelight.model.body
import com.squareup.sqldelight.model.constantName
import com.squareup.sqldelight.model.identifier
import com.squareup.sqldelight.model.isNullable
import com.squareup.sqldelight.model.javaType
import com.squareup.sqldelight.model.methodName
import com.squareup.sqldelight.model.pathAsType
import com.squareup.sqldelight.model.pathFileName
import com.squareup.sqldelight.model.pathPackage
import com.squareup.sqldelight.model.sqliteName
import com.squareup.sqldelight.model.sqliteText
import com.squareup.sqldelight.validation.QueryResults
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.PrintStream
import java.util.Locale.US
import javax.lang.model.element.Modifier.ABSTRACT
import javax.lang.model.element.Modifier.FINAL
import javax.lang.model.element.Modifier.PUBLIC
import javax.lang.model.element.Modifier.STATIC

class SqliteCompiler {
  private val nameAllocator = NameAllocator()

  private fun write(
      parseContext: SqliteParser.ParseContext,
      queryResultsList: List<QueryResults>,
      relativePath: String,
      projectPath: String
  ): Status {
    try {
      val className = interfaceName(relativePath.pathFileName())
      val typeSpec = TypeSpec.interfaceBuilder(className)
          .addModifiers(PUBLIC)

      queryResultsList.filter { it.requiresType }.forEach { queryResults ->
        typeSpec.addType(queryResults.generateInterface())
        typeSpec.addType(queryResults.generateCreator())
        typeSpec.addType(MapperSpec.builder(nameAllocator, queryResults).build())
      }

      var table: Table? = null
      if (parseContext.sql_stmt_list().create_table_stmt() != null) {
        table = Table(relativePath.pathAsType(),
            parseContext.sql_stmt_list().create_table_stmt(), nameAllocator)

        typeSpec.addField(FieldSpec.builder(String::class.java, TABLE_NAME)
            .addModifiers(PUBLIC, STATIC, FINAL)
            .initializer("\$S", table.name)
            .build())
            .addType(table.creatorInterface())
        .addType(MapperSpec.builder(table).build())

        for (column in table.column_def()) {
          if (column.constantName(nameAllocator) == TABLE_NAME
              || column.constantName(nameAllocator) == CREATE_TABLE) {
            throw SqlitePluginException(column.column_name(),
                "Column name '${column.sqliteName}' forbidden")
          }

          typeSpec.addField(FieldSpec.builder(String::class.java, column.constantName(nameAllocator))
              .addModifiers(PUBLIC, STATIC, FINAL)
              .initializer("\$S", column.sqliteName)
              .build())

          val methodSpec = MethodSpec.methodBuilder(column.methodName(nameAllocator))
              .returns(column.javaType)
              .addModifiers(PUBLIC, ABSTRACT)
          if (!column.javaType.isPrimitive) {
            methodSpec.addAnnotation(if (column.isNullable) NULLABLE else NON_NULL)
          }
          typeSpec.addMethod(methodSpec.build())
        }

        typeSpec.addField(FieldSpec.builder(String::class.java, CREATE_TABLE)
            .addModifiers(PUBLIC, STATIC, FINAL)
            .initializer("\"\"\n    + \$S", table.sqliteText()) // Start SQL on wrapped line.
            .build())

        typeSpec.addType(MarshalSpec.builder(table).build())
      }
      typeSpec.addType(
          FactorySpec.builder(table, queryResultsList, relativePath.pathAsType(), nameAllocator)
              .build())

      parseContext.sql_stmt_list().sql_stmt().forEach {
        if (it.identifier == CREATE_TABLE) {
          throw SqlitePluginException(it.sql_stmt_name(), "'CREATE_TABLE' identifier is reserved")
        }
        typeSpec.addField(FieldSpec.builder(String::class.java, it.identifier)
            .addModifiers(PUBLIC, STATIC, FINAL)
            .initializer("\"\"\n    + \$S", it.body().sqliteText()) // Start SQL on wrapped line.
            .build())
      }

      val javaFile = JavaFile.builder(relativePath.pathPackage(), typeSpec.build()).build()

      val buildDirectory = File(File(projectPath, "build"), SqliteCompiler.OUTPUT_DIRECTORY)
      val packageDirectory = File(buildDirectory, relativePath.substringBeforeLast(File.separatorChar))
      packageDirectory.mkdirs()
      val outputFile = File(packageDirectory, className + ".java")
      outputFile.createNewFile()
      javaFile.writeTo(PrintStream(FileOutputStream(outputFile)))

      return Status.Success(parseContext, outputFile)
    } catch (e: SqlitePluginException) {
      return Status.Failure(e.originatingElement, e.message)
    } catch (e: IOException) {
      return Status.Failure(parseContext, e.message ?: "IOException occurred")
    }
  }

  companion object {
    const val TABLE_NAME = "TABLE_NAME"
    const val CREATE_TABLE = "CREATE_TABLE"
    const val FILE_EXTENSION = "sq"
    val OUTPUT_DIRECTORY = "generated${File.separatorChar}source${File.separatorChar}sqldelight"
    val NULLABLE = ClassName.get("android.support.annotation", "Nullable")
    val NON_NULL = ClassName.get("android.support.annotation", "NonNull")
    val COLUMN_ADAPTER_TYPE = ClassName.get("com.squareup.sqldelight", "ColumnAdapter")

    fun interfaceName(sqliteFileName: String) = sqliteFileName + "Model"
    fun constantName(name: String) = name.toUpperCase(US)
    fun write(
        parseContext: SqliteParser.ParseContext,
        queryResultsList: List<QueryResults>,
        relativePath: String,
        projectPath: String
    ) = SqliteCompiler().write(parseContext, queryResultsList, relativePath, projectPath)
  }
}
