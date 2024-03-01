package caliban

import caliban.CalibanError.ValidationError
import caliban.Configurator.ExecutionConfiguration
import caliban.execution.{ ExecutionRequest, Executor, Feature }
import caliban.introspection.Introspector
import caliban.introspection.adt._
import caliban.parsing.adt.Definition.TypeSystemDefinition.SchemaDefinition
import caliban.parsing.adt.{ Directive, Document, OperationType }
import caliban.parsing.{ Parser, SourceMapper, VariablesCoercer }
import caliban.rendering.DocumentRenderer
import caliban.schema._
import caliban.validation.Validator
import caliban.wrappers.Wrapper
import caliban.wrappers.Wrapper._
import zio.stacktracer.TracingImplicits.disableAutoTrace
import zio.{ Exit, IO, Trace, URIO, ZIO }

/**
 * A `GraphQL[-R]` represents a GraphQL API whose execution requires a ZIO environment of type `R`.
 *
 * It is intended to be created only once, typically when you start your server.
 * The introspection schema will be generated when this class is instantiated.
 */
trait GraphQL[-R] { self =>

  protected val schemaBuilder: RootSchemaBuilder[R]
  protected val wrappers: List[Wrapper[R]]
  protected val additionalDirectives: List[__Directive]
  protected val features: Set[Feature]

  private[caliban] def validateRootSchema(implicit trace: Trace): IO[ValidationError, RootSchema[R]] =
    ZIO.fromEither(Validator.validateSchemaEither(schemaBuilder))

  /**
   * Returns a string that renders the API types into the GraphQL SDL.
   */
  final def render: String = DocumentRenderer.render(toDocument)

  /**
   * Converts the schema to a Document.
   */
  final def toDocument: Document = cachedDocument

  private lazy val cachedDocument: Document =
    Document(
      SchemaDefinition(
        schemaBuilder.schemaDirectives,
        schemaBuilder.query.flatMap(_.opType.name),
        schemaBuilder.mutation.flatMap(_.opType.name),
        schemaBuilder.subscription.flatMap(_.opType.name),
        schemaBuilder.schemaDescription
      ) :: schemaBuilder.types.flatMap(_.toTypeDefinition) ++ additionalDirectives.map(_.toDirectiveDefinition),
      SourceMapper.empty
    )

  /**
   * Creates an interpreter from your API. A GraphQLInterpreter is a wrapper around your API that allows
   * adding some middleware around the query execution.
   * Fails with a [[caliban.CalibanError.ValidationError]] if the schema is invalid.
   *
   * @see [[interpreterEither]] for a variant that returns an Either instead, making it easier to embed in non-ZIO applications
   */
  final def interpreter(implicit trace: Trace): IO[ValidationError, GraphQLInterpreter[R, CalibanError]] =
    ZIO.fromEither(interpreterEither)

