/*
 * Copyright (C) 2025 Square, Inc.
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
package app.cash.sqldelight.core.compiler.model

/**
 * Represents a custom key expression that can contain literal strings
 * and parameter references.
 *
 * Custom keys are specified via comments like:
 * ```sql
 * selectConversation:
 * -- @CustomKey conversation_:conversation_id
 * SELECT * FROM Message WHERE conversation_id = :conversation_id;
 * ```
 */
sealed class CustomKeyExpression {
  /**
   * A literal custom key with no parameter references.
   * Example: "all_users"
   */
  data class Literal(val value: String) : CustomKeyExpression()

  /**
   * A template string that interpolates parameter values.
   * Example: "conversation_:conversation_id" becomes "conversation_$conversationId"
   */
  data class Template(val parts: List<Part>) : CustomKeyExpression() {
    sealed class Part {
      /**
       * A literal text part of the template.
       */
      data class Text(val value: String) : Part()

      /**
       * A parameter reference that will be interpolated.
       */
      data class Parameter(val name: String) : Part()
    }
  }

  companion object {
    /**
     * Parses a custom key expression string into a CustomKeyExpression.
     *
     * Examples:
     * - "all_users" -> Literal("all_users")
     * - "conversation_:conversation_id" -> Template with text and parameter parts
     * - "user_:user_id_msg_:message_id" -> Template with multiple parameters
     */
    fun parse(expression: String): CustomKeyExpression {
      val trimmed = expression.trim()
      if (!trimmed.contains(':')) {
        return Literal(trimmed)
      }

      val parts = mutableListOf<Template.Part>()
      var currentText = StringBuilder()
      var i = 0

      while (i < trimmed.length) {
        if (trimmed[i] == ':') {
          if (currentText.isNotEmpty()) {
            parts.add(Template.Part.Text(currentText.toString()))
            currentText.clear()
          }

          // Extract parameter name
          i++ // Skip ':'
          val paramStart = i
          while (i < trimmed.length &&
            (trimmed[i].isLetterOrDigit() || trimmed[i] == '_')
          ) {
            i++
          }
          val paramName = trimmed.substring(paramStart, i)
          if (paramName.isNotEmpty()) {
            parts.add(Template.Part.Parameter(paramName))
          }
        } else {
          currentText.append(trimmed[i])
          i++
        }
      }

      if (currentText.isNotEmpty()) {
        parts.add(Template.Part.Text(currentText.toString()))
      }

      return Template(parts)
    }
  }

  /**
   * Returns all parameter names referenced in this expression.
   */
  fun referencedParameters(): List<String> = when (this) {
    is Literal -> emptyList()
    is Template -> parts.filterIsInstance<Template.Part.Parameter>()
      .map { it.name }
  }
}
