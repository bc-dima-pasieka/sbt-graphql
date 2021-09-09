package rocks.muki.graphql

import sangria.parser.QueryParser
import sangria.schema.Schema
import sangria.validation.{QueryValidator, Violation}
import sbt._
import sbt.Keys._

object GraphQLQueryPlugin extends AutoPlugin {

  override def requires: Plugins = GraphQLSchemaPlugin

  object autoImport {

    /**
      * Validate all queries
      */
    val graphqlValidateQueries: TaskKey[Unit] =
      taskKey[Unit]("validate all queries in the graphql source directory")

  }

  import autoImport._
  import GraphQLSchemaPlugin.autoImport._

  override def projectSettings: Seq[Setting[_]] = Seq(
    Test / graphqlValidateQueries / sourceDirectory := (Test / sourceDirectory).value / "graphql",
    graphqlValidateQueries := {
      val log = streams.value.log
      val schemaFile = IO.read(graphqlSchemaGen.value)
      val schemaDocument = QueryParser
        .parse(schemaFile)
        .getOrElse(
          sys.error("Invalid graphql schema generated by `graphqlSchemaGen` task")
        )
      val schema = Schema.buildFromAst(schemaDocument)

      val graphqlQueryDirectory =
        (Test / graphqlValidateQueries / sourceDirectory).value
      log.info(s"Checking graphql files in $graphqlQueryDirectory")
      val graphqlFiles = (graphqlQueryDirectory ** "*.graphql").get
      val violations = graphqlFiles.flatMap {
        file =>
          log.info(s"Validate ${file.getPath}")
          val query = IO.read(file)
          val violations = QueryParser
            .parse(query)
            .fold(
              error => Vector(InvalidQueryValidation(error)),
              query => QueryValidator.default.validateQuery(schema, query)
            )
          if (violations.nonEmpty) {
            log.error(s"File: ${file.getAbsolutePath}")
            log.error("## Query ##")
            log.error(query)
            log.error("## Violations ##")
            violations.foreach(v => log.error(v.errorMessage))
            List(QueryViolations(file, query, violations))
          } else {
            Nil
          }
      }

      if (violations.nonEmpty) {
        log.error("Validation errors in")
        violations.foreach { queryViolation =>
          log.error(s"File: ${queryViolation.file.getAbsolutePath}")
        }
        quietError("Some queries contain validation violations")
      }
      log.success(s"All ${graphqlFiles.size} graphql files are valid")
    }
  )

  /**
    * Aggregates violations for a single file
    * @param file the file that was validated
    * @param query the file content
    * @param violations the violations found
    */
  private case class QueryViolations(file: File, query: String, violations: Seq[Violation])

  /**
    * Violation when parsing failed
    * @param throwable the error
    */
  private case class InvalidQueryValidation(throwable: Throwable) extends Violation {
    override def errorMessage: String = s"""|Parsing file failed
                                            |Exception: [${throwable.getClass}]
                                            |${throwable.getMessage}""".stripMargin
  }

}