  /**
   * Creates an interpreter from your API. A GraphQLInterpreter is a wrapper around your API that allows
   * adding some middleware around the query execution.
   * Returns a `Left` containing the schema validation error if the schema is invalid.
   *
   * @see [[interpreter]] for a ZIO variant of this method that makes it easier to embed in ZIO applications
   */
  final lazy val interpreterEither: Either[ValidationError, GraphQLInterpreter[R, CalibanError]] =
    Validator.validateSchemaEither(schemaBuilder).map { schema =>
      new GraphQLInterpreter[R, CalibanError] {
        private val rootType =
          RootType(
            schema.query.opType,
            schema.mutation.map(_.opType),
            schema.subscription.map(_.opType),
            schemaBuilder.additionalTypes,
            additionalDirectives,
            schemaBuilder.schemaDescription
          )

        private val introWrappers                               = wrappers.collect { case w: IntrospectionWrapper[R] => w }
        private lazy val introspectionRootSchema: RootSchema[R] = Introspector.introspect(rootType, introWrappers)

        override def check(query: String)(implicit trace: Trace): IO[CalibanError, Unit] =
          for {
            document      <- Parser.parseQuery(query)
            intro          = Introspector.isIntrospection(document)
            typeToValidate = if (intro) Introspector.introspectionRootType else rootType
            _             <- Validator.validate(document, typeToValidate)
          } yield ()

        override def executeRequest(request: GraphQLRequest)(implicit
          trace: Trace
        ): URIO[R, GraphQLResponse[CalibanError]] =
          decompose(wrappers).flatMap {
            case (overallWrappers, parsingWrappers, validationWrappers, executionWrappers, fieldWrappers, _) =>
              wrap((request: GraphQLRequest) =>
                (for {
                  doc          <- wrap(Parser.parseQuery)(parsingWrappers, request.query.getOrElse(""))
                  coercedVars  <- coerceVariables(doc, request.variables.getOrElse(Map.empty))
                  executionReq <- wrap(validation(request.operationName, coercedVars))(validationWrappers, doc)
                  result       <- wrap(execution(schemaToExecute(doc), fieldWrappers))(executionWrappers, executionReq)
                } yield result).catchAll(Executor.fail(_))
              )(overallWrappers, request)
          }

        private def coerceVariables(doc: Document, variables: Map[String, InputValue])(implicit
          trace: Trace
        ): IO[ValidationError, Map[String, InputValue]] =
          Configurator.configuration.flatMap { config =>
            if (doc.isIntrospection && !config.enableIntrospection)
              ZIO.fail(CalibanError.ValidationError("Introspection is disabled", ""))
            else
              VariablesCoercer
                .coerceVariablesEither(variables, doc, typeToValidate(doc), config.skipValidation)
                .fold(ZIO.fail(_), ZIO.succeed(_))
          }

        private def validation(
          operationName: Option[String],
          coercedVars: Map[String, InputValue]
        )(doc: Document)(implicit trace: Trace): IO[ValidationError, ExecutionRequest] =
          Configurator.configuration.flatMap { config =>
            Validator
              .prepareEither(
                doc,
                typeToValidate(doc),
                schemaToExecute(doc),
                operationName,
                coercedVars,
                config.skipValidation,
                config.validations
              )
              .fold(ZIO.fail(_), checkHttpMethod(config))
          }

        private def execution(
          schemaToExecute: RootSchema[R],
          fieldWrappers: List[FieldWrapper[R]]
        )(request: ExecutionRequest)(implicit trace: Trace) = {
          val op = request.operationType match {
            case OperationType.Query        => schemaToExecute.query
            case OperationType.Mutation     => schemaToExecute.mutation.getOrElse(schemaToExecute.query)
            case OperationType.Subscription => schemaToExecute.subscription.getOrElse(schemaToExecute.query)
          }
          Configurator.configuration.flatMap { config =>
            Executor.executeRequest(request, op.plan, fieldWrappers, config.queryExecution, features)
          }
        }

        private def typeToValidate(doc: Document) =
          if (doc.isIntrospection) Introspector.introspectionRootType else rootType

        private def schemaToExecute(doc: Document) =
          if (doc.isIntrospection) introspectionRootSchema else schema

        private def checkHttpMethod(cfg: ExecutionConfiguration)(req: ExecutionRequest)(implicit
          trace: Trace
        ): IO[ValidationError, ExecutionRequest] =
          if ((req.operationType eq OperationType.Mutation) && !cfg.allowMutationsOverGetRequests)
            HttpRequestMethod.get.flatMap {
              case HttpRequestMethod.GET => ZIO.fail(HttpRequestMethod.MutationOverGetError)
              case _                     => ZIO.succeed(req)
            }
          else ZIO.succeed(req)

      }
    }

  /**
   * Attaches a function that will wrap one of the stages of query processing
   * (parsing, validation, execution, field execution or overall).
   * @param wrapper a wrapping function
   * @return a new GraphQL API
   */
  final def withWrapper[R2 <: R](wrapper: Wrapper[R2]): GraphQL[R2] =
    new GraphQL[R2] {
      override val schemaBuilder: RootSchemaBuilder[R2]    = self.schemaBuilder
      override val wrappers: List[Wrapper[R2]]             = wrapper :: self.wrappers
      override val additionalDirectives: List[__Directive] = self.additionalDirectives
      override val features: Set[Feature]                  = self.features
    }

  /**
   * Attaches an aspect that will wrap the entire GraphQL so that it can be manipulated.
   * This method is a higher-level abstraction of [[withWrapper]] which allows the caller to
   * completely replace or change all aspects of the schema.
   * @param aspect A wrapper type that will be applied to this GraphQL
   * @return A new GraphQL API
   */
  final def @@[LowerR <: UpperR, UpperR <: R](aspect: GraphQLAspect[LowerR, UpperR]): GraphQL[UpperR] =
    aspect(self)

