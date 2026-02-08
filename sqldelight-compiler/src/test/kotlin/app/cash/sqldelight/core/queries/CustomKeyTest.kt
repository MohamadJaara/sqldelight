package app.cash.sqldelight.core.queries

import app.cash.sqldelight.test.util.FixtureCompiler
import app.cash.sqldelight.test.util.fixtureRoot
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CustomKeyTest {
  @get:Rule val tempFolder = TemporaryFolder()

  @Test fun `query with literal custom key generates correct code`() {
    // Write the SQL to an actual file to preserve comments
    FixtureCompiler.writeSql(
      """
      |CREATE TABLE user (
      |  id TEXT NOT NULL PRIMARY KEY,
      |  name TEXT NOT NULL
      |);
      |
      |selectAllUsers:
      |-- @CustomKey all_users
      |SELECT * FROM user;
      """.trimMargin(),
      tempFolder,
      fileName = "User.sq",
    )

    val result = FixtureCompiler.compileFixture(
      fixtureRoot = tempFolder.fixtureRoot().path,
      enableCustomQueryKeys = true,
    )

    assertThat(result.errors).isEmpty()
    val queriesFile = File(result.outputDirectory, "com/example/UserQueries.kt")
    assertThat(result.compilerOutput).containsKey(queriesFile)
    assertThat(result.compilerOutput[queriesFile].toString()).contains(
      """arrayOf("all_users")""",
    )
  }

  @Test fun `query with template custom key generates interpolated code`() {
    val result = FixtureCompiler.compileSql(
      """
      |CREATE TABLE message (
      |  id TEXT NOT NULL PRIMARY KEY,
      |  conversation_id TEXT NOT NULL,
      |  content TEXT NOT NULL
      |);
      |
      |selectConversation:
      |-- @CustomKey conversation_:conversation_id
      |SELECT * FROM message WHERE conversation_id = :conversation_id;
      """.trimMargin(),
      tempFolder,
      fileName = "Message.sq",
      enableCustomQueryKeys = true,
    )

    assertThat(result.errors).isEmpty()
    val queriesFile = File(result.outputDirectory, "com/example/MessageQueries.kt")
    assertThat(result.compilerOutput).containsKey(queriesFile)
    val queries = result.compilerOutput[queriesFile].toString()
    assertThat(queries).contains("""driver.addListener("conversation_${'$'}conversation_id", listener = listener)""")
  }

  @Test fun `query with multiple custom keys generates array with multiple keys`() {
    val result = FixtureCompiler.compileSql(
      """
      |CREATE TABLE message (
      |  id TEXT NOT NULL PRIMARY KEY,
      |  conversation_id TEXT NOT NULL,
      |  user_id TEXT NOT NULL,
      |  content TEXT NOT NULL
      |);
      |
      |selectUserInConversation:
      |-- @CustomKey conversation_:conversation_id
      |-- @CustomKey user_:user_id
      |SELECT * FROM message
      |WHERE conversation_id = :conversation_id AND user_id = :user_id;
      """.trimMargin(),
      tempFolder,
      fileName = "Message.sq",
      enableCustomQueryKeys = true,
    )

    assertThat(result.errors).isEmpty()
    val queriesFile = File(result.outputDirectory, "com/example/MessageQueries.kt")
    assertThat(result.compilerOutput).containsKey(queriesFile)
    val queries = result.compilerOutput[queriesFile].toString()
    assertThat(queries).contains("""driver.addListener("conversation_${'$'}conversation_id", "user_${'$'}user_id", listener = listener)""")
  }

  @Test fun `query without custom key uses table-based keys`() {
    val result = FixtureCompiler.compileSql(
      """
      |CREATE TABLE message (
      |  id TEXT NOT NULL PRIMARY KEY,
      |  content TEXT NOT NULL
      |);
      |
      |selectAll:
      |SELECT * FROM message;
      """.trimMargin(),
      tempFolder,
      fileName = "Message.sq",
      enableCustomQueryKeys = true,
    )

    assertThat(result.errors).isEmpty()
    val queriesFile = File(result.outputDirectory, "com/example/MessageQueries.kt")
    assertThat(result.compilerOutput).containsKey(queriesFile)
    val queries = result.compilerOutput[queriesFile].toString()
    // Should use table name as key
    assertThat(queries).contains("""arrayOf("message")""")
  }

  @Test fun `mutation with custom notify key generates notifyListeners call`() {
    val result = FixtureCompiler.compileSql(
      """
      |CREATE TABLE message (
      |  id TEXT NOT NULL PRIMARY KEY,
      |  conversation_id TEXT NOT NULL,
      |  content TEXT NOT NULL
      |);
      |
      |insertMessage:
      |-- @NotifyCustomKey conversation_:conversation_id
      |INSERT INTO message (id, content, conversation_id)
      |VALUES (?, ?, ?);
      """.trimMargin(),
      tempFolder,
      fileName = "Message.sq",
      enableCustomQueryKeys = true,
    )

    assertThat(result.errors).isEmpty()
    val queriesFile = File(result.outputDirectory, "com/example/MessageQueries.kt")
    assertThat(result.compilerOutput).containsKey(queriesFile)
    val queries = result.compilerOutput[queriesFile].toString()
    // Should notify custom key
    assertThat(queries).contains("""driver.notifyListeners("conversation_${'$'}conversation_id")""")
    // Should NOT notify table-based listeners when custom keys are used
    assertThat(queries).doesNotContain("notifyQueries")
  }

  @Test fun `mutation with multiple notify keys generates multiple notifyListeners calls`() {
    val result = FixtureCompiler.compileSql(
      """
      |CREATE TABLE message (
      |  id TEXT NOT NULL PRIMARY KEY,
      |  conversation_id TEXT NOT NULL,
      |  content TEXT NOT NULL
      |);
      |
      |updateMessage:
      |-- @NotifyCustomKey conversation_:conversation_id
      |-- @NotifyCustomKey message_:id
      |UPDATE message SET content = :content
      |WHERE id = :id AND conversation_id = :conversation_id;
      """.trimMargin(),
      tempFolder,
      fileName = "Message.sq",
      enableCustomQueryKeys = true,
    )

    assertThat(result.errors).isEmpty()
    val queriesFile = File(result.outputDirectory, "com/example/MessageQueries.kt")
    assertThat(result.compilerOutput).containsKey(queriesFile)
    val queries = result.compilerOutput[queriesFile].toString()
    // Should notify both custom keys
    assertThat(queries).contains("""driver.notifyListeners("conversation_${'$'}conversation_id")""")
    assertThat(queries).contains("""driver.notifyListeners("message_${'$'}id")""")
    // Should NOT notify table-based listeners when custom keys are used
    assertThat(queries).doesNotContain("notifyQueries")
  }

  @Test fun `custom key with invalid parameter reference fails`() {
    try {
      FixtureCompiler.compileSql(
        """
        |CREATE TABLE message (
        |  id TEXT NOT NULL PRIMARY KEY,
        |  conversation_id TEXT NOT NULL,
        |  content TEXT NOT NULL
        |);
        |
        |selectConversation:
        |-- @CustomKey conversation_:invalid_param
        |SELECT * FROM message WHERE conversation_id = :conversation_id;
        """.trimMargin(),
        tempFolder,
        fileName = "Message.sq",
        enableCustomQueryKeys = true,
      )
      throw AssertionError("Expected compilation to fail with invalid parameter reference")
    } catch (e: IllegalStateException) {
      // Should have an error about unknown parameter - check the cause
      val errorMessage = e.cause?.message ?: e.message ?: ""
      assertThat(errorMessage).contains("invalid_param")
      assertThat(errorMessage).contains("selectConversation")
    }
  }

  @Test fun `custom key preserves parameter interpolation in addListener and removeListener`() {
    val result = FixtureCompiler.compileSql(
      """
      |CREATE TABLE message (
      |  id TEXT NOT NULL PRIMARY KEY,
      |  conversation_id TEXT NOT NULL,
      |  content TEXT NOT NULL
      |);
      |
      |selectConversation:
      |-- @CustomKey conversation_:conversation_id
      |SELECT * FROM message WHERE conversation_id = :conversation_id;
      """.trimMargin(),
      tempFolder,
      fileName = "Message.sq",
      enableCustomQueryKeys = true,
    )

    assertThat(result.errors).isEmpty()
    val queriesFile = File(result.outputDirectory, "com/example/MessageQueries.kt")
    assertThat(result.compilerOutput).containsKey(queriesFile)
    val queries = result.compilerOutput[queriesFile].toString()

    // Check that the inner query class has proper listener methods
    assertThat(queries).contains("""addListener""")
    assertThat(queries).contains("""removeListener""")
    assertThat(queries).contains("""driver.addListener("conversation_${'$'}conversation_id"""")
    assertThat(queries).contains("""driver.removeListener("conversation_${'$'}conversation_id"""")
  }

  @Test fun `mutation without custom key uses table-based notification`() {
    val result = FixtureCompiler.compileSql(
      """
      |CREATE TABLE message (
      |  id TEXT NOT NULL PRIMARY KEY,
      |  content TEXT NOT NULL
      |);
      |
      |insertMessage:
      |INSERT INTO message (id, content) VALUES (?, ?);
      """.trimMargin(),
      tempFolder,
      fileName = "Message.sq",
      enableCustomQueryKeys = true,
    )

    assertThat(result.errors).isEmpty()
    val queriesFile = File(result.outputDirectory, "com/example/MessageQueries.kt")
    assertThat(result.compilerOutput).containsKey(queriesFile)
    val queries = result.compilerOutput[queriesFile].toString()
    // Should use notifyQueries with table name
    assertThat(queries).contains("notifyQueries")
  }

  @Test fun `custom key annotations are ignored when feature flag is disabled`() {
    val result = FixtureCompiler.compileSql(
      """
      |CREATE TABLE message (
      |  id TEXT NOT NULL PRIMARY KEY,
      |  conversation_id TEXT NOT NULL,
      |  content TEXT NOT NULL
      |);
      |
      |selectConversation:
      |-- @CustomKey conversation_:conversation_id
      |SELECT * FROM message WHERE conversation_id = :conversation_id;
      |
      |insertMessage:
      |-- @NotifyCustomKey conversation_:conversation_id
      |INSERT INTO message (id, content, conversation_id)
      |VALUES (?, ?, ?);
      """.trimMargin(),
      tempFolder,
      fileName = "Message.sq",
      enableCustomQueryKeys = false,
    )

    assertThat(result.errors).isEmpty()
    val queriesFile = File(result.outputDirectory, "com/example/MessageQueries.kt")
    assertThat(result.compilerOutput).containsKey(queriesFile)
    val queries = result.compilerOutput[queriesFile].toString()

    // Should use table-based keys in addListener/removeListener
    assertThat(queries).contains("""driver.addListener("message", listener = listener)""")
    assertThat(queries).contains("""driver.removeListener("message", listener = listener)""")

    // Should NOT contain custom key listener code with conversation prefix
    assertThat(queries).doesNotContain("""driver.addListener("conversation_""")
    assertThat(queries).doesNotContain("""driver.removeListener("conversation_""")

    // Should NOT contain custom key notification code
    assertThat(queries).doesNotContain("""driver.notifyListeners("conversation_""")

    // Should use standard table-based notification
    assertThat(queries).contains("notifyQueries")
    assertThat(queries).contains("""emit("message")""")
  }
}
