package app.cash.sqldelight.core.compiler

import app.cash.sqldelight.core.capitalize
import app.cash.sqldelight.core.compiler.model.BindableQuery
import app.cash.sqldelight.core.compiler.model.NamedExecute
import app.cash.sqldelight.core.lang.ASYNC_RESULT_TYPE
import app.cash.sqldelight.core.lang.QUERY_RESULT_TYPE
import app.cash.sqldelight.core.lang.argumentType
import app.cash.sqldelight.core.lang.util.findChildrenOfType
import app.cash.sqldelight.core.psi.SqlDelightStmtClojureStmtList
import com.alecstrong.sql.psi.core.psi.SqlStmt
import com.squareup.kotlinpoet.ClassName
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.FunSpec
import com.squareup.kotlinpoet.KModifier
import com.squareup.kotlinpoet.KModifier.SUSPEND
import com.squareup.kotlinpoet.LONG
import com.squareup.kotlinpoet.ParameterSpec
import com.squareup.kotlinpoet.ParameterizedTypeName.Companion.parameterizedBy
import com.squareup.kotlinpoet.PropertySpec

open class ExecuteQueryGenerator(
  private val query: NamedExecute,
) : QueryGenerator(query) {

  /**
   * The public api to execute [query]
   */
  fun function(): FunSpec {
    return interfaceFunction()
      .addCode(executeBlock())
      .apply { if (mutatorReturns) addCode("return result") }
      .build()
  }

  fun interfaceFunction(): FunSpec.Builder {
    return FunSpec.builder(query.name)
      .apply { if (generateAsync) addModifiers(SUSPEND) }
      .also(this::addJavadoc)
      .addParameters(
        query.parameters.map {
          ParameterSpec.builder(it.name, it.argumentType()).build()
        },
      )
      .apply {
        val type = if (generateAsync) LONG else QUERY_RESULT_TYPE.parameterizedBy(LONG)
        returns(type, CodeBlock.of("The number of rows updated."))
      }
  }

  fun value(): PropertySpec {
    return PropertySpec.builder(query.name, ClassName("", query.name.capitalize()), KModifier.PRIVATE)
      .initializer("${query.name.capitalize()}()")
      .build()
  }

  /**
   * The public api to execute [query] with custom notification keys
   */
  fun functionWithCustomKeys(): FunSpec {
    return interfaceFunctionWithCustomKeys()
      .addCode(executeBlockWithCustomKeys())
      .apply { if (mutatorReturns) addCode("return result") }
      .build()
  }

  private fun interfaceFunctionWithCustomKeys(): FunSpec.Builder {
    return FunSpec.builder(query.name)
      .apply { if (generateAsync) addModifiers(SUSPEND) }
      .also(this::addJavadoc)
      .addParameters(
        query.parameters.map {
          ParameterSpec.builder(it.name, it.argumentType()).build()
        },
      )
      .addParameter(
        ParameterSpec.builder("customKeys", ClassName("kotlin.collections", "List").parameterizedBy(ClassName("kotlin", "String")))
          .build(),
      )
      .apply {
        val type = if (generateAsync) LONG else QUERY_RESULT_TYPE.parameterizedBy(LONG)
        returns(type, CodeBlock.of("The number of rows updated."))
      }
  }

  /**
   * Creates the execute block with custom keys for notifications
   */
  private fun executeBlockWithCustomKeys(): CodeBlock {
    val result = CodeBlock.builder()

    val notifyBlock = notifyQueriesBlockWithCustomKeys()
    if (query.statement is SqlDelightStmtClojureStmtList) {
      result
        .apply { if (generateAsync) beginControlFlow("return %T", ASYNC_RESULT_TYPE) }
        .beginControlFlow(if (generateAsync) "transactionWithResult" else "return transactionWithResult")
      val handledArrayArgs = mutableSetOf<BindableQuery.Argument>()
      query.statement.findChildrenOfType<SqlStmt>().forEachIndexed { index, statement ->
        val (block, additionalArrayArgs) = executeBlock(statement, handledArrayArgs, query.idForIndex(index))
        handledArrayArgs.addAll(additionalArrayArgs)
        result.add(block)
      }
      if (notifyBlock.isNotEmpty()) {
        result.nextControlFlow(".also")
        result.add(notifyBlock)
        result.endControlFlow()
      } else {
        result.endControlFlow()
        result.add(notifyBlock)
      }
      if (generateAsync) {
        result.endControlFlow()
      }
    } else {
      result.add(executeBlock(query.statement, emptySet(), query.id).first)
      result.add(notifyBlock)
    }

    return result.build()
  }

  private fun notifyQueriesBlockWithCustomKeys(): CodeBlock {
    // Custom notification with provided keys instead of table-based keys
    return CodeBlock.builder()
      .beginControlFlow("notifyQueries(%L) { emit ->", query.id)
      .addStatement("customKeys.forEach { emit(it) }")
      .endControlFlow()
      .build()
  }
}