  /**
   * Merges this GraphQL API with another GraphQL API.
   * In case of conflicts (same field declared on both APIs), fields from `that` API will be used.
   * @param that another GraphQL API object
   * @return a new GraphQL API
   */
  final def combine[R1 <: R](that: GraphQL[R1]): GraphQL[R1] =
    new GraphQL[R1] {
      override val schemaBuilder: RootSchemaBuilder[R1]              = self.schemaBuilder |+| that.schemaBuilder
      override protected val wrappers: List[Wrapper[R1]]             = self.wrappers ++ that.wrappers
      override protected val additionalDirectives: List[__Directive] =
        self.additionalDirectives ++ that.additionalDirectives
      override protected val features: Set[Feature]                  = self.features ++ that.features
    }

  /**
   * Operator alias for `combine`.
   */
  final def |+|[R1 <: R](that: GraphQL[R1]): GraphQL[R1] = combine(that)

  /**
   * Renames the root queries, mutations and subscriptions objects.
   * @param queriesName a new name for the root queries object
   * @param mutationsName a new name for the root mutations object
   * @param subscriptionsName a new name for the root subscriptions object
   * @return a new GraphQL API
   */
  final def rename(
    queriesName: Option[String] = None,
    mutationsName: Option[String] = None,
    subscriptionsName: Option[String] = None
  ): GraphQL[R] = new GraphQL[R] {
    override protected val schemaBuilder: RootSchemaBuilder[R]     = self.schemaBuilder.copy(
      query = queriesName.fold(self.schemaBuilder.query)(name =>
        self.schemaBuilder.query.map(m => m.copy(opType = m.opType.copy(name = Some(name))))
      ),
      mutation = mutationsName.fold(self.schemaBuilder.mutation)(name =>
        self.schemaBuilder.mutation.map(m => m.copy(opType = m.opType.copy(name = Some(name))))
      ),
      subscription = subscriptionsName.fold(self.schemaBuilder.subscription)(name =>
        self.schemaBuilder.subscription.map(m => m.copy(opType = m.opType.copy(name = Some(name))))
      )
    )
    override protected val wrappers: List[Wrapper[R]]              = self.wrappers
    override protected val additionalDirectives: List[__Directive] = self.additionalDirectives
    override protected val features: Set[Feature]                  = self.features
  }

  /**
   * Adds linking to additional types which are unreachable from the root query.
   *
   * @note This is for advanced usage only i.e. when declaring federation type links
   * @param types The type definitions to add.
   */
  final def withAdditionalTypes(types: List[__Type]): GraphQL[R] = new GraphQL[R] {
    override protected val schemaBuilder: RootSchemaBuilder[R]     =
      self.schemaBuilder.copy(additionalTypes = self.schemaBuilder.additionalTypes ++ types)
    override protected val wrappers: List[Wrapper[R]]              = self.wrappers
    override protected val additionalDirectives: List[__Directive] = self.additionalDirectives
    override protected val features: Set[Feature]                  = self.features
  }

  final def withSchemaDirectives(directives: List[Directive]): GraphQL[R] = new GraphQL[R] {
    override protected val schemaBuilder: RootSchemaBuilder[R]     =
      self.schemaBuilder.copy(schemaDirectives = self.schemaBuilder.schemaDirectives ++ directives)
    override protected val wrappers: List[Wrapper[R]]              = self.wrappers
    override protected val additionalDirectives: List[__Directive] = self.additionalDirectives
    override protected val features: Set[Feature]                  = self.features
  }

  final def withAdditionalDirectives(directives: List[__Directive]): GraphQL[R] = new GraphQL[R] {
    override protected val schemaBuilder: RootSchemaBuilder[R]     = self.schemaBuilder
    override protected val wrappers: List[Wrapper[R]]              = self.wrappers
    override protected val additionalDirectives: List[__Directive] = self.additionalDirectives ++ directives
    override protected val features: Set[Feature]                  = self.features
  }

  final def enable(feature: Feature): GraphQL[R] = new GraphQL[R] {
    override protected val schemaBuilder: RootSchemaBuilder[R]     = self.schemaBuilder
    override protected val wrappers: List[Wrapper[R]]              = self.wrappers
    override protected val additionalDirectives: List[__Directive] = self.additionalDirectives
    override protected val features: Set[Feature]                  = self.features + feature
  }
}
