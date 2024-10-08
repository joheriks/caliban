package caliban.schema

import caliban.parsing.adt.Directive

import scala.annotation.StaticAnnotation

trait AnnotationsVersionSpecific {

  /**
   * Annotation that can be used on a case class method to mark it as a GraphQL field.
   * The method must be public, a `def` (does not work on `val`s / `lazy val`s) and must not take any arguments.
   *
   * '''NOTE''' This annotation is not safe for use with ahead-of-time compilation (e.g., generating a GraalVM native-image executable)
   */
  case class GQLField() extends StaticAnnotation

  /**
   * Annotation that can be used on a case class / case object to have all the public methods on it derived as fields.
   *
   * If you wish to exclude a public method from being derived as a field, you can annotate it with [[GQLExclude]].
   *
   * @see [[GQLField]] for a more fine-grained control over which methods are derived as fields
   */
  case class GQLFieldsFromMethods() extends StaticAnnotation

  /**
   * Annotation used to provide directives to a schema type
   */
  open class GQLDirective(val directive: Directive) extends StaticAnnotation

  object GQLDirective {
    def unapply(annotation: GQLDirective): Option[Directive] =
      Some(annotation.directive)
  }

}
